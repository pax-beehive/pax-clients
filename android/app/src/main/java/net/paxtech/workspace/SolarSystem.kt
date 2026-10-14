package net.paxtech.workspace

import kotlin.math.*

internal enum class SolarKind {
    SUN,
    MERCURY,
    VENUS,
    MARS,
    JUPITER,
    SATURN,
}

internal data class SolarBody(val kind: SolarKind, val position: Vector3, val radius: Float)

internal data class SolarFrame(val earth: Vector3, val bodies: List<SolarBody>) {
    val sunlight: Vector3
        get() = Vector3(-earth.x, earth.y, -earth.z)
}

/**
 * Heliocentric motion, viewed from a translating camera locked to Earth. Distances, body sizes and
 * periods are deliberately compressed for a phone-sized scene. Integer revolutions close every path
 * at the master clock boundary without a jump.
 */
internal fun solarFrame(phase: Float): SolarFrame {
    fun orbit(radius: Float, angle: Float, inclination: Float): Vector3 {
        val x = radius * cos(angle)
        val y = radius * sin(angle) * cos(inclination)
        val heading = .35f
        return Vector3(
            x * cos(heading) - y * sin(heading),
            x * sin(heading) + y * cos(heading),
            radius * sin(angle) * sin(inclination),
        )
    }
    val earth = orbit(6f, phase * 10f - 1.85f, 1.25f)
    fun relative(
        kind: SolarKind,
        distance: Float,
        speed: Int,
        offset: Float,
        tilt: Float,
        size: Float,
    ): SolarBody {
        val p = orbit(distance, phase * speed + offset, tilt)
        return SolarBody(kind, Vector3(p.x - earth.x, p.y - earth.y, p.z - earth.z), size)
    }
    return SolarFrame(
        earth,
        listOf(
            SolarBody(SolarKind.SUN, Vector3(-earth.x, -earth.y, -earth.z), .64f),
            relative(SolarKind.MERCURY, 2.8f, 40, 2f, 1.10f, .12f),
            relative(SolarKind.VENUS, 4.3f, 16, .25f, 1.18f, .24f),
            relative(SolarKind.MARS, 8.6f, 6, -1f, 1.12f, .20f),
            relative(SolarKind.JUPITER, 12f, 2, -1.45f, 1.18f, .65f),
            relative(SolarKind.SATURN, 16f, 1, -1.7f, 1.32f, .52f),
        ),
    )
}

internal data class SolarProjection(val x: Float, val y: Float, val scale: Float)

internal fun projectSolarBody(position: Vector3): SolarProjection {
    val scale = 32f / (32f - position.z)
    return SolarProjection(position.x * scale, -position.y * scale, scale)
}
