package net.paxtech.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

private data class Vector3(val x: Float, val y: Float, val z: Float)

private data class SatelliteFace(val points: List<Vector3>, val color: Color, val normal: Vector3)

/** A small mesh projected in Canvas; geometry is cached, with no textures or rendering engine. */
@Composable
internal fun SatelliteScene(phase: State<Float>, arrival: State<Float>) {
    val mesh = remember { satelliteMesh() }
    Canvas(Modifier.fillMaxSize()) {
        val time = phase.value
        val reveal = arrival.value
        val anchor = Offset(center.x, center.y + sin(time * 2f) * 5.dp.toPx())
        val unit = min(size.width / 3.7f, size.height / 3.1f) * (0.65f + reveal * 0.35f)
        val yaw = 0.42f + sin(time) * 0.42f + (1f - reveal) * 1.2f
        val pitch = -0.28f + cos(time) * 0.15f
        val roll = -0.18f + sin(time * 2f) * 0.07f
        fun rotate(v: Vector3): Vector3 {
            val x = v.x * cos(yaw) + v.z * sin(yaw)
            val z = -v.x * sin(yaw) + v.z * cos(yaw)
            val y = v.y * cos(pitch) - z * sin(pitch)
            val depth = v.y * sin(pitch) + z * cos(pitch)
            return Vector3(x * cos(roll) - y * sin(roll), x * sin(roll) + y * cos(roll), depth)
        }
        fun project(v: Vector3): Offset {
            val perspective = 5f / (5f - v.z)
            return anchor + Offset(v.x * unit * perspective, -v.y * unit * perspective)
        }
        val halo = min(size.width, size.height) * 0.5f
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF5B55D4).copy(alpha = 0.17f * reveal), Color.Transparent),
                anchor,
                halo,
            ),
            halo,
            anchor,
        )

        // A tilted orbit and its particles pass behind and in front of the spacecraft.
        fun orbit(angle: Float, spread: Float = 1f): Offset {
            val x = cos(angle) * size.width * 0.46f * spread
            val y = sin(angle) * size.height * 0.24f * spread
            return anchor + Offset(x * 0.98f + y * 0.2f, -x * 0.2f + y * 0.98f)
        }
        val track = Path()
        repeat(121) { index ->
            val p = orbit(index * (2f * PI.toFloat() / 120))
            if (index == 0) track.moveTo(p.x, p.y) else track.lineTo(p.x, p.y)
        }
        drawPath(
            track,
            Color(0xFF6CCBFF).copy(alpha = 0.12f * reveal),
            style = Stroke(0.7.dp.toPx()),
        )
        fun particles(front: Boolean) {
            repeat(180) { index ->
                val seed = (index * 0.618034f) % 1f
                val angle = index * 2.39996f + time * (if (index % 2 == 0) 1f else -0.65f)
                if ((sin(angle) > 0.72f) != front) return@repeat
                val spread = 0.88f + seed * 0.25f + (1f - reveal) * (1.5f + seed)
                val point = orbit(angle, spread)
                val tint = if (index % 3 == 0) Color(0xFFB49AFF) else Color(0xFF81DDFF)
                val alpha = (0.2f + seed * 0.6f) * (0.25f + reveal * 0.75f)
                if (index % 8 == 0) {
                    val tail = orbit(angle - 0.06f, spread)
                    drawLine(
                        tint.copy(alpha = alpha * 0.5f),
                        tail,
                        point,
                        0.8.dp.toPx(),
                        StrokeCap.Round,
                    )
                    drawCircle(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = 0.16f), Color.Transparent),
                            point,
                            6.dp.toPx(),
                        ),
                        6.dp.toPx(),
                        point,
                    )
                }
                drawCircle(tint.copy(alpha = alpha), (0.5f + seed * 0.75f).dp.toPx(), point)
            }
        }
        particles(false)

        // Painter's algorithm with face lighting gives panels, bus and dish real depth.
        val transformed = mesh.map { face -> face to face.points.map(::rotate) }
        transformed
            .sortedBy { (_, vertices) -> vertices.sumOf { it.z.toDouble() } / vertices.size }
            .forEach { (face, vertices) ->
                val normal = rotate(face.normal)
                val centroid =
                    Vector3(
                        vertices.map { it.x }.average().toFloat(),
                        vertices.map { it.y }.average().toFloat(),
                        vertices.map { it.z }.average().toFloat(),
                    )
                if (
                    -normal.x * centroid.x - normal.y * centroid.y + normal.z * (5f - centroid.z) <=
                        0f
                )
                    return@forEach
                val points = vertices.map(::project)
                val path =
                    Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                val light =
                    (0.67f + (normal.x * -0.3f + normal.y * 0.5f + normal.z * 0.7f) * 0.3f)
                        .coerceIn(0.35f, 1f)
                val base = face.color
                val tint = Color(base.red * light, base.green * light, base.blue * light, reveal)
                val highlight =
                    Color(
                        (tint.red * 1.13f).coerceAtMost(1f),
                        (tint.green * 1.13f).coerceAtMost(1f),
                        (tint.blue * 1.13f).coerceAtMost(1f),
                        reveal,
                    )
                drawPath(
                    path,
                    Brush.linearGradient(
                        listOf(
                            highlight,
                            tint,
                            tint.copy(
                                red = tint.red * 0.8f,
                                green = tint.green * 0.8f,
                                blue = tint.blue * 0.8f,
                            ),
                        ),
                        anchor - Offset(unit, unit),
                        anchor + Offset(unit, unit),
                    ),
                )
                drawPath(
                    path,
                    Color(0xFFD2EBFF).copy(alpha = 0.18f * reveal),
                    style = Stroke(0.55.dp.toPx()),
                )
            }
        // Small navigation beacons are decorative, not connection-status indicators.
        listOf(Vector3(-1.4f, 0f, 0.04f), Vector3(1.4f, 0f, 0.04f)).forEach { point ->
            val p = project(rotate(point))
            val tint = Color(0xFF8BEFFF)
            drawCircle(
                Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.5f * reveal), Color.Transparent),
                    p,
                    12.dp.toPx(),
                ),
                12.dp.toPx(),
                p,
            )
            drawCircle(tint.copy(alpha = reveal), 1.5.dp.toPx(), p)
        }
        particles(true)
    }
}

private fun satelliteMesh(): List<SatelliteFace> = buildList {
    fun face(color: Color, normal: Vector3, vararg points: Vector3) {
        add(SatelliteFace(points.toList(), color, normal))
    }
    fun box(
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
        z0: Float,
        z1: Float,
        color: Color,
        openFront: Boolean = false,
    ) {
        if (!openFront)
            face(
                color,
                Vector3(0f, 0f, 1f),
                Vector3(x0, y0, z1),
                Vector3(x1, y0, z1),
                Vector3(x1, y1, z1),
                Vector3(x0, y1, z1),
            )
        face(
            color,
            Vector3(0f, 0f, -1f),
            Vector3(x0, y0, z0),
            Vector3(x1, y0, z0),
            Vector3(x1, y1, z0),
            Vector3(x0, y1, z0),
        )
        face(
            color,
            Vector3(0f, 1f, 0f),
            Vector3(x0, y1, z0),
            Vector3(x1, y1, z0),
            Vector3(x1, y1, z1),
            Vector3(x0, y1, z1),
        )
        face(
            color,
            Vector3(0f, -1f, 0f),
            Vector3(x0, y0, z0),
            Vector3(x1, y0, z0),
            Vector3(x1, y0, z1),
            Vector3(x0, y0, z1),
        )
        face(
            color,
            Vector3(1f, 0f, 0f),
            Vector3(x1, y0, z0),
            Vector3(x1, y1, z0),
            Vector3(x1, y1, z1),
            Vector3(x1, y0, z1),
        )
        face(
            color,
            Vector3(-1f, 0f, 0f),
            Vector3(x0, y0, z0),
            Vector3(x0, y1, z0),
            Vector3(x0, y1, z1),
            Vector3(x0, y0, z1),
        )
    }
    val metal = Color(0xFFD1DCE9)
    val gold = Color(0xFFD2AE69)
    box(-0.35f, 0.35f, -0.32f, 0.32f, -0.25f, 0.25f, gold)
    box(-0.36f, 0.36f, 0.27f, 0.34f, -0.26f, 0.27f, metal)
    box(-0.36f, 0.36f, -0.34f, -0.27f, -0.26f, 0.27f, metal)
    // Raised instrument housing stays visibly separate from the thermal shell.
    box(-0.19f, 0.19f, -0.19f, 0.22f, 0.26f, 0.36f, Color(0xFF344761))
    box(-0.55f, 0.55f, -0.035f, 0.035f, -0.035f, 0.035f, metal)
    for (side in listOf(-1, 1)) {
        val left = if (side == -1) -1.42f else 0.52f
        val right = left + 0.9f
        // Cells replace the front surface, preventing coplanar faces from sorting over them.
        box(left, right, -0.4f, 0.4f, -0.035f, 0.035f, Color(0xFF647BA7), openFront = true)
        repeat(5) { column ->
            repeat(4) { row ->
                val x = left + column * 0.18f
                val y = -0.4f + row * 0.2f
                val color = if ((column + row) % 3 == 0) Color(0xFF5269CF) else Color(0xFF2859A1)
                face(
                    color,
                    Vector3(0f, 0f, 1f),
                    Vector3(x, y, 0.037f),
                    Vector3(x + 0.18f, y, 0.037f),
                    Vector3(x + 0.18f, y + 0.2f, 0.037f),
                    Vector3(x, y + 0.2f, 0.037f),
                )
            }
        }
    }
    // Dish with a recessed center, raised rim and central feed.
    box(-0.045f, 0.045f, 0.05f, 0.37f, 0.25f, 0.49f, metal)
    repeat(32) { index ->
        val a = index * 2f * PI.toFloat() / 32f
        val b = (index + 1) * 2f * PI.toFloat() / 32f
        val center = Vector3(0f, 0.29f, 0.49f)
        val p = Vector3(cos(a) * 0.28f, 0.29f + sin(a) * 0.28f, 0.67f)
        val q = Vector3(cos(b) * 0.28f, 0.29f + sin(b) * 0.28f, 0.67f)
        face(Color(0xFFD2E3F5), Vector3(-cos(a) * 0.4f, -sin(a) * 0.4f, 0.8f), center, p, q)
    }
    box(-0.018f, 0.018f, 0.27f, 0.31f, 0.5f, 0.81f, gold)
    box(-0.05f, 0.05f, 0.24f, 0.34f, 0.80f, 0.84f, metal)
    box(0.2f, 0.22f, 0.34f, 0.77f, -0.17f, -0.15f, metal)
    box(0.16f, 0.26f, 0.76f, 0.80f, -0.2f, -0.12f, gold)
}
