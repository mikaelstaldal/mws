package nu.staldal.mws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class EditorMaintenanceState(
    val clearing: Boolean = false,
    val removing: Boolean = false,
    val clearResult: ClearResult? = null,
    val removalResult: RemovalResult? = null,
    val removalFailed: Boolean = false,
)

/** Main-thread entry points; shared per app so recreation cannot duplicate work or lose outcomes. */
class EditorMaintenance(
    private val profiles: ProfileManager,
    private val repository: ConfigurationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val states = mutableMapOf<String, MutableStateFlow<EditorMaintenanceState>>()

    private fun mutableState(id: String) = states.getOrPut(id) { MutableStateFlow(EditorMaintenanceState()) }
    fun state(id: String): StateFlow<EditorMaintenanceState> = mutableState(id)

    fun clear(id: String) {
        val state = mutableState(id)
        if (state.value.clearing || state.value.removing || state.value.removalResult != null) return
        state.value = state.value.copy(clearing = true, clearResult = null, removalFailed = false)
        scope.launch {
            val result = try { profiles.clear(id) }
            catch (failure: Exception) { ClearResult.Failed(failure) }
            state.value = state.value.copy(clearing = false, clearResult = result)
        }
    }

    fun remove(id: String) {
        val state = mutableState(id)
        if (state.value.removing || state.value.removalResult != null) return
        state.value = state.value.copy(removing = true, removalFailed = false)
        scope.launch {
            val result = try { profiles.remove(id) }
            catch (_: Exception) {
                // A tombstone cleanup can fail after the configuration was already deleted.
                val gone = try { repository.get(id) == null } catch (_: Exception) { false }
                if (gone) RemovalResult.ProfileDeletionPending else null
            }
            state.value = state.value.copy(removing = false, removalResult = result,
                removalFailed = result == null)
        }
    }
}
