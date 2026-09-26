package nu.staldal.mws

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

private const val PROBE_URL = "https://mws-self-test.invalid/probe"
private const val MARKER = "mws-self-test-marker"
private const val COOKIE_MARKER = "mws_probe=present"
private const val SUITE_REVISION = "suite-revision-4"

/** No URLs or credentials are persisted. Absent feature keys fail closed. */
internal object SelfTestEncoding {
    fun key(key: ProviderKey): String = JSONObject().apply {
        put("package", key.packageName)
        put("version", key.version)
        put("api", key.apiLevel)
        put("abi", key.primaryAbi)
    }.toString()

    fun decode(key: ProviderKey, stored: RuntimeSelfTest?): ProviderSelfTestResult? {
        if (stored?.matchKey != this.key(key)) return null
        val flags = stored.features
        if (flags[SUITE_REVISION] != true) return null
        return ProviderSelfTestResult(key, flags["isolation"] == true, flags["deletion"] == true,
            flags["basicAuth"] == true, flags["newWindow"] == true,
            flags["formFill"] == true, flags["discoveryCookies"] == true)
    }
}

/** A new feature task can add a probe without changing the cache or compatibility gate. */
internal interface ProviderProbe {
    val name: String
    suspend fun run(): Boolean
}

/** Serial caller is ShellServices.awaitStartup; cached results run once per match key. */
internal class ProviderSelfTestRunner(
    private val source: ProviderSnapshotSource,
    private val store: MaintenanceStore,
    private val probes: List<ProviderProbe>,
) {
    suspend fun runIfNeeded() {
        val snapshot = source.snapshot()
        val key = snapshot.key ?: return
        if (SelfTestEncoding.decode(key, withContext(Dispatchers.IO) { store.selfTest() }) != null) return
        if (!snapshot.multiProfile) return
        val results = mutableMapOf<String, Boolean>()
        var incomplete = false
        for (probe in probes) {
            results[probe.name] = try { probe.run() } catch (_: TimeoutCancellationException) {
                incomplete = true
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                incomplete = true
                false
            }
        }
        // An exception or timeout did not establish provider behavior; retry on the next start.
        if (incomplete) return
        // A provider may update while probes run. Never save results under the newer key.
        if (source.snapshot() != snapshot) return
        results[SUITE_REVISION] = true
        withContext(Dispatchers.IO) {
            store.putSelfTest(RuntimeSelfTest(SelfTestEncoding.key(key), results))
        }
    }
}

/** Synthetic HTTPS document. Every request receives a local response, including unexpected URLs. */
internal class SyntheticProbePage {
    fun install(webView: WebView) {
        webView.settings.blockNetworkLoads = true
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
    }

    suspend fun load(webView: WebView) = withTimeout(10_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val body = if (request.url.toString() == PROBE_URL) "<html><body>probe</body></html>" else ""
                    return WebResourceResponse("text/html", "UTF-8",
                        ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == PROBE_URL && continuation.isActive) continuation.resume(Unit)
                }
            }
            webView.loadUrl(PROBE_URL)
        }
        check(evaluate(webView,
            "location.href === '$PROBE_URL' && document.body && document.body.textContent.trim() === 'probe'") == "true") {
            "Synthetic probe document did not load"
        }
    }

    suspend fun value(webView: WebView): String = evaluate(webView,
        "(function(){try{var v=localStorage.getItem('$MARKER');return v===null?'absent':v}" +
            "catch(e){return 'error'}})()")

    suspend fun mark(webView: WebView) {
        check(evaluate(webView,
            "(function(){try{localStorage.setItem('$MARKER','present');return 'stored'}" +
                "catch(e){return 'error'}})()") == "\"stored\"") {
            "Synthetic probe marker could not be stored"
        }
    }

    suspend fun markCookie(webView: WebView) {
        val cookies = WebViewCompat.getProfile(webView).cookieManager
        val accepted = withTimeout(5_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                cookies.setCookie(PROBE_URL, "$COOKIE_MARKER; Path=/; Secure; SameSite=Strict") {
                    if (continuation.isActive) continuation.resume(it)
                }
            }
        }
        check(accepted) { "Synthetic probe cookie was rejected" }
        cookies.flush()
        check(hasCookie(webView)) { "Synthetic probe cookie was not stored" }
    }

    fun hasCookie(webView: WebView): Boolean =
        WebViewCompat.getProfile(webView).cookieManager.getCookie(PROBE_URL)
            ?.split(';')?.any { it.trim() == COOKIE_MARKER } == true

    private suspend fun evaluate(webView: WebView, script: String): String = withTimeout(5_000) {
        suspendCancellableCoroutine<String> { continuation ->
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }
}

internal class IsolationProbe(private val context: Context, private val profiles: ProfileManager) : ProviderProbe {
    override val name = "isolation"
    override suspend fun run(): Boolean = withContext(Dispatchers.Main) {
        val a = profiles.createSelfTestProfile()
        val b = profiles.createSelfTestProfile()
        try {
            val page = SyntheticProbePage()
            val first = profiles.createSelfTestWebView(context, a).webView
            page.install(first)
            page.load(first)
            page.mark(first)
            if (page.value(first) != "\"present\"") return@withContext false
            val second = profiles.createSelfTestWebView(context, b).webView
            page.install(second)
            page.load(second)
            page.value(second) == "\"absent\""
        } finally {
            profiles.finishSelfTestProfile(a)
            profiles.finishSelfTestProfile(b)
        }
    }
}

internal class DeletionProbe(private val context: Context) : ProviderProbe {
    override val name = "deletion"
    internal enum class Outcome { PASSED, DELETE_DEFERRED,
        MARKER_NOT_PERSISTED, PROFILE_NOT_PERSISTED, DELETE_FAILED, DELETE_ABSENT, MARKER_SURVIVED,
        CLEANUP_DEFERRED, CLEANUP_FAILED, CLEANUP_ABSENT, INCOMPLETE }

    override suspend fun run(): Boolean = when (diagnose()) {
        Outcome.PASSED -> true
        Outcome.INCOMPLETE, Outcome.MARKER_NOT_PERSISTED, Outcome.PROFILE_NOT_PERSISTED,
        Outcome.DELETE_ABSENT,
        Outcome.CLEANUP_DEFERRED, Outcome.CLEANUP_FAILED, Outcome.CLEANUP_ABSENT ->
            error("Deletion probe did not establish provider behavior")
        else -> false
    }

    /** The outcome is kept in memory for the device test; only pass/fail is persisted. */
    internal suspend fun diagnose(): Outcome = SelfTestProcessService.probe(context)
}
