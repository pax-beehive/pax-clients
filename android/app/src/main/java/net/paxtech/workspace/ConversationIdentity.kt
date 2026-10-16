package net.paxtech.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.paxtech.workspace.data.AgentSession
import net.paxtech.workspace.data.Node

internal data class ConversationIdentity(
    val agentName: String = "Assistant",
    val deviceName: String = "",
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
    val accent =
        when (identity.agentName) {
            "Codex" -> MaterialTheme.colorScheme.secondary
            "Claude" -> MaterialTheme.colorScheme.tertiary
            "Assistant" -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        }
    FlowRow(
        Modifier.padding(bottom = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(WorkspaceSymbols.Agent, null, Modifier.size(16.dp), tint = accent)
            Text(
                identity.agentName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (identity.deviceName.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    WorkspaceSymbols.Computer,
                    null,
                    Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    identity.deviceName,
                    Modifier.widthIn(max = 220.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(identity.deviceName)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
