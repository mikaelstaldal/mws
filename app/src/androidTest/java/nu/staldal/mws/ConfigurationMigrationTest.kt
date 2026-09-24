package nu.staldal.mws

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** The exported v1 schema is packaged for future migration tests. */
@RunWith(AndroidJUnit4::class)
class ConfigurationMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ConfigurationDatabase::class.java)

    @Test fun exportedVersionOneSchemaValidates() {
        val name = "configuration-migration-test.db"
        migrations.createDatabase(name, 1).close()
        migrations.runMigrationsAndValidate(name, 1, true).close()
    }

    @Test fun productionDatabaseUsesSingleTruncateJournalFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("configuration.db")
        val database = ConfigurationDatabase.open(context)
        try {
            val journal = database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("truncate", journal.lowercase())
            assertEquals("configuration.db", database.openHelper.databaseName)
            assertTrue(context.getDatabasePath("configuration.db").isFile)
        } finally {
            database.close()
        }
        context.deleteDatabase("configuration.db")
    }
}
