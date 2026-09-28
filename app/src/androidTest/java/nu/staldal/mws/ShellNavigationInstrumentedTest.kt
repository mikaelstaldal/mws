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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.security.NetworkSecurityPolicy
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
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
    private val cleartextBytes = AtomicInteger()
    private val cleartextReadErrors = AtomicInteger()
    private val cleartextSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val requestedPaths = mutableListOf<String>()
    private val requestedMethods = ConcurrentHashMap<String, String>()
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val apps = mutableListOf<String>()
    private var diagnosticView: WebView? = null

    @Before fun setUp() {
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        val key = snapshot.key
        assumeTrue(key != null && snapshot.multiProfile)
        cleartext = ServerSocket(0, 50, InetAddress.getByName("::"))
        thread(isDaemon = true) {
            while (!cleartext.isClosed) {
                val socket = runCatching { cleartext.accept() }.getOrNull() ?: break
                cleartextSockets.add(socket)
                if (cleartext.isClosed) {
                    socket.close()
                    cleartextSockets.remove(socket)
                    break
                }
                cleartextConnections.incrementAndGet()
                // Speculative connections must not block acceptance of a later leaking request.
                thread(isDaemon = true) {
                    try {
                        socket.use {
                            socket.soTimeout = 2_000
                            val buffer = ByteArray(4096)
                            val input = socket.getInputStream()
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                cleartextBytes.addAndGet(count)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // An idle connection sent no further bytes during the observation period.
                    } catch (_: Exception) {
                        if (!cleartext.isClosed) cleartextReadErrors.incrementAndGet()
                    } finally {
                        cleartextSockets.remove(socket)
                    }
                }
            }
        }
        val http = "http://127.0.0.1:${cleartext.localPort}/leak"
        server = LocalHttpsServer()
        server.dispatch { request ->
            synchronized(requestedPaths) { requestedPaths += request.path ?: "" }
            requestedMethods[request.path ?: ""] = request.method ?: ""
            when (request.path?.substringBefore("?")) {
                "/redirect", "/redirect-post" -> MockResponse()
                    .setResponseCode(if (request.path?.substringBefore("?") == "/redirect-post") 307 else 302)
                    .setHeader("Location", request.requestUrl?.queryParameter("target") ?: http)
                "/qualification-worker.js" -> MockResponse().setHeader("Content-Type", "application/javascript")
                    .setBody("self.addEventListener('message', e => {" +
                        "const reply=e.ports[0];" +
                        "e.waitUntil(fetch(e.data, {method:'POST',body:'fixture'}).then(" +
                        "()=>reply.postMessage('resolved'),e=>reply.postMessage('rejected:'+e.name)));});")
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
        cleartextSockets.forEach { runCatching { it.close() } }
        database.close()
        store.close()
    }

    @Test fun cleartextLinksFormsAndRedirectsTransmitZeroBytes() {
        for (host in listOf("127.0.0.1", "localhost", "127.0.0.2", "[::1]")) assertCleartextFlows(host)
    }

    @Test fun nonLoopbackCleartextFlowsTransmitZeroBytes() {
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
        // A skip is missing non-loopback evidence, never a qualification pass.
        assumeTrue("A non-loopback IPv4 interface is required", host != null)
        assertCleartextFlows(checkNotNull(host))
    }

    @Test fun networkPolicyRejectsLoopbackAndNonLoopbackHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        for (host in listOf("localhost", "ip6-localhost", "127.0.0.1", "127.0.0.2", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1", "example.com")) {
            assertFalse("cleartext policy for $host", policy.isCleartextTrafficPermitted(host))
        }
    }

    private fun assertCleartextFlows(host: String) {
        calibrateCleartextListener(host)
        val (activity, webView) = openShell()
        val page = at("/app/")
        val http = "http://$host:${cleartext.localPort}/leak"
        val redirect = at("/redirect-post")
        // The redirect target is passed as a query, so both GET and POST redirects exercise this host.
        val encoded = java.net.URLEncoder.encode(http, "UTF-8")
        for (script in listOf("location.href = '$http'",
            "var f=document.getElementById('get');f.action='$http';f.submit()",
            "var f=document.getElementById('post');f.action='$http';f.submit()",
            "location.href = '${at("/redirect")}?target=$encoded'",
            "var f=document.getElementById('post');f.action='$redirect?target=$encoded';f.submit()",
            "window.open('$http')",
            "var f=document.getElementById('post');f.action='$http';f.target='_blank';f.submit()")) {
            // A unique URL prevents the previous ready document from satisfying this reset.
            val fixture = "$page?attempt=${UUID.randomUUID()}"
            onMain {
                // Recover through the holder so a prior intercepted flow's error is cleared.
                if (activity.holder()?.error != null) activity.holder()?.retry()
                webView.loadUrl(fixture)
            }
            await("fixture ready after reset: $fixture") { fixtureDocumentReady(webView, fixture) }
            assertEquals("no stale shell error before $script", null, onMain { activity.holder()?.error })
            val documentToken = UUID.randomUUID().toString()
            evaluateChecked(webView, "window.__mwsDocumentToken='$documentToken';$script")
            if (script.contains("/redirect")) {
                val post = script.contains("/redirect-post")
                val path = "${if (post) "/redirect-post" else "/redirect"}?target=$encoded"
                await("HTTPS redirect source reached: $path") { requestedMethods[path] != null }
                assertEquals("redirect source method", if (post) "POST" else "GET", requestedMethods[path])
            }
            SystemClock.sleep(3_000)
            // A provider may cancel before interception/error callbacks, retaining the HTTPS
            // document. If interception runs, the shell instead shows HTTPS required.
            // evaluateChecked proves submission executed; the listener proves no transmission.
            if (script.contains("getElementById('post')") &&
                !script.contains("_blank") && !script.contains("/redirect")) {
                val error = onMain { activity.holder()?.error }
                assertTrue("$host: blocked POST must show HTTPS required or retain the fixture; " +
                    "error=$error; page=${fixtureDocumentState(webView)}",
                    error == R.string.shell_https_required ||
                        (error == null && fixtureDocumentReady(webView, fixture)))
                if (error == null) evaluateChecked(webView,
                    "if(window.__mwsDocumentToken!=='$documentToken')throw Error('Document replaced')")
            }
            assertEquals("$host: $script; listener read errors", 0, cleartextReadErrors.get())
            assertEquals("$host: $script; connections=${cleartextConnections.get()}; " +
                "page=${fixtureDocumentState(webView)}", 0, cleartextBytes.get())
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

    private fun calibrateCleartextListener(host: String = "127.0.0.1") {
        // Calibrate reachability with a native socket before accepting zero bytes as evidence.
        assertEquals("late bytes before calibrating $host", 0, cleartextBytes.get())
        Socket().use {
            it.connect(InetSocketAddress(host, cleartext.localPort), 2_000)
            it.getOutputStream().write(1)
        }
        await("listener reachable at $host") { cleartextBytes.get() >= 1 }
        assertEquals("only the calibration byte at $host", 1, cleartextBytes.getAndAdd(-1))
    }

    @Test fun websocketAndServiceWorkerHttpAttemptsTransmitZeroBytes() {
        calibrateCleartextListener()
        val (_, view) = openShell()
        val target = "http://127.0.0.1:${cleartext.localPort}/worker"
        evaluateChecked(view, "window.__wsOutcome=null;try{" +
            "const ws=new WebSocket('ws://127.0.0.1:${cleartext.localPort}/ws');" +
            "ws.onopen=()=>window.__wsOutcome='opened';" +
            "ws.onerror=()=>{if(window.__wsOutcome===null)window.__wsOutcome='error'};" +
            "ws.onclose=()=>{if(window.__wsOutcome===null)window.__wsOutcome='closed'}" +
            "}catch(e){window.__wsOutcome='threw:'+e.name}")
        await("WebSocket attempt to settle") { evaluateResult(view, "window.__wsOutcome") != "null" }
        val websocketOutcome = evaluateResult(view, "window.__wsOutcome")
        assertTrue("WebSocket must fail before opening: $websocketOutcome",
            websocketOutcome in setOf("\"threw:SecurityError\"", "\"error\"", "\"closed\""))
        SystemClock.sleep(3000)
        assertEquals("WebSocket listener read errors", 0, cleartextReadErrors.get())
        assertEquals("WebSocket cleartext bytes", 0, cleartextBytes.get())
        evaluateChecked(view, "window.__workerStage='registering';window.__workerError=null;" +
            "window.__workerOutcome=null;" +
            "navigator.serviceWorker.register('/qualification-worker.js')" +
            ".then(()=>{window.__workerStage='registered';return navigator.serviceWorker.ready})" +
            ".then(r=>{window.__workerStage='ready';const channel=new MessageChannel();" +
            "channel.port1.onmessage=e=>{window.__workerOutcome=e.data;window.__workerStage='replied'};" +
            "channel.port1.onmessageerror=()=>window.__workerError='messageerror';" +
            "r.active.postMessage('$target',[channel.port2]);window.__workerStage='posted'})" +
            ".catch(e=>{window.__workerError=String(e);window.__workerStage='error'})")
        await("worker HTTP fetch executed and settled", diagnostics = {
            "workerStage=${evaluateResult(view, "window.__workerStage")}; " +
                "workerError=${evaluateResult(view, "window.__workerError")}; " +
                "workerOutcome=${evaluateResult(view, "window.__workerOutcome")}; "
        }) { evaluateResult(view, "window.__workerOutcome!==null || window.__workerError!==null") == "true" }
        assertEquals("Worker setup error", "null", evaluateResult(view, "window.__workerError"))
        assertEquals("Worker must reject HTTP fetch", "\"rejected:TypeError\"",
            evaluateResult(view, "window.__workerOutcome"))
        SystemClock.sleep(3000)
        assertEquals("listener read errors", 0, cleartextReadErrors.get())
        assertEquals("WebSocket/worker cleartext bytes", 0, cleartextBytes.get())
    }

    @Test fun gesturedCleartextPopupAndIntentFallbackAreNotTransmittedOrDelegated() {
        calibrateCleartextListener()
        val (activity, view) = openShell()
        monitor = instrumentation.addMonitor(null as String?, null, true)
        val http = "http://127.0.0.1:${cleartext.localPort}/gesture"
        for (script in listOf("window.open('$http')",
            "location.href='intent://fixture/#Intent;scheme=fixture;S.browser_fallback_url=" +
                java.net.URLEncoder.encode(http, "UTF-8") + ";end'")) {
            val fixture = at("/app/") + "?gesture=" + UUID.randomUUID()
            onMain { view.loadUrl(fixture) }
            await("gestured fixture ready") { fixtureDocumentReady(view, fixture) }
            evaluateChecked(view, "document.body.innerHTML='<button id=gesture style=\"position:fixed;inset:0;width:100%;height:100%\">tap</button>';" +
                "window.__gesture=false;document.getElementById('gesture').onclick=()=>{" +
                "window.__gesture=navigator.userActivation.isActive;$script;}")
            val coordinates = onMain {
                val location = IntArray(2); view.getLocationOnScreen(location)
                (location[0] + view.width / 2).toFloat() to (location[1] + view.height / 2).toFloat()
            }
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    coordinates.first, coordinates.second, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            await("real gesture reached fixture handler") { evaluateResult(view, "window.__gesture") == "true" }
            SystemClock.sleep(3000)
            assertEquals("gestured cleartext bytes: $script", 0, cleartextBytes.get())
            assertEquals("listener errors", 0, cleartextReadErrors.get())
            assertEquals("external intent delegated", 0, monitor!!.hits)
            assertFalse(activity.isFinishing)
        }
    }

    @Test fun realGesturesRouteHttpsGetInScopeAndDelegateOnlyOutOfScope() {
        val (_, view) = openShell()
        val inside = at("/app/inside") + "?activated=true"
        val outside = at("/elsewhere-blank") + "?activated=true"
        val intents = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                intents += intent
                return Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        evaluateChecked(view, "document.body.innerHTML='<button style=\"position:fixed;inset:0;width:100%;height:100%\">open</button>';" +
            "document.querySelector('button').onclick=()=>window.open('${at("/app/inside")}?activated='+navigator.userActivation.isActive)")
        tapCenter(view)
        await("gestured HTTPS GET routed into parent") { fixtureDocumentReady(view, inside) }
        assertEquals("Only the parent may request the destination", 1,
            synchronized(requestedPaths) { requestedPaths.count { it == "/app/inside?activated=true" } })
        assertTrue("In-scope destination delegated", intents.isEmpty())
        evaluateChecked(view, "document.body.innerHTML='<a id=outside target=_blank style=\"position:fixed;inset:0;display:block\">open</a>';" +
            "document.getElementById('outside').onclick=function(){this.href='${at("/elsewhere-blank")}?activated='+navigator.userActivation.isActive;}")
        tapCenter(view)
        await("gestured out-of-scope GET delegated") { intents.isNotEmpty() }
        assertEquals(1, intents.size)
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals(outside, intents.single().dataString)
        SystemClock.sleep(3000)
        assertFalse("Popup dispatched before native delegation", synchronized(requestedPaths) {
            requestedPaths.any { it.startsWith("/elsewhere-blank") }
        })
        assertEquals(inside, onMain { view.url })
    }

    private fun tapCenter(view: WebView) {
        val coordinates = onMain {
            val location = IntArray(2); view.getLocationOnScreen(location)
            (location[0] + view.width / 2).toFloat() to (location[1] + view.height / 2).toFloat()
        }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                coordinates.first, coordinates.second, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    private fun evaluateResult(view: WebView, script: String): String? {
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>()
        onMain { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScript callback missing: $script", done.await(5, TimeUnit.SECONDS))
        return result.get()
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

    private fun evaluateChecked(webView: WebView, script: String) {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        onMain {
            webView.evaluateJavascript("(function(){try{$script;return 'mws-attempted';}" +
                "catch(e){return String(e);}})()") { result.set(it); done.countDown() }
        }
        assertTrue("script callback: $script", done.await(5, TimeUnit.SECONDS))
        assertEquals("script execution: $script", "\"mws-attempted\"", result.get())
    }

    private fun ShellActivity.holder(): ShellHolder? =
        ShellActivity::class.java.getDeclaredField("holder").apply { isAccessible = true }
            .get(this) as ShellHolder?

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun await(description: String = "shell attached at initial URL", diagnostics: () -> String = { "" },
                      condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description; ${diagnostics()}" +
            "page=${diagnosticView?.let(::fixtureDocumentState)}; " +
            "requests=${synchronized(requestedPaths) { requestedPaths.toList() }}; " +
            "cleartextConnections=${cleartextConnections.get()}; " +
            "cleartextBytes=${cleartextBytes.get()}; readErrors=${cleartextReadErrors.get()}; " +
            "intentHits=${monitor?.hits}")
    }
}
