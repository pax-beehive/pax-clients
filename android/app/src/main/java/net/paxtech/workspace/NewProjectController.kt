package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.ApiFailure

data class NewProjectState(
    val name: String = "",
    val creating: Boolean = false,
    val uncertain: Boolean = false,
    val error: String? = null,
) {
    val tooLong
        get() = name.trim().toByteArray(Charsets.UTF_8).size > 100

    val canCreate
        get() = name.isNotBlank() && !tooLong && !creating && !uncertain
}

/** Project IDs are assigned by Manager; an ambiguous POST must never be silently retried. */
class NewProjectController(
    private val repo: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val onCreated: (Project) -> Unit,
    private val onUncertainDismissed: () -> Unit,
) {
    private val mutable = MutableStateFlow<NewProjectState?>(null)
    val state = mutable.asStateFlow()

    fun open() {
        if (mutable.value == null) mutable.value = NewProjectState()
    }

    fun name(value: String) {
        val old = mutable.value ?: return
        if (!old.creating && !old.uncertain) mutable.value = old.copy(name = value, error = null)
    }

    fun dismiss() {
        val old = mutable.value ?: return
        if (old.creating) return
        mutable.value = null
        if (old.uncertain) onUncertainDismissed()
    }

    fun create() {
        val draft = mutable.value?.takeIf { it.canCreate } ?: return
        mutable.value = draft.copy(creating = true, error = null)
        scope.launch {
            try {
                val result =
                    repo
                        .post(listOf("projects"), body("display_name" to draft.name.trim()))
                        .jsonObject
                val created = wireJson.decodeFromJsonElement<Project>(result.getValue("project"))
                check(created.project_id.isNotBlank()) { "Missing project identity" }
                mutable.value = null
                onCreated(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val uncertain = e !is ApiFailure || e.status >= 500 || e.status == 408
                mutable.value =
                    draft.copy(
                        creating = false,
                        uncertain = uncertain,
                        error =
                            if (uncertain)
                                "Creation may have completed. Return to Projects and check the refreshed list before creating again."
                            else e.message ?: "Could not create project",
                    )
            }
        }
    }
}
