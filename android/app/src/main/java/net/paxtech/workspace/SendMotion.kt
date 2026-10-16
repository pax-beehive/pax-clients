package net.paxtech.workspace

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import java.text.Bidi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.paxtech.workspace.session.ChatItem

/** Geometry is presentation-only. Sending and delivery never wait for this animation. */
@Stable
internal class SendMotionState(val enabled: Boolean = true) {
    var flight by mutableStateOf<SendFlight?>(null)
        private set

    var composer: Rect? = null
    var host: Rect? = null
        private set

    fun placeHost(bounds: Rect) {
        host?.let { if (it.width != bounds.width || it.left != bounds.left) flight = null }
        host = bounds
    }

    fun launch(id: String, text: String) {
        val origin = composer ?: return
        if (
            !enabled ||
                !ValueAnimator.areAnimatorsEnabled() ||
                origin.isEmpty ||
                text.contains("```") ||
                Bidi.requiresBidi(text.toCharArray(), 0, text.length)
        )
            return
        flight = SendFlight(id, text, origin)
    }

    fun matches(message: ChatItem): Boolean =
        flight?.let {
            message.id == it.id ||
                (it.turn != null &&
                    !it.turn!!.startsWith("local:") &&
                    message.turn == it.turn &&
                    message.role == "user" &&
                    message.text == it.text)
        } ?: false

    fun observe(message: ChatItem) {
        flight?.let { if (message.id == it.id) it.turn = message.turn }
    }

    fun destination(message: ChatItem, bounds: Rect) {
        if (!matches(message)) return
        val visible = host ?: return
        // A long draft may extend off-screen. Reveal it normally instead of flying a huge surface
        // over the toolbar, keyboard or the other pane of a foldable.
        if (
            bounds.isEmpty ||
                bounds.height > visible.height * .7f ||
                bounds.top < visible.top ||
                bounds.bottom > visible.bottom
        ) {
            // The multiline composer is still shrinking on the first layout after send.
            // Let the existing destination timeout wait for a visible landing rectangle;
            // once a flight has started, moving it out of view still settles immediately.
            if (flight?.destination != null) flight = null
        } else flight?.destination = bounds
    }

    fun finish(value: SendFlight) {
        if (flight === value) flight = null
    }
}

@Stable
internal class SendFlight(val id: String, val text: String, val origin: Rect) {
    var destination by mutableStateOf<Rect?>(null)
    var turn by mutableStateOf<String?>(null)
}

@Composable
internal fun SendMotionHost(
    state: SendMotionState = remember { SendMotionState() },
    modifier: Modifier = Modifier,
    content: @Composable (SendMotionState) -> Unit,
) {
    Box(
        modifier.fillMaxSize().clipToBounds().onGloballyPositioned {
            state.placeHost(it.boundsInRoot())
        }
    ) {
        content(state)
        SendBubbleOverlay(state)
    }
}

@Composable
private fun SendBubbleOverlay(state: SendMotionState) {
    val flight = state.flight ?: return
    val progress = remember(flight) { Animatable(0f) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    val ink = UserMessageInk
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = with(density) { 16.dp.toPx() }
    val radius = with(density) { 26.dp.toPx() }
    val target = flight.destination ?: flight.origin
    // Measure only the endpoints, never on animation frames. Each shaped line fragment moves
    // continuously between those layouts; no duplicated crossfading text or per-frame reflow.
    val originText =
        remember(flight, density, style) {
            measurer.measure(
                flight.text,
                style,
                maxLines = 6,
                constraints =
                    Constraints(
                        maxWidth =
                            (flight.origin.width - inset * 2 - with(density) { 48.dp.toPx() })
                                .toInt()
                                .coerceAtLeast(1)
                    ),
            )
        }
    val targetText =
        remember(flight, target.width, density, style) {
            measurer.measure(
                flight.text,
                style,
                maxLines = 12,
                constraints =
                    Constraints(maxWidth = (target.width - inset * 2).toInt().coerceAtLeast(1)),
            )
        }
    val fits = !originText.hasVisualOverflow && !targetText.hasVisualOverflow
    val fragments =
        remember(originText, targetText, fits) {
            if (fits) textFragments(originText, targetText) else emptyList()
        }
    LaunchedEffect(flight, fits) { if (!fits) state.finish(flight) }
    LaunchedEffect(flight) {
        try {
            if (
                withTimeoutOrNull(250) {
                    snapshotFlow { flight.destination }.filterNotNull().first()
                } != null
            ) {
                progress.animateTo(1f, tween(420, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)))
            }
        } finally {
            state.finish(flight)
        }
    }
    Canvas(Modifier.fillMaxSize().testTag("send-bubble-flight").clearAndSetSemantics {}) {
        val parent = state.host ?: return@Canvas
        val amount = progress.value
        val box =
            lerp(flight.origin, flight.destination ?: flight.origin, amount)
                .translate(-parent.topLeft)
        drawRoundRect(
            androidx.compose.ui.graphics.lerp(fill, UserMessageFill, amount),
            box.topLeft,
            box.size,
            CornerRadius(radius.coerceAtMost(box.height / 2)),
        )
        val textOrigin = Offset(box.left + inset, box.top + inset)
        clipRect(box.left + inset, box.top, box.right - inset, box.bottom) {
            fragments.forEach { fragment ->
                val delta = fragment.to.topLeft - fragment.from.topLeft
                // Move a wrapping fragment around its neighbour, rather than through its glyphs.
                val early = (amount / .55f).coerceIn(0f, 1f)
                val late = ((amount - .45f) / .55f).coerceIn(0f, 1f)
                val shift =
                    if (delta.y == 0f) delta * amount
                    else
                        Offset(
                            delta.x * if (delta.x > 0f) early else late,
                            delta.y * if (delta.x > 0f) late else early,
                        )
                val clip = fragment.from.translate(textOrigin + shift)
                clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                    drawText(originText, ink, textOrigin + shift)
                }
            }
        }
    }
}

private data class TextFragment(val from: Rect, val to: Rect)

private fun textFragments(from: TextLayoutResult, to: TextLayoutResult): List<TextFragment> {
    val text = from.layoutInput.text.text
    val ends = sortedSetOf(0, text.length)
    for (layout in listOf(from, to)) repeat(layout.lineCount) { ends += layout.getLineEnd(it) }
    fun bounds(layout: TextLayoutResult, start: Int, end: Int): Rect? {
        var result: Rect? = null
        for (index in start until end) {
            if (text[index] == '\n' || text[index] == '\r') continue
            val box = layout.getBoundingBox(index)
            if (box.isEmpty) continue
            result =
                result?.let {
                    Rect(
                        minOf(it.left, box.left),
                        minOf(it.top, box.top),
                        maxOf(it.right, box.right),
                        maxOf(it.bottom, box.bottom),
                    )
                } ?: box
        }
        return result
    }
    return ends.zipWithNext().mapNotNull { (start, end) ->
        val source = bounds(from, start, end) ?: return@mapNotNull null
        val target = bounds(to, start, end) ?: return@mapNotNull null
        TextFragment(source, target)
    }
}

/** One quiet breathing dot, removed as soon as the first response content arrives. */
@Composable
internal fun ReplyDot() {
    val breathing = rememberInfiniteTransition(label = "reply-dot")
    val phase by
        breathing.animateFloat(
            .65f,
            1f,
            infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        )
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier.padding(vertical = 10.dp).size(16.dp).testTag("reply-dot").semantics {
            contentDescription = "Waiting for agent response"
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        val value = if (ValueAnimator.areAnimatorsEnabled()) phase else 1f
        drawCircle(color.copy(alpha = .6f + value * .4f), size.minDimension * .36f * value, center)
    }
}
