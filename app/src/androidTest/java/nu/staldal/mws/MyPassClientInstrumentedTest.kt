package nu.staldal.mws

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MyPassClientInstrumentedTest {
    @Test fun unavailableAndInsecureRequestsNeverLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var launches = 0
                val client = MyPassClient(activity, { false }, { _, _ -> launches++ })
                var outcome: MyPassClient.Outcome? = null
                assertFalse(client.fetch(id(), "https://example.com/", isCurrent = { true }) {
                    outcome = it
                })
                assertEquals(MyPassClient.Outcome.Unavailable, outcome)
                assertFalse(client.fetch(id(), "http://example.com/", isCurrent = { true }) {
                    outcome = it
                })
                assertEquals(0, launches)
                client.close()
            }
        }
    }

    @Test fun disappearanceAndSignatureFailureAreUnavailable() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val client = MyPassClient(activity, { true }, { _, _ -> throw failure })
                    var outcome: MyPassClient.Outcome? = null
                    assertFalse(client.fetch(id(), "https://example.com/", isCurrent = { true }) {
                        outcome = it
                    })
                    assertEquals(MyPassClient.Outcome.Unavailable, outcome)
                    client.close()
                }
            }
        }
    }

    @Test fun timeoutAndCancellationLeaveOnlyNonSecretIdentityState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val timedOut = CountDownLatch(1)
            lateinit var client: MyPassClient
            scenario.onActivity { activity ->
                client = MyPassClient(activity, { true }, { _, _ -> }, 10L)
                val identity = id()
                assertTrue(client.fetch(identity, "https://example.com/", isCurrent = { true }) {
                    assertEquals(MyPassClient.Outcome.NoCredential, it)
                    timedOut.countDown()
                })
                assertFalse(client.fetch(identity, "https://example.com/", isCurrent = { true }) {})
            }
            assertTrue(timedOut.await(5, TimeUnit.SECONDS))
            scenario.onActivity {
                val saved = Bundle()
                client.saveIdentity(saved)
                assertFalse(saved.toString().contains("example.com"))
                assertFalse(saved.toString().contains("password"))
                assertTrue(saved.keySet().all { it.startsWith("mypass.") })
                client.cancel()
                client.close()
            }
        }
    }

    @Test fun lateCredentialResultIsDrainedBeforeRegistrySavesState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val registry = CapturingRegistry()
                val client = MyPassClient(activity, { true }, registry = registry)
                var callbacks = 0
                assertTrue(client.fetch(id(), "https://example.com/", isCurrent = { true }) {
                    callbacks++
                })
                val requestCode = registry.requestCode
                client.cancel()
                registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent()
                    .putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "alice")
                    .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, "secret"))
                assertEquals(0, callbacks)
                val clientState = Bundle()
                client.saveIdentity(clientState)
                assertTrue(clientState.getStringArrayList("mypass.launchIds").isNullOrEmpty())
                val registryState = Bundle()
                registry.onSaveInstanceState(registryState)
                assertFalse(containsSecret(registryState))
                client.close()
            }
        }
    }

    @Test fun successfulCredentialIsClearedAfterCallbackAndNeverSaved() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val registry = CapturingRegistry()
                val client = MyPassClient(activity, { true }, registry = registry)
                var received: MyPassClient.Outcome.Credential? = null
                assertTrue(client.fetch(id(), "https://example.com/", isCurrent = { true }) {
                    received = it as MyPassClient.Outcome.Credential
                    assertEquals("secret", String(received!!.password))
                })
                registry.dispatchResult(registry.requestCode, Activity.RESULT_OK, Intent()
                    .putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "alice")
                    .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, "secret"))
                assertTrue(received!!.username.all { it == '\u0000' })
                assertTrue(received!!.password.all { it == '\u0000' })
                val clientState = Bundle()
                client.saveIdentity(clientState)
                val registryState = Bundle()
                registry.onSaveInstanceState(registryState)
                assertFalse(containsSecret(clientState))
                assertFalse(containsSecret(registryState))
                client.close()
            }
        }
    }

    private class CapturingRegistry : ActivityResultRegistry() {
        var requestCode = -1
            private set

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
        }
    }

    @Suppress("DEPRECATION")
    private fun containsSecret(value: Any?): Boolean = when (value) {
        is Bundle -> value.keySet().any { containsSecret(value.get(it)) }
        is Intent -> containsSecret(value.extras)
        is ArrayList<*> -> value.any(::containsSecret)
        is String -> value == "secret" || value == "alice"
        else -> false
    }

    private fun id() = MyPassClient.Identity("shell", "navigation-1", "document-1")
}
