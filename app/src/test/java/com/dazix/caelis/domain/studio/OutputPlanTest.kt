package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPlanTest {

    @Test
    fun durationFollowsFrameCountAndFps() {
        assertEquals(10_000L, OutputPlan.durationMs(frames = 300, fps = 30))
        assertEquals(0L, OutputPlan.durationMs(frames = 0, fps = 30))
    }

    @Test
    fun originalAspectKeepsTheWholeFrameAndFitsTheBox() {
        val plan = OutputPlan.frame(4080, 3072, Aspect.ORIGINAL, Resolution.FHD)
        assertEquals(Crop(0, 0, 4080, 3072), plan.crop)
        assertEquals(1434, plan.width)
        assertEquals(1080, plan.height)
    }

    @Test
    fun sixteenByNineFromAFourByThreeSensorCropsTopAndBottomEvenly() {
        val plan = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD)
        assertEquals(Crop(0, 375, 4000, 2250), plan.crop)
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
    }

    @Test
    fun portraitSourceGetsAPortraitBox() {
        val plan = OutputPlan.frame(3000, 4000, Aspect.R9_16, Resolution.UHD)
        assertEquals(2160, plan.width)
        assertEquals(3840, plan.height)
        assertEquals(Crop(375, 0, 2250, 4000), plan.crop)
    }

    @Test
    fun squareCropFromLandscape() {
        val plan = OutputPlan.frame(4000, 3000, Aspect.R1_1, Resolution.FHD)
        assertEquals(Crop(500, 0, 3000, 3000), plan.crop)
        assertEquals(1080, plan.width)
        assertEquals(1080, plan.height)
    }

    @Test
    fun aSmallSourceIsNeverUpscaledAndDimensionsAreEven() {
        val plan = OutputPlan.frame(1001, 751, Aspect.ORIGINAL, Resolution.UHD)
        assertEquals(1000, plan.width)
        assertEquals(750, plan.height)
    }

    @Test
    fun theDominantSizeWinsAndOddFramesAreListed() {
        val sizes = listOf(Size(4000, 3000), Size(4000, 3000), Size(3000, 4000), Size(4000, 3000))
        val check = FrameSizes.check(sizes)
        assertEquals(Size(4000, 3000), check.size)
        assertEquals(listOf(2), check.rejected)
    }

    @Test
    fun noFramesNoSize() {
        assertTrue(FrameSizes.check(emptyList<Size>()).size == null)
    }

    @Test
    fun growingTrailFramesEndOnTheLastFrame() {
        assertEquals(listOf(2, 5, 8, 9), OutputPlan.growingTrailSnapshots(frames = 10, every = 3))
        assertEquals(listOf(0), OutputPlan.growingTrailSnapshots(frames = 1, every = 3))
    }
}
