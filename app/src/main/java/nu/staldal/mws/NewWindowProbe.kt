package nu.staldal.mws

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Strict new-window qualification through the production [NewWindowRouting] and [WindowProbeClient].
 * The target is cross-origin to the synthetic opener, so any state written for it is a probe leak.
 * An opener's own-origin writes through a blank window's handle are allowed: the platform performs
 * them before any embedder callback, and they need no authority the opener lacks (R§9.3).
 */
internal class NewWindowProbe(private val context: Context, private val profiles: ProfileManager) : ProviderProbe {
    override val name = "newWindow"
    override suspend fun run(): Boolean = diagnose() == null

    /** In-memory diagnostics contain case names and counters, never URLs, cookies or script text. */
    internal suspend fun diagnose(target: String = TARGET): String? = withContext(Dispatchers.Main) {
        val profile = profiles.createSelfTestProfile()
        try {
            val bound = profiles.createSelfTestWebView(context, profile)
            val parent = bound.webView
            val page = SyntheticProbePage()
            page.install(parent)
            parent.settings.setSupportMultipleWindows(true)
            page.load(parent)
            page.mark(parent)
            val cookies = WebViewCompat.getProfile(parent).cookieManager
            val targetOrigin = Uri.parse(target).buildUpon().path("/").clearQuery().fragment(null).build().toString()
            val ungestured = ungesturedFailure(parent, target)
            if (ungestured != null) return@withContext ungestured
            // Synthetic JavaScript has no human gesture; only this disposable test allows it and
            // passes the gesture explicitly. Provider gesture reporting itself is not qualified here.
            parent.settings.javaScriptCanOpenWindowsAutomatically = true
            val cases = listOf(
                Case("window.open_in_scope", "window.open('$target','_blank')", Outcome.Routed(true)),
                Case("target_blank_out_of_scope", "var a=document.createElement('a');a.href='$target';" +
                    "a.target='_blank';document.body.appendChild(a);a.click()", Outcome.Routed(false)),
                Case("blank", "window.open('about:blank','_blank')", Outcome.Rejected),
                Case("blank_mutation", "var w=window.open('about:blank','_blank');try{" +
                    "w.document.cookie='$OPENER_COOKIE;Path=/;Secure';" +
                    "w.localStorage.setItem('mws-self-test-marker','changed')}catch(e){}",
                    Outcome.Rejected, openerWrites = true),
                Case("blank_redirect", "var d=window.open('about:blank','_blank');if(d)d.location='$target'",
                    Outcome.Rejected),
                Case("blank_late", "window.__mwsLate=window.open('about:blank','_blank')", Outcome.Rejected,
                    late = "try{window.__mwsLate.location='$target'}catch(e){}"),
                Case("post", "var f=document.createElement('form');f.action='$target';f.method='POST';" +
                    "f.target='_blank';document.body.appendChild(f);f.submit()", Outcome.Rejected, post = true),
            )
            for (case in cases) {
                val failure = runCase(case, bound, profile, page, cookies, target, targetOrigin)
                if (failure != null) return@withContext "${case.name}: $failure"
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

    /** With the shell's setting, a script without a gesture reaches no window callback at all. */
    private suspend fun ungesturedFailure(parent: WebView, target: String): String? {
        parent.settings.javaScriptCanOpenWindowsAutomatically = false
        var windows = 0
        parent.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean {
                windows++
                return false
            }
        }
        val result = evaluate(parent, "(function(){return window.open('$target','_blank')===null})()")
        delay(SETTLE_MILLIS)
        parent.webChromeClient = WebChromeClient()
        return if (result != "true" || windows != 0) "ungestured: windows=$windows, nullHandle=${result == "true"}"
        else null
    }

    private suspend fun runCase(
        case: Case, parent: BoundWebView, profile: DisposableProfile, page: SyntheticProbePage,
        cookies: CookieManager, target: String, targetOrigin: String,
    ): String? {
        val child = profiles.createSelfTestWebView(context, profile)
        val pageCookiesBefore = cookies.getCookie(PAGE_ORIGIN)
        val targetCookiesBefore = cookies.getCookie(targetOrigin)
        val outcomes = mutableListOf<Outcome>()
        val first = CompletableDeferred<Outcome>()
        var windows = 0
        var creates = 0
        var releases = 0
        val events = ProbeEvents()
        val routing = NewWindowRouting(
            create = { creates++; child },
            owner = { parent },
            release = { releases++; profiles.release(it) },
            scope = { if (case.expected == Outcome.Routed(true)) targetOrigin else PAGE_ORIGIN },
            route = { url, inside ->
                val outcome = if (url == target) Outcome.Routed(inside) else Outcome.Mismatch
                outcomes += outcome; first.complete(outcome)
            },
            reject = { outcomes += Outcome.Rejected; first.complete(Outcome.Rejected) },
            client = { finish, cleartext -> events.wrap(WindowProbeClient(finish, cleartext)) },
        )
        try {
            parent.webView.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean {
                    windows++
                    return routing.open(view, true, message)
                }
            }
            parent.webView.evaluateJavascript(case.script, null)
            val observed = withTimeoutOrNull(OUTCOME_MILLIS) { first.await() }
            // Give an incorrectly dispatched load time to reveal itself, without allowing network bytes.
            delay(SETTLE_MILLIS)
            if (case.late != null) {
                parent.webView.evaluateJavascript(case.late, null)
                delay(SETTLE_MILLIS)
            }
            val pageCookies = cookies.getCookie(PAGE_ORIGIN).cookieSet()
            val marker = page.value(parent.webView)
            // Only the blank-mutation case's own writes are allowed; a provider may also block them.
            val allowedCookies = if (case.openerWrites) listOf(pageCookiesBefore.cookieSet(),
                pageCookiesBefore.cookieSet() + OPENER_COOKIE) else listOf(pageCookiesBefore.cookieSet())
            val allowedMarkers = if (case.openerWrites) setOf("\"present\"", "\"changed\"") else setOf("\"present\"")
            val pageChanged = pageCookies !in allowedCookies || marker !in allowedMarkers
            val targetChanged = cookies.getCookie(targetOrigin) != targetCookiesBefore
            val unexpectedInterception = events.intercepted.any { !(case.post && it == "POST") }
            val nonBlankStart = events.started.any { it != "about:blank" }
            val failed = windows != 1 || creates != 1 || releases != 1 || observed != case.expected ||
                outcomes.size != 1 || unexpectedInterception || nonBlankStart || targetChanged ||
                pageChanged
            return if (failed) "windows=$windows, creates=$creates, releases=$releases, " +
                "outcome=${observed?.label ?: "none"}, outcomes=${outcomes.size}, " +
                "intercepted=${events.intercepted.size}, unexpectedInterception=$unexpectedInterception, " +
                "started=${events.started.size}, nonBlankStart=$nonBlankStart, " +
                "targetStateChanged=$targetChanged, pageStateChanged=$pageChanged" else null
        } finally {
            parent.webView.webChromeClient = WebChromeClient()
            routing.close()
            profiles.release(child)
            if (case.openerWrites) {
                // Settle the removal before the next case snapshots the opener's cookies.
                withTimeoutOrNull(OUTCOME_MILLIS) {
                    suspendCancellableCoroutine { continuation ->
                        cookies.setCookie(PAGE_ORIGIN, "mws_window_mutation=; Path=/; Secure; Max-Age=0") {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                page.mark(parent.webView)
            }
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String? =
        withTimeoutOrNull(OUTCOME_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
            }
        }

    /** Records provider callbacks around the production client; it never changes their results. */
    private class ProbeEvents {
        /** Written on the provider's IO thread. */
        val intercepted = CopyOnWriteArrayList<String>()
        val started = mutableListOf<String>()
        fun wrap(production: WindowProbeClient) = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                production.shouldOverrideUrlLoading(view, request)
            @Deprecated("A URL-only callback cannot establish the request method")
            // OVERRIDE_DEPRECATION: the compiler ignores @Deprecated on an anonymous object's member.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                production.shouldOverrideUrlLoading(view, url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                intercepted += request.method
                return production.shouldInterceptRequest(view, request)
            }
            override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
                started += url
                production.onPageStarted(view, url, icon)
            }
            override fun onPageFinished(view: WebView, url: String) = production.onPageFinished(view, url)
        }
    }

    private sealed class Outcome(val label: String) {
        data class Routed(val inScope: Boolean) : Outcome(if (inScope) "in_scope" else "out_of_scope")
        data object Rejected : Outcome("rejected")
        data object Mismatch : Outcome("wrong_destination")
    }

    private class Case(
        val name: String, val script: String, val expected: Outcome,
        val openerWrites: Boolean = false, val late: String? = null, val post: Boolean = false,
    )

    companion object {
        private const val PAGE_ORIGIN = "https://mws-self-test.invalid/"
        private const val TARGET = "https://mws-self-test-target.invalid/window"
        private const val OUTCOME_MILLIS = 3_000L
        private const val SETTLE_MILLIS = 250L
        private const val OPENER_COOKIE = "mws_window_mutation=present"
        private fun String?.cookieSet(): Set<String> =
            orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
