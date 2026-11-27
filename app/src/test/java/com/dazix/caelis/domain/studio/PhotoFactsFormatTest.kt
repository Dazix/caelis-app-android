package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoFactsFormatTest {

    @Test
    fun shortExposuresAreFractionsLongOnesSeconds() {
        assertEquals("1/250 s", PhotoFactsFormat.exposure(0.004))
        assertEquals("1/3 s", PhotoFactsFormat.exposure(0.3333))
        assertEquals("1 s", PhotoFactsFormat.exposure(1.0))
        assertEquals("2.5 s", PhotoFactsFormat.exposure(2.5))
        assertEquals("30 s", PhotoFactsFormat.exposure(30.02))
        assertNull(PhotoFactsFormat.exposure(0.0))
    }

    @Test
    fun exifTimeUsesTheOffsetWhenThereIsOne() {
        val prague = java.time.ZoneId.of("Europe/Prague")
        val utc = java.time.Instant.parse("2026-11-27T14:38:37Z").toEpochMilli()
        assertEquals(utc, PhotoFactsFormat.exifTime("2026:11:27 15:38:37", "+01:00", java.time.ZoneOffset.UTC))
        assertEquals(utc, PhotoFactsFormat.exifTime("2026:11:27 15:38:37", null, prague))
        assertNull(PhotoFactsFormat.exifTime("0000:00:00 00:00:00", null, prague))
        assertNull(PhotoFactsFormat.exifTime(null, null, prague))
    }

    @Test
    fun apertureAndFocal() {
        assertEquals("f/1.8", PhotoFactsFormat.aperture(1.8))
        assertEquals("7 mm", PhotoFactsFormat.focal(6.99))
        assertEquals("6.8 mm", PhotoFactsFormat.focal(6.81))
        assertNull(PhotoFactsFormat.aperture(0.0))
    }

    @Test
    fun theExposureLineLeavesOutWhatIsMissing() {
        val facts = PhotoFacts(exposureS = 0.004, iso = 100)
        assertEquals("1/250 s · ISO 100", PhotoFactsFormat.exposureLine(facts))
        assertNull(PhotoFactsFormat.exposureLine(PhotoFacts(iso = 0)))
    }

    @Test
    fun theMakerIsNotRepeated() {
        assertEquals("Google Pixel 7 Pro", PhotoFactsFormat.camera("Google", "Pixel 7 Pro"))
        assertEquals("samsung SM-S911B", PhotoFactsFormat.camera("samsung", "samsung SM-S911B"))
        assertEquals("Pixel 7 Pro", PhotoFactsFormat.camera(" ", "Pixel 7 Pro"))
        assertNull(PhotoFactsFormat.camera(null, null))
    }

    @Test
    fun redactedOrImpossibleCoordinatesAreNoPosition() {
        assertEquals("50.76543, 15.05678", PhotoFactsFormat.coordinates(50.765432, 15.056781))
        assertNull(PhotoFactsFormat.coordinates(0.0, 0.0))
        assertNull(PhotoFactsFormat.coordinates(91.0, 15.0))
        assertNull(PhotoFactsFormat.coordinates(null, 15.0))
    }

    @Test
    fun dimensionsAndSize() {
        assertEquals("4080 × 3072 · 3.2 MB", PhotoFactsFormat.dimensions(4080, 3072, 3_210_000))
        assertEquals("850 kB", PhotoFactsFormat.dimensions(null, null, 850_200))
        assertNull(PhotoFactsFormat.dimensions(0, 0, null))
    }
}
