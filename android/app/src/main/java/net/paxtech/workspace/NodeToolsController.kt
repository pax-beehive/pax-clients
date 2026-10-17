package net.paxtech.workspace

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*

data class NodeToolsState(
    val node: Node,
    val latest: String = "",
    val loading: Boolean = true,
    val pending: PendingUpgrade? = null,
    val message: String = "",
    val error: String? = null,
)

class NodeToolsController(
    private val repo: WorkspaceRepository,
    private val vault: Vault,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<NodeToolsState?>(null)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var ownerKey = ""

    fun dismiss() {
        job?.cancel()
        mutable.value = null
    }

    fun open(node: Node) {
        dismiss()
        mutable.value = NodeToolsState(node)
        job =
            scope.launch {
                try {
                    val account = repo.credential().userId
                    ensureActive()
                    ownerKey = account + ":" + node.node_id
                    val pending = vault.read().pendingUpgrades[ownerKey]
                    ensureActive()
                    mutable.update { it?.copy(pending = pending) }
                    if (pending != null) {
                        poll(node.node_id, pending)
                        return@launch
                    }
                    val fresh =
                        wireJson.decodeFromJsonElement<Node>(repo.get("nodes", node.node_id))
                    ensureActive()
                    mutable.update { it?.copy(node = fresh) }
                    val latest = repo.latestPaxd(fresh)
                    ensureActive()
                    mutable.update { it?.copy(latest = latest, loading = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it?.copy(loading = false, error = e.message) }
                }
            }
    }

    fun upgrade() {
        val current = mutable.value ?: return
        if (
            current.loading ||
                current.pending != null ||
                current.latest.isBlank() ||
                !current.node.online
        )
            return
        job?.cancel()
        val requestOwner = ownerKey
        val pending = PendingUpgrade("upgrade_${UUID.randomUUID()}", current.latest)
        mutable.value =
            current.copy(
                loading = true,
                pending = pending,
                error = null,
                message = "Requesting upgrade",
            )
        job =
            scope.launch {
                try {
                    // Persist before dispatch: a lost response must not create a second command.
                    try {
                        vault.update {
                            it.copy(
                                pendingUpgrades = it.pendingUpgrades + (requestOwner to pending)
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update {
                            it?.copy(
                                loading = false,
                                pending = null,
                                error = "Could not save upgrade request. Nothing was sent.",
                            )
                        }
                        return@launch
                    }
                    ensureActive()
                    val ack =
                        repo
                            .post(
                                listOf("nodes", current.node.node_id, "daemon", "upgrade"),
                                buildJsonObject {
                                    put("command_id", pending.commandId)
                                    put("version", pending.version)
                                    put("tag", "stable")
                                    put("mode", "when_idle")
                                },
                            )
                            .jsonObject
                    val progress = upgradeProgress(ack)
                    if (progress.terminal) {
                        finish(progress)
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (
                        e is net.paxtech.workspace.network.ApiFailure &&
                            e.status in 400..499 &&
                            e.status !in setOf(408, 429)
                    ) {
                        finish(
                            UpgradeProgress(
                                e.message ?: "Upgrade request was rejected",
                                terminal = true,
                            )
                        )
                        return@launch
                    }
                    mutable.update {
                        it?.copy(
                            message = "Request outcome unknown. Checking the same upgrade.",
                            error = e.message,
                        )
                    }
                }
                poll(current.node.node_id, pending)
            }
    }

    fun check() {
        val current = mutable.value ?: return
        val pending = current.pending ?: return
        job?.cancel()
        job = scope.launch { poll(current.node.node_id, pending) }
    }

    private suspend fun finish(progress: UpgradeProgress) {
        val requestOwner = ownerKey
        vault.update { it.copy(pendingUpgrades = it.pendingUpgrades - requestOwner) }
        kotlin.coroutines.coroutineContext.ensureActive()
        mutable.update { old ->
            old?.copy(
                loading = false,
                pending = null,
                message = progress.message,
                error = null,
                node =
                    if (progress.succeeded)
                        old.node.copy(paxd_version = old.pending?.version ?: old.node.paxd_version)
                    else old.node,
            )
        }
    }

    private suspend fun poll(node: String, pending: PendingUpgrade) {
        mutable.update { it?.copy(loading = true, error = null) }
        repeat(120) {
            try {
                val result =
                    repo.get("nodes", node, "daemon", "commands", pending.commandId).jsonObject
                kotlin.coroutines.coroutineContext.ensureActive()
                val progress = upgradeProgress(result)
                if (progress.terminal) {
                    finish(progress)
                    return
                }
                mutable.update {
                    it?.copy(
                        message = progress.message,
                        error = result.obj("error").text("message").ifBlank { null },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it?.copy(message = "Waiting for the device to reconnect", error = null)
                }
            }
            delay(3000)
        }
        mutable.update {
            it?.copy(
                loading = false,
                message =
                    "Upgrade is not confirmed yet. Check status again; no new request will be sent.",
            )
        }
    }
}
