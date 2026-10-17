package net.paxtech.workspace

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.paxtech.workspace.data.Approval
import net.paxtech.workspace.session.ChatItem

/** Keep keystrokes local: editing a draft must not invalidate the session or its timeline. */
@Composable
internal fun MessageComposer(
    connection: String,
    busy: Boolean,
    error: String?,
    onSend: (String) -> String?,
    onStop: () -> Unit,
    motion: SendMotionState? = null,
) {
    val haptics = LocalHapticFeedback.current
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
                modifier =
                    Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 650f))
                        .onGloballyPositioned { motion?.composer = it.boundsInRoot() },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border =
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier =
                            Modifier.weight(1f)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .heightIn(min = 24.dp)
                                .testTag("message-composer"),
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty())
                                    Text(
                                        "Message your agent",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                inner()
                            }
                        },
                    )
                    ComposerAction(
                        busy = busy,
                        enabled = busy || (draft.isNotBlank() && connection == "Connected"),
                        onClick = {
                            if (busy) onStop()
                            else {
                                val text = draft
                                val id = onSend(text)
                                if (id != null) {
                                    motion?.launch(id, text)
                                    submitted = text
                                    draft = ""
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerAction(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            if (pressed) .9f else 1f,
            spring(dampingRatio = .75f, stiffness = 900f),
            label = "send-press",
        )
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier =
            Modifier.size(48.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = CircleShape,
    ) {
        Crossfade(busy, animationSpec = tween(140), label = "send-stop") { stopping ->
            if (stopping)
                Box(
                    Modifier.size(14.dp)
                        .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(3.dp))
                        .semantics { contentDescription = "Stop" }
                )
            else Icon(WorkspaceSymbols.Send, "Send", Modifier.size(24.dp))
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
    motion: SendMotionState? = null,
    waitingForReply: Boolean = false,
    identity: ConversationIdentity = ConversationIdentity(),
) {
    val entries = remember(messages) { conversationEntries(messages) }
    val atTail by remember(list) { derivedStateOf { !list.canScrollBackward } }
    // Capture the old layout before the new tail is measured. Stable-key anchoring otherwise
    // keeps the previous message visible even when a reader was following the conversation.
    val followNewTail = atTail
    LaunchedEffect(
        messages.lastOrNull()?.id,
        approvals.lastOrNull()?.approval_id,
        waitingForReply,
    ) {
        // Scaffold may compose this effect during measurement. Schedule the scroll
        // for the next measure instead of forcing a reentrant measure/layout pass.
        if (followNewTail) list.requestScrollToItem(0)
    }
    LaunchedEffect(motion?.flight?.id) { if (motion?.flight != null) list.requestScrollToItem(0) }
    LazyColumn(
        modifier.testTag("conversation-timeline"),
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (waitingForReply)
            item(key = "reply-waiting", contentType = "reply-waiting") {
                Box(
                    Modifier.animateItem(
                        fadeInSpec = tween(160),
                        placementSpec = null,
                        fadeOutSpec = tween(120),
                    )
                ) {
                    ReplyDot()
                }
            }
        items(
            approvals.asReversed(),
            key = { "approval:${it.approval_id}" },
            contentType = { "approval-card" },
        ) {
            approval(it)
        }
        items(
            entries.asReversed(),
            key = { it.id },
            contentType = { if (it.isActivity) "activity" else it.messages.first().role },
        ) {
            Box(
                Modifier.animateItem(
                    fadeInSpec = tween(160),
                    placementSpec = null,
                    fadeOutSpec = tween(120),
                )
            ) {
                if (it.isActivity) ConversationActivity(it)
                else MessageCard(it.messages.single(), motion, identity)
            }
        }
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
internal fun MessageCard(
    message: ChatItem,
    motion: SendMotionState? = null,
    identity: ConversationIdentity = ConversationIdentity(),
) {
    SideEffect { motion?.observe(message) }
    val isAgent = message.role in setOf("assistant", "agent")
    val isUser = message.role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser)
            Text(
                "You",
                Modifier.padding(end = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (isAgent) ConversationByline(identity)
        Surface(
            modifier =
                (if (isUser) Modifier.fillMaxWidth(.85f).wrapContentWidth(Alignment.End)
                    else Modifier.fillMaxWidth())
                    .onGloballyPositioned {
                        motion?.destination(
                            message,
                            it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false),
                        )
                    }
                    .graphicsLayer { alpha = if (motion?.matches(message) == true) 0f else 1f },
            color =
                when {
                    isUser -> UserMessageFill
                    isAgent -> MaterialTheme.colorScheme.surfaceContainerLow
                    else -> MaterialTheme.colorScheme.background
                },
            contentColor = if (isUser) UserMessageInk else MaterialTheme.colorScheme.onSurface,
            border =
                if (isAgent)
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                else null,
            shape =
                when {
                    isUser -> RoundedCornerShape(26.dp)
                    isAgent -> RoundedCornerShape(20.dp)
                    else -> RectangleShape
                },
        ) {
            Column(
                // Every rounded bubble needs an inset so its corners never clip glyphs.
                Modifier.padding(if (isUser || isAgent) 16.dp else 0.dp),
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
                if (message.status.isNotBlank() && message.status != "Sending…")
                    Text(
                        message.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                message.attachments.forEach { attachment ->
                    key(attachment) { AttachmentCard(attachment) }
                }
                if (message.text.isNotBlank()) {
                    if (isAgent) MessageMarkdown(message.text)
                    else
                        SelectionContainer {
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        }
                }
            }
        }
    }
}
