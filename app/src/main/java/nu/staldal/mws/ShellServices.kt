package nu.staldal.mws

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val startupLock = Mutex()
    private var startup: StartupReport? = null

    /**
     * A§5.3: maintenance runs once per process, before the first profile attaches. A failed run
     * is retried by the next caller.
     */
    suspend fun awaitStartup(): StartupReport = startupLock.withLock {
        startup ?: profiles.startupMaintenance().also { startup = it }
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

        private fun create(context: Context): ShellServices {
            val repository = ConfigurationRepository(ConfigurationDatabase.shared(context))
            val compatibility = ProviderCompatibility(AndroidProviderSnapshotSource(context),
                ProviderSelfTestStore { null }, debugOverride = BuildConfig.DEBUG_PROVIDER_OVERRIDE)
            val icons = IconStore(context)
            val shells = LiveShellRegistry()
            val profiles = ProfileManager(repository, MaintenanceStore(context), compatibility,
                AndroidWebProfileBackend(), shells, object : AppNativeData {
                    override fun deleteIcons(appId: String) = icons.delete(appId)
                    override fun widgetsChanged() {} // Widgets are not implemented yet.
                })
            return ShellServices(repository, compatibility, shells, profiles, icons)
        }
    }
}
