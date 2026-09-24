package nu.staldal.mws

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

/** Create in Activity.onCreate, then restore pending result keys before the activity starts. */
class MyPassClient(
    private val activity: ComponentActivity,
    private val availabilityProbe: (Intent) -> Boolean = { intent ->
        activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            intent.resolveActivity(activity.packageManager) != null
    },
    private val start: (ActivityResultLauncher<Intent>, Intent) -> Unit = { launcher, intent ->
        launcher.launch(intent)
    },
    private val timeoutMs: Long = TIMEOUT_MS,
    private val registry: ActivityResultRegistry = activity.activityResultRegistry,
) {
    enum class Availability { AVAILABLE, UNAVAILABLE }

    sealed interface Outcome {
        data class Credential(val username: CharArray, val password: CharArray) : Outcome {
            fun clear() {
                username.fill('\u0000')
                password.fill('\u0000')
            }
        }
        data object Unavailable : Outcome
        data object NoCredential : Outcome
    }

    data class Identity(val flow: String, val navigation: String, val document: String? = null)

    private data class Request(
        val identity: Identity,
        val requestId: String,
        val isCurrent: (Identity) -> Boolean,
        val callback: (Outcome) -> Unit,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val state = MyPassRequestState()
    private var request: Request? = null
    private var timeout: Runnable? = null
    private val awaitingResults = mutableSetOf<String>()
    private val launchers = mutableMapOf<String, ActivityResultLauncher<Intent>>()

    fun availability(): Availability = try {
        if (availabilityProbe(requestIntent("https://example.invalid/"))) {
            Availability.AVAILABLE
        } else Availability.UNAVAILABLE
    } catch (_: SecurityException) {
        Availability.UNAVAILABLE
    }

    /**
     * URL must already have passed the caller's secure-context policy. Returns true only
     * when MyPass was launched. A client belongs to one flow; cancel when that flow ends.
     */
    fun fetch(
        identity: Identity,
        url: String,
        realm: String? = null,
        isCurrent: (Identity) -> Boolean,
        callback: (Outcome) -> Unit,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (request != null) return false
        // The client also enforces the minimum transport rule independently of its caller.
        if (!isCurrent(identity) || !url.startsWith("https://", ignoreCase = true)) {
            if (isCurrent(identity)) callback(Outcome.Unavailable)
            return false
        }
        val intent = requestIntent(url, realm)
        if (availability() != Availability.AVAILABLE) {
            callback(Outcome.Unavailable)
            return false
        }
        val pending = state.begin(identity.toStateIdentity()) ?: return false
        request = Request(identity, pending.requestId, isCurrent, callback)
        register(pending.requestId)
        val timer = Runnable { finish(Outcome.NoCredential) }
        timeout = timer
        mainHandler.postDelayed(timer, timeoutMs)
        try {
            start(launchers.getValue(pending.requestId), intent)
        } catch (_: ActivityNotFoundException) {
            finish(Outcome.Unavailable)
            discard(pending.requestId)
            return false
        } catch (_: SecurityException) {
            finish(Outcome.Unavailable)
            discard(pending.requestId)
            return false
        }
        return true
    }

    /** Call on navigation or flow completion. The caller owns its timer on cancellation. */
    fun cancel() {
        clearRequest()
    }

    /** Call in onDestroy. Activity results after recreation are discarded. */
    fun close() {
        clearRequest()
        launchers.values.forEach { it.unregister() }
        launchers.clear()
        awaitingResults.clear()
    }

    /** Only identities are saved. Never write a URL, result, or credential here. */
    fun saveIdentity(outState: Bundle) {
        outState.putStringArrayList(STATE_LAUNCH_IDS, ArrayList(awaitingResults))
        state.pending?.let {
            outState.putString(STATE_REQUEST_ID, it.requestId)
            outState.putString(STATE_FLOW, it.identity.flow)
            outState.putString(STATE_NAVIGATION, it.identity.navigation)
            outState.putString(STATE_DOCUMENT, it.identity.document)
        }
    }

    /** Re-register old keys to consume late results without retaining their Intent in saved state. */
    fun restoreIdentity(savedState: Bundle?) {
        savedState?.getStringArrayList(STATE_LAUNCH_IDS)?.forEach(::register)
    }

    private fun register(requestId: String) {
        if (!awaitingResults.add(requestId)) return
        val launcher = registry.register(
            "mypass.$requestId",
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> onResult(requestId, result) }
        // A restored pending result can be delivered during register itself.
        if (requestId in awaitingResults) launchers[requestId] = launcher else launcher.unregister()
    }

    private fun onResult(requestId: String, result: ActivityResult) {
        val current = request
        if (current == null || current.requestId != requestId) {
            discard(requestId)
            return
        }
        if (!current.isCurrent(current.identity)) {
            clearRequest()
            discard(requestId)
            return
        }
        try {
            finish(parseResult(result))
        } finally {
            discard(requestId)
        }
    }

    private fun finish(outcome: Outcome) {
        val current = request ?: return
        if (!state.consume(current.requestId, current.identity.toStateIdentity())) return
        clearRequest()
        try {
            if (current.isCurrent(current.identity)) current.callback(outcome)
        } finally {
            (outcome as? Outcome.Credential)?.clear()
        }
    }

    private fun clearRequest() {
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
        request = null
        state.cancel()
    }

    private fun discard(requestId: String) {
        awaitingResults.remove(requestId)
        launchers.remove(requestId)?.unregister()
    }

    private fun Identity.toStateIdentity() =
        MyPassRequestState.Identity(flow, navigation, document)

    companion object {
        const val PACKAGE = "nu.staldal.mypass"
        const val PERMISSION = "nu.staldal.mypass.permission.FETCH_PASSWORD"
        const val ACTION = "nu.staldal.mypass.action.FETCH_PASSWORD"
        const val EXTRA_URL = "nu.staldal.mypass.extra.URL"
        const val EXTRA_REALM = "nu.staldal.mypass.extra.REALM"
        const val EXTRA_RESULT_USERNAME = "nu.staldal.mypass.extra.RESULT_USERNAME"
        const val EXTRA_RESULT_PASSWORD = "nu.staldal.mypass.extra.RESULT_PASSWORD"
        const val TIMEOUT_MS = 60_000L
        private const val STATE_REQUEST_ID = "mypass.requestId"
        private const val STATE_FLOW = "mypass.flow"
        private const val STATE_NAVIGATION = "mypass.navigation"
        private const val STATE_DOCUMENT = "mypass.document"
        private const val STATE_LAUNCH_IDS = "mypass.launchIds"

        fun requestIntent(url: String, realm: String? = null): Intent =
            Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_URL, url).apply {
                if (realm != null) putExtra(EXTRA_REALM, realm)
            }

        fun parseResult(result: ActivityResult): Outcome {
            val data = result.data ?: return Outcome.NoCredential
            if (result.resultCode != Activity.RESULT_OK) return Outcome.NoCredential
            return try {
                if (!data.hasExtra(EXTRA_RESULT_USERNAME) || !data.hasExtra(EXTRA_RESULT_PASSWORD)) {
                    Outcome.NoCredential
                } else {
                    val username = data.getStringExtra(EXTRA_RESULT_USERNAME)
                    val password = data.getStringExtra(EXTRA_RESULT_PASSWORD)
                    if (username == null || password == null) Outcome.NoCredential
                    else Outcome.Credential(username.toCharArray(), password.toCharArray())
                }
            } catch (_: RuntimeException) {
                Outcome.NoCredential
            }
        }
    }
}
