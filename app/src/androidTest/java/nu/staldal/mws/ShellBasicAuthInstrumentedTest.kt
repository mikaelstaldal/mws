package nu.staldal.mws

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A§14.2 Basic auth, against the installed provider: the shell's gate answers only the current
 * top-level navigation (including same-origin redirects) with a MyPass lookup of the exact URL and
 * realm, once. Cross-origin redirects, iframes and subresources are cancelled.
 */
@RunWith(AndroidJUnit4::class)
class ShellBasicAuthInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var database: ConfigurationDatabase
    private lateinit var store: MaintenanceStore
    private lateinit var services: ShellServices
    private lateinit var server: LocalHttpsServer
    private lateinit var other: LocalHttpsServer
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val otherRequests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val apps = mutableListOf<String>()
    private val myPassReady = java.util.concurrent.CountDownLatch(1)
    private var basicAuthProbe = true
    private var diagnosticView: WebView? = null
    private var diagnosticMyPass: FakeMyPass? = null

    @Before fun setUp() {
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        val key = snapshot.key
        assumeTrue(key != null && snapshot.multiProfile)
        server = LocalHttpsServer()
        other = LocalHttpsServer()
        other.dispatch { request ->
            otherRequests += (request.path ?: "") to request.getHeader("Authorization")
            challenge("Top")
        }
        server.dispatch { request ->
            val path = request.path ?: ""
            val authorization = request.getHeader("Authorization")
            requests += path to authorization
            if (path in listOf("/top", "/redirect", "/cross", "/frame-page", "/image-page", "/worker-page")) {
                check(myPassReady.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
            when (path) {
                "/top" -> if (authorization == AUTHORIZATION) html("<p id=ok>ok</p>") else challenge("Top")
                "/redirect" -> MockResponse().setResponseCode(302).setHeader("Location", "/top")
                "/cross" -> MockResponse().setResponseCode(302).setHeader("Location", other.url.resolve("/top").toString())
                "/frame-page" -> html("<iframe src=\"/protected-frame\"></iframe>")
                "/image-page" -> html("<img src=\"/protected-image\">")
                "/worker-page" -> html("<script>navigator.serviceWorker.register('/workers/worker.js')</script>")
                "/workers/worker.js" -> MockResponse().setHeader("Content-Type", "application/javascript")
                    .setBody("self.addEventListener('activate', e => e.waitUntil(fetch('/protected-worker')));")
                "/protected-worker" -> challenge("Worker")
                "/protected-frame" -> challenge("Frame")
                "/protected-image" -> challenge("Image")
                else -> html("<p>app</p>")
            }
        }
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        store = MaintenanceStore(context)
        val repository = ConfigurationRepository(database)
        val compatibility = ProviderCompatibility({ snapshot },
            { k -> ProviderSelfTestResult(k, true, true, basicAuthProbe, true, true, true) },
            floors = mapOf(key!!.packageName to listOf(0)))
        val shells = LiveShellRegistry()
        val icons = IconStore(context)
        val profiles = ProfileManager(repository, store, compatibility, AndroidWebProfileBackend(),
            shells, object : AppNativeData {
                override fun deleteIcons(appId: String) = icons.delete(appId)
                override fun widgetsChanged() {}
            })
        services = ShellServices(repository, compatibility, shells, profiles, icons)
        ShellServices.install(services)
    }

    @After fun tearDown() {
        myPassReady.countDown()
        if (!::services.isInitialized) return
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ShellActivity>().forEach { it.finishAndRemoveTask() }
        }
        runBlocking { apps.forEach { services.profiles.remove(it) } }
        ShellServices.install(null)
        server.close()
        other.close()
        database.close()
        store.close()
    }

    @Test fun topLevelChallengeLooksUpExactUrlAndRealmAndSuppliesOnce() {
        val (webView, myPass) = openShell(path = "/top")
        await("authenticated document contains ok marker") { evaluate(webView, "document.getElementById('ok') !== null") == "true" }
        assertEquals(listOf(at("/top") to "Top"), myPass.lookups)
        assertEquals(1, requests.count { it == "/top" to AUTHORIZATION })
    }

    @Test fun rejectedCredentialIsNotSuppliedAgain() {
        val (webView, myPass) = openShell(password = "wrong", path = "/top")
        await("credential rejection produces second /top request") { requests.count { it.first == "/top" } >= 2 }
        SystemClock.sleep(1500)
        assertEquals(1, myPass.lookups.size)
        assertEquals(1, requests.count { it.first == "/top" && it.second != null })
    }

    @Test fun sameOriginRedirectIsLookedUpWithTheRedirectedUrl() {
        val (webView, myPass) = openShell(path = "/redirect")
        await("authenticated document contains ok marker") { evaluate(webView, "document.getElementById('ok') !== null") == "true" }
        assertEquals(listOf(at("/top") to "Top"), myPass.lookups)
    }

    @Test fun crossOriginRedirectCannotInheritTheNavigation() {
        val (webView, myPass) = openShell(path = "/cross")
        await("cross-origin redirect reaches second listener") { otherRequests.isNotEmpty() }
        SystemClock.sleep(1500)
        assertEquals(emptyList<Pair<String, String?>>(), myPass.lookups)
        assertTrue(otherRequests.all { it.second == null })
    }

    @Test fun iframeChallengeIsCancelledWithoutAnotherNavigation() {
        val (webView, myPass) = openShell(path = "/frame-page")
        await("iframe request reaches listener") { requests.any { it.first == "/protected-frame" } }
        await("iframe fixture completes") { fixtureDocumentReady(webView, at("/frame-page")) }
        SystemClock.sleep(1500)
        assertEquals(emptyList<Pair<String, String?>>(), myPass.lookups)
        assertTrue(requests.all { it.second == null })
    }

    @Test fun imageChallengeIsCancelledWithoutAnotherNavigation() {
        val (webView, myPass) = openShell(path = "/image-page")
        await("image request reaches listener") { requests.any { it.first == "/protected-image" } }
        await("image fixture completes") { fixtureDocumentReady(webView, at("/image-page")) }
        SystemClock.sleep(1500)
        assertEquals(emptyList<Pair<String, String?>>(), myPass.lookups)
        assertTrue(requests.all { it.second == null })
    }

    /** Keep the overlapping case: request arrival is deliberately not challenge completion. */
    @Test fun iframeAndSubresourceChallengesAreCancelled() {
        val (webView, myPass) = openShell(path = "/frame-page")
        await("iframe request reaches listener") { requests.any { it.first == "/protected-frame" } }
        navigate(webView, at("/image-page"))
        await("image request reaches listener") { requests.any { it.first == "/protected-image" } }
        SystemClock.sleep(1500)
        assertEquals(emptyList<Pair<String, String?>>(), myPass.lookups)
        assertTrue(requests.all { it.second == null })
    }

    @Test fun serviceWorkerRequestRetiresEligibilityAcrossViewReplacement() {
        val (webView, myPass) = openShell(path = "/worker-page")
        await("worker network request reaches listener") { requests.any { it.first == "/protected-worker" } }
        val activity = onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ShellActivity>().first { it.holder()?.bound?.webView === webView }
        }
        val bound = onMain { activity.holder()!!.bound!! }
        assertTrue(!bound.basicAuthSafe())
        val replacement = runBlocking {
            (services.profiles.createAppWebView(context, ShellActivity.appIdOf(activity.intent)!!) as AttachResult.Attached).bound
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var replacementAuth: ShellBasicAuth? = null
        try {
            assertTrue(!replacement.basicAuthSafe())
            onMain {
                val auth = ShellBasicAuth({ true }, { activity.myPass }, "worker-replacement",
                    eligible = replacement.basicAuthSafe, onLookup = {}, onLookupEnded = {}, onError = {})
                replacementAuth = auth
                replacement.webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        auth.intercepted(request.url.toString(), request.isForMainFrame)
                        return null
                    }
                    override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler,
                        host: String?, realm: String?) {
                        auth.challenge(handler.asAnswer(), host, realm)
                        cancelled.set(true)
                    }
                }
                auth.arm(at("/top"))
                replacement.webView.loadUrl(at("/top"))
            }
            await("replacement's top-level challenge is cancelled") { cancelled.get() }
        } finally {
            onMain { replacementAuth?.close() }
            runBlocking { services.profiles.release(replacement) }
        }
        SystemClock.sleep(1500)
        assertTrue(myPass.lookups.isEmpty())
        assertTrue(requests.all { it.second == null })
    }

    @Test fun laterTopLevelChallengeIsCancelledAndReloadDoesNotRearm() {
        val (webView, myPass) = openShell()
        navigate(webView, at("/top"))
        await("later challenge is cancelled") { fixtureDocumentReady(webView, at("/top")) }
        onMain { webView.reload() }
        await("reload reaches listener") { requests.count { it.first == "/top" } >= 2 }
        SystemClock.sleep(1500)
        assertTrue(myPass.lookups.isEmpty())
        assertTrue(requests.all { it.second == null })
    }

    @Test fun failedProbeCancelsEveryChallenge() {
        basicAuthProbe = false
        val (webView, myPass) = openShell()
        navigate(webView, at("/top"))
        await("unauthenticated /top request reaches listener") { requests.any { it.first == "/top" } }
        SystemClock.sleep(1500)
        assertEquals(emptyList<Pair<String, String?>>(), myPass.lookups)
        assertTrue(requests.all { it.second == null })
    }

    /** Answers every MyPass launch at once, recording only the non-secret lookup keys. */
    private class FakeMyPass(private val password: String) : ActivityResultRegistry() {
        val lookups = CopyOnWriteArrayList<Pair<String?, String?>>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                     options: ActivityOptionsCompat?) {
            val intent = input as Intent
            lookups += intent.getStringExtra(MyPassClient.EXTRA_URL) to intent.getStringExtra(MyPassClient.EXTRA_REALM)
            Handler(Looper.getMainLooper()).post {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent()
                    .putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "user")
                    .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, password))
            }
        }
    }

    private fun openShell(password: String = "secret", path: String = "/app/"): Pair<WebView, FakeMyPass> {
        val id = UUID.randomUUID().toString()
        val url = at(path)
        runBlocking {
            services.repository.add(ConfiguredApp(id, "Auth", NameSource.USER_EDITED, url, null, url, url,
                createdAt = 1, updatedAt = 1))
        }
        apps += id
        context.startActivity(ShellActivity.intent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var found: Pair<ShellActivity, WebView>? = null
        await("shell attached at initial URL: $url") {
            found = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ShellActivity>()
                    .firstOrNull { ShellActivity.appIdOf(it.intent) == id }
                    ?.let { activity -> activity.holder()?.bound?.webView?.let { activity to it } }
            }
            diagnosticView = found?.second
            found != null
        }
        val (activity, webView) = found!!
        diagnosticView = webView
        val myPass = FakeMyPass(password)
        diagnosticMyPass = myPass
        onMain {
            activity.myPass.close()
            activity.myPass = MyPassClient(activity, availabilityProbe = { true }, registry = myPass)
        }
        myPassReady.countDown()
        if (path == "/app/") {
            await("initial fixture ready: $url") { fixtureDocumentReady(webView, url) }
            requests.clear()
        }
        return webView to myPass
    }

    /** A page-initiated main-frame navigation, armed in `shouldOverrideUrlLoading`. */
    private fun navigate(webView: WebView, url: String) =
        onMain { webView.evaluateJavascript("location.href = '$url'", null) }

    private fun evaluate(webView: WebView, script: String): String? {
        val result = AtomicReference<String?>()
        val done = java.util.concurrent.CountDownLatch(1)
        onMain { webView.evaluateJavascript(script) { result.set(it); done.countDown() } }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    private fun at(path: String) = server.url.resolve(path)!!.toString()

    private fun html(body: String) =
        MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body><span id=fixture-ready></span>$body</body></html>")

    private fun challenge(realm: String) = MockResponse().setResponseCode(401)
        .setHeader("WWW-Authenticate", "Basic realm=\"$realm\"")
        .setHeader("Content-Type", "text/html").setBody("<html><body><span id=fixture-ready></span>denied</body></html>")

    private fun ShellActivity.holder(): ShellHolder? =
        ShellActivity::class.java.getDeclaredField("holder").apply { isAccessible = true }
            .get(this) as ShellHolder?

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun await(description: String = "expected auth response or request", condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description; " +
            "page=${diagnosticView?.let(::fixtureDocumentState)}; " +
            "requests=${requests.map { (path, auth) -> path to (auth != null) }}; " +
            "otherRequests=${otherRequests.map { (path, auth) -> path to (auth != null) }}; " +
            "lookups=${diagnosticMyPass?.lookups}; basicAuthProbe=$basicAuthProbe")
    }

    private companion object {
        /** `user:secret`, only in this test fixture. */
        const val AUTHORIZATION = "Basic dXNlcjpzZWNyZXQ="
    }
}
