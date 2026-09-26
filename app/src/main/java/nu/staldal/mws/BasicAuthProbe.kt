package nu.staldal.mws

import android.content.Context
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Verifies, on a network-blocked synthetic HTTPS page, the provider callbacks the Basic-auth gate
 * (A§11) relies on, replayed through the production [BasicAuthGate]:
 *
 * - the app's own load reports the main document to `shouldInterceptRequest` with the exact
 *   recorded URL before commit, so it does not disarm the gate;
 * - subresource and iframe requests reach `shouldInterceptRequest` as non-main-frame requests and
 *   disarm it;
 * - a page-initiated main-frame navigation reaches `shouldOverrideUrlLoading` before its request.
 *
 * Challenge delivery itself needs a real server and is covered per floor version by the
 * compatibility suite (A§15). A failed probe makes the shell cancel every challenge.
 */
internal class BasicAuthProbe(private val context: Context, private val profiles: ProfileManager) : ProviderProbe {
    override val name = "basicAuth"

    private data class Event(val kind: String, val url: String, val mainFrame: Boolean = true,
                             val redirect: Boolean = false)

    override suspend fun run(): Boolean = withContext(Dispatchers.Main) {
        val profile = profiles.createSelfTestProfile()
        try {
            val view = profiles.createSelfTestWebView(context, profile).webView
            view.layout(0, 0, 800, 600)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            val events = mutableListOf<Event>()
            fun record(event: Event) = synchronized(events) { events += event }
            fun snapshot() = synchronized(events) { events.toList() }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url.toString()
                    record(Event("intercept", url, request.isForMainFrame))
                    val (type, body) = when (url) {
                        FIRST -> "text/html" to PAGE
                        NEXT, FRAME -> "text/html" to "<html><body>probe</body></html>"
                        else -> "image/gif" to ""
                    }
                    return WebResourceResponse(type, "UTF-8", ByteArrayInputStream(body.toByteArray()))
                }
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    record(Event("override", request.url.toString(), request.isForMainFrame, request.isRedirect))
                    return false
                }
                override fun onPageCommitVisible(v: WebView, url: String) = record(Event("commit", url))
                override fun onPageFinished(v: WebView, url: String) = record(Event("finished", url))
                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    record(Event("gone", ""))
                    return true
                }
                override fun onReceivedHttpAuthRequest(v: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                    record(Event("auth", host.orEmpty()))
                    handler.cancel()
                }
            }
            view.loadUrl(FIRST)
            if (!awaitEvents(::snapshot) { it.any { e -> e.kind == "finished" && e.url == FIRST } &&
                    it.any { e -> e.kind == "intercept" && e.url == PIXEL } &&
                    it.any { e -> e.kind == "intercept" && e.url == FRAME } }) return@withContext false
            view.evaluateJavascript("location.href = ${JSONObject.quote(NEXT)};", null)
            if (!awaitEvents(::snapshot) { it.any { e -> e.kind == "finished" && e.url == NEXT } }) {
                return@withContext false
            }
            replay(snapshot())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            false
        } finally {
            profiles.finishSelfTestProfile(profile)
        }
    }

    private suspend fun awaitEvents(events: () -> List<Event>, condition: (List<Event>) -> Boolean): Boolean =
        withTimeoutOrNull(10_000) {
            while (!condition(events())) {
                if (events().any { it.kind == "gone" }) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } == true

    /** Runs the recorded provider callbacks through the production gate, as the shell would. */
    private fun replay(events: List<Event>): Boolean {
        if (events.any { it.kind == "auth" }) return false
        // Subresources and iframes must be reported as non-main-frame requests.
        if (events.none { it.kind == "intercept" && it.url == PIXEL && !it.mainFrame } ||
            events.none { it.kind == "intercept" && it.url == FRAME && !it.mainFrame }) return false
        val gate = BasicAuthGate()
        gate.arm(FIRST) ?: return false
        var firstMain = false
        var nextArmed = false
        var nextMain = false
        for (event in events) when (event.kind) {
            "intercept" -> {
                val disarmed = gate.intercepted(event.url)
                if (event.mainFrame && event.url == FIRST && !firstMain) {
                    // The app's own load must reach the provider with exactly the recorded URL.
                    if (disarmed || gate.current?.documentRequested != true) return false
                    firstMain = true
                } else if (event.mainFrame && event.url == NEXT) {
                    // A page-initiated navigation must be armed before its request is dispatched.
                    if (!nextArmed || disarmed || gate.current?.documentRequested != true) return false
                    nextMain = true
                }
            }
            "override" -> if (event.mainFrame && !(event.url == FIRST && !firstMain)) {
                if (event.url != NEXT || event.redirect) return false
                gate.arm(event.url) ?: return false
                nextArmed = true
            }
            "commit", "finished" -> {
                // Commit must not precede the main document's own request.
                if (event.url == FIRST && !firstMain || event.url == NEXT && !nextMain) return false
                gate.disarm()
            }
        }
        return firstMain && nextArmed && nextMain
    }

    companion object {
        private const val ORIGIN = "https://mws-auth-probe.invalid"
        private const val FIRST = "$ORIGIN/first"
        private const val NEXT = "$ORIGIN/next"
        private const val FRAME = "$ORIGIN/frame"
        private const val PIXEL = "$ORIGIN/pixel.gif"
        private const val PAGE = """<html><body><img src="/pixel.gif"><iframe src="/frame"></iframe></body></html>"""
    }
}
