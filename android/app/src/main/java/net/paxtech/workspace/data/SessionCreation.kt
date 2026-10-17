package net.paxtech.workspace.data

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import net.paxtech.workspace.network.userPath

@Serializable
data class SessionAgent(
    val agent_id: String,
    val node_id: String = "",
    val name: String = "",
    val agent_type: String = "",
    val online: Boolean = true,
) {
    val label
        get() = name.ifBlank { agent_type.ifBlank { agent_id } }
}

@Serializable
data class ProjectTarget(
    val target_id: String,
    val agent_id: String,
    val display_name: String = "",
    val cwd: String = "",
    val is_default: Boolean = false,
    val enabled: Boolean = false,
)

data class SessionOptions(
    val projects: List<Project>,
    val nodes: List<Node>,
    val agents: List<SessionAgent>,
)

data class SessionDraft(
    val id: String,
    val projectId: String = "",
    val nodeId: String = "",
    val agentId: String = "",
    val cwd: String = "",
    val name: String = "",
) {
    val validPath
        get() =
            cwd.trim().let {
                (it.startsWith("/") ||
                    Regex("^[A-Za-z]:[\\\\/].*").matches(it) ||
                    it.startsWith("\\\\")) &&
                    it.none { c -> c == '\u0000' || c == '\n' || c == '\r' }
            }
}

suspend fun WorkspaceRepository.sessionOptions(): SessionOptions = coroutineScope {
    val projects = async {
        wireJson.decodeFromJsonElement<List<Project>>(get("projects").jsonObject.array("projects"))
    }
    val nodes = wireJson.decodeFromJsonElement<List<Node>>(get("nodes").jsonObject.array("nodes"))
    val agents =
        nodes
            .filter { it.online }
            .map { node ->
                async {
                    wireJson
                        .decodeFromJsonElement<List<SessionAgent>>(
                            get("nodes", node.node_id, "agents").jsonObject.array("agents")
                        )
                        .map { it.copy(node_id = node.node_id) }
                }
            }
            .awaitAll()
            .flatten()
    SessionOptions(projects.await(), nodes, agents)
}

suspend fun WorkspaceRepository.projectTargets(projectId: String): List<ProjectTarget> =
    wireJson.decodeFromJsonElement(
        get("projects", projectId, "targets").jsonObject.array("targets")
    )

suspend fun WorkspaceRepository.findCreatedSession(draft: SessionDraft): AgentSession {
    check(draft.id.isNotBlank()) {
        "The server did not return a session ID. Refresh Sessions to check for the new session before creating another."
    }
    val result =
        get("nodes", draft.nodeId, "agents", draft.agentId, "sessions", draft.id).jsonObject
    // A failed multi-step server write may have created the identity but not its directory.
    check(result.obj("pax_config").text("cwd") == draft.cwd.trim()) {
        "The session exists but its working directory has not been confirmed."
    }
    return wireJson.decodeFromJsonElement<AgentSession>(result).also {
        check(it.encrypted || it.native_id.isNotBlank()) {
            "This session has not finished initializing on the agent. Refresh Sessions to check its status."
        }
        require(
            it.session_id == draft.id &&
                it.node_id == draft.nodeId &&
                it.agent_id == draft.agentId &&
                it.primary_project_id == draft.projectId
        )
    }
}

/**
 * Initialize through the conversation API before configuring the returned manager identity. The
 * session-record POST alone never starts an ACP session. This is the normal manager creation flow;
 * existing E2EE sessions continue to initialize through encrypted commands.
 */
suspend fun WorkspaceRepository.createSession(
    draft: SessionDraft,
    onAssigned: (String) -> Unit = {},
): AgentSession {
    require(draft.nodeId.isNotBlank() && draft.agentId.isNotBlank() && draft.validPath)
    val credential = credential()
    var assignedId = ""
    var complete = false
    api.events(
            api.request(
                credential.origin,
                userPath("nodes", draft.nodeId, "agents", draft.agentId, "conversation"),
                credential.apiKey,
                "POST",
                buildJsonObject {
                    put("initialize_only", true)
                    put("cwd", draft.cwd.trim())
                    if (draft.projectId.isNotBlank()) put("primary_project_id", draft.projectId)
                },
            )
        )
        .collect { event ->
            val data = wireJson.parseToJsonElement(event.data).jsonObject
            when (data.text("type")) {
                "session",
                "done" -> {
                    val id = data.text("session_id")
                    check(
                        id.isNotBlank() &&
                            data.text("node_id") == draft.nodeId &&
                            data.text("agent_id") == draft.agentId
                    ) {
                        "Unexpected session initialization response"
                    }
                    if (data.text("type") == "session") {
                        check(assignedId.isBlank() || assignedId == id) {
                            "Session identity changed during initialization"
                        }
                        assignedId = id
                        onAssigned(id)
                    } else {
                        check(assignedId == id) { "Session initialization was not confirmed" }
                        complete = true
                    }
                }
                "error" -> error(data.text("message", "Could not initialize session"))
            }
        }
    check(complete) {
        "Session initialization outcome is unknown. Check status before trying again."
    }
    // Apply the optional mobile name to the already initialized identity. No native ID is
    // fabricated; the server preserves the ACP binding established by initialize_only.
    val configured =
        wireJson.decodeFromJsonElement<AgentSession>(
            post(
                listOf("nodes", draft.nodeId, "agents", draft.agentId, "sessions"),
                buildJsonObject {
                    put("session_id", assignedId)
                    put("name", draft.name.trim().ifBlank { "Mobile session" })
                    put("source", "android")
                    if (draft.projectId.isNotBlank()) put("primary_project_id", draft.projectId)
                    putJsonArray("workspace_roots") { add(draft.cwd.trim()) }
                    putJsonObject("pax_config") { put("cwd", draft.cwd.trim()) }
                },
            )
        )
    check(
        configured.session_id == assignedId &&
            configured.node_id == draft.nodeId &&
            configured.agent_id == draft.agentId &&
            configured.native_id.isNotBlank()
    ) {
        "The initialized session could not be confirmed. Check status before trying again."
    }
    return configured
}
