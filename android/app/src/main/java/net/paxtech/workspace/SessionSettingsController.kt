package net.paxtech.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import net.paxtech.workspace.data.*

data class SessionSettingsState(
    val session: AgentSession,
    val configuration: SessionConfiguration? = null,
    val catalog: AgentPermissionCatalog? = null,
    val loading: Boolean = true,
    val pendingOption: String = "",
    val pendingPermission: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val choices
        get() = permissionChoicesFromCatalog(catalog)

    val permissionChoiceId
        get() = currentPermissionChoiceId(choices, session.pax_config, catalog)

    val permissionLabel: String
        get() =
            choices.firstOrNull { it.choice_id == permissionChoiceId }?.label?.ifBlank { null }
                ?: if (session.pax_config?.approval_mode == "auto_approve_all") "Auto approve"
                else "Ask"

    /** "Opus · High · Ask before tools", like the web composer's settings button. */
    val summary: String
        get() =
            listOf(if (session.encrypted) "" else configuration?.summary.orEmpty(), permissionLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}

/** Model, reasoning and other agent options plus the PAX permission choice for one session. */
class SessionSettingsController(
    private val repo: WorkspaceRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<SessionSettingsState?>(null)
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null

    fun clear() {
        loadJob?.cancel()
        mutable.value = null
    }

    fun open(session: AgentSession) {
        if (mutable.value?.session?.session_id == session.session_id) return reload()
        loadJob?.cancel()
        mutable.value = SessionSettingsState(session)
        reload()
    }

    /** Options appear once the agent has started the session, so re-read when the sheet opens. */
    fun reload() {
        val current = mutable.value ?: return
        loadJob?.cancel()
        mutable.update { it?.copy(loading = true, error = null) }
        loadJob =
            scope.launch {
                try {
                    coroutineScope {
                        val catalog = async { repo.permissionCatalog(current.session.agent_id) }
                        val configuration =
                            if (current.session.encrypted) null
                            else async { repo.sessionConfiguration(current.session) }
                        val loaded = configuration?.await()
                        val permissions = catalog.await()
                        mutable.update { state ->
                            state?.let {
                                it.copy(
                                    configuration = loaded ?: it.configuration,
                                    catalog = permissions,
                                    loading = false,
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it?.copy(loading = false, error = message(e)) }
                }
            }
    }

    fun setOption(optionId: String, value: JsonPrimitive) {
        val current = mutable.value ?: return
        if (current.pendingOption.isNotEmpty()) return
        mutable.update { it?.copy(pendingOption = optionId, error = null) }
        scope.launch {
            try {
                val updated = repo.setSessionConfigOption(current.session, optionId, value)
                mutable.update { it?.copy(configuration = updated, pendingOption = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it?.copy(pendingOption = "", error = message(e)) }
            }
        }
    }

    fun refresh() {
        val current = mutable.value ?: return
        if (current.refreshing) return
        mutable.update { it?.copy(refreshing = true, error = null) }
        scope.launch {
            try {
                val updated = repo.refreshSessionConfiguration(current.session)
                mutable.update { it?.copy(configuration = updated, refreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it?.copy(refreshing = false, error = message(e)) }
            }
        }
    }

    fun setPermission(choiceId: String, onSaved: (AgentSession) -> Unit = {}) {
        val current = mutable.value ?: return
        if (current.pendingPermission || choiceId == current.permissionChoiceId) return
        mutable.update { it?.copy(pendingPermission = true, error = null) }
        scope.launch {
            try {
                val saved = repo.setSessionPermission(current.session, choiceId)
                // Keep fields the response may omit; pax_config is what changed.
                val session = current.session.copy(pax_config = saved.pax_config)
                mutable.update { it?.copy(session = session, pendingPermission = false) }
                onSaved(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it?.copy(pendingPermission = false, error = message(e)) }
            }
        }
    }

    private fun message(e: Exception) =
        e.message?.takeIf { it.isNotBlank() } ?: "Settings could not be updated."
}
