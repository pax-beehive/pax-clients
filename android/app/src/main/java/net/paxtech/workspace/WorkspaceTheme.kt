package net.paxtech.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual tokens from pax-console's globals.css, adapted to native controls. */
private val workspaceColors =
    darkColorScheme(
        primary = Color(0xFFDDDDDD),
        onPrimary = Color(0xFF141414),
        primaryContainer = Color(0xFF303030),
        onPrimaryContainer = Color(0xFFF1F1EF),
        secondary = Color(0xFFADADAA),
        onSecondary = Color(0xFF141414),
        secondaryContainer = Color(0xFF242424),
        onSecondaryContainer = Color(0xFFF1F1EF),
        tertiary = Color(0xFFB8B8B8),
        onTertiary = Color(0xFF141414),
        background = Color(0xFF141414),
        onBackground = Color(0xFFF1F1EF),
        surface = Color(0xFF141414),
        onSurface = Color(0xFFF1F1EF),
        surfaceVariant = Color(0xFF242424),
        onSurfaceVariant = Color(0xFFADADAA),
        surfaceContainerLowest = Color(0xFF141414),
        surfaceContainerLow = Color(0xFF1C1C1C),
        surfaceContainer = Color(0xFF1C1C1C),
        surfaceContainerHigh = Color(0xFF242424),
        surfaceContainerHighest = Color(0xFF303030),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF575754),
        outlineVariant = Color(0xFF30302E),
        error = Color(0xFFF87171),
        onError = Color(0xFF141414),
    )

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = height.sp,
    )

@Composable
fun WorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = workspaceColors,
        typography =
            Typography(
                headlineLarge = type(28, 34, FontWeight.SemiBold),
                headlineMedium = type(24, 30, FontWeight.SemiBold),
                headlineSmall = type(22, 28, FontWeight.SemiBold),
                titleLarge = type(20, 26, FontWeight.Medium),
                titleMedium = type(16, 22, FontWeight.Medium),
                titleSmall = type(14, 20, FontWeight.Medium),
                bodyLarge = type(16, 24),
                bodyMedium = type(15, 23),
                bodySmall = type(13, 19),
                labelLarge = type(14, 20, FontWeight.Medium),
                labelMedium = type(12, 18, FontWeight.Medium),
                labelSmall = type(11, 16),
            ),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
        content = content,
    )
}

@Composable
fun WorkspaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(), label = "button press")
    Button(
        onClick,
        modifier.heightIn(min = 52.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF0F0F0),
                contentColor = Color(0xFF202020),
                disabledContainerColor = Color(0xFF383838),
                disabledContentColor = Color(0xFF888888),
            ),
        elevation =
            ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun WorkspaceOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) Color(0xFF575754) else Color(0xFF484848)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF242424)),
        content = content,
    )
}

@Composable
fun WorkspaceIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp).size(48.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        content = content,
    )
}
