package net.paxtech.workspace

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.sin

private val spaceInk = Color(0xFF080D18)
private val starBlue = Color(0xFF80DFFF)

/** Native projected geometry and bounded particle fields; no video or downloaded assets. */
@Composable
fun WelcomeScreen(signedIn: Boolean, restoring: Boolean, onEnter: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    val arrival = remember { Animatable(if (introPlayed) 1f else 0f) }
    LaunchedEffect(active) {
        if (active) {
            if (!introPlayed) {
                introPlayed = true
                arrival.animateTo(1f, tween(1800, easing = FastOutSlowInEasing))
            } else arrival.snapTo(1f)
        }
    }
    // Both animations follow the system animator scale, including reduced/disabled motion.
    val phase: State<Float> =
        if (active) {
            rememberInfiniteTransition(label = "satellite drift")
                .animateFloat(
                    0f,
                    (Math.PI * 2).toFloat(),
                    infiniteRepeatable(tween(48000, easing = LinearEasing)),
                    label = "orbit time",
                )
        } else remember { mutableFloatStateOf(0f) }
    Surface(Modifier.fillMaxSize(), color = spaceInk) {
        StarField(phase)
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val short = maxHeight < 660.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(6.dp)
                            .background(starBlue, androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(
                        "PAX WORKSPACE",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                        color = Color(0xFFADBFD5),
                    )
                }
                Box(
                    Modifier.widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .height(if (short) 250.dp else 340.dp)
                ) {
                    PlanetaryScene(phase, arrival.asState())
                }
                Column(
                    Modifier.graphicsLayer {
                        val progress = ((arrival.value - 0.18f) / 0.82f).coerceIn(0f, 1f)
                        alpha = 0.2f + 0.8f * progress
                        translationY = (1f - progress) * 24.dp.toPx()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Your agents.",
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 34.sp,
                                lineHeight = 41.sp,
                            ),
                        color = Color(0xFFF4F7FF),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Within reach.",
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 34.sp,
                                lineHeight = 41.sp,
                                brush =
                                    Brush.linearGradient(
                                        listOf(Color(0xFF8CE8FF), Color(0xFFA79CFF))
                                    ),
                            ),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Stay connected to the work in motion.",
                        Modifier.widthIn(max = 330.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF9DAFC5),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(32.dp))
                Column(
                    Modifier.widthIn(max = 360.dp).fillMaxWidth().graphicsLayer {
                        val progress = ((arrival.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        alpha = 0.35f + 0.65f * progress
                        translationY = (1f - progress) * 18.dp.toPx()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WorkspaceButton(
                        onClick = onEnter,
                        enabled = !restoring,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (restoring) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            if (restoring) "Restoring session…"
                            else if (signedIn) "Enter workspace" else "Sign in to PaxWorkspace",
                            Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!restoring) {
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                null,
                                Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (signedIn) "Welcome back. Your workspace is ready."
                        else "Your workspace, wherever you are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9DAFC5),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun StarField(phase: State<Float>) {
    Canvas(Modifier.fillMaxSize()) {
        val glowCenter = Offset(size.width * 0.5f, size.height * 0.36f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF152B48), spaceInk.copy(alpha = 0f)),
                glowCenter,
                size.maxDimension * 0.55f,
            ),
            size.maxDimension * 0.55f,
            glowCenter,
        )
        repeat(100) { index ->
            val x = ((index * 0.618034f + 0.13f) % 1f) * size.width
            val y = ((index * 0.414214f + 0.07f) % 1f) * size.height
            val shimmer = (sin(phase.value * 3f + index * 1.7f) + 1f) * 0.5f
            val opacity = (0.12f + shimmer * 0.35f) * if (y > size.height * 0.57f) 0.35f else 1f
            drawCircle(
                Color(0xFFCADBFF).copy(alpha = opacity),
                if (index % 7 == 0) 1.dp.toPx() else 0.5.dp.toPx(),
                Offset(x, y),
            )
        }
    }
}
