package nu.staldal.mws

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Include environment identity in the raw instrumentation report, with hard prerequisites. */
@RunWith(AndroidJUnit4::class)
class ProviderEvidenceInstrumentedTest {
    @Test fun recordProviderAndDeviceIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val snapshot = AndroidProviderSnapshotSource(instrumentation.targetContext).snapshot()
        val key = snapshot.key
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "\nQUALIFICATION: provider=$key; model=${Build.MODEL}; " +
                "android=${Build.VERSION.RELEASE}; abi=${Build.SUPPORTED_ABIS.joinToString()}; " +
                "debugProviderOverride=${BuildConfig.DEBUG_PROVIDER_OVERRIDE}; webkit=${BuildConfig.ANDROIDX_WEBKIT_VERSION}; date=${Instant.now()}; " +
                "sourceRevision=${InstrumentationRegistry.getArguments().getString("sourceRevision", "UNRECORDED")}\n")
        })
        assertNotNull("An installed WebView provider is required", key)
        assertTrue("MULTI_PROFILE must be supported; skips cannot qualify a provider", snapshot.multiProfile)
    }
}
