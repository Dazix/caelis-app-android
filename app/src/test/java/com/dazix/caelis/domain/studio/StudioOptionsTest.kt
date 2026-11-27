package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioOptionsTest {

    @Test
    fun aFrameRateOutsideTheChoicesFallsBackToTheDefault() {
        assertEquals(30, StudioOptions(fps = 7).sanitized().fps)
        assertEquals(60, StudioOptions(fps = 60).sanitized().fps)
    }

    @Test
    fun theDefaultIsOneOfTheChoices() {
        assertTrue(StudioOptions().fps in StudioOptions.FPS_CHOICES)
    }

    @Test
    fun theShortcutFollowsAFinishedTimelapseWithFrames() {
        assertTrue(StudioShortcut.offer(stopped = true, shotsTaken = 12, timelapse = true))
    }

    @Test
    fun noShortcutWhileRunningWithoutFramesOrForAPeriodicTap() {
        assertFalse(StudioShortcut.offer(stopped = false, shotsTaken = 12, timelapse = true))
        assertFalse(StudioShortcut.offer(stopped = true, shotsTaken = 0, timelapse = true))
        assertFalse(StudioShortcut.offer(stopped = true, shotsTaken = 12, timelapse = false))
    }
}
