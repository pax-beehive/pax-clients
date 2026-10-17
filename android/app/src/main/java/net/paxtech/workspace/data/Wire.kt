package net.paxtech.workspace.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

fun JsonObject.text(key: String, fallback: String = "") =
    (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback

fun JsonObject.number(key: String, fallback: Long = 0) =
    (get(key) as? JsonPrimitive)?.longOrNull ?: fallback

fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())

fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

fun body(vararg fields: Pair<String, String>) = buildJsonObject {
    fields.forEach { (k, v) -> put(k, v) }
}

@Serializable
data class Credential(
    val origin: String,
    val userId: String,
    val email: String,
    val apiKey: String,
    val keyId: String,
    val region: String,
)

@Serializable
data class LoginCandidate(
    val origin: String,
    val region: String,
    val loginId: String,
    val code: String,
    val pollToken: String,
    val interval: Long,
)

@Serializable
data class LoginAttempt(
    val candidates: List<LoginCandidate>,
    val url: String,
    val expiresAt: Long,
    val selected: LoginCandidate? = null,
    val userId: String = "",
    val email: String = "",
)

@Serializable
data class RootKey(val account: String, val agentId: String, val epoch: Int, val encoded: String)

@Serializable
data class Pairing(
    val account: String,
    val agentId: String,
    val epoch: Int,
    val id: String,
    val deviceId: String,
    val privateKey: String,
    val publicKey: String,
    val secret: String,
)

@Serializable
data class VaultData(
    val credential: Credential? = null,
    val login: LoginAttempt? = null,
    val keys: List<RootKey> = emptyList(),
    val pairings: List<Pairing> = emptyList(),
    val oauth: OAuthPending? = null,
    val userSession: UserSession? = null,
    val pendingUpgrades: Map<String, PendingUpgrade> = emptyMap(),
)

@Serializable
data class OAuthPending(
    val state: String,
    val verifier: String,
    val url: String,
    val expiresAt: Long,
)

@Serializable data class UserSession(val subject: String, val refreshToken: String)

@Serializable
data class AgentSession(
    val session_id: String,
    val node_id: String,
    val agent_id: String,
    val name: String = "",
    val reported_name: String = "",
    val native_id: String = "",
    val preview: String = "",
    val runtime_status: String = "unknown",
    val transport: String = "manager",
    val workspace_roots: List<String> = emptyList(),
    val primary_project_id: String = "",
    val metadata: JsonObject = JsonObject(emptyMap()),
    val agent_type: String = "",
) {
    val title
        get() = name.ifBlank { reported_name.ifBlank { "Agent session" } }

    val encrypted
        get() = transport == "e2ee"
}

@Serializable data class Project(val project_id: String, val display_name: String)

@Serializable
data class Node(
    val node_id: String,
    val name: String = "",
    val hostname: String = "",
    val online: Boolean = false,
    val os: String = "",
    val arch: String = "",
    val paxd_version: String = "",
)

@Serializable
data class ApprovalOption(
    val option_id: String = "",
    val label: String = "",
    val decision: String = "",
    val scope: String = "",
)

@Serializable
data class Approval(
    val approval_id: String,
    val title: String = "Approval requested",
    val description: String = "",
    val status: String = "",
    val request_session_id: String = "",
    val request_node_id: String = "",
    val request_agent_id: String = "",
    val options: List<ApprovalOption> = emptyList(),
)

data class Workspace(
    val sessions: List<AgentSession> = emptyList(),
    val projects: List<Project> = emptyList(),
    val nodes: List<Node> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val hasMore: Boolean = false,
)
