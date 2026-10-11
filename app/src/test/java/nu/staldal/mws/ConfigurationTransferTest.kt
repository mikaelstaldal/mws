package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ConfigurationTransferTest {
    private val app = ConfiguredApp("old", "My app", NameSource.USER_EDITED,
        "https://example.com/", "https://example.com/home", "https://example.com/home",
        "https://example.com/", allowThirdPartyCookies = true,
        profileState = ProfileState.CLEARING, profileGeneration = 9,
        retiringProfileGeneration = 8, manifestSnapshot = "{\"manifestUrl\":\"https://example.com/manifest.json\"}", iconReference = "local-icon",
        themeColor = -123, backgroundColor = 0, sensitiveUrlConsent = "private",
        createdAt = 1, updatedAt = 2)

    @Test fun roundTripPreservesConfigurationAndResetsLocalState() {
        val payload = ConfigurationTransfer.encode(listOf(app, app.copy(name = "Second")))
        val imported = ConfigurationTransfer.decode(payload, 100)
        assertEquals(listOf("My app", "Second"), imported.map { it.name })
        assertEquals(2, imported.map { it.id }.toSet().size)
        assertFalse(payload.contains("local-icon"))
        assertFalse(payload.contains("private"))
        assertEquals(app.copy(id = imported[0].id, profileState = ProfileState.ACTIVE,
            profileGeneration = 0, retiringProfileGeneration = null, iconReference = null,
            sensitiveUrlConsent = null, createdAt = 100, updatedAt = 100), imported[0])
    }

    @Test fun oversizedAndInvalidMetadataAndCrossOriginUrlsAreRejected() {
        for (invalid in listOf(app.copy(manifestSnapshot = "x".repeat(1024 * 1024 + 1)),
            app.copy(manifestSnapshot = "{}"), app.copy(finalUrl = "https://other.example/"),
            app.copy(configuredUrl = "https://other.example/"))) {
            assertThrows(Exception::class.java) {
                ConfigurationTransfer.decode(ConfigurationTransfer.encode(listOf(invalid)))
            }
        }
        assertThrows(Exception::class.java) {
            ConfigurationTransfer.decode(ConfigurationTransfer.encode(List(1001) { app }))
        }
    }

    @Test fun invalidVersionUrlsTypesAndScopeAreRejected() {
        for ((key, value) in listOf("launchUrl" to "http://example.com/",
            "scope" to "https://other.example/", "allowThirdPartyCookies" to "true",
            "name" to " ", "themeColor" to 2147483648L)) {
            val json = JSONObject(ConfigurationTransfer.encode(listOf(app)))
            json.getJSONArray("apps").getJSONObject(0).put(key, value)
            assertThrows(Exception::class.java) { ConfigurationTransfer.decode(json.toString()) }
        }
        val json = JSONObject(ConfigurationTransfer.encode(listOf(app))).put("version", 2)
        assertThrows(Exception::class.java) { ConfigurationTransfer.decode(json.toString()) }
        assertThrows(Exception::class.java) { ConfigurationTransfer.decode("broken") }
        assertEquals(emptyList<ConfiguredApp>(), ConfigurationTransfer.decode(ConfigurationTransfer.encode(emptyList())))
    }
}
