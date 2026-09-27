package nu.staldal.mws

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Run prepare, force-stop, then verify as separate instrumentation invocations; not in the Suite. */
@RunWith(AndroidJUnit4::class)
class RestartQualificationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("qualification-restart", Context.MODE_PRIVATE)
    private val backend = AndroidWebProfileBackend()
    private val root get() = File(context.noBackupFilesDir, "qualification-restart").apply { mkdirs() }
    @Before fun requireExplicitPhaseSelection() {
        // Ordinary test discovery must not prepare or consume a cross-process fixture.
        org.junit.Assume.assumeTrue("Select one restart phase explicitly",
            InstrumentationRegistry.getArguments().getString("class", "")
                .startsWith("nu.staldal.mws.RestartQualificationInstrumentedTest#"))
    }
    private fun store() = MaintenanceStore(object : ContextWrapper(context) {
        override fun getNoBackupFilesDir() = root
    })
    private fun database() = Room.databaseBuilder(context, ConfigurationDatabase::class.java,
        File(root, "configuration.db").absolutePath).build()
    private fun compatibility(): ProviderCompatibility {
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        assertNotNull("Provider required", snapshot.key)
        assertTrue("MULTI_PROFILE required", snapshot.multiProfile)
        val key = snapshot.key!!
        return ProviderCompatibility({ snapshot }, { ProviderSelfTestResult(key, true, true, true, true, true, false) },
            mapOf(key.packageName to listOf(0)))
    }
    private fun manager(repository: ConfigurationRepository, maintenance: MaintenanceStore,
        selectedBackend: WebProfileBackend = backend) = ProfileManager(repository, maintenance, compatibility(),
        selectedBackend, LiveShellRegistry(), object : AppNativeData {
            override fun deleteIcons(appId: String) {}
            override fun widgetsChanged() {}
        })

    @Test fun preparePersistedWorkerAndInterruptedMaintenance() = runBlocking {
        assertFalse("Pending fixture exists; verify/cleanup it before preparing again", preferences.contains("run"))
        compatibility()
        val run = UUID.randomUUID().toString()
        val workerName = ProfileNames.app("qualification-worker-$run", 0)
        val clearId = "qualification-clear-$run"
        val journalId = "qualification-journal-$run"
        val survivorId = "qualification-survivor-$run"
        val deferredId = "qualification-deferred-$run"
        val removedId = "qualification-removed-$run"
        val postDeleteId = "qualification-postdelete-$run"
        val orphanId = "qualification-orphan-$run"
        LocalHttpsServer().use { server ->
            val origin = server.url.toString()
            installServer(server, run)
            // Record names before touching provider state, so failed preparation is recoverable.
            assertTrue(preferences.edit().putString("run", run).putString("worker", workerName)
                .putString("clear", clearId).putString("journal", journalId).putString("survivor", survivorId)
                .putString("deferred", deferredId).putString("removed", removedId)
                .putString("postDelete", postDeleteId).putString("orphan", orphanId)
                .putInt("port", server.url.port).putInt("pid", Process.myPid())
                .putLong("processStart", Process.getStartElapsedRealtime()).commit())
            val worker = onMain { backend.createBoundWebView(context, workerName).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            } }
            try {
                load(worker, origin)
                assertEquals("true", promise(worker, "navigator.serviceWorker.register('/worker.js')" +
                    ".then(()=>navigator.serviceWorker.ready).then(()=>true)"))
                assertEquals("1", promise(worker, "navigator.serviceWorker.getRegistrations().then(r=>r.length)"))
            } finally { onMain { worker.destroy() } }
            val db = database()
            val maintenance = store()
            try {
                maintenance.createInstallationSentinel()
                val repo = ConfigurationRepository(db)
                for (id in listOf(clearId, journalId, survivorId, deferredId, removedId, postDeleteId)) {
                    assertEquals(ConfigurationWriteResult.Success, repo.add(ConfiguredApp(id, "Qualification",
                        NameSource.USER_EDITED, origin, null, origin, origin, createdAt = 1, updatedAt = 1)))
                    val view = onMain { backend.createBoundWebView(context, ProfileNames.app(id, 0)) }
                    try {
                        val cookie = onMain { WebViewCompat.getProfile(view).cookieManager }
                        val done = CountDownLatch(1)
                        onMain { cookie.setCookie(origin, "restart=$run; Secure; Path=/; Max-Age=3600") { done.countDown() } }
                        assertTrue(done.await(5, TimeUnit.SECONDS))
                        assertTrue(onMain { cookie.getCookie(origin).orEmpty().contains("restart=$run") })
                        onMain { cookie.flush() }
                    } finally { onMain { view.destroy() } }
                }
                // Fail precisely after the durable generation reservation and before deletion.
                // IllegalStateException denotes a loaded profile; another failure leaves CLEARING.
                val interrupted = object : WebProfileBackend by backend {
                    override fun deleteProfile(name: String): Boolean = throw java.io.IOException("qualification interruption")
                }
                val profiles = manager(repo, maintenance, interrupted)
                profiles.startupMaintenance()
                assertTrue(profiles.clear(clearId) is ClearResult.Failed)
                assertTrue(profiles.clear(postDeleteId) is ClearResult.Failed)
                assertEquals(ProfileState.CLEARING, repo.get(clearId)!!.profileState)
                assertEquals(1L, repo.get(clearId)!!.profileGeneration)
                // Exercise genuine loaded-profile IllegalStateException deferral, not a fake.
                val deferredViews = listOf(deferredId, removedId).map { id ->
                    onMain { backend.createBoundWebView(context, ProfileNames.app(id, 0)) }
                }
                try {
                    val real = manager(repo, maintenance)
                    assertEquals(ClearResult.PendingRestart, real.clear(deferredId))
                    assertEquals(RemovalResult.ProfileDeletionPending, real.remove(removedId))
                    assertNull(repo.get(removedId))
                    for (id in listOf(deferredId, removedId))
                        assertTrue(maintenance.hasTombstone(ProfileNames.app(id, 0)))
                } finally { onMain { deferredViews.forEach(WebView::destroy) } }
                // Durable discovery registration whose owning process will disappear.
                maintenance.registerDiscoveryProfile(DiscoveryProfile(orphanId, System.currentTimeMillis()))
                val orphan = onMain { backend.createBoundWebView(context, ProfileNames.disposable(orphanId)) }
                try {
                    val cookie = onMain { WebViewCompat.getProfile(orphan).cookieManager }
                    val done = CountDownLatch(1)
                    onMain { cookie.setCookie(origin, "restart=$run; Secure; Path=/; Max-Age=3600") { done.countDown() } }
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    onMain { cookie.flush() }
                } finally { onMain { orphan.destroy() } }
                // Leave a real production UPDATE_URL journal at its initial cursor.
                val changedOrigin = server.url.newBuilder().host("127.0.0.1").build().toString()
                val snapshot = repo.get(journalId)!!.copy(configuredUrl = changedOrigin + "changed",
                    launchUrl = changedOrigin + "changed", scope = changedOrigin)
                maintenance.begin(OperationIntent(OperationType.UPDATE_URL, journalId, 0, 1,
                    OriginChangeSnapshot.encode(snapshot)), 2)
                assertTrue(maintenance.pendingOperations().any { it.intent.appId == journalId })
                // Flush the registry just as the fresh-process deletion probe does.
                Thread.sleep(12_000)
                assertTrue(preferences.edit().putBoolean("prepared", true).commit())
            } finally { maintenance.close(); db.close() }
        }
    }

    @Test fun interruptAfterProfileDeletionBeforeFinalCommit() = runBlocking {
        assertTrue("Run preparation first", preferences.getBoolean("prepared", false))
        assertFreshProcess("pid", "processStart")
        assertTrue(preferences.edit().putInt("deletePid", Process.myPid())
            .putLong("deleteStart", Process.getStartElapsedRealtime()).commit())
        val id = preferences.getString("postDelete", null)!!
        val name = ProfileNames.app(id, 0)
        assertTrue("Old profile was not persisted", onMain { backend.profileExists(name) })
        val interrupted = object : WebProfileBackend by backend {
            override fun deleteProfile(selected: String): Boolean {
                check(selected == name)
                check(backend.deleteProfile(selected)) { "Expected real deletion" }
                throw java.io.IOException("Interruption after deletion, before final commit")
            }
        }
        val db = database(); val maintenance = store()
        try {
            val repo = ConfigurationRepository(db)
            assertTrue(manager(repo, maintenance, interrupted).clear(id) is ClearResult.Failed)
            assertFalse(onMain { backend.profileExists(name) })
            assertEquals(ProfileState.CLEARING, repo.get(id)!!.profileState)
            assertEquals(1L, repo.get(id)!!.profileGeneration)
            assertTrue(preferences.edit().putBoolean("postDeleted", true).commit())
        } finally { maintenance.close(); db.close() }
    }

    @Test fun verifyPersistedWorkerAndResumeMaintenance() = runBlocking {
        assertTrue("Run preparation first", preferences.getBoolean("prepared", false))
        assertTrue("Run deletion interruption phase first", preferences.getBoolean("postDeleted", false))
        assertFreshProcess("pid", "processStart")
        assertFreshProcess("deletePid", "deleteStart")
        val run = preferences.getString("run", null)!!
        val workerName = preferences.getString("worker", null)!!
        val clearId = preferences.getString("clear", null)!!
        val journalId = preferences.getString("journal", null)!!
        val survivorId = preferences.getString("survivor", null)!!
        val deferredId = preferences.getString("deferred", null)!!
        val removedId = preferences.getString("removed", null)!!
        val postDeleteId = preferences.getString("postDelete", null)!!
        val orphanId = preferences.getString("orphan", null)!!
        compatibility()
        assertTrue("Worker profile registry did not persist", onMain { backend.profileExists(workerName) })
        for (id in listOf(clearId, journalId, deferredId, removedId))
            assertTrue("Retiring profile missing before recovery: $id", onMain { backend.profileExists(ProfileNames.app(id, 0)) })
        LocalHttpsServer(preferences.getInt("port", 0)).use { server ->
            installServer(server, run)
            val db = database(); val maintenance = store()
            try {
                val repo = ConfigurationRepository(db)
                assertEquals(ProfileState.CLEARING, repo.get(clearId)!!.profileState)
                assertTrue(maintenance.pendingOperations().any { it.intent.appId == journalId })
                val profiles = manager(repo, maintenance)
                val recovery = profiles.startupMaintenance()
                assertTrue("Journal resume failed: ${recovery}", maintenance.pendingOperations().none { it.intent.appId == journalId })
                for (id in listOf(clearId, journalId, deferredId, postDeleteId)) {
                    val app = repo.get(id)!!
                    assertEquals(ProfileState.ACTIVE, app.profileState)
                    assertEquals("Generation renewed instead of resumed", 1L, app.profileGeneration)
                    assertNull(app.retiringProfileGeneration)
                    assertFalse("Old profile not deleted", onMain { backend.profileExists(ProfileNames.app(id, 0)) })
                    // Recreate the deleted OLD name: a fresh generation alone proves nothing.
                    assertDeletedData(ProfileNames.app(id, 0), server.url.toString(), run)
                    val attached = profiles.createAppWebView(context, id) as AttachResult.Attached
                    onMain { profiles.release(attached.bound) }
                }
                assertNull(repo.get(removedId))
                assertTrue("Tombstones not retired", maintenance.tombstones().isEmpty())
                assertFalse("Removed profile remains", onMain { backend.profileExists(ProfileNames.app(removedId, 0)) })
                assertDeletedData(ProfileNames.app(removedId, 0), server.url.toString(), run)
                assertFalse("Orphan discovery profile remains", onMain { backend.profileExists(ProfileNames.disposable(orphanId)) })
                assertFalse(maintenance.hasDiscoveryProfile(orphanId))
                assertDeletedData(ProfileNames.disposable(orphanId), server.url.toString(), run)
                assertEquals(server.url.newBuilder().host("127.0.0.1").build().toString() + "changed", repo.get(journalId)!!.launchUrl)
                assertEquals(0L, repo.get(survivorId)!!.profileGeneration)
                assertTrue("Unrelated profile deleted during recovery", onMain {
                    backend.profileExists(ProfileNames.app(survivorId, 0))
                })
                val survivor = onMain { backend.createBoundWebView(context, ProfileNames.app(survivorId, 0)) }
                try {
                    assertTrue("Control cookie did not persist across process death", onMain {
                        WebViewCompat.getProfile(survivor).cookieManager.getCookie(server.url.toString())
                            .orEmpty().contains("restart=$run")
                    })
                    val observations = observeAuth(survivor, server.url.resolve("/worker-free-challenge")!!.toString(),
                        onMain { backend.basicAuthSafety(survivor) })
                    assertEquals("Worker-free gate must attempt one lookup", 1, observations.lookups.get())
                    assertEquals(1, observations.cancelled.get())
                } finally { onMain { survivor.destroy() } }
                verifyPersistedWorker(server, workerName, run)
                Thread.sleep(12_000)
                assertTrue(preferences.edit().putBoolean("verified", true).putInt("verifyPid", Process.myPid())
                    .putLong("verifyStart", Process.getStartElapsedRealtime()).commit())
            } finally { maintenance.close(); db.close() }
        }
    }

    @Test fun cleanupRestartFixture() {
        assertTrue("No fixture to clean", preferences.contains("run"))
        for ((pidKey, startKey) in listOf("pid" to "processStart", "deletePid" to "deleteStart", "verifyPid" to "verifyStart")) {
            assertTrue("Cleanup needs a fresh process after $pidKey", preferences.getInt(pidKey, -1) != Process.myPid() ||
                preferences.getLong(startKey, -1) != Process.getStartElapsedRealtime())
        }
        val names = listOf(preferences.getString("worker", null)!!, ProfileNames.disposable(preferences.getString("orphan", null)!!)) +
            listOf("clear", "journal", "survivor", "deferred", "removed", "postDelete")
                .flatMap { key -> val id = preferences.getString(key, null)!!
                    listOf(ProfileNames.app(id, 0), ProfileNames.app(id, 1)) }
        for (name in names) onMain {
            ProfileStore.getInstance().deleteProfile(name)
            assertFalse(ProfileStore.getInstance().allProfileNames.contains(name))
        }
        assertTrue("Fixture database cleanup", root.deleteRecursively())
        assertTrue(preferences.edit().clear().commit())
    }

    private fun assertFreshProcess(pidKey: String, startKey: String) {
        assertTrue("Fresh process required after $pidKey", preferences.getInt(pidKey, -1) != Process.myPid() ||
            preferences.getLong(startKey, -1) != Process.getStartElapsedRealtime())
    }
    private fun assertDeletedData(name: String, origin: String, run: String) {
        val view = onMain { backend.createBoundWebView(context, name) }
        try { assertFalse("Deleted-name data reappeared: $name", onMain {
            WebViewCompat.getProfile(view).cookieManager.getCookie(origin).orEmpty().contains("restart=$run")
        }) } finally { onMain { view.destroy() } }
    }
    private data class AuthObservations(val lookups: AtomicInteger = AtomicInteger(), val cancelled: AtomicInteger = AtomicInteger())
    private fun observeAuth(view: WebView, target: String, safety: () -> Boolean): AuthObservations {
        val observations = AuthObservations()
        val delivered = CountDownLatch(1)
        var auth: ShellBasicAuth? = null
        try {
            onMain {
                val gate = ShellBasicAuth({ true }, { observations.lookups.incrementAndGet(); null }, "restart",
                    eligible = safety, onLookup = {}, onLookupEnded = {}, onError = {})
                auth = gate
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        gate.intercepted(request.url.toString(), request.isForMainFrame); return null
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { gate.started(url) }
                    override fun onPageCommitVisible(view: WebView, url: String) { gate.disarm() }
                    override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String?, realm: String?) {
                        val answer = handler.asAnswer()
                        gate.challenge(object : AuthAnswer by answer {
                            override fun cancel() { observations.cancelled.incrementAndGet(); answer.cancel() }
                        }, host, realm)
                        delivered.countDown()
                    }
                }
                gate.arm(target); view.loadUrl(target)
            }
            assertTrue("Real auth challenge not delivered: $target", delivered.await(15, TimeUnit.SECONDS))
            return observations
        } finally { onMain { auth?.close() } }
    }
    private fun verifyPersistedWorker(server: LocalHttpsServer, name: String, run: String) {
        val workerRequests = CountDownLatch(1)
        val safetyAtRequest = AtomicReference<Boolean>()
        val safety = AtomicReference<() -> Boolean>({ true })
        installServer(server, run, workerRequests) { safetyAtRequest.set(safety.get().invoke()) }
        val view = onMain { backend.createBoundWebView(context, name).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        } }
        try {
            safety.set(onMain { backend.basicAuthSafety(view) })
            assertTrue("Fresh-process worker latch must start clean", safety.get().invoke())
            val observations = observeAuth(view, server.url.resolve("/worker-challenge")!!.toString(), safety.get())
            assertTrue("Persisted worker did not execute", workerRequests.await(15, TimeUnit.SECONDS))
            assertEquals("Worker observer must retire before dispatch", false, safetyAtRequest.get())
            assertEquals("MyPass lookup attempted despite persisted worker", 0, observations.lookups.get())
            assertEquals("Real handler was not cancelled", 1, observations.cancelled.get())
        } finally { onMain { view.destroy() } }
    }

    private fun installServer(server: LocalHttpsServer, run: String, worker: CountDownLatch? = null, atWorkerRequest: () -> Unit = {}) {
        server.dispatch { request ->
            when (request.path?.substringBefore('?')) {
                "/worker.js" -> MockResponse().setHeader("Content-Type", "application/javascript")
                    .setHeader("Cache-Control", "max-age=86400")
                    .setBody("self.addEventListener('fetch',e=>{if(new URL(e.request.url).pathname==='/worker-challenge')" +
                        "e.waitUntil(fetch('/worker-network',{headers:{'X-Qualification-Worker':'$run'}}));});")
                "/worker-network" -> {
                    if (request.getHeader("X-Qualification-Worker") == run) { atWorkerRequest(); worker?.countDown() }
                    MockResponse().setBody("worker")
                }
                "/worker-challenge", "/worker-free-challenge" -> {
                    if (worker != null && request.path == "/worker-challenge") worker.await(10, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"Restart\"")
                        .setHeader("Content-Type", "text/html").setBody("denied")
                }
                else -> MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body><span id=fixture-ready></span>fixture</body></html>")
            }
        }
    }
    private fun load(view: WebView, url: String) {
        val done = CountDownLatch(1)
        onMain {
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { done.countDown() }
            }
            view.loadUrl(url)
        }
        assertTrue("Fixture load", done.await(15, TimeUnit.SECONDS))
    }
    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference<String>(); val done = CountDownLatch(1)
        onMain { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JS callback", done.await(5, TimeUnit.SECONDS)); return result.get()
    }
    private fun promise(view: WebView, expression: String): String {
        evaluate(view, "window.__qualification=undefined;Promise.resolve($expression).then(" +
            "v=>window.__qualification=JSON.stringify(v),e=>window.__qualification=JSON.stringify(String(e)))")
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            val encoded = evaluate(view, "window.__qualification===undefined?null:window.__qualification")
            if (encoded != "null") return org.json.JSONTokener(encoded).nextValue() as String
            Thread.sleep(50)
        }
        throw AssertionError("Promise did not settle: $expression")
    }
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
