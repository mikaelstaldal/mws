package nu.staldal.mws

import nu.staldal.mws.BasicAuthGate.Challenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicAuthGateTest {
    private var gate = BasicAuthGate()

    /** Arms and reports the navigation's own main-document request, as the provider does. */
    private fun armed(url: String): BasicAuthGate.Armed? =
        gate.arm(url)?.also { gate.intercepted(url) }

    private fun lookup(host: String = "example.com", realm: String = "Private") =
        gate.challenge(host, realm) as Challenge.Lookup

    @Test fun lookupUsesExactCanonicalTopLevelUrlAndRealm() {
        val armed = armed("https://EXAMPLE.com:443/app/?q=1#frag")!!
        assertEquals(BasicAuthGate.Target("https://example.com/app/?q=1", "https", "example.com", 443), armed.target)
        val lookup = lookup(host = "Example.COM", realm = "Private")
        assertEquals(armed.identity, lookup.identity)
        assertEquals("https://example.com/app/?q=1", lookup.url)
        assertEquals("Private", lookup.realm)
    }

    @Test fun recordsEffectivePortAndIpv6Host() {
        assertEquals(8443, armed("https://example.com:8443/")!!.target.port)
        gate = BasicAuthGate()
        val armed = armed("https://[::1]:8443/x")!!
        assertEquals("::1", armed.target.host)
        assertEquals(8443, armed.target.port)
        assertEquals("https://[::1]:8443/x", lookup(host = "::1").url)
    }

    @Test fun unarmedMismatchedHostOrMissingRealmIsCancelled() {
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        armed("https://example.com/")
        assertEquals(Challenge.Cancel, gate.challenge("other.example", "Private"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com.evil", "Private"))
        assertEquals(Challenge.Cancel, gate.challenge(null, "Private"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", null))
        assertTrue(gate.challenge("example.com", "Private") is Challenge.Lookup)
    }

    @Test fun cleartextOrIneligibleNavigationNeverArms() {
        armed("https://example.com/")
        assertNull(gate.arm("http://example.com/"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        assertNull(armed("https://user:pass@example.com/"))
        assertNull(armed("https://bad_host.example/"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
    }

    @Test fun sameOriginRedirectReplacesRecordedUrl() {
        val armed = armed("https://example.com/start")!!
        assertTrue(gate.redirect("https://example.com/login?next=%2F"))
        val lookup = lookup()
        assertEquals(armed.identity, lookup.identity)
        assertEquals("https://example.com/login?next=%2F", lookup.url)
    }

    @Test fun crossOriginRedirectCannotInheritTheNavigation() {
        armed("https://example.com/start")
        assertFalse(gate.redirect("https://auth.example.com/login"))
        assertNull(gate.current)
        assertEquals(Challenge.Cancel, gate.challenge("auth.example.com", "Private"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))

        gate = BasicAuthGate()
        armed("https://example.com/start")
        assertFalse(gate.redirect("https://example.com:8443/start"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))

        gate = BasicAuthGate()
        armed("https://example.com/start")
        assertFalse(gate.redirect("http://example.com/start"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
    }

    @Test fun redirectWhileLookupIsOutstandingIsAmbiguousAndFailsClosed() {
        armed("https://example.com/start")
        val lookup = lookup()
        assertFalse(gate.redirect("https://example.com/other"))
        assertFalse(gate.isCurrent(lookup))
        assertFalse(gate.supply(lookup))
    }

    @Test fun mainDocumentRequestKeepsGateButAnySubresourceOrIframeDisarms() {
        armed("https://example.com/app")
        assertFalse(gate.intercepted("https://EXAMPLE.com:443/app"))
        assertNotNull(gate.current)
        // An iframe or early subresource request is the conservative "subresource began" signal.
        assertTrue(gate.intercepted("https://example.com/frame"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))

        gate = BasicAuthGate()
        armed("https://example.com/app")
        assertTrue(gate.intercepted("https://cdn.example/lib.js"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
    }

    @Test fun lateChallengesAfterCommitOrErrorAreCancelled() {
        armed("https://example.com/app")
        assertTrue(gate.disarm())
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        assertFalse(gate.disarm())
    }

    @Test fun credentialIsSuppliedOncePerIdentityUrlAndRealmAndRepeatIsRejection() {
        armed("https://example.com/app")
        val first = lookup()
        assertTrue(gate.isCurrent(first))
        assertTrue(gate.supply(first))
        assertFalse(gate.isCurrent(first))
        assertEquals(Challenge.Rejected, gate.challenge("example.com", "Private"))
        // After rejection nothing more is supplied for this navigation.
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Other"))
    }

    @Test fun differentRealmOrRedirectedUrlIsANewLookup() {
        armed("https://example.com/app")
        assertTrue(gate.supply(lookup(realm = "One")))
        val second = lookup(realm = "Two")
        assertEquals("Two", second.realm)
        assertTrue(gate.supply(second))
        assertTrue(gate.redirect("https://example.com/next"))
        assertEquals("https://example.com/next", lookup(realm = "One").url)
    }

    @Test fun concurrentChallengeWhileOutstandingIsCancelled() {
        armed("https://example.com/app")
        val first = lookup()
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        assertTrue(gate.isCurrent(first))
    }

    @Test fun newNavigationInvalidatesOutstandingLookup() {
        armed("https://example.com/app")
        val first = lookup()
        armed("https://example.com/app")
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.supply(first))
        // A new navigation cannot erase the old request risk.
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
    }

    @Test fun challengeBeforeTheNavigationsOwnRequestIsCancelled() {
        // E.g. a fetch the previous page already had in flight when the link was followed.
        gate.arm("https://example.com/next")
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        assertFalse(gate.intercepted("https://example.com/next"))
        assertTrue(gate.challenge("example.com", "Private") is Challenge.Lookup)
    }

    @Test fun abandonedLookupCanBeAskedAgainOnlyForTheSameNavigation() {
        armed("https://example.com/app")
        val first = lookup()
        gate.abandon(first)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.challenge("example.com", "Private") is Challenge.Lookup)
    }
    @Test fun oldIframeChallengeAfterNewMainDocumentNeverLooksUp() {
        armed("https://example.com/frame-page")
        gate.intercepted("https://example.com/protected-frame", false)
        assertNull(gate.arm("https://example.com/image-page"))
        gate.intercepted("https://example.com/image-page")
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Frame"))
    }

    @Test fun commitReloadAndRetryCannotRestoreEligibilityButNewViewCan() {
        armed("https://example.com/public")
        gate.disarm()
        repeat(3) {
            assertNull(gate.arm("https://example.com/private"))
            gate.intercepted("https://example.com/private")
            assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        }
        gate = BasicAuthGate()
        assertNotNull(armed("https://example.com/private"))
        assertTrue(gate.challenge("example.com", "Private") is Challenge.Lookup)
    }

    @Test fun iframeWithExactDocumentUrlCannotPassAsMainDocument() {
        gate.arm("https://example.com/app")
        assertTrue(gate.intercepted("https://example.com/app", false))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Frame"))
    }

    @Test fun workerRiskCancelsChallengeAndOutstandingResult() {
        var safe = true
        gate = BasicAuthGate { safe }
        armed("https://example.com/app")
        val first = lookup()
        safe = false
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.ownsLookup(first))
        assertFalse(gate.supply(first))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
        gate = BasicAuthGate { safe }
        assertNull(gate.arm("https://example.com/app"))
    }

    @Test fun sameUrlPageInitiatedReloadBeforeCommitCannotStayEligible() {
        gate.arm("https://example.com/app")
        assertFalse(gate.started("https://example.com/app"))
        gate.intercepted("https://example.com/app", true)
        assertTrue(gate.started("https://example.com/app"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
    }

    @Test fun pageStartedAfterMainRequestRetiresWithoutWaitingForVisiblePaint() {
        armed("https://example.com/app")
        assertTrue(gate.started("https://example.com/app"))
        assertEquals(Challenge.Cancel, gate.challenge("example.com", "Private"))
    }

}
