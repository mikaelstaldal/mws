package nu.staldal.mws

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.CookieHandler
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Full context is required before a provider may enable cookies for discovery. */
data class DiscoveryCookieContext(
    val url: String,
    val topLevelSite: String,
    val partitionKey: String,
    val role: Role,
    val credentials: Credentials,
) {
    enum class Role { MANIFEST, MANIFEST_ICON, PAGE_ICON, FAVICON }
    enum class Credentials { OMIT, SAME_ORIGIN, INCLUDE }
}

interface DiscoveryCookieAdapter {
    fun requestCookie(context: DiscoveryCookieContext): String?
    fun receiveSetCookie(context: DiscoveryCookieContext, headers: List<String>)
}

fun interface DiscoveryBasicAuth {
    /** Return the already supplied credential only for the given realm and URL. */
    fun credential(url: String, realm: String): Pair<String, String>?
}

data class DiscoveryResponse(val url: String, val body: ByteArray)

/**
 * Sole native fetcher of manifest and icon bodies. Invoke on a worker thread.
 * The default is credentialless; full-context cookies require an explicit compatibility flag.
 * Unlike the document WebView, this client does not enforce document CSP. Its bytes are used
 * only by native metadata and image parsers; no script runs and no state is transferred.
 */
class NativeDiscoveryClient(
    private val documentUrl: String,
    private val topLevelSite: String,
    private val cookieAdapter: DiscoveryCookieAdapter? = null,
    private val fullContextCookiesVerified: Boolean = false,
    private val basicAuth: DiscoveryBasicAuth? = null,
    private val limits: Limits = Limits(),
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val attemptBytes: AtomicLong = AtomicLong(0),
) : IconFetcher {
    data class Limits(
        val resourceMillis: Long = 10_000,
        val redirects: Int = 10,
        val manifestDecodedBytes: Int = 1024 * 1024,
        val iconEncodedBytes: Int = 5 * 1024 * 1024,
        val attemptBytes: Long = 20L * 1024 * 1024,
    )

    private val cancelled = AtomicBoolean(false)
    private val identity = AtomicLong(0)
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var manifestOrigin: String? = null
    @Volatile private var manifestUrl: String? = null
    @Volatile private var pageReferrerPolicy = "strict-origin-when-cross-origin"
    @Volatile private var documentReferrerPolicy = "strict-origin-when-cross-origin"

    @Volatile var limitsExceeded: Boolean = false
        private set

    val authenticatedMetadataWarning: Boolean get() = !fullContextCookiesVerified || cookieAdapter == null

    fun cancel() {
        cancelled.set(true)
        identity.incrementAndGet()
        activeConnection?.disconnect()
    }

    /** Invalidate a late request without cancelling the entire attempt. */
    fun newRequestIdentity(): Long {
        val next = identity.incrementAndGet()
        activeConnection?.disconnect()
        return next
    }

    fun setReferrerPolicies(document: String, pageIcon: String) {
        documentReferrerPolicy = document
        pageReferrerPolicy = pageIcon
    }

    fun fetchManifest(url: String, crossorigin: String?, referrerPolicy: String = documentReferrerPolicy): DiscoveryResponse? {
        val doc = UrlPolicy.parseHttps(documentUrl) ?: return null
        manifestUrl = null
        manifestOrigin = null
        val credentials = if (crossorigin.equals("use-credentials", true))
            DiscoveryCookieContext.Credentials.INCLUDE else DiscoveryCookieContext.Credentials.SAME_ORIGIN
        return fetch(url, DiscoveryCookieContext.Role.MANIFEST, credentials, doc.origin,
            documentUrl, referrerPolicy, limits.manifestDecodedBytes)?.also {
            manifestUrl = it.url
            manifestOrigin = UrlPolicy.parseHttps(it.url)?.origin
        }
    }

    /** Restored snapshots carry a sanitized manifest response URL for icon request context. */
    fun retainManifestSource(url: String) {
        val parsed = UrlPolicy.parseHttps(url) ?: return
        manifestUrl = parsed.serialized
        manifestOrigin = parsed.origin
    }

    override fun fetch(candidate: IconCandidate): ByteArray? {
        val doc = UrlPolicy.parseHttps(documentUrl) ?: return null
        val role: DiscoveryCookieContext.Role
        val baseOrigin: String
        val referrer: String
        val policy: String
        when (candidate.source) {
            IconCandidate.Source.MANIFEST -> {
                role = DiscoveryCookieContext.Role.MANIFEST_ICON
                baseOrigin = manifestOrigin ?: return null
                referrer = manifestUrl ?: return null
                policy = documentReferrerPolicy
            }
            IconCandidate.Source.PAGE -> {
                role = DiscoveryCookieContext.Role.PAGE_ICON
                baseOrigin = doc.origin
                referrer = documentUrl
                policy = candidate.referrerPolicy?.takeIf(String::isNotBlank) ?: pageReferrerPolicy
            }
            IconCandidate.Source.FAVICON -> {
                role = DiscoveryCookieContext.Role.FAVICON
                baseOrigin = doc.origin
                referrer = documentUrl
                policy = documentReferrerPolicy
                val parsed = UrlPolicy.parseHttps(candidate.url) ?: return null
                if (parsed.origin != doc.origin || parsed.path != "/favicon.ico") return null
            }
        }
        return fetch(candidate.url, role, DiscoveryCookieContext.Credentials.SAME_ORIGIN,
            baseOrigin, referrer, policy, limits.iconEncodedBytes)?.body
    }

    private @Synchronized fun fetch(
        requested: String, role: DiscoveryCookieContext.Role,
        credentials: DiscoveryCookieContext.Credentials, credentialOrigin: String,
        referrer: String, policy: String, bodyLimit: Int,
    ): DiscoveryResponse? {
        val requestId = identity.incrementAndGet()
        if (cancelled.get()) return null
        val first = UrlPolicy.parseHttps(requested) ?: return null
        val doc = UrlPolicy.parseHttps(documentUrl) ?: return null
        // HttpURLConnection can consult process-wide handlers even when no header is set.
        // A discovery attempt must never use ambient cookies or credentials.
        if (CookieHandler.getDefault() != null) return null
        val start = nowMillis()
        val watchdog = TIMER.schedule({
            limitsExceeded = true
            identity.compareAndSet(requestId, requestId + 1)
            activeConnection?.disconnect()
        }, limits.resourceMillis, TimeUnit.MILLISECONDS)
        var current = first
        var redirects = 0
        var authRetried = false
        var authHeader: String? = null
        var crossedOrigin = false
        var corsTainted = first.origin != doc.origin
        try {
        while (true) {
            if (cancelled.get() || identity.get() != requestId) return null
            val remaining = limits.resourceMillis - (nowMillis() - start)
            if (remaining <= 0) { limitsExceeded = true; return null }
            val eligible = current.origin == credentialOrigin ||
                (role == DiscoveryCookieContext.Role.MANIFEST &&
                    credentials == DiscoveryCookieContext.Credentials.INCLUDE)
            val effective = if (fullContextCookiesVerified && cookieAdapter != null && eligible)
                credentials else DiscoveryCookieContext.Credentials.OMIT
            val context = DiscoveryCookieContext(current.serialized, topLevelSite,
                topLevelSite, role, effective)
            val conn = try { open(current.serialized) } catch (_: Exception) { return null }
            activeConnection = conn
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                conn.readTimeout = conn.connectTimeout
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
                // Explicit empty values keep ambient URLConnection handlers from adding them.
                conn.setRequestProperty("Cookie", "")
                conn.setRequestProperty("Authorization", "")
                conn.setRequestProperty("Proxy-Authorization", "")
                if (role == DiscoveryCookieContext.Role.MANIFEST)
                    conn.setRequestProperty("Origin", doc.origin)
                referrerHeader(referrer, current.serialized, policy)?.let {
                    conn.setRequestProperty("Referer", it)
                }
                if (effective != DiscoveryCookieContext.Credentials.OMIT)
                    cookieAdapter?.requestCookie(context)?.let { conn.setRequestProperty("Cookie", it) }
                if (!crossedOrigin && current.origin == doc.origin && authHeader != null)
                    conn.setRequestProperty("Authorization", authHeader)
                val status = conn.responseCode
                if (cancelled.get() || identity.get() != requestId || nowMillis() - start >= limits.resourceMillis)
                    return null
                if (effective != DiscoveryCookieContext.Credentials.OMIT)
                    cookieAdapter?.receiveSetCookie(context, conn.headerFields.entries
                        .filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value.orEmpty() })
                if (status in 300..399 && status != 304) {
                    if (redirects++ >= limits.redirects) { limitsExceeded = true; return null }
                    val location = conn.getHeaderField("Location") ?: return null
                    val next = resolve(current.serialized, location) ?: return null
                    if (role == DiscoveryCookieContext.Role.MANIFEST &&
                        (current.origin != doc.origin || corsTainted) &&
                        !corsAllowed(conn, doc.origin, credentials)) return null
                    if (next.origin != current.origin) {
                        crossedOrigin = true
                        authHeader = null
                    }
                    if (next.origin != doc.origin) corsTainted = true
                    current = next
                    continue
                }
                if (status == 401 && !authRetried && !crossedOrigin &&
                    current.origin == doc.origin && basicAuth != null) {
                    val challenge = conn.getHeaderField("WWW-Authenticate").orEmpty()
                    val realm = Regex("(?i)^Basic\\s+realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1)
                    val pair = realm?.let { basicAuth.credential(current.serialized, it) }
                    if (pair != null) {
                        val token = java.util.Base64.getEncoder().encodeToString(
                            "${pair.first}:${pair.second}".toByteArray(StandardCharsets.ISO_8859_1))
                        authHeader = "Basic $token"
                        authRetried = true
                        continue
                    }
                }
                if (status !in 200..299) return null
                if (role == DiscoveryCookieContext.Role.MANIFEST && corsTainted &&
                    !corsAllowed(conn, doc.origin, credentials)) return null
                val raw = conn.inputStream ?: return null
                val counted = counting(raw, if (role == DiscoveryCookieContext.Role.MANIFEST)
                    null else limits.iconEncodedBytes, start, requestId)
                val stream = when (conn.getHeaderField("Content-Encoding")?.lowercase(Locale.ROOT)) {
                    "gzip" -> GZIPInputStream(counted)
                    "deflate" -> InflaterInputStream(counted)
                    null, "identity" -> counted
                    else -> return null
                }
                val output = ByteArrayOutputStream()
                stream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (cancelled.get() || identity.get() != requestId ||
                            nowMillis() - start >= limits.resourceMillis) return null
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > bodyLimit) { limitsExceeded = true; return null }
                        val aggregateBytes = attemptBytes.addAndGet(n.toLong())
                        if (aggregateBytes > limits.attemptBytes) { limitsExceeded = true; return null }
                        output.write(buffer, 0, n)
                    }
                }
                if (cancelled.get() || identity.get() != requestId ||
                    nowMillis() - start >= limits.resourceMillis) return null
                return DiscoveryResponse(current.serialized, output.toByteArray())
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
                if (activeConnection === conn) activeConnection = null
            }
        }
        } finally {
            watchdog.cancel(false)
            activeConnection?.disconnect()
            activeConnection = null
        }
    }

    private fun corsAllowed(conn: HttpURLConnection, origin: String,
        credentials: DiscoveryCookieContext.Credentials): Boolean {
        val acao = conn.headerFields.entries.filter { it.key.equals("Access-Control-Allow-Origin", true) }
            .flatMap { it.value.orEmpty() }
        if (acao.size != 1 || (acao.single() != origin &&
                !(acao.single() == "*" && credentials != DiscoveryCookieContext.Credentials.INCLUDE))) return false
        return credentials != DiscoveryCookieContext.Credentials.INCLUDE ||
            conn.getHeaderField("Access-Control-Allow-Credentials") == "true"
    }

    private fun counting(input: InputStream, encodedLimit: Int?, start: Long,
        requestId: Long): InputStream =
        object : InputStream() {
            var encodedBytes = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (cancelled.get() || identity.get() != requestId ||
                    nowMillis() - start >= limits.resourceMillis) throw LimitExceeded()
                val n = input.read(bytes, offset, length)
                if (n > 0) {
                    encodedBytes += n
                    if (encodedLimit != null && encodedBytes > encodedLimit) {
                        limitsExceeded = true
                        throw LimitExceeded()
                    }
                }
                return n
            }
            override fun close() = input.close()
        }

    private class LimitExceeded : Exception()

    companion object {
        private val TIMER = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mws-discovery-deadline").apply { isDaemon = true }
        }
    }

    private fun resolve(base: String, location: String): UrlPolicy.HttpsUrl? = try {
        UrlPolicy.parseHttps(URI(base).resolve(location).toString())
    } catch (_: Exception) { null }

    private fun referrerHeader(source: String, destination: String, policy: String): String? {
        val from = UrlPolicy.parseHttps(source) ?: return null
        val to = UrlPolicy.parseHttps(destination) ?: return null
        val same = from.origin == to.origin
        val full = from.serialized.substringBefore('#').let {
            if (it.length > 4096) from.origin + "/" else it
        }
        return when (policy.lowercase(Locale.ROOT)) {
            "no-referrer" -> null
            "origin" -> from.origin + "/"
            "same-origin" -> if (same) full else null
            "origin-when-cross-origin", "strict-origin-when-cross-origin" ->
                if (same) full else from.origin + "/"
            "unsafe-url", "no-referrer-when-downgrade" -> full
            "strict-origin" -> from.origin + "/"
            else -> if (same) full else from.origin + "/"
        }
    }
}
