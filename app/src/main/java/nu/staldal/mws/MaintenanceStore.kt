package nu.staldal.mws

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Device-local state that must never be restored with configuration.db. */
class MaintenanceStore(context: Context) : Closeable {
    private val database: SQLiteDatabase

    init {
        val file = File(context.noBackupFilesDir, "maintenance.db")
        database = SQLiteDatabase.openOrCreateDatabase(file.path, null, DatabaseErrorHandler {
            // Do not erase device-local evidence and mistake corruption for a restored install.
            throw SQLiteDatabaseCorruptException("Corrupt maintenance store")
        })
        check(pragmaValue("PRAGMA journal_mode=DELETE").equals("delete", ignoreCase = true))
        database.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
        check(pragmaValue("PRAGMA synchronous") == "2")
        database.execSQL("CREATE TABLE IF NOT EXISTS restore_pending (singleton INTEGER PRIMARY KEY)")
        val version = pragmaValue("PRAGMA user_version").toInt()
        check(version in 0..1) { "Unsupported maintenance database version $version" }
        database.execSQL("""CREATE TABLE IF NOT EXISTS operations (
            id TEXT PRIMARY KEY, type TEXT NOT NULL, app_id TEXT, old_generation INTEGER,
            new_generation INTEGER, resource_id TEXT, step_count INTEGER NOT NULL,
            completed_steps INTEGER NOT NULL DEFAULT 0 CHECK(completed_steps >= 0 AND completed_steps <= step_count))""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS tombstones (
            profile_name TEXT PRIMARY KEY, generation INTEGER NOT NULL, created_at INTEGER NOT NULL)""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS discovery_profiles (
            profile_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS permission_decisions (
            app_id TEXT NOT NULL, generation INTEGER NOT NULL, origin TEXT NOT NULL,
            capability TEXT NOT NULL CHECK(capability = 'GEOLOCATION'),
            decision TEXT NOT NULL CHECK(decision IN ('SITE_ALLOW', 'SITE_DENY')),
            PRIMARY KEY(app_id, generation, origin, capability))""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS widgets (
            widget_id INTEGER PRIMARY KEY, widget_type TEXT NOT NULL,
            selection_mode TEXT, target_app_id TEXT)""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS widget_selection (
            widget_id INTEGER NOT NULL, app_id TEXT NOT NULL,
            PRIMARY KEY(widget_id, app_id), FOREIGN KEY(widget_id) REFERENCES widgets(widget_id) ON DELETE CASCADE)""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS self_test (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1), match_key TEXT NOT NULL,
            results TEXT NOT NULL)""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS installation (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1))""")
        database.rawQuery("PRAGMA foreign_keys=ON", null).use { it.moveToFirst() }
        check(pragmaValue("PRAGMA foreign_keys") == "1")
        if (version == 0) {
            database.execSQL("INSERT OR IGNORE INTO restore_pending VALUES (1)")
            database.execSQL("PRAGMA user_version=1")
        }
    }

    @Synchronized override fun close() = database.close()

    @Synchronized internal fun storagePragmas(): Triple<String, String, String> = Triple(
        pragmaValue("PRAGMA journal_mode"), pragmaValue("PRAGMA synchronous"),
        pragmaValue("PRAGMA user_version"))

    /** A completed step is committed before the next step is invoked. */
    /** [tombstones] commit in the same transaction as the intent, so neither exists without the other. */
    @Synchronized fun begin(intent: OperationIntent, stepCount: Int,
        tombstones: List<ProfileTombstone> = emptyList()): JournalEntry {
        require(stepCount > 0)
        val id = UUID.randomUUID().toString()
        database.beginTransaction()
        try {
            tombstones.forEach(::putTombstone)
            database.insertOrThrow("operations", null, values(
                "id" to id, "type" to intent.type.name, "app_id" to intent.appId,
                "old_generation" to intent.oldGeneration, "new_generation" to intent.newGeneration,
                "resource_id" to intent.resourceId, "step_count" to stepCount))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return JournalEntry(id, intent, stepCount, 0)
    }

    @Synchronized fun pendingOperations(): List<JournalEntry> = operationIds(false).map(::operation)

    @Synchronized fun completedOperations(): List<JournalEntry> = operationIds(true).map(::operation)

    @Synchronized fun pendingOperationIds(): List<String> = operationIds(false)

    @Synchronized fun operation(id: String): JournalEntry = database.rawQuery(
        "SELECT type,app_id,old_generation,new_generation,resource_id,step_count,completed_steps " +
            "FROM operations WHERE id = ?", arrayOf(id)).use { cursor ->
        check(cursor.moveToFirst()) { "Missing journal entry $id" }
        JournalEntry(id, OperationIntent(OperationType.valueOf(cursor.getString(0)),
            cursor.nullableString(1), cursor.nullableLong(2), cursor.nullableLong(3),
            cursor.nullableString(4)), cursor.getInt(5), cursor.getInt(6))
    }

    private fun operationIds(completed: Boolean): List<String> = database.rawQuery(
        "SELECT id FROM operations WHERE completed_steps ${if (completed) "=" else "<"} " +
            "step_count ORDER BY rowid", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    @Synchronized fun completeStep(id: String, expectedStep: Int) {
        val changed = database.compileStatement("""UPDATE operations SET completed_steps = completed_steps + 1
            WHERE id = ? AND completed_steps = ? AND completed_steps < step_count""").use { statement ->
            statement.bindString(1, id)
            statement.bindLong(2, expectedStep.toLong())
            statement.executeUpdateDelete()
        }
        check(changed == 1) { "Journal cursor changed for $id at step $expectedStep" }
    }

    /** Finished intents may be deleted after all consumers have observed completion. */
    @Synchronized fun discardCompleted(id: String) {
        database.delete("operations", "id = ? AND completed_steps = step_count", arrayOf(id))
    }

    @Synchronized fun putTombstone(record: ProfileTombstone) {
        database.insertWithOnConflict("tombstones", null, values(
            "profile_name" to record.profileName, "generation" to record.generation,
            "created_at" to record.createdAt), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun tombstones(): List<ProfileTombstone> = database.rawQuery(
        "SELECT profile_name,generation,created_at FROM tombstones ORDER BY created_at", null).use { c ->
        buildList { while (c.moveToNext()) add(ProfileTombstone(c.getString(0), c.getLong(1), c.getLong(2))) }
    }

    @Synchronized fun hasTombstone(profileName: String): Boolean = database.rawQuery(
        "SELECT 1 FROM tombstones WHERE profile_name = ?", arrayOf(profileName)).use(Cursor::moveToFirst)

    @Synchronized fun deleteTombstone(profileName: String) {
        database.delete("tombstones", "profile_name = ?", arrayOf(profileName))
    }

    @Synchronized fun registerDiscoveryProfile(record: DiscoveryProfile) {
        database.insertWithOnConflict("discovery_profiles", null, values(
            "profile_id" to record.profileId, "created_at" to record.createdAt),
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun discoveryProfiles(): List<DiscoveryProfile> = database.rawQuery(
        "SELECT profile_id,created_at FROM discovery_profiles ORDER BY created_at", null).use { c ->
        buildList { while (c.moveToNext()) add(DiscoveryProfile(c.getString(0), c.getLong(1))) }
    }

    @Synchronized fun deleteDiscoveryProfile(profileId: String) {
        database.delete("discovery_profiles", "profile_id = ?", arrayOf(profileId))
    }

    @Synchronized fun putPermissionDecision(record: PermissionDecision) {
        database.insertWithOnConflict("permission_decisions", null, values(
            "app_id" to record.appId, "generation" to record.generation,
            "origin" to record.origin, "capability" to record.capability.name,
            "decision" to record.decision.name), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun permissionDecision(appId: String, generation: Long, origin: String,
        capability: PermissionCapability): PermissionDecision? = database.rawQuery(
        "SELECT decision FROM permission_decisions WHERE app_id = ? AND generation = ? " +
            "AND origin = ? AND capability = ?",
        arrayOf(appId, generation.toString(), origin, capability.name)).use { c ->
        if (c.moveToFirst()) PermissionDecision(appId, generation, origin, capability,
            SiteDecision.valueOf(c.getString(0))) else null
    }

    @Synchronized fun deletePermissionDecisions(appId: String, generation: Long) {
        database.delete("permission_decisions", "app_id = ? AND generation = ?",
            arrayOf(appId, generation.toString()))
    }

    @Synchronized fun deleteAllPermissionDecisions(appId: String) {
        database.delete("permission_decisions", "app_id = ?", arrayOf(appId))
    }

    @Synchronized fun prunePermissions(active: Set<Pair<String, Long>>) {
        val obsolete = database.rawQuery("SELECT DISTINCT app_id,generation FROM permission_decisions", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        obsolete.filterNot(active::contains).forEach { (id, generation) ->
            deletePermissionDecisions(id, generation)
        }
    }

    @Synchronized fun pruneDanglingWidgetSelections(appIds: Set<String>) {
        widgetIds().forEach { id ->
            val record = requireNotNull(widget(id))
            if (record.widgetType == WidgetType.MULTI_APP) {
                val retained = record.selectedAppIds.intersect(appIds)
                if (retained != record.selectedAppIds) putWidget(record.copy(selectedAppIds = retained))
            }
        }
    }

    @Synchronized fun hasDiscoveryProfile(profileId: String): Boolean = database.rawQuery(
        "SELECT 1 FROM discovery_profiles WHERE profile_id = ?", arrayOf(profileId)).use(Cursor::moveToFirst)

    @Synchronized fun putWidget(record: WidgetRecord) {
        database.beginTransaction()
        try {
            database.insertWithOnConflict("widgets", null, values(
                "widget_id" to record.appWidgetId, "widget_type" to record.widgetType.name,
                "selection_mode" to record.selectionMode?.name,
                "target_app_id" to record.targetAppId), SQLiteDatabase.CONFLICT_REPLACE)
            database.delete("widget_selection", "widget_id = ?", arrayOf(record.appWidgetId.toString()))
            record.selectedAppIds.forEach { appId ->
                database.insertOrThrow("widget_selection", null, values(
                    "widget_id" to record.appWidgetId, "app_id" to appId))
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun widget(appWidgetId: Int): WidgetRecord? = database.rawQuery(
        "SELECT widget_type,selection_mode,target_app_id FROM widgets WHERE widget_id = ?",
        arrayOf(appWidgetId.toString())).use { c ->
        if (!c.moveToFirst()) return@use null
        val ids = database.rawQuery("SELECT app_id FROM widget_selection WHERE widget_id = ? ORDER BY app_id",
            arrayOf(appWidgetId.toString())).use { selection ->
            buildSet { while (selection.moveToNext()) add(selection.getString(0)) }
        }
        WidgetRecord(appWidgetId, WidgetType.valueOf(c.getString(0)),
            c.nullableString(1)?.let(SelectionMode::valueOf), ids, c.nullableString(2))
    }

    @Synchronized fun deleteWidget(appWidgetId: Int) {
        database.delete("widgets", "widget_id = ?", arrayOf(appWidgetId.toString()))
    }

    @Synchronized fun widgetIds(): List<Int> = database.rawQuery(
        "SELECT widget_id FROM widgets", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getInt(0)) }
    }

    @Synchronized fun pruneWidgets(installedIds: Set<Int>, type: WidgetType? = null) {
        database.beginTransaction()
        try {
            widgetIds().filterNot(installedIds::contains).filter { id ->
                type == null || widget(id)?.widgetType == type
            }.forEach(::deleteWidget)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun pruneWidgetSelection(appId: String) {
        database.delete("widget_selection", "app_id = ?", arrayOf(appId))
    }

    @Synchronized fun putSelfTest(result: RuntimeSelfTest) {
        val encoded = JSONObject(result.features).toString()
        database.insertWithOnConflict("self_test", null, values(
            "singleton" to 1, "match_key" to result.matchKey, "results" to encoded),
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun selfTest(): RuntimeSelfTest? = database.rawQuery(
        "SELECT match_key,results FROM self_test WHERE singleton = 1", null).use { c ->
        if (!c.moveToFirst()) return@use null
        val json = JSONObject(c.getString(1))
        RuntimeSelfTest(c.getString(0), json.keys().asSequence().associateWith(json::getBoolean))
    }

    @Synchronized fun hasInstallationSentinel(): Boolean = database.rawQuery(
        "SELECT 1 FROM installation WHERE singleton = 1", null).use(Cursor::moveToFirst)

    /** Existing pre-sentinel installs have a maintenance DB but no restore marker. */
    @Synchronized fun needsRestoreNormalization(): Boolean = !hasInstallationSentinel() &&
        (database.rawQuery("SELECT 1 FROM restore_pending", null).use(Cursor::moveToFirst) ||
            (pendingOperations() + completedOperations()).any { it.intent.type == OperationType.RESTORE })

    @Synchronized fun createInstallationSentinel() {
        database.beginTransaction()
        try {
            database.insertWithOnConflict("installation", null, values("singleton" to 1),
                SQLiteDatabase.CONFLICT_IGNORE)
            database.delete("restore_pending", null, null)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    private fun pragmaValue(sql: String): String = database.rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst()) { "No result from $sql" }
        cursor.getString(0)
    }

    private fun values(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues().apply {
        pairs.forEach { (key, value) -> when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            else -> error("Unsupported value for $key")
        } }
    }

    private fun Cursor.nullableString(index: Int) = if (isNull(index)) null else getString(index)
    private fun Cursor.nullableLong(index: Int) = if (isNull(index)) null else getLong(index)
}

enum class OperationType { ADD_APP, REMOVE_APP, UPDATE_URL, CLEAR_DATA, RESTORE,
    UPDATE_ICON, UPDATE_WIDGET, DELETE_PERMISSION_DECISIONS }

data class OperationIntent(val type: OperationType, val appId: String? = null,
    val oldGeneration: Long? = null, val newGeneration: Long? = null,
    val resourceId: String? = null)
data class JournalEntry(val id: String, val intent: OperationIntent,
    val stepCount: Int, val completedSteps: Int)

data class ProfileTombstone(val profileName: String, val generation: Long, val createdAt: Long)
data class DiscoveryProfile(val profileId: String, val createdAt: Long)
enum class PermissionCapability { GEOLOCATION }
enum class SiteDecision { SITE_ALLOW, SITE_DENY }
data class PermissionDecision(val appId: String, val generation: Long, val origin: String,
    val capability: PermissionCapability, val decision: SiteDecision)
enum class WidgetType { MULTI_APP, SINGLE_APP }
enum class SelectionMode { ALL_APPS, SELECTED_SUBSET }
data class WidgetRecord(val appWidgetId: Int, val widgetType: WidgetType,
    val selectionMode: SelectionMode? = null, val selectedAppIds: Set<String> = emptySet(),
    val targetAppId: String? = null) {
    init {
        require(if (widgetType == WidgetType.SINGLE_APP)
            selectionMode == null && selectedAppIds.isEmpty() && targetAppId != null
        else selectionMode != null && targetAppId == null &&
            (selectionMode == SelectionMode.SELECTED_SUBSET || selectedAppIds.isEmpty()))
    }
}
data class RuntimeSelfTest(val matchKey: String, val features: Map<String, Boolean>)

/** Step actions must tolerate replay: a crash can happen after the action but before cursor commit. */
class OperationJournal(private val store: MaintenanceStore,
    private val plans: Map<OperationType, List<(OperationIntent) -> Unit>>) {
    fun start(intent: OperationIntent, tombstones: List<ProfileTombstone> = emptyList()): JournalEntry {
        val steps = requireNotNull(plans[intent.type]) { "No plan for ${intent.type}" }
        return store.begin(intent, steps.size, tombstones)
    }

    fun run(entry: JournalEntry) {
        val steps = requireNotNull(plans[entry.intent.type]) { "No plan for ${entry.intent.type}" }
        check(steps.size == entry.stepCount) { "Plan changed for ${entry.intent.type}" }
        for (index in entry.completedSteps until steps.size) {
            steps[index](entry.intent)
            store.completeStep(entry.id, index)
        }
    }

    /** Every entry is attempted; failed entries stay pending and must be handled by the caller. */
    fun resumeOnStartup(): ResumeResult {
        val completed = mutableListOf<String>()
        val failures = mutableMapOf<String, Throwable>()
        for (id in store.pendingOperationIds()) {
            try {
                run(store.operation(id))
                completed += id
            } catch (failure: Exception) {
                failures[id] = failure
            }
        }
        return ResumeResult(completed, failures)
    }
}

data class ResumeResult(val completedIds: List<String>, val failures: Map<String, Throwable>)
