package net.paxtech.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.random.Random

private data class BackgroundStar(
    val x: Float,
    val y: Float,
    val strength: Float,
    val warm: Boolean,
)

@Composable
internal fun StarField(phase: State<Float>) {
    val stars = remember {
        val random = Random(20261014)
        List(520) {
            BackgroundStar(
                random.nextFloat(),
                random.nextFloat(),
                random.nextFloat(),
                random.nextInt(7) == 0,
            )
        }
    }
    Canvas(Modifier.fillMaxSize().testTag("welcome-starfield")) {
        val glowCenter = Offset(size.width * .5f, size.height * .36f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF0A1726), Color.Transparent),
                glowCenter,
                size.maxDimension * .55f,
            ),
            size.maxDimension * .55f,
            glowCenter,
        )
        val count =
            (size.width / density * size.height / density / 1400f).toInt().coerceIn(180, 520)
        stars.take(count).forEachIndexed { index, star ->
            val p = Offset(star.x * size.width, star.y * size.height)
            val prominent = star.strength > .94f
            val radius = (if (prominent) 1.1f else .38f + star.strength * .38f).dp.toPx()
            // Fixed inertial orientation keeps distant stars still; only exposure varies subtly.
            val exposure = 1f + .035f * sin(phase.value * .6f + index * 1.71f)
            val textZone = if (star.y > .58f) .48f else 1f
            val opacity = ((.22f + star.strength * .52f) * exposure * textZone).coerceIn(0f, 1f)
            val color = if (star.warm) Color(0xFFFFE3BB) else Color(0xFFD3E4FF)
            if (prominent)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = opacity * .18f), Color.Transparent),
                        p,
                        radius * 3.5f,
                    ),
                    radius * 3.5f,
                    p,
                )
            drawCircle(color.copy(alpha = opacity), radius, p)
        }
    }
}
