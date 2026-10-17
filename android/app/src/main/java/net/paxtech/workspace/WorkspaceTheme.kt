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

/** Neutral charcoal surfaces; color is reserved for actions, selection and status. */
internal val UserMessageFill = Color(0xFF293747)
internal val UserMessageInk = Color(0xFFEDF4FC)
internal val WorkspaceSuccess = Color(0xFF8BD5B2)
internal val WorkspaceAttention = Color(0xFFF0C285)
internal val WorkspaceOnAttention = Color(0xFF30210F)

private val workspaceColors =
    darkColorScheme(
        primary = Color(0xFF9ABFFF),
        onPrimary = Color(0xFF102544),
        primaryContainer = Color(0xFF283442),
        onPrimaryContainer = Color(0xFFDCE9FF),
        secondary = Color(0xFFBFC2C8),
        onSecondary = Color(0xFF252629),
        secondaryContainer = Color(0xFF303134),
        onSecondaryContainer = Color(0xFFE3E3E5),
        tertiary = Color(0xFFEAB890),
        onTertiary = Color(0xFF3B2716),
        tertiaryContainer = Color(0xFF463426),
        onTertiaryContainer = Color(0xFFFFDFC5),
        background = Color(0xFF181818),
        onBackground = Color(0xFFF1F1EF),
        surface = Color(0xFF181818),
        onSurface = Color(0xFFF1F1EF),
        surfaceVariant = Color(0xFF282828),
        onSurfaceVariant = Color(0xFFB3B3B6),
        surfaceContainerLowest = Color(0xFF141414),
        surfaceContainerLow = Color(0xFF202020),
        surfaceContainer = Color(0xFF222222),
        surfaceContainerHigh = Color(0xFF282828),
        surfaceContainerHighest = Color(0xFF333333),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF656568),
        outlineVariant = Color(0xFF383838),
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
                titleLarge = type(18, 24, FontWeight.Medium),
                titleMedium = type(16, 22, FontWeight.Medium),
                titleSmall = type(15, 21, FontWeight.Medium),
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
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
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
        border =
            BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.outlineVariant,
            ),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
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
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        content = content,
    )
}
