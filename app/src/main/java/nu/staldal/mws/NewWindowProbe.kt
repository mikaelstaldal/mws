package nu.staldal.mws

import android.content.Context
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Optional feature probe: any missing pre-dispatch callback disables all new-window routing. */
internal class NewWindowProbe(private val context: Context, private val profiles: ProfileManager) : ProviderProbe {
    override val name = "newWindow"
    override suspend fun run(): Boolean = diagnose() == null

    /** In-memory diagnostics contain case names and counters, never URLs, cookies or script text. */
    internal suspend fun diagnose(): String? = withContext(Dispatchers.Main) {
        val profile = profiles.createSelfTestProfile()
        try {
            val parent = profiles.createSelfTestWebView(context, profile).webView
            val page = SyntheticProbePage()
            page.install(parent)
            parent.settings.setSupportMultipleWindows(true)
            // Synthetic JavaScript has no human gesture; only this disposable test allows it.
            parent.settings.javaScriptCanOpenWindowsAutomatically = true
            page.load(parent)
            page.mark(parent)
            val cookies = WebViewCompat.getProfile(parent).cookieManager
            val before = cookies.getCookie(TARGET)
            for ((index, testCase) in listOf(
                "window.open('$TARGET','_blank')" to TARGET,
                "var a=document.createElement('a');a.href='$TARGET';a.target='_blank';document.body.appendChild(a);a.click()" to TARGET,
                "var w=window.open('about:blank','_blank');try{w.document.cookie='mws_window_mutation=present;Path=/;Secure';w.localStorage.setItem('mws-self-test-marker','changed')}catch(e){}" to null,
                "var delayed=window.open('about:blank','_blank');if(delayed)delayed.location='$TARGET'" to null,
                "var f=document.createElement('form');f.action='$TARGET';f.method='POST';f.target='_blank';document.body.appendChild(f);f.submit()" to null,
            ).withIndex()) {
                val (script, expected) = testCase
                val caseName = listOf("window.open", "target_blank", "blank_mutation", "blank_redirect", "post")[index]
                val child = profiles.createSelfTestWebView(context, profile)
                try {
                    configureWindowProbe(child.webView)
                    val intercepted = AtomicInteger()
                    val started = AtomicInteger()
                    var windows = 0
                    val observed = withTimeoutOrNull(3_000) {
                        suspendCancellableCoroutine<String?> { continuation ->
                            val production = WindowProbeClient(finish = { url ->
                                if (continuation.isActive) continuation.resume(url)
                            })
                            child.webView.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                    production.shouldOverrideUrlLoading(view, request)
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                    intercepted.incrementAndGet()
                                    return production.shouldInterceptRequest(view, request)
                                }
                                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                                    started.incrementAndGet()
                                    production.onPageStarted(view, url, icon)
                                }
                                override fun onPageFinished(view: WebView, url: String) = production.onPageFinished(view, url)
                            }
                            parent.webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean {
                                    if (++windows != 1) return false
                                    (message.obj as WebView.WebViewTransport).webView = child.webView
                                    message.sendToTarget()
                                    return true
                                }
                            }
                            parent.evaluateJavascript(script, null)
                        }
                    }
                    // Give an incorrectly dispatched load time to reveal itself, without allowing network bytes.
                    delay(250)
                    val cookieChanged = cookies.getCookie(TARGET) != before
                    val storageChanged = page.value(parent) != "\"present\""
                    if (windows != 1 || observed != expected || intercepted.get() != 0 || started.get() != 0 ||
                        cookieChanged || storageChanged) return@withContext (
                        "$caseName: windows=$windows, destinationMatches=${observed == expected}, " +
                            "intercepted=${intercepted.get()}, started=${started.get()}, " +
                            "cookieChanged=$cookieChanged, storageChanged=$storageChanged")
                } finally {
                    parent.webChromeClient = WebChromeClient()
                    profiles.release(child)
                }
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            "probe_exception: ${failure.javaClass.simpleName}"
        } finally {
            profiles.finishSelfTestProfile(profile)
        }
    }
    companion object { private const val TARGET = "https://mws-self-test.invalid/window" }
}
