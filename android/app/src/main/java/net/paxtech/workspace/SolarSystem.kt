package net.paxtech.workspace

import kotlin.math.*

internal enum class SolarKind {
    SUN,
    MERCURY,
    VENUS,
    MARS,
    JUPITER,
    SATURN,
    URANUS,
    NEPTUNE,
}

/** AU and degrees. Fixed J2000 elements, propagated as two-body ellipses, not a live ephemeris. */
internal data class SolarOrbit(
    val axis: Double,
    val eccentricity: Double,
    val inclination: Double,
    val longitude: Double,
    val perihelion: Double,
    val node: Double,
) {
    val periodYears: Double
        get() = axis.pow(1.5)

    fun position(years: Double): Vector3 {
        val mean =
            Math.toRadians(longitude - perihelion) + (years % periodYears) / periodYears * 2 * PI
        var eccentric = mean
        repeat(8) {
            eccentric -=
                (eccentric - eccentricity * sin(eccentric) - mean) /
                    (1 - eccentricity * cos(eccentric))
        }
        val x = axis * (cos(eccentric) - eccentricity)
        val y = axis * sqrt(1 - eccentricity * eccentricity) * sin(eccentric)
        val w = Math.toRadians(perihelion - node)
        val n = Math.toRadians(node)
        val i = Math.toRadians(inclination)
        return Vector3(
            ((cos(w) * cos(n) - sin(w) * sin(n) * cos(i)) * x +
                    (-sin(w) * cos(n) - cos(w) * sin(n) * cos(i)) * y)
                .toFloat(),
            ((cos(w) * sin(n) + sin(w) * cos(n) * cos(i)) * x +
                    (-sin(w) * sin(n) + cos(w) * cos(n) * cos(i)) * y)
                .toFloat(),
            (sin(w) * sin(i) * x + cos(w) * sin(i) * y).toFloat(),
        )
    }
}

// JPL SSD Table 1, J2000: https://ssd.jpl.nasa.gov/planets/approx_pos.html
// Earth uses the Earth-Moon barycentre. Secular drift/perturbations are omitted for this animation.
internal val earthOrbit =
    SolarOrbit(1.00000261, .01671123, -.00001531, 100.46457166, 102.93768193, 0.0)
internal val solarOrbits =
    linkedMapOf(
        SolarKind.MERCURY to
            SolarOrbit(.38709927, .20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593),
        SolarKind.VENUS to
            SolarOrbit(.72333566, .00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255),
        SolarKind.MARS to
            SolarOrbit(1.52371034, .09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891),
        SolarKind.JUPITER to
            SolarOrbit(5.202887, .04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909),
        SolarKind.SATURN to
            SolarOrbit(9.53667594, .05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448),
        SolarKind.URANUS to
            SolarOrbit(19.18916464, .04725744, .77263783, 313.23810451, 170.95427630, 74.01692503),
        SolarKind.NEPTUNE to
            SolarOrbit(30.06992276, .00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574),
    )

internal data class SolarBody(val kind: SolarKind, val position: Vector3, val radius: Float)

internal data class SolarFrame(val earth: Vector3, val bodies: List<SolarBody>) {
    // Shader coordinates use screen-down Y; geometry uses camera-up Y.
    val sunlight: Vector3
        get() = Vector3(-earth.x, earth.y, -earth.z)
}

/**
 * Fixed inertial orientation. Only the camera's position follows Earth; it never chases a planet.
 */
private fun cameraAxes(v: Vector3): Vector3 {
    val longitude = Math.toRadians(42.0)
    val latitude = Math.toRadians(-22.0)
    val roll = Math.toRadians(-15.0)
    val right = -sin(longitude) * v.x + cos(longitude) * v.y
    val up = -sin(latitude) * (cos(longitude) * v.x + sin(longitude) * v.y) + cos(latitude) * v.z
    val back = -cos(latitude) * (cos(longitude) * v.x + sin(longitude) * v.y) - sin(latitude) * v.z
    return Vector3(
        (right * cos(roll) - up * sin(roll)).toFloat(),
        (right * sin(roll) + up * cos(roll)).toFloat(),
        back.toFloat(),
    )
}

/** Continuous shared time: one year per 120 screen seconds. No forced master-loop reset. */
internal fun solarFrame(elapsedYears: Float): SolarFrame {
    val years = .3 + elapsedYears.toDouble()
    val earth = cameraAxes(earthOrbit.position(years))
    fun body(kind: SolarKind, world: Vector3, displayRadius: Float): SolarBody {
        val p = cameraAxes(world)
        return SolarBody(kind, Vector3(p.x - earth.x, p.y - earth.y, p.z - earth.z), displayRadius)
    }
    // Disc radii alone are exaggerated for legibility. AU positions, distances and parallax are
    // not.
    // The nearby Earth remains the hero; the other planets are small distant objects.
    val radii =
        mapOf(
            SolarKind.MERCURY to .0049f,
            SolarKind.VENUS to .0121f,
            SolarKind.MARS to .0068f,
            SolarKind.JUPITER to .140f,
            SolarKind.SATURN to .117f,
            SolarKind.URANUS to .051f,
            SolarKind.NEPTUNE to .049f,
        )
    return SolarFrame(
        earth,
        listOf(body(SolarKind.SUN, Vector3(0f, 0f, 0f), .056f)) +
            solarOrbits.map { (kind, orbit) ->
                body(kind, orbit.position(years), radii.getValue(kind))
            },
    )
}

internal data class SolarProjection(val x: Float, val y: Float, val scale: Float)

/** Readable display spin periods, not astronomical day lengths; retrograde signs are retained. */
internal fun planetSpin(kind: SolarKind, seconds: Float): Float {
    val period =
        when (kind) {
            SolarKind.SUN -> 200f
            SolarKind.MERCURY -> 180f
            SolarKind.VENUS -> -220f
            SolarKind.MARS -> 68f
            SolarKind.JUPITER -> 32f
            SolarKind.SATURN -> 35f
            SolarKind.URANUS -> -44f
            SolarKind.NEPTUNE -> 42f
        }
    return seconds / period * (2 * PI).toFloat()
}

/** Pinhole camera, about four Earth radii away. Objects behind it must never mirror into view. */
internal fun projectSolarBody(position: Vector3): SolarProjection? {
    val depth = .00017f - position.z
    if (depth <= .00001f) return null
    val scale = 5f / depth
    return SolarProjection(position.x * scale, -position.y * scale, scale)
}
