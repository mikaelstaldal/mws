package nu.staldal.mws

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportSiteRefreshTest {
    private val url = "https://example.com/"
    private val app = ConfiguredApp("imported", "Custom name", NameSource.USER_EDITED,
        url, url, url, url, createdAt = 1, updatedAt = 1)

    private fun link(path: String) = DiscoveryDomLink(url + path, "", true,
        "", "", "", "512x512", true)

    private fun png(): ByteArray = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, output)
    }.toByteArray()

    private fun client(resources: Map<String, ByteArray>, requests: MutableList<String>) =
        NativeDiscoveryClient(url, "https://example.com", open = { requested ->
            requests += requested
            object : HttpURLConnection(URL(requested)) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = if (requested in resources) 200 else 404
                override fun getInputStream() = ByteArrayInputStream(resources[requested] ?: byteArrayOf())
            }
        })

    @Test fun importedAppRediscoversManifestIconInsteadOfUsingDeviceLocalReference() = runBlocking {
        val imported = ConfigurationTransfer.decode(ConfigurationTransfer.encode(listOf(app))).single()
        assertNull(imported.iconReference)
        val stages = EditorDiscovery(imported, url)
        stages.document(DiscoveryDocumentResult(url, false))
        val requests = mutableListOf<String>()
        val manifest = """{"short_name":"Site","icons":[{"src":"/icon.png","sizes":"512x512","type":"image/png"}]}"""
        refreshImportMetadata(stages, DiscoveryDomSnapshot("Title", listOf(link("manifest.json")),
            listOf(link("page.png"))), client(mapOf(url + "manifest.json" to manifest.toByteArray(),
            url + "icon.png" to png()), requests))
        assertEquals(url + "icon.png", stages.icon!!.candidate.url)
        assertEquals(IconCandidate.Source.MANIFEST, stages.icon!!.candidate.source)
        assertEquals(listOf(url + "manifest.json", url + "icon.png"), requests)
        assertEquals("Custom name", stages.apply(imported).name)
    }

    @Test fun failedManifestFallsBackToPageIconThenFavicon() = runBlocking {
        val stages = EditorDiscovery(app, url)
        stages.document(DiscoveryDocumentResult(url, false))
        val requests = mutableListOf<String>()
        refreshImportMetadata(stages, DiscoveryDomSnapshot("Title", listOf(link("manifest.json")),
            listOf(link("page.png"))), client(mapOf(url + "favicon.ico" to png()), requests))
        assertEquals(IconCandidate.Source.FAVICON, stages.icon!!.candidate.source)
        assertEquals(listOf(url + "manifest.json", url + "page.png", url + "favicon.ico"), requests)
    }

    @Test fun unavailableDocumentAndIconRetainImportedDetails() = runBlocking {
        val stages = EditorDiscovery(app, url)
        stages.document(DiscoveryDocumentResult(null, false, DiscoveryDocumentFailure.LOAD_ERROR))
        val requests = mutableListOf<String>()
        refreshImportMetadata(stages, null, client(emptyMap(), requests))
        assertNull(stages.icon)
        assertEquals(app, stages.apply(app))
        assertEquals(listOf(url + "favicon.ico"), requests)
    }

    @Test fun offlineRefreshRetainsDiscoveredNameEvenWithDifferentManifestName() {
        val imported = app.copy(name = "Saved name", nameSource = NameSource.DISCOVERED,
            manifestSnapshot = ManifestProcessor.process("""{"short_name":"Other name"}""",
                url + "manifest.json", url, url).snapshot!!.toJson())
        assertEquals(imported, applyImportDiscovery(imported, EditorDiscovery(imported, url)))
    }

    @Test fun newlyDiscoveredSensitiveUrlDoesNotReceiveImportConsent() {
        val sensitive = url + "?token=secret"
        val stages = EditorDiscovery(app, url)
        stages.document(DiscoveryDocumentResult(sensitive, false))
        val refreshed = applyImportDiscovery(app, stages)
        assertNull(refreshed.finalUrl)
        assertEquals(url, refreshed.launchUrl)
        assertTrue(EditorConsent.pending(refreshed).isEmpty())
        assertFalse(SensitiveUrlConsent.parse(refreshed.sensitiveUrlConsent).covers(sensitive))
    }
}
