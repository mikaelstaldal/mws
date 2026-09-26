package nu.staldal.mws

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production probes against the installed WebView with network loads blocked. */
@RunWith(AndroidJUnit4::class)
class RuntimeSelfTestInstrumentedTest {
    @Test fun newWindowProbePassesWithoutNetwork() = runBlocking {
        assumeTrue("MULTI_PROFILE", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("NewWindowProbe", NewWindowProbe(context, ShellServices.shared(context).profiles).run())
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
