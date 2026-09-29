package nu.staldal.mws

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoveryDomTest {
    private fun link(url: String = "https://example.com/manifest", matches: Boolean = true) =
        JSONObject().put("url", url).put("media", "").put("mediaMatches", matches)
            .put("type", "").put("crossorigin", "").put("referrerPolicy", "no-referrer")
            .put("sizes", "").put("usable", true)
    private fun snapshot(links: List<JSONObject>) = JSONObject().put("title", "Title")
        .put("manifests", org.json.JSONArray(links)).put("icons", org.json.JSONArray()).toString()

    @Test fun firstMatchingCandidateDoesNotFallThroughOnTruncation() {
        val first = link(matches = false)
        val truncated = link("x".repeat(8193))
        val result = requireNotNull(DiscoveryDom.parse(snapshot(listOf(first, truncated, link()))))
        assertSame(result.manifests[1], result.selectedManifest)
        assertFalse(result.selectedManifest!!.usable)
    }
    @Test fun capsAndUntrustedTypesAreRejected() {
        assertNotNull(DiscoveryDom.parse(snapshot(List(16) { link() })))
        assertNull(DiscoveryDom.parse(snapshot(List(17) { link() })))
        assertNull(DiscoveryDom.parse(snapshot(listOf(link().put("mediaMatches", "true")))))
        assertNull(DiscoveryDom.parse("[]"))
        assertNull(DiscoveryDom.parse(snapshot(emptyList()) + " garbage"))
        assertNull(DiscoveryDom.parse(" ".repeat(1048577)))
        assertNull(DiscoveryDom.parseEvaluation("{}"))
        val json = snapshot(listOf(link()))
        assertEquals(DiscoveryDom.parse(json), DiscoveryDom.parseEvaluation(JSONObject.quote(json)))
    }
    @Test fun onlyHttpsRefreshTargetIsKept() {
        val base = JSONObject(snapshot(emptyList()))
        assertNull(DiscoveryDom.parse(base.toString())!!.refresh)
        assertEquals("https://example.com/userapp/",
            DiscoveryDom.parse(base.put("refresh", "https://example.com/userapp/").toString())!!.refresh)
        assertNull(DiscoveryDom.parse(base.put("refresh", "http://example.com/").toString())!!.refresh)
        assertNull(DiscoveryDom.parse(base.put("refresh", "javascript:alert(1)").toString())!!.refresh)
    }
    @Test fun reloadAndDuplicateCommitRejectOldDocument() {
        val gate = DiscoveryDomIdentity()
        gate.started()
        val first = requireNotNull(gate.request())
        assertTrue(gate.accepts(first))
        assertNull(gate.request())
        gate.started()
        assertFalse(gate.accepts(first))
        val second = requireNotNull(gate.request())
        assertTrue(gate.accepts(second))
        assertFalse(gate.accepts(first))
    }
}
