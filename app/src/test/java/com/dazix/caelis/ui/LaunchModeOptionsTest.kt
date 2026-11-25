package com.dazix.caelis.ui

import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.service.CameraProfile
import com.dazix.caelis.service.CameraProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchModeOptionsTest {

    private val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")
    private val generic = CameraProfiles.generic

    @Test
    fun `the offered modes are the profile's own keys`() {
        assertEquals(pixel.supportedModeKeys().toList(), LaunchModeOptions.keysFor(pixel, hasApp = true))
        assertTrue(CameraProfile.MODE_ASTRO in LaunchModeOptions.keysFor(pixel, hasApp = true))
    }

    @Test
    fun `a profile without a mode the user stored offers nothing of it`() {
        assertTrue(CameraProfile.MODE_ASTRO !in LaunchModeOptions.keysFor(generic, hasApp = true))
    }

    @Test
    fun `choosing a long exposure mode turns on the interval after capture`() {
        // Night Sight / Astro run for minutes, and a tap during them cancels the photo.
        val night = LaunchModeOptions.choose(AdvancedSettings(), CameraProfile.MODE_NIGHT)
        assertEquals(CameraProfile.MODE_NIGHT, night.launchCameraMode)
        assertEquals(true, night.intervalAfterCapture)
        assertEquals(true, LaunchModeOptions.choose(AdvancedSettings(), CameraProfile.MODE_ASTRO).intervalAfterCapture)
    }

    @Test
    fun `choosing another mode leaves the interval setting as the user had it`() {
        val on = AdvancedSettings(intervalAfterCapture = true)
        assertEquals(true, LaunchModeOptions.choose(on, CameraProfile.MODE_PHOTO).intervalAfterCapture)
        assertEquals(true, LaunchModeOptions.choose(on, null).intervalAfterCapture)
        assertEquals(false, LaunchModeOptions.choose(AdvancedSettings(), CameraProfile.MODE_PHOTO).intervalAfterCapture)
        assertNull(LaunchModeOptions.choose(on, null).launchCameraMode)
    }

    @Test
    fun `nothing is offered until an app is chosen`() {
        assertTrue(LaunchModeOptions.keysFor(pixel, hasApp = false).isEmpty())
    }

    @Test
    fun `a stored key the profile knows stays selected`() {
        val settings = AdvancedSettings(launchCameraMode = CameraProfile.MODE_NIGHT)

        assertEquals(CameraProfile.MODE_NIGHT, LaunchModeOptions.selected(settings, pixel, hasApp = true))
    }

    @Test
    fun `a key kept from another app is off, not silently applied`() {
        val settings = AdvancedSettings(launchCameraMode = CameraProfile.MODE_ASTRO)

        assertNull(LaunchModeOptions.selected(settings, generic, hasApp = true))
    }

    @Test
    fun `without a chosen app every extra is off`() {
        val settings = AdvancedSettings(launchCameraMode = CameraProfile.MODE_PHOTO)

        assertNull(LaunchModeOptions.selected(settings, pixel, hasApp = false))
    }

    /**
     * Every extra that reaches into the camera app's UI is opt-in, so a default install taps the
     * shutter and touches nothing else. `keepScreenOn` is deliberately not in this list: it is not an
     * extra but a precondition — the engine will not tap a dark screen — and it is asserted against
     * the panel's own default in `SettingsMappingTest`.
     */
    @Test
    fun `the default settings arm nothing that touches the camera app`() {
        val defaults = AdvancedSettings()

        assertNull(LaunchModeOptions.selected(defaults, pixel, hasApp = true))
        assertEquals(false, defaults.intervalAfterCapture)
        assertEquals(false, defaults.ensureSelfTimerOff)
        assertEquals(false, defaults.dismissPostCaptureUi)
        assertEquals(false, defaults.dimOverlay)
    }

    @Test
    fun `a blank stored key is treated as off`() {
        val settings = AdvancedSettings(launchCameraMode = "  ")

        assertNull(LaunchModeOptions.selected(settings, pixel, hasApp = true))
    }

    // ---- the profile the advanced screen must be gated on ----------------------------------------

    /**
     * The extras have to be offered against the profile the *session* will run with, which for a
     * periodic tap is always `passthrough` — whatever app it names. Gated on the package alone, the
     * screen offered "launch in Night Sight" and the adaptive long-exposure wait for a periodic tap on
     * Google Camera, and both then silently did nothing: the session holds no camera assumptions at
     * all, so there are no mode chips to tap and no busy patterns to wait on.
     */
    @Test
    fun `a periodic tap offers no camera extras, because the session runs passthrough`() {
        val state = SetupUiState(
            mode = ModeChoice.PERIODIC_TAP,
            targetPackage = "com.google.android.GoogleCamera",
            targetLabel = "Camera",
        )

        val profile = CameraProfiles.forTrigger(state.triggerMode())

        assertEquals("passthrough", profile.id)
        assertTrue(LaunchModeOptions.keysFor(profile, hasApp = true).isEmpty())
        assertTrue(profile.longExposureBusyPatterns.isEmpty())
        assertTrue(profile.selfTimerOnPatterns.isEmpty())
        assertTrue(profile.postCaptureDismissPatterns.isEmpty())
    }

    @Test
    fun `a time lapse on the same app still offers them`() {
        val state = SetupUiState(
            mode = ModeChoice.TIMELAPSE,
            targetPackage = "com.google.android.GoogleCamera",
            targetLabel = "Camera",
        )

        val profile = CameraProfiles.forTrigger(state.triggerMode())

        assertEquals("pixel-camera", profile.id)
        assertTrue(CameraProfile.MODE_NIGHT in LaunchModeOptions.keysFor(profile, hasApp = true))
        assertTrue(profile.longExposureBusyPatterns.isNotEmpty())
    }
}
