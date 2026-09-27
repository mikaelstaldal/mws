package nu.staldal.mws

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class StartupReconciliationTest {
    @Test fun interruptedRestoreUsesRecordedGenerationAndRepairsOrder() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java)
            .allowMainThreadQueries().build()
        val repository = ConfigurationRepository(db)
        val store = MaintenanceStore(context)
        try {
            val app = ConfiguredApp("a", "A", NameSource.DISCOVERED, "https://example.com/",
                null, "https://example.com/", "https://example.com/",
                profileState = ProfileState.CLEARING, profileGeneration = 4,
                retiringProfileGeneration = 8, iconReference = "missing", sortPosition = 19,
                createdAt = 1, updatedAt = 1)
            db.apps().insert(app)
            assertFalse(store.hasInstallationSentinel())
            val entry = store.begin(OperationIntent(OperationType.RESTORE, "a", newGeneration = 9), 1)
            // Configuration committed, but the cursor did not: simulate interruption.
            repository.normalizeRestored("a", 9)
            val journal = OperationJournal(store, mapOf(OperationType.RESTORE to listOf({ intent ->
                runBlocking { repository.normalizeRestored(intent.appId!!, intent.newGeneration!!) }
            })))
            assertTrue(journal.resumeOnStartup().failures.isEmpty())
            assertEquals(1, store.operation(entry.id).completedSteps)
            val normalized = repository.get("a")!!
            assertEquals(9L, normalized.profileGeneration)
            assertEquals(ProfileState.ACTIVE, normalized.profileState)
            assertNull(normalized.retiringProfileGeneration)
            assertNull(normalized.iconReference)
            assertEquals(0, normalized.sortPosition)
            store.createInstallationSentinel()
            assertTrue(store.hasInstallationSentinel())
        } finally { store.close(); db.close() }
    }

    @Test fun danglingIconReferencesAreCleared() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val reference = "a/00000000-0000-0000-0000-000000000001"
            for (size in IconPipeline.SIZES) {
                java.io.File(context.noBackupFilesDir, "icons/$reference/$size.png").apply {
                    parentFile!!.mkdirs(); writeText("icon")
                }
            }
            val app = ConfiguredApp("a", "A", NameSource.DISCOVERED, "https://example.com/",
                null, "https://example.com/", "https://example.com/", iconReference = reference,
                createdAt = 1, updatedAt = 1)
            db.apps().insert(app)
            db.apps().insert(app.copy(id = "b", iconReference = "missing"))
            val repository = ConfigurationRepository(db)
            repository.clearMissingIcons(IconStore(context)::exists)
            assertEquals(reference, repository.get("a")!!.iconReference)
            assertNull(repository.get("b")!!.iconReference)
        } finally { db.close() }
    }

    @Test fun corruptMaintenanceStoreIsNotDeletedAndMisclassifiedAsRestore() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.noBackupFilesDir, "maintenance.db")
        val corrupt = "not a SQLite database".repeat(100)
        file.writeText(corrupt)
        assertThrows(android.database.sqlite.SQLiteException::class.java) { MaintenanceStore(context) }
        assertEquals(corrupt, file.readText())
    }

    @Test fun preSentinelInstallationIsNotMistakenForRestore() {
        val context: Context = RuntimeEnvironment.getApplication()
        MaintenanceStore(context).use { }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            java.io.File(context.noBackupFilesDir, "maintenance.db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE restore_pending") // Shape of the pre-sentinel release.
        }
        MaintenanceStore(context).use {
            assertFalse(it.hasInstallationSentinel())
            assertFalse(it.needsRestoreNormalization())
            it.createInstallationSentinel()
            assertTrue(it.hasInstallationSentinel())
        }
    }

    @Test fun iconPruningProtectsReferencesAndPendingApps() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = java.io.File(context.noBackupFilesDir, "icons")
        val keep = "a/00000000-0000-0000-0000-000000000001"
        val orphan = "a/00000000-0000-0000-0000-000000000002"
        val pending = "pending/.temporary"
        for (ref in listOf(keep, orphan, pending, "absent/old")) {
            java.io.File(root, ref).mkdirs()
            java.io.File(root, "$ref/48.png").writeText("icon")
        }
        val icons = IconStore(context)
        icons.reconcile(setOf(keep), setOf("pending"))
        assertTrue(java.io.File(root, keep).exists())
        assertTrue(java.io.File(root, pending).exists())
        assertFalse(java.io.File(root, orphan).exists())
        assertFalse(java.io.File(root, "absent").exists())
        assertFalse(icons.exists("../outside"))
        assertFalse(icons.exists(keep)) // Missing renditions count as a dangling reference.
    }

    @Test fun pruningRemovesOrphansAndKeepsUnavailableSingleWidget() {
        val store = MaintenanceStore(RuntimeEnvironment.getApplication())
        try {
            for ((id, generation) in listOf("a" to 1L, "a" to 2L, "absent" to 0L)) {
                store.putPermissionDecision(PermissionDecision(id, generation, "https://example.com",
                    PermissionCapability.GEOLOCATION, SiteDecision.SITE_ALLOW))
            }
            store.prunePermissions(setOf("a" to 2L))
            assertNull(store.permissionDecision("a", 1, "https://example.com", PermissionCapability.GEOLOCATION))
            assertNull(store.permissionDecision("absent", 0, "https://example.com", PermissionCapability.GEOLOCATION))
            assertNotNull(store.permissionDecision("a", 2, "https://example.com", PermissionCapability.GEOLOCATION))
            store.putWidget(WidgetRecord(1, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
                setOf("a", "absent")))
            store.putWidget(WidgetRecord(2, WidgetType.SINGLE_APP, targetAppId = "absent"))
            store.pruneDanglingWidgetSelections(setOf("a"))
            assertEquals(setOf("a"), store.widget(1)!!.selectedAppIds)
            assertEquals("absent", store.widget(2)!!.targetAppId)
            store.pruneWidgets(setOf(2))
            assertNull(store.widget(1))
            assertNotNull(store.widget(2))
        } finally { store.close() }
    }
}
