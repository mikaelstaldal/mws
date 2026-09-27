package nu.staldal.mws

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ProviderSelfTestTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun failedFormFillProbeLeavesIsolationUsable() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        MaintenanceStore(context).use { store ->
            val probes = listOf("isolation", "deletion", "formFill").map { feature ->
                object : ProviderProbe {
                    override val name = feature
                    override suspend fun run() = feature != "formFill"
                }
            }
            ProviderSelfTestRunner({ ProviderSnapshot(key, true) }, store, probes).runIfNeeded()
            val result = SelfTestEncoding.decode(key, store.selfTest())!!
            assertTrue(result.profileIsolation)
            assertTrue(result.profileDeletion)
            assertFalse(result.formFill)
            val compatibility = ProviderCompatibility({ ProviderSnapshot(key, true) }, { result },
                floors = mapOf("provider" to listOf(1)))
            assertTrue(compatibility.current().compatible)
        }
    }

    @Test fun failedBasicAuthProbeDisablesOnlyBasicAuth() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        MaintenanceStore(context).use { store ->
            val probes = listOf("isolation", "deletion", "basicAuth", "formFill").map { feature ->
                object : ProviderProbe {
                    override val name = feature
                    override suspend fun run() = feature != "basicAuth"
                }
            }
            ProviderSelfTestRunner({ ProviderSnapshot(key, true) }, store, probes).runIfNeeded()
            val result = SelfTestEncoding.decode(key, store.selfTest())!!
            val features = ProviderCompatibility({ ProviderSnapshot(key, true) }, { result },
                floors = mapOf("provider" to listOf(1))).current().features
            assertFalse(features.basicAuth)
            assertTrue(features.formFill)
            // Below the floor every feature, including Basic auth, is off.
            assertFalse(ProviderCompatibility({ ProviderSnapshot(key, true) }, { result.copy(basicAuth = true) },
                floors = mapOf("provider" to listOf(2))).current().features.basicAuth)
        }
    }

    @Test fun firstStartAndKeyChangeRunOnceAndLeaveOptionalFeaturesDisabled() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        var snapshot = ProviderSnapshot(key, true)
        var isolationRuns = 0
        var deletionRuns = 0
        MaintenanceStore(context).use { store ->
            val runner = ProviderSelfTestRunner({ snapshot }, store, listOf(
                object : ProviderProbe {
                    override val name = "isolation"
                    override suspend fun run(): Boolean { isolationRuns++; return true }
                },
                object : ProviderProbe {
                    override val name = "deletion"
                    override suspend fun run(): Boolean { deletionRuns++; return true }
                },
            ))
            runner.runIfNeeded()
            runner.runIfNeeded()
            assertEquals(1, isolationRuns)
            assertEquals(1, deletionRuns)
            val result = SelfTestEncoding.decode(key, store.selfTest())!!
            assertTrue(result.profileIsolation)
            assertTrue(result.profileDeletion)
            assertFalse(result.basicAuth)
            assertFalse(result.newWindowRouting)
            assertFalse(result.formFill)
            snapshot = ProviderSnapshot(key.copy(version = "1.3"), true)
            assertNull(SelfTestEncoding.decode(snapshot.key!!, store.selfTest()))
            runner.runIfNeeded()
            assertEquals(2, isolationRuns)
            assertEquals(2, deletionRuns)
        }
    }

    @Test fun observedProbeFailureIsCachedAndBlocksCompatibility() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        MaintenanceStore(context).use { store ->
            ProviderSelfTestRunner({ ProviderSnapshot(key, true) }, store, listOf(
                object : ProviderProbe {
                    override val name = "isolation"
                    override suspend fun run(): Boolean = false
                },
                object : ProviderProbe {
                    override val name = "deletion"
                    override suspend fun run(): Boolean = true
                },
            )).runIfNeeded()
            val gate = ProviderCompatibility({ ProviderSnapshot(key, true) },
                { requested -> SelfTestEncoding.decode(requested, store.selfTest()) },
                mapOf("provider" to listOf(1)))
            assertEquals(ProviderBlockReason.ISOLATION_FAILED, gate.current().reason)
            val result = SelfTestEncoding.decode(key, store.selfTest())!!
            assertFalse(result.profileIsolation)
            assertTrue(result.profileDeletion)
        }
    }

    @Test fun probeExceptionLeavesResultPendingForRetry() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        var attempts = 0
        MaintenanceStore(context).use { store ->
            val runner = ProviderSelfTestRunner({ ProviderSnapshot(key, true) }, store,
                listOf(object : ProviderProbe {
                    override val name = "isolation"
                    override suspend fun run(): Boolean {
                        attempts++
                        if (attempts == 1) error("transient failure")
                        return true
                    }
                }))
            runner.runIfNeeded()
            assertNull(store.selfTest())
            runner.runIfNeeded()
            assertEquals(2, attempts)
            assertTrue(SelfTestEncoding.decode(key, store.selfTest())!!.profileIsolation)
        }
    }

    @Test fun probeTimeoutLeavesResultPendingForRetry() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        MaintenanceStore(context).use { store ->
            ProviderSelfTestRunner({ ProviderSnapshot(key, true) }, store,
                listOf(object : ProviderProbe {
                    override val name = "deletion"
                    override suspend fun run(): Boolean = withTimeout(1) {
                        delay(20)
                        true
                    }
                })).runIfNeeded()
            assertNull(store.selfTest())
        }
    }

    @Test fun noProfileCapabilityNeverRunsProbes() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        MaintenanceStore(context).use { store ->
            ProviderSelfTestRunner({ ProviderSnapshot(key, false) }, store,
                listOf(object : ProviderProbe {
                    override val name = "isolation"
                    override suspend fun run(): Boolean = error("unexpected probe")
                })).runIfNeeded()
            assertNull(store.selfTest())
        }
    }

    @Test fun olderProbeSuiteResultIsRerunForTheSameProviderKey() = runBlocking {
        val key = ProviderKey("provider", "1.2", 35, "arm64")
        MaintenanceStore(context).use { store ->
            store.putSelfTest(RuntimeSelfTest(SelfTestEncoding.key(key),
                mapOf("isolation" to true, "deletion" to false)))
            assertNull(SelfTestEncoding.decode(key, store.selfTest()))
            var runs = 0
            ProviderSelfTestRunner({ ProviderSnapshot(key, true) }, store,
                listOf(object : ProviderProbe {
                    override val name = "deletion"
                    override suspend fun run(): Boolean { runs++; return true }
                })).runIfNeeded()
            assertEquals(1, runs)
            assertTrue(SelfTestEncoding.decode(key, store.selfTest())!!.profileDeletion)
        }
    }
}
