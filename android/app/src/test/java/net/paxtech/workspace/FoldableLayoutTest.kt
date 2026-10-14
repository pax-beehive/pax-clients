package net.paxtech.workspace

import org.junit.Assert.*
import org.junit.Test

class FoldableLayoutTest {
    @Test
    fun compactShowsExactlyOnePane() {
        assertNull(panePlan(400, 900, 1f, false).detail)
        assertNull(panePlan(400, 900, 1f, true).home)
        assertEquals(PaneBounds(0, 0, 400, 900), panePlan(400, 900, 1f, true).detail)
    }

    @Test
    fun expandedShowsMasterAndDetailAtAnyPixelDensity() {
        for (density in listOf(1f, 2f, 3f)) {
            val plan = panePlan((800 * density).toInt(), 1800, density, true)
            assertNotNull(plan.home)
            assertNotNull(plan.detail)
            assertTrue(plan.home!!.right < plan.detail!!.left)
        }
    }

    @Test
    fun separatesBothPanesAroundBookHinge() {
        val plan = panePlan(1600, 1800, 2f, true, PaneBounds(780, 0, 820, 1800))
        assertEquals(780, plan.home!!.right)
        assertEquals(820, plan.detail!!.left)
    }

    @Test
    fun tabletopNeverPlacesContentAcrossTheFold() {
        val plan = panePlan(1000, 1600, 2f, true, PaneBounds(0, 780, 1000, 820), false)
        assertEquals(780, plan.home!!.bottom)
        assertEquals(820, plan.detail!!.top)
    }

    @Test
    fun smallSplitWindowUsesTheLargerUnobstructedPane() {
        val plan = panePlan(500, 900, 2f, true, PaneBounds(180, 0, 200, 900))
        assertNull(plan.home)
        assertEquals(PaneBounds(200, 0, 500, 900), plan.detail)
    }
}
