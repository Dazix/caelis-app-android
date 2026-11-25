package com.dazix.caelis.service

import com.dazix.caelis.domain.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile data rules. All of this is pure Kotlin — no Android class is touched by [CameraProfile],
 * [CameraProfiles] or the `TAP_DURATION_MS` constant they share, so it runs on the JVM.
 */
class CameraProfileTest {

    // ---- interval floor clamping ----------------------------------------------------------------

    @Test
    fun `declared floor shorter than one capture is raised to capture latency plus one tap`() {
        val profile = profile(captureLatencyMs = 2_200L, declaredFloorMs = 1_500L)

        assertEquals(2_200L + ShutterDispatcher.TAP_DURATION_MS, profile.minIntervalMs)
    }

    @Test
    fun `a floor longer than one capture is left alone`() {
        val profile = profile(captureLatencyMs = 900L, declaredFloorMs = 5_000L)

        assertEquals(5_000L, profile.minIntervalMs)
    }

    @Test
    fun `a zero floor still leaves room for the tap itself`() {
        val profile = profile(captureLatencyMs = 0L, declaredFloorMs = 0L)

        assertEquals(ShutterDispatcher.TAP_DURATION_MS, profile.minIntervalMs)
    }

    @Test
    fun `every shipped profile can deliver the interval it advertises`() {
        for (profile in CameraProfiles.all() + CameraProfiles.passthrough) {
            assertTrue(
                "${profile.id} floor ${profile.minIntervalMs} cannot fit its own capture",
                profile.minIntervalMs >= profile.captureLatencyMs + ShutterDispatcher.TAP_DURATION_MS,
            )
        }
    }

    @Test
    fun `the pixel profile floor covers its long capture latency`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertEquals("pixel-camera", pixel.id)
        assertEquals(2_270L, pixel.minIntervalMs)
    }

    // ---- PeriodicTap bypasses the camera profile ------------------------------------------------

    @Test
    fun `periodic tap uses the passthrough profile whatever package is named`() {
        val mode = TriggerMode.PeriodicTap("com.google.android.GoogleCamera")

        val profile = CameraProfiles.forTrigger(mode)

        assertEquals("passthrough", profile.id)
        assertEquals(0L, profile.captureLatencyMs)
        assertTrue(profile.shutterViewIdPatterns.isEmpty())
        assertTrue(profile.longExposureBusyPatterns.isEmpty())
    }

    @Test
    fun `time lapse still gets the camera profile`() {
        val mode = TriggerMode.Timelapse("com.google.android.GoogleCamera")

        assertEquals("pixel-camera", CameraProfiles.forTrigger(mode).id)
    }

    @Test
    fun `periodic tap can be driven far faster than any camera floor`() {
        val floor = CameraProfiles.effectiveFloorMs(
            TriggerMode.PeriodicTap("com.google.android.GoogleCamera"),
            userFloorMs = 0L,
        )

        assertEquals(ShutterDispatcher.TAP_DURATION_MS, floor)
        assertTrue(floor < 900L)
    }

    @Test
    fun `periodic tap keeps a user floor that is longer than one tap`() {
        val floor = CameraProfiles.effectiveFloorMs(TriggerMode.PeriodicTap(null), userFloorMs = 400L)

        assertEquals(400L, floor)
    }

    @Test
    fun `time lapse floor is the camera app floor when the user asked for less`() {
        val floor = CameraProfiles.effectiveFloorMs(
            TriggerMode.Timelapse("com.google.android.GoogleCamera"),
            userFloorMs = 100L,
        )

        assertEquals(2_270L, floor)
    }

    @Test
    fun `time lapse floor keeps a stricter user floor`() {
        val floor = CameraProfiles.effectiveFloorMs(
            TriggerMode.Timelapse("com.google.android.GoogleCamera"),
            userFloorMs = 10_000L,
        )

        assertEquals(10_000L, floor)
    }

    @Test
    fun `an unknown camera package falls back to the generic floor`() {
        val floor = CameraProfiles.effectiveFloorMs(TriggerMode.Timelapse("com.acme.cam"), 0L)

        assertEquals(CameraProfiles.generic.minIntervalMs, floor)
    }

    // ---- mode aliases ---------------------------------------------------------------------------

    @Test
    fun `english mode labels match their keys`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertTrue(pixel.matchesMode(CameraProfile.MODE_PHOTO, "Photo"))
        assertTrue(pixel.matchesMode(CameraProfile.MODE_NIGHT, "Night Sight"))
        assertTrue(pixel.matchesMode(CameraProfile.MODE_ASTRO, "Astrophotography"))
    }

    @Test
    fun `czech mode labels match the same keys`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertTrue(pixel.matchesMode(CameraProfile.MODE_PHOTO, "Foto"))
        assertTrue(pixel.matchesMode(CameraProfile.MODE_PHOTO, "Fotografie"))
        // What Pixel Camera actually shows in Czech; "Foto" alone never found its chip.
        assertTrue(pixel.matchesMode(CameraProfile.MODE_PHOTO, "Fotka"))
        assertTrue(pixel.matchesMode(CameraProfile.MODE_NIGHT, "Noční vidění"))
        assertTrue(pixel.matchesMode(CameraProfile.MODE_NIGHT, "Nocni videni"))
        assertTrue(pixel.matchesMode(CameraProfile.MODE_ASTRO, "Astrofotografie"))
    }

    @Test
    fun `a mode key never matches another mode's label`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertFalse(pixel.matchesMode(CameraProfile.MODE_PHOTO, "Astrofotografie"))
        assertFalse(pixel.matchesMode(CameraProfile.MODE_PHOTO, "Night Sight"))
        assertFalse(pixel.matchesMode(CameraProfile.MODE_NIGHT, "Foto"))
        assertFalse(pixel.matchesMode(CameraProfile.MODE_ASTRO, "Photo"))
    }

    @Test
    fun `mode matching ignores case and surrounding key whitespace`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertTrue(pixel.matchesMode(" PHOTO ", "photo"))
    }

    @Test
    fun `an unknown key resolves to nothing so no chip can be tapped for it`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertTrue(pixel.modePatterns("panorama").isEmpty())
        assertTrue(pixel.modePatterns("portrait").isEmpty())
        assertTrue(pixel.modePatterns(null).isEmpty())
        assertTrue(pixel.modePatterns("").isEmpty())
        assertFalse(pixel.supportsMode("panorama"))
        assertFalse(pixel.matchesMode("panorama", "Panorama"))
    }

    @Test
    fun `the generic profile knows the modes it can offer and admits the ones it cannot`() {
        assertTrue(CameraProfiles.generic.supportsMode(CameraProfile.MODE_PHOTO))
        assertTrue(CameraProfiles.generic.supportsMode(CameraProfile.MODE_NIGHT))
        assertFalse(CameraProfiles.generic.supportsMode(CameraProfile.MODE_ASTRO))
    }

    @Test
    fun `a chip a profile can recognise is not automatically a mode it offers`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        // Portrait is a mode chip the switcher must be able to see, but starting a series in it
        // makes no sense, so it is not an offerable key and the UI never has to label it.
        assertTrue(pixel.eligibleModeChipPatterns.matchesAny("Portrait"))
        assertFalse(pixel.supportsMode("portrait"))
        assertEquals(setOf("photo", "night", "astro"), pixel.supportedModeKeys())
    }

    // ---- which node is the mode chip ------------------------------------------------------------

    /**
     * Pixel Camera on a Pixel 7 Pro (Czech, Photo mode, low light), from a uiautomator dump. Three
     * nodes carry "noční": the mode chip, the minibar's Night Sight icon at the top of the screen,
     * and the shutter. The minibar icon comes first in the tree, and tapping it opened the
     * "Nastavení fotek" sheet instead of switching to Night Sight, before the first shot.
     */
    private val pixelMinibarNight = "com.google.android.GoogleCamera:id/minibar_item_ext2" to "Noční vidění"
    private val pixelShutter = "ComposeShutter" to "Vyfotit pomocí nočního vidění"
    private val pixelNightChip = "com.google.android.GoogleCamera:id/mode_chip_text" to "Přepnout na noční vidění"
    private val pixelPhotoChip = "com.google.android.GoogleCamera:id/mode_chip_text" to "Fotka"

    @Test
    fun `only the mode carousel's chip counts as the pixel night mode chip`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")
        val night = CameraProfile.MODE_NIGHT

        assertTrue(pixel.isModeChipFor(night, pixelNightChip.first, pixelNightChip.second))
        assertTrue(pixel.isModeChipFor(night, pixelNightChip.first, "Noční vidění"))
        assertFalse(pixel.isModeChipFor(night, pixelMinibarNight.first, pixelMinibarNight.second))
        assertFalse(pixel.isModeChipFor(night, pixelShutter.first, pixelShutter.second))
        assertFalse(pixel.isModeChipFor(night, null, "Noční vidění"))
    }

    @Test
    fun `a chip of another mode is never the chip for the wanted one`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertTrue(pixel.isModeChipFor(CameraProfile.MODE_PHOTO, pixelPhotoChip.first, pixelPhotoChip.second))
        assertFalse(pixel.isModeChipFor(CameraProfile.MODE_NIGHT, pixelPhotoChip.first, pixelPhotoChip.second))
        assertFalse(pixel.isModeChipFor(CameraProfile.MODE_PHOTO, pixelNightChip.first, pixelNightChip.second))
    }

    @Test
    fun `a profile that names no chip view id still goes by the label alone`() {
        val generic = CameraProfiles.generic

        assertTrue(generic.isModeChipFor(CameraProfile.MODE_NIGHT, null, "Night"))
        assertTrue(generic.isModeChipFor(CameraProfile.MODE_NIGHT, "com.acme.cam:id/anything", "Night"))
        assertFalse(generic.isModeChipFor(CameraProfile.MODE_NIGHT, null, "Photo"))
        assertFalse(generic.isModeChipFor(CameraProfile.MODE_ASTRO, null, "Astro"))
    }

    @Test
    fun `the ui mode keys are exactly the ones the profiles answer to`() {
        // AdvancedScreen stores these three literals; a rename on either side breaks the feature
        // silently, which is the whole reason the keys are constants.
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")

        assertEquals("photo", CameraProfile.MODE_PHOTO)
        assertEquals("night", CameraProfile.MODE_NIGHT)
        assertEquals("astro", CameraProfile.MODE_ASTRO)
        assertEquals(setOf("photo", "night", "astro"), pixel.supportedModeKeys())
    }

    @Test
    fun `every alias label is an eligible chip so a mode switch is never blocked by the chip list`() {
        for (profile in CameraProfiles.all()) {
            for ((key, patterns) in profile.modeAliases) {
                assertTrue(
                    "${profile.id}/$key has aliases but no eligible chip pattern",
                    patterns.isEmpty() || profile.eligibleModeChipPatterns.isNotEmpty(),
                )
            }
        }
        // A profile without an explicit chip list falls back to its own aliases.
        assertEquals(
            CameraProfiles.generic.modeAliases.values.flatten().size,
            CameraProfiles.generic.eligibleModeChipPatterns.size,
        )
    }

    private fun profile(captureLatencyMs: Long, declaredFloorMs: Long) = CameraProfile(
        id = "test",
        packageNames = emptyList(),
        shutterViewIdPatterns = emptyList(),
        shutterDescriptionPatterns = emptyList(),
        captureLatencyMs = captureLatencyMs,
        declaredMinIntervalMs = declaredFloorMs,
    )
}
