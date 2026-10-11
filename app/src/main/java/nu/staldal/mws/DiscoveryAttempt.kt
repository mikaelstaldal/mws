package nu.staldal.mws

/** The document-stage result never treats a redirect that has not committed as final. */
data class DiscoveryDocumentResult(
    val finalUrl: String?,
    val crossOrigin: Boolean,
    val failure: DiscoveryDocumentFailure? = null,
    /** Committed HTTPS destination for user adoption; never authorizes cross-origin metadata. */
    val redirectedUrl: String? = null,
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
    private var snapshotAccepted = false
    private var follows = 0
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
            redirectedUrl = parsed?.serialized?.takeIf { it != configuredUrl.serialized },
        )
        return true
    }

    fun failDocument(failure: DiscoveryDocumentFailure = DiscoveryDocumentFailure.LOAD_ERROR): Boolean {
        if (finished || documentFinished) return false
        documentFinished = true
        documentResult = DiscoveryDocumentResult(null, false, failure)
        return true
    }

    /**
     * A committed same-origin document that navigates to another same-origin document before
     * its DOM snapshot is accepted (an immediate meta refresh or a script redirect) was only a
     * redirect page. Reopens the document stage so that the next commit becomes final.
     */
    fun follow(url: String): Boolean {
        val committed = documentResult?.finalUrl
        val target = UrlPolicy.parseHttps(url)
        if (finished || !documentFinished || snapshotAccepted || committed == null ||
            follows >= MAX_FOLLOWS || target == null || target.origin != configuredUrl.origin ||
            target.serialized.substringBefore('#') == committed.substringBefore('#')) return false
        follows++
        documentFinished = false
        documentResult = null
        return true
    }

    /** Accepting the DOM snapshot of the committed document makes it final. */
    fun acceptSnapshot(): Boolean {
        if (finished || !documentFinished || documentResult?.finalUrl == null) return false
        snapshotAccepted = true
        return true
    }

    fun finish() { finished = true }

    companion object {
        const val MAX_FOLLOWS = 5
    }
}
