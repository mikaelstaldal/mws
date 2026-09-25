package nu.staldal.mws

import android.graphics.Color
import android.util.JsonReader
import android.util.JsonToken
import io.github.stephanebastian.whatwg.url.Url
import io.github.stephanebastian.whatwg.url.ValidationException
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A sanitized, supported subset of a Web App Manifest. Safe to persist as [toJson]. */
data class ManifestSnapshot(
    val manifestUrl: String,
    val name: String?,
    val shortName: String?,
    val startUrl: String?,
    val scope: String?,
    val display: String?,
    val themeColor: Int?,
    val backgroundColor: Int?,
    val icons: List<IconCandidate>,
) {
    fun toJson(): String = JSONObject().apply {
        put("manifestUrl", manifestUrl)
        name?.let { put("name", it) }
        shortName?.let { put("short_name", it) }
        startUrl?.let { put("start_url", it) }
        scope?.let { put("scope", it) }
        display?.let { put("display", it) }
        themeColor?.let { put("theme_color", it) }
        backgroundColor?.let { put("background_color", it) }
        put("icons", JSONArray().apply {
            icons.forEach { icon ->
                put(JSONObject().apply {
                    put("src", icon.url)
                    icon.sizes?.let { put("sizes", it) }
                    icon.type?.let { put("type", it) }
                    icon.purpose?.let { put("purpose", it) }
                })
            }
        })
    }.toString()
}

data class ManifestResult(
    val snapshot: ManifestSnapshot?,
    val launchUrl: String,
    val scope: String,
    val suggestedName: String,
)

/** Fetching and CORS checks belong to discovery; this class only processes a fetched body. */
object ManifestProcessor {
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_DISPLAY_CODE_POINTS = 256

    /** Read only the supported, valid fields from persisted metadata. */
    fun restoreSnapshot(serialized: String): ManifestSnapshot? {
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) return null
        val json = parseObject(serialized) ?: return null
        val manifestUrl = canonicalUrl(string(json, "manifestUrl")) ?: return null
        val startUrl = string(json, "start_url")?.let(::canonicalUrl)
        val scope = string(json, "scope")?.let(::canonicalUrl)
        val icons = mutableListOf<IconCandidate>()
        json.optJSONArray("icons")?.let { array ->
            for (index in 0 until minOf(array.length(), 128)) {
                val icon = array.optJSONObject(index) ?: continue
                val url = canonicalUrl(string(icon, "src")) ?: continue
                icons += IconCandidate(url, IconCandidate.Source.MANIFEST,
                    string(icon, "sizes")?.let(::sanitizeDisplay),
                    string(icon, "type")?.let(::sanitizeDisplay),
                    string(icon, "purpose")?.let(::sanitizeDisplay), index)
            }
        }
        return ManifestSnapshot(manifestUrl,
            string(json, "name")?.let(::sanitizeDisplay),
            string(json, "short_name")?.let(::sanitizeDisplay), startUrl, scope,
            string(json, "display")?.takeIf { it in setOf("fullscreen", "standalone", "minimal-ui", "browser") },
            (json.opt("theme_color") as? Number)?.toInt(),
            (json.opt("background_color") as? Number)?.toInt(), icons)
    }

    private fun canonicalUrl(value: String?): String? = value?.let {
        UrlPolicy.parseHttps(it)?.serialized?.takeIf { canonical -> canonical == it }
    }

    fun process(
        manifestText: String?,
        manifestUrl: String?,
        finalDocumentUrl: String?,
        configuredUrl: String,
        documentTitle: String? = null,
    ): ManifestResult {
        val configured = requireNotNull(UrlPolicy.parseHttps(configuredUrl))
        val document = finalDocumentUrl?.let(UrlPolicy::parseHttps)
        val fallback = document ?: configured
        val source = manifestUrl?.let(UrlPolicy::parseHttps)
        val json = if (source != null && manifestText != null && hasValidUnicode(manifestText) &&
            manifestText.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
            parseObject(manifestText) else null
        if (json == null) return ManifestResult(null, fallback.serialized,
            fallback.origin + "/", sanitizeDisplay(documentTitle) ?: fallback.host)

        val start = string(json, "start_url")?.let { resolve(it, source!!) }
        val declaredScope = string(json, "scope")?.let { resolve(it, source!!) }
        val icons = mutableListOf<IconCandidate>()
        val iconArray = json.optJSONArray("icons")
        if (iconArray != null) for (index in 0 until minOf(iconArray.length(), 128)) {
            val icon = iconArray.optJSONObject(index) ?: continue
            val url = string(icon, "src")?.let { resolve(it, source!!) } ?: continue
            icons += IconCandidate(url.serialized, IconCandidate.Source.MANIFEST,
                string(icon, "sizes")?.let(::sanitizeDisplay),
                string(icon, "type")?.let(::sanitizeDisplay),
                string(icon, "purpose")?.let(::sanitizeDisplay), index)
        }
        val shortName = string(json, "short_name")?.let(::sanitizeDisplay)
        val name = string(json, "name")?.let(::sanitizeDisplay)
        val display = string(json, "display")?.trim()?.lowercase()
            ?.takeIf { it in setOf("fullscreen", "standalone", "minimal-ui", "browser") }
        val snapshot = ManifestSnapshot(source!!.serialized, name, shortName,
            start?.serialized, declaredScope?.serialized, display,
            string(json, "theme_color")?.let(::parseColor),
            string(json, "background_color")?.let(::parseColor), icons)
        return applySnapshot(snapshot, finalDocumentUrl, configuredUrl, documentTitle)
    }

    /** Re-evaluate stored manifest URLs against the current document after a failed Refresh. */
    fun applySnapshot(
        snapshot: ManifestSnapshot,
        finalDocumentUrl: String?,
        configuredUrl: String,
        documentTitle: String? = null,
    ): ManifestResult {
        val configured = requireNotNull(UrlPolicy.parseHttps(configuredUrl))
        val fallback = finalDocumentUrl?.let(UrlPolicy::parseHttps) ?: configured
        val start = snapshot.startUrl?.let(UrlPolicy::parseHttps)
            ?.takeIf { UrlPolicy.sameOrigin(it, fallback) }
        val effectiveStart = start ?: fallback
        val explicitScope = snapshot.scope?.let(UrlPolicy::parseHttps)
            ?.takeIf { UrlPolicy.isWithinScope(effectiveStart, it) }
            ?.let { UrlPolicy.parseHttps(it.origin + it.path) }
        val scope = explicitScope ?: defaultScope(effectiveStart)
        val launch = if (UrlPolicy.isWithinScope(effectiveStart, scope)) effectiveStart else fallback
        return ManifestResult(snapshot, launch.serialized, scope.serialized,
            snapshot.shortName ?: snapshot.name ?: sanitizeDisplay(documentTitle) ?: fallback.host)
    }

    private fun parseObject(text: String): JSONObject? {
        return try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
                val value = readObject(reader, 0)
                if (reader.peek() == JsonToken.END_DOCUMENT) value else null
            }
        } catch (_: Exception) { null }
    }

    private fun readObject(reader: JsonReader, depth: Int): JSONObject {
        if (depth > 64) throw JSONException("Manifest nesting limit exceeded")
        val result = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) result.put(reader.nextName(), readValue(reader, depth + 1))
        reader.endObject()
        return result
    }

    private fun readArray(reader: JsonReader, depth: Int): JSONArray {
        if (depth > 64) throw JSONException("Manifest nesting limit exceeded")
        val result = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) result.put(readValue(reader, depth + 1))
        reader.endArray()
        return result
    }

    private fun readValue(reader: JsonReader, depth: Int): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(reader, depth)
        JsonToken.BEGIN_ARRAY -> readArray(reader, depth)
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let {
            it.toLongOrNull() ?: it.toDouble().takeIf(Double::isFinite) ?: JSONObject.NULL
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> throw JSONException("Malformed manifest")
    }

    private fun string(json: JSONObject, key: String): String? =
        (json.opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun resolve(value: String, base: UrlPolicy.HttpsUrl): UrlPolicy.HttpsUrl? {
        if (value.length > 65536 || value.any(Character::isISOControl) ||
            !hasValidUnicode(value)) return null
        return try { UrlPolicy.parseHttps(Url.create(value, base.serialized).href()) }
        catch (_: ValidationException) { null }
    }

    private fun defaultScope(start: UrlPolicy.HttpsUrl): UrlPolicy.HttpsUrl {
        val path = start.path.substringBeforeLast('/', "") + "/"
        return requireNotNull(UrlPolicy.parseHttps(start.origin + path))
    }

    private fun hasValidUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isLowSurrogate(char)) return false
            if (Character.isHighSurrogate(char)) {
                if (index + 1 == value.length || !Character.isLowSurrogate(value[index + 1]))
                    return false
                index++
            }
            index++
        }
        return true
    }

    private fun sanitizeDisplay(value: String?): String? {
        if (value == null) return null
        val out = StringBuilder()
        var offset = 0
        var count = 0
        while (offset < value.length && count < MAX_DISPLAY_CODE_POINTS) {
            val cp = value.codePointAt(offset)
            offset += Character.charCount(cp)
            if (cp in 0xD800..0xDFFF || Character.isISOControl(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt()) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    private fun parseColor(value: String): Int? {
        val color = value.trim()
        if (color.startsWith('#')) {
            val digits = color.drop(1)
            if (digits.length !in setOf(3, 4, 6, 8) || digits.any { it.digitToIntOrNull(16) == null })
                return null
            val rgba = when (digits.length) {
                3 -> digits.map { "$it$it" }.joinToString("") + "ff"
                4 -> digits.map { "$it$it" }.joinToString("")
                6 -> digits + "ff"
                else -> digits
            }
            val r = rgba.substring(0, 2).toInt(16)
            val g = rgba.substring(2, 4).toInt(16)
            val b = rgba.substring(4, 6).toInt(16)
            val a = rgba.substring(6, 8).toInt(16)
            return Color.argb(a, r, g, b)
        }
        if (color.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
        CssNamedColors.values[color.lowercase()]?.let { return it }
        val function = Regex("(?i)(rgb|rgba|hsl|hsla)\\(([^()]*)\\)").matchEntire(color)
            ?: return null
        val kind = function.groupValues[1].lowercase()
        val body = function.groupValues[2]
        val legacy = ',' in body
        val pieces = if (legacy) body.split(',').map(String::trim)
            else body.replace("/", " / ").trim().split(Regex("\\s+"))
        val components: List<String>
        val alphaText: String?
        if (legacy) {
            if (pieces.size !in 3..4 || pieces.any(String::isEmpty)) return null
            components = pieces.take(3)
            alphaText = pieces.getOrNull(3)
        } else {
            val slash = pieces.indexOf("/")
            if (slash < 0 && pieces.size != 3 || slash != -1 &&
                (slash != 3 || pieces.size != 5)) return null
            components = pieces.take(3)
            alphaText = if (slash == 3) pieces[4] else null
        }
        val alpha = alphaText?.let(::cssAlpha) ?: if (alphaText == null) 255 else return null
        if (kind.startsWith("rgb")) {
            val channels = components.map(::cssRgbChannel)
            if (channels.any { it == null }) return null
            return Color.argb(alpha, channels[0]!!, channels[1]!!, channels[2]!!)
        }
        val hue = components[0].removeSuffix("deg").toDoubleOrNull()
            ?.takeIf(Double::isFinite) ?: return null
        val saturation = cssPercentage(components[1]) ?: return null
        val lightness = cssPercentage(components[2]) ?: return null
        val chroma = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
        val section = ((hue % 360 + 360) % 360) / 60
        val x = chroma * (1 - kotlin.math.abs(section % 2 - 1))
        val (r, g, b) = when (section.toInt()) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val m = lightness - chroma / 2
        return Color.argb(alpha, ((r + m) * 255 + 0.5).toInt(),
            ((g + m) * 255 + 0.5).toInt(), ((b + m) * 255 + 0.5).toInt())
    }

    private fun cssAlpha(value: String): Int? {
        val number = if (value.endsWith('%')) value.dropLast(1).toDoubleOrNull()?.div(100)
            else value.toDoubleOrNull()
        return number?.takeIf(Double::isFinite)?.let { (it.coerceIn(0.0, 1.0) * 255 + 0.5).toInt() }
    }

    private fun cssRgbChannel(value: String): Int? {
        val number = if (value.endsWith('%')) value.dropLast(1).toDoubleOrNull()?.times(255 / 100.0)
            else value.toDoubleOrNull()
        return number?.takeIf(Double::isFinite)?.let { (it.coerceIn(0.0, 255.0) + 0.5).toInt() }
    }

    private fun cssPercentage(value: String): Double? = value.takeIf { it.endsWith('%') }
        ?.dropLast(1)?.toDoubleOrNull()?.takeIf(Double::isFinite)?.div(100)?.coerceIn(0.0, 1.0)
}
