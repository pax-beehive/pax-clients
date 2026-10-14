package net.paxtech.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.paxtech.workspace.data.Approval
import net.paxtech.workspace.session.ChatItem

/** Keep keystrokes local: editing a draft must not invalidate the session or its timeline. */
@Composable
internal fun MessageComposer(
    connection: String,
    busy: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(connection) {
        if (connection == "Needs reconnect" && draft.isBlank()) draft = submitted
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            error?.let { ErrorNotice(it) }
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
                    TextField(
                        draft,
                        { draft = it },
                        placeholder = { Text("Message your agent") },
                        modifier = Modifier.weight(1f).testTag("message-composer"),
                        maxLines = 6,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                    FilledIconButton(
                        onClick = {
                            if (busy) onStop()
                            else {
                                val text = draft
                                submitted = text
                                draft = ""
                                onSend(text)
                            }
                        },
                        enabled = busy || (draft.isNotBlank() && connection == "Connected"),
                        modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                        shape = CircleShape,
                    ) {
                        Icon(
                            if (busy) Icons.Outlined.Close else WorkspaceSymbols.Send,
                            if (busy) "Stop" else "Send",
                            Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Index zero is the conversation tail. Growth and IME resizing stay bottom-anchored, while
 * LazyColumn's stable keys preserve the reader's position when browsing older messages.
 */
@Composable
internal fun ConversationTimeline(
    messages: List<ChatItem>,
    connection: String,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    onOlder: () -> Unit,
    modifier: Modifier = Modifier,
    approvals: List<Approval> = emptyList(),
    approval: @Composable (Approval) -> Unit = {},
    list: LazyListState = rememberLazyListState(),
) {
    val atTail by remember(list) { derivedStateOf { !list.canScrollBackward } }
    // Capture the old layout before the new tail is measured. Stable-key anchoring otherwise
    // keeps the previous message visible even when a reader was following the conversation.
    val followNewTail = atTail
    LaunchedEffect(messages.lastOrNull()?.id, approvals.lastOrNull()?.approval_id) {
        if (followNewTail) list.scrollToItem(0)
    }
    LazyColumn(
        modifier.testTag("conversation-timeline"),
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(
            approvals.asReversed(),
            key = { "approval:${it.approval_id}" },
            contentType = { "approval-card" },
        ) {
            approval(it)
        }
        items(messages.asReversed(), key = { it.id }, contentType = { it.role }) { MessageCard(it) }
        if (messages.isEmpty())
            item {
                Text(
                    if (connection == "Connected") "Send a message to continue this session."
                    else "Loading session…"
                )
            }
        if (hasOlder)
            item(key = "older") {
                WorkspaceOutlinedButton(onClick = onOlder, enabled = !loadingOlder) {
                    Text(if (loadingOlder) "Loading…" else "Load earlier messages")
                }
            }
    }
}

@Composable
internal fun MessageCard(message: ChatItem) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val collapsible = message.role in setOf("tool", "thought")
    val shown = if (collapsible && !expanded) message.text.take(600) else message.text
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start,
    ) {
        if (message.role in setOf("assistant", "agent"))
            Row(
                Modifier.padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    WorkspaceSymbols.Agent,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Agent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        Surface(
            modifier =
                if (message.role == "user") Modifier.fillMaxWidth(.9f) else Modifier.fillMaxWidth(),
            color =
                if (message.role == "user") MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(if (message.role == "user") 16.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.role in setOf("tool", "thought", "approval")) {
                    Text(
                        message.title.ifBlank {
                            if (message.role == "thought") "Thinking" else "Activity"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.status.isNotBlank())
                    Text(
                        message.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                SelectionContainer { MarkdownText(shown) }
                if (collapsible)
                    WorkspaceOutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Collapse" else "Expand")
                    }
            }
        }
    }
}

private data class TextBlock(val text: String, val code: Boolean)

@Composable
private fun MarkdownText(text: String) {
    // Small, synchronous segmentation: one text update produces one layout, with no stale
    // asynchronous result briefly showing the previous chunk or a raw code fence.
    val blocks =
        remember(text) {
            text.split("```").mapIndexed { index, value ->
                TextBlock(
                    if (index % 2 == 1) value.substringAfter('\n', value) else value,
                    index % 2 == 1,
                )
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.code)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        block.text,
                        Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            else Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
