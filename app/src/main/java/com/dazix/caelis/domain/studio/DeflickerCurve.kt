package com.dazix.caelis.domain.studio

/**
 * Per-frame brightness gains that remove flicker and keep the trend. The phone's camera picks its
 * exposure per shot, so a time-lapse jumps by a few percent from frame to frame; a sunset that slowly
 * darkens is the subject and must survive.
 *
 * The target for each frame is a trimmed mean of its neighbourhood: trimming drops a single outlier
 * (one frame the camera exposed differently) completely, where a plain mean would let it pull its
 * neighbours. A plain median was tried on paper and fails alternating flicker: it picks whichever
 * value has the majority in the window, which changes every frame.
 */
object DeflickerCurve {

    const val DEFAULT_RADIUS = 5
    const val MIN_GAIN = 0.5
    const val MAX_GAIN = 2.0

    /** [luminance] is the mean luma (0–255) of each frame, measured on a downsampled copy. */
    fun gains(luminance: DoubleArray, radius: Int = DEFAULT_RADIUS): DoubleArray {
        require(radius >= 1)
        val n = luminance.size
        val window = DoubleArray(2 * radius + 1)
        return DoubleArray(n) { i ->
            val own = luminance[i]
            if (own <= 0.0) return@DoubleArray 1.0
            val from = maxOf(0, i - radius)
            val to = minOf(n - 1, i + radius)
            val count = to - from + 1
            for (k in 0 until count) window[k] = luminance[from + k]
            window.sort(0, count)
            val trim = if (count >= 3) maxOf(1, count / 5) else 0
            var sum = 0.0
            for (k in trim until count - trim) sum += window[k]
            val target = sum / (count - 2 * trim)
            (target / own).coerceIn(MIN_GAIN, MAX_GAIN)
        }
    }
}
