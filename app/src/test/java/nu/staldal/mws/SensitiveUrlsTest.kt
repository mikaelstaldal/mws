package nu.staldal.mws

import android.content.DialogInterface
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SensitiveUrlsTest {
    private fun keys(url: String) = CredentialParameters.scan(url).keys
    private fun unparseable(url: String) = CredentialParameters.scan(url).unparseable

    @Test fun matchesWordListExactlyAndWithSeparatedAffixes() {
        for (word in CredentialParameters.WORDS) {
            assertTrue(word, CredentialParameters.isCredentialLikeKey(word))
            assertTrue(word, CredentialParameters.isCredentialLikeKey(word.uppercase()))
            for (separator in listOf("_", "-", ".")) {
                assertTrue(word, CredentialParameters.isCredentialLikeKey("x${separator}$word"))
                assertTrue(word, CredentialParameters.isCredentialLikeKey("$word${separator}x"))
            }
        }
        for (key in listOf("api_key", "API-Key", "id_token", "token.x", "X-Auth", "client_secret", "SessionID"))
            assertTrue(key, CredentialParameters.isCredentialLikeKey(key))
        for (key in listOf("tokens", "keyboard", "monkey", "authorize", "passwords", "a_tokenx", "zip_code_x",
                "x_token_y", "_", "", "token_", "_token", "tokenkey", "hotkey", "my token", "token+x"))
            assertFalse(key, CredentialParameters.isCredentialLikeKey(key))
    }

    @Test fun scansHttpsQueryAndFragmentKeys() {
        assertEquals(listOf("token"), keys("https://example.com/?token=abc"))
        assertEquals(listOf("Api_Key", "access_token"),
            keys("https://example.com/p?q=1&Api_Key=v&x#access_token=t&state=s"))
        assertEquals(listOf("access_token"), keys("https://example.com/#/callback?access_token=t"))
        assertEquals(listOf("code"), keys("https://example.com/?code"))
        assertEquals(listOf("token"), keys("https://example.com/?token=a&token=b"))
        assertEquals(listOf("id_token"), keys("https://example.com/?id%5Ftoken=1"))
        assertEquals(emptyList<String>(), keys("https://example.com/token/password?q=1"))
        assertEquals(listOf("key"), keys("https://example.com/?a=1#key"))
        assertEquals(emptyList<String>(), keys("https://example.com/?next=token%3Dx&title=secret"))
        assertEquals(emptyList<String>(), keys("https://example.com/secret?session%2Bid=1"))
        assertFalse(CredentialParameters.scan("https://example.com/?q=1").isSensitive)
        assertFalse(CredentialParameters.scan("https://example.com/").isSensitive)
    }

    @Test fun pathAndPayloadAreNeverScanned() {
        assertFalse(CredentialParameters.scan("mailto:token@example.com").isSensitive)
        assertFalse(CredentialParameters.scan("tel:+1-201-555-0123;token=x;ext=1").isSensitive)
        assertFalse(CredentialParameters.scan("sms:+15105550101,+15105550102").isSensitive)
        assertFalse(CredentialParameters.scan("geo:37.78,-122.39;u=35").isSensitive)
        assertFalse(CredentialParameters.scan("market://details/token").isSensitive)
    }

    @Test fun scansCustomSchemeQueryAndFragment() {
        assertEquals(listOf("token"), keys("mailto:a@example.com?subject=hi&token=x"))
        assertEquals(listOf("token", "auth"), keys("MAILTO:a@example.com?token=x#auth=y"))
        assertEquals(listOf("secret"), keys("mailto:?secret=1"))
        assertEquals(listOf("key"), keys("mailto:a@example.com#key"))
        assertEquals(listOf("session-id"), keys("sms:+15105550101?body=hello&session-id=1"))
        assertEquals(listOf("jwt"), keys("tel:+1555?jwt=x"))
        assertEquals(listOf("sig"), keys("geo:0,0?q=place&sig=1"))
        assertEquals(listOf("referrer.token"), keys("market://details?id=com.example&referrer.token=x"))
        assertFalse(CredentialParameters.scan("mailto:a@example.com?subject=token&body=password").isSensitive)
    }

    @Test fun decodesUtf8WithoutPlusAsSpace() {
        assertEquals(listOf("tökén_key"), keys("mailto:a@example.com?t%C3%B6k%C3%A9n_key=x"))
        assertFalse(CredentialParameters.scan("sms:1?to%25ken=x").isSensitive)
        assertFalse(CredentialParameters.scan("sms:1?x+token=1").isSensitive)
        assertEquals(listOf("x+_token"), keys("sms:1?x+_token=1"))
        assertEquals(listOf("a b.key"), keys("sms:1?a%20b.key=x"))
        assertEquals(listOf("access_token"), keys("geo:0,0?access%5Ftoken=1"))
        assertEquals("a+b", CredentialParameters.percentDecode("a+b"))
        assertNull(CredentialParameters.percentDecode("%C3"))
        assertNull(CredentialParameters.percentDecode("%zz"))
        assertNull(CredentialParameters.percentDecode("%+1"))
        assertNull(CredentialParameters.percentDecode("a\uD800"))
    }

    @Test fun malformedOrAmbiguousComponentsAreUnparseable() {
        val cases = listOf(
            "mailto:a@example.com?%zz=1", "mailto:a@example.com?x=%4", "mailto:a@example.com?x=%",
            "sms:1?%C3%28=1", "sms:1?%FF=1", "geo:0,0?%00token=1",
            "mailto:a@example.com?x=1?token=2", "mailto:a@example.com#a#b",
            "tel:1 ?token=1", "market://details?id=a\u0000", "https://example.com/?%zztoken=1",
            "https://example.com/#%E2%82=1", "intent://x#Intent;end", "javascript:alert(1)", "no-scheme",
            "mailto:a@b?body=%FF", "https://example.com/?q=%C3", "sms:1#x=%E2%82", "geo:0,0?q=%+1",
            "mailto:a@b#route?x?token=secret", "mailto:a@b?x=\u00A0", "tel:1\u0085?a=1", "sms:1?a=\u2028",
            "market://details?id=\u3000x", "mailto:a@b?x=\uD800",
        )
        for (url in cases) {
            val scan = CredentialParameters.scan(url)
            assertTrue(url, scan.unparseable)
            assertTrue(url, scan.isSensitive)
        }
        assertFalse(unparseable("mailto:a@example.com?subject=%E2%82%AC"))
        assertFalse(unparseable("sms:1?body=a%20b&&x"))
    }

    @Test fun consentIsBoundToExactSerializedUrl() {
        val url = "https://example.com/?token=abc"
        val consent = SensitiveUrlConsent.NONE.grant(url)
        assertTrue(consent.covers(url))
        assertFalse(consent.covers("https://example.com/?token=abd"))
        assertFalse(consent.covers("https://example.com/?token=abc#"))
        assertFalse(consent.covers("https://EXAMPLE.com/?token=abc"))
        assertEquals(listOf("https://example.com/?token=abd"),
            SensitiveUrlConsent.pending(listOf(url, "https://example.com/?token=abd", null, url), consent))
        assertEquals(emptyList<String>(),
            SensitiveUrlConsent.pending(listOf(url, "https://example.com/", null), consent))
        assertEquals(listOf(url), SensitiveUrlConsent.pending(listOf(url, url), SensitiveUrlConsent.NONE))
    }

    @Test fun consentRoundTripsWithoutStoringUrlAndFailsClosed() {
        val url = "https://example.com/?token=secret-value"
        val stored = SensitiveUrlConsent.NONE.grant(url).grant("mailto:a@b?key=1").serialize()!!
        assertFalse(stored.contains("secret-value"))
        assertFalse(stored.contains("example.com"))
        val parsed = SensitiveUrlConsent.parse(stored)
        assertTrue(parsed.covers(url))
        assertEquals(SensitiveUrlConsent.NONE.grant(url), parsed.retainOnly(listOf(url, null)))
        assertNull(parsed.retainOnly(listOf("https://example.com/")).serialize())
        assertNull(SensitiveUrlConsent.NONE.serialize())
        for (corrupt in listOf(null, "", "v1:", "v1:xyz", "v2:" + stored.substring(3), url, stored + ",zz"))
            assertFalse(corrupt.toString(), SensitiveUrlConsent.parse(corrupt).covers(url))
    }

    @Test fun warningNamesOnlyKeysNeverValues() {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://example.com/private-path?user=alice&api_key=VALUE1&x#access_token=VALUE2"
        val message = SensitiveUrlWarning.message(context, CredentialParameters.scan(url), persisted = false)
        assertTrue(message, message.contains("“api_key”"))
        assertTrue(message, message.contains("“access_token”"))
        for (hidden in listOf("VALUE1", "VALUE2", "alice", "user", "private-path", "example.com"))
            assertFalse(hidden, message.contains(hidden))
        assertFalse(message.contains(context.getString(R.string.sensitive_url_backup)))
        val persisted = SensitiveUrlWarning.message(context, CredentialParameters.scan(url), persisted = true)
        assertTrue(persisted.contains(context.getString(R.string.sensitive_url_backup)))
    }

    @Test fun warningNamesUnparseableAndBoundsKeys() {
        val context = RuntimeEnvironment.getApplication()
        val unreadable = SensitiveUrlWarning.message(context,
            CredentialParameters.scan("mailto:a@b?%zzSECRET=VALUE"), persisted = false)
        assertTrue(unreadable.contains(context.getString(R.string.sensitive_url_unparseable)))
        assertFalse(unreadable.contains("SECRET"))
        assertFalse(unreadable.contains("VALUE"))
        val many = (1..12).joinToString("&") { "k$it.token=v$it" }
        val bounded = SensitiveUrlWarning.message(context,
            CredentialParameters.scan("https://example.com/?$many"), persisted = false)
        assertTrue(bounded.contains("k8.token"))
        assertFalse(bounded.contains("k9.token"))
        assertTrue(bounded.contains(context.getString(R.string.sensitive_url_more_keys, 4)))
        assertFalse(bounded.contains("v1"))
        assertEquals("“x�_token”", SensitiveUrlWarning.displayKey("x‮_token"))
        val long = "a".repeat(100) + "_token"
        assertEquals("“" + "a".repeat(SensitiveUrlWarning.MAX_KEY_LENGTH) + "…”",
            SensitiveUrlWarning.displayKey(long))
    }

    private fun dialogActivity(): AppCompatActivity {
        return Robolectric.buildActivity(AppCompatActivity::class.java).also {
            it.get().setTheme(R.style.Theme_MobileWebShell)
        }.setup().get()
    }

    @Test fun dialogContinueAndCancelAnswerOnce() {
        val scan = CredentialParameters.scan("https://example.com/?token=VALUE")
        val answers = mutableListOf<Boolean>()
        val dialog = SensitiveUrlWarning.show(dialogActivity(), scan, persisted = true) { answers += it }
        val shown = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(shown.contains("token"))
        assertFalse(shown.contains("VALUE"))
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.sensitive_url_continue),
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(true), answers)

        answers.clear()
        val cancel = SensitiveUrlWarning.show(dialogActivity(), scan, persisted = false) { answers += it }
        cancel.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(false), answers)

        answers.clear()
        val back: AlertDialog = SensitiveUrlWarning.show(dialogActivity(), scan, persisted = false) { answers += it }
        back.cancel()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(false), answers)
    }
}
