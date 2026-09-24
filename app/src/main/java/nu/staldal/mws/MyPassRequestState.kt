package nu.staldal.mws

import java.util.UUID

/** Contains only non-secret identities. A request can be consumed once. */
internal class MyPassRequestState {
    data class Identity(
        val flow: String,
        val navigation: String,
        val document: String? = null,
    )

    data class Pending(val requestId: String, val identity: Identity)

    var pending: Pending? = null
        private set

    fun begin(identity: Identity): Pending? {
        if (pending != null) return null
        return Pending(UUID.randomUUID().toString(), identity).also { pending = it }
    }

    fun consume(requestId: String, identity: Identity): Boolean {
        val current = pending ?: return false
        if (current.requestId != requestId || current.identity != identity) return false
        pending = null
        return true
    }

    fun cancel() {
        pending = null
    }
}
