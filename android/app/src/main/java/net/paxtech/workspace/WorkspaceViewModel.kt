package net.paxtech.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.auth.UserAuth
import net.paxtech.workspace.crypto.DevicePairing
import net.paxtech.workspace.data.*
import net.paxtech.workspace.session.SessionRuntime

data class WorkspaceState(
    val loading: Boolean = true,
    val credential: Credential? = null,
    val login: OAuthPending? = null,
    val needsRegion: Boolean = false,
    val workspace: Workspace = Workspace(),
    val error: String? = null,
    val selected: AgentSession? = null,
    val pairing: Pairing? = null,
    val working: Boolean = false,
    val page: Int = 1,
)

@HiltViewModel
class WorkspaceViewModel
@Inject
constructor(
    private val vault: Vault,
    private val auth: UserAuth,
    private val repo: WorkspaceRepository,
    private val pairingService: DevicePairing,
    private val runtime: SessionRuntime,
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    val session = runtime.state
    val newProject =
        NewProjectController(
            repo,
            viewModelScope,
            onCreated = { created ->
                refreshJob?.cancel()
                mutable.update { old ->
                    old.copy(
                        loading = false,
                        error = null,
                        workspace =
                            old.workspace.copy(
                                projects =
                                    listOf(created) +
                                        old.workspace.projects.filterNot {
                                            it.project_id == created.project_id
                                        }
                            ),
                    )
                }
            },
            onUncertainDismissed = {
                refreshJob?.cancel()
                refreshJob = null
                refresh()
            },
        )
    val newSession =
        NewSessionController(repo, viewModelScope) { created ->
            mutable.update {
                it.copy(
                    workspace =
                        it.workspace.copy(
                            sessions =
                                listOf(created) +
                                    it.workspace.sessions.filterNot { old ->
                                        old.session_id == created.session_id
                                    }
                        )
                )
            }
            open(created)
        }
    private var readJob: Job? = null
    private var sendJob: Job? = null
    private var loginJob: Job? = null
    private var refreshJob: Job? = null
    private var monitorJob: Job? = null
    private var restoreJob: Job? = null
    private var foreground = false

    init {
        restoreJob = launchAction {
            val credential = auth.restore()
            val saved = vault.read()
            mutable.update {
                it.copy(
                    credential = credential,
                    login = saved.oauth,
                    needsRegion = credential == null && saved.userSession != null,
                    loading = false,
                )
            }
            if (credential != null) refresh()
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            if (state.value.credential != null && state.value.login == null) {
                refresh()
                reconnect()
            }
            if (monitorJob?.isActive != true)
                monitorJob =
                    viewModelScope.launch {
                        while (isActive) {
                            delay(10000)
                            if (state.value.credential != null && state.value.login == null) {
                                try {
                                    val approvals =
                                        wireJson.decodeFromJsonElement<List<Approval>>(
                                            repo.get("approvals").jsonObject.array("approvals")
                                        )
                                    mutable.update {
                                        it.copy(
                                            workspace = it.workspace.copy(approvals = approvals)
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {}
                            }
                        }
                    }
        } else {
            readJob?.cancel()
            refreshJob?.cancel()
            monitorJob?.cancel()
        }
    }

    private fun launchAction(block: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val saved = vault.read()
                mutable.update {
                    it.copy(
                        error = e.message ?: "Request failed",
                        loading = false,
                        working = false,
                        login = saved.oauth,
                        credential = if (saved.userSession == null) null else it.credential,
                        needsRegion = saved.userSession != null && saved.credential == null,
                    )
                }
            }
        }

    fun startLogin() {
        if (state.value.working) return
        mutable.update { it.copy(working = true, error = null) }
        launchAction {
            val attempt = auth.start()
            mutable.update { it.copy(login = attempt, working = false) }
        }
    }

    fun completeLogin(callback: String) {
        if (loginJob?.isActive == true) return
        loginJob = launchAction {
            restoreJob?.join()
            mutable.update { it.copy(working = true, error = null) }
            val credential = auth.finish(callback)
            mutable.update {
                it.copy(
                    credential = credential,
                    login = null,
                    error = null,
                    loading = false,
                    working = false,
                    needsRegion = credential == null,
                )
            }
            if (credential != null) refresh()
        }
    }

    fun chooseRegion(region: String) {
        if (state.value.working) return
        mutable.update { it.copy(working = true, error = null) }
        launchAction {
            val credential = auth.bootstrap(region)
            mutable.update {
                it.copy(credential = credential, needsRegion = credential == null, working = false)
            }
            if (credential != null) refresh()
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        launchAction {
            auth.cancel()
            mutable.value = WorkspaceState(loading = false)
        }
    }

    fun refresh(more: Boolean = false) {
        if (refreshJob?.isActive == true || state.value.credential == null) return
        mutable.update { it.copy(loading = true, error = null) }
        refreshJob = launchAction {
            val page = if (more) state.value.page + 1 else 1
            val loaded = repo.load(page)
            mutable.update { old ->
                old.copy(
                    workspace =
                        if (more)
                            loaded.copy(
                                sessions =
                                    (old.workspace.sessions + loaded.sessions).distinctBy {
                                        it.session_id
                                    }
                            )
                        else loaded,
                    loading = false,
                    page = page,
                )
            }
        }
    }

    fun open(target: AgentSession) {
        readJob?.cancel()
        sendJob?.cancel()
        mutable.update { it.copy(selected = target, pairing = null, error = null) }
        reconnect()
    }

    fun close() {
        readJob?.cancel()
        sendJob?.cancel()
        mutable.update { it.copy(selected = null, pairing = null) }
        refresh()
    }

    fun reconnect() {
        val target = state.value.selected ?: return
        val previous = readJob
        readJob = launchAction {
            previous?.cancelAndJoin()
            sendJob?.cancelAndJoin()
            val current =
                if (target.encrypted && target.native_id.isBlank()) {
                    wireJson.decodeFromJsonElement<AgentSession>(
                        repo.get(
                            "nodes",
                            target.node_id,
                            "agents",
                            target.agent_id,
                            "sessions",
                            target.session_id,
                        )
                    )
                } else target
            withContext(Dispatchers.Default) { runtime.run(current) }
        }
    }

    fun send(text: String): String? {
        if (
            sendJob?.isActive == true ||
                session.value.busy ||
                session.value.connection != "Connected"
        )
            return null
        val requestId = "prompt_${UUID.randomUUID()}"
        sendJob = launchAction {
            withContext(Dispatchers.Default) { runtime.send(text, requestId) }
        }
        return "user:$requestId"
    }

    fun stop() {
        launchAction { withContext(Dispatchers.Default) { runtime.stop() } }
    }

    fun older() {
        launchAction { withContext(Dispatchers.Default) { runtime.loadOlder() } }
    }

    fun beginPairing() {
        launchAction {
            val target = state.value.selected ?: return@launchAction
            val pending = pairingService.start(target.agent_id)
            mutable.update { it.copy(pairing = pending) }
        }
    }

    fun finishPairing() {
        launchAction {
            val pending = state.value.pairing ?: return@launchAction
            pairingService.finish(pending)
            mutable.update { it.copy(pairing = null, error = null) }
            reconnect()
        }
    }

    fun pairingCommand(pending: Pairing) = pairingService.command(pending)

    fun decide(approval: Approval, option: ApprovalOption) {
        if (state.value.working) return
        mutable.update { it.copy(working = true, error = null) }
        launchAction {
            repo.decide(approval, option)
            mutable.update { it.copy(working = false) }
            refresh()
            repo.resumeApproval(approval)
        }
    }

    fun signOut() {
        if (newSession.state.value?.creating == true || newProject.state.value?.creating == true)
            return
        newProject.dismiss()
        newSession.dismiss()
        launchAction {
            readJob?.cancelAndJoin()
            sendJob?.cancelAndJoin()
            refreshJob?.cancelAndJoin()
            loginJob?.cancelAndJoin()
            auth.signOut()
            mutable.value = WorkspaceState(loading = false)
        }
    }
}
