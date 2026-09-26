package nu.staldal.mws

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Exercises the production helper and reply transport using local synthetic, non-secret data. */
internal class FormFillProbe(private val context: Context, private val profiles: ProfileManager) : ProviderProbe {
    override val name = "formFill"
    override suspend fun run(): Boolean = withContext(Dispatchers.Main) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return@withContext false
        val profile = profiles.createSelfTestProfile()
        var engine: FormFillEngine? = null
        try {
            val view = profiles.createSelfTestWebView(context, profile).webView
            view.layout(0, 0, 800, 600)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            var requests = 0
            engine = FormFillEngine(view, { true }, { _, callback ->
                requests++
                val credential = MyPassClient.Outcome.Credential("probe-user".toCharArray(), "probe-value".toCharArray())
                try { callback(credential) } finally { credential.clear() }
            }, {})
            val transport = engine
            val completed = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse =
                            WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(PAGE.toByteArray()))
                        override fun onPageStarted(v: WebView, url: String, icon: android.graphics.Bitmap?) { transport.started(url) }
                        override fun onPageCommitVisible(v: WebView, url: String) { transport.commit(url) }
                        override fun onPageFinished(v: WebView, url: String) {
                            transport.commit(url)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                    transport.navigate(URL)
                    view.loadUrl(URL)
                }
                // Poll the synthetic DOM to let asynchronous WebMessage replies complete.
                var passed = false
                while (!passed) {
                    passed = suspendCancellableCoroutine { continuation ->
                        view.evaluateJavascript("document.querySelector('#user').value === 'probe-user' && document.querySelector('#pass').value === 'probe-value' && window.events === 4 && window.submitted === false") {
                            if (continuation.isActive) continuation.resume(it == "true")
                        }
                    }
                    if (!passed) kotlinx.coroutines.delay(50)
                }
            }
            // Optional transport failure must not suppress successful isolation results.
            completed != null && requests == 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            false
        } finally {
            engine?.close()
            profiles.finishSelfTestProfile(profile)
        }
    }
    companion object {
        private const val URL = "https://mws-form-probe.invalid/login"
        private const val PAGE = """<html><body><form onsubmit="window.submitted=true;return false">
            <input id="user" autocomplete="username"><input id="pass" type="password" autocomplete="current-password">
            </form><script>window.events=0;window.submitted=false;
            document.querySelectorAll('input').forEach(e=>['input','change'].forEach(t=>e.addEventListener(t,()=>window.events++)));
            </script></body></html>"""
    }
}
