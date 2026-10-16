package net.paxtech.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.paxtech.workspace.data.AgentSession

/** The quiet orbital signature carries the welcome scene into the workspace. */
@Composable
internal fun OrbitMark(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.secondary.copy(alpha = .55f)
    Canvas(modifier.size(56.dp)) {
        drawCircle(ink, size.minDimension * .12f)
        rotate(-32f) {
            drawOval(
                muted,
                Offset(size.width * .04f, size.height * .26f),
                Size(size.width * .92f, size.height * .48f),
                style = Stroke(1.2.dp.toPx()),
            )
            drawCircle(ink, 3.dp.toPx(), Offset(size.width * .88f, size.height * .36f))
        }
    }
}

@Composable
internal fun WorkspaceEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon == null) OrbitMark()
        else
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    icon,
                    null,
                    Modifier.padding(18.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            description,
            Modifier.widthIn(max = 320.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun StatusLabel(label: String, active: Boolean = false, attention: Boolean = false) {
    val ink =
        when {
            attention -> WorkspaceAttention
            active -> WorkspaceSuccess
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).background(ink, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

@Composable
internal fun SessionRow(
    target: AgentSession,
    selected: Boolean,
    projectName: String?,
    dualPane: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else if (dualPane) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(if (dualPane) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                WorkspaceSymbols.Agent,
                null,
                Modifier.padding(top = 2.dp).size(22.dp),
                tint =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    target.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (target.encrypted)
                    Text(
                        "End-to-end encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else if (target.preview.isNotBlank())
                    Text(
                        target.preview,
                        maxLines = if (dualPane) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusLabel(
                        target.runtime_status.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        target.runtime_status in setOf("running", "busy", "streaming"),
                    )
                    if (!projectName.isNullOrBlank())
                        Text(
                            projectName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
            }
            if (!dualPane)
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    null,
                    Modifier.padding(top = 2.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.secondary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
