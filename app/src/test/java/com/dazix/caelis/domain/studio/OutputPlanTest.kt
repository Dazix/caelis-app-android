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
    fun theCropSlidesAlongTheCutAxis() {
        val top = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.CROP, y = 0f)
        assertEquals(Crop(0, 0, 4000, 2250), top.crop)
        val bottom = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.CROP, y = 1f)
        assertEquals(Crop(0, 750, 4000, 2250), bottom.crop)
        val left = OutputPlan.frame(4000, 3000, Aspect.R1_1, Resolution.FHD, Framing.CROP, x = 0.25f)
        assertEquals(Crop(250, 0, 3000, 3000), left.crop)
        // A place out of range is clamped; at zoom 1 the uncut axis has no room to move.
        assertEquals(bottom.crop, OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.CROP, x = 7f, y = 7f).crop)
    }

    @Test
    fun zoomShrinksTheCutAndFreesBothAxesWithoutShrinkingTheVideo() {
        val plan = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.CROP, zoom = 2f, x = 0f, y = 1f)
        assertEquals(Crop(0, 1875, 2000, 1125), plan.crop)
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        // Original aspect zooms too, keeping the source's shape.
        val original = OutputPlan.frame(4000, 3000, Aspect.ORIGINAL, Resolution.FHD, Framing.CROP, zoom = 2f)
        assertEquals(Crop(1000, 750, 2000, 1500), original.crop)
        // Clamped to MIN_ZOOM..MAX_ZOOM; FIT ignores zoom and place.
        assertEquals(1000, OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, zoom = 99f).crop.width)
        assertEquals(
            OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.FIT).crop,
            OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.FIT, zoom = 3f, x = 0f).crop,
        )
    }

    @Test
    fun zoomingOutReachesPastThePictureWithoutChangingTheVideoSize() {
        // Half zoom: a window twice the cut, centred, black around the picture.
        val half = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.CROP, zoom = 0.5f)
        assertEquals(Crop(-2000, -750, 8000, 4500), half.crop)
        assertEquals(1920, half.width)
        assertEquals(1080, half.height)
        // Placed flush left: the picture at the window's left edge, the black to its right.
        val left = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.CROP, zoom = 0.5f, x = 0f)
        assertEquals(0, left.crop.x)
        // Below MIN_ZOOM is clamped.
        assertEquals(16000, OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, zoom = 0.01f).crop.width)
    }

    @Test
    fun theWindowTransformStretchesTheWindowOntoTheOutput() {
        // The whole picture maps to itself.
        val same = OutputPlan.windowTransform(NdcCrop(-1f, 1f, -1f, 1f))
        assertEquals(1f, same.scaleX, 1e-6f)
        assertEquals(1f, same.scaleY, 1e-6f)
        assertEquals(0f, same.translateX, 1e-6f)
        assertEquals(0f, same.translateY, 1e-6f)
        // The top half (y up): doubled vertically, moved down by one.
        val top = OutputPlan.windowTransform(NdcCrop(-1f, 1f, 0f, 1f))
        assertEquals(2f, top.scaleY, 1e-6f)
        assertEquals(-1f, top.translateY, 1e-6f)
        // A window twice the picture: halved, so black surrounds it.
        assertEquals(0.5f, OutputPlan.windowTransform(NdcCrop(-2f, 2f, -2f, 2f)).scaleX, 1e-6f)
    }

    @Test
    fun fitKeepsTheWholePictureInAFrameOfTheAspect() {
        // 4:3 into 16:9: full height, black bars at the sides.
        val wide = OutputPlan.frame(4000, 3000, Aspect.R16_9, Resolution.FHD, Framing.FIT)
        assertEquals(Crop(-666, 0, 5333, 3000), wide.crop)
        assertEquals(1920, wide.width)
        assertEquals(1080, wide.height)
        // 4:3 landscape into 9:16: a portrait frame, bars above and below.
        val tall = OutputPlan.frame(4000, 3000, Aspect.R9_16, Resolution.FHD, Framing.FIT)
        assertEquals(1080, tall.width)
        assertEquals(1920, tall.height)
        val square = OutputPlan.frame(4000, 3000, Aspect.R1_1, Resolution.FHD, Framing.FIT)
        assertEquals(1080, square.width)
        assertEquals(1080, square.height)
    }

    @Test
    fun theNdcCropIsYUp() {
        val ndc = OutputPlan.ndc(Crop(0, 0, 4000, 2250), 4000, 3000)
        assertEquals(-1f, ndc.left, 1e-6f)
        assertEquals(1f, ndc.right, 1e-6f)
        assertEquals(1f, ndc.top, 1e-6f)
        assertEquals(-0.5f, ndc.bottom, 1e-6f)
        assertEquals(NdcCrop(-1f, 1f, -1f, 1f), OutputPlan.ndc(Crop(0, 0, 4000, 3000), 4000, 3000))
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
