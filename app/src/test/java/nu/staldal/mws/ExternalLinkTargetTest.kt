package nu.staldal.mws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinkTargetTest {
    private fun app(id: String, scope: String, state: ProfileState = ProfileState.ACTIVE) =
        ConfiguredApp(id, id, NameSource.USER_EDITED, scope, null, scope, scope,
            profileState = state, createdAt = 1, updatedAt = 1)

    @Test fun choosesLongestMatchingActiveScopeAndNeverTheSource() {
        val apps = listOf(app("source", "https://example.com/"),
            app("wide", "https://example.com/"),
            app("specific", "https://example.com/docs/"),
            app("clearing", "https://example.com/docs/page", ProfileState.CLEARING))
        assertEquals("specific", ExternalLinkTarget.find(
            UrlPolicy.parseNavigation("https://example.com/docs/page?id=1")!!, "source", apps)?.id)
        assertNull(ExternalLinkTarget.find(
            UrlPolicy.parseNavigation("https://other.example/")!!, "source", apps))
        assertNull(ExternalLinkTarget.find(
            UrlPolicy.parseNavigation("https://example.com/")!!, "wide", listOf(apps[1])))
    }

    @Test fun equalScopesUseConfiguredOrderAndPathPrefixesFollowManifestRules() {
        val apps = listOf(app("first", "https://example.com/app"),
            app("second", "https://example.com/app"))
        assertEquals("first", ExternalLinkTarget.find(
            UrlPolicy.parseNavigation("https://example.com/application")!!, "source", apps)?.id)
    }
}
