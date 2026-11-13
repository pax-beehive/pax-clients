package net.paxtech.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.paxtech.workspace.data.AgentPermissionChoice
import net.paxtech.workspace.data.SessionConfigOption

private val Sliders: ImageVector by lazy {
    ImageVector.Builder("Sliders", 24.dp, 24.dp, 24f, 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 18f); lineTo(20f, 18f)
        }
        .path(fill = SolidColor(Color.Black)) {
            moveTo(9f, 6f); arcToRelative(2.2f, 2.2f, 0f, true, true, -0.01f, 0f)
            moveTo(16f, 12f); arcToRelative(2.2f, 2.2f, 0f, true, true, -0.01f, 0f)
            moveTo(7f, 18f); arcToRelative(2.2f, 2.2f, 0f, true, true, -0.01f, 0f)
        }
        .build()
}

/** One button for every session setting, like the web composer: "Opus · High · Ask before tools". */
@Composable
internal fun SessionSettingsButton(summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier =
            modifier.heightIn(min = 40.dp).testTag("session-settings").semantics {
                contentDescription = "Session settings: $summary"
            },
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Sliders, null, Modifier.size(16.dp))
            Text(
                summary.ifBlank { "Settings" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSettingsSheet(
    state: SessionSettingsState,
    onOption: (String, JsonPrimitive) -> Unit,
    onPermission: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<AgentPermissionChoice?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .testTag("session-settings-sheet")
        ) {
            Text("Session settings", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applies from the next message.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        it,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            val configuration = state.configuration
            when {
                state.session.encrypted ->
                    Note("Model and agent options aren't available in encrypted sessions.")
                configuration == null && state.loading ->
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                configuration != null -> {
                    val options = configuration.visibleOptions
                    if (options.isEmpty() && configuration.modelLabel.isBlank())
                        Note("The agent hasn't reported any options yet. Send a message, then try again.")
                    if (options.none { it.isModel } && configuration.modelLabel.isNotBlank()) {
                        Section("MODEL")
                        Text(configuration.modelLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                    options.forEach { option ->
                        key(option.id) {
                            OptionControl(
                                option,
                                enabled = configuration.can_set && state.pendingOption.isEmpty(),
                                pending = state.pendingOption == option.id,
                                onChange = { onOption(option.id, it) },
                            )
                        }
                    }
                    if (!configuration.can_set && options.isNotEmpty())
                        Note("These options can be changed while the agent is connected.")
                    if (configuration.can_force_refresh)
                        TextButton(
                            onClick = onRefresh,
                            enabled = !state.refreshing,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(if (state.refreshing) "Refreshing…" else "Refresh from agent")
                        }
                }
            }
            Section("PERMISSIONS")
            Column(Modifier.selectableGroup()) {
                state.choices.forEach { choice ->
                    key(choice.choice_id) {
                        ChoiceRow(
                            title = choice.label.ifBlank { choice.choice_id },
                            description = choice.description,
                            selected = choice.choice_id == state.permissionChoiceId,
                            enabled = !state.pendingPermission,
                            accent =
                                if (choice.risk.equals("high", true) || choice.requires_confirmation)
                                    WorkspaceAttention
                                else null,
                            tag = "permission:${choice.choice_id}",
                        ) {
                            if (choice.requires_confirmation) confirm = choice
                            else onPermission(choice.choice_id)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { choice ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(choice.label) },
            text = {
                Text(
                    choice.description.ifBlank {
                        "This lets the agent act without asking first. Continue?"
                    }
                )
            },
            confirmButton = {
                WorkspaceButton(
                    onClick = {
                        confirm = null
                        onPermission(choice.choice_id)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                WorkspaceOutlinedButton(onClick = { confirm = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionControl(
    option: SessionConfigOption,
    enabled: Boolean,
    pending: Boolean,
    onChange: (JsonPrimitive) -> Unit,
) {
    val title = option.name.ifBlank { option.id }
    if (option.isBoolean) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp).testTag("option:${option.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (option.description.isNotBlank())
                    Text(
                        option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Switch(
                checked = (option.current_value as? JsonPrimitive)?.booleanOrNull == true,
                onCheckedChange = { onChange(JsonPrimitive(it)) },
                enabled = enabled,
            )
        }
        return
    }
    Section(title.uppercase() + if (pending) " · SAVING…" else "")
    val values = option.options
    // Short value sets (reasoning effort) read best as a segmented control, like the mockup.
    if (!option.isModel && values.size in 2..4 && values.all { it.name.ifBlank { it.value }.length <= 10 }) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("option:${option.id}")) {
            values.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value.value == option.currentText,
                    onClick = { onChange(JsonPrimitive(value.value)) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, values.size),
                    icon = {},
                ) {
                    Text(value.name.ifBlank { value.value }, maxLines = 1)
                }
            }
        }
    } else
        Column(Modifier.selectableGroup().testTag("option:${option.id}")) {
            values.forEach { value ->
                key(value.value) {
                    ChoiceRow(
                        title = value.name.ifBlank { value.value },
                        description = value.description,
                        selected = value.value == option.currentText,
                        enabled = enabled,
                        tag = "option:${option.id}:${value.value}",
                    ) {
                        onChange(JsonPrimitive(value.value))
                    }
                }
            }
        }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    accent: Color? = null,
    onSelect: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier =
            Modifier.fillMaxWidth()
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .testTag(tag),
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent ?: MaterialTheme.colorScheme.onSurface,
                )
                if (description.isNotBlank())
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}
