package net.paxtech.workspace

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class MoonSceneTest {
    @Test
    fun sameLunarHemisphereFacesEarthThroughoutOrbit() {
        for (second in 0..160) {
            val moon = moonPose(second.toFloat())
            val radial = moon.position.unit()
            assertEquals(-1f, moon.surface(Vector3(0f, 0f, 1f)).dot(radial), .00001f)
            assertEquals(0f, moon.right.dot(moon.north), .00001f)
            assertEquals(1f, moon.right.cross(moon.north).dot(moon.nearSide), .00001f)
            assertEquals(3.1f, sqrt(moon.position.dot(moon.position)), .00001f)
        }
        // Synchronous rotation must actually rotate in space, not keep the texture camera-facing.
        assertEquals(0f, moonPose(0f).nearSide.dot(moonPose(20f).nearSide), .00001f)
    }

    @Test
    fun cycleClosesAndCrossingOldAnimationLoopDoesNotJump() {
        val first = moonPose(0f)
        val next = moonPose(80f)
        assertEquals(first.position.x, next.position.x, .00001f)
        assertEquals(first.position.y, next.position.y, .00001f)
        assertEquals(1f, first.nearSide.dot(next.nearSide), .00001f)
        val before = moonPose(41.999f)
        val after = moonPose(42.001f)
        assertTrue(abs(before.position.x - after.position.x) < .001f)
        assertTrue(abs(before.position.z - after.position.z) < .001f)
    }

    @Test
    fun allSevenOtherPlanetsOrbitAndHaveTheirOwnSpin() {
        assertEquals(7, solarOrbits.size)
        assertEquals(8, solarFrame(0f).bodies.size)
        assertTrue(planetSpin(SolarKind.VENUS, 10f) < 0f)
        assertTrue(planetSpin(SolarKind.URANUS, 10f) < 0f)
        assertTrue(planetSpin(SolarKind.JUPITER, 10f) > planetSpin(SolarKind.MERCURY, 10f))
    }
}
