package nu.staldal.mws

import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real HTTPS documents qualify message origin/frame reporting and the production fill helper. */
@RunWith(AndroidJUnit4::class)
class FormFillMessagingInstrumentedTest {
    @Test fun exactOriginMessagingFillsOnlyTopLevelDocumentOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val id = "qualification-form-${UUID.randomUUID()}"
        val name = ProfileNames.disposable(id)
        val maintenance = MaintenanceStore(context)
        maintenance.registerDiscoveryProfile(DiscoveryProfile(id, System.currentTimeMillis()))
        val backend = AndroidWebProfileBackend()
        var view: WebView? = null
        var engine: FormFillEngine? = null
        try {
            LocalHttpsServer().use { server ->
                LocalHttpsServer().use { cross ->
                    val origin = UrlPolicy.parseHttps(server.url.toString())!!.origin
                    val crossOrigin = UrlPolicy.parseHttps(cross.url.toString())!!.origin
                    cross.dispatch { MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><body><input id=user autocomplete=username><input id=pass type=password>
                        <script>parent.postMessage({kind:'cross',bridge:typeof qualificationOrigin!=='undefined'},'*');</script>
                        </body></html>
                    """.trimIndent()) }
                    server.dispatch { request ->
                        val body = if (request.path == "/frame") """
                            <html><body><input id=user autocomplete=username><input id=pass type=password>
                            <script>qualificationOrigin.postMessage('frame');</script></body></html>
                        """ else """
                            <html><body><form onsubmit="window.submitted=true;return false">
                            <input id=user autocomplete=username><input id=pass type=password autocomplete=current-password>
                            </form><script>window.events=0;window.submitted=false;window.crossResult=null;
                            document.querySelectorAll('input').forEach(e=>['input','change'].forEach(t=>e.addEventListener(t,()=>window.events++)));
                            window.addEventListener('message',e=>{if(e.origin==='$crossOrigin'&&e.data.kind==='cross')window.crossResult=e.data;});
                            qualificationOrigin.postMessage('top');</script>
                            <iframe id=same src='/frame'></iframe><iframe src='${cross.url}'></iframe></body></html>
                        """
                        MockResponse().setHeader("Content-Type", "text/html").setBody(body.trimIndent())
                    }
                    val messages = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
                    val lookups = CopyOnWriteArrayList<String>()
                    val finished = CountDownLatch(1)
                    val current = onMain {
                        backend.createBoundWebView(context, name).apply {
                            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                            layout(0, 0, 800, 600)
                        }
                    }
                    view = current
                    onMain {
                        WebViewCompat.addWebMessageListener(current, "qualificationOrigin", setOf(origin)) { _, message, source, mainFrame, _ ->
                            messages += Triple(message.data.orEmpty(), source.toString(), mainFrame)
                        }
                        val fill = FormFillEngine(current, { true }, { document, reply ->
                            lookups += document.url
                            val credential = MyPassClient.Outcome.Credential("fixture-user".toCharArray(), "fixture-pass".toCharArray())
                            try { reply(credential) } finally { credential.clear() }
                        }, {})
                        engine = fill
                        current.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) { fill.started(url) }
                            override fun onPageFinished(view: WebView, url: String) { fill.commit(url); finished.countDown() }
                        }
                        fill.navigate(server.url.toString()); current.loadUrl(server.url.toString())
                    }
                    assertTrue("HTTPS fixture did not finish", finished.await(15, TimeUnit.SECONDS))
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        ready = evaluate(current, "document.getElementById('user').value==='fixture-user' && " +
                            "document.getElementById('pass').value==='fixture-pass' && window.crossResult!==null") == "true"
                        if (!ready) Thread.sleep(50)
                    }
                    assertTrue("Form fill/reply or cross-origin frame did not complete", ready)
                    assertEquals(listOf(server.url.toString()), lookups)
                    assertEquals("4", evaluate(current, "window.events"))
                    assertEquals("false", evaluate(current, "window.submitted"))
                    assertEquals("false", evaluate(current, "window.crossResult.bridge"))
                    assertEquals("true", evaluate(current, "document.getElementById('same').contentDocument.getElementById('user').value===''"))
                    assertTrue("Top-level origin/frame report missing: $messages", messages.contains(Triple("top", origin, true)))
                    assertTrue("Same-origin subframe flag missing: $messages", messages.contains(Triple("frame", origin, false)))
                    assertEquals(2, messages.size)
                }
            }
        } finally {
            view?.let { current -> onMain {
                engine?.close()
                WebViewCompat.removeWebMessageListener(current, "qualificationOrigin")
                current.destroy()
                try {
                    ProfileStore.getInstance().deleteProfile(name)
                    maintenance.deleteDiscoveryProfile(id)
                } catch (_: IllegalStateException) { /* Durable discovery record retries deletion at restart. */ }
            } }
            maintenance.close()
        }
    }
    private fun evaluate(view: WebView, script: String): String {
        val value = AtomicReference<String>(); val done = CountDownLatch(1)
        onMain { view.evaluateJavascript(script) { value.set(it); done.countDown() } }
        assertTrue("JS callback missing", done.await(5, TimeUnit.SECONDS)); return value.get()
    }
    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }
}
