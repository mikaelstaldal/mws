package nu.staldal.mws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test fun normalizesTypedDnsUrls() {
        val cases = mapOf(
            "example.com" to "https://example.com/",
            "example.com/path?q=1#x" to "https://example.com/path?q=1#x",
            "example.com/login?next=https://example.com/home" to
                "https://example.com/login?next=https://example.com/home",
            "localhost:8080/path" to "https://localhost:8080/path",
            "https://EXAMPLE.COM.:443" to "https://example.com/",
            "https://EXAMPLE.COM:444" to "https://example.com:444/",
            "  bücher.example  " to "https://xn--bcher-kva.example/",
            "example。com" to "https://example.com/",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, UrlPolicy.normalizeUserInput(input)?.serialized)
        }
    }

    @Test fun rejectsUnsafeTypedUrls() {
        val inputs = listOf(
            "http://localhost:8080", "http://127.0.0.1", "https://user@example.com",
            "https://user:pass@example.com", "https://", "example.com:", "example.com:abc",
            "example.com:65536", "example.com:-1", "example.com:80:90", "[::1]",
            "127.0.0.1", "127.0.0.1/path", "example.com/a b", "exa\tmple.com",
            "127.1", "0x7f.1", "2130706433", "example.com\u0001",
            "https://example.com/a\nb", "https:example.com", "//example.com",
            "https://example.com/a\u0001", "https://example⒈com",
            "https://exa_mple.com", "https://example..com", "https://example.com..",
            "https://example.com@evil.test", "https://[::1]:abc",
        )
        for (input in inputs) assertNull(input, UrlPolicy.normalizeUserInput(input))
    }

    @Test fun canonicalizesExplicitIpLiteralsAndPorts() {
        assertEquals("https://127.0.0.1/", UrlPolicy.parseHttps("https://127.0.0.1")?.serialized)
        assertEquals("https://127.0.0.1/", UrlPolicy.parseHttps("https://127.1")?.serialized)
        assertEquals("https://[2001:db8::1]/", UrlPolicy.parseHttps("https://[2001:0db8:0:0:0:0:0:1]:443")?.serialized)
        assertNull(UrlPolicy.parseHttps("http://[::1]"))
        assertNull(UrlPolicy.parseHttps("https://[::1]:65536"))
    }

    @Test fun limitsCanonicalBytesAndMatchesOriginAndScope() {
        assertTrue(UrlPolicy.isAllowedHttpsUrl("https://example.com/" + "x".repeat(8192 - 20)))
        assertFalse(UrlPolicy.isAllowedHttpsUrl("https://example.com/" + "x".repeat(8192)))
        val scope = requireNotNull(UrlPolicy.parseHttps("https://EXAMPLE.COM:443/app?ignored=1#x"))
        val candidate = requireNotNull(UrlPolicy.parseHttps("https://example.com/application?q=2#y"))
        val other = requireNotNull(UrlPolicy.parseHttps("https://example.com:444/application"))
        assertTrue(UrlPolicy.sameOrigin(scope, candidate))
        assertTrue(UrlPolicy.isWithinScope(candidate, scope))
        assertFalse(UrlPolicy.isWithinScope(other, scope))
        assertFalse(UrlPolicy.isWithinScope(requireNotNull(UrlPolicy.parseHttps("https://example.com/else")), scope))
    }
}
