package nu.staldal.mws

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

/** Detached values only: no DOM handles or mutable JSON objects escape parsing. */
data class DiscoveryDomLink(
    val url: String?, val media: String?, val mediaMatches: Boolean,
    val type: String?, val crossorigin: String?, val referrerPolicy: String?,
    val sizes: String?, val usable: Boolean,
)
data class DiscoveryDomSnapshot(
    val title: String?, val manifests: List<DiscoveryDomLink>, val icons: List<DiscoveryDomLink>,
) {
    /** An unusable first matching candidate must not cause fall-through. */
    val selectedManifest: DiscoveryDomLink? get() = manifests.firstOrNull { it.mediaMatches }
}

internal object DiscoveryDom {
    private const val STRING_LIMIT = 8192
    private const val SNAPSHOT_LIMIT = 1048576

    fun parseEvaluation(raw: String): DiscoveryDomSnapshot? = try {
        // evaluateJavascript wraps the returned JSON string in another JSON string.
        if (raw.length > SNAPSHOT_LIMIT * 6 + 2) null else {
            val token = JSONTokener(raw)
            val value = token.nextValue()
            if (value !is String || token.nextClean() != '\u0000') null else parse(value)
        }
    } catch (_: Exception) { null }

    fun parse(json: String): DiscoveryDomSnapshot? = try {
        if (json.toByteArray(Charsets.UTF_8).size > SNAPSHOT_LIMIT) null else {
            val token = JSONTokener(json)
            val root = token.nextValue() as? JSONObject
            if (root == null || token.nextClean() != '\u0000') null else {
                val manifests = links(root.getJSONArray("manifests"), 16)
                val icons = links(root.getJSONArray("icons"), 128)
                DiscoveryDomSnapshot(string(root, "title"), manifests, icons)
            }
        }
    } catch (_: Exception) { null }

    private fun string(obj: JSONObject, key: String): String? =
        (obj.opt(key) as? String)?.takeIf { it.toByteArray(Charsets.UTF_8).size <= STRING_LIMIT }

    private fun links(array: JSONArray, cap: Int): List<DiscoveryDomLink> {
        require(array.length() <= cap)
        val values = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val url = string(obj, "url")
            val media = string(obj, "media")
            val type = string(obj, "type")
            val crossorigin = string(obj, "crossorigin")
            val policy = string(obj, "referrerPolicy")
            val sizes = string(obj, "sizes")
            require(obj.opt("mediaMatches") is Boolean && obj.opt("usable") is Boolean)
            DiscoveryDomLink(url, media, obj.getBoolean("mediaMatches"), type, crossorigin,
                policy, sizes, obj.getBoolean("usable") && url != null && media != null &&
                    type != null && crossorigin != null && policy != null && sizes != null &&
                    UrlPolicy.parseHttps(url) != null)
        }
        return Collections.unmodifiableList(values)
    }
}

/** Identity includes navigation sequence, so a reload of the same URL invalidates old replies. */
internal class DiscoveryDomIdentity {
    private var document = 0L
    private var requested = false
    fun started() { document++; requested = false }
    fun request(): Long? = if (requested) null else document.also { requested = true }
    fun accepts(identity: Long): Boolean = requested && identity == document
}
