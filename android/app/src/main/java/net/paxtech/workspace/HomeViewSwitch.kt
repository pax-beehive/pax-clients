package net.paxtech.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeViewSwitch(selected: String, onSelect: (String) -> Unit) {
    val position by
        animateFloatAsState(
            targetValue = if (selected == "Projects") 1f else 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
            label = "Home selection position",
        )
    val indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp)
            .drawBehind {
                // Use current layout geometry so folding/resizing never leaves a stale offset.
                val gap = 4.dp.toPx()
                val width = (size.width - gap) / 2f
                val progress = if (isRtl) 1f - position else position
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(progress * (width + gap), 0f),
                    size = Size(width, size.height),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("Home" to "Sessions", "Projects" to "Projects").forEach { (key, label) ->
            val active = selected == key
            val textColor by
                animateColorAsState(
                    targetValue =
                        if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(180),
                    label = "$label selection color",
                )
            Box(
                Modifier.weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .selectable(active, role = Role.RadioButton, onClick = { onSelect(key) })
                    .testTag("home-${label.lowercase()}")
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = textColor)
            }
        }
    }
}
