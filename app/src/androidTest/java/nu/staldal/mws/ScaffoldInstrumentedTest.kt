package nu.staldal.mws

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.webkit.WebView
import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ScaffoldInstrumentedTest {
    @Test fun localHttpsServerServesControlledResponses() {
        LocalHttpsServer().use { server ->
            server.enqueue("ready")
            val response = server.client.newCall(
                okhttp3.Request.Builder().url(server.url).build()
            ).execute()
            response.use {
                assertEquals(200, it.code)
                assertEquals("ready", it.body?.string())
            }
        }
    }

    @Test fun platformRejectsCleartext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        for (host in listOf("example.com", "localhost", "127.0.0.1", "10.0.2.2")) {
            assertFalse(policy.isCleartextTrafficPermitted(host))
        }
        assertEquals("nu.staldal.mws.testbed", context.packageName)
    }

    @Test fun webViewTrustsOnlyTheDebugTestCa() {
        LocalHttpsServer().use { server ->
            server.enqueue("<html><body>ready</body></html>")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val finished = CountDownLatch(1)
            val pageText = AtomicReference<String>()
            lateinit var webView: WebView
            instrumentation.runOnMainSync {
                webView = WebView(instrumentation.targetContext)
                // The assertion reads the document through evaluateJavascript; WebView defaults
                // to JavaScript disabled and can return null even after a successful HTTPS load.
                @Suppress("SetJavaScriptEnabled")
                webView.settings.javaScriptEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript("document.body.textContent") {
                            pageText.set(it)
                            finished.countDown()
                        }
                    }
                }
                webView.loadUrl(server.url.toString())
            }
            try {
                assertTrue("HTTPS page did not load", finished.await(15, TimeUnit.SECONDS))
                assertEquals("\"ready\"", pageText.get())
            } finally {
                instrumentation.runOnMainSync { webView.destroy() }
            }
        }
    }
}
