package nu.staldal.mws

import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: ConfigurationDatabase
    private lateinit var repository: ConfigurationRepository
    private lateinit var store: MaintenanceStore
    private val backend = FakeBackend()
    private var compatible = true
    private val closedShells = mutableListOf<String>()
    private val deletedIcons = mutableListOf<String>()
    private var widgetUpdates = 0
    private var failPrune = false
    private val prunedIcons = mutableListOf<Pair<String, String?>>()
    private var failIconReconciliation = false
    private var iconReconciliations = 0
    private var protectedIconApps = emptySet<String>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ConfigurationRepository(database)
        store = MaintenanceStore(context)
        store.createInstallationSentinel()
    }

    @After fun tearDown() {
        database.close()
        store.close()
    }

    /** A new instance models a fresh process: nothing in memory survives, persisted state does. */
    private fun manager(extraPlans: Map<OperationType, List<(OperationIntent) -> Unit>> = emptyMap()) = ProfileManager(repository, store,
        ProviderCompatibility(
            { ProviderSnapshot(PROVIDER, compatible) },
            { key -> ProviderSelfTestResult(key, true, true, true, true, true, true) },
            floors = mapOf(PROVIDER.packageName to listOf(1)),
        ),
        backend, { closedShells += it },
        object : AppNativeData {
            override fun deleteIcons(appId: String) { deletedIcons += appId }
            override fun widgetsChanged() { widgetUpdates++ }
            override fun pruneIcons(appId: String, retainedReference: String?) {
                if (failPrune) error("Icon storage unavailable")
                prunedIcons += appId to retainedReference
            }
            override fun reconcileIcons(apps: List<ConfiguredApp>, pending: List<JournalEntry>) {
                iconReconciliations++
                protectedIconApps = pending.mapNotNull { it.intent.appId }.toSet()
                if (failIconReconciliation) error("Unable to prune icons")
            }
        },
        extraPlans = extraPlans, clock = { 1000L }, main = Dispatchers.Unconfined, io = Dispatchers.Unconfined)

    private fun newApp(generation: Long = 0): ConfiguredApp = runBlocking {
        val id = UUID.randomUUID().toString()
        val app = ConfiguredApp(id, "App", NameSource.DISCOVERED, "https://example.com/",
            null, "https://example.com/", "https://example.com/", profileGeneration = generation,
            createdAt = 1, updatedAt = 1)
        assertEquals(ConfigurationWriteResult.Success, repository.add(app))
        app
    }

    private fun started(): ProfileManager = manager().also { runBlocking { it.startupMaintenance() } }

    private fun attach(manager: ProfileManager, id: String) = runBlocking {
        manager.createAppWebView(context, id)
    }

    @Test fun originChangeReplaysEveryReservationCrashBoundary() = runBlocking {
        for (boundary in 0..4) {
            val old = newApp(generation = 4)
            val snapshot = old.copy(configuredUrl = "https://new.example/path",
                launchUrl = "https://new.example/path", scope = "https://new.example/",
                iconReference = null, updatedAt = 20)
            val entry = store.begin(OperationIntent(OperationType.UPDATE_URL, old.id,
                oldGeneration = 4, newGeneration = 5,
                resourceId = OriginChangeSnapshot.encode(snapshot)), 2)
            if (boundary >= 1) repository.applyOriginSnapshot(snapshot, 4, 5)
            if (boundary >= 2) store.completeStep(entry.id, 0)
            if (boundary >= 3) store.completeStep(entry.id, 1)
            if (boundary == 4) store.discardCompleted(entry.id)
            compatible = false
            val fresh = manager()
            fresh.startupMaintenance()
            val current = repository.get(old.id)!!
            assertEquals(snapshot.configuredUrl, current.configuredUrl)
            assertEquals(5L, current.profileGeneration)
            assertEquals(4L, current.retiringProfileGeneration)
            assertEquals(ProfileState.CLEARING, current.profileState)
            compatible = true
            // Neither generation can attach while cleanup is outstanding.
            assertEquals(AttachResult.Blocked(AttachBlock.CLEARING), attach(fresh, old.id))
            fresh.startupMaintenance()
            assertEquals(ProfileState.ACTIVE, repository.get(old.id)!!.profileState)
            assertEquals(5L, repository.get(old.id)!!.profileGeneration)
            assertTrue(ProfileNames.app(old.id, 4) in backend.deleteCalls)
            assertFalse(ProfileNames.app(old.id, 5) in backend.deleteCalls)
        }
    }

    @Test fun replayAfterCleanupDoesNotReserveAnotherGenerationOrRestoreOldMetadata() = runBlocking {
        val old = newApp(generation = 4)
        val snapshot = old.copy(configuredUrl = "https://new.example/",
            launchUrl = "https://new.example/", scope = "https://new.example/")
        val entry = store.begin(OperationIntent(OperationType.UPDATE_URL, old.id,
            oldGeneration = 4, newGeneration = 5,
            resourceId = OriginChangeSnapshot.encode(snapshot)), 2)
        repository.applyOriginSnapshot(snapshot, 4, 5)
        repository.finishClearing(old.id, 4)
        repository.update(repository.get(old.id)!!.copy(name = "Later edit"))
        manager().startupMaintenance()
        assertEquals(5L, repository.get(old.id)!!.profileGeneration)
        assertEquals("Later edit", repository.get(old.id)!!.name)
        assertEquals(ProfileState.ACTIVE, repository.get(old.id)!!.profileState)
        assertTrue(store.completedOperations().none { it.id == entry.id })
    }

    @Test fun failedOriginJournalRetriesInProcessWithoutExtraGeneration() = runBlocking {
        val old = newApp(generation = 4)
        val profiles = started()
        failPrune = true
        val snapshot = old.copy(configuredUrl = "https://new.example/",
            launchUrl = "https://new.example/", scope = "https://new.example/",
            iconReference = "new-icon")
        assertTrue(profiles.changeOrigin(snapshot) is ConfigurationWriteResult.PendingOriginChange)
        assertEquals(ProfileState.CLEARING, repository.get(old.id)!!.profileState)
        assertEquals(ConfigurationWriteResult.InvalidOrder, profiles.changeOrigin(snapshot))
        assertTrue(profiles.clear(old.id) is ClearResult.Failed)
        assertEquals(5L, repository.get(old.id)!!.profileGeneration)
        failPrune = false
        assertEquals(ClearResult.Cleared, profiles.clear(old.id))
        assertEquals(snapshot.configuredUrl, repository.get(old.id)!!.configuredUrl)
        assertEquals(5L, repository.get(old.id)!!.profileGeneration)
        assertTrue(store.pendingOperations().none { it.intent.appId == old.id })
        assertEquals(listOf(old.id to "new-icon"), prunedIcons)
    }

    @Test fun clearAdoptsPendingIntentBeforeReservingAnyGeneration() = runBlocking {
        val old = newApp(generation = 4)
        val profiles = started()
        val snapshot = old.copy(configuredUrl = "https://new.example/",
            launchUrl = "https://new.example/", scope = "https://new.example/")
        store.begin(OperationIntent(OperationType.UPDATE_URL, old.id, 4, 5,
            OriginChangeSnapshot.encode(snapshot)), 2)
        assertEquals(ClearResult.Cleared, profiles.clear(old.id))
        assertEquals(snapshot.configuredUrl, repository.get(old.id)!!.configuredUrl)
        assertEquals(5L, repository.get(old.id)!!.profileGeneration)
        assertTrue(store.pendingOperations().isEmpty())
    }

    @Test fun missingOriginSaveDoesNotRecordIntent() = runBlocking {
        val old = newApp()
        repository.remove(old.id)
        assertEquals(ConfigurationWriteResult.MissingApp, manager().changeOrigin(old))
        assertTrue(store.pendingOperations().isEmpty())
    }

    @Test fun originSnapshotRoundTripsNullableMetadataAndConsent() {
        val base = ConfiguredApp("app", "Name", NameSource.USER_EDITED,
            "https://new.example/?token=secret", null, "https://new.example/?token=secret",
            "https://new.example/", createdAt = 1, updatedAt = 2)
        assertEquals(base, OriginChangeSnapshot.decode(OriginChangeSnapshot.encode(base)))
        val complete = base.copy(finalUrl = "https://new.example/final", themeColor = 12,
            backgroundColor = 34, manifestSnapshot = "{}", iconReference = "app/icon",
            allowThirdPartyCookies = true, sensitiveUrlConsent =
                SensitiveUrlConsent.parse(null).grant(base.configuredUrl).serialize())
        assertEquals(complete, OriginChangeSnapshot.decode(OriginChangeSnapshot.encode(complete)))
    }

    @Test fun originSaveClosesShellAndCommitsEvenWithIncompatibleProvider() = runBlocking {
        val old = newApp()
        val profiles = started()
        compatible = false
        val snapshot = old.copy(configuredUrl = "https://new.example/",
            launchUrl = "https://new.example/", scope = "https://new.example/")
        assertEquals(ConfigurationWriteResult.Success, profiles.changeOrigin(snapshot))
        assertEquals(snapshot.configuredUrl, repository.get(old.id)!!.configuredUrl)
        assertEquals(ProfileState.CLEARING, repository.get(old.id)!!.profileState)
        assertTrue(old.id in closedShells)
        assertTrue(backend.deleteCalls.isEmpty())
    }

    @Test fun missingSentinelNormalizesRestoreWithoutDeletingProfiles() = runBlocking {
        val app = newApp(generation = 4)
        database.apps().update(app.copy(profileState = ProfileState.CLEARING,
            retiringProfileGeneration = 8, iconReference = "missing", sortPosition = 17))
        store.close()
        java.io.File(context.noBackupFilesDir, "maintenance.db").delete()
        store = MaintenanceStore(context)
        assertFalse(store.hasInstallationSentinel())
        manager().startupMaintenance()
        val restored = repository.get(app.id)!!
        assertEquals(9L, restored.profileGeneration)
        assertEquals(ProfileState.ACTIVE, restored.profileState)
        assertNull(restored.retiringProfileGeneration)
        assertNull(restored.iconReference)
        assertEquals(0, restored.sortPosition)
        assertTrue(store.hasInstallationSentinel())
        assertTrue(backend.deleteCalls.isEmpty())
        manager().startupMaintenance()
        assertEquals(9L, repository.get(app.id)!!.profileGeneration)
    }

    @Test fun startupResumesRestoreAfterConfigurationCommitAndBeforeSentinel() = runBlocking {
        val first = newApp(generation = 4)
        val second = newApp(generation = 2)
        val third = newApp(generation = 10) // Crash before its restore intent was recorded.
        store.close()
        check(java.io.File(context.noBackupFilesDir, "maintenance.db").delete())
        store = MaintenanceStore(context)
        val completed = store.begin(OperationIntent(OperationType.RESTORE, first.id,
            newGeneration = 5), 1)
        repository.normalizeRestored(first.id, 5)
        store.completeStep(completed.id, 0)
        store.begin(OperationIntent(OperationType.RESTORE, second.id, newGeneration = 3), 1)
        repository.normalizeRestored(second.id, 3) // Cursor still pending after a crash.
        manager().startupMaintenance()
        assertEquals(5L, repository.get(first.id)!!.profileGeneration)
        assertEquals(3L, repository.get(second.id)!!.profileGeneration)
        assertEquals(11L, repository.get(third.id)!!.profileGeneration)
        assertTrue(store.hasInstallationSentinel())
        assertTrue(store.pendingOperations().isEmpty())
        assertTrue(store.completedOperations().isEmpty())
        assertTrue(backend.deleteCalls.isEmpty())
    }

    @Test fun failedRestoreBlocksExposureAndResumesOnRetry() = runBlocking {
        val app = newApp(generation = 4)
        store.close()
        check(java.io.File(context.noBackupFilesDir, "maintenance.db").delete())
        store = MaintenanceStore(context)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_restore
            BEFORE UPDATE ON configured_apps BEGIN SELECT RAISE(FAIL, 'disk failure'); END""")
        val manager = manager()
        assertThrows(IllegalStateException::class.java) { runBlocking { manager.prepareConfiguration() } }
        assertFalse(store.hasInstallationSentinel())
        assertEquals(AttachResult.Blocked(AttachBlock.STARTUP_PENDING), attach(manager, app.id))
        assertEquals(4L, repository.get(app.id)!!.profileGeneration)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        manager.startupMaintenance()
        assertTrue(store.hasInstallationSentinel())
        assertEquals(5L, repository.get(app.id)!!.profileGeneration)
    }

    @Test fun pendingJournalsProtectPermissionWidgetAndIconResources() = runBlocking {
        store.begin(OperationIntent(OperationType.UPDATE_ICON, "pending", oldGeneration = 7,
            resourceId = "pending/icon"), 1) // Missing plan remains pending.
        store.putPermissionDecision(PermissionDecision("pending", 7, "https://example.com",
            PermissionCapability.GEOLOCATION, SiteDecision.SITE_ALLOW))
        store.putWidget(WidgetRecord(1, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
            setOf("pending", "orphan")))
        manager().prepareConfiguration()
        assertNotNull(store.permissionDecision("pending", 7, "https://example.com",
            PermissionCapability.GEOLOCATION))
        assertEquals(setOf("pending"), store.widget(1)!!.selectedAppIds)
        assertEquals(setOf("pending"), protectedIconApps)
    }

    @Test fun orphanCleanupFailureDoesNotBlockAttachmentAndDoesNotRepeatInProcess() = runBlocking {
        val app = newApp()
        failIconReconciliation = true
        val manager = manager()
        manager.startupMaintenance()
        assertTrue(attach(manager, app.id) is AttachResult.Attached)
        manager.startupMaintenance()
        assertEquals(1, iconReconciliations)
    }

    @Test fun startupRetryResumesFailedJournalsWithoutRepeatingPruning() = runBlocking {
        var failing = true
        val entry = store.begin(OperationIntent(OperationType.UPDATE_ICON, "pending"), 1)
        val manager = manager(mapOf(OperationType.UPDATE_ICON to listOf({
            if (failing) error("Transient storage failure")
        })))
        assertTrue(entry.id in manager.startupMaintenance().journalFailures)
        failing = false
        assertTrue(manager.startupMaintenance().journalFailures.isEmpty())
        assertEquals(1, store.operation(entry.id).completedSteps)
        assertEquals(1, iconReconciliations)
    }

    @Test fun profileNamesAreOpaqueAndGenerationScoped() {
        val id = UUID.randomUUID().toString()
        assertEquals("mws-app-$id-3", ProfileNames.app(id, 3))
        assertNotEquals(ProfileNames.app(id, 3), ProfileNames.app(id, 4))
        assertEquals("mws-disposable-$id", ProfileNames.disposable(id))
        for (bad in listOf("https://example.com", "a/b", "", "a b", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { ProfileNames.app(bad, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProfileNames.app(id, -1) }
    }

    @Test fun reservationIsAtomicAndIdempotent() = runBlocking {
        val app = newApp(generation = 4)
        val reserved = (repository.reserveNextGeneration(app.id) as GenerationReservation.Reserved).app
        assertEquals(ProfileState.CLEARING, reserved.profileState)
        assertEquals(4L, reserved.retiringProfileGeneration)
        assertEquals(5L, reserved.profileGeneration)
        assertEquals(reserved, repository.get(app.id))
        // A second reservation (retry, restart) resumes rather than reserving generation 6.
        assertEquals(reserved, (repository.reserveNextGeneration(app.id) as GenerationReservation.Reserved).app)
        // Finishing a different generation is ignored.
        repository.finishClearing(app.id, 5)
        assertEquals(ProfileState.CLEARING, repository.get(app.id)?.profileState)
        repository.finishClearing(app.id, 4)
        assertEquals(ProfileState.ACTIVE, repository.get(app.id)?.profileState)
        assertNull(repository.get(app.id)?.retiringProfileGeneration)
        assertEquals(GenerationReservation.MissingApp, repository.reserveNextGeneration("missing"))
    }

    @Test fun clearClosesShellDeletesRetiringGenerationAndItsPermissions() = runBlocking {
        val app = newApp(generation = 2)
        val other = newApp(generation = 2)
        val manager = started()
        store.putWidget(WidgetRecord(4243, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
            setOf(app.id, other.id)))
        val before = repository.get(app.id)!!
        val bound = (attach(manager, app.id) as AttachResult.Attached).bound
        val otherBound = (attach(manager, other.id) as AttachResult.Attached).bound
        for (generation in listOf(2L, 3L)) store.putPermissionDecision(PermissionDecision(app.id,
            generation, "https://example.com", PermissionCapability.GEOLOCATION, SiteDecision.SITE_ALLOW))
        store.putPermissionDecision(PermissionDecision(other.id, 2, "https://example.com",
            PermissionCapability.GEOLOCATION, SiteDecision.SITE_ALLOW))

        assertEquals(ClearResult.Cleared, manager.clear(app.id))
        assertEquals(listOf(app.id), closedShells)
        assertTrue(backend.destroyed(bound.webView))
        assertFalse(backend.destroyed(otherBound.webView))
        assertEquals(listOf(ProfileNames.app(app.id, 2)), backend.deleteCalls)
        val saved = repository.get(app.id)!!
        assertEquals(ProfileState.ACTIVE, saved.profileState)
        assertEquals(3L, saved.profileGeneration)
        assertEquals(before.copy(profileGeneration = 3, updatedAt = saved.updatedAt), saved)
        assertEquals(setOf(app.id, other.id), store.widget(4243)?.selectedAppIds)
        assertTrue(deletedIcons.isEmpty())
        store.deleteWidget(4243)
        assertNull(store.permissionDecision(app.id, 2, "https://example.com", PermissionCapability.GEOLOCATION))
        assertNotNull(store.permissionDecision(app.id, 3, "https://example.com", PermissionCapability.GEOLOCATION))
        assertNotNull(store.permissionDecision(other.id, 2, "https://example.com", PermissionCapability.GEOLOCATION))
        assertEquals(other.copy(sortPosition = 1), repository.get(other.id))
        assertTrue(store.pendingOperations().none { it.intent.appId == app.id })
        val next = (attach(manager, app.id) as AttachResult.Attached).bound
        assertEquals(ProfileNames.app(app.id, 3), next.profileName)
    }

    @Test fun unresolvedJournalOperationBlocksAttachment() = runBlocking {
        val app = newApp()
        val manager = started()
        val entry = store.begin(OperationIntent(OperationType.UPDATE_URL, app.id, 0, 1), 1)
        assertEquals(AttachResult.Blocked(AttachBlock.PENDING_OPERATION), attach(manager, app.id))
        assertTrue(backend.bound.isEmpty())
        store.completeStep(entry.id, 0)
        assertTrue(attach(manager, app.id) is AttachResult.Attached)
    }

    @Test fun processDeathBeforeDeletionResumesAtStartupBeforeAnyLoad() = runBlocking {
        val app = newApp()
        repository.reserveNextGeneration(app.id) // died right after step 1
        val restarted = manager()
        assertEquals(AttachResult.Blocked(AttachBlock.STARTUP_PENDING), attach(restarted, app.id))
        assertTrue(backend.bound.isEmpty())
        val report = restarted.startupMaintenance()
        assertTrue(report.clearingFailures.isEmpty())
        assertEquals(listOf(ProfileNames.app(app.id, 0)), backend.deleteCalls)
        assertEquals(ProfileState.ACTIVE, repository.get(app.id)?.profileState)
        assertTrue(backend.deleteCalls.none { it == ProfileNames.app(app.id, 1) })
    }

    @Test fun processDeathAfterDeletionIsIdempotentOnRestart() = runBlocking {
        val app = newApp()
        val manager = started()
        backend.failAfterDelete = RuntimeException("process died") // deleted, state not persisted
        assertTrue(manager.clear(app.id) is ClearResult.Failed)
        assertEquals(ProfileState.CLEARING, repository.get(app.id)?.profileState)
        assertEquals(AttachResult.Blocked(AttachBlock.CLEARING), attach(manager, app.id))
        backend.failAfterDelete = null

        manager().startupMaintenance()
        val retired = ProfileNames.app(app.id, 0)
        assertEquals(listOf(retired, retired), backend.deleteCalls)
        assertEquals(listOf(true, false), backend.deleteResults)
        assertEquals(ProfileState.ACTIVE, repository.get(app.id)?.profileState)
        assertEquals(1L, repository.get(app.id)?.profileGeneration)
    }

    @Test fun otherFailureStaysClearingAndRetryTargetsOnlyRetiringGeneration() = runBlocking {
        val app = newApp(generation = 7)
        val manager = started()
        backend.failure = UnsupportedOperationException("provider error")
        val failed = manager.clear(app.id)
        assertEquals(backend.failure, (failed as ClearResult.Failed).cause)
        assertEquals(ProfileState.CLEARING, repository.get(app.id)?.profileState)
        assertTrue(store.tombstones().none { app.id in it.profileName })
        backend.failure = null
        assertEquals(ClearResult.Cleared, manager.clear(app.id))
        assertEquals(List(2) { ProfileNames.app(app.id, 7) }, backend.deleteCalls)
        assertEquals(8L, repository.get(app.id)?.profileGeneration)
        assertEquals(ProfileState.ACTIVE, repository.get(app.id)?.profileState)
    }

    @Test fun loadedProfileDefersDeletionToRestart() = runBlocking {
        val app = newApp()
        val manager = started()
        backend.inUse += ProfileNames.app(app.id, 0)
        assertEquals(ClearResult.PendingRestart, manager.clear(app.id))
        val saved = repository.get(app.id)!!
        assertEquals(ProfileState.ACTIVE, saved.profileState)
        assertNull(saved.retiringProfileGeneration)
        assertEquals(ProfileTombstone(ProfileNames.app(app.id, 0), 0, 1000L),
            store.tombstones().single { app.id in it.profileName })
        // The tombstone never blocks the new generation, and is not used to attach.
        assertEquals(ProfileNames.app(app.id, 1),
            (attach(manager, app.id) as AttachResult.Attached).bound.profileName)

        backend.inUse.clear() // process restarted
        val report = manager().startupMaintenance()
        assertTrue(report.pendingTombstones.none { app.id in it })
        assertTrue(store.tombstones().none { app.id in it.profileName })
        assertEquals(List(2) { ProfileNames.app(app.id, 0) }, backend.deleteCalls)
    }

    @Test fun removalTombstonesBothGenerationsAndDeletesNativeData() = runBlocking {
        val app = newApp(generation = 1)
        val other = newApp()
        val manager = started()
        (attach(manager, app.id) as AttachResult.Attached)
        repository.reserveNextGeneration(app.id) // CLEARING: active 2, retiring 1
        store.putPermissionDecision(PermissionDecision(app.id, 1, "https://example.com",
            PermissionCapability.GEOLOCATION, SiteDecision.SITE_DENY))
        store.putWidget(WidgetRecord(4242, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
            setOf(app.id, other.id)))
        backend.inUse += ProfileNames.app(app.id, 1)

        assertEquals(RemovalResult.ProfileDeletionPending, manager.remove(app.id))
        assertNull(repository.get(app.id))
        assertEquals(0, repository.get(other.id)?.sortPosition)
        assertEquals(listOf(app.id), deletedIcons)
        assertEquals(1, widgetUpdates)
        assertEquals(setOf(other.id), store.widget(4242)?.selectedAppIds)
        assertNull(store.permissionDecision(app.id, 1, "https://example.com", PermissionCapability.GEOLOCATION))
        assertEquals(listOf(app.id), closedShells)
        assertEquals(setOf(ProfileNames.app(app.id, 1), ProfileNames.app(app.id, 2)),
            backend.deleteCalls.toSet())
        assertEquals(listOf(ProfileNames.app(app.id, 1)),
            store.tombstones().filter { app.id in it.profileName }.map { it.profileName })
        assertEquals(AttachResult.Blocked(AttachBlock.MISSING_APP), attach(manager, app.id))
        store.deleteWidget(4242)

        backend.inUse.clear()
        manager().startupMaintenance()
        assertTrue(store.tombstones().none { app.id in it.profileName })
    }

    @Test fun incompatibleRemovalMakesNoProfileCallsAndCompletesLater() = runBlocking {
        val app = newApp(generation = 5)
        started()
        repository.reserveNextGeneration(app.id)
        compatible = false
        val manager = manager()
        manager.startupMaintenance()
        assertEquals(ClearResult.Incompatible, manager.clear(app.id))
        assertEquals(AttachResult.Blocked(AttachBlock.INCOMPATIBLE), attach(manager, app.id))

        assertEquals(RemovalResult.ProfileDeletionPending, manager.remove(app.id))
        assertNull(repository.get(app.id))
        assertEquals(listOf(app.id), deletedIcons)
        assertTrue(backend.deleteCalls.isEmpty())
        assertTrue(backend.bound.isEmpty())
        assertEquals(setOf(ProfileNames.app(app.id, 5), ProfileNames.app(app.id, 6)),
            store.tombstones().filter { app.id in it.profileName }.map { it.profileName }.toSet())

        manager().startupMaintenance() // still incompatible
        assertTrue(backend.deleteCalls.isEmpty())

        compatible = true
        manager().startupMaintenance()
        assertEquals(setOf(ProfileNames.app(app.id, 5), ProfileNames.app(app.id, 6)),
            backend.deleteCalls.toSet())
        assertTrue(store.tombstones().none { app.id in it.profileName })
    }

    @Test fun interruptedRemovalResumesBeforeTombstoneRetry() = runBlocking {
        val app = newApp()
        val manager = started()
        val iconFailure = RuntimeException("storage full")
        val failing = ProfileManager(repository, store,
            ProviderCompatibility({ ProviderSnapshot(PROVIDER, true) },
                { key -> ProviderSelfTestResult(key, true, true, true, true, true, true) },
                floors = mapOf(PROVIDER.packageName to listOf(1))),
            backend, { }, object : AppNativeData {
                override fun deleteIcons(appId: String) = throw iconFailure
                override fun widgetsChanged() {}
            }, main = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        failing.startupMaintenance()
        assertEquals(RemovalResult.NativeDeletionPending(iconFailure), failing.remove(app.id))
        assertTrue(backend.deleteCalls.isEmpty())
        assertTrue(store.hasTombstone(ProfileNames.app(app.id, 0)))
        assertEquals(AttachResult.Blocked(AttachBlock.MISSING_APP), attach(manager, app.id))

        manager().startupMaintenance()
        assertEquals(listOf(app.id), deletedIcons)
        assertEquals(listOf(ProfileNames.app(app.id, 0)), backend.deleteCalls)
        assertFalse(store.hasTombstone(ProfileNames.app(app.id, 0)))
        assertTrue(store.pendingOperations().none { it.intent.appId == app.id })
    }

    @Test fun tombstoneForLiveActiveGenerationIsNeverDeleted() = runBlocking {
        val app = newApp()
        store.putTombstone(ProfileTombstone(ProfileNames.app(app.id, 0), 0, 1))
        manager().startupMaintenance()
        assertTrue(backend.deleteCalls.isEmpty())
        store.deleteTombstone(ProfileNames.app(app.id, 0))
    }

    @Test fun disposableProfileIsRegisteredBeforeBindingAndCleanedUp() = runBlocking {
        val manager = started()
        val unregistered = DisposableProfile(UUID.randomUUID().toString())
        assertEquals(AttachResult.Blocked(AttachBlock.NOT_REGISTERED),
            manager.createDisposableWebView(context, unregistered))

        assertNull(manager().createDisposableProfile()) // before startup maintenance
        val profile = manager.createDisposableProfile()!!
        assertTrue(store.hasDiscoveryProfile(profile.profileId))
        backend.onBind = { name -> assertTrue(store.hasDiscoveryProfile(name.removePrefix("mws-disposable-"))) }
        val bound = (manager.createDisposableWebView(context, profile) as AttachResult.Attached).bound
        assertEquals(profile.profileName, bound.profileName)
        backend.inUse += profile.profileName
        assertEquals(DeletionOutcome.Deferred, manager.retireDisposable(profile))
        assertTrue(backend.destroyed(bound.webView))
        assertTrue(store.hasDiscoveryProfile(profile.profileId))
        // A deferred profile keeps its record for cleanup but can never be attached again.
        assertEquals(AttachResult.Blocked(AttachBlock.NOT_REGISTERED),
            manager.createDisposableWebView(context, profile))

        backend.inUse.clear()
        manager().startupMaintenance()
        assertFalse(store.hasDiscoveryProfile(profile.profileId))
        assertEquals(List(2) { profile.profileName }, backend.deleteCalls)

        val second = manager.createDisposableProfile()!!
        assertNotEquals(profile, second)
        assertEquals(DeletionOutcome.Deleted, manager.retireDisposable(second))
        assertFalse(store.hasDiscoveryProfile(second.profileId))
    }

    @Test fun selfTestProfileIsDeletedOnlyInItsFreshProbeProcess() = runBlocking {
        val firstProcess = manager()
        val profile = firstProcess.createSelfTestProfile(processProbe = true)
        assertTrue(store.hasDiscoveryProfile(profile.profileId))
        backend.inUse += profile.profileName
        assertEquals(SelfTestDeletion.Deferred, firstProcess.deleteSelfTestProfile(profile))

        // Startup maintenance in the app process must not delete the child data directory's name.
        manager().startupMaintenance()
        assertTrue(store.hasDiscoveryProfile(profile.profileId))

        backend.inUse.clear() // The probe process died; the same data directory is now unloaded.
        val nextProcess = manager()
        val adopted = nextProcess.adoptSelfTestProfile(profile.profileId)
        assertEquals(SelfTestDeletion.Deleted, nextProcess.deleteSelfTestProfile(adopted))
        nextProcess.finishSelfTestProfile(adopted)
        assertFalse(store.hasDiscoveryProfile(profile.profileId))
    }

    @Test fun webViewsAreBoundBeforeFirstLoadWithThirdPartyCookieSetting() = runBlocking {
        val app = newApp()
        repository.update(app.copy(allowThirdPartyCookies = true))
        val manager = started()
        val bound = (attach(manager, app.id) as AttachResult.Attached).bound
        assertEquals(listOf(ProfileNames.app(app.id, 0) to null), backend.bound)
        assertEquals(true, backend.thirdPartyCookies[bound.webView])
        manager.applyThirdPartyCookies(bound, false)
        assertEquals(false, backend.thirdPartyCookies[bound.webView])
        manager.release(bound)
        assertTrue(backend.destroyed(bound.webView))
    }

    @Test fun shellRegistryClosesOnlyTheTargetApp() = runBlocking {
        val registry = LiveShellRegistry()
        val closed = mutableListOf<String>()
        registry.register("a") { closed += "a1" }
        val unregister = registry.register("a") { closed += "a2" }
        registry.register("b") { closed += "b" }
        unregister()
        registry.closeAll("a")
        registry.closeAll("a")
        assertEquals(listOf("a1"), closed)

        var fail = true
        registry.register("c") { if (fail) error("busy") else closed += "c1" }
        registry.register("c") { closed += "c2" }
        assertThrows(IllegalStateException::class.java) { runBlocking { registry.closeAll("c") } }
        assertEquals(listOf("a1", "c2"), closed)
        fail = false
        registry.closeAll("c")
        assertEquals(listOf("a1", "c2", "c1"), closed)
    }

    @Test fun failedShellCloseKeepsClearingAndRemovalPending() = runBlocking {
        val app = newApp()
        val closeFailure = IllegalStateException("shell busy")
        val manager = ProfileManager(repository, store,
            ProviderCompatibility({ ProviderSnapshot(PROVIDER, true) },
                { key -> ProviderSelfTestResult(key, true, true, true, true, true, true) },
                floors = mapOf(PROVIDER.packageName to listOf(1))),
            backend, { throw closeFailure }, object : AppNativeData {
                override fun deleteIcons(appId: String) { deletedIcons += appId }
                override fun widgetsChanged() {}
            }, main = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        manager.startupMaintenance()
        assertEquals(ClearResult.Failed(closeFailure), manager.clear(app.id))
        assertEquals(ProfileState.CLEARING, repository.get(app.id)?.profileState)
        assertTrue(backend.deleteCalls.isEmpty())
        assertEquals(RemovalResult.NativeDeletionPending(closeFailure), manager.remove(app.id))
        assertNotNull(repository.get(app.id))
        assertTrue(backend.deleteCalls.isEmpty())
        assertEquals(AttachResult.Blocked(AttachBlock.CLEARING), attach(manager, app.id))

        manager().startupMaintenance() // new process: no live shells, journal completes
        assertNull(repository.get(app.id))
        assertEquals(setOf(ProfileNames.app(app.id, 0), ProfileNames.app(app.id, 1)),
            backend.deleteCalls.toSet())
        assertTrue(store.tombstones().none { app.id in it.profileName })
    }

    private class FakeBackend : WebProfileBackend {
        val deleteCalls = mutableListOf<String>()
        val deleteResults = mutableListOf<Boolean>()
        val bound = mutableListOf<Pair<String, String?>>()
        val inUse = mutableSetOf<String>()
        val thirdPartyCookies = mutableMapOf<WebView, Boolean>()
        private val deleted = mutableSetOf<String>()
        private val destroyedViews = mutableSetOf<WebView>()
        var failure: Exception? = null
        var failAfterDelete: Exception? = null
        var onBind: (String) -> Unit = {}

        override fun deleteProfile(name: String): Boolean {
            deleteCalls += name
            failure?.let { throw it }
            if (name in inUse) throw IllegalStateException("loaded")
            val result = deleted.add(name) // true once, then false: absent
            deleteResults += result
            failAfterDelete?.let { throw it }
            return result
        }

        override fun profileExists(name: String): Boolean = name !in deleted

        override fun createBoundWebView(context: Context, name: String): WebView {
            onBind(name)
            val webView = object : WebView(context) {
                override fun destroy() {
                    destroyedViews += this
                    super.destroy()
                }
            }
            bound += name to webView.url
            return webView
        }

        override fun setThirdPartyCookies(webView: WebView, allow: Boolean) {
            thirdPartyCookies[webView] = allow
        }

        fun destroyed(webView: WebView) = webView in destroyedViews
    }

    companion object {
        private val PROVIDER = ProviderKey("com.google.android.webview", "200.0", 28, "x86_64")
    }
}
