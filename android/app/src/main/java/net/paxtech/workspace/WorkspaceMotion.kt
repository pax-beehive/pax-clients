package net.paxtech.workspace

import android.animation.ValueAnimator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/**
 * Entrance only: old sessions are disposed immediately and cannot keep observing or accepting
 * input.
 */
@Composable
internal fun SessionArrival(content: @Composable () -> Unit) {
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) { opacity.animateTo(1f, tween(180)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) { content() }
}

@Composable
internal fun AgentWorkIndicator(working: Boolean, waitingForApproval: Boolean) {
    AnimatedVisibility(
        visible = working || waitingForApproval,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
    ) {
        val label = if (waitingForApproval) "Needs approval" else "Working"
        val color =
            if (waitingForApproval) WorkspaceAttention else MaterialTheme.colorScheme.primary
        Row(
            Modifier.testTag("agent-work-status").semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (working && !waitingForApproval) {
                val transition = rememberInfiniteTransition(label = "agent work")
                val breath by
                    transition.animateFloat(
                        .65f,
                        1f,
                        infiniteRepeatable(
                            tween(1000, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse,
                        ),
                        label = "work breath",
                    )
                Canvas(Modifier.size(8.dp)) {
                    val amount = if (ValueAnimator.areAnimatorsEnabled()) breath else 1f
                    drawCircle(color.copy(alpha = amount), size.minDimension * .4f * amount)
                }
            } else Canvas(Modifier.size(8.dp)) { drawCircle(color, size.minDimension * .35f) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
