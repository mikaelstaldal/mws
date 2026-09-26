package nu.staldal.mws

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

/** Pure document/nonce gate; callbacks retain their epoch and cannot answer a successor document. */
internal class FormFillState {
    data class Document(val epoch: String, val url: String, val origin: String, val nonce: String)
    var document: Document? = null
        private set
    private var requested = false
    fun invalidate() { document = null; requested = false }
    fun commit(url: String): Document? {
        invalidate()
        val parsed = UrlPolicy.parseHttps(url) ?: return null
        return Document(UUID.randomUUID().toString(), url, parsed.origin,
            UUID.randomUUID().toString()).also { document = it }
    }
    fun accept(epoch: String, origin: String, mainFrame: Boolean, nonce: String): Document? {
        val current = document ?: return null
        if (!mainFrame || requested || current.epoch != epoch || current.origin != origin ||
            current.nonce != nonce) return null
        requested = true
        return current
    }
    fun current(candidate: Document): Boolean = document === candidate
}

internal interface FormFillBridge {
    val supported: Boolean
    fun add(name: String, origin: String, receive: (String, String, Boolean, (String) -> Unit) -> Unit)
    fun remove(name: String)
    fun inject(script: String)
}

private class WebKitFormFillBridge(private val view: WebView) : FormFillBridge {
    override val supported get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    override fun add(name: String, origin: String, receive: (String, String, Boolean, (String) -> Unit) -> Unit) {
        WebViewCompat.addWebMessageListener(view, name, setOf(origin)) { _, message, source, mainFrame, reply ->
            if (message.type == androidx.webkit.WebMessageCompat.TYPE_STRING) {
                message.data?.let { receive(it, source.toString(), mainFrame) { value -> reply.postMessage(value) } }
            }
        }
    }
    override fun remove(name: String) = WebViewCompat.removeWebMessageListener(view, name)
    override fun inject(script: String) = view.evaluateJavascript(script, null)
}

/** Only credential data travels in a reply; injected JavaScript contains no secrets. */
internal class FormFillEngine(
    private val webView: WebView,
    private val enabled: () -> Boolean,
    private val fetch: (FormFillState.Document, (MyPassClient.Outcome) -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val bridge: FormFillBridge = WebKitFormFillBridge(webView),
) {
    private val state = FormFillState()
    private val handler = Handler(Looper.getMainLooper())
    private var objectName: String? = null
    private var expiry: Runnable? = null
    private var committed = false
    private var prepared = false
    private val helper = webView.context.assets.open("form-fill.js").bufferedReader().use { it.readText() }

    init { navigate() }

    /** Called before loadUrl/accepted navigation, and onPageStarted for browser-initiated loads. */
    fun navigate(url: String? = null) {
        invalidate()
        committed = false
        prepared = true
        objectName?.let { bridge.remove(it) }
        objectName = null
        if (!enabled() || !bridge.supported) return
        val name = "mws_" + UUID.randomUUID().toString().replace("-", "")
        objectName = name
        val originRule = url?.let(UrlPolicy::parseHttps)?.origin ?: "https://mws-inactive.invalid"
        bridge.add(name, originRule) receive@{ payload, origin, mainFrame, reply ->
            if (objectName != name || !enabled()) return@receive
            val document = state.document ?: return@receive
            if (payload.length > 512) return@receive
            val data = try { JSONObject(payload) } catch (_: Exception) { return@receive }
            if (data.optString("type") != "ready") return@receive
            val accepted = state.accept(document.epoch, origin, mainFrame,
                data.optString("nonce")) ?: return@receive
            // Detection has a 10s budget; MyPass has its own bounded 60s lifetime.
            expiry?.let(handler::removeCallbacks)
            expiry = null
            fetch(accepted) { outcome ->
                try {
                    if (state.current(accepted) && enabled()) {
                        val data = JSONObject().put("nonce", accepted.nonce)
                        if (outcome is MyPassClient.Outcome.Credential) {
                            data.put("username", String(outcome.username)).put("password", String(outcome.password))
                        }
                        reply(data.toString())
                    }
                } catch (_: RuntimeException) {
                    // A disappeared renderer leaves the form available for manual entry after reload.
                } finally {
                    if (state.current(accepted)) invalidate()
                }
            }
        }
    }

    fun started(url: String) {
        // Listener injection is fixed when a load begins. Keep the pre-load registration.
        // Unobserved loads rotate here and may miss injection: manual entry remains available.
        if (!prepared) navigate(url)
        prepared = false
    }

    fun commit(url: String) {
        if (committed) return
        committed = true
        val name = objectName ?: return
        if (!enabled()) return
        val document = state.commit(url) ?: return
        expiry = Runnable { invalidate() }.also { handler.postDelayed(it, 10_000) }
        bridge.inject("$helper(${JSONObject.quote(name)}, ${JSONObject.quote(document.nonce)}, ${JSONObject.quote(url)});")
    }

    fun rejectDocument() {
        committed = true
        invalidate()
    }

    fun invalidate() {
        expiry?.let(handler::removeCallbacks)
        expiry = null
        state.invalidate()
        cancel()
    }
    fun close() {
        invalidate()
        objectName?.let { bridge.remove(it) }
        objectName = null
    }
    fun isCurrent(document: FormFillState.Document): Boolean = state.current(document) && enabled()
}
