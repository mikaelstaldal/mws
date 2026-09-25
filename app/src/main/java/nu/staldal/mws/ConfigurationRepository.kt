package nu.staldal.mws

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    /** Emits the current record, then every change to it; `null` once it is removed. */
    fun observe(id: String): Flow<ConfiguredApp?> =
        apps.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

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

    /**
     * Clear step 1 (A§5.2): in one transaction retire the active generation, reserve the next
     * never-used one and enter CLEARING. [change] lets an origin-changing save store its new
     * snapshot in the same transaction. A record that is already CLEARING is returned unchanged,
     * so a retry never reserves a second generation or targets the reserved one.
     */
    suspend fun reserveNextGeneration(id: String,
        change: (ConfiguredApp) -> ConfiguredApp = { it }): GenerationReservation =
        try {
            database.withTransaction {
                val saved = dao.get(id) ?: return@withTransaction GenerationReservation.MissingApp
                if (saved.profileState == ProfileState.CLEARING) {
                    return@withTransaction GenerationReservation.Reserved(saved)
                }
                val reserved = change(saved).copy(id = saved.id, sortPosition = saved.sortPosition,
                    createdAt = saved.createdAt, profileState = ProfileState.CLEARING,
                    retiringProfileGeneration = saved.profileGeneration,
                    profileGeneration = saved.profileGeneration + 1)
                dao.update(reserved)
                GenerationReservation.Reserved(reserved)
            }
        } catch (failure: Exception) {
            storageFailure(failure)?.let { GenerationReservation.StorageFailure(it) } ?: throw failure
        }

    /** Clear step 4: only the recorded retiring generation can be finished, and only once. */
    suspend fun finishClearing(id: String, retiringGeneration: Long): ConfigurationWriteResult = write {
        database.withTransaction {
            val saved = dao.get(id) ?: return@withTransaction ConfigurationWriteResult.MissingApp
            if (saved.profileState == ProfileState.CLEARING &&
                saved.retiringProfileGeneration == retiringGeneration) {
                dao.update(saved.copy(profileState = ProfileState.ACTIVE,
                    retiringProfileGeneration = null))
            }
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
        } catch (failure: Exception) {
            storageFailure(failure)?.let { ConfigurationWriteResult.StorageFailure(it) } ?: throw failure
        } catch (failure: OutOfMemoryError) {
            ConfigurationWriteResult.StorageFailure(failure)
        }

    private fun storageFailure(failure: Exception): Throwable? = when (failure) {
        is SQLiteFullException, is SQLiteDiskIOException, is SQLiteOutOfMemoryException,
        is SQLiteCantOpenDatabaseException, is SQLiteReadOnlyDatabaseException,
        is SQLiteDatabaseLockedException -> failure
        else -> null
    }
}

sealed interface GenerationReservation {
    /** [app] is CLEARING with a non-null retiring generation. */
    data class Reserved(val app: ConfiguredApp) : GenerationReservation
    data object MissingApp : GenerationReservation
    data class StorageFailure(val cause: Throwable) : GenerationReservation
}
