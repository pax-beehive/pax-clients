package net.paxtech.workspace.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// Wire shapes mirror pax-console src/features/api/types.ts and manager routes
// routeUserSessionConfig / routeUserSessionConfigOption / routeUserSessionConfigRefresh /
// routeUserAgentPermissionCatalog / routeUserSessionPermission.

@Serializable
data class SessionConfigValue(
    val value: String,
    val name: String = "",
    val description: String = "",
    val group: String = "",
)

@Serializable
data class SessionConfigOption(
    val id: String,
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val type: String = "select",
    /** A string for `select`, a boolean for `boolean`. */
    val current_value: JsonElement? = null,
    val options: List<SessionConfigValue> = emptyList(),
) {
    val isBoolean
        get() = type == "boolean" || (current_value as? JsonPrimitive)?.booleanOrNull != null

    val currentText: String
        get() = (current_value as? JsonPrimitive)?.contentOrNull.orEmpty()

    val valueLabel: String
        get() =
            if (isBoolean) {
                if ((current_value as? JsonPrimitive)?.booleanOrNull == true) "On" else "Off"
            } else options.firstOrNull { it.value == currentText }?.name?.ifBlank { null } ?: currentText

    /** Agent "mode" options are permission modes; they are chosen via the permission catalog. */
    val isPermissionMode
        get() = category.equals("mode", true) || id.equals("mode", true)

    val isModel
        get() =
            category.equals("model", true) ||
                id.equals("model", true) ||
                id.equals("models", true)

    val isReasoning
        get() =
            type == "select" &&
                (category.equals("thought_level", true) || id.equals("reasoning_effort", true))
}

@Serializable
data class SessionLegacyModel(val id: String, val name: String = "", val description: String = "")

@Serializable
data class SessionLegacyModels(
    val current_model_id: String = "",
    val available: List<SessionLegacyModel> = emptyList(),
)

@Serializable
data class SessionConfiguration(
    val session_id: String = "",
    val options: List<SessionConfigOption> = emptyList(),
    val legacy_models: SessionLegacyModels? = null,
    val can_set: Boolean = false,
    val can_force_refresh: Boolean = false,
) {
    /** Options shown in the settings sheet; permission modes are handled separately. */
    val visibleOptions
        get() = options.filterNot { it.isPermissionMode }

    val modelLabel: String
        get() =
            options.firstOrNull { it.isModel }?.valueLabel
                ?: legacy_models?.let { legacy ->
                    legacy.available.firstOrNull { it.id == legacy.current_model_id }?.name
                        ?: legacy.current_model_id
                }.orEmpty()

    /** "Opus · High", matching the web composer's configuration summary. */
    val summary: String
        get() =
            listOf(modelLabel, options.firstOrNull { it.isReasoning }?.valueLabel.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}

@Serializable
data class AgentPermissionChoice(
    val choice_id: String,
    val label: String = "",
    val description: String = "",
    val kind: String = "agent",
    val risk: String = "",
    val requires_confirmation: Boolean = false,
)

@Serializable
data class AgentPermissionCatalog(
    val default_choice_id: String = "",
    val source: String = "",
    val stale: Boolean = false,
    val choices: List<AgentPermissionChoice> = emptyList(),
)

@Serializable
data class SessionPaxConfig(
    val cwd: String = "",
    val approval_mode: String = "",
    val permission_choice_id: String = "",
)

// ---- Permission choices: a line-for-line port of pax-console permission-catalog.ts ----

const val PAX_AUTO_APPROVE_CHOICE_ID = "pax:auto_approve"
const val PAX_MANUAL_CHOICE_ID = "pax:manual"

private val paxAutoApproveChoice =
    AgentPermissionChoice(
        PAX_AUTO_APPROVE_CHOICE_ID,
        "Auto approve (PAX)",
        "PAX answers permission requests automatically while the agent keeps its default permission configuration.",
        "pax",
    )

private val paxManualChoice =
    AgentPermissionChoice(
        PAX_MANUAL_CHOICE_ID,
        "Ask before tools",
        "PAX asks before responding to agent permission requests.",
        "pax",
    )

fun permissionChoicesFromCatalog(catalog: AgentPermissionCatalog?): List<AgentPermissionChoice> {
    val choices = catalog?.choices.orEmpty().distinctBy { it.choice_id }
    val native =
        choices.filter {
            it.kind == "agent" &&
                it.choice_id != PAX_AUTO_APPROVE_CHOICE_ID &&
                it.choice_id != PAX_MANUAL_CHOICE_ID
        }
    val reportedAuto = choices.firstOrNull { it.choice_id == PAX_AUTO_APPROVE_CHOICE_ID }
    return if (native.isEmpty()) listOf(reportedAuto ?: paxAutoApproveChoice, paxManualChoice)
    else listOf(reportedAuto ?: paxAutoApproveChoice) + native
}

fun defaultPermissionChoiceId(
    choices: List<AgentPermissionChoice>,
    approvalMode: String,
    defaultChoiceId: String = "",
): String {
    if (approvalMode == "auto_approve_all") return PAX_AUTO_APPROVE_CHOICE_ID
    val catalogDefault =
        defaultChoiceId.takeIf { it.isNotBlank() }?.let { id ->
            choices.firstOrNull { it.kind == "agent" && it.choice_id == id }
        }
    if (catalogDefault != null)
        return if (catalogDefault.requires_confirmation) PAX_MANUAL_CHOICE_ID
        else catalogDefault.choice_id
    val first = choices.firstOrNull { it.kind == "agent" }
    return if (first == null || first.requires_confirmation) PAX_MANUAL_CHOICE_ID
    else first.choice_id
}

/** The session's explicit choice wins; otherwise fall back like the web workbench does. */
fun currentPermissionChoiceId(
    choices: List<AgentPermissionChoice>,
    config: SessionPaxConfig?,
    catalog: AgentPermissionCatalog?,
): String {
    val selected = config?.permission_choice_id.orEmpty()
    if (selected.isNotBlank()) return selected
    return defaultPermissionChoiceId(
        choices,
        config?.approval_mode.orEmpty(),
        catalog?.default_choice_id.orEmpty(),
    )
}

// ---- Requests ----

private fun sessionPath(session: AgentSession) =
    arrayOf("nodes", session.node_id, "agents", session.agent_id, "sessions", session.session_id)

suspend fun WorkspaceRepository.sessionConfiguration(session: AgentSession): SessionConfiguration =
    wireJson.decodeFromJsonElement(get(*sessionPath(session), "configuration"))

suspend fun WorkspaceRepository.setSessionConfigOption(
    session: AgentSession,
    optionId: String,
    value: JsonPrimitive,
): SessionConfiguration =
    wireJson.decodeFromJsonElement(
        send(
            "PATCH",
            listOf(*sessionPath(session), "configuration", "options", optionId),
            buildJsonObject { put("value", value) },
        )
    )

suspend fun WorkspaceRepository.refreshSessionConfiguration(
    session: AgentSession
): SessionConfiguration =
    wireJson.decodeFromJsonElement(
        post(listOf(*sessionPath(session), "configuration", "refresh"), JsonObject(emptyMap()))
    )

suspend fun WorkspaceRepository.permissionCatalog(agentId: String): AgentPermissionCatalog =
    wireJson.decodeFromJsonElement(get("agents", agentId, "permission-catalog"))

suspend fun WorkspaceRepository.setSessionPermission(
    session: AgentSession,
    choiceId: String,
): AgentSession =
    wireJson.decodeFromJsonElement(
        post(
            listOf(*sessionPath(session), "permission"),
            buildJsonObject { put("permission_choice_id", choiceId) },
        )
    )
