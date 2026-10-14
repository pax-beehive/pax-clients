package net.paxtech.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.auth.DeviceLogin
import net.paxtech.workspace.crypto.DevicePairing
import net.paxtech.workspace.data.*
import net.paxtech.workspace.session.SessionRuntime

data class WorkspaceState(
    val loading: Boolean = true,
    val credential: Credential? = null,
    val login: LoginAttempt? = null,
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
    private val auth: DeviceLogin,
    private val repo: WorkspaceRepository,
    private val pairingService: DevicePairing,
    private val runtime: SessionRuntime,
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    val session = runtime.state
    private var readJob: Job? = null
    private var sendJob: Job? = null
    private var loginJob: Job? = null
    private var refreshJob: Job? = null
    private var monitorJob: Job? = null

    init {
        launchAction {
            val saved = vault.read()
            mutable.update {
                it.copy(credential = saved.credential, login = saved.login, loading = false)
            }
            if (saved.login != null) pollLogin() else if (saved.credential != null) refresh()
        }
    }

    fun setForeground(value: Boolean) {
        if (value) {
            if (state.value.login != null) pollLogin()
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
            loginJob?.cancel()
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
                mutable.update {
                    it.copy(error = e.message ?: "Request failed", loading = false, working = false)
                }
            }
        }

    fun startLogin() {
        if (state.value.working) return
        mutable.update { it.copy(working = true, error = null) }
        launchAction {
            val attempt = auth.start()
            mutable.update { it.copy(login = attempt, working = false) }
            pollLogin()
        }
    }

    fun pollLogin() {
        if (loginJob?.isActive == true) return
        loginJob = launchAction {
            val credential = auth.finish()
            mutable.update {
                it.copy(credential = credential, login = null, error = null, loading = false)
            }
            refresh()
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

    fun send(text: String) {
        if (sendJob?.isActive != true)
            sendJob = launchAction { withContext(Dispatchers.Default) { runtime.send(text) } }
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
