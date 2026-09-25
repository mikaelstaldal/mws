package nu.staldal.mws

import android.os.SystemClock
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Provider contract for A§5 against the real WebView: two same-origin profiles share nothing,
 * data persists for a relaunched WebView, and deleting one profile leaves the other untouched.
 */
@RunWith(AndroidJUnit4::class)
class ProfileIsolationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backend = AndroidWebProfileBackend()
    private val webViews = mutableListOf<WebView>()
    private val names = mutableListOf<String>()
    private val cachedHits = AtomicInteger()
    private val authCallbacks = mutableMapOf<WebView, Int>()
    private lateinit var server: LocalHttpsServer

    @Before fun setUp() {
        assumeTrue(onMain { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) })
        server = LocalHttpsServer()
        server.dispatch { request ->
            when (request.path) {
                "/sw.js" -> MockResponse().setHeader("Content-Type", "text/javascript")
                    .setBody("self.addEventListener('fetch', () => {});")
                "/cached" -> MockResponse().setHeader("Cache-Control", "max-age=3600")
                    .setBody("hit-${cachedHits.incrementAndGet()}")
                "/auth" -> if (request.getHeader("Authorization") == null) {
                    MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Basic realm=\"test\"").setBody("denied")
                } else MockResponse().setBody("<html><body>authorized</body></html>")
                else -> MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>page</body></html>")
            }
        }
    }

    @After fun tearDown() {
        if (!::server.isInitialized) return
        onMain { webViews.forEach(WebView::destroy) }
        server.close()
        names.forEach { name -> runCatching { onMain { ProfileStore.getInstance().deleteProfile(name) } } }
    }

    @Test fun sameOriginProfilesShareNoStateAndPersistAcrossRelaunch() {
        val first = newName()
        val second = newName()
        val a = open(first)
        writeEverything(a, "a")
        assertEquals("true", evalAsync(a, "navigator.serviceWorker.register('/sw.js')" +
            ".then(() => navigator.serviceWorker.ready).then(() => true)"))
        assertEquals("\"hit-1\"", evalAsync(a, "fetch('/cached').then(r => r.text())"))
        assertEquals("\"hit-1\"", evalAsync(a, "fetch('/cached').then(r => r.text())"))
        load(a, server.url.resolve("/auth").toString()) { _, handler -> handler.proceed("user", "secret") }
        assertEquals(1, authCallbacks[a])

        val b = open(second)
        assertNotEquals(onMain { WebViewCompat.getProfile(a).name }, onMain { WebViewCompat.getProfile(b).name })
        assertNothingVisible(b)
        assertEquals("\"hit-2\"", evalAsync(b, "fetch('/cached').then(r => r.text())"))
        load(b, server.url.resolve("/auth").toString()) { _, handler -> handler.cancel() }
        assertEquals("HTTP-auth state leaked between profiles", 1, authCallbacks[b])

        // A relaunched shell (new WebView, same profile) sees its persistent state.
        close(a)
        val relaunched = open(first)
        assertEquals("\"a\"", eval(relaunched, "localStorage.getItem('k')"))
        assertTrue(eval(relaunched, "document.cookie").contains("k=a"))
        assertEquals("\"a\"", evalAsync(relaunched, IDB_READ))
        assertEquals("true", evalAsync(relaunched, "caches.has('c')"))
        assertEquals("1", evalAsync(relaunched,
            "navigator.serviceWorker.getRegistrations().then(r => r.length)"))
    }

    @Test fun deletingOneProfileLeavesTheOtherUntouched() {
        val kept = newName()
        val deleted = newName()
        writeEverything(open(kept), "kept")
        val victim = open(deleted)
        writeEverything(victim, "gone")
        close(victim)

        // A compatible provider must accept deletion once no WebView uses the profile.
        val result = deleteEventually(deleted)
        assertNotNull("Provider kept the profile loaded after its WebViews closed", result)
        assertTrue(result!!)
        assertFalse(onMain { ProfileStore.getInstance().allProfileNames.contains(deleted) })
        assertTrue(onMain { ProfileStore.getInstance().allProfileNames.contains(kept) })
        val survivor = open(kept)
        assertEquals("\"kept\"", eval(survivor, "localStorage.getItem('k')"))
        assertTrue(eval(survivor, "document.cookie").contains("k=kept"))
        assertEquals("\"kept\"", evalAsync(survivor, IDB_READ))
        assertEquals("true", evalAsync(survivor, "caches.has('c')"))
    }

    @Test fun disposableDiscoveryDataNeverAppearsInRuntimeProfile() {
        val disposable = ProfileNames.disposable(UUID.randomUUID().toString()).also(names::add)
        val discovery = open(disposable)
        writeEverything(discovery, "discovery")
        close(discovery)

        val runtime = open(newName())
        assertNothingVisible(runtime)
    }

    @Test fun thirdPartyCookieSettingIsPerProfile() {
        val first = open(newName())
        val second = open(newName())
        onMain {
            backend.setThirdPartyCookies(first, true)
            backend.setThirdPartyCookies(second, false)
            assertTrue(WebViewCompat.getProfile(first).cookieManager.acceptThirdPartyCookies(first))
            assertFalse(WebViewCompat.getProfile(second).cookieManager.acceptThirdPartyCookies(second))
        }
    }

    private fun writeEverything(webView: WebView, value: String) {
        eval(webView, "document.cookie = 'k=$value; Secure; SameSite=Strict; max-age=3600'")
        eval(webView, "localStorage.setItem('k', '$value')")
        eval(webView, "sessionStorage.setItem('k', '$value')")
        assertEquals("true", evalAsync(webView, """new Promise((resolve, reject) => {
            const open = indexedDB.open('d', 1);
            open.onupgradeneeded = () => open.result.createObjectStore('s');
            open.onsuccess = () => {
                const tx = open.result.transaction('s', 'readwrite');
                tx.objectStore('s').put('$value', 'k');
                tx.oncomplete = () => { open.result.close(); resolve(true); };
                tx.onerror = () => reject(tx.error);
            };
            open.onerror = () => reject(open.error);
        })"""))
        assertEquals("true", evalAsync(webView,
            "caches.open('c').then(c => c.put('/x', new Response('$value'))).then(() => true)"))
    }

    private fun assertNothingVisible(webView: WebView) {
        assertFalse(eval(webView, "document.cookie").contains("k="))
        assertEquals("null", eval(webView, "localStorage.getItem('k')"))
        assertEquals("null", eval(webView, "sessionStorage.getItem('k')"))
        assertEquals("\"absent\"", evalAsync(webView, IDB_READ))
        assertEquals("false", evalAsync(webView, "caches.has('c')"))
        assertEquals("0", evalAsync(webView, "navigator.serviceWorker.getRegistrations().then(r => r.length)"))
    }

    private fun newName() = ProfileNames.app(UUID.randomUUID().toString(), 0).also(names::add)

    private fun open(name: String): WebView {
        val webView = onMain {
            backend.createBoundWebView(instrumentation.targetContext, name).apply {
                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }
        }
        webViews += webView
        load(webView, server.url.toString())
        return webView
    }

    private fun close(webView: WebView) {
        webViews -= webView
        onMain { webView.destroy() }
    }

    private fun load(webView: WebView, url: String,
        onAuth: (WebView, HttpAuthHandler) -> Unit = { _, handler -> handler.cancel() }) {
        val finished = CountDownLatch(1)
        onMain {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = finished.countDown()
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
                    host: String, realm: String) {
                    authCallbacks[view] = (authCallbacks[view] ?: 0) + 1
                    onAuth(view, handler)
                }
            }
            webView.loadUrl(url)
        }
        assertTrue("Page did not load: $url", finished.await(15, TimeUnit.SECONDS))
    }

    private fun eval(webView: WebView, script: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        onMain { webView.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("Script timed out", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    /** Evaluates a promise-valued expression and returns its JSON result. */
    private fun evalAsync(webView: WebView, expression: String): String {
        eval(webView, "window.__r = undefined; Promise.resolve().then(() => ($expression))" +
            ".then(v => { window.__r = JSON.stringify(v); }, e => { window.__r = JSON.stringify('ERR ' + e); })")
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val value = eval(webView, "window.__r === undefined ? null : window.__r")
            if (value != "null") return value.removeSurrounding("\"").replace("\\\"", "\"")
            SystemClock.sleep(100)
        }
        throw AssertionError("Promise did not settle: $expression")
    }

    /** `null` while the provider still reports the profile as loaded. */
    private fun deleteEventually(name: String): Boolean? {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            try {
                return onMain { ProfileStore.getInstance().deleteProfile(name) }
            } catch (_: IllegalStateException) {
                if (SystemClock.uptimeMillis() > deadline) return null
                SystemClock.sleep(200)
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    companion object {
        private const val IDB_READ = """new Promise((resolve, reject) => {
            const open = indexedDB.open('d', 1);
            let created = false;
            open.onupgradeneeded = () => { created = true; open.result.createObjectStore('s'); };
            open.onsuccess = () => {
                if (created) { open.result.close(); indexedDB.deleteDatabase('d'); resolve('absent'); return; }
                const get = open.result.transaction('s').objectStore('s').get('k');
                get.onsuccess = () => { open.result.close(); resolve(get.result); };
                get.onerror = () => reject(get.error);
            };
            open.onerror = () => reject(open.error);
        })"""
    }
}
