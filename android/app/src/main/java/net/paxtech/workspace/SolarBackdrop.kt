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
    alpha: Float,
    time: Float,
) {
    frame.bodies
        .sortedBy { it.position.z }
        .forEach { body ->
            val p = projectSolarBody(body.position) ?: return@forEach
            val center = origin + Offset(p.x, p.y) * unit
            val radius = body.radius * p.scale * unit
            val extent = radius * if (body.kind == SolarKind.SUN) 5f else 2f
            if (
                center.x + extent < 0 ||
                    center.x - extent > size.width ||
                    center.y + extent < 0 ||
                    center.y - extent > size.height
            )
                return@forEach
            val opacity = alpha
            if (opacity <= 0f) return@forEach
            if (body.kind == SolarKind.SUN) drawSun(center, radius, opacity, time)
            else
                drawPlanet(
                    body.kind,
                    center,
                    radius,
                    opacity,
                    body.position,
                    frame.earth,
                    planetSpin(body.kind, time * 42f / (2f * PI.toFloat())),
                )
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
    spin: Float,
) {
    val base =
        when (kind) {
            SolarKind.MERCURY -> Color(0xFFA5A19B)
            SolarKind.VENUS -> Color(0xFFE9C99D)
            SolarKind.MARS -> Color(0xFFC17D5B)
            SolarKind.JUPITER -> Color(0xFFCEB49C)
            SolarKind.URANUS -> Color(0xFF9BCED1)
            SolarKind.NEPTUNE -> Color(0xFF648ACD)
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
        rotate(if (kind == SolarKind.URANUS) 82f else -14f, center) {
            if (
                kind in
                    setOf(
                        SolarKind.JUPITER,
                        SolarKind.SATURN,
                        SolarKind.VENUS,
                        SolarKind.URANUS,
                        SolarKind.NEPTUNE,
                    )
            ) {
                repeat(13) { i ->
                    val latitude = (i / 12f - .5f) * 2f
                    val y = center.y + radius * latitude
                    val path =
                        Path().apply {
                            repeat(33) { step ->
                                val x = -1.1f + step * 2.2f / 32f
                                val longitude = asin(x.coerceIn(-1f, 1f)) - spin
                                val wave =
                                    sin(longitude * 9f + i * 2.1f) * .022f +
                                        sin(longitude * 23f - i) * .008f
                                val py = y + radius * (wave + .10f * (1f - x * x))
                                if (step == 0) moveTo(center.x + x * radius, py)
                                else lineTo(center.x + x * radius, py)
                            }
                        }
                    val icy = kind in setOf(SolarKind.URANUS, SolarKind.NEPTUNE)
                    val color =
                        if (icy) Color(0xFFE3F5FF)
                        else if (i % 3 == 0) Color(0xFF86644F) else Color(0xFFF4E6C8)
                    drawPath(
                        path,
                        color.copy(
                            alpha = alpha * if (kind == SolarKind.VENUS || icy) .12f else .30f
                        ),
                        style = Stroke(radius * (.07f + (i % 3) * .035f)),
                    )
                }
                val spotLongitude = spin - .22f
                val spotCenter = center + Offset(sin(spotLongitude) * radius * .92f, radius * .325f)
                val spotWidth = radius * .42f * cos(spotLongitude).coerceAtLeast(0f)
                if (kind == SolarKind.JUPITER && spotWidth > 0f)
                    drawOval(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFB47C61).copy(alpha = alpha * .8f),
                                Color(0xFFDCB99A).copy(alpha = alpha * .15f),
                            ),
                            spotCenter,
                            radius * .22f,
                        ),
                        spotCenter - Offset(spotWidth / 2, radius * .095f),
                        Size(spotWidth, radius * .19f),
                    )
            } else {
                repeat(9) { i ->
                    val longitude = i * 2.4f + spin
                    val latitude = -.7f + i / 8f * 1.4f
                    val facing = cos(longitude)
                    if (facing <= 0f) return@repeat
                    val r = radius * (.10f + i % 3 * .025f)
                    val p = center + Offset(sin(longitude) * cos(latitude), sin(latitude)) * radius
                    drawOval(
                        Color(0xFF3B3631).copy(alpha = .16f * alpha),
                        p - Offset(r * facing, r),
                        Size(r * facing * 2, r * 2),
                    )
                }
            }
        }
        // The terminator is the projection of a lit hemisphere, including the phase angle.
        // A radial shadow alone makes every planet look half-lit regardless of the Sun.
        val sx = -earth.x - position.x
        val sy = -earth.y - position.y
        val sz = -earth.z - position.z
        val sunLength = sqrt(sx * sx + sy * sy + sz * sz)
        val vx = -position.x
        val vy = -position.y
        val vz = .00017f - position.z
        val viewLength = sqrt(vx * vx + vy * vy + vz * vz)
        val phaseCos = ((sx * vx + sy * vy + sz * vz) / (sunLength * viewLength)).coerceIn(-1f, 1f)
        val tangentX = sx / sunLength - phaseCos * vx / viewLength
        val tangentY = -(sy / sunLength - phaseCos * vy / viewLength)
        val angle = atan2(tangentY, tangentX)
        fun surfacePoint(x: Float, y: Float): Offset =
            center +
                Offset(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle)) * radius
        val night =
            Path().apply {
                for (step in 0..64) {
                    val y = -1f + step / 32f
                    val p = surfacePoint(-sqrt((1f - y * y).coerceAtLeast(0f)), y)
                    if (step == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                for (step in 64 downTo 0) {
                    val y = -1f + step / 32f
                    val p = surfacePoint(-phaseCos * sqrt((1f - y * y).coerceAtLeast(0f)), y)
                    lineTo(p.x, p.y)
                }
                close()
            }
        drawPath(night, Color(0xFF02050A).copy(alpha = alpha * .88f))
        drawCircle(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = alpha * .32f)),
                center,
                radius,
            ),
            radius,
            center,
        )
    }
    if (kind == SolarKind.SATURN) rings(true)
}
