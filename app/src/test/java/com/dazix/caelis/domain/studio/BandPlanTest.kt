package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class BandPlanTest {

    private val mb = 1024L * 1024L

    @Test
    fun aFrameThatFitsIsOneBand() {
        // Lighten, 12.5 MP at 4 B/px = 48 MB, budget 64 MB.
        assertEquals(3072, BandPlan.bandRows(4080, 3072, 4, 64 * mb))
    }

    @Test
    fun aMeanThatDoesNotFitIsSplitEvenly() {
        // Mean at 12 B/px = 143 MB against 64 MB: three bands of 1024 rows, not 1370 + 1370 + 332.
        assertEquals(1024, BandPlan.bandRows(4080, 3072, 12, 64 * mb))
    }

    @Test
    fun aTinyBudgetStillMakesProgressRowByRow() {
        assertEquals(1, BandPlan.bandRows(4000, 3000, 12, 10))
    }

    @Test
    fun theBandsCoverTheWholeFrame() {
        for (h in listOf(1, 7, 1000, 3001)) {
            val rows = BandPlan.bandRows(4000, h, 12, 20 * mb)
            val bands = (h + rows - 1) / rows
            assert(rows * bands >= h && rows * (bands - 1) < h) { "h=$h rows=$rows" }
        }
    }

    @Test
    fun shapesWithAnotherOrientationAreRejected() {
        val shapes = listOf(FrameShape(4000, 3000, 6), FrameShape(4000, 3000, 6), FrameShape(4000, 3000, 1))
        val check = FrameSizes.check(shapes)
        assertEquals(FrameShape(4000, 3000, 6), check.size)
        assertEquals(listOf(2), check.rejected)
    }
}
