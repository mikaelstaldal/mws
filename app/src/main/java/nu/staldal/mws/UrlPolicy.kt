package nu.staldal.mws

import com.ibm.icu.text.IDNA
import io.github.stephanebastian.whatwg.url.Url
import io.github.stephanebastian.whatwg.url.ValidationException

/** The only authority for URL decisions. Callers use the canonical serialization it returns. */
object UrlPolicy {
    const val MAX_URL_BYTES = 8 * 1024

    class HttpsUrl internal constructor(
        val serialized: String,
        val origin: String,
        val host: String,
        val path: String,
    )

    private val idna = IDNA.getUTS46Instance(IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or
        IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII)
    private val ipv4 = Regex("[0-9]+(?:\\.[0-9]+){3}")
    private val decimalPort = Regex("[0-9]+")

    /** Accepts a typed URL or an unambiguous scheme-less DNS authority. */
    fun normalizeUserInput(input: String): HttpsUrl? {
        val trimmed = input.trim { it == ' ' || it in '\t'..'\r' }
        if (trimmed.isEmpty() || trimmed.any(::isForbiddenInputCharacter)) return null
        if (trimmed.startsWith("https://", ignoreCase = true)) return parseHttps(trimmed)
        if (trimmed.startsWith("//")) return null
        val authority = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.startsWith('[') || authority.contains('@') ||
            authority.contains('\\')) return null
        val host = authority.substringBefore(':')
        if (host.isEmpty() || authority.count { it == ':' } > 1) return null
        if (authority.contains(':') && !validPort(authority.substringAfter(':'))) return null
        return parseHttps("https://$trimmed", allowIpLiteral = false)
    }

    /** Checks and canonicalizes an absolute URL from navigation or discovery. */
    fun parseHttps(input: String): HttpsUrl? = parseHttps(input, allowIpLiteral = true)

    fun isAllowedHttpsUrl(input: String): Boolean = parseHttps(input) != null

    fun sameOrigin(first: HttpsUrl, second: HttpsUrl): Boolean = first.origin == second.origin

    /** Web Manifest scope uses a path string prefix, with no segment boundary. */
    fun isWithinScope(candidate: HttpsUrl, scope: HttpsUrl): Boolean =
        sameOrigin(candidate, scope) && candidate.path.startsWith(scope.path)

    private fun parseHttps(input: String, allowIpLiteral: Boolean): HttpsUrl? {
        if (input.length > 65536 || !input.startsWith("https://", ignoreCase = true) ||
            input.any(::isForbiddenInputCharacter)) return null
        val authority = input.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.contains('@') || authority.contains('\\')) return null
        val rawHost: String
        val port: String?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            rawHost = authority.substring(0, close + 1)
            val remainder = authority.substring(close + 1)
            if (remainder.isNotEmpty() && !remainder.startsWith(':')) return null
            port = if (remainder.isEmpty()) null else remainder.substring(1)
        } else {
            if (authority.count { it == ':' } > 1 || authority.contains(']') ||
                authority.contains('[')) return null
            rawHost = authority.substringBefore(':')
            port = if (authority.contains(':')) authority.substringAfter(':') else null
        }
        if (rawHost.isEmpty() || (port != null && !validPort(port))) return null
        val parsed = try { Url.create(input) } catch (_: ValidationException) { return null }
        if (parsed.protocol() != "https:" || parsed.username().isNotEmpty() ||
            parsed.password().isNotEmpty()) return null
        var host = parsed.hostname()
        if (host.isEmpty()) return null
        val ipLiteral = host.startsWith('[') || ipv4.matches(host)
        if (ipLiteral && !allowIpLiteral) return null
        if (!ipLiteral) {
            // WHATWG parsing performs UTS #46 but permits non-STD3 DNS names. Enforce the
            // stricter application rule on its decoded and dot-mapped host.
            if (host.endsWith('.')) {
                host = host.dropLast(1)
                if (host.isEmpty() || host.endsWith('.')) return null
            }
            val ascii = StringBuilder()
            val info = IDNA.Info()
            idna.nameToASCII(host, ascii, info)
            if (info.hasErrors() || ascii.toString() != host || host.split('.').any { it.isEmpty() })
                return null
            if ('%' !in rawHost) {
                // Check the user's Unicode host before the WHATWG parser's permissive
                // UTS #46 mapping can erase a STD3 error.
                val rawWithoutDot = rawHost.trimEnd('.', '。', '．', '｡')
                if (rawWithoutDot.length < rawHost.length - 1) return null
                val rawAscii = StringBuilder()
                val rawInfo = IDNA.Info()
                idna.nameToASCII(rawWithoutDot, rawAscii, rawInfo)
                if (rawInfo.hasErrors() || rawAscii.toString() != host) return null
            }
            if (host != parsed.hostname()) {
                parsed.hostname(host)
                if (parsed.hostname() != host) return null
            }
        }
        val serialized = parsed.href()
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_URL_BYTES) return null
        return HttpsUrl(serialized, parsed.origin(), parsed.hostname(), parsed.pathname())
    }

    private fun validPort(port: String): Boolean = decimalPort.matches(port) &&
        port.toIntOrNull()?.let { it in 0..65535 } == true

    private fun isForbiddenInputCharacter(char: Char): Boolean =
        char <= '\u001f' || char == '\u007f' || char.isWhitespace() || Character.isSpaceChar(char)
}
