package net.paxtech.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private val spaceInk = Color(0xFF03070D)

/** Native projected geometry and bounded particle fields; no video or downloaded assets. */
@Composable
fun WelcomeScreen(signedIn: Boolean, restoring: Boolean, onEnter: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    val arrival = remember { Animatable(if (introPlayed) 1f else 0f) }
    var departing by rememberSaveable { mutableStateOf(false) }
    val approach = remember { Animatable(0f) }
    val enter by rememberUpdatedState(onEnter)
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var sceneBounds by remember { mutableStateOf(Rect.Zero) }
    val destinationColor = MaterialTheme.colorScheme.background
    BackHandler(enabled = departing) { departing = false }
    LaunchedEffect(departing, active) {
        if (!departing) approach.snapTo(0f)
        else if (active) {
            arrival.snapTo(1f)
            approach.animateTo(1f, tween(1050, easing = CubicBezierEasing(.42f, 0f, .38f, 1f)))
            enter()
        }
    }
    LaunchedEffect(active) {
        if (active) {
            if (!introPlayed) {
                introPlayed = true
                arrival.animateTo(1f, tween(2200, easing = FastOutSlowInEasing))
            } else arrival.snapTo(1f)
        }
    }
    val solarYears = rememberSolarYears(active)
    // Shared saved clock: stopping, backgrounding and recreation preserve the whole scene.
    val phase = remember {
        derivedStateOf { solarYears.value * 120f * (2f * Math.PI.toFloat()) / 42f }
    }
    Surface(
        Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() },
        color = spaceInk,
    ) {
        StarField(phase)
        // Draw at the window level so the camera can travel beyond the scrolling hero slot.
        PlanetaryScene(
            phase,
            arrival.asState(),
            viewport = sceneBounds,
            approach = approach.asState(),
            solarYears = solarYears,
        )
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val heroHeight = (maxHeight - 280.dp).coerceIn(220.dp, 490.dp)
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier =
                        Modifier.graphicsLayer {
                            alpha = (1f - approach.value * 5f).coerceIn(0f, 1f)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "PAX",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = Color(0xFFEBF3FA),
                    )
                    Text(
                        "WORKSPACE",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                        color = Color(0xFF8E9EAF),
                    )
                }
                Box(
                    Modifier.widthIn(max = 620.dp)
                        .fillMaxWidth()
                        .height(heroHeight)
                        .onGloballyPositioned {
                            sceneBounds =
                                Rect(
                                    it.positionInRoot() - rootOrigin,
                                    Size(it.size.width.toFloat(), it.size.height.toFloat()),
                                )
                        }
                )
                Column(
                    Modifier.graphicsLayer {
                        val progress = ((arrival.value - 0.18f) / 0.82f).coerceIn(0f, 1f)
                        alpha =
                            (0.2f + 0.8f * progress) * (1f - approach.value * 5f).coerceIn(0f, 1f)
                        translationY = (1f - progress) * 24.dp.toPx()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Your agents.",
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 26.sp,
                                lineHeight = 33.sp,
                            ),
                        color = Color(0xFFF4F7FF),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Within reach.",
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 26.sp,
                                lineHeight = 33.sp,
                                brush =
                                    Brush.linearGradient(
                                        listOf(Color(0xFFB1D2E6), Color(0xFF8AA6BC))
                                    ),
                            ),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Connected, wherever you are.",
                        Modifier.widthIn(max = 330.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9DAFC5),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Column(
                    Modifier.widthIn(max = 360.dp).fillMaxWidth().graphicsLayer {
                        val progress = ((arrival.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        alpha =
                            (0.35f + 0.65f * progress) * (1f - approach.value * 5f).coerceIn(0f, 1f)
                        translationY = (1f - progress) * 18.dp.toPx()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WorkspaceButton(
                        onClick = {
                            if (!departing) {
                                if (signedIn) departing = true else enter()
                            }
                        },
                        enabled = !restoring && !departing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Equal side slots keep the label on the page's center line.
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            if (restoring)
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (restoring) "Restoring session…"
                            else if (signedIn) "Enter workspace" else "Sign in to PaxWorkspace",
                            Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            if (!restoring)
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
                        else "PaxWorkspace for Android",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9DAFC5),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val dissolve = ((approach.value - .68f) / .32f).coerceIn(0f, 1f)
            if (dissolve > 0f)
                drawRect(destinationColor.copy(alpha = dissolve * dissolve * (3f - 2f * dissolve)))
        }
    }
}

/**
 * Saved, pauseable orbital time; the infinite-animation policy also supports Compose test clocks.
 */
@Composable
internal fun rememberSolarYears(active: Boolean): State<Float> {
    val solarYears = rememberSaveable { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var previous: Long? = null
        while (currentCoroutineContext().isActive) {
            withInfiniteAnimationFrameNanos { now ->
                val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                val before = previous
                if (before != null && scale > 0f) {
                    solarYears.floatValue += ((now - before) / 120_000_000_000.0 / scale).toFloat()
                }
                previous = now
            }
        }
    }
    return solarYears
}
