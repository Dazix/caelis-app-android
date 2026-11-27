package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputNameTest {

    @Test
    fun theBaseLeavesOutTheExtension() {
        assertEquals("CAELIS_TRAILS_20261127_153832", OutputName.base("CAELIS_TRAILS_20261127_153832.jpg"))
        assertEquals("noext", OutputName.base("noext"))
    }

    @Test
    fun theExtensionIsKept() {
        assertEquals("Krkonoše v noci.jpg", OutputName.renamed("CAELIS_TRAILS_1.jpg", "Krkonoše v noci"))
        assertEquals("západ.mp4", OutputName.renamed("CAELIS_VIDEO_1.mp4", "  západ  "))
    }

    @Test
    fun aTypedExtensionIsNotDoubledAndAnotherIsNotTaken() {
        assertEquals("night.jpg", OutputName.renamed("a.jpg", "night.JPG"))
        assertEquals("night.mp4.jpg", OutputName.renamed("a.jpg", "night.mp4"))
    }

    @Test
    fun pathAndReservedCharactersBecomeUnderscores() {
        assertEquals("a_b_c_d.jpg", OutputName.renamed("x.jpg", "a/b\\c:d"))
        assertEquals("q_.jpg", OutputName.renamed("x.jpg", "q\n"))
    }

    @Test
    fun hiddenAndTrailingDotsAreDropped() {
        assertEquals("night.jpg", OutputName.renamed("x.jpg", "..night.."))
    }

    @Test
    fun nothingUsableIsRefused() {
        assertNull(OutputName.renamed("x.jpg", ""))
        assertNull(OutputName.renamed("x.jpg", "   "))
        assertNull(OutputName.renamed("x.jpg", "..."))
        assertNull(OutputName.renamed("x.jpg", ".jpg"))
    }

    @Test
    fun aLongNameIsCut() {
        val name = OutputName.renamed("x.jpg", "a".repeat(200))!!
        assertEquals(OutputName.MAX_LENGTH + ".jpg".length, name.length)
    }
}
