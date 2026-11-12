package net.paxtech.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.paxtech.workspace.data.Approval
import net.paxtech.workspace.data.ApprovalOption
import net.paxtech.workspace.data.obj
import net.paxtech.workspace.data.text
import net.paxtech.workspace.data.wireJson
import net.paxtech.workspace.session.ApprovalRef
import net.paxtech.workspace.session.ChatItem

// ---- Presentation rules (pure, unit tested) ----

/** An injected approval id is authoritative; tool call and request ids are fallbacks. */
internal fun Approval.matches(ref: ApprovalRef?): Boolean {
    if (ref == null) return false
    if (ref.approvalId.isNotBlank()) return ref.approvalId == approval_id
    return (ref.toolCallId.isNotBlank() && ref.toolCallId == resource_ref) ||
        (ref.requestId.isNotBlank() && ref.requestId == native_id)
}

internal fun List<Approval>.forItem(item: ChatItem): Approval? =
    if (item.role != "approval" || item.approval == null) null
    else firstOrNull { it.matches(item.approval) }

/** An undecided approval is shown once, as the card at the tail, not also as its notice. */
internal fun withoutPendingNotices(
    messages: List<ChatItem>,
    approvals: List<Approval>,
): List<ChatItem> =
    if (approvals.isEmpty()) messages
    else messages.filterNot { approvals.forItem(it)?.pending == true }

internal enum class ApprovalTone {
    Pending,
    Allowed,
    Denied,
    Neutral,
}

internal data class ApprovalText(
    /** A short sentence: the agent's own description when it supplied one. */
    val headline: String,
    /** The command or tool title, shown monospaced and clamped. */
    val detail: String,
    /** Tool kind such as "Command" or "Edit"; blank when unknown. */
    val kind: String,
) {
    val summary: String
        get() = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: headline
}

private fun Approval.toolCall(): JsonObject {
    val body =
        when (val raw = request_body) {
            is JsonObject -> raw
            is JsonPrimitive ->
                if (raw.isString)
                    runCatching { wireJson.parseToJsonElement(raw.content) as? JsonObject }
                        .getOrNull()
                else null
            else -> null
        }
    return body?.obj("toolCall") ?: JsonObject(emptyMap())
}

internal fun Approval.presentation(): ApprovalText {
    val call = toolCall()
    val kind = call.text("kind").lowercase()
    val detail = call.obj("rawInput").text("command").ifBlank { title }.trim()
    val headline =
        description.trim().ifBlank {
            when (kind) {
                "execute" -> "Run a command"
                "edit" -> "Edit a file"
                "delete" -> "Delete files"
                "move" -> "Move files"
                "read" -> "Read files"
                "search" -> "Search files"
                "fetch" -> "Fetch from the web"
                else -> "Approval requested"
            }
        }
    val label =
        when (kind) {
            "execute" -> "Command"
            "edit" -> "Edit"
            "delete" -> "Delete"
            "move" -> "Move"
            "read" -> "Read"
            "search" -> "Search"
            "fetch" -> "Fetch"
            else -> ""
        }
    return ApprovalText(headline, if (detail == headline) "" else detail, label)
}

internal val ApprovalOption.isDeny: Boolean
    get() =
        decision.equals("deny", true) ||
            decision.equals("reject", true) ||
            option_id.startsWith("deny") ||
            option_id.startsWith("reject")

private val ApprovalOption.isAllowOnce: Boolean
    get() =
        !isDeny &&
            (scope.equals("once", true) || option_id == "allow_once" || option_id == "allow")

/** Deny and allow-once stay one tap away; broader grants move behind "More". */
internal data class ApprovalActions(
    val deny: ApprovalOption?,
    val allow: ApprovalOption?,
    val more: List<ApprovalOption>,
)

internal fun approvalActions(options: List<ApprovalOption>): ApprovalActions {
    val usable = options.filter { it.option_id.isNotBlank() }
    val deny = usable.firstOrNull { it.isDeny }
    val allow = usable.firstOrNull { it.isAllowOnce } ?: usable.firstOrNull { !it.isDeny }
    return ApprovalActions(deny, allow, usable.filter { it != deny && it != allow })
}

internal val Approval.tone: ApprovalTone
    get() =
        when {
            pending -> ApprovalTone.Pending
            !status.equals("decided", true) -> ApprovalTone.Neutral
            decision.equals("deny", true) ||
                decision.equals("reject", true) ||
                options.firstOrNull { it.option_id == decision_option }?.isDeny == true ->
                ApprovalTone.Denied
            else -> ApprovalTone.Allowed
        }

/** "Allow once" becomes "Allowed once"; the stored option label keeps scopes accurate. */
internal fun Approval.outcomeLabel(): String =
    when (tone) {
        ApprovalTone.Pending -> "Needs approval"
        ApprovalTone.Denied -> "Denied"
        ApprovalTone.Neutral -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
        ApprovalTone.Allowed -> {
            val label =
                options.firstOrNull { it.option_id == decision_option }?.label?.trim().orEmpty()
            when {
                label.startsWith("Allow ", ignoreCase = true) -> "Allowed " + label.substring(6)
                label.isNotBlank() && !label.equals("Allow", true) -> label
                decision_scope.equals("once", true) -> "Allowed once"
                else -> "Allowed"
            }
        }
    }

internal fun scopeDescription(scope: String, agent: String = "", device: String = ""): String =
    when (scope.lowercase()) {
        "once" -> "Only this request"
        "session" -> "Until this session ends"
        "agent" ->
            if (agent.isNotBlank() && device.isNotBlank()) "$agent on $device, in every session"
            else "This agent, in every session"
        "node" -> "Every agent on ${device.ifBlank { "this device" }}"
        "across_all_agents" -> "Every agent on every device"
        else -> ""
    }

private val ApprovalOption.isBroadest: Boolean
    get() = scope.equals("across_all_agents", true)

/** Local wall-clock time; earlier days also show the date. */
internal fun approvalTime(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
    locale: Locale = Locale.getDefault(),
): String {
    if (iso.isBlank()) return ""
    val instant =
        runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(iso) }.getOrNull()
            ?: return ""
    val time = instant.atZone(zone)
    val clock = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return if (time.toLocalDate() == now.atZone(zone).toLocalDate()) clock
    else time.format(DateTimeFormatter.ofPattern("MMM d", locale)) + ", " + clock
}

internal fun Approval.decisionMeta(viewerId: String = "", zone: ZoneId = ZoneId.systemDefault()): String {
    val time = approvalTime(decided_at, zone)
    val who =
        if (viewerId.isNotBlank() && decided_by_user_id == viewerId) " by you" else ""
    val decided = if (time.isBlank()) "" else "Decided $time$who"
    val scope =
        if (tone == ApprovalTone.Allowed && !decision_scope.equals("once", true))
            scopeDescription(decision_scope)
        else ""
    return listOf(decided, scope).filter { it.isNotBlank() }.joinToString(" · ")
}

// ---- Pending approval card ----

@Composable
internal fun PendingApprovalCard(
    approval: Approval,
    working: Boolean,
    identity: ConversationIdentity,
    modifier: Modifier = Modifier,
    onDecision: (ApprovalOption) -> Unit,
) {
    val text = remember(approval) { approval.presentation() }
    val actions = remember(approval.options) { approvalActions(approval.options) }
    var selected by remember(approval.approval_id) { mutableStateOf<ApprovalOption?>(null) }
    var showMore by rememberSaveable(approval.approval_id) { mutableStateOf(false) }
    val enabled = !working && approval.pending
    Surface(
        modifier.fillMaxWidth().testTag("approval-card:${approval.approval_id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, WorkspaceAttention.copy(alpha = .28f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusLabel("Needs approval", attention = true) }
                if (text.kind.isNotBlank()) KindChip(text.kind)
            }
            Text(text.headline, style = MaterialTheme.typography.titleMedium)
            if (text.detail.isNotBlank()) CommandPreview(text.detail, approval.approval_id)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.deny?.let { option ->
                    OutlinedButton(
                        onClick = { selected = option },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = enabled,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Text(
                            option.label.ifBlank { "Deny" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions.allow?.let { option ->
                    Button(
                        onClick = { selected = option },
                        modifier = Modifier.weight(1.35f).heightIn(min = 48.dp),
                        enabled = enabled,
                        shape = RoundedCornerShape(24.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    ) {
                        Text(
                            option.label.ifBlank { option.option_id },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (actions.more.isNotEmpty())
                    OutlinedIconButton(
                        onClick = { showMore = true },
                        enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("approval-more"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "More approval options",
                            modifier = Modifier.rotate(90f),
                        )
                    }
            }
        }
    }
    if (showMore)
        MoreApprovalOptions(
            text = text,
            options = actions.more,
            identity = identity,
            onDismiss = { showMore = false },
            onPick = {
                showMore = false
                selected = it
            },
        )
    selected?.let { option ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(option.label.ifBlank { "Confirm decision" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text.headline, style = MaterialTheme.typography.bodyMedium)
                    if (text.detail.isNotBlank())
                        Text(
                            text.detail,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    Text(
                        scopeDescription(option.scope, identity.agentName, identity.deviceName)
                            .ifBlank { "Apply this decision to the requested action." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                WorkspaceButton(
                    onClick = {
                        selected = null
                        onDecision(option)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                WorkspaceOutlinedButton(onClick = { selected = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun KindChip(label: String) {
    Text(
        label,
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Long commands (heredocs, PR bodies) start clamped so one request never fills the screen. */
@Composable
private fun CommandPreview(command: String, key: String, collapsedLines: Int = 4) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflows by remember(command) { mutableStateOf(false) }
    val lines = remember(command) { command.lines().size }
    val fill = MaterialTheme.colorScheme.surfaceContainerLowest
    Column {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = fill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box {
                SelectionContainer {
                    Text(
                        command,
                        Modifier.fillMaxWidth()
                            .then(
                                if (expanded)
                                    Modifier.heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState())
                                else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("approval-command"),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .86f),
                        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                    )
                }
                if (!expanded && overflows)
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(Brush.verticalGradient(listOf(fill.copy(alpha = 0f), fill)))
                    )
            }
        }
        if (overflows || expanded)
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            ) {
                Text(
                    when {
                        expanded -> "Show less"
                        lines > 1 -> "Show full command · $lines lines"
                        else -> "Show full command"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreApprovalOptions(
    text: ApprovalText,
    options: List<ApprovalOption>,
    identity: ConversationIdentity,
    onDismiss: () -> Unit,
    onPick: (ApprovalOption) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                if (options.none { it.isDeny }) "More ways to allow" else "More options",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text.summary,
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { option ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Button) { onPick(option) }
                        .padding(vertical = 12.dp)
                        .testTag("approval-option:${option.option_id}"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        option.label.ifBlank { option.option_id },
                        style = MaterialTheme.typography.titleSmall,
                        color =
                            if (option.isBroadest) WorkspaceAttention
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    val scope =
                        scopeDescription(option.scope, identity.agentName, identity.deviceName)
                    if (scope.isNotBlank())
                        Text(
                            scope,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "You'll confirm before it's applied.",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- Decided approvals ----

@Composable
private fun toneColor(tone: ApprovalTone): Color =
    when (tone) {
        ApprovalTone.Allowed -> WorkspaceSuccess
        ApprovalTone.Denied -> MaterialTheme.colorScheme.error
        ApprovalTone.Pending -> WorkspaceAttention
        ApprovalTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun ToneBadge(tone: ApprovalTone) {
    val ink = toneColor(tone)
    Box(
        Modifier.size(20.dp).background(ink.copy(alpha = .16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (tone) {
            ApprovalTone.Allowed -> Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = ink)
            ApprovalTone.Denied -> Icon(Icons.Outlined.Close, null, Modifier.size(13.dp), tint = ink)
            else -> Box(Modifier.size(6.dp).background(ink, CircleShape))
        }
    }
}

@Composable
private fun DisclosureRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val arrow by
        animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "approval arrow")
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 12.dp)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            null,
            Modifier.size(18.dp).graphicsLayer { rotationZ = arrow },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DecidedApprovalBody(approval: Approval, viewerId: String) {
    val text = remember(approval) { approval.presentation() }
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val meta = approval.decisionMeta(viewerId)
        if (meta.isNotBlank())
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Text(text.headline, style = MaterialTheme.typography.titleSmall)
        if (text.detail.isNotBlank())
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                SelectionContainer {
                    Text(
                        text.detail,
                        Modifier.fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .86f),
                    )
                }
            }
    }
}

/** One line once decided; tap to see what was approved, when, and for how long. */
@Composable
internal fun DecidedApprovalRow(
    approval: Approval,
    viewerId: String = "",
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(approval.approval_id) { mutableStateOf(false) }
    val text = remember(approval) { approval.presentation() }
    val tone = approval.tone
    Surface(
        modifier.fillMaxWidth().testTag("approval-row:${approval.approval_id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            DisclosureRow(expanded, { expanded = !expanded }) {
                ToneBadge(tone)
                Text(
                    approval.outcomeLabel(),
                    Modifier.widthIn(max = 220.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = toneColor(tone),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("·", color = MaterialTheme.colorScheme.outline)
                Text(
                    if (expanded) text.kind.ifBlank { "Request" } else text.summary,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(tween(200)) + fadeIn(tween(140)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                DecidedApprovalBody(approval, viewerId)
            }
        }
    }
}

/** Approvals decided inside one activity run share a single line, however many there were. */
@Composable
internal fun ApprovalSummary(
    approvals: List<Approval>,
    viewerId: String = "",
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty()) return
    if (approvals.size == 1) {
        DecidedApprovalRow(approvals.single(), viewerId, modifier)
        return
    }
    var expanded by rememberSaveable(approvals.first().approval_id) { mutableStateOf(false) }
    val allowed = approvals.count { it.tone == ApprovalTone.Allowed }
    val denied = approvals.count { it.tone == ApprovalTone.Denied }
    val other = approvals.size - allowed - denied
    val tone =
        when {
            denied == 0 && other == 0 -> ApprovalTone.Allowed
            allowed == 0 && other == 0 -> ApprovalTone.Denied
            else -> ApprovalTone.Neutral
        }
    Surface(
        modifier.fillMaxWidth().testTag("approval-summary:${approvals.first().approval_id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            DisclosureRow(expanded, { expanded = !expanded }) {
                ToneBadge(tone)
                Text(
                    "${approvals.size} approvals",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("·", color = MaterialTheme.colorScheme.outline)
                Text(
                    listOfNotNull(
                            allowed.takeIf { it > 0 }?.let { "$it allowed" },
                            denied.takeIf { it > 0 }?.let { "$it denied" },
                            other.takeIf { it > 0 }?.let { "$it other" },
                        )
                        .joinToString(", "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(tween(200)) + fadeIn(tween(140)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                Column(
                    Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    approvals.forEach { key(it.approval_id) { DecidedApprovalRow(it, viewerId) } }
                }
            }
        }
    }
}
