package nu.staldal.mws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEditorTest {
    @Test fun originChangeDropsOldIconButSameOriginKeepsIt() {
        val old = ConfiguredApp("app", "Old", NameSource.USER_EDITED,
            "https://example.com/", "https://example.com/final", "https://example.com/start",
            "https://example.com/", iconReference = "old-icon", manifestSnapshot = "old",
            themeColor = 42, createdAt = 1, updatedAt = 1)
        fun draft(url: String) = EditorDraft("New", NameSource.USER_EDITED,
            UrlPolicy.parseHttps(url)!!, false).record(old, old.id, 2)
        val same = draft("https://example.com/path?q=1#fragment")
        assertEquals("old-icon", same.iconReference)
        assertEquals(old.profileGeneration, same.profileGeneration)
        val changed = draft("https://other.example/path")
        assertNull(changed.iconReference)
        assertNull(changed.finalUrl)
        assertNull(changed.manifestSnapshot)
        assertNull(changed.themeColor)
        assertEquals(changed.configuredUrl, changed.launchUrl)
    }

    @Test fun urlInputReportsWhyItIsRejected() {
        assertEquals("https://example.com/",
            (UrlPolicy.validateUserInput(" example.com ") as UrlPolicy.UserInput.Valid).url.serialized)
        assertEquals("https://example.com/path",
            (UrlPolicy.validateUserInput("example.com/path") as UrlPolicy.UserInput.Valid).url.serialized)
        for (input in listOf("http://localhost:8080", "HTTP://example.com", "http:example.com",
            "http://127.0.0.1/")) {
            assertEquals(input, UrlPolicy.UserInput.HttpsRequired, UrlPolicy.validateUserInput(input))
        }
        // A scheme-less host named "http" with a port is an ordinary authority.
        assertEquals("https://http:8080/",
            (UrlPolicy.validateUserInput("http:8080") as UrlPolicy.UserInput.Valid).url.serialized)
        assertEquals(UrlPolicy.UserInput.Empty, UrlPolicy.validateUserInput(" \t "))
        for (input in listOf("ftp://example.com", "https://user@example.com", "example.com/a b", "127.0.0.1")) {
            assertEquals(input, UrlPolicy.UserInput.Invalid, UrlPolicy.validateUserInput(input))
        }
    }

    @Test fun urlLimitAppliesToCanonicalSerialization() {
        val prefix = "https://example.com/"
        val fits = prefix + "a".repeat(UrlPolicy.MAX_URL_BYTES - prefix.length)
        assertTrue(UrlPolicy.validateUserInput(fits) is UrlPolicy.UserInput.Valid)
        assertEquals(UrlPolicy.UserInput.TooLong, UrlPolicy.validateUserInput(fits + "a"))
        // Percent-encoding makes the canonical form longer than the typed input.
        val encoded = prefix + "a".repeat(UrlPolicy.MAX_URL_BYTES - prefix.length - 2) + "\""
        assertEquals(UrlPolicy.UserInput.TooLong, UrlPolicy.validateUserInput(encoded))
        assertEquals(UrlPolicy.UserInput.TooLong,
            UrlPolicy.validateUserInput(prefix + "a".repeat(3 * UrlPolicy.MAX_URL_BYTES)))
        assertNull(UrlPolicy.normalizeUserInput(fits + "a"))
    }

    @Test fun namesAreTrimmedAndValidated() {
        assertEquals("Mail", AppNames.normalize("\u00a0 Mail\u3000\n"))
        assertEquals("Café 👩\u200d💻", AppNames.normalize("Café 👩\u200d💻"))
        assertEquals(AppNames.Result.Empty, AppNames.validate(" \u2003\t"))
        // Nothing visible: joiners and combining marks alone do not make a name.
        assertEquals(AppNames.Result.Empty, AppNames.validate("\u200d\u0301"))
        assertEquals("\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc7f",
            AppNames.normalize("\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc7f"))
        for (bad in listOf("a\u0000b", "a\u0007b", "a\nb", "\u202eevil", "a\u2066b", "a\u200fb",
            "a\u061cb", "a\u2028b", "a\ud800b", "\u200b", "a\u200bb", "a\ufeffb", "a\u00adb")) {
            assertEquals(bad, AppNames.Result.InvalidCharacter, AppNames.validate(bad))
        }
        val max = "😀".repeat(AppNames.MAX_CODE_POINTS)
        assertEquals(max, AppNames.normalize("  $max  "))
        assertEquals(AppNames.Result.TooLong, AppNames.validate(max + "x"))
    }

    private fun url(value: String) = requireNotNull(UrlPolicy.normalizeUserInput(value))

    @Test fun newRecordUsesConfiguredUrlAndOriginScope() {
        val record = EditorDraft("Example", NameSource.DISCOVERED, url("example.com/app?x=1"), false)
            .record(null, "id-1", 42)
        assertEquals("id-1", record.id)
        assertEquals("https://example.com/app?x=1", record.configuredUrl)
        assertEquals(record.configuredUrl, record.launchUrl)
        assertNull(record.finalUrl)
        assertEquals("https://example.com/", record.scope)
        assertFalse(record.allowThirdPartyCookies)
        assertEquals(ProfileState.ACTIVE, record.profileState)
        assertEquals(42, record.createdAt)
    }

    private val saved = ConfiguredApp(id = "saved", name = "Old", nameSource = NameSource.DISCOVERED,
        configuredUrl = "https://example.com/app", finalUrl = "https://example.com/app/home",
        launchUrl = "https://example.com/app/start", scope = "https://example.com/app/",
        manifestSnapshot = "{}", iconReference = "icon", themeColor = 1, backgroundColor = 2,
        sortPosition = 3, createdAt = 1, updatedAt = 1)

    @Test fun unchangedUrlKeepsDerivedMetadata() {
        val record = EditorDraft("New", NameSource.USER_EDITED, url("https://example.com/app"), true)
            .record(saved, "ignored", 9)
        assertEquals(saved.copy(name = "New", nameSource = NameSource.USER_EDITED,
            allowThirdPartyCookies = true, updatedAt = 9), record)
    }

    @Test fun sameOriginChangeInvalidatesDerivedMetadataButKeepsIcon() {
        val record = EditorDraft("Old", NameSource.DISCOVERED, url("https://example.com/other"), false)
            .record(saved, "ignored", 9)
        assertEquals("https://example.com/other", record.configuredUrl)
        assertEquals("https://example.com/other", record.launchUrl)
        assertEquals("https://example.com/", record.scope)
        assertNull(record.finalUrl)
        assertNull(record.manifestSnapshot)
        assertNull(record.themeColor)
        assertNull(record.backgroundColor)
        assertEquals("icon", record.iconReference)
        assertEquals("saved", record.id)
        assertEquals(1, record.createdAt)
    }

    @Test fun detectsOriginChanges() {
        assertFalse(EditorDraft.changesOrigin(null, url("other.example")))
        assertFalse(EditorDraft.changesOrigin(saved, url("EXAMPLE.com:443/x?y#z")))
        assertTrue(EditorDraft.changesOrigin(saved, url("example.com:8443/app")))
        assertTrue(EditorDraft.changesOrigin(saved, url("www.example.com/app")))
    }

    @Test fun sensitiveConfiguredUrlNeedsConsentAndDeclineBlocksSave() {
        val record = EditorDraft("Example", NameSource.DISCOVERED, url("example.com/?token=secret"), false)
            .record(null, "id", 1)
        assertEquals(listOf("https://example.com/?token=secret"), EditorConsent.pending(record))
        assertNull(EditorConsent.answer(record, record.configuredUrl, accepted = false))
        val consented = EditorConsent.prune(requireNotNull(
            EditorConsent.answer(record, record.configuredUrl, accepted = true)))
        assertTrue(EditorConsent.pending(consented).isEmpty())
        assertFalse(consented.sensitiveUrlConsent!!.contains("secret"))
        // A changed sensitive URL needs consent again, and the old digest is pruned.
        val changed = EditorDraft("Example", NameSource.DISCOVERED, url("example.com/?token=other"), false)
            .record(consented, "id", 2)
        assertEquals(listOf("https://example.com/?token=other"), EditorConsent.pending(changed))
        val pruned = EditorConsent.prune(changed.copy(configuredUrl = "https://example.com/",
            launchUrl = "https://example.com/"))
        assertNull(pruned.sensitiveUrlConsent)
    }

    @Test fun declinedDiscoveredUrlsFallBackToConfiguredUrl() {
        val record = saved.copy(finalUrl = "https://example.com/app?code=1",
            launchUrl = "https://example.com/app?session=2")
        assertEquals(listOf("https://example.com/app?code=1", "https://example.com/app?session=2"),
            EditorConsent.pending(record))
        val withoutFinal = requireNotNull(EditorConsent.answer(record, record.finalUrl!!, accepted = false))
        assertNull(withoutFinal.finalUrl)
        assertEquals(record.scope, withoutFinal.scope)
        assertEquals(record.manifestSnapshot, withoutFinal.manifestSnapshot)
        val withoutLaunch = requireNotNull(EditorConsent.answer(withoutFinal, withoutFinal.launchUrl,
            accepted = false))
        assertEquals(record.configuredUrl, withoutLaunch.launchUrl)
        // The narrower manifest scope does not contain the fallback, so it is replaced.
        assertEquals("https://example.com/", withoutLaunch.scope)
        assertNull(withoutLaunch.manifestSnapshot)
        assertNull(withoutLaunch.themeColor)
        assertEquals("icon", withoutLaunch.iconReference)
        assertTrue(UrlPolicy.isWithinScope(UrlPolicy.parseHttps(withoutLaunch.launchUrl)!!,
            UrlPolicy.parseHttps(withoutLaunch.scope)!!))
        assertTrue(EditorConsent.pending(withoutLaunch).isEmpty())
    }
}
