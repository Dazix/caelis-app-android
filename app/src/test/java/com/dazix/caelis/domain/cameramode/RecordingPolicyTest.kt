package com.dazix.caelis.domain.cameramode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {

    private val slop = 24f

    @Test
    fun `a finger that stays within the slop is a tap where it went down`() {
        val samples = listOf(TouchSample(100f, 200f, 0), TouchSample(110f, 205f, 60), TouchSample(104f, 198f, 90))
        assertEquals(RecordedGesture.Tap(100f, 200f), RecordingPolicy.classify(samples, slop))
    }

    @Test
    fun `a finger that travels is a swipe with its path and time`() {
        val samples = (0..4).map { TouchSample(800f - it * 150f, 2500f, it * 50L) }
        val swipe = RecordingPolicy.classify(samples, slop) as RecordedGesture.Swipe
        assertEquals(samples, swipe.points)
        assertEquals(200L, swipe.durationMs)
    }

    @Test
    fun `a swipe's duration is kept within what a gesture can replay`() {
        val flick = listOf(TouchSample(0f, 0f, 0), TouchSample(300f, 0f, 10))
        val crawl = listOf(TouchSample(0f, 0f, 0), TouchSample(300f, 0f, 60_000))
        assertEquals(RecordingPolicy.MIN_SWIPE_MS, (RecordingPolicy.classify(flick, slop) as RecordedGesture.Swipe).durationMs)
        assertEquals(RecordingPolicy.MAX_SWIPE_MS, (RecordingPolicy.classify(crawl, slop) as RecordedGesture.Swipe).durationMs)
    }

    @Test
    fun `a long path is thinned to the limit, keeping both ends`() {
        val samples = (0 until 100).map { TouchSample(it.toFloat() * 10, 0f, it.toLong()) }
        val kept = RecordingPolicy.resample(samples, RecordingPolicy.MAX_SWIPE_POINTS)
        assertEquals(RecordingPolicy.MAX_SWIPE_POINTS, kept.size)
        assertEquals(samples.first(), kept.first())
        assertEquals(samples.last(), kept.last())
        assertEquals(kept.sortedBy { it.timeMs }, kept)
    }

    @Test
    fun `no samples is no gesture`() {
        assertNull(RecordingPolicy.classify(emptyList(), slop))
    }

    @Test
    fun `the pause before a step is clamped`() {
        assertEquals(RecordingPolicy.MIN_DELAY_MS, RecordingPolicy.clampDelay(0))
        assertEquals(1_200L, RecordingPolicy.clampDelay(1_200))
        assertEquals(RecordingPolicy.MAX_DELAY_MS, RecordingPolicy.clampDelay(40_000))
    }

    @Test
    fun `a recording stops taking steps at the limit`() {
        assertTrue(RecordingPolicy.canAddStep(RecordingPolicy.MAX_STEPS - 1))
        assertFalse(RecordingPolicy.canAddStep(RecordingPolicy.MAX_STEPS))
    }
}
