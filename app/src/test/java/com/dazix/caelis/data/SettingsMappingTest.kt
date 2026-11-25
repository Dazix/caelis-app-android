package com.dazix.caelis.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.RampDomain
import com.dazix.caelis.domain.RampSpec
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.domain.horizon.DownloadMode
import com.dazix.caelis.domain.nightsky.ConstellationArtOpacity
import com.dazix.caelis.overlay.OverlayOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preference mapping is where a setting silently stops being saved. `MutablePreferences` is plain
 * JVM, so the round-trips can be held down here rather than by noticing on a device that a toggle
 * forgot itself.
 */
class SettingsMappingTest {

    // Surface.ROTATION_*, spelled out: this suite touches no Android class.
    private val ROTATION_0 = 0
    private val ROTATION_90 = 1
    private val ROTATION_180 = 2
    private val ROTATION_270 = 3

    private val PKG = "com.example.cam"

    /** Fractions are stored in natural display space, so both are valid in either rotation. */
    private val portrait = TapTarget(xFraction = 0.5f, yFraction = 0.88f)
    private val landscape = TapTarget(xFraction = 0.88f, yFraction = 0.5f, jitter = true)

    @Test
    fun `the shared AR calibration survives a round trip`() {
        val prefs = mutablePreferencesOf()

        SettingsMapping.writeArCalibration(prefs, azimuthDeg = -2.9f, elevationDeg = 1.3f)

        assertEquals(-2.9f, SettingsMapping.readArAzimuthOffset(prefs), 1e-6f)
        assertEquals(1.3f, SettingsMapping.readArElevationOffset(prefs), 1e-6f)
    }

    /**
     * The compass correction used to be stored twice, once per AR screen. Merging the two must not
     * throw away what someone had already aligned — a silently reset overlay looks like a bug in the
     * sensors, not like a migration.
     */
    @Test
    fun `a calibration stored under the old Sun AR key is inherited`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArAzimuthOffset(prefs, -2.5f)
        SettingsMapping.writeSunArElevationOffset(prefs, 0.75f)

        assertEquals(-2.5f, SettingsMapping.readArAzimuthOffset(prefs), 1e-6f)
        assertEquals(0.75f, SettingsMapping.readArElevationOffset(prefs), 1e-6f)
    }

    @Test
    fun `a calibration stored only under the old Night Sky key is inherited`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyAzimuthOffset(prefs, 4f)
        SettingsMapping.writeNightSkyElevationOffset(prefs, -1.5f)

        assertEquals(4f, SettingsMapping.readArAzimuthOffset(prefs), 1e-6f)
        assertEquals(-1.5f, SettingsMapping.readArElevationOffset(prefs), 1e-6f)
    }

    @Test
    fun `the shared key wins over both old ones`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArAzimuthOffset(prefs, -2.5f)
        SettingsMapping.writeNightSkyAzimuthOffset(prefs, 4f)

        SettingsMapping.writeArCalibration(prefs, azimuthDeg = 0.5f, elevationDeg = 0f)

        assertEquals(0.5f, SettingsMapping.readArAzimuthOffset(prefs), 1e-6f)
    }

    /** Sun AR is where the correction can be measured against a ridge, so it outranks Night Sky. */
    @Test
    fun `Sun AR outranks Night Sky when both old keys exist`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArAzimuthOffset(prefs, -2.5f)
        SettingsMapping.writeNightSkyAzimuthOffset(prefs, 4f)

        assertEquals(-2.5f, SettingsMapping.readArAzimuthOffset(prefs), 1e-6f)
    }

    @Test
    fun `no calibration anywhere reads as zero, not as missing`() {
        assertEquals(0f, SettingsMapping.readArAzimuthOffset(emptyPreferences()), 1e-6f)
        assertEquals(0f, SettingsMapping.readArElevationOffset(emptyPreferences()), 1e-6f)
    }

    @Test
    fun `every horizon setting survives a round trip`() {
        val settings = HorizonSettings(
            enabled = true,
            networkConsent = true,
            autoDownload = false,
            confirmAboveMb = 100,
            maxRangeKm = 100,
            shadeOccludedTrajectory = false,
            wifiOnly = true,
            realHorizonInNightSky = true,
            peakNamesInSunAr = false,
            peakNamesInNightSky = false,
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeHorizon(prefs, settings)

        assertEquals(settings, SettingsMapping.readHorizon(prefs))
    }

    /**
     * "Never asked" and "said no" must not collapse into the same value. If they did, a user who
     * declined the download once would be asked again on every launch, or — worse, if the default
     * went the other way — would never be offered it again after a mis-tap.
     */
    @Test
    fun `horizon network consent keeps refusal apart from an unanswered question`() {
        assertNull("a fresh install has not been asked", SettingsMapping.readHorizon(emptyPreferences()).networkConsent)
        assertFalse(SettingsMapping.readHorizon(emptyPreferences()).networkConsentAnswered)
        assertFalse(SettingsMapping.readHorizon(emptyPreferences()).networkDownloadAllowed)

        val refused = mutablePreferencesOf()
        SettingsMapping.writeHorizon(refused, HorizonSettings(networkConsent = false))
        val readBack = SettingsMapping.readHorizon(refused)

        assertEquals(false, readBack.networkConsent)
        assertEquals(true, readBack.networkConsentAnswered)
        assertFalse(readBack.networkDownloadAllowed)
    }

    /**
     * Horizon keys are absent from every install that predates the feature. Missing must read as the
     * same thing a fresh install reads: the layer off, no consent given, and nothing fetched.
     */
    @Test
    fun `horizon settings default to off on an install that never had the feature`() {
        val defaults = SettingsMapping.readHorizon(emptyPreferences())

        assertEquals(HorizonSettings(), defaults)
        assertFalse(defaults.enabled)
        assertFalse(defaults.networkDownloadAllowed)
        assertTrue("peak names follow the terrain layer, which is what gates them", defaults.peakNamesInSunAr && defaults.peakNamesInNightSky)
        assertEquals(60, defaults.maxRangeKm)
        assertEquals(60_000.0, defaults.maxRangeMeters, 1e-9)
        assertEquals(1.6, defaults.eyeHeightMeters, 1e-9)
        assertEquals(DownloadMode.NEVER, defaults.downloadMode)
        assertTrue("a later consent starts automatic, as it always did", defaults.autoDownload)
        assertEquals(50, defaults.confirmAboveMb)
        assertEquals(50_000_000L, defaults.confirmAboveBytes)
    }

    /**
     * The height above terrain describes where the user stands right now; a stored one from a lookout
     * tower would skew the next day's horizon unseen. It is written nowhere, and the key an older build
     * wrote is removed on the next save.
     */
    @Test
    fun `the height above terrain is never stored and an old stored one is dropped`() {
        val prefs = mutablePreferencesOf()
        prefs[intPreferencesKey("horizon_eye_height_cm")] = 3000
        SettingsMapping.writeHorizon(prefs, HorizonSettings(eyeHeightCm = 5000))

        assertNull(prefs[intPreferencesKey("horizon_eye_height_cm")])
        assertEquals(HorizonSettings.DEFAULT_EYE_HEIGHT_CM, SettingsMapping.readHorizon(prefs).eyeHeightCm)
    }

    @Test
    fun `a confirmation limit that is not offered reads as the default`() {
        val prefs = mutablePreferencesOf()
        // Below the range, between two steps, above it (200 is now "unlimited"), and the old scale's 25 and 250.
        for (stored in listOf(7, 15, 25, 200, 250)) {
            prefs[intPreferencesKey("horizon_confirm_above_mb")] = stored

            assertEquals(
                "stored $stored",
                HorizonSettings.DEFAULT_CONFIRM_ABOVE_MB,
                SettingsMapping.readHorizon(prefs).confirmAboveMb,
            )
        }
    }

    @Test
    fun `the slider's ends, unlimited and a step between them are kept`() {
        val prefs = mutablePreferencesOf()
        for (stored in listOf(10, 70, 190, HorizonSettings.CONFIRM_UNLIMITED)) {
            prefs[intPreferencesKey("horizon_confirm_above_mb")] = stored

            assertEquals(stored, SettingsMapping.readHorizon(prefs).confirmAboveMb)
        }
        assertNull(HorizonSettings(confirmAboveMb = HorizonSettings.CONFIRM_UNLIMITED).confirmAboveBytes)
    }

    @Test
    fun `the download mode follows consent and the automatic switch`() {
        assertEquals(DownloadMode.NEVER, HorizonSettings(networkConsent = null).downloadMode)
        assertEquals(DownloadMode.NEVER, HorizonSettings(networkConsent = false, autoDownload = true).downloadMode)
        assertEquals(DownloadMode.AUTOMATIC, HorizonSettings(networkConsent = true, autoDownload = true).downloadMode)
        assertEquals(DownloadMode.ON_DEMAND, HorizonSettings(networkConsent = true, autoDownload = false).downloadMode)
    }

    @Test
    fun `every advanced flag survives a round trip`() {
        val settings = AdvancedSettings(
            launchCameraMode = "Night Sight",
            intervalAfterCapture = true,
            ensureSelfTimerOff = true,
            dismissPostCaptureUi = true,
            keepScreenOn = true,
            dimOverlay = true,
            // Off, because on is the default: a flag that was never written would read back as on.
            handheldWarning = false,
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeAdvanced(prefs, settings)

        assertEquals(settings, SettingsMapping.readAdvanced(prefs))
    }

    /**
     * The flags added for the overlay did not exist in earlier installs, so their keys are missing
     * from every stored record. Missing must read as *the same thing a fresh install reads*, not as a
     * third state: the floating panel obeys `OverlaySettings`, whose keep-screen-on defaults to on,
     * and an upgraded install that silently read it as off would pause at the first display timeout
     * while a fresh one kept shooting. Everything else stays untouched.
     */
    @Test
    fun `a record written before the new flags existed reads as the shared defaults`() {
        val older = mutablePreferencesOf()
        SettingsMapping.writeAdvanced(older, AdvancedSettings(intervalAfterCapture = true))
        older.removeByName("advanced_keep_screen_on", "advanced_dim_overlay", "advanced_handheld_warning")

        val restored = SettingsMapping.readAdvanced(older)

        assertEquals(AdvancedSettings().keepScreenOn, restored.keepScreenOn)
        assertEquals(AdvancedSettings().dimOverlay, restored.dimOverlay)
        assertEquals(AdvancedSettings().handheldWarning, restored.handheldWarning)
        assertEquals(true, restored.intervalAfterCapture)
    }

    /**
     * The two mirrored flags must default to exactly what the panel defaults to, or the advanced
     * screen shows one thing on a fresh install and the panel does another.
     */
    @Test
    fun `the mirrored overlay flags share the panel's defaults`() {
        assertEquals(OverlayOptions().keepScreenOn, AdvancedSettings().keepScreenOn)
        assertEquals(OverlayOptions().dimPanel, AdvancedSettings().dimOverlay)
    }

    /** Explicitly off still round-trips: the new default must not become a floor. */
    @Test
    fun `keep screen on can be stored as off`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeAdvanced(prefs, AdvancedSettings(keepScreenOn = false))

        assertFalse(SettingsMapping.readAdvanced(prefs).keepScreenOn)
    }

    @Test
    fun `an empty store reads as the default advanced settings`() {
        assertEquals(AdvancedSettings(), SettingsMapping.readAdvanced(emptyPreferences()))
    }

    @Test
    fun `a fully specified config survives a round trip`() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse("com.google.android.GoogleCamera"),
            intervalMs = 30_000L,
            startDelayMs = 5_000L,
            ramp = RampSpec(RampCurve.EXPONENTIAL, 90_000L, RampDomain.ELAPSED_TIME, overDurationMs = 600_000L),
            stop = StopConditions(maxShots = 300, maxDurationMs = 3_600_000L, minBatteryPercent = 20, minFreeBytes = 1_000L),
            target = TapTarget(0.5f, 0.87f, 0.1f, 0.1f, jitter = true),
            verifyCaptures = true,
            minIntervalFloorMs = 1_200L,
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeConfig(prefs, config)

        assertEquals(config, SettingsMapping.readConfig(prefs))
    }

    @Test
    fun `an empty or half written config reads as nothing to start from`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeConfig(
            prefs,
            SessionConfig(TriggerMode.Timelapse("com.example.cam"), 10_000L),
        )
        val withoutInterval = mutablePreferencesOf()
        SettingsMapping.writeConfig(
            withoutInterval,
            SessionConfig(TriggerMode.Timelapse("com.example.cam"), 10_000L),
        )
        withoutInterval.removeByName("interval_ms")

        assertNull(SettingsMapping.readConfig(emptyPreferences()))
        assertNull(SettingsMapping.readConfig(withoutInterval))
        assertEquals(10_000L, SettingsMapping.readConfig(prefs)?.intervalMs)
    }

    /** Writing a config with no ramp has to clear a ramp an earlier config left behind. */
    @Test
    fun `clearing an optional field removes it instead of leaving the old value`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeConfig(
            prefs,
            SessionConfig(
                mode = TriggerMode.Timelapse("com.example.cam"),
                intervalMs = 10_000L,
                ramp = RampSpec(RampCurve.LINEAR, 20_000L, RampDomain.SHOT_INDEX, overShots = 50),
                target = TapTarget(0.5f, 0.9f),
            ),
        )
        SettingsMapping.writeConfig(
            prefs,
            SessionConfig(TriggerMode.Timelapse("com.example.cam"), 10_000L),
        )

        val restored = SettingsMapping.readConfig(prefs)

        assertNull(restored?.ramp)
        assertNull(restored?.target)
    }

    // ---- calibration slots -----------------------------------------------------------------------

    /**
     * The rotation the user calibrated in is the best slot, because the shutter is rarely at the
     * mirrored spot after a quarter turn.
     */
    @Test
    fun `the slot for the current rotation wins`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)

        assertEquals(landscape, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_90))
        assertEquals(portrait, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_0))
    }

    /**
     * The defect: calibrate in landscape, turn the phone upright, and a read of the current rotation
     * plus the natural one found nothing — Home said "not calibrated", Start was blocked, and the miss
     * was written back over the config. Every stored fraction is already in natural display space, so
     * the landscape slot was usable the whole time.
     */
    @Test
    fun `a calibration made in one rotation is found from every other rotation`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)

        for (rotation in listOf(ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270)) {
            assertEquals(
                "no calibration found with the display at rotation $rotation",
                landscape,
                SettingsMapping.readAnyTarget(prefs, PKG, rotation),
            )
        }
    }

    /** With no slot for the current rotation, the natural one is preferred over the rest. */
    @Test
    fun `the natural rotation is the second choice, not just any slot`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_270, landscape)

        assertEquals(portrait, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_90))
    }

    @Test
    fun `a package with no slot at all is still not calibrated`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)

        assertNull(SettingsMapping.readAnyTarget(prefs, "com.example.other", ROTATION_90))
        assertNull(SettingsMapping.readAnyTarget(emptyPreferences(), PKG, ROTATION_0))
    }

    /** A calibration belongs to one package; another app's slots must never stand in for it. */
    @Test
    fun `slots are listed per package`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, "com.example.other", ROTATION_90, landscape)

        assertEquals(mapOf(ROTATION_0 to portrait), SettingsMapping.readTargetSlots(prefs, PKG))
    }

    @Test
    fun `every stored rotation is listed, in rotation order`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_270, landscape)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)

        assertEquals(
            listOf(ROTATION_0, ROTATION_270),
            SettingsMapping.readTargetSlots(prefs, PKG).keys.toList(),
        )
    }

    /**
     * A half-written slot is not a calibration, in the fallback exactly as in [SettingsMapping.readTarget]
     * — otherwise a torn write in one rotation would shadow a good calibration in another.
     */
    @Test
    fun `a half written slot is skipped in favour of a complete one`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)
        prefs.removeByName("target_${PKG}_0_y")

        assertEquals(landscape, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_0))
        assertEquals(mapOf(ROTATION_90 to landscape), SettingsMapping.readTargetSlots(prefs, PKG))
    }

    /** A package name that itself ends in an underscore and digits must not read as a slot. */
    @Test
    fun `a package whose name looks like a slot key is not confused with one`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, "com.example.cam_1", ROTATION_0, landscape)

        assertNull(SettingsMapping.readAnyTarget(prefs, "com.example.cam", ROTATION_0))
        assertEquals(
            landscape,
            SettingsMapping.readAnyTarget(prefs, "com.example.cam_1", ROTATION_90),
        )
    }

    @Test
    fun `sun ar keep screen on defaults to false on empty preferences`() {
        assertFalse(SettingsMapping.readSunArKeepScreenOn(emptyPreferences()))
    }

    @Test
    fun `sun ar keep screen on survives round trip when true and false`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArKeepScreenOn(prefs, true)
        assertEquals(true, SettingsMapping.readSunArKeepScreenOn(prefs))

        SettingsMapping.writeSunArKeepScreenOn(prefs, false)
        assertEquals(false, SettingsMapping.readSunArKeepScreenOn(prefs))
    }

    @Test
    fun `sun ar red vision mode defaults to false and survives round trip`() {
        assertFalse(SettingsMapping.readSunArRedVisionMode(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArRedVisionMode(prefs, true)
        assertEquals(true, SettingsMapping.readSunArRedVisionMode(prefs))

        SettingsMapping.writeSunArRedVisionMode(prefs, false)
        assertEquals(false, SettingsMapping.readSunArRedVisionMode(prefs))
    }

    @Test
    fun `sun ar azimuth offset defaults to 0 and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readSunArAzimuthOffset(emptyPreferences()), 0.001f)

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArAzimuthOffset(prefs, 15.5f)
        assertEquals(15.5f, SettingsMapping.readSunArAzimuthOffset(prefs), 0.001f)

        SettingsMapping.writeSunArAzimuthOffset(prefs, -8.25f)
        assertEquals(-8.25f, SettingsMapping.readSunArAzimuthOffset(prefs), 0.001f)
    }

    @Test
    fun `night sky keep screen on defaults to false and survives round trip`() {
        assertFalse(SettingsMapping.readNightSkyKeepScreenOn(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyKeepScreenOn(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyKeepScreenOn(prefs))

        SettingsMapping.writeNightSkyKeepScreenOn(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyKeepScreenOn(prefs))
    }

    @Test
    fun `night sky red vision mode defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyRedVisionMode(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyRedVisionMode(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyRedVisionMode(prefs))

        SettingsMapping.writeNightSkyRedVisionMode(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyRedVisionMode(prefs))
    }

    @Test
    fun `night sky azimuth offset defaults to 0 and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readNightSkyAzimuthOffset(emptyPreferences()), 0.001f)

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyAzimuthOffset(prefs, 12.5f)
        assertEquals(12.5f, SettingsMapping.readNightSkyAzimuthOffset(prefs), 0.001f)

        SettingsMapping.writeNightSkyAzimuthOffset(prefs, -4.5f)
        assertEquals(-4.5f, SettingsMapping.readNightSkyAzimuthOffset(prefs), 0.001f)
    }

    @Test
    fun `night sky opaque background defaults to false and survives round trip`() {
        assertFalse(SettingsMapping.readNightSkyOpaqueBackground(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyOpaqueBackground(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyOpaqueBackground(prefs))

        SettingsMapping.writeNightSkyOpaqueBackground(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyOpaqueBackground(prefs))
    }

    @Test
    fun `night sky milky way visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyMilkyWayVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyMilkyWayVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyMilkyWayVisible(prefs))

        SettingsMapping.writeNightSkyMilkyWayVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyMilkyWayVisible(prefs))
    }

    @Test
    fun `night sky atmosphere visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyAtmosphereVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyAtmosphereVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyAtmosphereVisible(prefs))

        SettingsMapping.writeNightSkyAtmosphereVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyAtmosphereVisible(prefs))
    }

    @Test
    fun `night sky landscape visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyLandscapeVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyLandscapeVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyLandscapeVisible(prefs))

        SettingsMapping.writeNightSkyLandscapeVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyLandscapeVisible(prefs))
    }

    @Test
    fun `night sky grid visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyGridVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyGridVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyGridVisible(prefs))

        SettingsMapping.writeNightSkyGridVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyGridVisible(prefs))
    }

    @Test
    fun `night sky planets visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyPlanetsVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyPlanetsVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyPlanetsVisible(prefs))

        SettingsMapping.writeNightSkyPlanetsVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyPlanetsVisible(prefs))
    }

    @Test
    fun `night sky dsos visible defaults to false and survives round trip`() {
        assertEquals(false, SettingsMapping.readNightSkyDsosVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyDsosVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyDsosVisible(prefs))

        SettingsMapping.writeNightSkyDsosVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyDsosVisible(prefs))
    }

    @Test
    fun `night sky meteor showers visible defaults to false and survives round trip`() {
        assertEquals(false, SettingsMapping.readNightSkyMeteorShowersVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyMeteorShowersVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyMeteorShowersVisible(prefs))

        SettingsMapping.writeNightSkyMeteorShowersVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyMeteorShowersVisible(prefs))
    }

    @Test
    fun `sun ar elevation offset defaults to zero and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readSunArElevationOffset(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArElevationOffset(prefs, 15.5f)
        assertEquals(15.5f, SettingsMapping.readSunArElevationOffset(prefs))

        SettingsMapping.writeSunArElevationOffset(prefs, -8.2f)
        assertEquals(-8.2f, SettingsMapping.readSunArElevationOffset(prefs))
    }

    @Test
    fun `night sky elevation offset defaults to zero and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readNightSkyElevationOffset(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyElevationOffset(prefs, -12.4f)
        assertEquals(-12.4f, SettingsMapping.readNightSkyElevationOffset(prefs))

        SettingsMapping.writeNightSkyElevationOffset(prefs, 25.0f)
        assertEquals(25.0f, SettingsMapping.readNightSkyElevationOffset(prefs))
    }

    @Test
    fun `sun ar pitch offset defaults to zero and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readSunArPitchOffset(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArPitchOffset(prefs, 120.0f)
        assertEquals(120.0f, SettingsMapping.readSunArPitchOffset(prefs))

        SettingsMapping.writeSunArPitchOffset(prefs, -45.5f)
        assertEquals(-45.5f, SettingsMapping.readSunArPitchOffset(prefs))
    }

    @Test
    fun `night sky pitch offset defaults to zero and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readNightSkyPitchOffset(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyPitchOffset(prefs, -135.0f)
        assertEquals(-135.0f, SettingsMapping.readNightSkyPitchOffset(prefs))

        SettingsMapping.writeNightSkyPitchOffset(prefs, 60.0f)
        assertEquals(60.0f, SettingsMapping.readNightSkyPitchOffset(prefs))
    }

    @Test
    fun `sun ar quaternion defaults to identity and survives round trip`() {
        val defaultQuat = SettingsMapping.readSunArQuaternion(emptyPreferences())
        assertEquals(0.0f, defaultQuat[0], 0.0001f)
        assertEquals(0.0f, defaultQuat[1], 0.0001f)
        assertEquals(0.0f, defaultQuat[2], 0.0001f)
        assertEquals(1.0f, defaultQuat[3], 0.0001f)

        val prefs = mutablePreferencesOf()
        val custom = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)
        SettingsMapping.writeSunArQuaternion(prefs, custom)
        val read = SettingsMapping.readSunArQuaternion(prefs)
        assertEquals(0.1f, read[0], 0.0001f)
        assertEquals(0.2f, read[1], 0.0001f)
        assertEquals(0.3f, read[2], 0.0001f)
        assertEquals(0.9f, read[3], 0.0001f)
    }

    @Test
    fun `night sky quaternion defaults to identity and survives round trip`() {
        val defaultQuat = SettingsMapping.readNightSkyQuaternion(emptyPreferences())
        assertEquals(0.0f, defaultQuat[0], 0.0001f)
        assertEquals(0.0f, defaultQuat[1], 0.0001f)
        assertEquals(0.0f, defaultQuat[2], 0.0001f)
        assertEquals(1.0f, defaultQuat[3], 0.0001f)

        val prefs = mutablePreferencesOf()
        val custom = floatArrayOf(-0.5f, 0.0f, 0.5f, 0.707f)
        SettingsMapping.writeNightSkyQuaternion(prefs, custom)
        val read = SettingsMapping.readNightSkyQuaternion(prefs)
        assertEquals(-0.5f, read[0], 0.0001f)
        assertEquals(0.0f, read[1], 0.0001f)
        assertEquals(0.5f, read[2], 0.0001f)
        assertEquals(0.707f, read[3], 0.0001f)
    }

    @Test
    fun `night sky constellations visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyConstellationsVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyConstellationsVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyConstellationsVisible(prefs))

        SettingsMapping.writeNightSkyConstellationsVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyConstellationsVisible(prefs))
    }

    @Test
    fun `night sky constellation art visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyConstellationArtVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyConstellationArtVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyConstellationArtVisible(prefs))

        SettingsMapping.writeNightSkyConstellationArtVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyConstellationArtVisible(prefs))
    }

    @Test
    fun `night sky constellation art opacity defaults to the old alpha and is clamped both ways`() {
        assertEquals(
            ConstellationArtOpacity.DEFAULT,
            SettingsMapping.readNightSkyConstellationArtOpacity(emptyPreferences()),
            0f,
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyConstellationArtOpacity(prefs, 0.75f)
        assertEquals(0.75f, SettingsMapping.readNightSkyConstellationArtOpacity(prefs), 0f)

        SettingsMapping.writeNightSkyConstellationArtOpacity(prefs, 0f)
        assertEquals(ConstellationArtOpacity.MIN, SettingsMapping.readNightSkyConstellationArtOpacity(prefs), 0f)

        // A value stored outside the range (an older build, a restored backup) is clamped on read too.
        prefs[floatPreferencesKey("night_sky_constellation_art_opacity")] = 3f
        assertEquals(ConstellationArtOpacity.MAX, SettingsMapping.readNightSkyConstellationArtOpacity(prefs), 0f)
    }

    @Test
    fun `night sky asterism names visible defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyAsterismNamesVisible(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyAsterismNamesVisible(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyAsterismNamesVisible(prefs))

        SettingsMapping.writeNightSkyAsterismNamesVisible(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyAsterismNamesVisible(prefs))
    }

    @Test
    fun `night sky sensor tracking defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkySensorTracking(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkySensorTracking(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkySensorTracking(prefs))

        SettingsMapping.writeNightSkySensorTracking(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkySensorTracking(prefs))
    }

    @Test
    fun `night vision theme defaults to AMBER and survives round trip`() {
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
            SettingsMapping.readNightVisionTheme(emptyPreferences()),
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightVisionTheme(prefs, com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER)
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionTheme.AMBER,
            SettingsMapping.readNightVisionTheme(prefs),
        )

        SettingsMapping.writeNightVisionTheme(prefs, com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN)
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionTheme.GREEN,
            SettingsMapping.readNightVisionTheme(prefs),
        )

        SettingsMapping.writeNightVisionTheme(prefs, com.dazix.caelis.domain.nightsky.NightVisionTheme.RED)
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionTheme.RED,
            SettingsMapping.readNightVisionTheme(prefs),
        )
    }

    @Test
    fun `night vision activation mode defaults to OFF and survives round trip`() {
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionActivationMode.OFF,
            SettingsMapping.readNightVisionActivationMode(emptyPreferences()),
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightVisionActivationMode(prefs, com.dazix.caelis.domain.nightsky.NightVisionActivationMode.AUTO_SOLAR)
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionActivationMode.AUTO_SOLAR,
            SettingsMapping.readNightVisionActivationMode(prefs),
        )

        SettingsMapping.writeNightVisionActivationMode(prefs, com.dazix.caelis.domain.nightsky.NightVisionActivationMode.CUSTOM_TIME)
        assertEquals(
            com.dazix.caelis.domain.nightsky.NightVisionActivationMode.CUSTOM_TIME,
            SettingsMapping.readNightVisionActivationMode(prefs),
        )
    }

    @Test
    fun `night vision custom times default and survive round trip`() {
        assertEquals(java.time.LocalTime.of(22, 0), SettingsMapping.readCustomStartTime(emptyPreferences()))
        assertEquals(java.time.LocalTime.of(6, 0), SettingsMapping.readCustomEndTime(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeCustomStartTime(prefs, java.time.LocalTime.of(22, 30))
        SettingsMapping.writeCustomEndTime(prefs, java.time.LocalTime.of(4, 15))

        assertEquals(java.time.LocalTime.of(22, 30), SettingsMapping.readCustomStartTime(prefs))
        assertEquals(java.time.LocalTime.of(4, 15), SettingsMapping.readCustomEndTime(prefs))
    }

    @Test
    fun `debug mode enabled defaults to false on empty preferences`() {
        assertFalse(SettingsMapping.readDebugModeEnabled(emptyPreferences()))
    }

    @Test
    fun `debug mode enabled survives round trip when true and false`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeDebugModeEnabled(prefs, true)
        assertEquals(true, SettingsMapping.readDebugModeEnabled(prefs))

        SettingsMapping.writeDebugModeEnabled(prefs, false)
        assertEquals(false, SettingsMapping.readDebugModeEnabled(prefs))
    }

    @Test
    fun `debug preferences default to all false on empty preferences`() {
        val defaults = SettingsMapping.readDebugPreferences(emptyPreferences())
        assertFalse(defaults.enableNightSkyFocusRegion)
        assertFalse(defaults.showTerrainMetrics)
        assertFalse(defaults.showTapRadiusIndicator)
        assertFalse("the compass log is off unless asked for", defaults.logCompass)
        assertEquals(DebugPreferences.DEFAULT_STAR_TAP_RADIUS_DP, defaults.starTapRadiusDp)
        assertFalse(defaults.anyEnabled)
    }

    @Test
    fun `the compass log switch survives a round trip and counts as debug on`() {
        val prefs = mutablePreferencesOf()
        val custom = DebugPreferences(logCompass = true)
        SettingsMapping.writeDebugPreferences(prefs, custom)
        assertEquals(custom, SettingsMapping.readDebugPreferences(prefs))
        assertTrue(custom.anyEnabled)
        // The legacy debug flag never switched logging on.
        val legacy = mutablePreferencesOf()
        SettingsMapping.writeDebugModeEnabled(legacy, true)
        assertFalse(SettingsMapping.readDebugPreferences(legacy).logCompass)
    }

    @Test
    fun `the tilt recovery switch defaults to on and survives a round trip`() {
        assertFalse(
            "the correction is on unless switched off",
            SettingsMapping.readDebugPreferences(emptyPreferences()).disableTiltRecovery,
        )
        val prefs = mutablePreferencesOf()
        val custom = DebugPreferences(disableTiltRecovery = true)
        SettingsMapping.writeDebugPreferences(prefs, custom)
        assertEquals(custom, SettingsMapping.readDebugPreferences(prefs))
        assertTrue(custom.anyEnabled)
    }

    @Test
    fun `debug preferences survive round trip when true and false`() {
        val prefs = mutablePreferencesOf()
        val custom = DebugPreferences(
            enableNightSkyFocusRegion = false,
            showTerrainMetrics = true,
            starTapRadiusDp = 64,
            showTapRadiusIndicator = true,
        )
        SettingsMapping.writeDebugPreferences(prefs, custom)
        assertEquals(custom, SettingsMapping.readDebugPreferences(prefs))
        assertTrue(custom.anyEnabled)
    }

    @Test
    fun `debug preferences fall back to legacy debug mode enabled flag when granular keys are absent`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeDebugModeEnabled(prefs, true)

        val read = SettingsMapping.readDebugPreferences(prefs)
        assertEquals(true, read.enableNightSkyFocusRegion)
        assertEquals(false, read.showTerrainMetrics)
        assertEquals(false, read.showTapRadiusIndicator)
        assertEquals(DebugPreferences.DEFAULT_STAR_TAP_RADIUS_DP, read.starTapRadiusDp)
    }

    @Test
    fun `night sky search history defaults to empty string and survives round trip`() {
        assertEquals("", SettingsMapping.readNightSkySearchHistory(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        val raw = "STAR\t42\tSirius\tCanis Major\t101.28\t-16.71\t-1.46\t"
        SettingsMapping.writeNightSkySearchHistory(prefs, raw)
        assertEquals(raw, SettingsMapping.readNightSkySearchHistory(prefs))
    }

    @Test
    fun `notification permission prompted defaults to false on empty preferences`() {
        assertFalse(SettingsMapping.readNotificationPermissionPrompted(emptyPreferences()))
    }

    @Test
    fun `notification permission prompted survives round trip when true and false`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNotificationPermissionPrompted(prefs, true)
        assertEquals(true, SettingsMapping.readNotificationPermissionPrompted(prefs))

        SettingsMapping.writeNotificationPermissionPrompted(prefs, false)
        assertEquals(false, SettingsMapping.readNotificationPermissionPrompted(prefs))
    }

    // ---- ARCore consent ----

    @Test
    fun `ARCore and Geospatial consent are both off on empty preferences`() {
        val settings = SettingsMapping.readArCore(emptyPreferences())

        assertFalse(settings.arCoreConsent)
        assertFalse(settings.geospatialConsent)
        assertEquals(ArCoreSettings(), settings)
    }

    @Test
    fun `ARCore consent is stored under its own documented keys`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeArCore(prefs, ArCoreSettings(arCoreConsent = true, geospatialConsent = true))

        // The key names are the on-disk contract; a rename silently drops everyone's answer.
        assertEquals(setOf("arcore_consent", "geospatial_consent"), prefs.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun `ARCore consent survives a round trip in every combination it can legally take`() {
        val legal = listOf(
            ArCoreSettings(arCoreConsent = false, geospatialConsent = false),
            ArCoreSettings(arCoreConsent = true, geospatialConsent = false),
            ArCoreSettings(arCoreConsent = true, geospatialConsent = true),
        )
        for (settings in legal) {
            val prefs = mutablePreferencesOf()
            SettingsMapping.writeArCore(prefs, settings)
            assertEquals(settings, SettingsMapping.readArCore(prefs))
        }
    }

    @Test
    fun `a stored Geospatial yes without an ARCore yes is read as no`() {
        // Hand-edited backup or an older build: the narrower reading is the safe one.
        val prefs = mutablePreferencesOf()
        prefs[androidx.datastore.preferences.core.booleanPreferencesKey("geospatial_consent")] = true

        val settings = SettingsMapping.readArCore(prefs)

        assertFalse(settings.arCoreConsent)
        assertFalse(settings.geospatialConsent)
    }

    @Test
    fun `writing an inconsistent pair overwrites the stale Geospatial yes instead of keeping it`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeArCore(prefs, ArCoreSettings(arCoreConsent = true, geospatialConsent = true))

        SettingsMapping.writeArCore(prefs, ArCoreSettings(arCoreConsent = false, geospatialConsent = true))

        assertEquals(
            false,
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("geospatial_consent")],
        )
        assertEquals(ArCoreSettings(), SettingsMapping.readArCore(prefs))
    }

    @Test
    fun `turning ARCore off turns Geospatial off with it`() {
        val on = ArCoreSettings(arCoreConsent = true, geospatialConsent = true)

        assertEquals(ArCoreSettings(arCoreConsent = false, geospatialConsent = false), on.withArCoreConsent(false))
    }

    @Test
    fun `Geospatial cannot be granted while ARCore is off`() {
        val off = ArCoreSettings()

        assertEquals(off, off.withGeospatialConsent(true))
        assertEquals(
            ArCoreSettings(arCoreConsent = true, geospatialConsent = true),
            off.withArCoreConsent(true).withGeospatialConsent(true),
        )
    }

    @Test
    fun `granting ARCore does not grant Geospatial`() {
        val granted = ArCoreSettings().withArCoreConsent(true)

        assertTrue(granted.arCoreConsent)
        assertFalse(granted.geospatialConsent)
    }

    /** Simulates a record written by a build that did not have these keys yet. */
    private fun MutablePreferences.removeByName(vararg names: String) {
        asMap().keys.filter { it.name in names }.toList().forEach {
            @Suppress("UNCHECKED_CAST")
            remove(it as Preferences.Key<Any>)
        }
    }
}
