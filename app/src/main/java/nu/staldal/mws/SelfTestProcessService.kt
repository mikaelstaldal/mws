package nu.staldal.mws

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.RemoteException
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.selects.select

/**
 * A loaded Chromium profile cannot be deleted in its process. All phases use the same private
 * WebView data directory, but this process dies between them. No app WebView enters this process.
 */
internal class SelfTestProcessService : Service() {
    private val profileRegistryWriteDelayMs = 12_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ready = false
    private var startupFailure = DeletionProbe.Outcome.INCOMPLETE

    override fun onCreate() {
        super.onCreate()
        ready = try {
            if (!WebViewFeature.isStartupFeatureSupported(
                    this, WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)) {
                startupFailure = DeletionProbe.Outcome.DELETE_FAILED
                false
            }
            else {
                ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(this, "mws-selftest"))
                true
            }
        } catch (_: Exception) { false }
    }

    override fun onBind(intent: Intent): IBinder {
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        scope.launch {
            val reply = try {
                if (!ready || receiver == null) result(startupFailure)
                else when (intent.action) {
                    ACTION_MARK -> mark()
                    ACTION_VERIFY -> verify(requireNotNull(intent.getStringExtra(EXTRA_PROFILE_ID)))
                    ACTION_DELETE -> delete(requireNotNull(intent.getStringExtra(EXTRA_PROFILE_ID)))
                    ACTION_CLEANUP -> cleanup(requireNotNull(intent.getStringExtra(EXTRA_PROFILE_ID)))
                    else -> result(DeletionProbe.Outcome.INCOMPLETE)
                }
            } catch (_: Exception) {
                result(DeletionProbe.Outcome.INCOMPLETE)
            }
            receiver?.send(0, reply)
        }
        return Binder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
        return false
    }

    private fun manager(): ProfileManager {
        val maintenance = MaintenanceStore(this)
        return ProfileManager(
            ConfigurationRepository(ConfigurationDatabase.shared(this)), maintenance,
            ProviderCompatibility({ ProviderSnapshot(null, false) }, { null }),
            AndroidWebProfileBackend(), LiveShellRegistry(), object : AppNativeData {
                override fun deleteIcons(appId: String) = Unit
                override fun widgetsChanged() = Unit
            })
    }

    private suspend fun mark(): Bundle {
        val profiles = manager()
        val stale = profiles.cleanupStaleSelfTestProfiles()
        val profile = profiles.createSelfTestProfile(processProbe = true)
        val page = SyntheticProbePage()
        val bound = profiles.createSelfTestWebView(this, profile)
        val webView = bound.webView
        page.install(webView)
        page.load(webView)
        page.markCookie(webView)
        val outcome = DeletionProbe.Outcome.PASSED
        profiles.release(bound)
        // Chromium batches writes to the profile registry. Killing this process before its
        // scheduled write can leave a cookie directory without a deletable profile name.
        delay(profileRegistryWriteDelayMs)
        profiles.finishStaleSelfTestProfiles(stale)
        return result(outcome, profile.profileId)
    }

    private suspend fun delete(profileId: String): Bundle {
        val profiles = manager()
        val profile = profiles.adoptSelfTestProfile(profileId)
        val outcome = when (profiles.deleteSelfTestProfile(profile)) {
            SelfTestDeletion.Deleted -> {
                val page = SyntheticProbePage()
                val bound = profiles.createSelfTestWebView(this, profile)
                val webView = bound.webView
                page.install(webView)
                page.load(webView)
                val recreatedWithoutMarker = !page.hasCookie(webView)
                profiles.release(bound)
                // The recreated profile must also reach the registry before the cleanup process.
                delay(profileRegistryWriteDelayMs)
                if (recreatedWithoutMarker) DeletionProbe.Outcome.PASSED
                else DeletionProbe.Outcome.MARKER_SURVIVED
            }
            SelfTestDeletion.Absent -> DeletionProbe.Outcome.DELETE_ABSENT
            SelfTestDeletion.Deferred -> DeletionProbe.Outcome.DELETE_DEFERRED
            is SelfTestDeletion.Failed -> DeletionProbe.Outcome.DELETE_FAILED
        }
        return result(outcome)
    }

    private suspend fun cleanup(profileId: String): Bundle {
        val profiles = manager()
        val profile = profiles.adoptSelfTestProfile(profileId)
        val outcome = when (profiles.deleteSelfTestProfile(profile)) {
            SelfTestDeletion.Deleted -> DeletionProbe.Outcome.PASSED
            SelfTestDeletion.Absent -> DeletionProbe.Outcome.CLEANUP_ABSENT
            SelfTestDeletion.Deferred -> DeletionProbe.Outcome.CLEANUP_DEFERRED
            is SelfTestDeletion.Failed -> DeletionProbe.Outcome.CLEANUP_FAILED
        }
        if (outcome == DeletionProbe.Outcome.PASSED) {
            delay(profileRegistryWriteDelayMs)
            profiles.finishSelfTestProfile(profile)
        }
        return result(outcome)
    }

    private suspend fun verify(profileId: String): Bundle {
        val profiles = manager()
        val profile = profiles.adoptSelfTestProfile(profileId)
        if (!profiles.selfTestProfileExists(profile)) {
            return result(DeletionProbe.Outcome.PROFILE_NOT_PERSISTED)
        }
        val page = SyntheticProbePage()
        val webView = profiles.createSelfTestWebView(this, profile).webView
        page.install(webView)
        page.load(webView)
        return result(if (page.hasCookie(webView)) DeletionProbe.Outcome.PASSED
            else DeletionProbe.Outcome.MARKER_NOT_PERSISTED)
    }

    private fun result(outcome: DeletionProbe.Outcome, profileId: String? = null) = Bundle().apply {
        putString(EXTRA_OUTCOME, outcome.name)
        if (profileId != null) putString(EXTRA_PROFILE_ID, profileId)
    }

    companion object {
        private const val ACTION_MARK = "nu.staldal.mws.SELFTEST_MARK"
        private const val ACTION_VERIFY = "nu.staldal.mws.SELFTEST_VERIFY"
        private const val ACTION_DELETE = "nu.staldal.mws.SELFTEST_DELETE"
        private const val ACTION_CLEANUP = "nu.staldal.mws.SELFTEST_CLEANUP"
        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_OUTCOME = "outcome"

        suspend fun probe(context: Context): DeletionProbe.Outcome {
            val marked = phase(context, ACTION_MARK)
            val first = outcome(marked)
            val id = marked.getString(EXTRA_PROFILE_ID) ?: return first
            val observed = if (first != DeletionProbe.Outcome.PASSED) first else {
                val verified = outcome(phase(context, ACTION_VERIFY, id))
                if (verified != DeletionProbe.Outcome.PASSED) verified
                else outcome(phase(context, ACTION_DELETE, id))
            }
            val cleaned = outcome(phase(context, ACTION_CLEANUP, id))
            return if (cleaned == DeletionProbe.Outcome.PASSED) observed else cleaned
        }

        private fun outcome(bundle: Bundle): DeletionProbe.Outcome =
            bundle.getString(EXTRA_OUTCOME)?.let(DeletionProbe.Outcome::valueOf)
                ?: DeletionProbe.Outcome.INCOMPLETE

        private suspend fun phase(context: Context, action: String, profileId: String? = null): Bundle =
            withTimeout(60_000) {
                val reply = CompletableDeferred<Bundle>()
                val death = CompletableDeferred<Unit>()
                val connected = CompletableDeferred<Unit>()
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (resultData != null) reply.complete(resultData)
                    }
                }
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        try {
                            binder.linkToDeath({ death.complete(Unit) }, 0)
                            connected.complete(Unit)
                        } catch (_: RemoteException) {
                            death.complete(Unit)
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) { death.complete(Unit) }
                    override fun onBindingDied(name: ComponentName) { death.complete(Unit) }
                    override fun onNullBinding(name: ComponentName) { death.complete(Unit) }
                }
                val intent = Intent(context, SelfTestProcessService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_RECEIVER, receiver)
                    if (profileId != null) putExtra(EXTRA_PROFILE_ID, profileId)
                }
                check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
                var bound = true
                try {
                    val result = select<Bundle> {
                        reply.onAwait { it }
                        death.onAwait { error("Probe process ended before replying") }
                    }
                    check(connected.isCompleted) { "Probe process replied before binding" }
                    context.unbindService(connection)
                    bound = false
                    death.await()
                    result
                } finally {
                    if (bound) context.unbindService(connection)
                }
            }
    }
}
