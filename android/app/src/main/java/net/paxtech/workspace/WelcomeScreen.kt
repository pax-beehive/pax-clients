package net.paxtech.workspace

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight native animation; no video, network assets, or animation work in the background. */
@Composable
fun WelcomeScreen(signedIn: Boolean, restoring: Boolean, onEnter: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val compactHeight = maxHeight < 620.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "PAX WORKSPACE",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .height(if (compactHeight) 210.dp else 300.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AgentOrbit(lifecycle.isAtLeast(Lifecycle.State.RESUMED))
                    Text(
                        "PAX",
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 7.sp,
                    )
                }
                Text(
                    "Your agents.\nWithin reach.",
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Pick up a conversation. Move an idea forward.",
                    Modifier.widthIn(max = 330.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(36.dp))
                WorkspaceButton(
                    onClick = onEnter,
                    enabled = !restoring,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                ) {
                    if (restoring) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        if (restoring) "Restoring session…"
                        else if (signedIn) "Enter workspace" else "Sign in to PaxWorkspace"
                    )
                    if (!restoring) {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (signedIn) "Welcome back. Your workspace is ready."
                    else "Your workspace, wherever you are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AgentOrbit(animate: Boolean) {
    // InfiniteTransition respects Android's animation-duration scale, including disabled motion.
    val phase: State<Float> =
        if (animate) {
            rememberInfiniteTransition(label = "agent orbit")
                .animateFloat(
                    initialValue = 0f,
                    targetValue = (Math.PI * 2).toFloat(),
                    animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
                    label = "orbit position",
                )
        } else remember { mutableFloatStateOf(0f) }
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension * 0.4f
        val silver = Color(0xFFD9E5E3)
        drawCircle(
            Brush.radialGradient(
                listOf(silver.copy(alpha = 0.09f), Color.Transparent),
                center,
                radius * 1.25f,
            ),
            radius * 1.25f,
        )
        drawCircle(silver.copy(alpha = 0.12f), radius, style = Stroke(1.dp.toPx()))
        drawCircle(silver.copy(alpha = 0.06f), radius * 0.75f, style = Stroke(1.dp.toPx()))
        repeat(6) { index ->
            val angle = phase.value + index * (Math.PI / 3).toFloat()
            val point = center + Offset(cos(angle), sin(angle)) * radius
            val nextAngle = angle + (Math.PI / 3).toFloat()
            val next = center + Offset(cos(nextAngle), sin(nextAngle)) * radius
            drawLine(silver.copy(alpha = 0.09f), point, next, strokeWidth = 1.dp.toPx())
            drawCircle(
                Brush.radialGradient(
                    listOf(silver.copy(alpha = 0.22f), Color.Transparent),
                    point,
                    18.dp.toPx(),
                ),
                18.dp.toPx(),
                point,
            )
            drawCircle(silver, if (index % 2 == 0) 3.dp.toPx() else 2.dp.toPx(), point)
        }
        drawArc(
            silver.copy(alpha = 0.55f),
            startAngle = -phase.value * 180f / Math.PI.toFloat(),
            sweepAngle = 45f,
            useCenter = false,
            topLeft = center - Offset(radius, radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}
