package net.paxtech.workspace

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.ApiFailure

data class NewSessionState(
    val draft: SessionDraft = SessionDraft("sess_${UUID.randomUUID()}"),
    val options: SessionOptions? = null,
    val targets: List<ProjectTarget> = emptyList(),
    val targetId: String = "",
    val loading: Boolean = true,
    val creating: Boolean = false,
    val uncertain: Boolean = false,
    val error: String? = null,
) {
    val editable
        get() = !loading && !creating && !uncertain

    val canCreate
        get() =
            editable &&
                draft.validPath &&
                options?.nodes?.any { it.node_id == draft.nodeId && it.online } == true &&
                options.agents.any {
                    it.agent_id == draft.agentId && it.node_id == draft.nodeId && it.online
                }
}

/** Owns form state across resize/recreation and keeps uncertain mutations from being repeated. */
class NewSessionController(
    private val repo: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val onCreated: (AgentSession) -> Unit,
) {
    private val mutable = MutableStateFlow<NewSessionState?>(null)
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null

    fun open(projectId: String = "") {
        if (mutable.value != null) return
        mutable.value =
            NewSessionState(draft = SessionDraft("sess_${UUID.randomUUID()}", projectId))
        reload()
    }

    fun dismiss() {
        if (mutable.value?.creating == true) return
        loadJob?.cancel()
        mutable.value = null
    }

    fun reload() {
        val previous = mutable.value ?: return
        if (previous.creating || previous.uncertain) return
        loadJob?.cancel()
        mutable.value = previous.copy(loading = true, error = null)
        loadJob =
            scope.launch {
                try {
                    val options = repo.sessionOptions()
                    val project =
                        previous.draft.projectId
                            .takeIf { id -> options.projects.any { it.project_id == id } }
                            .orEmpty()
                    mutable.value =
                        previous.copy(
                            options = options,
                            draft = previous.draft.copy(projectId = project),
                            loading = true,
                        )
                    loadTargets(project)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it?.copy(loading = false, error = e.message ?: "Could not load devices")
                    }
                }
            }
    }

    fun project(id: String) {
        val current = mutable.value ?: return
        if (!current.editable) return
        loadJob?.cancel()
        mutable.value =
            current.copy(
                draft = current.draft.copy(projectId = id, cwd = ""),
                targets = emptyList(),
                targetId = "",
                loading = true,
                error = null,
            )
        loadJob =
            scope.launch {
                try {
                    loadTargets(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it?.copy(
                            loading = false,
                            error = e.message ?: "Could not load project targets",
                        )
                    }
                }
            }
    }

    private suspend fun loadTargets(projectId: String) {
        val targets =
            if (projectId.isBlank()) emptyList()
            else repo.projectTargets(projectId).filter { it.enabled }
        mutable.update { it?.copy(targets = targets, loading = false) }
        val current = mutable.value ?: return
        val suggested = targets.firstOrNull { it.is_default } ?: targets.singleOrNull()
        if (suggested != null) target(suggested.target_id)
        else if (current.draft.nodeId.isBlank())
            current.options?.nodes?.singleOrNull { it.online }?.let { node(it.node_id) }
    }

    fun target(id: String) {
        val current = mutable.value ?: return
        if (!current.editable) return
        val target = current.targets.firstOrNull { it.target_id == id } ?: return
        val agent = current.options?.agents?.firstOrNull { it.agent_id == target.agent_id }
        mutable.value =
            current.copy(
                targetId = id,
                error = null,
                draft =
                    current.draft.copy(
                        nodeId = agent?.node_id.orEmpty(),
                        agentId = agent?.agent_id.orEmpty(),
                        cwd = target.cwd,
                    ),
            )
    }

    fun node(id: String) = edit { current ->
        val agent =
            current.options?.agents?.filter { it.node_id == id && it.online }?.singleOrNull()
        current.copy(
            targetId = "",
            draft = current.draft.copy(nodeId = id, agentId = agent?.agent_id.orEmpty(), cwd = ""),
        )
    }

    fun agent(id: String) = edit { it.copy(targetId = "", draft = it.draft.copy(agentId = id)) }

    fun cwd(value: String) = edit { it.copy(draft = it.draft.copy(cwd = value)) }

    fun name(value: String) = edit { it.copy(draft = it.draft.copy(name = value)) }

    private fun edit(change: (NewSessionState) -> NewSessionState) {
        mutable.update { if (it?.editable == true) change(it).copy(error = null) else it }
    }

    fun create() {
        val current = mutable.value ?: return
        if (!current.canCreate && !(current.uncertain && !current.creating)) return
        mutable.value = current.copy(creating = true, error = null)
        scope.launch {
            try {
                val session =
                    if (current.uncertain) repo.findCreatedSession(current.draft)
                    else repo.createSession(current.draft)
                mutable.value = null
                onCreated(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val uncertain =
                    current.uncertain || e !is ApiFailure || e.status >= 500 || e.status == 408
                mutable.update {
                    it?.copy(
                        creating = false,
                        uncertain = uncertain,
                        error =
                            if (uncertain)
                                "Creation could not be confirmed. Check status before starting another session."
                            else e.message ?: "Could not create session",
                    )
                }
            }
        }
    }
}
