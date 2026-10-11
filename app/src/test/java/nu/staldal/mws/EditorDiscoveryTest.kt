package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditorDiscoveryTest {
    private val url = "https://example.com/app"
    private fun record() = ConfiguredApp(id = "test", name = "Custom",
        nameSource = NameSource.USER_EDITED, configuredUrl = url, finalUrl = "$url/final",
        launchUrl = "$url/start", scope = "$url/", createdAt = 1, updatedAt = 1,
        manifestSnapshot = ManifestProcessor.process("""{"short_name":"Site","start_url":"/app/start","scope":"/app/","theme_color":"red"}""",
            "https://example.com/manifest.json", "$url/final", url).snapshot!!.toJson())

    @Test fun failedUnchangedRefreshRetainsSnapshot() {
        val saved = record()
        val stages = EditorDiscovery(saved, url)
        stages.document(DiscoveryDocumentResult(null, false, DiscoveryDocumentFailure.LOAD_ERROR))
        assertEquals(saved, stages.apply(saved))
        assertEquals("Site", stages.suggestedName())
    }

    @Test fun crossOriginRefreshKeepsSavedSnapshot() {
        val saved = record()
        val stages = EditorDiscovery(saved, url)
        stages.document(DiscoveryDocumentResult(null, true))
        assertEquals(saved, stages.apply(saved))
    }

    @Test fun redirectsAreOfferedWithoutAdoptingCrossOriginMetadata() {
        val saved = record()
        val stages = EditorDiscovery(saved, url)
        stages.document(DiscoveryDocumentResult(null, true,
            redirectedUrl = "https://other.example/new"))
        assertEquals("https://other.example/new", stages.redirectedUrl)
        assertEquals(saved, stages.apply(saved))
        stages.document(DiscoveryDocumentResult(null, false, DiscoveryDocumentFailure.LOAD_ERROR))
        assertNull(stages.redirectedUrl)
    }

    @Test fun clearingRedirectRetainsDiscoveredMetadata() {
        val stages = EditorDiscovery(null, url)
        stages.document(DiscoveryDocumentResult("$url/final", false))
        stages.clearRedirect()
        assertNull(stages.redirectedUrl)
        assertEquals("$url/final", stages.finalUrl)
    }

    @Test fun sameOriginRedirectIsOfferedButUnchangedCommitIsNot() {
        val stages = EditorDiscovery(null, url)
        stages.document(DiscoveryDocumentResult("$url/final", false))
        assertEquals("$url/final", stages.redirectedUrl)
        stages.document(DiscoveryDocumentResult(url, false))
        assertNull(stages.redirectedUrl)
    }

    @Test fun changedUrlImmediatelyUsesFallbacksAndOfflineIsRecoverable() {
        val saved = record()
        val changed = "https://example.com/other"
        val stages = EditorDiscovery(saved, changed)
        stages.document(DiscoveryDocumentResult(null, false, DiscoveryDocumentFailure.LOAD_ERROR))
        val base = EditorDraft("Custom", NameSource.USER_EDITED,
            UrlPolicy.parseHttps(changed)!!, false).record(saved, "test", 2)
        val result = stages.apply(base)
        assertNull(result.finalUrl)
        assertNull(result.manifestSnapshot)
        assertEquals(changed, result.launchUrl)
        assertEquals("https://example.com/", result.scope)
    }

    @Test fun manifestDefaultsAndColorsAreSavedAtomically() {
        val stages = EditorDiscovery(null, url)
        stages.document(DiscoveryDocumentResult("$url/final", false))
        stages.manifestCompleted(ManifestProcessor.process("""{"short_name":"Site","start_url":"/app/start","scope":"/app/","theme_color":"red","background_color":"blue"}""",
            "https://example.com/manifest.json", "$url/final", url).snapshot)
        val result = stages.apply(record())
        assertEquals("Site", stages.suggestedName())
        assertEquals("$url/start", result.launchUrl)
        assertEquals("$url/", result.scope)
        assertNotNull(result.themeColor)
        assertNotNull(result.backgroundColor)
    }

    @Test fun nonManifestTitleAndCommitSurviveLaterStageFailure() {
        val stages = EditorDiscovery(record(), url)
        stages.document(DiscoveryDocumentResult("https://example.com/new", false))
        stages.title = "Page title"
        stages.manifestCompleted(null)
        val result = stages.apply(record())
        assertEquals("Page title", stages.suggestedName())
        assertNull(result.manifestSnapshot)
        assertEquals("https://example.com/new", result.launchUrl)
    }

    @Test fun decliningSensitiveDiscoveredLaunchUsesConfiguredUrl() {
        val stages = EditorDiscovery(null, url)
        stages.document(DiscoveryDocumentResult("$url?token=secret", false))
        val result = stages.apply(record())
        val declined = EditorConsent.answer(result, result.launchUrl, false)!!
        assertEquals(url, declined.launchUrl)
        assertNull(declined.finalUrl)
        assertEquals("https://example.com/", declined.scope)
    }
}
