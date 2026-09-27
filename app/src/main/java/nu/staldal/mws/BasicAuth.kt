package nu.staldal.mws

import android.os.Handler
import android.os.Looper
import android.webkit.HttpAuthHandler
import java.util.Locale
import java.util.UUID

/**
 * Basic-auth navigation gate (A§11). Pure and thread-safe: `shouldInterceptRequest` disarms it
 * from a background thread, every other signal arrives on the main thread.
 *
 * It records only the canonical top-level request URL, never a credential. A challenge is answered
 * only while exactly this gate is armed for a navigation the app itself observed before dispatch.
 */
internal class BasicAuthGate(private val eligible: () -> Boolean = { true }) {
    data class Target(val url: String, val scheme: String, val host: String, val port: Int) {
        val origin: Triple<String, String, Int> get() = Triple(scheme, host, port)
    }

    /** [documentRequested]: the provider has reported this navigation's own main-document request. */
    data class Armed(val identity: String, val target: Target, val documentRequested: Boolean = false)

    sealed interface Challenge {
        /** Look up exactly this URL and realm; answer only if [identity] is still armed. */
        data class Lookup(val identity: String, val url: String, val realm: String) : Challenge
        /** A repeat of an answered (identity, URL, realm): the credential was rejected. */
        data object Rejected : Challenge
        /** Not the current eligible top-level navigation, or ambiguous: cancel silently. */
        data object Cancel : Challenge
    }

    private val lock = Any()
    private var armed: Armed? = null
    // Lifetime latch: clearing a navigation must never make an old request eligible again.
    private var used = false
    private var retired = false
    private val startedUrls = mutableSetOf<String>()
    private val supplied = mutableSetOf<Triple<String, String, String>>()
    private var outstanding: String? = null

    val current: Armed? get() = synchronized(lock) { armed }

    /**
     * The first call arms the initial navigation. Any later load, reload, Retry or
     * non-redirect navigation permanently retires eligibility for this WebView.
     */
    fun arm(url: String): Armed? = synchronized(lock) {
        clear()
        if (used || retired || !eligible()) {
            retired = true
            return null
        }
        used = true
        val target = target(url) ?: return null
        Armed(UUID.randomUUID().toString(), target).also { armed = it }
    }

    /**
     * A main-frame redirect continuation that already passed the navigation policy. Only a
     * same-origin continuation keeps the gate armed; a credential never follows a cross-origin
     * redirect, so anything else fails closed.
     */
    fun redirect(url: String): Boolean = synchronized(lock) {
        val current = armed ?: return false
        val target = target(url)
        if (target == null || target.origin != current.target.origin || outstanding != null) {
            clear()
            return false
        }
        armed = current.copy(target = target)
        true
    }

    /** Only the recorded main-frame document can keep lifetime eligibility. */
    fun intercepted(url: String, mainFrame: Boolean = true): Boolean = synchronized(lock) {
        val current = armed ?: return false
        if (mainFrame && target(url)?.url == current.target.url) {
            armed = current.copy(documentRequested = true)
            return false
        }
        clear()
        true
    }

    /** Only an early first start before the main request is observed can remain eligible. */
    fun started(url: String): Boolean = synchronized(lock) {
        val requestUrl = target(url)?.url
        if (requestUrl == null || armed?.target?.url != requestUrl || armed?.documentRequested == true || !startedUrls.add(requestUrl)) {
            return disarm()
        }
        false
    }

    /** A new main-frame navigation, commit, terminal error or cancellation. */
    fun disarm(): Boolean = synchronized(lock) {
        retired = true
        val wasArmed = armed != null
        clear()
        wasArmed
    }

    /** `onReceivedHttpAuthRequest`. Scheme and port come from the recorded navigation. */
    fun challenge(host: String?, realm: String?): Challenge = synchronized(lock) {
        if (!eligible()) { clear(); return Challenge.Cancel }
        val current = armed ?: return Challenge.Cancel
        if (host == null || realm == null || outstanding != null) return Challenge.Cancel
        // A challenge before the navigation's own request can only belong to a request that was
        // already in flight, such as a fetch from the previous page.
        if (!current.documentRequested) return Challenge.Cancel
        if (normalizeHost(host) != current.target.host) return Challenge.Cancel
        if (Triple(current.identity, current.target.url, realm) in supplied) {
            // A repeated challenge counts as rejection; nothing more is supplied for this navigation.
            clear()
            return Challenge.Rejected
        }
        outstanding = current.identity
        Challenge.Lookup(current.identity, current.target.url, realm)
    }

    /** The lookup for [lookup] is no longer outstanding and must not be answered. */
    fun abandon(lookup: Challenge.Lookup) = synchronized(lock) {
        if (outstanding == lookup.identity) outstanding = null
    }

    /**
     * Marks the credential for [lookup] as supplied. Returns false when the navigation has moved
     * on or the credential was already supplied once; the caller must then cancel.
     */
    fun supply(lookup: Challenge.Lookup): Boolean = synchronized(lock) {
        if (outstanding == lookup.identity) outstanding = null
        if (!eligible()) { clear(); return false }
        val current = armed ?: return false
        if (current.identity != lookup.identity || current.target.url != lookup.url) return false
        supplied.add(Triple(lookup.identity, lookup.url, lookup.realm))
    }

    fun isCurrent(lookup: Challenge.Lookup): Boolean = synchronized(lock) {
        eligible() && ownsLookup(lookup)
    }

    /** Result delivery must still settle an owned lookup whose profile became unsafe. */
    fun ownsLookup(lookup: Challenge.Lookup): Boolean = synchronized(lock) {
        armed?.let { it.identity == lookup.identity && it.target.url == lookup.url } == true &&
            outstanding == lookup.identity
    }

    private fun clear() {
        armed = null
        outstanding = null
        supplied.clear()
    }

    companion object {
        fun target(url: String): Target? {
            val parsed = UrlPolicy.parseNavigation(url) ?: return null
            val host = normalizeHost(parsed.host)
            val authority = parsed.origin.removePrefix("https://")
            val port = authority.substringAfterLast(':', "").takeIf {
                authority.lastIndexOf(':') > authority.lastIndexOf(']')
            }?.toIntOrNull() ?: 443
            // The request URL, as the provider reports it: a fragment is never sent.
            return Target(parsed.serialized.substringBefore('#'), "https", host, port)
        }

        /** The provider reports IPv6 hosts without brackets; DNS names compare case-insensitively. */
        fun normalizeHost(host: String): String =
            host.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)
    }
}

/** The only place that answers an HTTP auth challenge; see `scripts/check-security.py`. */
internal interface AuthAnswer {
    fun proceed(username: String, password: String)
    fun cancel()
}

internal fun HttpAuthHandler.asAnswer(): AuthAnswer = object : AuthAnswer {
    override fun proceed(username: String, password: String) = this@asAnswer.proceed(username, password)
    override fun cancel() = this@asAnswer.cancel()
}

/**
 * Shell side of A§11. Main thread only, except [intercepted]. Every challenge that is not the
 * current eligible top-level navigation, or that cannot get a credential, is cancelled; there is
 * no manual fallback and the provider HTTP-auth database is never used.
 */
internal class ShellBasicAuth(
    private val enabled: () -> Boolean,
    private val client: () -> MyPassClient?,
    private val flow: String,
    private val eligible: () -> Boolean = { true },
    /** A lookup is starting: give it precedence over form fill and pause the load timer. */
    private val onLookup: () -> Unit,
    /** The lookup ended, whatever its outcome. */
    private val onLookupEnded: () -> Unit,
    /** Shows a non-sensitive error for the current navigation. */
    private val onError: (Int) -> Unit,
    private val onCredential: (BasicAuthGate.Challenge.Lookup, MyPassClient.Outcome.Credential) -> Unit = { _, _ -> },
) {
    val gate = BasicAuthGate(eligible)
    private val main = Handler(Looper.getMainLooper())
    private var pending: Pending? = null

    private class Pending(
        val lookup: BasicAuthGate.Challenge.Lookup,
        val answer: AuthAnswer,
        val client: MyPassClient,
    )

    fun arm(url: String) {
        gate.arm(url)
        settle()
    }

    fun redirect(url: String) {
        gate.redirect(url)
        settle()
    }

    fun started(url: String) {
        gate.started(url)
        settle()
    }

    fun disarm() {
        gate.disarm()
        settle()
    }

    /** Any thread: `shouldInterceptRequest`. */
    fun intercepted(url: String, mainFrame: Boolean = true) {
        if (gate.intercepted(url, mainFrame)) main.post(::settle)
    }

    fun challenge(answer: AuthAnswer, host: String?, realm: String?) {
        if (!enabled()) {
            // Below the floor or a failed self-test probe: every challenge is cancelled.
            gate.disarm()
            settle()
            answer.cancel()
            return
        }
        when (val challenge = gate.challenge(host, realm)) {
            BasicAuthGate.Challenge.Cancel -> {
                answer.cancel()
                settle()
            }
            BasicAuthGate.Challenge.Rejected -> {
                answer.cancel()
                settle()
                onError(R.string.shell_auth_rejected)
            }
            is BasicAuthGate.Challenge.Lookup -> lookup(challenge, answer)
        }
    }

    private fun lookup(lookup: BasicAuthGate.Challenge.Lookup, answer: AuthAnswer) {
        val myPass = client()
        if (myPass == null) {
            gate.abandon(lookup)
            answer.cancel()
            onError(R.string.shell_auth_unavailable)
            return
        }
        onLookup()
        val request = Pending(lookup, answer, myPass)
        pending = request
        val launched = myPass.fetch(MyPassClient.Identity(flow, lookup.identity), lookup.url, lookup.realm,
            isCurrent = { pending === request && gate.ownsLookup(lookup) }) { outcome -> complete(request, outcome) }
        if (!launched && pending === request) complete(request, MyPassClient.Outcome.Unavailable)
    }

    private fun complete(request: Pending, outcome: MyPassClient.Outcome) {
        if (pending !== request) return
        pending = null
        onLookupEnded()
        val current = gate.current?.identity == request.lookup.identity
        if (outcome is MyPassClient.Outcome.Credential && enabled() && gate.supply(request.lookup)) {
            onCredential(request.lookup, outcome)
            request.answer.proceed(String(outcome.username), String(outcome.password))
            return
        }
        gate.abandon(request.lookup)
        request.answer.cancel()
        if (current) onError(R.string.shell_auth_unavailable)
    }

    /** Cancels an outstanding lookup whose navigation is no longer armed. */
    private fun settle() {
        val request = pending ?: return
        if (gate.isCurrent(request.lookup)) return
        pending = null
        request.client.cancel()
        request.answer.cancel()
        onLookupEnded()
    }

    /**
     * The activity, and with it the MyPass client, is going away. An outstanding lookup cannot
     * complete: its challenge is cancelled with the non-sensitive error. The armed navigation is
     * kept, so a later challenge can use the recreated activity's client.
     */
    fun detach() {
        val request = pending ?: return
        pending = null
        gate.abandon(request.lookup)
        request.client.cancel()
        request.answer.cancel()
        onLookupEnded()
        onError(R.string.shell_auth_unavailable)
    }

    /** The WebView is released. */
    fun close() {
        gate.disarm()
        settle()
    }
}
