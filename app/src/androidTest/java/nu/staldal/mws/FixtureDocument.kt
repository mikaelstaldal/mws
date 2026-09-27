package nu.staldal.mws

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Query the actual loaded fixture, rather than treating WebView.url as a load-complete signal. */
internal fun fixtureDocumentState(webView: WebView): String {
    val result = AtomicReference("<JavaScript callback timed out>")
    val done = CountDownLatch(1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        // Never let a query exception escape the main-thread runnable: diagnostics must
        // still finish when the WebView is detached or the renderer has failed.
        try {
            webView.evaluateJavascript(
                "JSON.stringify({url:location.href,ready:document.readyState,fixture:!!document.getElementById('fixture-ready')})",
            ) { value -> result.set(value ?: "null"); done.countDown() }
        } catch (error: Exception) {
            result.set("<JavaScript query failed: ${error.javaClass.simpleName}>")
            done.countDown()
        }
    }
    done.await(1, TimeUnit.SECONDS)
    return result.get()
}

internal fun fixtureDocumentReady(webView: WebView, url: String): Boolean = runCatching {
    // evaluateJavascript encodes the returned JSON string once more for its callback.
    val encoded = org.json.JSONTokener(fixtureDocumentState(webView)).nextValue() as String
    val state = JSONObject(encoded)
    state.getString("url") == url && state.getString("ready") == "complete" && state.getBoolean("fixture")
}.getOrDefault(false)
