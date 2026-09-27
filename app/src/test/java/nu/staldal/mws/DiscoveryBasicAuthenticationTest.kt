package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.Config(sdk = [31])
@RunWith(RobolectricTestRunner::class)
class DiscoveryBasicAuthenticationTest {
    private class Answer : AuthAnswer {
        var cancelled = false
        override fun cancel() { cancelled = true }
        override fun proceed(username: String, password: String) { fail("Unexpected credential") }
    }

    private fun auth(trigger: DiscoveryTrigger = DiscoveryTrigger.REFRESH,
        client: () -> MyPassClient? = { null }, required: () -> Unit = {}) =
        DiscoveryBasicAuthentication(DiscoveryAttempt(1,
            requireNotNull(UrlPolicy.parseHttps("https://example.com/start")), trigger),
            client, {}, {}, required).also { it.arm() }

    @Test fun debounceNeverRequestsMyPass() {
        var lookups = 0
        var notes = 0
        val auth = auth(DiscoveryTrigger.DEBOUNCE, { lookups++; null }, { notes++ })
        auth.intercepted("https://example.com/start")
        val answer = Answer()
        auth.challenge(answer, "example.com", "private")
        assertTrue(answer.cancelled)
        assertEquals(0, lookups)
        assertEquals(1, notes)
    }

    @Test fun crossOriginRedirectAndSubresourceCancelWithoutLookup() {
        for (redirect in listOf(true, false)) {
            var lookups = 0
            val auth = auth(client = { lookups++; null })
            auth.intercepted("https://example.com/start")
            if (redirect) auth.redirect("https://other.example/login")
            else auth.intercepted("https://example.com/frame")
            val answer = Answer()
            auth.challenge(answer, if (redirect) "other.example" else "example.com", "private")
            assertTrue(answer.cancelled)
            assertEquals(0, lookups)
        }
    }

    @Test fun sameOriginRedirectUsesEligibleLookupAndAbsenceFallsBack() {
        var lookups = 0
        val auth = auth(client = { lookups++; null })
        auth.redirect("https://example.com/private")
        auth.intercepted("https://example.com/private")
        val answer = Answer()
        auth.challenge(answer, "example.com", "private")
        assertTrue(answer.cancelled)
        assertEquals(1, lookups)
        assertNull(auth.credential("https://example.com/icon", "private"))
        auth.close()
    }
    @Test fun exactRedirectLookupPausesTimersAndRetainsOnlySameRealmUntilClose() {
        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        var requestCode = 0
        var intent: android.content.Intent? = null
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int,
                contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) {
                requestCode = code
                intent = contract.createIntent(activity, input)
            }
        }
        val client = MyPassClient(activity, { true }, registry = registry)
        val state = DiscoveryAttempt(2, requireNotNull(UrlPolicy.parseHttps("https://example.com/start")), DiscoveryTrigger.FOCUS_LOSS)
        state.start(0)
        val auth = DiscoveryBasicAuthentication(state, { client }, { state.pause(5_000) },
            { state.resume(65_000) }, {})
        auth.arm()
        val target = "https://example.com/private?next=%2F"
        auth.redirect(target)
        auth.intercepted(target)
        var supplied = false
        auth.challenge(object : AuthAnswer {
            override fun proceed(username: String, password: String) { supplied = password == "secret" }
            override fun cancel() { fail("Eligible credential cancelled") }
        }, "example.com", "private")
        assertEquals(target, intent!!.getStringExtra(MyPassClient.EXTRA_URL))
        assertEquals("private", intent!!.getStringExtra(MyPassClient.EXTRA_REALM))
        assertTrue(state.isPaused)
        assertEquals(10_000L, state.remainingInitial(60_000))
        assertEquals(25_000L, state.remainingOverall(60_000))
        registry.dispatchResult(requestCode, android.app.Activity.RESULT_OK, android.content.Intent()
            .putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "user")
            .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, "secret"))
        assertTrue(supplied)
        assertFalse(state.isPaused)
        auth.documentEnded()
        assertEquals("user" to "secret", auth.credential("https://example.com/icon", "private"))
        assertNull(auth.credential("https://example.com/icon", "other"))
        assertNull(auth.credential("https://other.example/icon", "private"))
        auth.redirect("https://other.example/away")
        auth.redirect("https://example.com/back")
        assertNull(auth.credential("https://example.com/icon", "private"))
        auth.close()
        assertNull(auth.credential("https://example.com/icon", "private"))
        client.close()
    }

    @Test fun failedProviderProbeNeverLooksUpCredential() {
        var lookups = 0
        val auth = DiscoveryBasicAuthentication(DiscoveryAttempt(3,
            requireNotNull(UrlPolicy.parseHttps("https://example.com/start")), DiscoveryTrigger.REFRESH),
            { lookups++; null }, {}, {}, {}, { false })
        auth.arm()
        auth.intercepted("https://example.com/start")
        val answer = Answer()
        auth.challenge(answer, "example.com", "private")
        assertTrue(answer.cancelled)
        assertEquals(0, lookups)
    }

}
