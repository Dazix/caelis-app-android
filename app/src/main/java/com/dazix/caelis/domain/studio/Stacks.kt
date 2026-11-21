package com.dazix.caelis.domain.studio

/*
 * Stacking works on one band of rows at a time, in the packed ARGB_8888 layout `Bitmap.getPixels`
 * writes. A whole 12 MP frame as per-channel sums would be ~144 MB; a band of a few hundred rows
 * keeps the accumulator small enough for any phone the app runs on. The caller walks the bands and
 * feeds every frame's copy of the same band to one stack.
 */

private const val OPAQUE = 0xFF shl 24

private fun pack(r: Int, g: Int, b: Int) = OPAQUE or (r shl 16) or (g shl 8) or b

/**
 * Star trails: the brightest value each pixel ever had. With [decay] below 1 the accumulated image
 * fades by that factor before every new frame, so the trail tapers off like a comet's tail.
 */
class LightenStack(private val size: Int, private val decay: Double = 1.0) {

    init {
        require(decay in 0.0..1.0) { "decay must be within 0..1" }
    }

    private val acc = IntArray(size) { OPAQUE }

    fun add(band: IntArray) {
        require(band.size == size) { "band of ${band.size} pixels, stack of $size" }
        val fading = decay < 1.0
        for (i in 0 until size) {
            val a = acc[i]
            var r = (a shr 16) and 0xFF
            var g = (a shr 8) and 0xFF
            var b = a and 0xFF
            if (fading) {
                r = (r * decay + 0.5).toInt()
                g = (g * decay + 0.5).toInt()
                b = (b * decay + 0.5).toInt()
            }
            val p = band[i]
            acc[i] = pack(
                maxOf(r, (p shr 16) and 0xFF),
                maxOf(g, (p shr 8) and 0xFF),
                maxOf(b, p and 0xFF),
            )
        }
    }

    fun result(): IntArray = acc.copyOf()
}

/** Averaging: noise falls with the square root of the frame count, motion turns into a long exposure. */
class MeanStack(private val size: Int) {

    private val r = IntArray(size)
    private val g = IntArray(size)
    private val b = IntArray(size)

    var count = 0
        private set

    fun add(band: IntArray) {
        require(band.size == size) { "band of ${band.size} pixels, stack of $size" }
        for (i in 0 until size) {
            val p = band[i]
            r[i] += (p shr 16) and 0xFF
            g[i] += (p shr 8) and 0xFF
            b[i] += p and 0xFF
        }
        count++
    }

    fun result(): IntArray {
        check(count > 0) { "no frame was added" }
        val half = count / 2
        return IntArray(size) { i -> pack((r[i] + half) / count, (g[i] + half) / count, (b[i] + half) / count) }
    }
}
