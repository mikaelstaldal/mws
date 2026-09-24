package nu.staldal.mws

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigurationRepositoryTest {
    private lateinit var database: ConfigurationDatabase
    private lateinit var repository: ConfigurationRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            ConfigurationDatabase::class.java).allowMainThreadQueries().build()
        repository = ConfigurationRepository(database)
    }

    @After fun tearDown() = database.close()

    private fun app(id: String) = ConfiguredApp(id, id, NameSource.DISCOVERED,
        "https://example.com/$id", null, "https://example.com/$id", "https://example.com/",
        createdAt = 10, updatedAt = 10)

    private suspend fun assertOrder(vararg ids: String) {
        val rows = repository.ordered()
        assertEquals(ids.toList(), rows.map { it.id })
        assertEquals(ids.indices.toList(), rows.map { it.sortPosition })
        assertEquals(rows, repository.apps.first())
    }

    @Test fun appendRemoveAndRenameKeepCanonicalOrder() = runBlocking {
        assertEquals(ConfigurationWriteResult.Success, repository.add(app("b")))
        assertEquals(ConfigurationWriteResult.Success, repository.add(app("a")))
        assertEquals(ConfigurationWriteResult.Success, repository.add(app("c")))
        assertOrder("b", "a", "c")
        assertEquals(ConfigurationWriteResult.Success,
            repository.update(app("a").copy(name = "Renamed", sortPosition = 900)))
        assertOrder("b", "a", "c")
        assertEquals("Renamed", repository.get("a")?.name)
        assertEquals(10L, repository.get("a")?.createdAt)
        assertEquals(ConfigurationWriteResult.Success, repository.remove("a"))
        assertOrder("b", "c")
        assertEquals(ConfigurationWriteResult.Success, repository.add(app("d")))
        assertOrder("b", "c", "d")
        assertEquals(ConfigurationWriteResult.DuplicateApp, repository.add(app("b")))
        assertEquals(ConfigurationWriteResult.MissingApp, repository.update(app("missing")))
        assertEquals(ConfigurationWriteResult.MissingApp, repository.remove("missing"))
    }

    @Test fun reorderSurvivesDatabaseReopenAndRejectsInvalidLists() = runBlocking {
        database.close()
        val context = RuntimeEnvironment.getApplication()
        val name = "configuration-test-${System.nanoTime()}.db"
        database = Room.databaseBuilder(context, ConfigurationDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        repository = ConfigurationRepository(database)
        listOf("a", "b", "c").forEach { repository.add(app(it)) }
        assertEquals(ConfigurationWriteResult.InvalidOrder, repository.reorder(listOf("c", "c", "a")))
        assertEquals(ConfigurationWriteResult.InvalidOrder, repository.reorder(listOf("c", "a")))
        assertEquals(ConfigurationWriteResult.InvalidOrder, repository.reorder(listOf("c", "a", "unknown")))
        assertOrder("a", "b", "c")
        assertEquals(ConfigurationWriteResult.Success, repository.reorder(listOf("c", "a", "b")))
        database.close()
        database = Room.databaseBuilder(context, ConfigurationDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        repository = ConfigurationRepository(database)
        assertOrder("c", "a", "b")
        database.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test fun failedReorderRollsBackAllPositions() = runBlocking {
        listOf("a", "b", "c").forEach { repository.add(app(it)) }
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_reorder BEFORE UPDATE OF sortPosition
            ON configured_apps WHEN NEW.id = 'b' BEGIN SELECT RAISE(ABORT, 'interrupted'); END""")
        try {
            repository.reorder(listOf("c", "b", "a"))
            fail("Expected interrupted reorder")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertOrder("a", "b", "c")
        }
    }

    @Test fun repairUsesIdForTiesAndClosesGaps() = runBlocking {
        database.apps().insert(app("z").copy(sortPosition = 8))
        database.apps().insert(app("b").copy(sortPosition = 2))
        database.apps().insert(app("a").copy(sortPosition = 2))
        assertEquals(ConfigurationWriteResult.Success, repository.repairOrder())
        assertOrder("a", "b", "z")
        assertEquals(ConfigurationWriteResult.Success, repository.repairOrder())
        assertOrder("a", "b", "z")
    }

    @Test fun staleEditorUpdateCannotUndoProfileReservation() = runBlocking {
        val stale = app("a")
        repository.add(stale)
        database.apps().update(stale.copy(profileState = ProfileState.CLEARING,
            profileGeneration = 4, retiringProfileGeneration = 3))
        assertEquals(ConfigurationWriteResult.Success,
            repository.update(stale.copy(name = "New name")))
        val saved = repository.get("a")!!
        assertEquals("New name", saved.name)
        assertEquals(ProfileState.CLEARING, saved.profileState)
        assertEquals(4L, saved.profileGeneration)
        assertEquals(3L, saved.retiringProfileGeneration)
    }

    @Test fun failedRemovalRollsBackDeletedRowAndPositionUpdates() = runBlocking {
        listOf("a", "b", "c").forEach { repository.add(app(it)) }
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_compaction
            BEFORE UPDATE OF sortPosition ON configured_apps WHEN NEW.id = 'c'
            BEGIN SELECT RAISE(ABORT, 'interrupted'); END""")
        try {
            repository.remove("b")
            fail("Expected interrupted removal")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertOrder("a", "b", "c")
        }
    }

    @Test fun productionDatabaseUsesBackupName() {
        val context = RuntimeEnvironment.getApplication()
        database.close()
        context.deleteDatabase("configuration.db")
        database = ConfigurationDatabase.open(context)
        assertEquals("configuration.db", database.openHelper.databaseName)
        database.close()
        context.deleteDatabase("configuration.db")
        Unit
    }
}
