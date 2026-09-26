package nu.staldal.mws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationPolicyTest {
    private fun main(url: String, redirect: Boolean = false, gesture: Boolean = true) =
        NavigationPolicy.decide(url, mainFrame = true, redirect = redirect, gesture = gesture)

    private fun sub(url: String) = NavigationPolicy.decide(url, mainFrame = false, redirect = false, gesture = true)

    @Test fun httpsStaysInTheShellInAndOutOfScope() {
        assertEquals(NavigationDecision.Stay, main("https://example.com/next"))
        assertEquals(NavigationDecision.Stay, main("https://login.other.example/authorize?state=x", redirect = true))
        assertEquals(NavigationDecision.Stay, main("HTTPS://Example.COM/", gesture = false))
        assertEquals(NavigationDecision.Stay, sub("https://frame.example/"))
    }

    @Test fun longHttpsNavigationIsNotLimitedLikePersistedUrls() {
        val long = "https://example.com/cb?state=" + "a".repeat(20_000)
        assertNull(UrlPolicy.parseHttps(long))
        assertEquals(NavigationDecision.Stay, main(long))
    }

    @Test fun userinfoAndInvalidHostsAreRejected() {
        assertEquals(NavigationDecision.Block(R.string.shell_url_rejected), main("https://user:pw@example.com/"))
        assertEquals(NavigationDecision.Block(R.string.shell_url_rejected), main("https://bad_host.example/"))
        assertEquals(NavigationDecision.Block(null), sub("https://user@example.com/"))
    }

    @Test fun cleartextIsRejectedForLinksRedirectsAndFrames() {
        val required = NavigationDecision.Block(R.string.shell_https_required)
        assertEquals(required, main("http://example.com/"))
        assertEquals(required, main("HTTP://example.com/", redirect = true))
        assertEquals(required, main("http://localhost:8080/", gesture = false))
        assertEquals(NavigationDecision.Block(null), sub("http://example.com/"))
    }

    @Test fun dangerousSchemesAreBlockedAtTopLevel() {
        for (url in listOf("javascript:alert(1)", "data:text/html,<p>x", "file:///sdcard/x", "content://a/b",
            "intent://scan/#Intent;scheme=zxing;package=x;end", "about:blank", "blob:https://example.com/id",
            "ftp://example.com/", "custom-app:open", "not a url", ":nothing")) {
            assertEquals(url, NavigationDecision.Block(R.string.shell_scheme_blocked), main(url))
        }
    }

    @Test fun subframeContentSchemesStayButOthersAreBlockedSilently() {
        assertEquals(NavigationDecision.Stay, sub("about:blank"))
        assertEquals(NavigationDecision.Stay, sub("data:text/html,x"))
        assertEquals(NavigationDecision.Stay, sub("blob:https://example.com/id"))
        assertEquals(NavigationDecision.Block(null), sub("intent://x#Intent;end"))
        assertEquals(NavigationDecision.Block(null), sub("file:///x"))
        assertEquals(NavigationDecision.Block(null), sub("mailto:a@example.com"))
    }

    @Test fun allowlistedSchemesNeedMainFrameGestureWithoutRedirect() {
        for ((url, scheme) in listOf("mailto:a@example.com?subject=hi" to ExternalScheme.MAILTO,
            "tel:+4612345" to ExternalScheme.TEL, "sms:+4612345?body=x" to ExternalScheme.SMS,
            "geo:59.3,18.0" to ExternalScheme.GEO, "market://details?id=x" to ExternalScheme.MARKET,
            "MAILTO:a@example.com" to ExternalScheme.MAILTO)) {
            val decision = main(url) as NavigationDecision.Confirm
            assertEquals(scheme, decision.scheme)
            assertEquals(url, decision.url)
            val blocked = NavigationDecision.Block(R.string.shell_external_blocked)
            assertEquals(blocked, main(url, redirect = true))
            assertEquals(blocked, main(url, gesture = false))
        }
    }

    @Test fun externalSchemeLookupIsExact() {
        assertNotNull(ExternalScheme.of("tel"))
        assertNull(ExternalScheme.of("intent"))
        assertNull(ExternalScheme.of("telx"))
    }
}
