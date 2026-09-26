package nu.staldal.mws

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.util.Log

/**
 * Process-wide collaborators shared by the main screen and every shell. There is exactly one
 * [ProfileManager] and one [LiveShellRegistry] per process, so Clear and Remove reach every live
 * shell.
 */
class ShellServices(
    val repository: ConfigurationRepository,
    val compatibility: ProviderCompatibility,
    val shells: LiveShellRegistry,
    val profiles: ProfileManager,
    val icons: IconStore,
    private val selfTest: (suspend () -> Unit)? = null,
    private val reconcileWidgets: (suspend () -> Unit)? = null,
) {
    private val startupLock = Mutex()
    private var startup: StartupReport? = null
    private var startupCompatible = false
    private var selfTestAttempted = false
    private var selfTestKey: ProviderKey? = null

    /**
     * A§5.3: maintenance runs once per process, before the first profile attaches. A failed run
     * is retried by the next caller.
     */
    suspend fun awaitStartup(): StartupReport = startupLock.withLock {
        val currentKey = compatibility.current().key
        if (!selfTestAttempted || currentKey != selfTestKey) {
            selfTest?.invoke()
            selfTestKey = currentKey
            selfTestAttempted = true
        }
        val decision = compatibility.current()
        if (startup == null || (!startupCompatible && decision.compatible)) {
            val report = profiles.startupMaintenance()
            try { reconcileWidgets?.invoke() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("ShellServices", "Widget reconciliation failed", failure) }
            startup = report
            startupCompatible = decision.compatible
        }
        checkNotNull(startup)
    }

    companion object {
        @Volatile private var instance: ShellServices? = null

        fun shared(context: Context): ShellServices = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** Replaces the process graph; `null` restores the real one on next use. */
        @VisibleForTesting
        fun install(services: ShellServices?) {
            instance = services
        }

        private fun refreshWidgets(context: Context, reconcile: Boolean = false) {
            for (update in listOf<() -> Unit>(
                { if (reconcile) MultiWidget.reconcile(context) else MultiWidget.refresh(context) },
                { if (reconcile) SingleWidget.reconcile(context) else SingleWidget.refresh(context) })) {
                try { update() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Log.w("ShellServices", "Widget refresh failed", failure) }
            }
        }

        private fun create(context: Context): ShellServices {
            val repository = ConfigurationRepository(ConfigurationDatabase.shared(context))
            val maintenance = MaintenanceStore(context)
            val source = AndroidProviderSnapshotSource(context)
            val compatibility = ProviderCompatibility(source,
                ProviderSelfTestStore { key -> SelfTestEncoding.decode(key, maintenance.selfTest()) },
                debugOverride = BuildConfig.DEBUG_PROVIDER_OVERRIDE)
            val icons = IconStore(context)
            val shells = LiveShellRegistry()
            val profiles = ProfileManager(repository, maintenance, compatibility,
                AndroidWebProfileBackend(), shells, object : AppNativeData {
                    override fun deleteIcons(appId: String) = icons.delete(appId)
                    override fun widgetsChanged() {
                        try { refreshWidgets(context) }
                        catch (failure: Exception) { Log.w("ShellServices", "Widget refresh failed", failure) }
                    }
                })
            val runner = ProviderSelfTestRunner(source, maintenance, listOf(
                IsolationProbe(context, profiles), DeletionProbe(context)))
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                repository.apps.collect {
                    try { refreshWidgets(context) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // A later configuration change or provider update retries the refresh.
                    }
                }
            }
            return ShellServices(repository, compatibility, shells, profiles, icons,
                { runner.runIfNeeded() }, { withContext(Dispatchers.IO) { refreshWidgets(context, reconcile = true) } })
        }
    }
}
