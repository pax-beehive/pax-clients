package net.paxtech.workspace

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class SolarSystemTest {
    @Test
    fun trackingCameraKeepsEarthFixedWhileSunMoves() {
        val start = solarFrame(0f)
        val later = solarFrame(.15f)
        assertTrue(abs(start.earth.x - later.earth.x) > 1f)
        for (frame in listOf(start, later)) {
            val sun = frame.bodies.single { it.kind == SolarKind.SUN }
            assertEquals(-frame.earth.x, sun.position.x, .0001f)
            assertEquals(-frame.earth.y, sun.position.y, .0001f)
            assertEquals(-frame.earth.z, sun.position.z, .0001f)
        }
        val target = projectSolarBody(Vector3(0f, 0f, 0f))
        assertEquals(0f, target.x, 0f)
        assertEquals(0f, target.y, 0f)
    }

    @Test
    fun planetsEnterAndLeaveEarthCentredView() {
        // The compact hero is about 5 world units wide. Neighbours must cross its edges.
        for (kind in listOf(SolarKind.MARS, SolarKind.JUPITER, SolarKind.SATURN)) {
            val positions =
                (0..1000).map { step ->
                    projectSolarBody(
                        solarFrame(step * 2f * PI.toFloat() / 1000)
                            .bodies
                            .single { it.kind == kind }
                            .position
                    )
                }
            assertTrue(
                "$kind should enter the field",
                positions.any { abs(it.x) < 2.5f && abs(it.y) < 3.5f },
            )
            assertTrue("$kind should leave the field", positions.any { abs(it.x) > 4f })
            assertTrue(positions.all { it.x.isFinite() && it.y.isFinite() && it.scale > 0f })
        }
    }

    @Test
    fun masterLoopClosesWithoutTeleporting() {
        val first = solarFrame(0f)
        val last = solarFrame(2f * PI.toFloat())
        first.bodies.zip(last.bodies).forEach { (a, b) ->
            assertEquals(a.position.x, b.position.x, .001f)
            assertEquals(a.position.y, b.position.y, .001f)
            assertEquals(a.position.z, b.position.z, .001f)
        }
    }
}
