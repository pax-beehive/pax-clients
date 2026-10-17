package net.paxtech.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.paxtech.workspace.session.ChatItem

internal data class ConversationEntry(val messages: List<ChatItem>) {
    val id
        get() = messages.first().id

    val isActivity
        get() = messages.first().role in setOf("tool", "thought")
}

/** Only adjacent activity from the same turn is grouped; replies and approvals stay visible. */
internal fun conversationEntries(messages: List<ChatItem>): List<ConversationEntry> {
    val groups = mutableListOf<MutableList<ChatItem>>()
    for (message in messages) {
        val previous = groups.lastOrNull()?.lastOrNull()
        if (
            message.role in setOf("tool", "thought") &&
                previous?.role in setOf("tool", "thought") &&
                previous?.turn == message.turn
        ) {
            groups.last().add(message)
        } else groups.add(mutableListOf(message))
    }
    return groups.map { ConversationEntry(it.toList()) }
}

internal fun activityStatus(messages: List<ChatItem>): String {
    val states = messages.map { it.status.lowercase() }
    return when {
        states.any { it in setOf("failed", "error") } -> "Failed"
        states.any { it in setOf("in_progress", "running", "streaming") } -> "Running"
        states.any { it in setOf("pending", "queued") } -> "Pending"
        states.any { it in setOf("cancelled", "canceled") } -> "Cancelled"
        states.isNotEmpty() && states.all { it in setOf("completed", "done", "success") } -> "Done"
        else -> ""
    }
}

@Composable
internal fun ConversationActivity(entry: ConversationEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val tools = entry.messages.filter { it.role == "tool" }
    val status = activityStatus(tools)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.messages
            .flatMap { it.attachments }
            .distinct()
            .forEach { attachment -> key(attachment) { AttachmentCard(attachment) } }
        ActivityDisclosure(
            title =
                "Activity · ${entry.messages.size} ${if (entry.messages.size == 1) "step" else "steps"}",
            subtitle = tools.lastOrNull()?.title?.ifBlank { "Tool operation" } ?: "Reasoning",
            status = status,
            expanded = expanded,
            modifier = Modifier.testTag("activity-group:${entry.id}"),
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(
            expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.messages.forEach { message ->
                    key(message.id) {
                        var details by rememberSaveable(message.id) { mutableStateOf(false) }
                        Column(Modifier.padding(start = 12.dp)) {
                            ActivityDisclosure(
                                title =
                                    if (message.role == "thought") "Reasoning"
                                    else message.title.ifBlank { "Tool operation" },
                                status = activityStatus(listOf(message)),
                                expanded = details,
                                modifier = Modifier.testTag("activity-step:${message.id}"),
                                onClick = { details = !details },
                            )
                            AnimatedVisibility(
                                details,
                                enter = expandVertically(tween(200)) + fadeIn(tween(140)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                            ) {
                                SelectionContainer {
                                    Text(
                                        message.text.ifBlank { "No additional details." },
                                        Modifier.fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily =
                                            if (message.role == "tool") FontFamily.Monospace
                                            else null,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDisclosure(
    title: String,
    subtitle: String? = null,
    status: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val arrow by
        animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "disclosure arrow")
    Surface(
        onClick = onClick,
        modifier =
            modifier.fillMaxWidth().semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (subtitle != null)
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (status.isNotBlank())
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            when (status) {
                                "Failed" -> MaterialTheme.colorScheme.error
                                "Done" -> WorkspaceSuccess
                                "Running" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
            }
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                null,
                Modifier.size(20.dp).graphicsLayer { rotationZ = arrow },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
