package nu.staldal.mws

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A§14.2 navigation and transport: cleartext is refused before any request byte leaves the device,
 * blocked schemes are never passed to Android, and out-of-scope HTTPS stays in the shell.
 */
@RunWith(AndroidJUnit4::class)
class ShellNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var database: ConfigurationDatabase
    private lateinit var store: MaintenanceStore
    private lateinit var services: ShellServices
    private lateinit var server: LocalHttpsServer
    private lateinit var cleartext: ServerSocket
    private val cleartextConnections = AtomicInteger()
    private val requestedPaths = mutableListOf<String>()
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val apps = mutableListOf<String>()
    private var diagnosticView: WebView? = null

    @Before fun setUp() {
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        val key = snapshot.key
        assumeTrue(key != null && snapshot.multiProfile)
        cleartext = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!cleartext.isClosed) {
                runCatching { cleartext.accept() }.getOrNull()?.use { cleartextConnections.incrementAndGet() }
            }
        }
        val http = "http://127.0.0.1:${cleartext.localPort}/leak"
        server = LocalHttpsServer()
        server.dispatch { request ->
            synchronized(requestedPaths) { requestedPaths += request.path ?: "" }
            when (request.path) {
                "/redirect" -> MockResponse().setResponseCode(302).setHeader("Location", http)
                "/download" -> MockResponse().setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Disposition", "attachment; filename=a.bin").setBody("x")
                else -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <html><body><span id="fixture-ready"></span>
                    <form id="get" method="get" action="$http"><input name="q" value="1"></form>
                    <form id="post" method="post" action="$http"><input name="q" value="1"></form>
                    </body></html>""".trimIndent())
            }
        }
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        store = MaintenanceStore(context)
        val repository = ConfigurationRepository(database)
        val compatibility = ProviderCompatibility({ snapshot },
            { k -> ProviderSelfTestResult(k, true, true, true, true, true, true) },
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
        if (!::services.isInitialized) return
        monitor?.let(instrumentation::removeMonitor)
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ShellActivity>().forEach { it.finishAndRemoveTask() }
        }
        runBlocking { apps.forEach { services.profiles.remove(it) } }
        ShellServices.install(null)
        server.close()
        cleartext.close()
        database.close()
        store.close()
    }

    @Test fun cleartextLinksFormsAndRedirectsTransmitZeroBytes() {
        val (_, webView) = openShell()
        val page = at("/app/")
        val http = "http://127.0.0.1:${cleartext.localPort}/leak"
        for (script in listOf("location.href = '$http'", "document.getElementById('get').submit()",
            "document.getElementById('post').submit()", "location.href = '${at("/redirect")}'",
            "window.open('$http')")) {
            if (onMain { webView.url } != page) onMain { webView.loadUrl(page) }
            await("fixture ready after reset: $page") { fixtureDocumentReady(webView, page) }
            evaluate(webView, script)
            SystemClock.sleep(1500)
            assertEquals(script, 0, cleartextConnections.get())
        }
    }

    @Test fun blockedAndUngesturedSchemesAreNeverPassedToAndroid() {
        val (activity, webView) = openShell()
        monitor = instrumentation.addMonitor(null as String?, null, true)
        for (url in listOf("intent://scan/#Intent;scheme=zxing;end", "mailto:a@example.com", "tel:123",
            "market://details?id=x", "file:///sdcard/a", "content://x/y", "data:text/html,x")) {
            evaluate(webView, "location.href = '$url'")
            SystemClock.sleep(500)
        }
        assertEquals(0, monitor!!.hits)
        assertFalse(activity.isFinishing)
        assertEquals(at("/app/"), onMain { webView.url })
    }

    @Test fun outOfScopeHttpsStaysInTheShellAndDownloadsAreNotDelegated() {
        val (activity, webView) = openShell()
        monitor = instrumentation.addMonitor(null as String?, null, true)
        val other = at("/elsewhere")
        evaluate(webView, "location.href = '$other'")
        await("out-of-scope fixture ready: $other") { fixtureDocumentReady(webView, other) }
        evaluate(webView, "location.href = '${at("/download")}'")
        await("download request observed") { synchronized(requestedPaths) { "/download" in requestedPaths } }
        SystemClock.sleep(500)
        assertEquals(0, monitor!!.hits)
        assertFalse(activity.isFinishing)
    }

    private fun openShell(): Pair<ShellActivity, WebView> {
        val id = UUID.randomUUID().toString()
        val url = at("/app/")
        runBlocking {
            services.repository.add(ConfiguredApp(id, "Nav", NameSource.USER_EDITED, url, null, url, url,
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
            found != null && onMain { found!!.second.url } == url
        }
        diagnosticView = found!!.second
        await("initial fixture ready: $url") { fixtureDocumentReady(found!!.second, url) }
        return found!!
    }

    private fun at(path: String) = server.url.resolve(path)!!.toString()

    private fun evaluate(webView: WebView, script: String) = onMain { webView.evaluateJavascript(script, null) }

    private fun ShellActivity.holder(): ShellHolder? =
        ShellActivity::class.java.getDeclaredField("holder").apply { isAccessible = true }
            .get(this) as ShellHolder?

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun await(description: String = "shell attached at initial URL", condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description; " +
            "page=${diagnosticView?.let(::fixtureDocumentState)}; " +
            "requests=${synchronized(requestedPaths) { requestedPaths.toList() }}; " +
            "cleartextConnections=${cleartextConnections.get()}; intentHits=${monitor?.hits}")
    }
}
