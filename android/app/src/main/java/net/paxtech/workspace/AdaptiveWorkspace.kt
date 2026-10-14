package net.paxtech.workspace

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal data class PaneBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width
        get() = right - left

    val height
        get() = bottom - top
}

internal data class PanePlan(val home: PaneBounds?, val detail: PaneBounds?)

/** Pixel geometry also handles separating hinges with nonzero occlusion. */
internal fun panePlan(
    width: Int,
    height: Int,
    density: Float,
    selected: Boolean,
    hinge: PaneBounds? = null,
    vertical: Boolean = true,
): PanePlan {
    val full = PaneBounds(0, 0, width, height)
    fun single(area: PaneBounds) = if (selected) PanePlan(null, area) else PanePlan(area, null)
    if (hinge != null) {
        val first =
            if (vertical) PaneBounds(0, 0, hinge.left.coerceIn(0, width), height)
            else PaneBounds(0, 0, width, hinge.top.coerceIn(0, height))
        val second =
            if (vertical) PaneBounds(hinge.right.coerceIn(0, width), 0, width, height)
            else PaneBounds(0, hinge.bottom.coerceIn(0, height), width, height)
        val fits =
            if (vertical) first.width >= 280 * density && second.width >= 320 * density
            else first.height >= 240 * density && second.height >= 300 * density
        if (fits) return PanePlan(first, second)
        return single(
            if (first.width.toLong() * first.height >= second.width.toLong() * second.height) first
            else second
        )
    }
    if (width >= 720 * density) {
        val split = (320 * density).toInt().coerceAtMost(width / 2)
        return PanePlan(
            PaneBounds(0, 0, split, height),
            PaneBounds(split + density.toInt().coerceAtLeast(1), 0, width, height),
        )
    }
    return single(full)
}

@Composable
internal fun AdaptiveWorkspace(
    selected: Boolean,
    home: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val activity = LocalActivity.current
    val flow: Flow<WindowLayoutInfo?> =
        remember(activity) {
            activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: flowOf(null)
        }
    val info by flow.collectAsStateWithLifecycle(initialValue = null)
    val fold =
        info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull {
            it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
        }
    val density = LocalDensity.current.density
    var x by remember { mutableIntStateOf(0) }
    var y by remember { mutableIntStateOf(0) }
    // Both slots keep their composition identity while resized or temporarily hidden.
    // Drafts, list position and filters therefore survive fold/unfold without reparenting.
    Layout(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.outlineVariant)
                .onGloballyPositioned {
                    x = it.positionInWindow().x.toInt()
                    y = it.positionInWindow().y.toInt()
                },
        content = {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("workspace-list")
            ) {
                home()
            }
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("workspace-detail")
            ) {
                detail()
            }
        },
    ) { measurables, constraints ->
        val hinge =
            fold?.bounds?.let { PaneBounds(it.left - x, it.top - y, it.right - x, it.bottom - y) }
        val plan =
            panePlan(
                constraints.maxWidth,
                constraints.maxHeight,
                density,
                selected,
                hinge,
                fold?.orientation != FoldingFeature.Orientation.HORIZONTAL,
            )
        val areas = listOf(plan.home, plan.detail)
        val children =
            measurables.mapIndexed { index, measurable ->
                val area =
                    areas[index] ?: PaneBounds(0, 0, constraints.maxWidth, constraints.maxHeight)
                measurable.measure(Constraints.fixed(area.width, area.height))
            }
        layout(constraints.maxWidth, constraints.maxHeight) {
            areas.forEachIndexed { index, area ->
                if (area != null) children[index].place(area.left, area.top)
            }
        }
    }
}
