package com.dazix.caelis.domain.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StackTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun lightenKeepsTheBrightestValuePerChannel() {
        val stack = LightenStack(2)
        stack.add(intArrayOf(argb(10, 200, 0), argb(0, 0, 0)))
        stack.add(intArrayOf(argb(100, 50, 5), argb(255, 1, 2)))
        assertArrayEquals(intArrayOf(argb(100, 200, 5), argb(255, 1, 2)), stack.result())
    }

    @Test
    fun aStarMovingAcrossLeavesATrail() {
        val stack = LightenStack(4)
        for (i in 0 until 4) {
            val frame = IntArray(4) { argb(5, 5, 5) }
            frame[i] = argb(250, 250, 250)
            stack.add(frame)
        }
        assertArrayEquals(IntArray(4) { argb(250, 250, 250) }, stack.result())
    }

    @Test
    fun cometDecayFadesTheOlderPartOfTheTrail() {
        val stack = LightenStack(2, decay = 0.5)
        stack.add(intArrayOf(argb(200, 200, 200), argb(0, 0, 0)))
        stack.add(intArrayOf(argb(0, 0, 0), argb(200, 200, 200)))
        assertArrayEquals(intArrayOf(argb(100, 100, 100), argb(200, 200, 200)), stack.result())
    }

    @Test
    fun meanAveragesWithRounding() {
        val stack = MeanStack(1)
        stack.add(intArrayOf(argb(0, 10, 255)))
        stack.add(intArrayOf(argb(1, 11, 255)))
        assertArrayEquals(intArrayOf(argb(1, 11, 255)), stack.result())
        assertEquals(2, stack.count)
    }

    @Test
    fun meanOfRandomNoiseConvergesOnTheSignal() {
        val rnd = java.util.Random(7)
        val stack = MeanStack(100)
        repeat(64) {
            stack.add(IntArray(100) { val v = (128 + rnd.nextGaussian() * 20).toInt().coerceIn(0, 255); argb(v, v, v) })
        }
        val reds = stack.result().map { (it shr 16) and 0xFF }
        reds.forEach { assertEquals(128.0, it.toDouble(), 10.0) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBandOfTheWrongSizeIsRefused() {
        LightenStack(3).add(IntArray(2))
    }

    @Test(expected = IllegalStateException::class)
    fun anEmptyMeanHasNoResult() {
        MeanStack(1).result()
    }
}
