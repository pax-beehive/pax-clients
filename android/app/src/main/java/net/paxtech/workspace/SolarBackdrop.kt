package net.paxtech.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*

/** Distant bodies stay behind the enlarged Earth subject; no extra solar orbit diagrams. */
internal fun DrawScope.drawSolarBackdrop(
    frame: SolarFrame,
    origin: Offset,
    unit: Float,
    bounds: Rect,
    alpha: Float,
    time: Float,
) {
    frame.bodies
        .sortedBy { it.position.z }
        .forEach { body ->
            val p = projectSolarBody(body.position)
            val center = origin + Offset(p.x, p.y) * unit
            val radius = body.radius * p.scale * unit
            val extent = radius * if (body.kind == SolarKind.SUN) 5f else 2f
            if (
                center.x + extent < 0 ||
                    center.x - extent > size.width ||
                    center.y + extent < 0 ||
                    center.y - extent > bounds.bottom
            )
                return@forEach
            // Fade toward the text boundary, so no disc is abruptly sliced above the title.
            val bodyExtent = radius * if (body.kind == SolarKind.SATURN) 1.8f else 1f
            val visibility =
                min(
                        (bounds.bottom - center.y - bodyExtent) / (bodyExtent * 1.2f),
                        (center.y - bounds.top - bodyExtent) / (bodyExtent * 1.2f),
                    )
                    .coerceIn(0f, 1f)
            val opacity = alpha * visibility
            if (opacity <= 0f) return@forEach
            if (body.kind == SolarKind.SUN) drawSun(center, radius, opacity, time)
            else drawPlanet(body.kind, center, radius, opacity, body.position, frame.earth)
        }
}

private fun DrawScope.drawSun(center: Offset, radius: Float, alpha: Float, time: Float) {
    drawCircle(
        Brush.radialGradient(
            listOf(
                Color(0xFFFFC978).copy(alpha = .27f * alpha),
                Color(0xFFF99B48).copy(alpha = .07f * alpha),
                Color.Transparent,
            ),
            center,
            radius * 5f,
        ),
        radius * 5f,
        center,
    )
    drawCircle(
        Brush.radialGradient(
            listOf(
                Color(0xFFFFF4CF).copy(alpha = alpha),
                Color(0xFFFFD585).copy(alpha = alpha),
                Color(0xFFF39A45).copy(alpha = alpha),
            ),
            center - Offset(radius * .18f, radius * .22f),
            radius * 1.2f,
        ),
        radius,
        center,
    )
    val disc =
        Path().apply {
            addOval(Rect(center - Offset(radius, radius), Size(radius * 2f, radius * 2f)))
        }
    clipPath(disc) {
        repeat(46) { index ->
            val angle = index * 2.39996f + time * .03f
            val r = sqrt((index + .5f) / 46f) * radius
            drawCircle(
                Color(0xFFD97932).copy(alpha = .07f * alpha),
                radius * (.04f + index % 3 * .015f),
                center + Offset(cos(angle), sin(angle)) * r,
            )
        }
    }
    drawCircle(
        Color(0xFFFFE4B0).copy(alpha = .45f * alpha),
        radius,
        center,
        style = Stroke(radius * .025f),
    )
}

private fun DrawScope.drawPlanet(
    kind: SolarKind,
    center: Offset,
    radius: Float,
    alpha: Float,
    position: Vector3,
    earth: Vector3,
) {
    val base =
        when (kind) {
            SolarKind.MERCURY -> Color(0xFFA5A19B)
            SolarKind.VENUS -> Color(0xFFE9C99D)
            SolarKind.MARS -> Color(0xFFC17D5B)
            SolarKind.JUPITER -> Color(0xFFCEB49C)
            else -> Color(0xFFC4B895)
        }
    fun rings(front: Boolean) {
        rotate(-24f, center) {
            listOf(
                    1.8f to Color(0xFFAA9980),
                    1.63f to Color(0xFFD7C7A6),
                    1.48f to Color(0xFF827A69),
                )
                .forEach { (size, color) ->
                    drawArc(
                        color.copy(alpha = alpha * .72f),
                        if (front) 0f else 180f,
                        180f,
                        false,
                        center - Offset(radius * size, radius * size * .31f),
                        Size(radius * size * 2, radius * size * .62f),
                        style = Stroke(radius * .12f),
                    )
                }
        }
    }
    if (kind == SolarKind.SATURN) rings(false)
    drawCircle(base.copy(alpha = alpha), radius, center)
    val disc =
        Path().apply {
            addOval(Rect(center - Offset(radius, radius), Size(radius * 2f, radius * 2f)))
        }
    clipPath(disc) {
        rotate(-14f, center) {
            if (kind == SolarKind.JUPITER || kind == SolarKind.SATURN || kind == SolarKind.VENUS) {
                repeat(13) { i ->
                    val latitude = (i / 12f - .5f) * 2f
                    val y = center.y + radius * latitude
                    val path =
                        Path().apply {
                            repeat(33) { step ->
                                val x = -1.1f + step * 2.2f / 32f
                                val wave = sin(x * 9f + i * 2.1f) * .022f + sin(x * 23f - i) * .008f
                                val py = y + radius * (wave + .10f * (1f - x * x))
                                if (step == 0) moveTo(center.x + x * radius, py)
                                else lineTo(center.x + x * radius, py)
                            }
                        }
                    val color = if (i % 3 == 0) Color(0xFF86644F) else Color(0xFFF4E6C8)
                    drawPath(
                        path,
                        color.copy(alpha = alpha * if (kind == SolarKind.VENUS) .12f else .30f),
                        style = Stroke(radius * (.07f + (i % 3) * .035f)),
                    )
                }
                if (kind == SolarKind.JUPITER)
                    drawOval(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFB47C61).copy(alpha = alpha * .8f),
                                Color(0xFFDCB99A).copy(alpha = alpha * .15f),
                            ),
                            center + Offset(-radius * .21f, radius * .325f),
                            radius * .22f,
                        ),
                        center + Offset(-radius * .42f, radius * .23f),
                        Size(radius * .42f, radius * .19f),
                    )
            } else {
                repeat(9) { i ->
                    val angle = i * 2.4f
                    val r = sqrt((i + .5f) / 9f) * radius * .86f
                    drawCircle(
                        Color(0xFF3B3631).copy(alpha = .16f * alpha),
                        radius * (.10f + i % 3 * .025f),
                        center + Offset(cos(angle), sin(angle)) * r,
                    )
                }
            }
        }
        val sunX = -earth.x - position.x
        val sunY = earth.y + position.y
        val length = hypot(sunX, sunY).coerceAtLeast(.001f)
        val highlight = center + Offset(sunX / length, sunY / length) * radius * .55f
        drawCircle(
            Brush.radialGradient(
                listOf(Color.Transparent, Color(0xFF030712).copy(alpha = alpha * .90f)),
                highlight,
                radius * 1.5f,
            ),
            radius,
            center,
        )
    }
    if (kind == SolarKind.SATURN) rings(true)
}
