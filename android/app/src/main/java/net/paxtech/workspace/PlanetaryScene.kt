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

private data class Orbit(
    val radius: Float,
    val inclination: Float,
    val heading: Float,
    val speed: Int,
    val offset: Float,
    val kind: Int,
    val scale: Float,
    val color: Color,
) {
    fun position(angle: Float): Vector3 {
        val x = radius * cos(angle)
        val y = radius * sin(angle) * cos(inclination)
        return Vector3(
            x * cos(heading) - y * sin(heading),
            x * sin(heading) + y * cos(heading),
            radius * sin(angle) * sin(inclination),
        )
    }
}

private val orbits =
    listOf(
        Orbit(1.78f, 1.13f, -0.38f, 2, 0.5f, 0, 0.23f, Color(0xFF81DCFF)),
        Orbit(2.02f, 1.28f, 1.02f, -1, 2.6f, 2, 0.22f, Color(0xFFAD9BFF)),
        Orbit(2.2f, 0.78f, -1.05f, 1, 4.2f, 1, 0.32f, Color(0xFF88EBCF)),
        Orbit(1.95f, 1.35f, 0.45f, -2, 5.7f, 0, 0.17f, Color(0xFFAAC9FF)),
    )

/** A rotating planet with independently inclined orbits and depth-ordered spacecraft. */
@Composable
internal fun PlanetaryScene(phase: State<Float>, arrival: State<Float>) {
    val satellites = remember { (0..2).map(::satelliteMesh) }
    val land = remember { planetLand() }
    Canvas(Modifier.fillMaxSize()) {
        val time = phase.value
        val reveal = arrival.value
        val unit = min(size.width, size.height) / 5.8f * (0.72f + reveal * 0.28f)
        val origin = center
        fun project(v: Vector3): Offset {
            val perspective = 8f / (8f - v.z)
            return origin + Offset(v.x, -v.y) * unit * perspective
        }
        val globeRadius = unit * 1.12f
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF3486BB).copy(alpha = 0.24f * reveal), Color.Transparent),
                origin,
                globeRadius * 1.9f,
            ),
            globeRadius * 1.9f,
            origin,
        )
        fun orbitPass(front: Boolean) {
            orbits.forEach { orbit ->
                val path = Path()
                var connected = false
                repeat(145) { index ->
                    val v = orbit.position(index * 2f * PI.toFloat() / 144f)
                    if ((v.z >= 0f) == front) {
                        val p = project(v)
                        if (!connected) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        connected = true
                    } else connected = false
                }
                drawPath(
                    path,
                    orbit.color.copy(alpha = reveal * if (front) 0.25f else 0.11f),
                    style = Stroke(0.7.dp.toPx()),
                )
                repeat(48) { index ->
                    val angle = index * 2.39996f + time * orbit.speed
                    val seed = (index * 0.618034f) % 1f
                    val v = orbit.position(angle)
                    if ((v.z >= 0f) != front) return@repeat
                    val scatter = 1f + (seed - 0.5f) * 0.065f + (1f - reveal) * 1.8f
                    val p = project(Vector3(v.x * scatter, v.y * scatter, v.z))
                    drawCircle(
                        orbit.color.copy(alpha = reveal * (0.13f + seed * 0.45f)),
                        (0.4f + seed * 0.5f).dp.toPx(),
                        p,
                    )
                }
            }
        }
        fun satellitePass(front: Boolean) {
            orbits
                .map { it to it.position(time * it.speed + it.offset) }
                .filter { (_, v) -> (v.z >= 0f) == front }
                .sortedBy { it.second.z }
                .forEach { (orbit, v) ->
                    val position = project(v)
                    val perspective = 8f / (8f - v.z)
                    val satelliteUnit = unit * orbit.scale * perspective
                    drawSpacecraft(
                        satellites[orbit.kind],
                        position,
                        satelliteUnit,
                        time * orbit.speed + orbit.offset,
                        orbit.heading,
                        reveal,
                        orbit.color,
                    )
                    // Short moving wake follows this satellite's own orbital plane.
                    val tail = Path()
                    repeat(15) { step ->
                        val p =
                            project(
                                orbit.position(
                                    time * orbit.speed + orbit.offset -
                                        step * 0.014f * orbit.speed.sign
                                )
                            )
                        if (step == 0) tail.moveTo(p.x, p.y) else tail.lineTo(p.x, p.y)
                    }
                    drawPath(
                        tail,
                        orbit.color.copy(alpha = 0.32f * reveal),
                        style = Stroke(1.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
        }
        orbitPass(false)
        satellitePass(false)
        drawPlanet(origin, globeRadius, time, reveal, land)
        orbitPass(true)
        satellitePass(true)
    }
}

private fun DrawScope.drawSpacecraft(
    mesh: List<SatelliteFace>,
    origin: Offset,
    unit: Float,
    angle: Float,
    roll: Float,
    alpha: Float,
    accent: Color,
) {
    fun rotate(v: Vector3): Vector3 {
        val yaw = angle + 0.4f
        val x = v.x * cos(yaw) + v.z * sin(yaw)
        val z = -v.x * sin(yaw) + v.z * cos(yaw)
        val y = v.y * 0.96f + z * 0.28f
        return Vector3(
            x * cos(roll) - y * sin(roll),
            x * sin(roll) + y * cos(roll),
            z * 0.96f - v.y * 0.28f,
        )
    }
    mesh
        .map { it to it.points.map(::rotate) }
        .sortedBy { (_, points) -> points.sumOf { it.z.toDouble() } / points.size }
        .forEach { (face, points) ->
            val normal = rotate(face.normal)
            if (normal.z <= 0f) return@forEach
            val path =
                Path().apply {
                    points.forEachIndexed { index, v ->
                        val p = origin + Offset(v.x, -v.y) * unit
                        if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }
            val light = (0.72f + 0.22f * (normal.z + normal.y - normal.x)).coerceIn(0.4f, 1f)
            val color = face.color
            drawPath(path, Color(color.red * light, color.green * light, color.blue * light, alpha))
            drawPath(path, Color.White.copy(alpha = 0.12f * alpha), style = Stroke(0.3.dp.toPx()))
        }
    drawCircle(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.18f * alpha), Color.Transparent),
            origin,
            unit * 1.7f,
        ),
        unit * 1.7f,
        origin,
    )
}

private fun sphere(lon: Float, lat: Float) =
    Vector3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))

private fun DrawScope.drawPlanet(
    origin: Offset,
    radius: Float,
    time: Float,
    alpha: Float,
    land: List<List<Vector3>>,
) {
    // The opaque sphere is also the occluder for all far-side satellites and tracks.
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF368FB8), Color(0xFF155478), Color(0xFF061425)),
            origin + Offset(-radius * 0.38f, -radius * 0.4f),
            radius * 1.55f,
        ),
        radius,
        origin,
        alpha = alpha,
    )
    fun rotate(v: Vector3): Vector3 {
        val yaw = time + 0.35f
        val x = v.x * cos(yaw) + v.z * sin(yaw)
        val z = -v.x * sin(yaw) + v.z * cos(yaw)
        return Vector3(x * 0.98f + v.y * 0.2f, -x * 0.2f + v.y * 0.98f, z)
    }
    val landPath = Path()
    land.forEach { patch ->
        val rotated = patch.map(::rotate)
        if (rotated.all { it.z < 0f }) return@forEach
        // Clip polygons at the horizon so continents never pop through the far hemisphere.
        val clipped = mutableListOf<Vector3>()
        rotated.indices.forEach { index ->
            val a = rotated[index]
            val b = rotated[(index + 1) % rotated.size]
            if (a.z >= 0f) clipped.add(a)
            if ((a.z >= 0f) != (b.z >= 0f)) {
                val t = a.z / (a.z - b.z)
                clipped.add(Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, 0f))
            }
        }
        if (clipped.size < 3) return@forEach
        clipped.forEachIndexed { index, v ->
            val p = origin + Offset(v.x, -v.y) * radius
            if (index == 0) landPath.moveTo(p.x, p.y) else landPath.lineTo(p.x, p.y)
        }
        landPath.close()
    }
    // One shaded draw for all land contours avoids hundreds of per-cell draw calls.
    drawPath(
        landPath,
        Brush.radialGradient(
            listOf(Color(0xFF71C9B3), Color(0xFF328F87), Color(0xFF0A2838)),
            origin + Offset(-radius * 0.38f, -radius * 0.4f),
            radius * 1.55f,
        ),
        alpha = alpha,
    )
    // Faint illuminated atmosphere and a small specular glint retain a spherical silhouette.
    drawCircle(
        Color(0xFF83DEFF).copy(alpha = 0.32f * alpha),
        radius,
        origin,
        style = Stroke(1.dp.toPx()),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFB5EFFF).copy(alpha = 0.19f * alpha), Color.Transparent),
            origin + Offset(-radius * 0.45f, -radius * 0.45f),
            radius * 0.75f,
        ),
        radius,
        origin,
    )
}

/** Stylized land patches on a sphere; generated once, not per animation frame. */
private fun planetLand(): List<List<Vector3>> = buildList {
    val step = PI.toFloat() / 48f
    for (latIndex in -22..22) for (lonIndex in -48..47) {
        val lat = latIndex * step
        val lon = lonIndex * step
        val shape =
            sin(lon * 2.3f + sin(lat * 3f)) +
                0.6f * cos(lat * 5f - lon) +
                0.22f * sin(lon * 8f + lat * 7f)
        if (shape > 0.6f || lat > 1.36f)
            add(
                listOf(
                    sphere(lon, lat),
                    sphere(lon + step, lat),
                    sphere(lon + step, lat + step),
                    sphere(lon, lat + step),
                )
            )
    }
}
