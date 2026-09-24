package nu.staldal.mws

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class NameSource { DISCOVERED, USER_EDITED }
enum class ProfileState { ACTIVE, CLEARING }

/** Non-secret configuration only. Snapshots and consent contain sanitized serialized metadata. */
@Entity(tableName = "configured_apps", indices = [Index("sortPosition")])
data class ConfiguredApp(
    @PrimaryKey val id: String,
    val name: String,
    val nameSource: NameSource,
    val configuredUrl: String,
    val finalUrl: String?,
    val launchUrl: String,
    val scope: String,
    val allowThirdPartyCookies: Boolean = false,
    val profileState: ProfileState = ProfileState.ACTIVE,
    val profileGeneration: Long = 0,
    val retiringProfileGeneration: Long? = null,
    val manifestSnapshot: String? = null,
    val iconReference: String? = null,
    val themeColor: Int? = null,
    val backgroundColor: Int? = null,
    val sortPosition: Int = 0,
    val sensitiveUrlConsent: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ConfiguredAppDao {
    @Query("SELECT * FROM configured_apps ORDER BY sortPosition ASC, id ASC")
    fun observeOrdered(): Flow<List<ConfiguredApp>>

    @Query("SELECT * FROM configured_apps ORDER BY sortPosition ASC, id ASC")
    suspend fun ordered(): List<ConfiguredApp>

    @Query("SELECT * FROM configured_apps WHERE id = :id")
    suspend fun get(id: String): ConfiguredApp?

    @Insert
    suspend fun insert(app: ConfiguredApp)

    @Update
    suspend fun update(app: ConfiguredApp)

    @Query("UPDATE configured_apps SET sortPosition = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    @Query("DELETE FROM configured_apps WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [ConfiguredApp::class], version = 1, exportSchema = true)
abstract class ConfigurationDatabase : RoomDatabase() {
    abstract fun apps(): ConfiguredAppDao

    companion object {
        fun open(context: Context): ConfigurationDatabase =
            Room.databaseBuilder(context, ConfigurationDatabase::class.java, "configuration.db")
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
