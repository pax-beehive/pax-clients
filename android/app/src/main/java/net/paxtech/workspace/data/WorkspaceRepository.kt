package net.paxtech.workspace.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.network.*

@Singleton
class WorkspaceRepository @Inject constructor(val api: PaxApi, private val vault: Vault) {
    suspend fun credential() = vault.read().credential ?: error("Sign in to continue")

    suspend fun get(vararg path: String, query: Map<String, String> = emptyMap()): JsonElement {
        val c = credential()
        return api.json(api.request(c.origin, userPath(*path), c.apiKey, query = query))
    }

    suspend fun post(
        path: List<String>,
        body: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement {
        val c = credential()
        return api.json(
            api.request(
                c.origin,
                userPath(*path.toTypedArray()),
                c.apiKey,
                "POST",
                body,
                headers = headers,
            )
        )
    }

    suspend fun load(page: Int = 1): Workspace = coroutineScope {
        val sessions = async {
            get("sessions", query = mapOf("page_num" to "$page", "page_size" to "50")).jsonObject
        }
        val projects = async { get("projects").jsonObject.array("projects") }
        val nodes = async { get("nodes").jsonObject.array("nodes") }
        val approvals = async { get("approvals").jsonObject.array("approvals") }
        val result = sessions.await()
        Workspace(
            wireJson.decodeFromJsonElement(result.array("sessions")),
            wireJson.decodeFromJsonElement(projects.await()),
            wireJson.decodeFromJsonElement(nodes.await()),
            wireJson.decodeFromJsonElement(approvals.await()),
            result.array("sessions").size == 50,
        )
    }

    suspend fun decide(approval: Approval, option: ApprovalOption) {
        require(option.option_id.isNotBlank())
        post(
            listOf("approvals", approval.approval_id, "decision"),
            body(
                "approval_id" to approval.approval_id,
                "decision_option" to option.option_id,
                "user_id" to credential().userId,
            ),
        )
    }

    suspend fun resumeApproval(approval: Approval) {
        if (
            approval.request_node_id.isBlank() ||
                approval.request_agent_id.isBlank() ||
                approval.request_session_id.isBlank()
        )
            return
        val session =
            get(
                    "nodes",
                    approval.request_node_id,
                    "agents",
                    approval.request_agent_id,
                    "sessions",
                    approval.request_session_id,
                )
                .jsonObject
        if (session.text("transport") == "e2ee") return
        val c = credential()
        val body = buildJsonObject {
            put("session_id", approval.request_session_id)
            putJsonObject("resume") { put("approval_id", approval.approval_id) }
        }
        var complete = false
        api.events(
                api.request(
                    c.origin,
                    userPath(
                        "nodes",
                        approval.request_node_id,
                        "agents",
                        approval.request_agent_id,
                        "conversation",
                    ),
                    c.apiKey,
                    "POST",
                    body,
                )
            )
            .collect { event ->
                val data = wireJson.parseToJsonElement(event.data).jsonObject
                when (data.text("type")) {
                    "done",
                    "turn_done",
                    "interrupted" -> complete = true
                    "error" ->
                        error(data.text("message", "Approval saved; session could not resume"))
                }
            }
        check(complete) {
            "Approval saved; resume outcome unknown. Reconnect to inspect the session."
        }
    }
}
