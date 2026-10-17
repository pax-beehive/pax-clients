package net.paxtech.workspace

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A tap is inert; the platform long-press gesture owns activation and cancellation. */
@Composable
internal fun HoldToRefreshButton(enabled: Boolean, failed: Boolean = false, onRefresh: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val progress = remember { Animatable(0f) }
    var requested by remember { mutableStateOf(false) }
    var observedLoading by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, failed) {
        if (requested && !enabled) observedLoading = true
        if (requested && observedLoading && enabled) {
            requested = false
            observedLoading = false
            completed = !failed
        }
    }
    LaunchedEffect(completed) {
        if (completed) {
            delay(900)
            completed = false
        }
    }
    val holdMillis = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    LaunchedEffect(pressed, enabled, holdMillis) {
        if (pressed && enabled) progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
        else progress.snapTo(0f)
    }
    Box(
        Modifier.height(48.dp)
            .widthIn(min = 80.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))
            .combinedClickable(
                interactionSource = interactions,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = "Refresh workspace",
                onLongClick = {
                    requested = true
                    completed = false
                    onRefresh()
                },
                onClick = {},
            )
            .semantics { contentDescription = "Refresh workspace" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .2f))
            )
        }
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val color =
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
            Crossfade(
                if (completed) "complete" else if (requested && !enabled) "loading" else "ready",
                animationSpec = tween(160),
                label = "refresh feedback",
            ) { phase ->
                when (phase) {
                    "loading" -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    "complete" ->
                        Icon(
                            Icons.Outlined.Check,
                            "Workspace refreshed",
                            Modifier.size(18.dp),
                            tint = WorkspaceSuccess,
                        )
                    else -> Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = color)
                }
            }
            Text("Hold", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
