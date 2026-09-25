package nu.staldal.mws

import android.content.Context
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Profile names contain only a fixed prefix, an opaque ID and a generation (A§5.1). */
object ProfileNames {
    private val OPAQUE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private const val APP_PREFIX = "mws-app-"
    private const val DISPOSABLE_PREFIX = "mws-disposable-"

    fun isOpaqueId(id: String): Boolean = OPAQUE_ID.matches(id)

    fun app(appId: String, generation: Long): String {
        require(OPAQUE_ID.matches(appId)) { "App ID is not opaque" }
        require(generation >= 0) { "Negative generation" }
        return "$APP_PREFIX$appId-$generation"
    }

    fun disposable(profileId: String): String {
        require(OPAQUE_ID.matches(profileId)) { "Profile ID is not opaque" }
        return "$DISPOSABLE_PREFIX$profileId"
    }
}

/**
 * The only boundary to provider profile state. Calls happen on the main thread, and never while
 * the provider is incompatible. Nothing here may touch the default profile.
 */
interface WebProfileBackend {
    /** `true` deleted, `false` absent. Throws [IllegalStateException] while loaded in this process. */
    fun deleteProfile(name: String): Boolean

    /** Checks the persisted profile registry without loading or creating the profile. */
    fun profileExists(name: String): Boolean

    /** Returns a WebView bound to [name] that has not loaded anything yet. */
    fun createBoundWebView(context: Context, name: String): WebView

    /** Applies to the WebView's own profile cookie manager, effective for subsequent requests. */
    fun setThirdPartyCookies(webView: WebView, allow: Boolean)
}

/** The single place where WebViews are constructed and bound (checked by scripts/check-security.py). */
class AndroidWebProfileBackend : WebProfileBackend {
    override fun deleteProfile(name: String): Boolean = ProfileStore.getInstance().deleteProfile(name)

    override fun profileExists(name: String): Boolean =
        name in ProfileStore.getInstance().allProfileNames

    override fun createBoundWebView(context: Context, name: String): WebView {
        check(name != Profile.DEFAULT_PROFILE_NAME)
        val webView = WebView(context)
        try {
            WebViewCompat.setProfile(webView, name)
            check(WebViewCompat.getProfile(webView).name == name) { "Profile binding failed" }
        } catch (failure: Throwable) {
            webView.destroy()
            throw failure
        }
        return webView
    }

    override fun setThirdPartyCookies(webView: WebView, allow: Boolean) {
        val profile = WebViewCompat.getProfile(webView)
        check(profile.name != Profile.DEFAULT_PROFILE_NAME)
        profile.cookieManager.setAcceptThirdPartyCookies(webView, allow)
    }
}

/**
 * Clear/remove step 2 hook, implemented by the shell host: finish the app's shell activity and
 * task and destroy its WebViews before returning. Called on the main thread.
 */
fun interface LiveShellCloser {
    suspend fun closeAll(appId: String)
}

/** Shells register while live, so profile retirement can close them first (A§9.1). */
class LiveShellRegistry : LiveShellCloser {
    private val closers = mutableMapOf<String, MutableList<suspend () -> Unit>>()

    /** Returns an unregister action. Main thread only. */
    fun register(appId: String, close: suspend () -> Unit): () -> Unit {
        closers.getOrPut(appId) { mutableListOf() } += close
        return { closers[appId]?.remove(close) }
    }

    /** Every closer runs; failed ones stay registered for Retry, and the first failure is rethrown. */
    override suspend fun closeAll(appId: String) {
        val pending = closers.remove(appId)?.toList() ?: return
        var failure: Exception? = null
        for (close in pending) {
            try {
                close()
            } catch (e: Exception) {
                closers.getOrPut(appId) { mutableListOf() } += close
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}

/** Native, non-profile data removed with an app. Steps must tolerate replay. */
interface AppNativeData {
    fun deleteIcons(appId: String)
    fun widgetsChanged()
}

/** A WebView bound to [profileName]. Release it through [ProfileManager.release]. */
class BoundWebView internal constructor(val webView: WebView, val profileName: String)

/** A never-reused discovery or self-test profile, durably registered before first use. */
data class DisposableProfile(val profileId: String) {
    val profileName: String get() = ProfileNames.disposable(profileId)
}

enum class AttachBlock { STARTUP_PENDING, INCOMPATIBLE, MISSING_APP, CLEARING, NOT_REGISTERED,
    /** A journal operation for the app, such as an origin change, has not completed. */
    PENDING_OPERATION }

sealed interface AttachResult {
    data class Attached(val bound: BoundWebView) : AttachResult
    data class Blocked(val reason: AttachBlock) : AttachResult
}

sealed interface ClearResult {
    data object Cleared : ClearResult
    /** The old name is retired; its data is deleted at next process start. */
    data object PendingRestart : ClearResult
    /** Still CLEARING with launches blocked; offer Retry. */
    data class Failed(val cause: Throwable) : ClearResult
    data object Incompatible : ClearResult
    data object MissingApp : ClearResult
}

sealed interface RemovalResult {
    data object Removed : RemovalResult
    /** Configuration is gone; profile data stays tombstoned until a later start deletes it. */
    data object ProfileDeletionPending : RemovalResult
    /** Native deletion stays in the journal and resumes at next start. */
    data class NativeDeletionPending(val cause: Throwable) : RemovalResult
    data object MissingApp : RemovalResult
}

sealed interface DeletionOutcome {
    /** `deleteProfile` returned `true` (deleted) or `false` (absent): the name is retired. */
    data object Deleted : DeletionOutcome
    /** The profile is still loaded in this process; the next start retries. */
    data object Deferred : DeletionOutcome
    data class Failed(val cause: Throwable) : DeletionOutcome
}

/** The probe must distinguish accepted deletion from a profile that never existed. */
internal sealed interface SelfTestDeletion {
    data object Deleted : SelfTestDeletion
    data object Absent : SelfTestDeletion
    data object Deferred : SelfTestDeletion
    data class Failed(val cause: Throwable) : SelfTestDeletion
}

data class StartupReport(
    val journalFailures: Map<String, Throwable>,
    val pendingTombstones: List<String>,
    val pendingDisposableProfiles: List<String>,
    val clearingFailures: Map<String, Throwable>,
)

/**
 * Owns profile naming, generation lifecycle and every `ProfileStore` call (A§5). Shell,
 * discovery and self-test WebViews come only from here, bound before their first load.
 */
class ProfileManager(
    private val repository: ConfigurationRepository,
    private val maintenance: MaintenanceStore,
    private val compatibility: ProviderCompatibility,
    private val backend: WebProfileBackend,
    private val shells: LiveShellCloser,
    private val nativeData: AppNativeData,
    extraPlans: Map<OperationType, List<(OperationIntent) -> Unit>> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val journal = OperationJournal(maintenance, extraPlans + mapOf(
        OperationType.DELETE_PERMISSION_DECISIONS to listOf({ intent ->
            maintenance.deletePermissionDecisions(requireNotNull(intent.appId),
                requireNotNull(intent.oldGeneration))
        }),
        OperationType.REMOVE_APP to listOf(
            { intent ->
                val result = runBlocking { repository.remove(requireNotNull(intent.appId)) }
                if (result is ConfigurationWriteResult.StorageFailure) throw result.cause
            },
            { intent -> nativeData.deleteIcons(requireNotNull(intent.appId)) },
            { intent -> maintenance.deleteAllPermissionDecisions(requireNotNull(intent.appId)) },
            { intent ->
                maintenance.pruneWidgetSelection(requireNotNull(intent.appId))
                nativeData.widgetsChanged()
            },
        ),
    ))

    /** Serializes attachment against generation transitions and tombstoning. */
    private val lifecycle = Mutex()

    /** Main thread only. */
    private val live = mutableMapOf<String, MutableList<WebView>>()

    @Volatile private var startupComplete = false

    /** Disposable profiles registered by this process and not yet retired. Guarded by [lifecycle]. */
    private val openDisposables = mutableSetOf<String>()

    /** Self-test is the sole pre-compatibility profile access path. The caller first checks
     * MULTI_PROFILE; names are durable so a crash can be cleaned up on a later compatible start. */
    suspend fun createSelfTestProfile(processProbe: Boolean = false): DisposableProfile = lifecycle.withLock {
        val id = if (processProbe) "selftest-${UUID.randomUUID()}" else UUID.randomUUID().toString()
        val profile = DisposableProfile(id)
        withContext(io) { maintenance.registerDiscoveryProfile(DiscoveryProfile(profile.profileId, clock())) }
        openDisposables += profile.profileId
        profile
    }

    /** A fresh probe process adopts only a name that phase one registered durably. */
    suspend fun adoptSelfTestProfile(profileId: String): DisposableProfile = lifecycle.withLock {
        require(profileId.startsWith("selftest-") && ProfileNames.isOpaqueId(profileId))
        check(withContext(io) { maintenance.hasDiscoveryProfile(profileId) })
        openDisposables += profileId
        DisposableProfile(profileId)
    }

    /** Child-process leftovers are cleaned only in that process's WebView data directory. */
    suspend fun cleanupStaleSelfTestProfiles(): List<String> = lifecycle.withLock {
        val deleted = mutableListOf<String>()
        for (record in withContext(io) { maintenance.discoveryProfiles() }) {
            if (!record.profileId.startsWith("selftest-")) continue
            if (deleteNamed(ProfileNames.disposable(record.profileId)) == DeletionOutcome.Deleted) {
                deleted += record.profileId
            }
        }
        deleted
    }

    suspend fun finishStaleSelfTestProfiles(deleted: List<String>) = lifecycle.withLock {
        for (id in deleted) withContext(io) { maintenance.deleteDiscoveryProfile(id) }
    }

    suspend fun createSelfTestWebView(context: Context, profile: DisposableProfile): BoundWebView =
        lifecycle.withLock {
            check(profile.profileId in openDisposables)
            check(withContext(io) { maintenance.hasDiscoveryProfile(profile.profileId) })
            withContext(main) { bind(context, profile.profileName) }
        }

    suspend fun selfTestProfileExists(profile: DisposableProfile): Boolean = lifecycle.withLock {
        check(profile.profileId in openDisposables)
        withContext(main) { backend.profileExists(profile.profileName) }
    }

    /** Keep the registration until the probe has checked deletion and any recreation. */
    internal suspend fun deleteSelfTestProfile(profile: DisposableProfile): SelfTestDeletion = lifecycle.withLock {
        check(profile.profileId in openDisposables)
        withContext(main) { destroyLive(profile.profileName) }
        withContext(main) {
            try {
                if (backend.deleteProfile(profile.profileName)) SelfTestDeletion.Deleted
                else SelfTestDeletion.Absent
            } catch (_: IllegalStateException) {
                SelfTestDeletion.Deferred
            } catch (failure: Exception) {
                SelfTestDeletion.Failed(failure)
            }
        }
    }

    suspend fun finishSelfTestProfile(profile: DisposableProfile) {
        val outcome = deleteSelfTestProfile(profile)
        lifecycle.withLock { openDisposables -= profile.profileId }
        if (outcome == SelfTestDeletion.Deleted || outcome == SelfTestDeletion.Absent) {
            withContext(io) { maintenance.deleteDiscoveryProfile(profile.profileId) }
        }
    }

    /**
     * A§5.3: runs before any app profile is loaded. Journal intents resume
     * first, so a tombstone is never retried while its removal can still be adopted.
     */
    suspend fun startupMaintenance(): StartupReport = lifecycle.withLock {
        val resumed = withContext(io) { journal.resumeOnStartup() }
        val clearingFailures = mutableMapOf<String, Throwable>()
        if (compatibility.mayAccessProfile()) {
            val apps = repository.ordered()
            val liveNames = apps.map { ProfileNames.app(it.id, it.profileGeneration) }.toSet()
            for (tombstone in withContext(io) { maintenance.tombstones() }) {
                if (tombstone.profileName in liveNames) continue
                if (deleteNamed(tombstone.profileName) == DeletionOutcome.Deleted) {
                    withContext(io) { maintenance.deleteTombstone(tombstone.profileName) }
                }
            }
            for (record in withContext(io) { maintenance.discoveryProfiles() }) {
                if (record.profileId.startsWith("selftest-")) continue
                if (deleteNamed(ProfileNames.disposable(record.profileId)) == DeletionOutcome.Deleted) {
                    withContext(io) { maintenance.deleteDiscoveryProfile(record.profileId) }
                }
            }
            for (app in apps.filter { it.profileState == ProfileState.CLEARING }) {
                val result = finishRetiring(app)
                if (result is ClearResult.Failed) clearingFailures[app.id] = result.cause
            }
        }
        startupComplete = true
        withContext(io) {
            StartupReport(resumed.failures, maintenance.tombstones().map { it.profileName },
                maintenance.discoveryProfiles().map { it.profileId }, clearingFailures)
        }
    }

    /**
     * A§9.1: only an existing, ACTIVE, non-tombstoned app without an unresolved journal operation
     * attaches, and only its active generation.
     */
    suspend fun createAppWebView(context: Context, appId: String): AttachResult = lifecycle.withLock {
        if (!startupComplete) return@withLock AttachResult.Blocked(AttachBlock.STARTUP_PENDING)
        if (!compatibility.mayAccessProfile()) return@withLock AttachResult.Blocked(AttachBlock.INCOMPATIBLE)
        val app = repository.get(appId) ?: return@withLock AttachResult.Blocked(AttachBlock.MISSING_APP)
        if (app.profileState != ProfileState.ACTIVE) {
            return@withLock AttachResult.Blocked(AttachBlock.CLEARING)
        }
        val name = ProfileNames.app(app.id, app.profileGeneration)
        if (withContext(io) { maintenance.hasTombstone(name) }) {
            return@withLock AttachResult.Blocked(AttachBlock.MISSING_APP)
        }
        if (withContext(io) { maintenance.pendingOperations().any { it.intent.appId == appId } }) {
            return@withLock AttachResult.Blocked(AttachBlock.PENDING_OPERATION)
        }
        withContext(main) {
            val bound = bind(context, name)
            backend.setThirdPartyCookies(bound.webView, app.allowThirdPartyCookies)
            AttachResult.Attached(bound)
        }
    }

    /** Applies a changed third-party-cookie setting to a live WebView without reloading. Main thread only. */
    fun applyThirdPartyCookies(bound: BoundWebView, allow: Boolean) {
        backend.setThirdPartyCookies(bound.webView, allow)
    }

    /** Destroys a WebView obtained from this manager. Main thread only; repeated calls are harmless. */
    fun release(bound: BoundWebView) {
        if (live[bound.profileName]?.remove(bound.webView) == true) bound.webView.destroy()
    }

    /** Clear and Retry share one path: an existing reservation is resumed, never renewed. */
    suspend fun clear(appId: String): ClearResult {
        if (!compatibility.mayAccessProfile()) return ClearResult.Incompatible
        val app = lifecycle.withLock {
            when (val reservation = repository.reserveNextGeneration(appId)) {
                is GenerationReservation.Reserved -> reservation.app
                GenerationReservation.MissingApp -> null
                is GenerationReservation.StorageFailure -> return ClearResult.Failed(reservation.cause)
            }
        } ?: return ClearResult.MissingApp
        return finishRetiring(app)
    }

    private suspend fun finishRetiring(app: ConfiguredApp): ClearResult {
        val retiring = requireNotNull(app.retiringProfileGeneration)
        val name = ProfileNames.app(app.id, retiring)
        return try {
            closeLive(app.id, listOf(name))
            withContext(io) {
                val entry = journal.start(OperationIntent(OperationType.DELETE_PERMISSION_DECISIONS,
                    app.id, oldGeneration = retiring))
                journal.run(entry)
                maintenance.discardCompleted(entry.id)
            }
            if (!compatibility.mayAccessProfile()) return ClearResult.Incompatible
            val deferred = when (val outcome = deleteNamed(name)) {
                DeletionOutcome.Deleted -> false
                DeletionOutcome.Deferred -> true
                is DeletionOutcome.Failed -> return ClearResult.Failed(outcome.cause)
            }
            if (deferred) withContext(io) {
                maintenance.putTombstone(ProfileTombstone(name, retiring, clock()))
            }
            val finished = repository.finishClearing(app.id, retiring)
            if (finished is ConfigurationWriteResult.StorageFailure) return ClearResult.Failed(finished.cause)
            if (deferred) ClearResult.PendingRestart else ClearResult.Cleared
        } catch (failure: Exception) {
            ClearResult.Failed(failure)
        }
    }

    /**
     * A§5.2 removal: tombstones for every generation commit with the journal intent, then live
     * shells close, native data is deleted through the journal, and profile deletion is attempted
     * only while compatible.
     */
    suspend fun remove(appId: String): RemovalResult {
        val (entry, names) = lifecycle.withLock {
            val app = repository.get(appId) ?: return RemovalResult.MissingApp
            val generations = listOfNotNull(app.profileGeneration, app.retiringProfileGeneration)
            val now = clock()
            val tombstones = generations.map { ProfileTombstone(ProfileNames.app(app.id, it), it, now) }
            val entry = withContext(io) {
                journal.start(OperationIntent(OperationType.REMOVE_APP, app.id,
                    oldGeneration = app.retiringProfileGeneration, newGeneration = app.profileGeneration),
                    tombstones)
            }
            entry to tombstones.map { it.profileName }
        }
        try {
            closeLive(appId, names)
            withContext(io) {
                journal.run(entry)
                maintenance.discardCompleted(entry.id)
            }
        } catch (failure: Exception) {
            return RemovalResult.NativeDeletionPending(failure)
        }
        if (!compatibility.mayAccessProfile()) return RemovalResult.ProfileDeletionPending
        var pending = false
        for (name in names) {
            if (deleteNamed(name) == DeletionOutcome.Deleted) {
                withContext(io) { maintenance.deleteTombstone(name) }
            } else pending = true
        }
        return if (pending) RemovalResult.ProfileDeletionPending else RemovalResult.Removed
    }

    /** A§8.1: the record is durable before any WebView can create or attach the profile. */
    /**
     * Registration waits for startup maintenance, so startup cleanup never deletes a profile
     * registered by this process. Returns `null` before startup maintenance has run.
     */
    suspend fun createDisposableProfile(): DisposableProfile? = lifecycle.withLock {
        if (!startupComplete) return@withLock null
        val profile = DisposableProfile(UUID.randomUUID().toString())
        withContext(io) { maintenance.registerDiscoveryProfile(DiscoveryProfile(profile.profileId, clock())) }
        openDisposables += profile.profileId
        profile
    }

    /** Only a profile registered in this process and not yet retired attaches. */
    suspend fun createDisposableWebView(context: Context, profile: DisposableProfile): AttachResult =
        lifecycle.withLock {
            if (!startupComplete) return@withLock AttachResult.Blocked(AttachBlock.STARTUP_PENDING)
            if (!compatibility.mayAccessProfile()) {
                return@withLock AttachResult.Blocked(AttachBlock.INCOMPATIBLE)
            }
            if (profile.profileId !in openDisposables ||
                !withContext(io) { maintenance.hasDiscoveryProfile(profile.profileId) }) {
                return@withLock AttachResult.Blocked(AttachBlock.NOT_REGISTERED)
            }
            withContext(main) { AttachResult.Attached(bind(context, profile.profileName)) }
        }

    /**
     * Closes the profile's WebViews, then deletes it. The record is removed only after
     * `true`/`false`; otherwise the next start retries.
     */
    suspend fun retireDisposable(profile: DisposableProfile): DeletionOutcome {
        lifecycle.withLock { openDisposables -= profile.profileId }
        withContext(main) { destroyLive(profile.profileName) }
        if (!compatibility.mayAccessProfile()) {
            return DeletionOutcome.Failed(IllegalStateException("Provider incompatible"))
        }
        val outcome = deleteNamed(profile.profileName)
        if (outcome == DeletionOutcome.Deleted) {
            withContext(io) { maintenance.deleteDiscoveryProfile(profile.profileId) }
        }
        return outcome
    }

    private fun bind(context: Context, name: String): BoundWebView {
        val webView = backend.createBoundWebView(context, name)
        live.getOrPut(name) { mutableListOf() } += webView
        return BoundWebView(webView, name)
    }

    private suspend fun closeLive(appId: String, names: List<String>) = withContext(main) {
        shells.closeAll(appId)
        names.forEach(::destroyLive)
    }

    private fun destroyLive(name: String) {
        live.remove(name)?.forEach(WebView::destroy)
    }

    private suspend fun deleteNamed(name: String): DeletionOutcome = withContext(main) {
        try {
            backend.deleteProfile(name)
            DeletionOutcome.Deleted
        } catch (_: IllegalStateException) {
            DeletionOutcome.Deferred
        } catch (failure: Exception) {
            DeletionOutcome.Failed(failure)
        }
    }
}
