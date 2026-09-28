package nu.staldal.mws

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class NewWindowTransportTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun request(url: String, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun getMethod() = method
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun transportRoutesInAndOutOfScopeOnlyAfterCancellation() {
        for ((url, inside) in listOf("https://example.com/app/next" to true, "https://other.example/" to false)) {
            val owner = BoundWebView(WebView(context), "app-profile")
            val child = BoundWebView(WebView(context), owner.profileName)
            var released = false
            var result: Pair<String, Boolean>? = null
            val routing = NewWindowRouting({ child }, { owner }, { released = true; it.webView.destroy() },
                { "https://example.com/app/" }, { target, scope ->
                    assertTrue(released); result = target to scope
                }, { fail("unexpected rejection") })
            val transport = owner.webView.WebViewTransport()
            val message = Handler(Looper.getMainLooper()).obtainMessage(0, transport)
            assertTrue(routing.open(owner.webView, true, message))
            assertSame(child.webView, transport.webView)
            assertTrue(child.webView.settings.blockNetworkLoads)
            assertFalse(child.webView.settings.javaScriptEnabled)
            assertTrue(child.webView.webViewClient.shouldOverrideUrlLoading(child.webView, request(url)))
            assertNull(result)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(url to inside, result)
            routing.close(); owner.webView.destroy()
        }
    }

    @Test fun ungesturedAndFailedFeatureRequestsNeverCreateProbe() {
        val owner = BoundWebView(WebView(context), "app-profile")
        var creates = 0
        var rejected = 0
        val routing = NewWindowRouting({ creates++; null }, { owner }, {},
            { "https://example.com/" }, { _, _ -> fail() }, { rejected++ })
        val message = Handler(Looper.getMainLooper()).obtainMessage(0, owner.webView.WebViewTransport())
        assertFalse(routing.open(owner.webView, false, message))
        assertEquals(0, creates)
        assertFalse(routing.open(owner.webView, true, message))
        assertEquals(1, creates)
        assertEquals(1, rejected)
        routing.close(); owner.webView.destroy()
    }

    @Test fun httpGetsRequiredMessageAndOnlyOneProbeMayBePending() {
        val owner = BoundWebView(WebView(context), "app-profile")
        val child = BoundWebView(WebView(context), "app-profile")
        var creates = 0
        var message: Int? = null
        val routing = NewWindowRouting({ creates++; child }, { owner }, {},
            { "https://example.com/" }, { _, _ -> fail() }, { message = it })
        fun transport() = Handler(Looper.getMainLooper()).obtainMessage(0, owner.webView.WebViewTransport())
        assertTrue(routing.open(owner.webView, true, transport()))
        assertFalse(routing.open(owner.webView, true, transport()))
        assertEquals(1, creates)
        assertTrue(child.webView.webViewClient.shouldOverrideUrlLoading(child.webView,
            request("http://example.com/")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(R.string.shell_https_required, message)
        routing.close(); owner.webView.destroy(); child.webView.destroy()
    }

    @Test fun interceptionFirstCancelsPostAndLaterGet() {
        val view = WebView(context)
        val results = mutableListOf<String?>()
        val client = WindowProbeClient(finish = { results.add(it) })
        val response = client.shouldInterceptRequest(view, request("https://example.com/", "POST"))
        assertEquals(204, response.statusCode)
        assertEquals(-1, response.data.read())
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://example.com/next")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it == null })
        view.destroy()
    }

    @Test fun probeLogsOnlyDisallowedSchemes() {
        val view = WebView(context)
        ShadowLog.clear()
        for (url in listOf("https://example.com/", "http://example.com/", "intent://x#Intent;end"))
            WindowProbeClient(finish = {}).shouldOverrideUrlLoading(view, request(url))
        val logged = ShadowLog.getLogsForTag("ShellNavigation")
        assertEquals(1, logged.size)
        assertEquals(android.util.Log.INFO, logged[0].type)
        assertTrue(logged[0].msg.startsWith("Blocked navigation with disallowed scheme intent"))
        view.destroy()
    }

    @Test fun closingCancelsDeferredAcceptedDestination() {
        val owner = BoundWebView(WebView(context), "app-profile")
        val child = BoundWebView(WebView(context), "app-profile")
        var released = 0
        val routing = NewWindowRouting({ child }, { owner }, { released++ },
            { "https://example.com/" }, { _, _ -> fail("late routing") }, { fail("late rejection") })
        assertTrue(routing.open(owner.webView, true,
            Handler(Looper.getMainLooper()).obtainMessage(0, owner.webView.WebViewTransport())))
        assertTrue(child.webView.webViewClient.shouldOverrideUrlLoading(child.webView,
            request("https://example.com/next")))
        routing.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, released)
        owner.webView.destroy(); child.webView.destroy()
    }

    @Test fun unknownDestinationTimesOut() {
        val owner = BoundWebView(WebView(context), "app-profile")
        val child = BoundWebView(WebView(context), "app-profile")
        var released = 0
        var rejected = 0
        val routing = NewWindowRouting({ child }, { owner }, { released++ },
            { "https://example.com/" }, { _, _ -> fail() }, { rejected++ })
        assertTrue(routing.open(owner.webView, true,
            Handler(Looper.getMainLooper()).obtainMessage(0, owner.webView.WebViewTransport())))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, rejected)
        assertEquals(1, released)
        routing.close(); owner.webView.destroy(); child.webView.destroy()
    }
}
