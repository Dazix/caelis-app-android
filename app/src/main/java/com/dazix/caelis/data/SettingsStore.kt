package com.dazix.caelis.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.RampDomain
import com.dazix.caelis.domain.RampSpec
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.domain.nightsky.ConstellationArtOpacity
import com.dazix.caelis.domain.nightsky.NightVisionActivationMode
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.SearchHistoryCodec
import com.dazix.caelis.domain.nightsky.SearchResultItem
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.caelisPreferences by preferencesDataStore(name = "caelis")

class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.caelisPreferences

    /**
     * For the long-lived flows the UI collects: a read error there must not tear the collector down, so
     * it degrades to "nothing stored". Deliberately *not* used by the one-shot reads below.
     */
    private val preferences: Flow<Preferences> =
        dataStore.data.catch { emit(emptyPreferences()) }

    /**
     * For a single read whose caller distinguishes "nothing stored" from "could not read".
     *
     * The calibration readers are exactly that caller: reporting an unreadable store as "this app has
     * no calibration" blocks Start and, before the mirrored copy was made read-only, wrote the miss
     * back over the config. Swallowing the failure here made the `storeReadable` guard in
     * `CalibrationResolution` unreachable — every failure arrived as a successful read of nothing.
     */
    private suspend fun currentPreferences(): Preferences = dataStore.data.first()

    val lastConfig: Flow<SessionConfig?> = preferences.map { SettingsMapping.readConfig(it) }

    val advanced: Flow<AdvancedSettings> = preferences.map { SettingsMapping.readAdvanced(it) }

    val sunArKeepScreenOn: Flow<Boolean> = preferences.map { SettingsMapping.readSunArKeepScreenOn(it) }

    val notificationPermissionPrompted: Flow<Boolean> =
        preferences.map { SettingsMapping.readNotificationPermissionPrompted(it) }

    val sunArRedVisionMode: Flow<Boolean> = preferences.map { SettingsMapping.readSunArRedVisionMode(it) }

    /** The one compass correction, shared by Sun AR and Night Sky AR. */
    val arAzimuthOffset: Flow<Float> = preferences.map { SettingsMapping.readArAzimuthOffset(it) }

    val arElevationOffset: Flow<Float> = preferences.map { SettingsMapping.readArElevationOffset(it) }

    val sunArAzimuthOffset: Flow<Float> = preferences.map { SettingsMapping.readSunArAzimuthOffset(it) }

    val sunArPitchOffset: Flow<Float> = preferences.map { SettingsMapping.readSunArPitchOffset(it) }

    val sunArElevationOffset: Flow<Float> = sunArPitchOffset

    val sunArQuaternion: Flow<FloatArray> = preferences.map { SettingsMapping.readSunArQuaternion(it) }

    val nightSkyKeepScreenOn: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyKeepScreenOn(it) }

    val nightSkyRedVisionMode: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyRedVisionMode(it) }

    val nightSkyAzimuthOffset: Flow<Float> = preferences.map { SettingsMapping.readNightSkyAzimuthOffset(it) }

    val nightSkyPitchOffset: Flow<Float> = preferences.map { SettingsMapping.readNightSkyPitchOffset(it) }

    val nightSkyElevationOffset: Flow<Float> = nightSkyPitchOffset

    val nightSkyQuaternion: Flow<FloatArray> = preferences.map { SettingsMapping.readNightSkyQuaternion(it) }

    val nightSkyOpaqueBackground: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyOpaqueBackground(it) }

    val nightSkyMilkyWayVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyMilkyWayVisible(it) }

    val nightSkyAtmosphereVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyAtmosphereVisible(it) }

    val nightSkyLandscapeVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyLandscapeVisible(it) }

    val nightSkyGridVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyGridVisible(it) }

    val nightSkyPlanetsVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyPlanetsVisible(it) }
    val nightSkyDsosVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyDsosVisible(it) }
    val nightSkyMeteorShowersVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyMeteorShowersVisible(it) }

    val nightSkyConstellationsVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyConstellationsVisible(it) }
    val nightSkyConstellationArtVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyConstellationArtVisible(it) }
    val nightSkyConstellationArtOpacity: Flow<Float> = preferences.map { SettingsMapping.readNightSkyConstellationArtOpacity(it) }
    val nightSkyAsterismNamesVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyAsterismNamesVisible(it) }

    val nightSkySensorTracking: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkySensorTracking(it) }

    val nightSkySearchHistory: Flow<List<SearchResultItem>> = preferences.map {
        SearchHistoryCodec.decode(SettingsMapping.readNightSkySearchHistory(it))
    }

    val nightVisionTheme: Flow<NightVisionTheme> = preferences.map { SettingsMapping.readNightVisionTheme(it) }

    val nightVisionActivationMode: Flow<NightVisionActivationMode> = preferences.map { SettingsMapping.readNightVisionActivationMode(it) }

    val customStartTime: Flow<LocalTime> = preferences.map { SettingsMapping.readCustomStartTime(it) }

    val customEndTime: Flow<LocalTime> = preferences.map { SettingsMapping.readCustomEndTime(it) }

    val debugModeEnabled: Flow<Boolean> = preferences.map { SettingsMapping.readDebugModeEnabled(it) }

    val debugPreferences: Flow<DebugPreferences> = preferences.map { SettingsMapping.readDebugPreferences(it) }

    val horizon: Flow<HorizonSettings> = preferences.map { SettingsMapping.readHorizon(it) }

    suspend fun saveHorizon(settings: HorizonSettings) {
        dataStore.edit { SettingsMapping.writeHorizon(it, settings) }
    }

    /** One-shot read for the places that need the current settings without collecting a flow. */
    suspend fun currentHorizon(): HorizonSettings = SettingsMapping.readHorizon(currentPreferences())

    val arCore: Flow<ArCoreSettings> = preferences.map { SettingsMapping.readArCore(it) }

    /**
     * One-shot read for [com.dazix.caelis.ar.pose.ArCoreSessionController], which asks again on every
     * use. Deliberately goes through [currentPreferences] and so propagates an unreadable store
     * instead of reporting "nothing stored": the controller treats a failed read as *no consent*, and
     * that has to be its decision to make rather than something this layer papers over.
     */
    suspend fun currentArCore(): ArCoreSettings = SettingsMapping.readArCore(currentPreferences())

    /**
     * Read-modify-write inside one transaction. Both switches live in one record, and turning ARCore
     * off has to turn geospatial off in the same write or a collector could briefly see the pair
     * the user can never create.
     */
    suspend fun setArCoreConsent(granted: Boolean) {
        dataStore.edit {
            SettingsMapping.writeArCore(it, SettingsMapping.readArCore(it).withArCoreConsent(granted))
        }
    }

    suspend fun setGeospatialConsent(granted: Boolean) {
        dataStore.edit {
            SettingsMapping.writeArCore(it, SettingsMapping.readArCore(it).withGeospatialConsent(granted))
        }
    }

    suspend fun saveNightVisionTheme(theme: NightVisionTheme) {
        dataStore.edit { SettingsMapping.writeNightVisionTheme(it, theme) }
    }

    suspend fun saveNightVisionActivationMode(mode: NightVisionActivationMode) {
        dataStore.edit { SettingsMapping.writeNightVisionActivationMode(it, mode) }
    }

    suspend fun saveCustomStartTime(time: LocalTime) {
        dataStore.edit { SettingsMapping.writeCustomStartTime(it, time) }
    }

    suspend fun saveCustomEndTime(time: LocalTime) {
        dataStore.edit { SettingsMapping.writeCustomEndTime(it, time) }
    }

    suspend fun saveDebugModeEnabled(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeDebugModeEnabled(it, enabled) }
    }

    suspend fun saveDebugPreferences(preferences: DebugPreferences) {
        dataStore.edit { SettingsMapping.writeDebugPreferences(it, preferences) }
    }

    suspend fun saveSunArKeepScreenOn(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeSunArKeepScreenOn(it, enabled) }
    }

    suspend fun saveSunArRedVisionMode(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeSunArRedVisionMode(it, enabled) }
    }

    /** Writes the shared compass correction. The per-screen keys are no longer written at all. */
    suspend fun saveArCalibration(azimuthDeg: Float, elevationDeg: Float) {
        dataStore.edit { SettingsMapping.writeArCalibration(it, azimuthDeg, elevationDeg) }
    }

    suspend fun saveSunArAzimuthOffset(offsetDeg: Float) {
        dataStore.edit { SettingsMapping.writeSunArAzimuthOffset(it, offsetDeg) }
    }

    suspend fun saveSunArPitchOffset(offsetDeg: Float) {
        dataStore.edit { SettingsMapping.writeSunArPitchOffset(it, offsetDeg) }
    }

    suspend fun saveSunArElevationOffset(offsetDeg: Float) {
        saveSunArPitchOffset(offsetDeg)
    }

    suspend fun saveSunArQuaternion(quat: FloatArray) {
        dataStore.edit { SettingsMapping.writeSunArQuaternion(it, quat) }
    }

    suspend fun saveNightSkyKeepScreenOn(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyKeepScreenOn(it, enabled) }
    }

    suspend fun saveNightSkyRedVisionMode(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyRedVisionMode(it, enabled) }
    }

    suspend fun saveNightSkyAzimuthOffset(offsetDeg: Float) {
        dataStore.edit { SettingsMapping.writeNightSkyAzimuthOffset(it, offsetDeg) }
    }

    suspend fun saveNightSkyPitchOffset(offsetDeg: Float) {
        dataStore.edit { SettingsMapping.writeNightSkyPitchOffset(it, offsetDeg) }
    }

    suspend fun saveNightSkyElevationOffset(offsetDeg: Float) {
        saveNightSkyPitchOffset(offsetDeg)
    }

    suspend fun saveNightSkyQuaternion(quat: FloatArray) {
        dataStore.edit { SettingsMapping.writeNightSkyQuaternion(it, quat) }
    }

    suspend fun addNightSkySearchHistory(item: SearchResultItem) {
        dataStore.edit { prefs ->
            val current = SearchHistoryCodec.decode(SettingsMapping.readNightSkySearchHistory(prefs))
            val updated = SearchHistoryCodec.addToHistory(current, item)
            SettingsMapping.writeNightSkySearchHistory(prefs, SearchHistoryCodec.encode(updated))
        }
    }

    suspend fun removeNightSkySearchHistory(item: SearchResultItem) {
        dataStore.edit { prefs ->
            val current = SearchHistoryCodec.decode(SettingsMapping.readNightSkySearchHistory(prefs))
            val updated = SearchHistoryCodec.removeFromHistory(current, item)
            SettingsMapping.writeNightSkySearchHistory(prefs, SearchHistoryCodec.encode(updated))
        }
    }

    suspend fun clearNightSkySearchHistory() {
        dataStore.edit { SettingsMapping.writeNightSkySearchHistory(it, "") }
    }

    suspend fun saveNightSkyOpaqueBackground(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyOpaqueBackground(it, enabled) }
    }

    suspend fun saveNightSkyMilkyWayVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyMilkyWayVisible(it, enabled) }
    }

    suspend fun saveNightSkyAtmosphereVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyAtmosphereVisible(it, enabled) }
    }

    suspend fun saveNightSkyLandscapeVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyLandscapeVisible(it, enabled) }
    }

    suspend fun saveNightSkyGridVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyGridVisible(it, enabled) }
    }

    suspend fun saveNightSkyPlanetsVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyPlanetsVisible(it, enabled) }
    }

    suspend fun saveNightSkyDsosVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyDsosVisible(it, enabled) }
    }

    suspend fun saveNightSkyMeteorShowersVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyMeteorShowersVisible(it, enabled) }
    }

    suspend fun saveNightSkyConstellationsVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyConstellationsVisible(it, enabled) }
    }

    suspend fun saveNightSkyConstellationArtVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyConstellationArtVisible(it, enabled) }
    }

    suspend fun saveNightSkyConstellationArtOpacity(opacity: Float) {
        dataStore.edit { SettingsMapping.writeNightSkyConstellationArtOpacity(it, opacity) }
    }

    suspend fun saveNightSkyAsterismNamesVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyAsterismNamesVisible(it, enabled) }
    }

    suspend fun saveNightSkySensorTracking(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkySensorTracking(it, enabled) }
    }

    suspend fun saveNotificationPermissionPrompted(prompted: Boolean = true) {
        dataStore.edit { SettingsMapping.writeNotificationPermissionPrompted(it, prompted) }
    }

    suspend fun currentNotificationPermissionPrompted(): Boolean =
        SettingsMapping.readNotificationPermissionPrompted(currentPreferences())

    suspend fun saveConfig(config: SessionConfig) {
        dataStore.edit { SettingsMapping.writeConfig(it, config) }
    }

    suspend fun saveTarget(packageName: String, orientation: Int, target: TapTarget) {
        dataStore.edit { SettingsMapping.writeTarget(it, packageName, orientation, target) }
    }

    /** Exactly the slot [orientation] was stored in, or null. See [anyTarget] for the usable read. */
    suspend fun target(packageName: String, orientation: Int): TapTarget? =
        SettingsMapping.readTarget(currentPreferences(), packageName, orientation)

    /**
     * The calibration to use for [packageName] with the display at [preferredRotation], taken from
     * whichever slot has one — see [SettingsMapping.readAnyTarget].
     *
     * This is what every reader wants. Asking for one rotation and giving up is what made a
     * calibration captured in landscape read as "not calibrated" the moment the phone was turned
     * upright, which blocked Start and then wrote the miss back over the session config.
     */
    suspend fun anyTarget(packageName: String, preferredRotation: Int): TapTarget? =
        SettingsMapping.readAnyTarget(currentPreferences(), packageName, preferredRotation)

    suspend fun saveAdvanced(settings: AdvancedSettings) {
        dataStore.edit { SettingsMapping.writeAdvanced(it, settings) }
    }
}

/**
 * The single place where domain models meet primitive preference keys. Readers are total: a partially
 * written or corrupted record degrades to `null` (config, target) or to defaults (advanced) rather
 * than throwing.
 *
 * `internal` rather than private so the round-trips can be unit-tested against an in-memory
 * `MutablePreferences`, which is the only part of this file that has no device dependency.
 */
internal object SettingsMapping {

    private const val MODE_TIMELAPSE = "timelapse"
    private const val MODE_PERIODIC_TAP = "periodic_tap"

    private val MODE = stringPreferencesKey("mode")
    private val MODE_PACKAGE = stringPreferencesKey("mode_package")
    private val INTERVAL_MS = longPreferencesKey("interval_ms")
    private val START_DELAY_MS = longPreferencesKey("start_delay_ms")
    private val VERIFY_CAPTURES = booleanPreferencesKey("verify_captures")
    private val MIN_INTERVAL_FLOOR_MS = longPreferencesKey("min_interval_floor_ms")

    private val RAMP_CURVE = stringPreferencesKey("ramp_curve")
    private val RAMP_END_INTERVAL_MS = longPreferencesKey("ramp_end_interval_ms")
    private val RAMP_DOMAIN = stringPreferencesKey("ramp_domain")
    private val RAMP_OVER_SHOTS = intPreferencesKey("ramp_over_shots")
    private val RAMP_OVER_DURATION_MS = longPreferencesKey("ramp_over_duration_ms")

    private val STOP_MAX_SHOTS = intPreferencesKey("stop_max_shots")
    private val STOP_MAX_DURATION_MS = longPreferencesKey("stop_max_duration_ms")
    private val STOP_MIN_BATTERY_PERCENT = intPreferencesKey("stop_min_battery_percent")
    private val STOP_MIN_FREE_BYTES = longPreferencesKey("stop_min_free_bytes")

    private val CONFIG_TARGET_X = floatPreferencesKey("config_target_x")
    private val CONFIG_TARGET_Y = floatPreferencesKey("config_target_y")
    private val CONFIG_TARGET_W = floatPreferencesKey("config_target_w")
    private val CONFIG_TARGET_H = floatPreferencesKey("config_target_h")
    private val CONFIG_TARGET_JITTER = booleanPreferencesKey("config_target_jitter")

    private val ADV_LAUNCH_CAMERA_MODE = stringPreferencesKey("advanced_launch_camera_mode")
    // Interval after capture; the key is the adaptive long-exposure wait's, which it replaced.
    private val ADV_ADAPTIVE_LONG_EXPOSURE_WAIT = booleanPreferencesKey("advanced_adaptive_long_exposure_wait")
    private val ADV_ENSURE_SELF_TIMER_OFF = booleanPreferencesKey("advanced_ensure_self_timer_off")
    private val ADV_DISMISS_POST_CAPTURE_UI = booleanPreferencesKey("advanced_dismiss_post_capture_ui")
    private val ADV_KEEP_SCREEN_ON = booleanPreferencesKey("advanced_keep_screen_on")
    private val ADV_DIM_OVERLAY = booleanPreferencesKey("advanced_dim_overlay")
    private val ADV_HANDHELD_WARNING = booleanPreferencesKey("advanced_handheld_warning")
    private val SUN_AR_KEEP_SCREEN_ON = booleanPreferencesKey("sun_ar_keep_screen_on")
    private val SUN_AR_RED_VISION_MODE = booleanPreferencesKey("sun_ar_red_vision_mode")
    /**
     * One compass correction per phone, shared by every AR screen.
     *
     * There used to be a `sun_ar_` pair and a `night_sky_` pair for what is physically one
     * magnetometer in one device: aligning the terrain silhouette by day taught the app nothing
     * about where north is at night. Those two are read only to migrate from, never written again.
     */
    private val AR_AZIMUTH_OFFSET = floatPreferencesKey("ar_azimuth_offset")
    private val AR_ELEVATION_OFFSET = floatPreferencesKey("ar_elevation_offset")
    private val SUN_AR_AZIMUTH_OFFSET = floatPreferencesKey("sun_ar_azimuth_offset")
    private val SUN_AR_ELEVATION_OFFSET = floatPreferencesKey("sun_ar_elevation_offset")
    private val SUN_AR_QUAT_X = floatPreferencesKey("sun_ar_quat_x")
    private val SUN_AR_QUAT_Y = floatPreferencesKey("sun_ar_quat_y")
    private val SUN_AR_QUAT_Z = floatPreferencesKey("sun_ar_quat_z")
    private val SUN_AR_QUAT_W = floatPreferencesKey("sun_ar_quat_w")
    private val NIGHT_SKY_KEEP_SCREEN_ON = booleanPreferencesKey("night_sky_keep_screen_on")
    private val NIGHT_SKY_RED_VISION_MODE = booleanPreferencesKey("night_sky_red_vision_mode")
    private val NIGHT_SKY_AZIMUTH_OFFSET = floatPreferencesKey("night_sky_azimuth_offset")
    private val NIGHT_SKY_ELEVATION_OFFSET = floatPreferencesKey("night_sky_elevation_offset")
    private val NIGHT_SKY_QUAT_X = floatPreferencesKey("night_sky_quat_x")
    private val NIGHT_SKY_QUAT_Y = floatPreferencesKey("night_sky_quat_y")
    private val NIGHT_SKY_QUAT_Z = floatPreferencesKey("night_sky_quat_z")
    private val NIGHT_SKY_QUAT_W = floatPreferencesKey("night_sky_quat_w")
    private val NIGHT_SKY_OPAQUE_BACKGROUND = booleanPreferencesKey("night_sky_opaque_background")
    private val NIGHT_SKY_MILKY_WAY_VISIBLE = booleanPreferencesKey("night_sky_milky_way_visible")
    private val NIGHT_SKY_ATMOSPHERE_VISIBLE = booleanPreferencesKey("night_sky_atmosphere_visible")
    private val NIGHT_SKY_LANDSCAPE_VISIBLE = booleanPreferencesKey("night_sky_landscape_visible")
    private val NIGHT_SKY_GRID_VISIBLE = booleanPreferencesKey("night_sky_grid_visible")
    private val NIGHT_SKY_PLANETS_VISIBLE = booleanPreferencesKey("night_sky_planets_visible")
    private val NIGHT_SKY_DSOS_VISIBLE = booleanPreferencesKey("night_sky_dsos_visible")
    private val NIGHT_SKY_METEOR_SHOWERS_VISIBLE = booleanPreferencesKey("night_sky_meteor_showers_visible")
    private val NIGHT_SKY_CONSTELLATIONS_VISIBLE = booleanPreferencesKey("night_sky_constellations_visible")
    private val NIGHT_SKY_CONSTELLATION_ART_VISIBLE = booleanPreferencesKey("night_sky_constellation_art_visible")
    private val NIGHT_SKY_CONSTELLATION_ART_OPACITY = floatPreferencesKey("night_sky_constellation_art_opacity")
    private val NIGHT_SKY_ASTERISM_NAMES_VISIBLE = booleanPreferencesKey("night_sky_asterism_names_visible")
    private val NIGHT_SKY_SENSOR_TRACKING = booleanPreferencesKey("night_sky_sensor_tracking")
    private val NIGHT_SKY_SEARCH_HISTORY = stringPreferencesKey("night_sky_search_history")
    private val NIGHT_VISION_THEME_KEY = stringPreferencesKey("night_vision_theme")
    private val NIGHT_VISION_MODE_KEY = stringPreferencesKey("night_vision_mode")
    private val CUSTOM_START_MINUTES_KEY = intPreferencesKey("custom_start_minutes")
    private val CUSTOM_END_MINUTES_KEY = intPreferencesKey("custom_end_minutes")
    private val DEBUG_MODE_ENABLED = booleanPreferencesKey("debug_mode_enabled")
    private val DEBUG_NIGHT_SKY_FOCUS_REGION = booleanPreferencesKey("debug_night_sky_focus_region")
    private val DEBUG_SHOW_TERRAIN_METRICS = booleanPreferencesKey("debug_show_terrain_metrics")
    private val DEBUG_STAR_TAP_RADIUS = intPreferencesKey("debug_star_tap_radius")
    private val DEBUG_SHOW_TAP_RADIUS_INDICATOR = booleanPreferencesKey("debug_show_tap_radius_indicator")
    private val DEBUG_LOG_COMPASS = booleanPreferencesKey("debug_log_compass")
    private val DEBUG_DISABLE_TILT_RECOVERY = booleanPreferencesKey("debug_disable_tilt_recovery")

    private val ARCORE_CONSENT = booleanPreferencesKey("arcore_consent")
    private val GEOSPATIAL_CONSENT = booleanPreferencesKey("geospatial_consent")

    private val HORIZON_ENABLED = booleanPreferencesKey("horizon_enabled")
    private val HORIZON_NETWORK_CONSENT = booleanPreferencesKey("horizon_network_consent")
    private val HORIZON_AUTO_RANGE = booleanPreferencesKey("horizon_auto_range")
    private val HORIZON_MAX_RANGE_KM = intPreferencesKey("horizon_max_range_km")
    // Read by no one since the height above terrain became per process; only removed, see writeHorizon.
    private val HORIZON_EYE_HEIGHT_CM = intPreferencesKey("horizon_eye_height_cm")
    private val HORIZON_AUTO_DOWNLOAD = booleanPreferencesKey("horizon_auto_download")
    private val HORIZON_CONFIRM_ABOVE_MB = intPreferencesKey("horizon_confirm_above_mb")
    private val HORIZON_SHADE_OCCLUDED = booleanPreferencesKey("horizon_shade_occluded")
    private val HORIZON_WIFI_ONLY = booleanPreferencesKey("horizon_wifi_only")
    private val HORIZON_NIGHT_SKY_REAL = booleanPreferencesKey("horizon_night_sky_real")
    private val HORIZON_PEAK_NAMES_SUN_AR = booleanPreferencesKey("horizon_peak_names_sun_ar")
    private val HORIZON_PEAK_NAMES_NIGHT_SKY = booleanPreferencesKey("horizon_peak_names_night_sky")

    fun writeConfig(prefs: MutablePreferences, config: SessionConfig) {
        prefs[MODE] = when (config.mode) {
            is TriggerMode.Timelapse -> MODE_TIMELAPSE
            is TriggerMode.PeriodicTap -> MODE_PERIODIC_TAP
        }
        prefs.put(MODE_PACKAGE, config.mode.targetPackage)
        prefs[INTERVAL_MS] = config.intervalMs
        prefs[START_DELAY_MS] = config.startDelayMs
        prefs[VERIFY_CAPTURES] = config.verifyCaptures
        prefs[MIN_INTERVAL_FLOOR_MS] = config.minIntervalFloorMs

        val ramp = config.ramp
        prefs.put(RAMP_CURVE, ramp?.curve?.name)
        prefs.put(RAMP_END_INTERVAL_MS, ramp?.endIntervalMs)
        prefs.put(RAMP_DOMAIN, ramp?.domain?.name)
        prefs.put(RAMP_OVER_SHOTS, ramp?.overShots)
        prefs.put(RAMP_OVER_DURATION_MS, ramp?.overDurationMs)

        prefs.put(STOP_MAX_SHOTS, config.stop.maxShots)
        prefs.put(STOP_MAX_DURATION_MS, config.stop.maxDurationMs)
        prefs.put(STOP_MIN_BATTERY_PERCENT, config.stop.minBatteryPercent)
        prefs.put(STOP_MIN_FREE_BYTES, config.stop.minFreeBytes)

        val target = config.target
        prefs.put(CONFIG_TARGET_X, target?.xFraction)
        prefs.put(CONFIG_TARGET_Y, target?.yFraction)
        prefs.put(CONFIG_TARGET_W, target?.widthFraction)
        prefs.put(CONFIG_TARGET_H, target?.heightFraction)
        prefs.put(CONFIG_TARGET_JITTER, target?.jitter)
    }

    fun readConfig(prefs: Preferences): SessionConfig? = runCatching {
        val pkg = prefs[MODE_PACKAGE]
        val mode = when (prefs[MODE]) {
            MODE_TIMELAPSE -> pkg?.let { TriggerMode.Timelapse(it) }
            MODE_PERIODIC_TAP -> TriggerMode.PeriodicTap(pkg)
            else -> null
        } ?: return@runCatching null
        val interval = prefs[INTERVAL_MS]?.takeIf { it > 0L } ?: return@runCatching null
        SessionConfig(
            mode = mode,
            intervalMs = interval,
            startDelayMs = prefs[START_DELAY_MS] ?: 0L,
            ramp = readRamp(prefs),
            stop = StopConditions(
                maxShots = prefs[STOP_MAX_SHOTS],
                maxDurationMs = prefs[STOP_MAX_DURATION_MS],
                minBatteryPercent = prefs[STOP_MIN_BATTERY_PERCENT],
                minFreeBytes = prefs[STOP_MIN_FREE_BYTES],
            ),
            target = readConfigTarget(prefs),
            verifyCaptures = prefs[VERIFY_CAPTURES] ?: false,
            minIntervalFloorMs = prefs[MIN_INTERVAL_FLOOR_MS] ?: 0L,
        )
    }.getOrNull()

    fun writeTarget(prefs: MutablePreferences, packageName: String, orientation: Int, target: TapTarget) {
        val p = targetPrefix(packageName, orientation)
        prefs[floatPreferencesKey(p + "x")] = target.xFraction
        prefs[floatPreferencesKey(p + "y")] = target.yFraction
        prefs[floatPreferencesKey(p + "w")] = target.widthFraction
        prefs[floatPreferencesKey(p + "h")] = target.heightFraction
        prefs[booleanPreferencesKey(p + "jitter")] = target.jitter
    }

    fun readTarget(prefs: Preferences, packageName: String, orientation: Int): TapTarget? = runCatching {
        val p = targetPrefix(packageName, orientation)
        val x = prefs[floatPreferencesKey(p + "x")] ?: return@runCatching null
        val y = prefs[floatPreferencesKey(p + "y")] ?: return@runCatching null
        TapTarget(
            xFraction = x,
            yFraction = y,
            widthFraction = prefs[floatPreferencesKey(p + "w")] ?: 0f,
            heightFraction = prefs[floatPreferencesKey(p + "h")] ?: 0f,
            jitter = prefs[booleanPreferencesKey(p + "jitter")] ?: false,
        )
    }.getOrNull()

    /**
     * Every calibration stored for [packageName], keyed by the display rotation it was captured in
     * and ordered by that rotation. A slot that is half written is simply absent, exactly as in
     * [readTarget].
     */
    fun readTargetSlots(prefs: Preferences, packageName: String): Map<Int, TapTarget> {
        val prefix = targetPrefix(packageName)
        return prefs.asMap().keys.asSequence()
            .map { it.name }
            .filter { it.startsWith(prefix) }
            // "target_<pkg>_<rotation>_x" -> <rotation>. A package whose *name* ends in "_<digits>"
            // can produce a bogus rotation here, which costs nothing: the slot is then read back
            // through readTarget, which finds no keys for it and drops it.
            .mapNotNull { it.removePrefix(prefix).substringBefore('_').toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { rotation -> readTarget(prefs, packageName, rotation)?.let { rotation to it } }
            .toMap()
    }

    /**
     * The calibration to use for [packageName] with the display at [preferredRotation]: that slot if
     * it has one, else the natural-orientation slot, else any slot at all.
     *
     * Slots exist because the shutter is rarely at the mirrored spot after a quarter turn, so the
     * rotation the user calibrated in is the *best* answer — but every stored fraction is already in
     * natural display space (see [com.dazix.caelis.service.DisplayGeometry]), so every other
     * slot is a usable answer too. Reading only the current rotation and the natural one meant a
     * calibration captured in landscape vanished as soon as the phone was held upright: Home said
     * "not calibrated", Start was blocked, and the read-back miss was persisted over the config.
     */
    fun readAnyTarget(
        prefs: Preferences,
        packageName: String,
        preferredRotation: Int,
    ): TapTarget? {
        val slots = readTargetSlots(prefs, packageName)
        return slots[preferredRotation] ?: slots[NATURAL_ROTATION] ?: slots.values.firstOrNull()
    }

    fun writeAdvanced(prefs: MutablePreferences, settings: AdvancedSettings) {
        prefs.put(ADV_LAUNCH_CAMERA_MODE, settings.launchCameraMode)
        prefs[ADV_ADAPTIVE_LONG_EXPOSURE_WAIT] = settings.intervalAfterCapture
        prefs[ADV_ENSURE_SELF_TIMER_OFF] = settings.ensureSelfTimerOff
        prefs[ADV_DISMISS_POST_CAPTURE_UI] = settings.dismissPostCaptureUi
        prefs[ADV_KEEP_SCREEN_ON] = settings.keepScreenOn
        prefs[ADV_DIM_OVERLAY] = settings.dimOverlay
        prefs[ADV_HANDHELD_WARNING] = settings.handheldWarning
    }

    fun readAdvanced(prefs: Preferences): AdvancedSettings = runCatching {
        AdvancedSettings(
            launchCameraMode = prefs[ADV_LAUNCH_CAMERA_MODE],
            intervalAfterCapture = prefs[ADV_ADAPTIVE_LONG_EXPOSURE_WAIT] ?: false,
            ensureSelfTimerOff = prefs[ADV_ENSURE_SELF_TIMER_OFF] ?: false,
            dismissPostCaptureUi = prefs[ADV_DISMISS_POST_CAPTURE_UI] ?: false,
            // Absent key -> the shared default, which for these two is whatever
            // overlay.OverlaySettings defaults to: the panel is the thing that obeys them, and a
            // record from before they existed must not read as a *different* setting from a fresh
            // install. Hence AdvancedSettings()'s own values rather than a literal here.
            keepScreenOn = prefs[ADV_KEEP_SCREEN_ON] ?: AdvancedSettings().keepScreenOn,
            dimOverlay = prefs[ADV_DIM_OVERLAY] ?: AdvancedSettings().dimOverlay,
            handheldWarning = prefs[ADV_HANDHELD_WARNING] ?: AdvancedSettings().handheldWarning,
        )
    }.getOrElse { AdvancedSettings() }

    private fun readRamp(prefs: Preferences): RampSpec? {
        val curve = RampCurve.entries.firstOrNull { it.name == prefs[RAMP_CURVE] } ?: return null
        val domain = RampDomain.entries.firstOrNull { it.name == prefs[RAMP_DOMAIN] } ?: return null
        val end = prefs[RAMP_END_INTERVAL_MS]?.takeIf { it > 0L } ?: return null
        return RampSpec(
            curve = curve,
            endIntervalMs = end,
            domain = domain,
            overShots = prefs[RAMP_OVER_SHOTS],
            overDurationMs = prefs[RAMP_OVER_DURATION_MS],
        )
    }

    private fun readConfigTarget(prefs: Preferences): TapTarget? {
        val x = prefs[CONFIG_TARGET_X] ?: return null
        val y = prefs[CONFIG_TARGET_Y] ?: return null
        return TapTarget(
            xFraction = x,
            yFraction = y,
            widthFraction = prefs[CONFIG_TARGET_W] ?: 0f,
            heightFraction = prefs[CONFIG_TARGET_H] ?: 0f,
            jitter = prefs[CONFIG_TARGET_JITTER] ?: false,
        )
    }

    /** Common prefix of every calibration key of [packageName], whatever rotation it was stored in. */
    private fun targetPrefix(packageName: String) = "target_${packageName}_"

    private fun targetPrefix(packageName: String, orientation: Int) =
        targetPrefix(packageName) + "${orientation}_"

    /**
     * `Surface.ROTATION_0`, spelled out so this file stays free of Android classes and testable on
     * the JVM. The natural orientation is the fallback slot because it is the one a phone is most
     * often calibrated in.
     */
    private const val NATURAL_ROTATION = 0

    fun readSunArKeepScreenOn(prefs: Preferences): Boolean =
        prefs[SUN_AR_KEEP_SCREEN_ON] ?: false

    fun writeSunArKeepScreenOn(prefs: MutablePreferences, enabled: Boolean) {
        prefs[SUN_AR_KEEP_SCREEN_ON] = enabled
    }

    fun readSunArRedVisionMode(prefs: Preferences): Boolean =
        prefs[SUN_AR_RED_VISION_MODE] ?: false

    fun writeSunArRedVisionMode(prefs: MutablePreferences, enabled: Boolean) {
        prefs[SUN_AR_RED_VISION_MODE] = enabled
    }

    /**
     * The shared compass correction, falling back to whichever per-screen value exists.
     *
     * Sun AR wins the tie: it is the screen where the correction can actually be measured against a
     * ridge, so a value stored there is the better-founded of the two.
     */
    fun readArAzimuthOffset(prefs: Preferences): Float =
        prefs[AR_AZIMUTH_OFFSET]
            ?: prefs[SUN_AR_AZIMUTH_OFFSET]
            ?: prefs[NIGHT_SKY_AZIMUTH_OFFSET]
            ?: 0.0f

    fun readArElevationOffset(prefs: Preferences): Float =
        prefs[AR_ELEVATION_OFFSET]
            ?: prefs[SUN_AR_ELEVATION_OFFSET]
            ?: prefs[NIGHT_SKY_ELEVATION_OFFSET]
            ?: 0.0f

    fun writeArCalibration(prefs: MutablePreferences, azimuthDeg: Float, elevationDeg: Float) {
        prefs[AR_AZIMUTH_OFFSET] = azimuthDeg
        prefs[AR_ELEVATION_OFFSET] = elevationDeg
    }

    fun readSunArAzimuthOffset(prefs: Preferences): Float =
        prefs[SUN_AR_AZIMUTH_OFFSET] ?: 0.0f

    fun writeSunArAzimuthOffset(prefs: MutablePreferences, offsetDeg: Float) {
        prefs[SUN_AR_AZIMUTH_OFFSET] = offsetDeg
    }

    fun readSunArElevationOffset(prefs: Preferences): Float =
        prefs[SUN_AR_ELEVATION_OFFSET] ?: 0.0f

    fun writeSunArElevationOffset(prefs: MutablePreferences, offsetDeg: Float) {
        prefs[SUN_AR_ELEVATION_OFFSET] = offsetDeg
    }

    fun readSunArPitchOffset(prefs: Preferences): Float = readSunArElevationOffset(prefs)

    fun writeSunArPitchOffset(prefs: MutablePreferences, offsetDeg: Float) = writeSunArElevationOffset(prefs, offsetDeg)

    fun readSunArQuaternion(prefs: Preferences): FloatArray {
        val x = prefs[SUN_AR_QUAT_X] ?: 0.0f
        val y = prefs[SUN_AR_QUAT_Y] ?: 0.0f
        val z = prefs[SUN_AR_QUAT_Z] ?: 0.0f
        val w = prefs[SUN_AR_QUAT_W] ?: 1.0f
        return floatArrayOf(x, y, z, w)
    }

    fun writeSunArQuaternion(prefs: MutablePreferences, quat: FloatArray) {
        prefs[SUN_AR_QUAT_X] = if (quat.size > 0) quat[0] else 0.0f
        prefs[SUN_AR_QUAT_Y] = if (quat.size > 1) quat[1] else 0.0f
        prefs[SUN_AR_QUAT_Z] = if (quat.size > 2) quat[2] else 0.0f
        prefs[SUN_AR_QUAT_W] = if (quat.size > 3) quat[3] else 1.0f
    }

    fun readNightSkyKeepScreenOn(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_KEEP_SCREEN_ON] ?: false

    fun writeNightSkyKeepScreenOn(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_KEEP_SCREEN_ON] = enabled
    }

    fun readNightSkyRedVisionMode(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_RED_VISION_MODE] ?: true

    fun writeNightSkyRedVisionMode(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_RED_VISION_MODE] = enabled
    }

    fun readNightSkyAzimuthOffset(prefs: Preferences): Float =
        prefs[NIGHT_SKY_AZIMUTH_OFFSET] ?: 0.0f

    fun writeNightSkyAzimuthOffset(prefs: MutablePreferences, offsetDeg: Float) {
        prefs[NIGHT_SKY_AZIMUTH_OFFSET] = offsetDeg
    }

    fun readNightSkyElevationOffset(prefs: Preferences): Float =
        prefs[NIGHT_SKY_ELEVATION_OFFSET] ?: 0.0f

    fun writeNightSkyElevationOffset(prefs: MutablePreferences, offsetDeg: Float) {
        prefs[NIGHT_SKY_ELEVATION_OFFSET] = offsetDeg
    }

    fun readNightSkyPitchOffset(prefs: Preferences): Float = readNightSkyElevationOffset(prefs)

    fun writeNightSkyPitchOffset(prefs: MutablePreferences, offsetDeg: Float) = writeNightSkyElevationOffset(prefs, offsetDeg)

    fun readNightSkyQuaternion(prefs: Preferences): FloatArray {
        val x = prefs[NIGHT_SKY_QUAT_X] ?: 0.0f
        val y = prefs[NIGHT_SKY_QUAT_Y] ?: 0.0f
        val z = prefs[NIGHT_SKY_QUAT_Z] ?: 0.0f
        val w = prefs[NIGHT_SKY_QUAT_W] ?: 1.0f
        return floatArrayOf(x, y, z, w)
    }

    fun writeNightSkyQuaternion(prefs: MutablePreferences, quat: FloatArray) {
        prefs[NIGHT_SKY_QUAT_X] = if (quat.size > 0) quat[0] else 0.0f
        prefs[NIGHT_SKY_QUAT_Y] = if (quat.size > 1) quat[1] else 0.0f
        prefs[NIGHT_SKY_QUAT_Z] = if (quat.size > 2) quat[2] else 0.0f
        prefs[NIGHT_SKY_QUAT_W] = if (quat.size > 3) quat[3] else 1.0f
    }

    fun readNightSkyOpaqueBackground(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_OPAQUE_BACKGROUND] ?: false

    fun writeNightSkyOpaqueBackground(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_OPAQUE_BACKGROUND] = enabled
    }

    fun readNightSkyMilkyWayVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_MILKY_WAY_VISIBLE] ?: true

    fun writeNightSkyMilkyWayVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_MILKY_WAY_VISIBLE] = enabled
    }

    fun readNightSkyAtmosphereVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_ATMOSPHERE_VISIBLE] ?: true

    fun writeNightSkyAtmosphereVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_ATMOSPHERE_VISIBLE] = enabled
    }

    fun readNightSkyLandscapeVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_LANDSCAPE_VISIBLE] ?: true

    fun writeNightSkyLandscapeVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_LANDSCAPE_VISIBLE] = enabled
    }

    fun readNightSkyGridVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_GRID_VISIBLE] ?: true

    fun writeNightSkyGridVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_GRID_VISIBLE] = enabled
    }

    fun readNightSkyPlanetsVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_PLANETS_VISIBLE] ?: true

    fun writeNightSkyPlanetsVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_PLANETS_VISIBLE] = enabled
    }

    fun readNightSkyDsosVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_DSOS_VISIBLE] ?: false

    fun writeNightSkyDsosVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_DSOS_VISIBLE] = enabled
    }

    fun readNightSkyMeteorShowersVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_METEOR_SHOWERS_VISIBLE] ?: false

    fun writeNightSkyMeteorShowersVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_METEOR_SHOWERS_VISIBLE] = enabled
    }

    fun readNightSkyConstellationsVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_CONSTELLATIONS_VISIBLE] ?: true

    fun writeNightSkyConstellationsVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_CONSTELLATIONS_VISIBLE] = enabled
    }

    fun readNightSkyConstellationArtVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_CONSTELLATION_ART_VISIBLE] ?: true

    fun writeNightSkyConstellationArtVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_CONSTELLATION_ART_VISIBLE] = enabled
    }

    /** Clamped on read as well: a stored value outside the range must not hide the artwork entirely. */
    fun readNightSkyConstellationArtOpacity(prefs: Preferences): Float =
        ConstellationArtOpacity.clamp(prefs[NIGHT_SKY_CONSTELLATION_ART_OPACITY] ?: ConstellationArtOpacity.DEFAULT)

    fun writeNightSkyConstellationArtOpacity(prefs: MutablePreferences, opacity: Float) {
        prefs[NIGHT_SKY_CONSTELLATION_ART_OPACITY] = ConstellationArtOpacity.clamp(opacity)
    }

    fun readNightSkyAsterismNamesVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_ASTERISM_NAMES_VISIBLE] ?: true

    fun writeNightSkyAsterismNamesVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_ASTERISM_NAMES_VISIBLE] = enabled
    }

    fun readNightSkySensorTracking(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_SENSOR_TRACKING] ?: true

    fun writeNightSkySensorTracking(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_SENSOR_TRACKING] = enabled
    }

    fun readNightSkySearchHistory(prefs: Preferences): String =
        prefs[NIGHT_SKY_SEARCH_HISTORY] ?: ""

    fun writeNightSkySearchHistory(prefs: MutablePreferences, history: String) {
        prefs[NIGHT_SKY_SEARCH_HISTORY] = history
    }

    fun readNightVisionTheme(prefs: Preferences): NightVisionTheme {
        val name = prefs[NIGHT_VISION_THEME_KEY] ?: return NightVisionTheme.AMBER
        return runCatching { NightVisionTheme.valueOf(name) }.getOrDefault(NightVisionTheme.AMBER)
    }

    fun writeNightVisionTheme(prefs: MutablePreferences, theme: NightVisionTheme) {
        prefs[NIGHT_VISION_THEME_KEY] = theme.name
    }

    fun readNightVisionActivationMode(prefs: Preferences): NightVisionActivationMode {
        val name = prefs[NIGHT_VISION_MODE_KEY] ?: return NightVisionActivationMode.OFF
        return runCatching { NightVisionActivationMode.valueOf(name) }.getOrDefault(NightVisionActivationMode.OFF)
    }

    fun writeNightVisionActivationMode(prefs: MutablePreferences, mode: NightVisionActivationMode) {
        prefs[NIGHT_VISION_MODE_KEY] = mode.name
    }

    fun readCustomStartTime(prefs: Preferences): LocalTime {
        val minutes = prefs[CUSTOM_START_MINUTES_KEY] ?: (22 * 60)
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return LocalTime.of(h, m)
    }

    fun writeCustomStartTime(prefs: MutablePreferences, time: LocalTime) {
        prefs[CUSTOM_START_MINUTES_KEY] = time.hour * 60 + time.minute
    }

    fun readCustomEndTime(prefs: Preferences): LocalTime {
        val minutes = prefs[CUSTOM_END_MINUTES_KEY] ?: (6 * 60)
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return LocalTime.of(h, m)
    }

    fun writeCustomEndTime(prefs: MutablePreferences, time: LocalTime) {
        prefs[CUSTOM_END_MINUTES_KEY] = time.hour * 60 + time.minute
    }

    fun readDebugModeEnabled(prefs: Preferences): Boolean =
        prefs[DEBUG_MODE_ENABLED] ?: false

    fun writeDebugModeEnabled(prefs: MutablePreferences, enabled: Boolean) {
        prefs[DEBUG_MODE_ENABLED] = enabled
    }

    fun readDebugPreferences(prefs: Preferences): DebugPreferences {
        val legacyDebug = prefs[DEBUG_MODE_ENABLED] ?: false
        return DebugPreferences(
            enableNightSkyFocusRegion = prefs[DEBUG_NIGHT_SKY_FOCUS_REGION] ?: legacyDebug,
            showTerrainMetrics = prefs[DEBUG_SHOW_TERRAIN_METRICS] ?: false,
            starTapRadiusDp = prefs[DEBUG_STAR_TAP_RADIUS] ?: DebugPreferences.DEFAULT_STAR_TAP_RADIUS_DP,
            showTapRadiusIndicator = prefs[DEBUG_SHOW_TAP_RADIUS_INDICATOR] ?: false,
            logCompass = prefs[DEBUG_LOG_COMPASS] ?: false,
            disableTiltRecovery = prefs[DEBUG_DISABLE_TILT_RECOVERY] ?: false,
        )
    }

    fun writeDebugPreferences(prefs: MutablePreferences, preferences: DebugPreferences) {
        prefs[DEBUG_NIGHT_SKY_FOCUS_REGION] = preferences.enableNightSkyFocusRegion
        prefs[DEBUG_SHOW_TERRAIN_METRICS] = preferences.showTerrainMetrics
        prefs[DEBUG_STAR_TAP_RADIUS] = preferences.starTapRadiusDp
        prefs[DEBUG_SHOW_TAP_RADIUS_INDICATOR] = preferences.showTapRadiusIndicator
        prefs[DEBUG_LOG_COMPASS] = preferences.logCompass
        prefs[DEBUG_DISABLE_TILT_RECOVERY] = preferences.disableTiltRecovery
    }

    /**
     * Absent means "never agreed", which for these two is the same as "no": unlike the terrain
     * download there is no first-run prompt to tell the two apart, the question is only ever asked
     * from Settings.
     */
    fun readArCore(prefs: Preferences): ArCoreSettings = ArCoreSettings(
        arCoreConsent = prefs[ARCORE_CONSENT] ?: false,
        geospatialConsent = prefs[GEOSPATIAL_CONSENT] ?: false,
    ).normalized()

    /** Writes the normalised pair, so a stale geospatial `true` is overwritten, not just ignored. */
    fun writeArCore(prefs: MutablePreferences, settings: ArCoreSettings) {
        val normalized = settings.normalized()
        prefs[ARCORE_CONSENT] = normalized.arCoreConsent
        prefs[GEOSPATIAL_CONSENT] = normalized.geospatialConsent
    }

    fun readHorizon(prefs: Preferences): HorizonSettings = HorizonSettings(
        enabled = prefs[HORIZON_ENABLED] ?: false,
        // Absent means "never asked", which is not the same as "said no" — the prompt has to be able
        // to tell those apart or it either nags forever or never appears at all.
        networkConsent = prefs[HORIZON_NETWORK_CONSENT],
        autoRange = prefs[HORIZON_AUTO_RANGE] ?: true,
        maxRangeKm = prefs[HORIZON_MAX_RANGE_KM] ?: HorizonSettings.DEFAULT_MAX_RANGE_KM,
        autoDownload = prefs[HORIZON_AUTO_DOWNLOAD] ?: true,
        // A value that is not one of the offered limits (an older build's, a hand-edited file) falls
        // back to the default rather than to a limit nobody can see or pick in the UI.
        confirmAboveMb = prefs[HORIZON_CONFIRM_ABOVE_MB]
            ?.takeIf { it in HorizonSettings.CONFIRM_LIMIT_CHOICES_MB }
            ?: HorizonSettings.DEFAULT_CONFIRM_ABOVE_MB,
        shadeOccludedTrajectory = prefs[HORIZON_SHADE_OCCLUDED] ?: true,
        wifiOnly = prefs[HORIZON_WIFI_ONLY] ?: false,
        realHorizonInNightSky = prefs[HORIZON_NIGHT_SKY_REAL] ?: false,
        peakNamesInSunAr = prefs[HORIZON_PEAK_NAMES_SUN_AR] ?: true,
        peakNamesInNightSky = prefs[HORIZON_PEAK_NAMES_NIGHT_SKY] ?: true,
    )

    fun writeHorizon(prefs: MutablePreferences, settings: HorizonSettings) {
        prefs[HORIZON_ENABLED] = settings.enabled
        prefs.put(HORIZON_NETWORK_CONSENT, settings.networkConsent)
        prefs[HORIZON_AUTO_RANGE] = settings.autoRange
        prefs[HORIZON_MAX_RANGE_KM] = settings.maxRangeKm
        prefs[HORIZON_AUTO_DOWNLOAD] = settings.autoDownload
        prefs[HORIZON_CONFIRM_ABOVE_MB] = settings.confirmAboveMb
        // The height above terrain is never stored; a value from before it was is dropped here.
        prefs.remove(HORIZON_EYE_HEIGHT_CM)
        prefs[HORIZON_SHADE_OCCLUDED] = settings.shadeOccludedTrajectory
        prefs[HORIZON_WIFI_ONLY] = settings.wifiOnly
        prefs[HORIZON_NIGHT_SKY_REAL] = settings.realHorizonInNightSky
        prefs[HORIZON_PEAK_NAMES_SUN_AR] = settings.peakNamesInSunAr
        prefs[HORIZON_PEAK_NAMES_NIGHT_SKY] = settings.peakNamesInNightSky
    }

    private val NOTIFICATION_PERMISSION_PROMPTED = booleanPreferencesKey("notification_permission_prompted")

    fun readNotificationPermissionPrompted(prefs: Preferences): Boolean =
        prefs[NOTIFICATION_PERMISSION_PROMPTED] ?: false

    fun writeNotificationPermissionPrompted(prefs: MutablePreferences, prompted: Boolean) {
        prefs[NOTIFICATION_PERMISSION_PROMPTED] = prompted
    }

    private fun <T : Any> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else set(key, value)
    }
}
