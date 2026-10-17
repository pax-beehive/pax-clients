package net.paxtech.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.paxtech.workspace.data.AgentSession
import net.paxtech.workspace.data.ConversationPresence
import net.paxtech.workspace.data.Node

internal data class ConversationIdentity(
    val agentName: String = "Assistant",
    val deviceName: String = "",
    val presence: ConversationPresence = ConversationPresence(),
)

internal fun conversationIdentity(session: AgentSession, nodes: List<Node>): ConversationIdentity {
    val type = session.agent_type.trim()
    val name =
        when (type.lowercase(Locale.ROOT)) {
            "codex",
            "codex-acp" -> "Codex"
            "claude",
            "claude-code",
            "claude_code",
            "claude-agent-acp" -> "Claude"
            "gemini" -> "Gemini"
            "pi",
            "pi-acp" -> "Pi"
            "kiro" -> "Kiro"
            "" -> "Assistant"
            else -> type
        }
    val node = nodes.firstOrNull { it.node_id == session.node_id }
    return ConversationIdentity(
        name,
        node
            ?.name
            ?.trim()
            .orEmpty()
            .ifBlank { node?.hostname?.trim().orEmpty() }
            .ifBlank { "Unknown device" },
    )
}

@Composable
internal fun ConversationByline(identity: ConversationIdentity) {
    FlowRow(
        Modifier.padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IdentityPresence(
            WorkspaceSymbols.Agent,
            identity.agentName,
            "Agent",
            identity.presence.agentOnline,
            Modifier.alignByBaseline(),
        )
        if (identity.deviceName.isNotBlank())
            IdentityPresence(
                WorkspaceSymbols.Computer,
                identity.deviceName,
                "Device",
                identity.presence.deviceOnline,
                Modifier.alignByBaseline(),
            )
    }
}

private fun presenceLabel(online: Boolean?) =
    when (online) {
        true -> "Online"
        false -> "Offline"
        null -> "Unknown"
    }

@Composable
private fun IdentityPresence(
    icon: ImageVector,
    name: String,
    kind: String,
    online: Boolean?,
    modifier: Modifier,
) {
    val label = presenceLabel(online)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.widthIn(max = 300.dp).heightIn(min = 24.dp).semantics(mergeDescendants = true) {
            stateDescription = "$kind $label"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = muted)
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                name,
                Modifier.weight(1f, fill = false).alignByBaseline(),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = if (online == true) WorkspaceSuccess else muted)) {
                        append("• ")
                    }
                    append(label)
                },
                Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
    }
}

@Composable
internal fun ConversationDetails(identity: ConversationIdentity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session details") },
        text = {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Agent",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(identity.agentName)
                        Text(
                            presenceLabel(identity.presence.agentOnline),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(identity.deviceName)
                        Text(
                            presenceLabel(identity.presence.deviceOnline),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
