package nu.staldal.mws

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Only the request-bearing, synchronous override callback can authorize a destination. */
internal class WindowDestination {
    private val observed = AtomicBoolean(false)
    private val dispatched = AtomicBoolean(false)
    fun afterDispatch() { dispatched.set(true); observed.set(true) }
    fun beforeLoad(url: String, method: String, mainFrame: Boolean): String? {
        if (!observed.compareAndSet(false, true) || dispatched.get() || method != "GET" || !mainFrame) return null
        return UrlPolicy.parseNavigation(url)?.serialized
    }
}

/** No script, storage, network or visible surface is enabled in the temporary view. */
internal class WindowProbeClient(
    private val finish: (String?) -> Unit,
    private val cleartext: () -> Unit = {},
) : WebViewClient() {
    private val destination = WindowDestination()
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.url.scheme.equals("http", ignoreCase = true)) cleartext()
        finish(destination.beforeLoad(request.url.toString(), request.method, request.isForMainFrame))
        return true
    }
    @Deprecated("A URL-only callback cannot establish the request method")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        destination.afterDispatch()
        finish(null)
        return true
    }
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        destination.afterDispatch()
        view.post {
            if (request.url.scheme.equals("http", ignoreCase = true)) cleartext()
            finish(null)
        }
        // A local 204 neither touches the network nor commits a document for the destination.
        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(),
            ByteArrayInputStream(byteArrayOf()))
    }
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        destination.afterDispatch()
        finish(null)
    }
    override fun onPageFinished(view: WebView, url: String) { finish(null) }
}

internal fun configureWindowProbe(view: WebView) {
    view.isSaveEnabled = false
    view.settings.apply {
        blockNetworkLoads = true
        javaScriptEnabled = false
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    view.setDownloadListener { _, _, _, _, _ -> }
}

/** Activity-owned; rotation, replacement and close cancel all pending results. */
internal class NewWindowRouting(
    private val create: (BoundWebView) -> BoundWebView?,
    private val owner: () -> BoundWebView?,
    private val release: (BoundWebView) -> Unit,
    private val scope: () -> String?,
    private val route: (String, Boolean) -> Unit,
    private val reject: (Int) -> Unit,
    private val client: (finish: (String?) -> Unit, cleartext: () -> Unit) -> WebViewClient = ::WindowProbeClient,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<BoundWebView>()
    private var closed = false

    fun open(source: WebView, gesture: Boolean, message: Message): Boolean {
        if (closed || !gesture || pending.isNotEmpty()) return false
        val parent = owner()?.takeIf { it.webView === source } ?: return false
        val transport = message.obj as? WebView.WebViewTransport ?: return false
        val probe = try { create(parent) } catch (_: RuntimeException) { null }
        if (probe == null) { reject(R.string.shell_url_rejected); return false }
        pending.add(probe)
        var complete = false
        var rejection = R.string.shell_url_rejected
        lateinit var timeout: Runnable
        fun finish(url: String?) {
            if (complete) return
            complete = true
            handler.removeCallbacks(timeout)
            probe.webView.stopLoading()
            // Return from the provider callback before destroying its view or navigating the parent.
            handler.post {
                if (!pending.remove(probe)) return@post
                release(probe)
                if (closed || owner() !== parent) return@post
                val target = url?.let(UrlPolicy::parseNavigation)
                val appScope = scope()?.let(UrlPolicy::parseHttps)
                if (target == null || appScope == null) reject(rejection)
                else route(target.serialized, UrlPolicy.isWithinScope(target, appScope))
            }
        }
        timeout = Runnable { finish(null) }
        try {
            configureWindowProbe(probe.webView)
            probe.webView.webViewClient = client(::finish) { rejection = R.string.shell_https_required }
            handler.postDelayed(timeout, 2_000)
            transport.webView = probe.webView
            message.sendToTarget()
        } catch (_: RuntimeException) {
            finish(null)
            return false
        }
        return true
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        pending.toList().forEach { release(it) }
        pending.clear()
    }
}
