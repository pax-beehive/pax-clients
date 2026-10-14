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
        onPrimary = Color(0xFF262626),
        primaryContainer = Color(0xFF3D3D3D),
        onPrimaryContainer = Color(0xFFE7E7E7),
        secondary = Color(0xFFC3C3C3),
        onSecondary = Color(0xFF262626),
        secondaryContainer = Color(0xFF353535),
        onSecondaryContainer = Color(0xFFE7E7E7),
        tertiary = Color(0xFFB8B8B8),
        onTertiary = Color(0xFF262626),
        background = Color(0xFF262626),
        onBackground = Color(0xFFE7E7E7),
        surface = Color(0xFF262626),
        onSurface = Color(0xFFE7E7E7),
        surfaceVariant = Color(0xFF353535),
        onSurfaceVariant = Color(0xFFC3C3C3),
        surfaceContainerLowest = Color(0xFF262626),
        surfaceContainerLow = Color(0xFF2D2D2D),
        surfaceContainer = Color(0xFF2D2D2D),
        surfaceContainerHigh = Color(0xFF353535),
        surfaceContainerHighest = Color(0xFF3D3D3D),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF5B5B5B),
        outlineVariant = Color(0xFF434343),
        error = Color(0xFFF87171),
        onError = Color(0xFF262626),
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
                bodyMedium = type(16, 24),
                bodySmall = type(13, 19),
                labelLarge = type(14, 20, FontWeight.Medium),
                labelMedium = type(12, 18, FontWeight.Medium),
                labelSmall = type(11, 16),
            ),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(12.dp),
                extraLarge = RoundedCornerShape(16.dp),
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
            ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 0.dp),
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
        border = BorderStroke(1.dp, if (enabled) Color(0xFF808080) else Color(0xFF484848)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF353535)),
        content = content,
    )
}

@Composable
fun WorkspaceIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp).size(48.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors =
            IconButtonDefaults.outlinedIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        content = content,
    )
}
