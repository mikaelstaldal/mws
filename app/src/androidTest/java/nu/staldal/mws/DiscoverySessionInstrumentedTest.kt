package nu.staldal.mws

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real attached WebView; it requires a qualified provider on a device. */
@RunWith(AndroidJUnit4::class)
class DiscoverySessionInstrumentedTest {
    private lateinit var database: ConfigurationDatabase
    private lateinit var maintenance: MaintenanceStore

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::maintenance.isInitialized) maintenance.close()
    }

    /** Test-only compatibility allows document-stage checks on an unqualified provider. */
    private fun testProfiles(): ProfileManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val key = snapshot.key ?: error("No WebView provider")
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java)
            .allowMainThreadQueries().build()
        maintenance = MaintenanceStore(context)
        val compatibility = ProviderCompatibility({ snapshot },
            { ProviderSelfTestResult(key, true, true, true, true, true, true) },
            floors = mapOf(key.packageName to listOf(0)))
        val manager = ProfileManager(ConfigurationRepository(database), maintenance, compatibility,
            AndroidWebProfileBackend(), LiveShellRegistry(), object : AppNativeData {
                override fun deleteIcons(appId: String) {}
                override fun widgetsChanged() {}
            })
        runBlocking { manager.startupMaintenance() }
        return manager
    }

    @Test fun replacingAnAttemptIgnoresTheOldPageCallback() {
        val profiles = testProfiles()
        LocalHttpsServer().use { slow ->
            LocalHttpsServer().use { fast ->
                val slowUrl = slow.url.toString()
                val fastUrl = fast.url.toString()
                val slowRequested = CountDownLatch(1)
                slow.dispatch {
                    slowRequested.countDown()
                    MockResponse().setHeadersDelay(3, TimeUnit.SECONDS)
                        .setBody("<html><body>old</body></html>")
                }
                fast.dispatch { MockResponse().setBody("<html><body>new</body></html>") }
                val committed = CountDownLatch(1)
                val count = AtomicInteger()
                val result = AtomicReference<DiscoveryDocumentResult>()
                lateinit var session: DiscoverySession
                ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val host = FrameLayout(activity)
                        activity.addContentView(host, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT))
                        session = DiscoverySession(host, profiles, activity.lifecycleScope,
                            { _, document ->
                                count.incrementAndGet()
                                result.set(document)
                                committed.countDown()
                            }, { }, { })
                        session.refresh(slowUrl)
                    }
                    try {
                        assertTrue("Old request never started", slowRequested.await(15, TimeUnit.SECONDS))
                        scenario.onActivity { session.refresh(fastUrl) }
                        assertTrue("New page did not commit", committed.await(20, TimeUnit.SECONDS))
                        Thread.sleep(3500)
                        assertEquals(fastUrl, result.get().finalUrl)
                        assertEquals(1, count.get())
                    } finally {
                        runBlocking(Dispatchers.Main) { session.close() }
                    }
                }
            }
        }
    }

    @Test fun firstCrossOriginCommitDoesNotBecomeFinalUrl() {
        val profiles = testProfiles()
        LocalHttpsServer().use { source ->
            LocalHttpsServer().use { destination ->
                val sourceUrl = source.url.toString()
                val destinationUrl = destination.url.toString()
                source.dispatch { MockResponse().setResponseCode(302)
                    .setHeader("Location", destinationUrl) }
                destination.dispatch { MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>identity provider</body></html>") }
                val committed = CountDownLatch(1)
                val result = AtomicReference<DiscoveryDocumentResult>()
                lateinit var session: DiscoverySession
                ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val host = FrameLayout(activity)
                        activity.addContentView(host, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT))
                        session = DiscoverySession(host, profiles, activity.lifecycleScope,
                            { _, document -> result.set(document); committed.countDown() },
                            { }, { })
                        session.refresh(sourceUrl)
                    }
                    try {
                        assertTrue("No visible commit", committed.await(20, TimeUnit.SECONDS))
                        assertNull(result.get().finalUrl)
                        assertTrue(result.get().crossOrigin)
                    } finally {
                        runBlocking(Dispatchers.Main) { session.close() }
                    }
                }
            }
        }
    }

    @Test fun cleartextRedirectIsRejectedBeforeTransmission() {
        val profiles = testProfiles()
        CleartextEvidenceListener().use { listener ->
            listener.calibrate()
            LocalHttpsServer().use { source ->
                val sources = AtomicInteger()
                source.dispatch {
                    sources.incrementAndGet()
                    MockResponse().setResponseCode(307).setHeader("Location", listener.url)
                }
                val finished = CountDownLatch(1)
                val result = AtomicReference<DiscoveryDocumentResult>()
                lateinit var session: DiscoverySession
                ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val host = FrameLayout(activity)
                        activity.addContentView(host, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        session = DiscoverySession(host, profiles, activity.lifecycleScope,
                            { _, document -> result.set(document); finished.countDown() }, {}, {})
                        session.refresh(source.url.toString())
                    }
                    try {
                        assertTrue("Discovery redirect did not terminate", finished.await(20, TimeUnit.SECONDS))
                        assertTrue("HTTPS source not reached", sources.get() > 0)
                        assertEquals(DiscoveryDocumentFailure.HTTPS_REQUIRED, result.get().failure)
                        Thread.sleep(3000)
                        assertEquals("Discovery cleartext bytes", 0, listener.bytes.get())
                        assertEquals("Listener errors", 0, listener.errors.get())
                    } finally { runBlocking(Dispatchers.Main) { session.close() } }
                }
            }
        }
    }

    @Test fun metaRefreshPageIsFollowedToItsSameOriginTarget() = assertRedirectPageFollowed(
        """<html><head><meta http-equiv="Refresh" content="0;url=/app/"></head>
            <body style="display:none">stub</body></html>""")

    @Test fun scriptRedirectPageIsFollowedToItsSameOriginTarget() = assertRedirectPageFollowed(
        """<html><head><script>location.replace('/app/')</script></head><body>stub</body></html>""")

    private fun assertRedirectPageFollowed(stub: String) {
        val profiles = testProfiles()
        LocalHttpsServer().use { server ->
            val targetUrl = server.url.resolve("/app/").toString()
            server.dispatch { request ->
                if (request.path == "/app/") MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("""<html><head><title>Target</title><link rel="icon" href="/icon.png">
                        </head><body>app</body></html>""")
                else MockResponse().setHeader("Content-Type", "text/html").setBody(stub)
            }
            val snapshotted = CountDownLatch(1)
            val documents = mutableListOf<DiscoveryDocumentResult>()
            val snapshot = AtomicReference<DiscoveryDomSnapshot>()
            lateinit var session: DiscoverySession
            ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val host = FrameLayout(activity)
                    activity.addContentView(host, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    session = DiscoverySession(host, profiles, activity.lifecycleScope,
                        { _, document -> synchronized(documents) { documents += document } }, { }, { },
                        onSnapshot = { _, dom -> snapshot.set(dom); snapshotted.countDown() })
                    session.refresh(server.url.toString())
                }
                try {
                    assertTrue("No snapshot", snapshotted.await(20, TimeUnit.SECONDS))
                    assertEquals("Target", snapshot.get().title)
                    assertEquals(server.url.resolve("/icon.png").toString(), snapshot.get().icons.single().url)
                    assertEquals(targetUrl, synchronized(documents) { documents.last().finalUrl })
                } finally {
                    runBlocking(Dispatchers.Main) { session.close() }
                }
            }
        }
    }

    @Test fun firstVisibleCommitRequiresAnAttachedDisposableWebView() {
        val profiles = testProfiles()
        LocalHttpsServer().use { server ->
            val pageUrl = server.url.toString()
            server.dispatch { MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><body>ready</body></html>") }
            val committed = CountDownLatch(1)
            val result = AtomicReference<DiscoveryDocumentResult>()
            lateinit var session: DiscoverySession
            lateinit var host: FrameLayout
            ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    host = FrameLayout(activity)
                    activity.addContentView(host, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    session = DiscoverySession(host, profiles, activity.lifecycleScope,
                        { _, document -> result.set(document); committed.countDown() },
                        { }, { })
                    session.refresh(pageUrl)
                }
                try {
                    assertTrue("No visible commit", committed.await(20, TimeUnit.SECONDS))
                    assertEquals(pageUrl, result.get().finalUrl)
                    scenario.onActivity {
                        val webView = (0 until host.childCount).map(host::getChildAt)
                            .filterIsInstance<WebView>().single()
                        assertTrue(webView.isAttachedToWindow)
                        assertTrue(webView.width > 0 && webView.height > 0)
                        assertTrue(androidx.webkit.WebViewCompat.getProfile(webView).name
                            .startsWith("mws-disposable-"))
                    }
                } finally {
                    runBlocking(Dispatchers.Main) { session.close() }
                }
            }
        }
    }
}
