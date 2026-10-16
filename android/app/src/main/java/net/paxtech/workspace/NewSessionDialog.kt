package net.paxtech.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun NewSessionDialog(state: NewSessionState, controller: NewSessionController) {
    Dialog(
        onDismissRequest = controller::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("New session", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Choose where your agent will work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    if (state.loading || state.creating)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let {
                        ErrorNotice(it)
                        if (!state.uncertain && !state.creating)
                            TextButton(onClick = controller::reload) { Text("Reload options") }
                    }
                    val options = state.options
                    if (options != null) {
                        SessionChoice(
                            "Project",
                            options.projects
                                .firstOrNull { it.project_id == state.draft.projectId }
                                ?.display_name ?: "No project",
                            state.editable,
                            listOf(Choice("", "No project")) +
                                options.projects.map { Choice(it.project_id, it.display_name) },
                            controller::project,
                        )
                        if (state.targets.isNotEmpty()) {
                            SessionChoice(
                                "Project target",
                                state.targets
                                    .firstOrNull { it.target_id == state.targetId }
                                    ?.let { it.display_name.ifBlank { it.cwd } }
                                    ?: "Choose a target",
                                state.editable,
                                state.targets.map { target ->
                                    val available =
                                        options.agents.any {
                                            it.agent_id == target.agent_id && it.online
                                        }
                                    Choice(
                                        target.target_id,
                                        target.display_name.ifBlank { target.cwd } +
                                            if (available) "" else " · Unavailable",
                                        available,
                                    )
                                },
                                controller::target,
                            )
                        }
                        SessionChoice(
                            "Device",
                            options.nodes
                                .firstOrNull { it.node_id == state.draft.nodeId }
                                ?.let { it.name.ifBlank { it.hostname.ifBlank { it.node_id } } }
                                ?: "Select device",
                            state.editable,
                            options.nodes.map {
                                Choice(
                                    it.node_id,
                                    it.name.ifBlank { it.hostname.ifBlank { it.node_id } } +
                                        if (it.online) "" else " · Offline",
                                    it.online,
                                )
                            },
                            controller::node,
                        )
                        val agents = options.agents.filter { it.node_id == state.draft.nodeId }
                        SessionChoice(
                            "Agent",
                            agents.firstOrNull { it.agent_id == state.draft.agentId }?.label
                                ?: "Select agent",
                            state.editable && state.draft.nodeId.isNotBlank(),
                            agents.map {
                                Choice(
                                    it.agent_id,
                                    it.label + if (it.online) "" else " · Offline",
                                    it.online,
                                )
                            },
                            controller::agent,
                        )
                        if (options.nodes.none { it.online })
                            Text(
                                "No devices are online. Connect a computer running Pax, then reload options.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        else if (state.draft.nodeId.isNotBlank() && agents.none { it.online })
                            Text(
                                "No agents are available on this device. Start an agent on the computer, then reload options.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        OutlinedTextField(
                            state.draft.cwd,
                            controller::cwd,
                            label = { Text("Working directory") },
                            placeholder = { Text("/path/to/project") },
                            singleLine = true,
                            enabled = state.editable,
                            isError = state.draft.cwd.isNotBlank() && !state.draft.validPath,
                            supportingText = { Text("Absolute path on the selected computer") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            state.draft.name,
                            controller::name,
                            label = { Text("Session name (optional)") },
                            singleLine = true,
                            enabled = state.editable,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (
                            options.nodes.none { it.online } ||
                                (state.draft.nodeId.isNotBlank() && agents.none { it.online })
                        )
                            TextButton(onClick = controller::reload, enabled = state.editable) {
                                Text("Reload options")
                            }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = controller::dismiss, enabled = !state.creating) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = controller::create,
                        enabled = state.canCreate || (state.uncertain && !state.creating),
                        modifier = Modifier.testTag("create-session"),
                    ) {
                        Text(
                            if (state.creating) "Please wait…"
                            else if (state.uncertain) "Check status" else "Create session"
                        )
                    }
                }
            }
        }
    }
}

private data class Choice(val id: String, val label: String, val enabled: Boolean = true)

@Composable
private fun SessionChoice(
    label: String,
    selected: String,
    enabled: Boolean,
    choices: List<Choice>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("choose-$label"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(selected, Modifier.weight(1f))
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        enabled = choice.enabled,
                        onClick = {
                            expanded = false
                            onSelect(choice.id)
                        },
                    )
                }
            }
        }
    }
}
