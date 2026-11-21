package com.dazix.caelis.domain.cameramode

import kotlin.math.hypot

/** One sample of a finger on the recording overlay: screen pixels and uptime in milliseconds. */
data class TouchSample(val x: Float, val y: Float, val timeMs: Long)

/** What one finger-down to finger-up became, still in screen pixels. */
sealed interface RecordedGesture {
    data class Tap(val x: Float, val y: Float) : RecordedGesture

    data class Swipe(val points: List<TouchSample>, val durationMs: Long) : RecordedGesture
}

/**
 * How a recording is cut into steps. Pure, so the thresholds are tested and the overlay only feeds
 * samples in.
 */
object RecordingPolicy {
    /** More than this is not "switch a mode" any more, and a replay that long would hold the shot. */
    const val MAX_STEPS = 20

    /** Points kept per swipe: a carousel swipe is a line, and a gesture stroke is a path. */
    const val MAX_SWIPE_POINTS = 16

    const val MIN_SWIPE_MS = 100L
    const val MAX_SWIPE_MS = 3_000L

    /**
     * The pause replayed before a step. At least long enough for a camera UI to react to the step
     * before; at most a few seconds, because a user reading the screen while recording would
     * otherwise make every session wait for it.
     */
    const val MIN_DELAY_MS = 300L
    const val MAX_DELAY_MS = 5_000L

    /**
     * A tap when the finger never left [touchSlopPx] of where it went down, a swipe otherwise. Null
     * for an empty gesture.
     */
    fun classify(samples: List<TouchSample>, touchSlopPx: Float): RecordedGesture? {
        val down = samples.firstOrNull() ?: return null
        val wandered = samples.any { hypot(it.x - down.x, it.y - down.y) > touchSlopPx }
        if (!wandered) return RecordedGesture.Tap(down.x, down.y)
        val duration = (samples.last().timeMs - down.timeMs).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        return RecordedGesture.Swipe(resample(samples, MAX_SWIPE_POINTS), duration)
    }

    /** At most [max] samples, evenly by index, always keeping the first and the last. */
    fun resample(samples: List<TouchSample>, max: Int): List<TouchSample> {
        if (samples.size <= max) return samples
        val last = samples.size - 1
        return (0 until max).map { i -> samples[Math.round(i.toDouble() * last / (max - 1)).toInt()] }
    }

    fun clampDelay(ms: Long): Long = ms.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    fun canAddStep(current: Int): Boolean = current < MAX_STEPS
}
