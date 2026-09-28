package nu.staldal.mws

import android.content.Context
import androidx.appcompat.app.AlertDialog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/**
 * Credential-like parameter detection (R§4, A§3). This is a warning heuristic only. [keys]
 * holds matching decoded keys in first-seen order; values are never retained.
 */
class CredentialParameterScan internal constructor(val keys: List<String>, val unparseable: Boolean) {
    val isSensitive: Boolean get() = keys.isNotEmpty() || unparseable
}

object CredentialParameters {
    val WORDS = listOf("access_token", "autostarttoken", "auth", "authorization", "code", "credential", "jwt", "key",
        "password", "secret", "session", "sessionid", "sig", "signature", "ticket", "token")
    val CUSTOM_SCHEMES = setOf("mailto", "tel", "sms", "geo", "market", "bankid")
    private const val SEPARATORS = "_-."

    fun isCredentialLikeKey(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        return WORDS.any { word ->
            lower == word ||
                (lower.length > word.length + 1 && lower.startsWith(word) && lower[word.length] in SEPARATORS) ||
                (lower.length > word.length + 1 && lower.endsWith(word) &&
                    lower[lower.length - word.length - 1] in SEPARATORS)
        }
    }

    /**
     * Scans an HTTPS URL (normally its canonical serialization) or an allowlisted custom-scheme
     * URL. Any other scheme is reported as unparseable, never as safe.
     */
    fun scan(url: String): CredentialParameterScan {
        val colon = url.indexOf(':')
        val scheme = if (colon > 0) url.substring(0, colon).lowercase(Locale.ROOT) else null
        val collector = Collector()
        when {
            scheme == "https" -> {
                val hash = url.indexOf('#')
                val beforeFragment = if (hash < 0) url else url.substring(0, hash)
                val question = beforeFragment.indexOf('?')
                if (question >= 0) collector.component(beforeFragment.substring(question + 1))
                if (hash >= 0) collector.fragment(url.substring(hash + 1))
            }
            scheme in CUSTOM_SCHEMES -> customScheme(url.substring(colon + 1), collector)
            else -> collector.unparseable = true
        }
        return CredentialParameterScan(collector.keys.toList(), collector.unparseable)
    }

    /**
     * mailto (RFC 6068), sms (RFC 5724), geo (RFC 5870), tel (RFC 3966) and market URLs all
     * carry at most one query after their payload and an optional fragment. The payload
     * (recipient, number, coordinates, market path, and tel `;` parameters) is not scanned.
     * A second `?` or `#` makes the split ambiguous.
     */
    private fun customScheme(rest: String, collector: Collector) {
        if (rest.any(::isAmbiguousCharacter)) { collector.unparseable = true; return }
        val hash = rest.indexOf('#')
        val beforeFragment = if (hash < 0) rest else rest.substring(0, hash)
        val fragment = if (hash < 0) null else rest.substring(hash + 1)
        if (fragment != null && ('#' in fragment || fragment.count { it == '?' } > 1)) {
            collector.unparseable = true; return
        }
        val question = beforeFragment.indexOf('?')
        if (question >= 0) {
            val query = beforeFragment.substring(question + 1)
            if ('?' in query) collector.unparseable = true else collector.component(query)
        }
        if (fragment != null) collector.fragment(fragment)
    }

    private fun isAmbiguousCharacter(char: Char): Boolean =
        Character.isISOControl(char) || char.isWhitespace() || Character.isSpaceChar(char)

    private class Collector {
        val keys = LinkedHashSet<String>()
        var unparseable = false

        /**
         * A fragment used by client-side routing may hold a query after a `?`, e.g. `#/cb?token=`.
         * Both sides are scanned so no key spans the `?` and route text is never shown as a key.
         */
        fun fragment(value: String) {
            val question = value.indexOf('?')
            if (question < 0) { component(value); return }
            component(value.substring(0, question))
            component(value.substring(question + 1))
        }

        fun component(value: String) {
            for (pair in value.split('&')) {
                if (pair.isEmpty()) continue
                // The value is decoded only to validate it; it is never retained.
                val key = percentDecode(pair.substringBefore('='))
                if (key == null || percentDecode(pair.substringAfter('=', "")) == null ||
                    key.any(Character::isISOControl)) { unparseable = true; continue }
                if (isCredentialLikeKey(key)) keys += key
            }
        }
    }

    private fun isHex(char: Char) = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    /** UTF-8 percent-decoding with `+` left as-is; a malformed escape or invalid UTF-8 returns null. */
    internal fun percentDecode(value: String): String? {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length || !isHex(value[index + 1]) || !isHex(value[index + 2])) return null
                bytes.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                val next = value.indexOf('%', index).let { if (it < 0) value.length else it }
                val literal = value.substring(index, next)
                val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                val encoded = try { encoder.encode(CharBuffer.wrap(literal)) } catch (_: CharacterCodingException) { return null }
                bytes.write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining())
                index = next
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) { null }
    }
}

/**
 * Consent for sensitive URLs, bound to their exact serialized form (R§4.1). Only SHA-256
 * digests are stored so the consent record does not duplicate URL secrets.
 */
class SensitiveUrlConsent private constructor(private val digests: Set<String>) {
    fun covers(url: String): Boolean = digest(url) in digests

    fun grant(url: String): SensitiveUrlConsent = SensitiveUrlConsent(digests + digest(url))

    /** Drops consent for URLs no longer stored, so a later return to one asks again. */
    fun retainOnly(urls: Collection<String?>): SensitiveUrlConsent =
        SensitiveUrlConsent(digests intersect urls.filterNotNull().map(::digest).toSet())

    /** Value for `ConfiguredApp.sensitiveUrlConsent`; null when there is no consent. */
    fun serialize(): String? = if (digests.isEmpty()) null else PREFIX + digests.sorted().joinToString(",")

    override fun equals(other: Any?) = other is SensitiveUrlConsent && other.digests == digests
    override fun hashCode() = digests.hashCode()

    companion object {
        private const val PREFIX = "v1:"
        private val hexDigest = Regex("[0-9a-f]{64}")
        val NONE = SensitiveUrlConsent(emptySet())

        /** Unknown or corrupt records grant nothing. */
        fun parse(stored: String?): SensitiveUrlConsent {
            if (stored == null || !stored.startsWith(PREFIX)) return NONE
            val values = stored.substring(PREFIX.length).split(',')
            return if (values.all(hexDigest::matches)) SensitiveUrlConsent(values.toSet()) else NONE
        }

        private fun digest(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /**
         * The URLs, in order and without duplicates, that must be confirmed before persisting
         * with [consent]. Callers pass the exact serialized configured, final and launch URLs.
         */
        fun pending(urls: List<String?>, consent: SensitiveUrlConsent): List<String> =
            urls.filterNotNull().distinct().filter { CredentialParameters.scan(it).isSensitive && !consent.covers(it) }
    }
}

/** The shared redacted warning for persisting or delegating sensitive URLs (R§4.1, R§9.3). */
object SensitiveUrlWarning {
    const val MAX_KEYS = 8
    const val MAX_KEY_LENGTH = 64

    /** Names only matching keys, never values or other URL parts. */
    fun message(context: Context, scan: CredentialParameterScan, persisted: Boolean): String {
        val names = scan.keys.take(MAX_KEYS).map(::displayKey).toMutableList()
        if (scan.keys.size > MAX_KEYS)
            names += context.getString(R.string.sensitive_url_more_keys, scan.keys.size - MAX_KEYS)
        if (scan.unparseable) names += context.getString(R.string.sensitive_url_unparseable)
        val body = context.getString(R.string.sensitive_url_keys, names.joinToString(", "))
        return if (persisted) body + "\n\n" + context.getString(R.string.sensitive_url_backup) else body
    }

    /** Bounds length and replaces control and invisible formatting characters such as bidi overrides. */
    internal fun displayKey(key: String): String {
        val cleaned = buildString {
            var index = 0
            while (index < key.length) {
                val codePoint = key.codePointAt(index)
                val type = Character.getType(codePoint)
                if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt())
                    append('�')
                else appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
        if (cleaned.codePointCount(0, cleaned.length) <= MAX_KEY_LENGTH) return "“$cleaned”"
        return "“" + cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_KEY_LENGTH)) + "…”"
    }

    /**
     * Shows the warning with Continue/Cancel. [onResult] is called exactly once; back or
     * outside-touch dismissal counts as Cancel. [persisted] adds the backup disclosure.
     */
    fun show(context: Context, scan: CredentialParameterScan, persisted: Boolean,
             onResult: (Boolean) -> Unit): AlertDialog {
        var answered = false
        fun answer(value: Boolean) { if (!answered) { answered = true; onResult(value) } }
        return AlertDialog.Builder(context).setTitle(R.string.sensitive_url_title)
            .setMessage(message(context, scan, persisted))
            .setPositiveButton(R.string.sensitive_url_continue) { _, _ -> answer(true) }
            .setNegativeButton(R.string.sensitive_url_cancel) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
    }
}
