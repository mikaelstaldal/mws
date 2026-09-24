package nu.staldal.mws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCompatibilityTest {
    private val key = ProviderKey("com.example.webview", "130.0.2", 35, "arm64-v8a")
    private var snapshot = ProviderSnapshot(key, true)
    private var selfTest: ProviderSelfTestResult? = passed(key)
    private val gate = ProviderCompatibility(
        ProviderSnapshotSource { snapshot },
        ProviderSelfTestStore { selfTest },
        mapOf(key.packageName to listOf(130, 0, 1)),
    )

    @Test fun multiProfileIsMandatory() {
        snapshot = ProviderSnapshot(key, false)
        assertEquals(ProviderBlockReason.NO_MULTI_PROFILE, gate.current().reason)
        assertFalse(gate.allowed(WebOperation.LAUNCH))
    }

    @Test fun belowFloorAndUnlistedPackagesFailClosed() {
        snapshot = ProviderSnapshot(key.copy(version = "129.9"), true)
        assertEquals(ProviderBlockReason.BELOW_FLOOR, gate.current().reason)
        snapshot = ProviderSnapshot(key.copy(packageName = "com.other.webview"), true)
        assertEquals(ProviderBlockReason.UNLISTED_PROVIDER, gate.current().reason)
    }

    @Test fun staleOrFailedSelfTestFailsClosed() {
        selfTest = null
        assertEquals(ProviderBlockReason.SELF_TEST_PENDING, gate.current().reason)
        selfTest = passed(key.copy(version = "130.0.1"))
        assertEquals(ProviderBlockReason.SELF_TEST_PENDING, gate.current().reason)
        selfTest = passed(key).copy(profileDeletion = false)
        assertEquals(ProviderBlockReason.DELETION_FAILED, gate.current().reason)
    }

    @Test fun compatibilityLossBlocksProfileOperationsButNotRemoval() {
        assertTrue(gate.allowed(WebOperation.LAUNCH))
        snapshot = ProviderSnapshot(key, false)
        for (operation in listOf(WebOperation.LAUNCH, WebOperation.DISCOVERY, WebOperation.CLEAR)) {
            assertFalse(gate.allowed(operation))
        }
        assertTrue(gate.mayRemoveConfiguration())
        assertFalse(gate.mayAccessProfile())
        assertFalse(gate.current().features.profileIsolation)
    }

    @Test fun optionalFeaturesFailIndependently() {
        selfTest = passed(key).copy(basicAuth = false, formFill = false,
            newWindowRouting = false, discoveryCookies = false)
        val features = gate.current().features
        assertTrue(features.profileIsolation)
        assertFalse(features.basicAuth)
        assertFalse(features.formFill)
        assertFalse(features.newWindowRouting)
        assertFalse(features.discoveryCookies)
    }

    @Test fun debugOverrideStillRequiresMultiProfileAndRejectsFailedIsolation() {
        val debug = ProviderCompatibility(ProviderSnapshotSource { snapshot },
            ProviderSelfTestStore { selfTest }, emptyMap(), debugOverride = true)
        selfTest = null
        assertTrue(debug.allowed(WebOperation.LAUNCH))
        snapshot = ProviderSnapshot(key, false)
        assertFalse(debug.allowed(WebOperation.LAUNCH))
        snapshot = ProviderSnapshot(key, true)
        selfTest = passed(key).copy(profileIsolation = false)
        assertFalse(debug.allowed(WebOperation.LAUNCH))
        selfTest = passed(key).copy(profileDeletion = false)
        assertFalse(debug.allowed(WebOperation.LAUNCH))
    }

    @Test fun missingProviderAndMalformedVersionFailClosed() {
        snapshot = ProviderSnapshot(null, true)
        assertEquals(ProviderBlockReason.NO_PROVIDER, gate.current().reason)
        snapshot = ProviderSnapshot(key.copy(version = "130.0-beta"), true)
        assertEquals(ProviderBlockReason.INVALID_VERSION, gate.current().reason)
    }

    private fun passed(key: ProviderKey) = ProviderSelfTestResult(key, true, true, true, true, true, true)
}
