package nu.staldal.mws

/** Attempt-owned credentials; native workers only receive a same-origin, same-realm copy. */
internal class DiscoveryBasicAuthentication(
    private val attempt: DiscoveryAttempt,
    client: () -> MyPassClient?,
    pause: () -> Unit,
    resume: () -> Unit,
    private val required: () -> Unit,
    private val enabled: () -> Boolean = { true },
    eligible: () -> Boolean = { true },
) : DiscoveryBasicAuth {
    private var retained: MyPassClient.Outcome.Credential? = null
    private var realm: String? = null
    private var closed = false
    private val auth = ShellBasicAuth(
        enabled = { enabled() && !closed && attempt.trigger != DiscoveryTrigger.DEBOUNCE },
        client = client, flow = "discovery-${attempt.id}", eligible = eligible,
        onLookup = pause, onLookupEnded = resume, onError = {
            synchronized(this) {
                retained?.clear()
                retained = null
                realm = null
            }
        },
        onCredential = { lookup, credential ->
            synchronized(this) {
                retained?.clear()
                retained = MyPassClient.Outcome.Credential(credential.username.copyOf(), credential.password.copyOf())
                realm = lookup.realm
            }
        },
    )

    fun arm() = auth.arm(attempt.configuredUrl.serialized)
    fun redirect(url: String) {
        if (UrlPolicy.parseHttps(url)?.origin != attempt.configuredUrl.origin) {
            synchronized(this) {
                retained?.clear()
                retained = null
                realm = null
            }
            auth.disarm()
        }
        else auth.redirect(url)
    }
    fun intercepted(url: String, mainFrame: Boolean = true) = auth.intercepted(url, mainFrame)
    fun started(url: String) = auth.started(url)
    fun documentEnded() = auth.disarm()
    fun challenge(answer: AuthAnswer, host: String?, realm: String?) {
        if (attempt.trigger == DiscoveryTrigger.DEBOUNCE) {
            val eligible = enabled() && auth.gate.challenge(host, realm) is BasicAuthGate.Challenge.Lookup
            auth.disarm()
            answer.cancel()
            if (eligible) required()
        } else auth.challenge(answer, host, realm)
    }
    @Synchronized override fun credential(url: String, realm: String): Pair<String, String>? {
        if (closed || this.realm != realm ||
            UrlPolicy.parseHttps(url)?.origin != attempt.configuredUrl.origin) return null
        return retained?.let { String(it.username) to String(it.password) }
    }
    fun close() {
        synchronized(this) {
            closed = true
            retained?.clear()
            retained = null
            realm = null
        }
        auth.close()
    }
}
