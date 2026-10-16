package net.paxtech.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
        Orbit(1.92f, 1.12f, -0.38f, 2, 0.65f, 0, 0.22f, Color(0xFFACD8F5)),
        Orbit(2.1f, 1.25f, 1.02f, -1, 2.7f, 2, 0.17f, Color(0xFF9EACC9)),
        Orbit(2.02f, 0.95f, -0.97f, 1, 4.1f, 1, 0.26f, Color(0xFF91B6C6)),
    )

/** A rotating planet with independently inclined orbits and depth-ordered spacecraft. */
@Composable
internal fun PlanetaryScene(
    phase: State<Float>,
    arrival: State<Float>,
    forceSoftware: Boolean = false,
    viewport: Rect? = null,
    approach: State<Float>? = null,
    solarYears: State<Float>? = null,
) {
    val satellites = remember { (0..2).map(::satelliteMesh) }
    val planet = rememberPlanetPainter(forceSoftware)
    Canvas(Modifier.fillMaxSize()) {
        // One saved clock drives every moving object; rates below are art-directed for readability.
        val seconds = solarYears?.value?.times(120f) ?: (phase.value * 42f / (2f * PI.toFloat()))
        val time = seconds * (2f * PI.toFloat()) / 42f
        val moon = moonPose(seconds)
        val reveal = arrival.value
        val frame = viewport ?: Rect(Offset.Zero, size)
        if (frame.width <= 0f || frame.height <= 0f) return@Canvas
        val flight = approach?.value ?: 0f
        val solar = solarFrame(solarYears?.value ?: 0f)
        val restingUnit = min(frame.width, frame.height) / 7.4f * (0.86f + reveal * 0.14f)
        // Exponential dolly keeps the approach gentle initially and fills any screen aspect ratio.
        val destinationRadius = hypot(size.width, size.height) * 0.7f
        val unit = restingUnit * (destinationRadius / (restingUnit * 1.34f)).pow(flight)
        val origin = frame.center + (center - frame.center) * flight
        fun project(v: Vector3): Offset {
            val perspective = 10f / (10f - v.z)
            return origin + Offset(v.x, -v.y) * unit * perspective
        }
        val globeRadius = unit * 1.34f
        drawSolarBackdrop(solar, origin, unit, reveal * (1f - flight), time)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF183D66).copy(alpha = 0.20f * reveal), Color.Transparent),
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
                    orbit.color.copy(alpha = reveal * if (front) 0.035f else 0.012f),
                    style = Stroke(0.55.dp.toPx()),
                )
            }
        }
        fun satellitePass(front: Boolean) {
            // Include the Moon in depth ordering, so it can pass behind Earth and spacecraft.
            val bodies: List<Pair<Orbit?, Vector3>> =
                orbits.map { it to it.position(time * it.speed + it.offset) } +
                    (null to moon.position)
            bodies
                .filter { (_, v) -> (v.z >= 0f) == front }
                .sortedBy { it.second.z }
                .forEach { (orbit, v) ->
                    val position = project(v)
                    if (orbit == null) {
                        drawMoon(
                            moon,
                            position,
                            unit * .366f * 10f / (10f - v.z),
                            solar.sunlight,
                            reveal,
                        )
                        return@forEach
                    }
                    val perspective = 10f / (10f - v.z)
                    val satelliteUnit = unit * orbit.scale * perspective
                    drawSpacecraft(
                        satellites[orbit.kind],
                        position,
                        satelliteUnit,
                        time * orbit.speed + orbit.offset,
                        orbit.heading,
                        reveal,
                        orbit.color,
                        solar.sunlight,
                    )
                }
        }
        orbitPass(false)
        satellitePass(false)
        if (planet != null) {
            drawIntoCanvas {
                planet.draw(
                    it.nativeCanvas,
                    origin.x,
                    origin.y,
                    globeRadius,
                    seconds * (2f * PI.toFloat()) / 64f,
                    reveal,
                    solar.sunlight,
                )
            }
        } else {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF173D52), Color(0xFF02080F)),
                    origin - Offset(globeRadius * 0.4f, globeRadius * 0.4f),
                    globeRadius * 1.5f,
                ),
                globeRadius,
                origin,
            )
        }
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
    sunlight: Vector3,
) {
    val length = sqrt(sunlight.x * sunlight.x + sunlight.y * sunlight.y + sunlight.z * sunlight.z)
    val lightDirection = Vector3(sunlight.x / length, -sunlight.y / length, sunlight.z / length)
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
            val light =
                (0.22f +
                        0.78f *
                            (normal.x * lightDirection.x +
                                    normal.y * lightDirection.y +
                                    normal.z * lightDirection.z)
                                .coerceAtLeast(0f))
                    .coerceIn(0.22f, 1f)
            val color = face.color
            val spec = max(0f, -normal.x * 0.3f + normal.y * 0.3f + normal.z * 0.9f).pow(14) * 0.35f
            val tint =
                Color(
                    (color.red * light + spec).coerceAtMost(1f),
                    (color.green * light + spec).coerceAtMost(1f),
                    (color.blue * light + spec).coerceAtMost(1f),
                    alpha,
                )
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(
                        tint,
                        tint.copy(
                            red = tint.red * .65f,
                            green = tint.green * .65f,
                            blue = tint.blue * .7f,
                        ),
                    ),
                    origin - Offset(unit, unit),
                    origin + Offset(unit, unit),
                ),
            )
            drawPath(path, Color.White.copy(alpha = 0.10f * alpha), style = Stroke(0.18.dp.toPx()))
        }
    drawCircle(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.06f * alpha), Color.Transparent),
            origin,
            unit * 1.7f,
        ),
        unit * 1.7f,
        origin,
    )
}
