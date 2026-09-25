package nu.staldal.mws

/** The document-stage result never treats a redirect that has not committed as final. */
data class DiscoveryDocumentResult(
    val finalUrl: String?,
    val crossOrigin: Boolean,
    val failure: DiscoveryDocumentFailure? = null,
)

enum class DiscoveryDocumentFailure { HTTPS_REQUIRED, TLS_ERROR, LOAD_ERROR, TIMEOUT }

enum class DiscoveryTrigger { DEBOUNCE, FOCUS_LOSS, REFRESH }

/** Monotonic, pausable deadlines for one attempt. The caller supplies elapsed-realtime milliseconds. */
class DiscoveryAttempt(
    val id: Long,
    val configuredUrl: UrlPolicy.HttpsUrl,
    val trigger: DiscoveryTrigger,
    private val initialMillis: Long = 15_000,
    private val overallMillis: Long = 30_000,
) {
    private var startedAt: Long? = null
    private var pausedAt: Long? = null
    private var pausedTotal = 0L
    private var documentFinished = false
    private var finished = false
    var documentResult: DiscoveryDocumentResult? = null
        private set
    val isPaused: Boolean get() = pausedAt != null

    fun start(now: Long) {
        check(startedAt == null)
        startedAt = now
    }

    fun pause(now: Long) {
        if (!finished && startedAt != null && pausedAt == null) pausedAt = now
    }

    fun resume(now: Long) {
        pausedAt?.let { pausedTotal += (now - it).coerceAtLeast(0); pausedAt = null }
    }

    fun remainingInitial(now: Long): Long? =
        if (documentFinished || finished) null else remaining(now, initialMillis)

    fun remainingOverall(now: Long): Long? =
        if (finished) null else remaining(now, overallMillis)

    private fun remaining(now: Long, limit: Long): Long? {
        val start = startedAt ?: return null
        val activeNow = pausedAt ?: now
        return limit - (activeNow - start - pausedTotal).coerceAtLeast(0)
    }

    /** Returns true only for the first visible main-frame commit. */
    fun commit(url: String): Boolean {
        if (finished || documentFinished) return false
        documentFinished = true
        val parsed = UrlPolicy.parseHttps(url)
        documentResult = DiscoveryDocumentResult(
            parsed?.serialized?.takeIf { parsed.origin == configuredUrl.origin },
            parsed != null && parsed.origin != configuredUrl.origin,
        )
        return true
    }

    fun failDocument(failure: DiscoveryDocumentFailure = DiscoveryDocumentFailure.LOAD_ERROR): Boolean {
        if (finished || documentFinished) return false
        documentFinished = true
        documentResult = DiscoveryDocumentResult(null, false, failure)
        return true
    }

    fun finish() { finished = true }
}
