package nu.staldal.mws

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestProcessorTest {
    private val configured = "https://site.example/config"
    private val document = "https://site.example/app/page"
    private val manifest = "https://site.example/meta/manifest.json"

    private fun process(body: String?, final: String? = document, title: String? = "Page") =
        ManifestProcessor.process(body, manifest, final, configured, title)

    @Test fun resolvesMembersAgainstManifestAndUsesScopePrefix() {
        val result = process("""{
            "short_name":"Short", "name":"Long", "start_url":"../app/launch#fragment",
            "scope":"../app/", "display":"fullscreen",
            "theme_color":"#123456", "background_color":"#abcdef",
            "icons":[{"src":"icon.png","sizes":"512x512","type":"image/png","purpose":"any"}],
            "unknown":"never stored"
        }""")
        assertEquals("https://site.example/app/launch#fragment", result.launchUrl)
        assertEquals("https://site.example/app/", result.scope)
        assertEquals("Short", result.suggestedName)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals("fullscreen", snapshot.display)
        assertEquals("https://site.example/meta/icon.png", snapshot.icons.single().url)
        assertEquals(0xff123456.toInt(), snapshot.themeColor)
        assertFalse(snapshot.toJson().contains("unknown"))
        assertEquals("Short", JSONObject(snapshot.toJson()).getString("short_name"))
        assertEquals(snapshot, ManifestProcessor.restoreSnapshot(snapshot.toJson()))
    }

    @Test fun invalidStartAndScopeUseDocumentAndDefaultScope() {
        val result = process("""{"start_url":"https://else.example/out", "scope":"/else"}""")
        assertEquals(document, result.launchUrl)
        assertEquals("https://site.example/app/", result.scope)
        assertEquals("Page", result.suggestedName)
        val prefix = process("""{"start_url":"/application", "scope":"/app"}""")
        assertEquals("https://site.example/application", prefix.launchUrl)
        assertEquals("https://site.example/app", prefix.scope)
        val ignoredSuffix = process("""{"start_url":"/app/page", "scope":"/app/?q=1#fragment"}""")
        assertEquals("https://site.example/app/", ignoredSuffix.scope)
    }

    @Test fun missingDocumentFallsBackToConfiguredUrlAndHost() {
        val result = process("""{"start_url":"https://else.example/out"}""", null, null)
        assertEquals(configured, result.launchUrl)
        assertEquals("https://site.example/", result.scope)
        assertEquals("site.example", result.suggestedName)
    }

    @Test fun malformedManifestIsIgnored() {
        for (body in listOf("{", "[]", "{} trailing", "null", "{name:'bad'}", "{\"name\":1,}")) {
            val result = process(body)
            assertNull(body, result.snapshot)
            assertEquals(document, result.launchUrl)
            assertEquals("https://site.example/", result.scope)
        }
    }

    @Test fun cssHexAlphaUsesTrailingAlpha() {
        val snapshot = requireNotNull(process("""{"theme_color":"#1234","background_color":"#11223344"}""").snapshot)
        assertEquals(0x44112233, snapshot.backgroundColor)
        assertEquals(0x44112233, snapshot.themeColor)
        val rgb = requireNotNull(process("""{"theme_color":"rgb(17, 34, 51)","background_color":"rgba(17, 34, 51, 0.5)"}""").snapshot)
        assertEquals(0xff112233.toInt(), rgb.themeColor)
        assertEquals(0x80112233.toInt(), rgb.backgroundColor)
        assertEquals(0xff008000.toInt(), process("""{"theme_color":"green"}""").snapshot?.themeColor)
        assertEquals(0xffa9a9a9.toInt(), process("""{"theme_color":"darkgray"}""").snapshot?.themeColor)
        assertEquals(0x80112233.toInt(), process("""{"theme_color":"rgb(17 34 51 / 50%)"}""").snapshot?.themeColor)
        assertEquals(0xffff0000.toInt(), process("""{"theme_color":"hsl(0 100% 50%)"}""").snapshot?.themeColor)
    }

    @Test fun sanitizesStringsAndLimitsFirst128Icons() {
        val icons = (0..129).joinToString(",") { "{\"src\":\"/icon$it.png\"}" }
        val result = process("""{"name":"A\u0000B\n\u202e😀", "short_name":"${"x".repeat(258)}", "icons":[$icons]}""")
        val snapshot = requireNotNull(result.snapshot)
        assertEquals("AB😀", snapshot.name)
        assertEquals(256, snapshot.shortName!!.codePointCount(0, snapshot.shortName.length))
        assertEquals(128, snapshot.icons.size)
        assertEquals("https://site.example/icon127.png", snapshot.icons.last().url)
    }

    @Test fun retainedSnapshotRechecksStartAndScopeAgainstNewDocument() {
        val original = process("""{"start_url":"/app/launch", "scope":"/app/"}""")
        val restored = requireNotNull(ManifestProcessor.restoreSnapshot(requireNotNull(original.snapshot).toJson()))
        val refreshed = ManifestProcessor.applySnapshot(restored,
            "https://other.example/page", configured)
        assertEquals("https://other.example/page", refreshed.launchUrl)
        assertEquals("https://other.example/", refreshed.scope)
    }

    @Test fun validUnknownLargeNumberDoesNotDiscardManifest() {
        assertNotNull(process("""{"name":"App", "unknown":1e400}""").snapshot)
    }

    @Test fun crossOriginManifestStillChecksDocumentOrigin() {
        val result = ManifestProcessor.process(
            """{"start_url":"/launch", "scope":"/", "icons":[{"src":"icon.png"}]}""",
            "https://cdn.example/manifest.json", document, configured)
        assertEquals(document, result.launchUrl)
        assertEquals("https://site.example/app/", result.scope)
        assertEquals("https://cdn.example/icon.png", result.snapshot?.icons?.single()?.url)
    }

    @Test fun invalidStoredSnapshotFieldsAreDropped() {
        assertNull(ManifestProcessor.restoreSnapshot("{"))
        assertNull(ManifestProcessor.restoreSnapshot("""{"manifestUrl":"https://SITE.example/"}"""))
        val restored = requireNotNull(ManifestProcessor.restoreSnapshot(
            """{"manifestUrl":"https://site.example/", "icons":[{"src":"http://bad.example/"}]}"""))
        assertTrue(restored.icons.isEmpty())
    }
}
