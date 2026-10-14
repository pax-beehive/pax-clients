package net.paxtech.workspace

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class SolarSystemTest {
    private fun distance(v: Vector3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z).toDouble()

    @Test
    fun eccentricOrbitHasFocusAtSunAndSpeedsUpAtPerihelion() {
        val orbit = SolarOrbit(2.0, .2, 0.0, 0.0, 0.0, 0.0)
        assertEquals(1.6, distance(orbit.position(0.0)), .00001)
        assertEquals(2.4, distance(orbit.position(orbit.periodYears / 2)), .00001)
        fun movement(t: Double): Double {
            val a = orbit.position(t)
            val b = orbit.position(t + .001)
            return distance(Vector3(b.x - a.x, b.y - a.y, b.z - a.z))
        }
        assertEquals(1.5, movement(0.0) / movement(orbit.periodYears / 2), .001)
        // Equal time intervals sweep equal areas, rather than constant angular speed.
        fun area(t: Double): Double {
            val a = orbit.position(t)
            val b = orbit.position(t + .001)
            return (a.x * b.y - a.y * b.x).toDouble() / 2
        }
        assertEquals(area(0.0), area(orbit.periodYears / 2), .000001)
    }

    @Test
    fun periodsAndOrbitalPlanesHaveSolarSystemProportions() {
        assertEquals(.2408, solarOrbits.getValue(SolarKind.MERCURY).periodYears, .001)
        assertEquals(11.86, solarOrbits.getValue(SolarKind.JUPITER).periodYears, .02)
        assertEquals(29.45, solarOrbits.getValue(SolarKind.SATURN).periodYears, .02)
        for (orbit in solarOrbits.values) {
            for (step in 0..100) {
                val p = orbit.position(step * orbit.periodYears / 100)
                val latitude = asin(abs(p.z) / distance(p)) * 180 / PI
                assertTrue(latitude <= orbit.inclination + .00001)
            }
        }
    }

    @Test
    fun earthAtJ2000MatchesReferenceDirectionAndDistance() {
        val earth = earthOrbit.position(0.0)
        assertEquals(-.17717, earth.x.toDouble(), .0001)
        assertEquals(.96721, earth.y.toDouble(), .0001)
        assertEquals(.9833, distance(earth), .0001)
    }

    @Test
    fun trackingCameraKeepsEarthFixedAndSunAtHeliocentricOrigin() {
        for (year in listOf(0f, .25f, .5f, 1f, 7f)) {
            val frame = solarFrame(year)
            val sun = frame.bodies.single { it.kind == SolarKind.SUN }
            assertEquals(-frame.earth.x, sun.position.x, 0f)
            assertEquals(-frame.earth.y, sun.position.y, 0f)
            assertEquals(-frame.earth.z, sun.position.z, 0f)
            assertEquals(distance(earthOrbit.position(.3 + year)), distance(sun.position), .000001)
        }
        val target = requireNotNull(projectSolarBody(Vector3(0f, 0f, 0f)))
        assertEquals(0f, target.x, 0f)
        assertEquals(0f, target.y, 0f)
    }

    @Test
    fun perspectiveUsesDepthAndCullsBehindCamera() {
        val near = requireNotNull(projectSolarBody(Vector3(1f, 1f, -1f)))
        val far = requireNotNull(projectSolarBody(Vector3(2f, 2f, -2f)))
        assertEquals(near.x, far.x, .001f)
        assertEquals(near.scale / 2, far.scale, .001f)
        assertNull(projectSolarBody(Vector3(1f, 0f, 1f)))
        assertNull(projectSolarBody(Vector3(0f, 0f, .00017f)))
    }

    @Test
    fun crossingFormerFourteenMinuteBoundaryDoesNotResetPlanets() {
        val before = solarFrame(7f - .00001f)
        val after = solarFrame(7f + .00001f)
        before.bodies.zip(after.bodies).forEach { (a, b) ->
            assertTrue(
                distance(
                    Vector3(
                        b.position.x - a.position.x,
                        b.position.y - a.position.y,
                        b.position.z - a.position.z,
                    )
                ) < .001
            )
        }
        val firstJupiter = solarFrame(0f).bodies.single { it.kind == SolarKind.JUPITER }
        val nextJupiter = solarFrame(1f).bodies.single { it.kind == SolarKind.JUPITER }
        assertTrue(abs(firstJupiter.position.x - nextJupiter.position.x) > .5f)
    }
}
