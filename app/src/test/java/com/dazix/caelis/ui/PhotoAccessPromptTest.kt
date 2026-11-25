package com.dazix.caelis.ui

import com.dazix.caelis.ui.PhotoAccessPrompt.Access
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAccessPromptTest {

    @Test
    fun `full access wins over a recorded partial grant`() {
        assertEquals(Access.GRANTED, PhotoAccessPrompt.access(granted = true, partial = true))
        assertEquals(Access.PARTIAL, PhotoAccessPrompt.access(granted = false, partial = true))
        assertEquals(Access.MISSING, PhotoAccessPrompt.access(granted = false, partial = false))
    }

    @Test
    fun `switching on without full access explains`() {
        assertTrue(PhotoAccessPrompt.explainOnToggle(turnedOn = true, access = Access.MISSING))
        // "Select photos" cannot contain a photo that does not exist yet.
        assertTrue(PhotoAccessPrompt.explainOnToggle(turnedOn = true, access = Access.PARTIAL))
    }

    @Test
    fun `allow leads to the app settings only after a partial grant`() {
        // Asked again after "Select photos", Android opens only the picker for more photos.
        assertTrue(PhotoAccessPrompt.allowOpensSettings(Access.PARTIAL))
        assertFalse(PhotoAccessPrompt.allowOpensSettings(Access.MISSING))
        assertFalse(PhotoAccessPrompt.allowOpensSettings(Access.GRANTED))
    }

    @Test
    fun `switching on with access or switching off asks nothing`() {
        assertFalse(PhotoAccessPrompt.explainOnToggle(turnedOn = true, access = Access.GRANTED))
        Access.entries.forEach { access ->
            assertFalse(PhotoAccessPrompt.explainOnToggle(turnedOn = false, access = access))
        }
    }

    @Test
    fun `a refused or dismissed dialog switches verification off, selected photos keep it`() {
        assertTrue(PhotoAccessPrompt.verifyOffAfterAnswer(Access.MISSING))
        assertFalse(PhotoAccessPrompt.verifyOffAfterAnswer(Access.PARTIAL))
        assertFalse(PhotoAccessPrompt.verifyOffAfterAnswer(Access.GRANTED))
    }

    @Test
    fun `the status is shown only while the setting can take effect`() {
        assertTrue(PhotoAccessPrompt.showStatus(enabled = true, hasApp = true))
        assertFalse(PhotoAccessPrompt.showStatus(enabled = true, hasApp = false))
        assertFalse(PhotoAccessPrompt.showStatus(enabled = false, hasApp = true))
    }
}
