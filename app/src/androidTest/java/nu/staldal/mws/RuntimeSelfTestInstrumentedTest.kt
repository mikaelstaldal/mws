package nu.staldal.mws

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production probes against the installed WebView with network loads blocked. */
@RunWith(AndroidJUnit4::class)
class RuntimeSelfTestInstrumentedTest {
    @Test fun newWindowProbePassesWithoutNetwork() = runBlocking {
        assumeTrue("MULTI_PROFILE", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failure = NewWindowProbe(context, ShellServices.shared(context).profiles).diagnose()
        org.junit.Assert.assertNull("NewWindowProbe: $failure", failure)
    }

    /** A reachable, trusted listener as the destination: every strict case must send it zero requests. */
    @Test fun newWindowProbeSendsNothingToControlledListener() = runBlocking {
        assumeTrue("MULTI_PROFILE", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalHttpsServer().use { server ->
            val requests = AtomicInteger()
            server.dispatch { requests.incrementAndGet(); MockResponse() }
            val target = server.url.resolve("/window")!!.toString()
            val failure = NewWindowProbe(context, ShellServices.shared(context).profiles).diagnose(target)
            org.junit.Assert.assertNull("NewWindowProbe: $failure", failure)
            // A leaked request would be dispatched well within this quiet period.
            delay(2_000)
            assertEquals(0, requests.get())
        }
    }

    @Test fun formFillProbePassesWithoutNetwork() = runBlocking {
        assumeTrue("MULTI_PROFILE", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("FormFillProbe", FormFillProbe(context, ShellServices.shared(context).profiles).run())
    }

    @Test fun basicAuthProbePassesWithoutNetwork() = runBlocking {
        assumeTrue("MULTI_PROFILE", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("BasicAuthProbe", BasicAuthProbe(context, ShellServices.shared(context).profiles).run())
    }

    @Test fun isolationAndDeletionProbesPassWithoutNetwork() = runBlocking {
        assumeTrue("MULTI_PROFILE", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = ShellServices.shared(context)
        assertTrue("IsolationProbe", IsolationProbe(context, services.profiles).run())
        assertEquals(DeletionProbe.Outcome.PASSED, DeletionProbe(context).diagnose())
    }
}
