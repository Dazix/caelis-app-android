package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DeflickerCurveTest {

    @Test
    fun steadyExposureNeedsNoCorrection() {
        DeflickerCurve.gains(DoubleArray(20) { 120.0 }).forEach { assertEquals(1.0, it, 1e-9) }
    }

    @Test
    fun aSunsetDarkeningSlowlyIsKept() {
        // The scene is meant to get darker: deflicker must not flatten the day into the night.
        val lum = DoubleArray(60) { 200.0 - it * 2.0 }
        val gains = DeflickerCurve.gains(lum)
        for (i in DeflickerCurve.DEFAULT_RADIUS until lum.size - DeflickerCurve.DEFAULT_RADIUS) {
            assertEquals(1.0, gains[i], 1e-9)
        }
    }

    @Test
    fun aSingleBrightFrameIsPulledBackToItsNeighbours() {
        val lum = DoubleArray(21) { 100.0 }.also { it[10] = 130.0 }
        val gains = DeflickerCurve.gains(lum)
        assertEquals(100.0, lum[10] * gains[10], 1.0)
    }

    @Test
    fun alternatingFlickerIsSmoothed() {
        val lum = DoubleArray(40) { if (it % 2 == 0) 110.0 else 90.0 }
        val corrected = DeflickerCurve.gains(lum).mapIndexed { i, g -> lum[i] * g }
        val inner = corrected.subList(5, 35)
        val spread = inner.max() - inner.min()
        assertTrue("spread $spread", spread < 4.0)
    }

    @Test
    fun theGainIsBoundedSoABlackFrameIsNotBlownUp() {
        val lum = DoubleArray(11) { 120.0 }.also { it[5] = 2.0 }
        val gains = DeflickerCurve.gains(lum)
        assertEquals(DeflickerCurve.MAX_GAIN, gains[5], 1e-9)
        gains.forEachIndexed { i, g -> if (i != 5) assertTrue(abs(g - 1.0) < 0.2) }
    }

    @Test
    fun aZeroLuminanceFrameIsLeftAlone() {
        val gains = DeflickerCurve.gains(doubleArrayOf(0.0, 100.0, 100.0))
        assertEquals(1.0, gains[0], 1e-9)
    }
}
