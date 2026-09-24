package nu.staldal.mws

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

sealed interface ConfigurationWriteResult {
    data object Success : ConfigurationWriteResult
    data object MissingApp : ConfigurationWriteResult
    data object InvalidOrder : ConfigurationWriteResult
    data object DuplicateApp : ConfigurationWriteResult
    data class StorageFailure(val cause: Throwable) : ConfigurationWriteResult
}

/** Owns all mutations of the canonical order. Each operation commits or rolls back as one unit. */
class ConfigurationRepository(private val database: ConfigurationDatabase) {
    private val dao = database.apps()

    val apps: Flow<List<ConfiguredApp>> = dao.observeOrdered()

    suspend fun ordered(): List<ConfiguredApp> = dao.ordered()
    suspend fun get(id: String): ConfiguredApp? = dao.get(id)

    suspend fun add(app: ConfiguredApp): ConfigurationWriteResult = write {
        database.withTransaction {
            if (dao.get(app.id) != null) return@withTransaction ConfigurationWriteResult.DuplicateApp
            repairPositions()
            dao.insert(app.copy(sortPosition = dao.ordered().size))
            ConfigurationWriteResult.Success
        }
    }

    suspend fun update(app: ConfiguredApp): ConfigurationWriteResult = write {
        database.withTransaction {
            val saved = dao.get(app.id) ?: return@withTransaction ConfigurationWriteResult.MissingApp
            dao.update(app.copy(sortPosition = saved.sortPosition, createdAt = saved.createdAt,
                profileState = saved.profileState,
                profileGeneration = saved.profileGeneration,
                retiringProfileGeneration = saved.retiringProfileGeneration))
            ConfigurationWriteResult.Success
        }
    }

    suspend fun remove(id: String): ConfigurationWriteResult = write {
        database.withTransaction {
            if (dao.get(id) == null) return@withTransaction ConfigurationWriteResult.MissingApp
            dao.delete(id)
            repairPositions()
            ConfigurationWriteResult.Success
        }
    }

    suspend fun reorder(ids: List<String>): ConfigurationWriteResult = write {
        database.withTransaction {
            val existing = dao.ordered().map { it.id }
            if (ids.size != existing.size || ids.toSet() != existing.toSet()) {
                return@withTransaction ConfigurationWriteResult.InvalidOrder
            }
            ids.forEachIndexed { position, id -> dao.setPosition(id, position) }
            ConfigurationWriteResult.Success
        }
    }

    /** Called during startup reconciliation and after deletion. Equal positions sort by stable ID. */
    suspend fun repairOrder(): ConfigurationWriteResult = write {
        database.withTransaction {
            repairPositions()
            ConfigurationWriteResult.Success
        }
    }

    private suspend fun repairPositions() {
        dao.ordered().forEachIndexed { position, app ->
            if (app.sortPosition != position) dao.setPosition(app.id, position)
        }
    }

    private suspend fun write(block: suspend () -> ConfigurationWriteResult): ConfigurationWriteResult =
        try {
            block()
        } catch (failure: SQLiteFullException) {
            ConfigurationWriteResult.StorageFailure(failure)
        } catch (failure: SQLiteDiskIOException) {
            ConfigurationWriteResult.StorageFailure(failure)
        } catch (failure: SQLiteOutOfMemoryException) {
            ConfigurationWriteResult.StorageFailure(failure)
        } catch (failure: SQLiteCantOpenDatabaseException) {
            ConfigurationWriteResult.StorageFailure(failure)
        } catch (failure: SQLiteReadOnlyDatabaseException) {
            ConfigurationWriteResult.StorageFailure(failure)
        } catch (failure: SQLiteDatabaseLockedException) {
            ConfigurationWriteResult.StorageFailure(failure)
        } catch (failure: OutOfMemoryError) {
            ConfigurationWriteResult.StorageFailure(failure)
        }
}
