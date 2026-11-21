package com.dazix.caelis.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.caelis.R
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.ar.SolarLocationProvider
import com.dazix.caelis.BuildConfig
import com.dazix.caelis.data.ArCoreSettings
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.data.horizon.DemTileImporter
import com.dazix.caelis.data.horizon.HorizonRepository
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.RampDomain
import com.dazix.caelis.domain.RampSpec
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.domain.cameramode.CustomCameraMode
import com.dazix.caelis.domain.cameramode.CustomModeKey
import com.dazix.caelis.domain.nightsky.NightVisionActivationMode
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.overlay.CalibrationOverlay
import com.dazix.caelis.overlay.ModeRecordingOverlay
import com.dazix.caelis.overlay.OverlayOptions
import com.dazix.caelis.overlay.OverlaySettings
import com.dazix.caelis.service.CameraProfiles
import com.dazix.caelis.service.DisplayGeometry
import com.dazix.caelis.service.CustomReplayResult
import com.dazix.caelis.service.ShutterAccessibilityService
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import com.dazix.caelis.data.horizon.DownloadEstimate
import com.dazix.caelis.data.horizon.OfflineDownloadState
import com.dazix.caelis.domain.horizon.OfflineRegion
import com.dazix.caelis.data.location.SavedPlaceRepository
import com.dazix.caelis.domain.location.IncomingPlace
import com.dazix.caelis.domain.location.IncomingPlaceParser
import com.dazix.caelis.domain.location.SavedPlace
import com.dazix.caelis.ui.horizon.OfflineTerrainPrefill
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dazix.caelis.domain.solar.GeoLocation
import kotlin.math.roundToLong

/** The app is a single activity; this is the whole navigation model. */
enum class Screen {
    HOME, SETUP, RAMP, ADVANCED, ONBOARDING, APP_PICKER, SUN_AR, NIGHT_SKY_AR, SETTINGS, OFFLINE_TERRAIN,
    CUSTOM_MODES, CUSTOM_MODE_EDIT,
}

enum class ModeChoice { TIMELAPSE, PERIODIC_TAP }

/** Durations are always entered with an explicit unit, never as a bare number of milliseconds. */
enum class TimeUnitChoice(@param:StringRes val labelRes: Int, val millis: Long) {
    SECONDS(R.string.unit_seconds, 1_000L),
    MINUTES(R.string.unit_minutes, 60_000L),
    ;

    fun toMs(value: String): Long? = value.toPositiveDoubleOrNull()?.let { (it * millis).roundToLong() }

    fun format(ms: Long): String {
        val scaled = ms.toDouble() / millis
        return if (scaled == scaled.toLong().toDouble()) {
            scaled.toLong().toString()
        } else {
            String.format("%.1f", scaled)
        }
    }

    companion object {
        /** Picks the unit a human would have typed for this duration. */
        fun bestFor(ms: Long): TimeUnitChoice =
            if (ms >= MINUTES.millis && ms % MINUTES.millis == 0L) MINUTES else SECONDS
    }
}

data class AppEntry(
    val packageName: String,
    val label: String,
    /** Answers one of Android's camera intents, so it is floated to the top of the picker. */
    val isCamera: Boolean,
)

/** Why Start is refused right now. Rendered as inline explanations, never as a silent disable. */
sealed interface StartBlocker {
    data object ServiceOff : StartBlocker
    data object OverlayMissing : StartBlocker
    data object NoTargetApp : StartBlocker
    data class NoCalibration(val appLabel: String) : StartBlocker
    data class IntervalBelowFloor(val intervalMs: Long, val floorMs: Long) : StartBlocker
}

/**
 * Editable session settings. Numbers stay as text while the user types, so a half-typed field never
 * snaps back to a default; [intervalMs] and friends are the parsed view of the same data.
 */
data class SetupUiState(
    val mode: ModeChoice = ModeChoice.TIMELAPSE,
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val target: TapTarget? = null,
    val intervalValue: String = "10",
    val intervalUnit: TimeUnitChoice = TimeUnitChoice.SECONDS,
    val unlimitedShots: Boolean = true,
    val shotCount: String = "300",
    val startDelayValue: String = "5",
    val startDelayUnit: TimeUnitChoice = TimeUnitChoice.SECONDS,
    val maxDurationEnabled: Boolean = false,
    val maxDurationValue: String = "60",
    val maxDurationUnit: TimeUnitChoice = TimeUnitChoice.MINUTES,
    val minBatteryEnabled: Boolean = false,
    val minBatteryPercent: String = "15",
    val minFreeSpaceEnabled: Boolean = false,
    val minFreeSpaceMb: String = "500",
    val verifyCaptures: Boolean = false,
    val rampEnabled: Boolean = false,
    val rampEndValue: String = "30",
    val rampEndUnit: TimeUnitChoice = TimeUnitChoice.SECONDS,
    val rampCurve: RampCurve = RampCurve.LINEAR,
    val rampDomain: RampDomain = RampDomain.SHOT_INDEX,
    val rampOverShots: String = "100",
    val rampOverDurationValue: String = "30",
    val rampOverDurationUnit: TimeUnitChoice = TimeUnitChoice.MINUTES,
) {
    val intervalMs: Long get() = intervalUnit.toMs(intervalValue) ?: 0L
    val startDelayMs: Long get() = startDelayUnit.toMs(startDelayValue) ?: 0L
    val rampEndMs: Long get() = rampEndUnit.toMs(rampEndValue) ?: 0L
    val rampOverDurationMs: Long get() = rampOverDurationUnit.toMs(rampOverDurationValue) ?: 0L
    val shotCountValue: Int? get() = shotCount.toIntOrNull()?.takeIf { it > 0 }

    /**
     * A calibration belongs to exactly one package, so changing the target app drops it instead of
     * carrying the previous app's shutter position over; the new package's stored calibration (if it
     * has one) is loaded straight after. Applied to every setup edit, so no caller can forget it.
     */
    fun retargeted(previous: SetupUiState): SetupUiState =
        if (targetPackage == previous.targetPackage) this else copy(target = null)

    fun rampSpec(): RampSpec? {
        if (!rampEnabled || rampEndMs <= 0L) return null
        return when (rampDomain) {
            RampDomain.SHOT_INDEX -> {
                val shots = rampOverShots.toIntOrNull()?.takeIf { it > 0 } ?: return null
                RampSpec(rampCurve, rampEndMs, RampDomain.SHOT_INDEX, overShots = shots)
            }
            RampDomain.ELAPSED_TIME -> {
                val duration = rampOverDurationMs.takeIf { it > 0L } ?: return null
                RampSpec(rampCurve, rampEndMs, RampDomain.ELAPSED_TIME, overDurationMs = duration)
            }
        }
    }

    /** What this screen's mode + app choice means to the engine. Timelapse needs an app to drive. */
    fun triggerMode(): TriggerMode = when (mode) {
        ModeChoice.TIMELAPSE -> targetPackage?.let { TriggerMode.Timelapse(it) }
            ?: TriggerMode.PeriodicTap(null)
        ModeChoice.PERIODIC_TAP -> TriggerMode.PeriodicTap(targetPackage)
    }

    fun toConfig(floorMs: Long): SessionConfig =
        SessionConfig(
            mode = triggerMode(),
            intervalMs = intervalMs.coerceAtLeast(floorMs),
            startDelayMs = startDelayMs,
            ramp = rampSpec(),
            stop = StopConditions(
                maxShots = if (unlimitedShots) null else shotCountValue,
                maxDurationMs = if (maxDurationEnabled) maxDurationUnit.toMs(maxDurationValue) else null,
                minBatteryPercent = if (minBatteryEnabled) minBatteryPercent.toIntOrNull() else null,
                minFreeBytes = if (minFreeSpaceEnabled) {
                    minFreeSpaceMb.toLongOrNull()?.times(1_000_000L)
                } else {
                    null
                },
            ),
            target = target,
            verifyCaptures = verifyCaptures,
            minIntervalFloorMs = floorMs,
        )

    companion object {
        fun from(config: SessionConfig, label: String?): SetupUiState {
            val intervalUnit = TimeUnitChoice.bestFor(config.intervalMs)
            val delayUnit = TimeUnitChoice.bestFor(config.startDelayMs)
            val ramp = config.ramp
            val rampEndUnit = ramp?.let { TimeUnitChoice.bestFor(it.endIntervalMs) } ?: TimeUnitChoice.SECONDS
            val rampDurationUnit = ramp?.overDurationMs
                ?.let { TimeUnitChoice.bestFor(it) } ?: TimeUnitChoice.MINUTES
            val maxDurationUnit = config.stop.maxDurationMs
                ?.let { TimeUnitChoice.bestFor(it) } ?: TimeUnitChoice.MINUTES
            val defaults = SetupUiState()
            return SetupUiState(
                mode = if (config.mode is TriggerMode.Timelapse) {
                    ModeChoice.TIMELAPSE
                } else {
                    ModeChoice.PERIODIC_TAP
                },
                targetPackage = config.mode.targetPackage,
                targetLabel = label,
                target = config.target,
                intervalValue = intervalUnit.format(config.intervalMs),
                intervalUnit = intervalUnit,
                unlimitedShots = config.stop.maxShots == null,
                shotCount = config.stop.maxShots?.toString() ?: defaults.shotCount,
                startDelayValue = delayUnit.format(config.startDelayMs),
                startDelayUnit = delayUnit,
                maxDurationEnabled = config.stop.maxDurationMs != null,
                maxDurationValue = config.stop.maxDurationMs
                    ?.let { maxDurationUnit.format(it) } ?: defaults.maxDurationValue,
                maxDurationUnit = maxDurationUnit,
                minBatteryEnabled = config.stop.minBatteryPercent != null,
                minBatteryPercent = config.stop.minBatteryPercent?.toString()
                    ?: defaults.minBatteryPercent,
                minFreeSpaceEnabled = config.stop.minFreeBytes != null,
                minFreeSpaceMb = config.stop.minFreeBytes
                    ?.let { (it / 1_000_000L).toString() } ?: defaults.minFreeSpaceMb,
                verifyCaptures = config.verifyCaptures,
                rampEnabled = ramp != null,
                rampEndValue = ramp?.let { rampEndUnit.format(it.endIntervalMs) } ?: defaults.rampEndValue,
                rampEndUnit = rampEndUnit,
                rampCurve = ramp?.curve ?: RampCurve.LINEAR,
                rampDomain = ramp?.domain ?: RampDomain.SHOT_INDEX,
                rampOverShots = ramp?.overShots?.toString() ?: defaults.rampOverShots,
                rampOverDurationValue = ramp?.overDurationMs
                    ?.let { rampDurationUnit.format(it) } ?: defaults.rampOverDurationValue,
                rampOverDurationUnit = rampDurationUnit,
            )
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)

    // ==== ARCore consent ====
    //
    // Only the two switches live here. Nothing in this view model starts, probes or installs
    // ARCore: that is ar.pose.ArCoreSessionController's alone, and it re-reads these values on
    // every use rather than trusting whatever this flow last held.
    val arCoreSettings: StateFlow<ArCoreSettings> = settings.arCore.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ArCoreSettings(),
    )

    fun setArCoreConsent(granted: Boolean) {
        viewModelScope.launch { settings.setArCoreConsent(granted) }
    }

    fun setGeospatialConsent(granted: Boolean) {
        viewModelScope.launch { settings.setGeospatialConsent(granted) }
    }

    /** Whether this build can offer the Geospatial API at all: it needs a key given at build time. */
    val geospatialAvailable: Boolean = BuildConfig.HAS_GEOSPATIAL_API_KEY

    // ==== Terrain horizon ====
    //
    // Owned here rather than by the AR view models because the settings are shared: the search
    // radius, the eye height and the downloaded tiles are the same whether you are framing a sunset
    // or a constellation. The AR screens keep only their own visibility toggle.
    private val horizonRepository = HorizonRepository.getInstance(application)
    private val horizonLocationProvider = SolarLocationProvider(application)

    val horizonSettings: StateFlow<HorizonSettings> = settings.horizon.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HorizonSettings(),
    )

    val horizonState: StateFlow<HorizonState> = horizonRepository.state

    val horizonLocation: StateFlow<GeoLocation> = horizonLocationProvider.location
    val horizonHasGpsFix: StateFlow<Boolean> = horizonLocationProvider.hasGpsFix

    fun startHorizonLocationUpdates() {
        horizonLocationProvider.startUpdates()
    }

    fun stopHorizonLocationUpdates() {
        horizonLocationProvider.stopUpdates()
    }

    fun refreshHorizonLocation() {
        horizonLocationProvider.refreshLocation()
    }

    /** The radius in use once an automatic setting has worked one out; null until then. */
    val effectiveHorizonRangeKm: StateFlow<Int?> = horizonRepository.effectiveRangeKm

    /** False in a build made from source with the `INTERNET` line removed from the manifest. */
    val canDownloadTerrain: Boolean = horizonRepository.canDownload

    private val _storageMetrics = MutableStateFlow(horizonRepository.getStorageMetrics())
    val storageMetrics: StateFlow<com.dazix.caelis.data.horizon.StorageMetrics> = _storageMetrics.asStateFlow()

    private val backStack = mutableListOf(Screen.HOME)
    private val screenState = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = screenState.asStateFlow()

    private val setupState = MutableStateFlow(SetupUiState())
    val setup: StateFlow<SetupUiState> = setupState.asStateFlow()

    private val advancedState = MutableStateFlow(AdvancedSettings())
    val advanced: StateFlow<AdvancedSettings> = advancedState.asStateFlow()

    private val permissionState = MutableStateFlow(Permissions.read(application))
    val permissions: StateFlow<PermissionState> = permissionState.asStateFlow()

    private val allApps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val appsLoadingState = MutableStateFlow(true)
    val appsLoading: StateFlow<Boolean> = appsLoadingState.asStateFlow()

    private val queryState = MutableStateFlow("")
    val appQuery: StateFlow<String> = queryState.asStateFlow()

    private val calibrationState = MutableStateFlow<TapTarget?>(null)
    val calibrationTarget: StateFlow<TapTarget?> = calibrationState.asStateFlow()

    private val serviceUnavailableState = MutableStateFlow(false)
    val serviceUnavailable: StateFlow<Boolean> = serviceUnavailableState.asStateFlow()

    val snapshot: StateFlow<SessionSnapshot> = SessionRepository.snapshot

    val nightVisionTheme: StateFlow<NightVisionTheme> = settings.nightVisionTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, NightVisionTheme.AMBER)

    val nightVisionActivationMode: StateFlow<NightVisionActivationMode> = settings.nightVisionActivationMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, NightVisionActivationMode.OFF)

    val customStartTime: StateFlow<LocalTime> = settings.customStartTime
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalTime.of(22, 0))

    val customEndTime: StateFlow<LocalTime> = settings.customEndTime
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalTime.of(6, 0))

    val debugModeEnabled: StateFlow<Boolean> = settings.debugModeEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val debugPreferences: StateFlow<com.dazix.caelis.data.DebugPreferences> = settings.debugPreferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.dazix.caelis.data.DebugPreferences())

    val notificationPermissionPrompted: StateFlow<Boolean?> = settings.notificationPermissionPrompted
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markNotificationPermissionPrompted() {
        viewModelScope.launch { settings.saveNotificationPermissionPrompted(true) }
    }

    fun setNightVisionTheme(theme: NightVisionTheme) {
        viewModelScope.launch { settings.saveNightVisionTheme(theme) }
    }

    fun setNightVisionActivationMode(mode: NightVisionActivationMode) {
        viewModelScope.launch { settings.saveNightVisionActivationMode(mode) }
    }

    fun setCustomStartTime(time: LocalTime) {
        viewModelScope.launch { settings.saveCustomStartTime(time) }
    }

    fun setCustomEndTime(time: LocalTime) {
        viewModelScope.launch { settings.saveCustomEndTime(time) }
    }

    fun setDebugModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.saveDebugModeEnabled(enabled) }
    }

    fun setDebugPreferences(preferences: com.dazix.caelis.data.DebugPreferences) {
        viewModelScope.launch { settings.saveDebugPreferences(preferences) }
    }

    /** The shortest interval the chosen camera app can sustain, straight from its profile. */
    val intervalFloorMs: StateFlow<Long> = setupState
        .map { floorFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, floorFor(SetupUiState()))

    val visibleApps: StateFlow<List<AppEntry>> = combine(allApps, queryState) { apps, query ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blockers: StateFlow<List<StartBlocker>> =
        combine(setupState, permissionState, calibrationState) { setup, permissions, target ->
            buildList {
                if (!permissions.accessibilityEnabled) add(StartBlocker.ServiceOff)
                if (!permissions.canDrawOverlays) add(StartBlocker.OverlayMissing)
                if (setup.mode == ModeChoice.TIMELAPSE && setup.targetPackage == null) {
                    add(StartBlocker.NoTargetApp)
                }
                if (target == null) {
                    add(
                        StartBlocker.NoCalibration(
                            setup.targetLabel ?: setup.targetPackage
                                ?: getApplication<Application>().getString(R.string.picker_title),
                        )
                    )
                }
                val floor = floorFor(setup)
                if (setup.intervalMs < floor) {
                    add(StartBlocker.IntervalBelowFloor(setup.intervalMs, floor))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var persistJob: Job? = null

    /**
     * Everything that reads or writes a calibration runs through this one chain, so a store read
     * can never overtake the write of a calibration the user has just captured.
     */
    private var calibrationJob: Job? = null

    init {
        // The sizes in Settings were measured once and then only on resume, so a download made in an
        // AR screen showed up there late. Measure again whenever the terrain work moves to another
        // phase (a tile stored, a profile written, a failure) — not on every byte of a download.
        viewModelScope.launch {
            horizonRepository.state
                .distinctUntilChangedBy { it::class }
                .collect { refreshStorageMetrics() }
        }
        viewModelScope.launch {
            val stored = settings.lastConfig.first()
            if (stored != null) {
                setupState.value = SetupUiState.from(stored, labelFor(stored.mode.targetPackage))
            }
            advancedState.value = settings.advanced.first().withOverlayOptions(
                OverlaySettings.current(getApplication())
            )
            refreshSystemState()
            // Started only after the stored settings are in, so the first emission cannot be
            // overwritten by the load it raced with.
            viewModelScope.launch { observeOverlayOptions() }
        }
        viewModelScope.launch { loadInstalledApps() }
        viewModelScope.launch { observeCalibrationResults() }
    }

    /**
     * The calibration surface is an overlay window, not an activity we get a result from, so what the
     * user designated arrives here instead. This is the *only* path for a periodic tap with no target
     * app: that calibration has no per-package slot in the store, so re-reading the store on resume
     * would find nothing and report the tap the user just made as not calibrated.
     */
    private suspend fun observeCalibrationResults() {
        CalibrationOverlay.lastResult.collect { result ->
            if (result != null) onCalibrated(result.packageName, result.rotation, result.target)
        }
    }

    fun navigate(target: Screen) {
        if (target == screenState.value) return
        if (target == Screen.SETTINGS) refreshStorageMetrics()
        if (target == Screen.HOME) {
            backStack.clear()
            backStack.add(Screen.HOME)
        } else {
            val isTopLevel = NavigationDestination.forScreen(target) != null
            if (isTopLevel) {
                backStack.clear()
                backStack.add(Screen.HOME)
                backStack.add(target)
            } else {
                val existingIndex = backStack.lastIndexOf(target)
                if (existingIndex >= 0) {
                    while (backStack.size > existingIndex + 1) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                } else {
                    backStack.add(target)
                }
            }
        }
        screenState.value = target
    }

    private val focusTerrainDownloadState = MutableStateFlow(false)

    /** Settings should scroll to the terrain download once it is shown; cleared when it has. */
    val focusTerrainDownload: StateFlow<Boolean> = focusTerrainDownloadState.asStateFlow()

    /** From an AR screen's "data missing" chip: Settings, at the place where the data is fetched. */
    fun openTerrainDownloadSettings() {
        focusTerrainDownloadState.value = true
        navigate(Screen.SETTINGS)
    }

    fun terrainDownloadFocused() {
        focusTerrainDownloadState.value = false
    }

    fun back() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            screenState.value = backStack.last()
        } else {
            backStack.clear()
            backStack.add(Screen.HOME)
            screenState.value = Screen.HOME
        }
    }

    /** Called on every resume: Settings toggles change behind our back. */
    fun refreshSystemState() {
        val application = getApplication<Application>()
        permissionState.value = Permissions.read(application)
        reloadCalibration()
        refreshStorageMetrics()
    }

    fun updateSetup(transform: (SetupUiState) -> SetupUiState) {
        val previous = setupState.value
        val updated = transform(previous).retargeted(previous)
        setupState.value = updated
        if (updated.targetPackage != previous.targetPackage) {
            // Say "not calibrated" at once rather than after the store answers, so the previous
            // app's calibration is never shown against the newly chosen one.
            calibrationState.value = updated.target
            reloadCalibration()
        }
        schedulePersist()
    }

    /**
     * The two screen-related extras are carried out by the floating panel, which reads them from its
     * own synchronously-readable store and can also be toggled from the panel itself. They are
     * written to both stores here, so the setting the user sees in the app is the one the panel
     * obeys and neither place can drift into telling a different story.
     */
    // ==== Terrain horizon actions ====

    private fun editHorizon(recompute: Boolean = false, transform: (HorizonSettings) -> HorizonSettings) {
        viewModelScope.launch {
            val current = settings.currentHorizon()
            val updated = transform(current)
            settings.saveHorizon(updated)
            if (recompute || (updated.enabled && !current.enabled)) {
                // The range changes the skyline, so a stored profile computed with the
                // old values is not a cache hit — it is the wrong answer under the right file name.
                horizonLocationProvider.refreshLocation()
                if (horizonLocationProvider.hasGpsFix.value) {
                    if (recompute) horizonRepository.invalidate()
                    horizonRepository.ensureProfile(horizonLocationProvider.location.value, updated)
                } else if (updated.enabled && updated.networkDownloadAllowed) {
                    downloadTerrainForCurrentLocation(updated)
                }
            }
        }
    }

    fun setHorizonEnabled(enabled: Boolean) = editHorizon { it.copy(enabled = enabled) }

    fun setHorizonAutoRange(automatic: Boolean) = editHorizon(recompute = true) { it.copy(autoRange = automatic) }

    fun setHorizonRangeKm(rangeKm: Int) = editHorizon(recompute = true) { it.copy(maxRangeKm = rangeKm) }

    /**
     * Automatic or on demand, for a user who has already consented; Never goes through
     * [setHorizonNetworkConsent]. Changing it starts nothing: the next position or screen entry runs
     * through the gate with the new mode.
     */
    fun setHorizonAutoDownload(automatic: Boolean) = editHorizon { it.copy(autoDownload = automatic) }

    fun setHorizonConfirmLimitMb(megabytes: Int) = editHorizon {
        it.copy(confirmAboveMb = megabytes.takeIf { mb -> mb in HorizonSettings.CONFIRM_LIMIT_CHOICES_MB } ?: it.confirmAboveMb)
    }

    /** The user tapped Download on a run waiting for confirmation. */
    fun confirmTerrainDownload() = horizonRepository.confirmDownload()

    fun setHorizonShadeOccluded(enabled: Boolean) = editHorizon { it.copy(shadeOccludedTrajectory = enabled) }

    fun setHorizonWifiOnly(enabled: Boolean) = editHorizon { it.copy(wifiOnly = enabled) }

    fun setHorizonNetworkConsent(granted: Boolean) = editHorizon { it.copy(networkConsent = granted) }

    /**
     * @param autoDownload the mode the user picked before the consent dialog (Automatic or On demand
     *   in Settings), applied in the same write; null keeps the stored one.
     */
    fun answerHorizonConsent(granted: Boolean, wifiOnly: Boolean? = null, autoDownload: Boolean? = null) {
        viewModelScope.launch {
            val current = settings.currentHorizon()
            val updated = current.copy(
                networkConsent = granted,
                enabled = true,
                wifiOnly = if (wifiOnly != null) wifiOnly else current.wifiOnly,
                autoDownload = autoDownload ?: current.autoDownload,
            )
            settings.saveHorizon(updated)
            if (granted) {
                downloadTerrainForCurrentLocation(updated)
            }
        }
    }

    fun setRealHorizonInNightSky(enabled: Boolean) = editHorizon { it.copy(realHorizonInNightSky = enabled) }

    /**
     * Fetch or recompute for wherever the phone currently thinks it is.
     *
     * @param userConfirmed the call is the Download button itself, so the run is confirmed whatever its
     *   size; from a consent answer it is not, and goes through the mode and the limit.
     */
    fun downloadTerrainForCurrentLocation(explicitSettings: HorizonSettings? = null, userConfirmed: Boolean = false) {
        horizonLocationProvider.refreshLocation()
        viewModelScope.launch {
            val current = explicitSettings ?: settings.currentHorizon()
            val updated = current.copy(enabled = true)
            settings.saveHorizon(updated)
            horizonRepository.invalidate()
            if (horizonLocationProvider.hasGpsFix.value) {
                horizonRepository.ensureProfile(horizonLocationProvider.location.value, updated, userConfirmed)
            } else {
                horizonRepository.setLocating(horizonLocationProvider.satellitesAvailable())
                val hasFix = horizonLocationProvider.awaitGpsFix()
                if (hasFix) {
                    horizonRepository.ensureProfile(
                        horizonLocationProvider.location.value,
                        updated,
                        userConfirmed,
                    )
                } else {
                    horizonRepository.setLocationFailed()
                }
            }
        }
    }

    fun importTerrainTile(uri: android.net.Uri, onResult: (DemTileImporter.Result) -> Unit) {
        viewModelScope.launch {
            val result = horizonRepository.importTile(uri)
            if (result is DemTileImporter.Result.Imported) {
                horizonRepository.ensureProfile(
                    horizonLocationProvider.location.value,
                    settings.currentHorizon(),
                )
            }
            onResult(result)
        }
    }

    /** Tiles the current radius needs that are not on disk — what the import hint lists. */
    fun missingTerrainTiles(): List<DemTileKey> = horizonRepository.missingTilesFor(
        horizonLocationProvider.location.value,
        horizonSettings.value,
    )

    fun cachedTerrainBytes(): Long = horizonRepository.cachedTileBytes()

    fun refreshStorageMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            _storageMetrics.value = horizonRepository.getStorageMetrics()
        }
    }

    /** Clears all cached DEM tiles and refreshes storage metrics. */
    fun clearDemCache() {
        viewModelScope.launch(Dispatchers.IO) {
            horizonRepository.clearDemCache()
            _storageMetrics.value = horizonRepository.getStorageMetrics()
        }
    }

    fun clearTerrainCache() = clearDemCache()

    // ==== Offline terrain region management ====

    val offlineRegions: StateFlow<List<OfflineRegion>> =
        horizonRepository.offlineRegionRepository.offlineRegions

    val offlineDownloadState: StateFlow<OfflineDownloadState> =
        horizonRepository.offlineDownloader.state

    private var offlineDownloadJob: Job? = null

    fun estimateOfflineRegion(lat: Double, lon: Double, radiusKm: Int): DownloadEstimate =
        horizonRepository.offlineDownloader.estimate(lat, lon, radiusKm)

    fun downloadOfflineRegion(name: String, lat: Double, lon: Double, radiusKm: Int) {
        offlineDownloadJob?.cancel()
        offlineDownloadJob = viewModelScope.launch {
            horizonRepository.offlineDownloader.downloadRegion(name, lat, lon, radiusKm)
            refreshStorageMetrics()
        }
    }

    fun cancelOfflineDownload() {
        offlineDownloadJob?.cancel()
        offlineDownloadJob = null
        horizonRepository.offlineDownloader.resetState()
    }

    fun resetOfflineDownloadState() {
        horizonRepository.offlineDownloader.resetState()
    }

    fun deleteOfflineRegion(regionId: String) {
        viewModelScope.launch {
            horizonRepository.offlineRegionRepository.deleteRegion(regionId)
            refreshStorageMetrics()
        }
    }

    // ==== A place handed over by another app (share sheet, geo: link) ====
    //
    // Read from the text alone and never resolved over the network: a shortened link is reported as
    // such, not followed. Nothing here is logged — it is a position.

    private val savedPlaces = SavedPlaceRepository.getInstance(application)

    private val incomingPlaceState = MutableStateFlow<IncomingPlace?>(null)

    /** What the last share or geo: link carried, until the user has decided what to do with it. */
    val incomingPlace: StateFlow<IncomingPlace?> = incomingPlaceState.asStateFlow()

    fun receiveSharedPlace(text: String?, subject: String?) {
        incomingPlaceState.value = IncomingPlaceParser.parse(text, subject)
    }

    fun dismissIncomingPlace() {
        incomingPlaceState.value = null
    }

    /** Saved only; which screen shows it stays the user's choice under the map button. */
    fun saveIncomingPlace(place: SavedPlace) {
        incomingPlaceState.value = null
        viewModelScope.launch { savedPlaces.save(place) }
    }

    private val offlineTerrainPrefillState = MutableStateFlow<OfflineTerrainPrefill?>(null)

    /** A region the Offline terrain form should open with, cleared once the form has taken it. */
    val offlineTerrainPrefill: StateFlow<OfflineTerrainPrefill?> = offlineTerrainPrefillState.asStateFlow()

    /** The handed-over place as a new offline region: the form, filled in, with the radius left to the user. */
    fun prefetchIncomingPlace(name: String, location: GeoLocation) {
        incomingPlaceState.value = null
        offlineTerrainPrefillState.value = OfflineTerrainPrefill(name, location)
        navigate(Screen.OFFLINE_TERRAIN)
    }

    fun offlineTerrainPrefillConsumed() {
        offlineTerrainPrefillState.value = null
    }

    /** Clears stored horizon profiles and refreshes storage metrics. */
    fun clearProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            horizonRepository.clearProfiles()
            _storageMetrics.value = horizonRepository.getStorageMetrics()
        }
    }

    fun updateAdvanced(transform: (AdvancedSettings) -> AdvancedSettings) {
        val previous = advancedState.value
        val updated = transform(previous)
        advancedState.value = updated
        val application = getApplication<Application>()
        if (updated.keepScreenOn != previous.keepScreenOn) {
            OverlaySettings.setKeepScreenOn(application, updated.keepScreenOn)
        }
        if (updated.dimOverlay != previous.dimOverlay) {
            OverlaySettings.setDimPanel(application, updated.dimOverlay)
        }
        viewModelScope.launch { settings.saveAdvanced(updated) }
    }

    /** Keeps the advanced screen in step with a toggle flipped on the floating panel itself. */
    private suspend fun observeOverlayOptions() {
        OverlaySettings.options(getApplication()).collect { options ->
            advancedState.value = advancedState.value.withOverlayOptions(options)
        }
    }

    fun selectApp(entry: AppEntry) {
        updateSetup { it.copy(targetPackage = entry.packageName, targetLabel = entry.label) }
        back()
    }

    fun clearApp() {
        updateSetup { it.copy(targetPackage = null, targetLabel = null) }
    }

    // ---- recorded camera modes -----------------------------------------------------------------

    /** Every recorded mode, for every app. */
    val customModes: StateFlow<List<CustomCameraMode>> =
        settings.customModes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val modeDraftState = MutableStateFlow(CustomModeDraft())
    val modeDraft: StateFlow<CustomModeDraft> = modeDraftState.asStateFlow()

    /** The recording already taken over, so a recreated screen does not apply it twice. */
    private var lastRecordingSerial = ModeRecordingOverlay.lastResult.value?.serial ?: 0L

    // Here and not in the first init block: the state it writes is declared above, after that block.
    init {
        viewModelScope.launch { observeModeRecordings() }
    }

    fun openCustomModes() = navigate(Screen.CUSTOM_MODES)

    /** A new mode starts in the session's camera app, which is what the user is setting up. */
    fun newCustomMode() {
        val setup = setupState.value
        modeDraftState.value = CustomModeDraft(packageName = setup.targetPackage, appLabel = setup.targetLabel)
        navigate(Screen.CUSTOM_MODE_EDIT)
    }

    fun editCustomMode(mode: CustomCameraMode) {
        modeDraftState.value = CustomModeDraft.from(mode)
        navigate(Screen.CUSTOM_MODE_EDIT)
    }

    fun updateModeDraft(transform: (CustomModeDraft) -> CustomModeDraft) {
        modeDraftState.value = transform(modeDraftState.value)
    }

    fun saveModeDraft() {
        val mode = modeDraftState.value.toMode({ UUID.randomUUID().toString().replace("-", "").take(12) }, System.currentTimeMillis())
            ?: return
        viewModelScope.launch { settings.saveCustomMode(mode) }
        back()
    }

    fun deleteModeDraft() {
        val id = modeDraftState.value.id
        if (id != null) {
            // The store clears a launch mode pointing at it; the screen's copy has to follow.
            if (CustomModeKey.idOf(advancedState.value.launchCameraMode) == id) {
                advancedState.value = advancedState.value.copy(launchCameraMode = null)
            }
            viewModelScope.launch { settings.deleteCustomMode(id) }
        }
        back()
    }

    /** Replays the draft as it stands now; [onResult] gets null when the app did not open. */
    fun testModeDraft(onResult: (CustomReplayResult?) -> Unit): Boolean {
        val mode = modeDraftState.value.toMode({ "test" }, System.currentTimeMillis()) ?: return false
        return ShutterAccessibilityService.testCustomMode(mode, onResult)
    }

    private suspend fun observeModeRecordings() {
        ModeRecordingOverlay.lastResult.collect { recording ->
            if (recording == null || recording.serial <= lastRecordingSerial) return@collect
            lastRecordingSerial = recording.serial
            modeDraftState.value = modeDraftState.value.withRecording(recording)
        }
    }

    fun setAppQuery(query: String) {
        queryState.value = query
    }

    /**
     * Spreads each tap over the calibrated area instead of always hitting its centre. Only an area
     * calibration has something to spread over, so a point target ignores it. Written back to the
     * calibration slot as well, otherwise the next resume would read the stored `false` back over it.
     */
    fun setTargetJitter(enabled: Boolean) {
        val target = setupState.value.target ?: return
        if (!target.isRect) return
        val updated = target.copy(jitter = enabled)
        updateSetup { it.copy(target = updated) }
        calibrationState.value = updated
        val packageName = setupState.value.targetPackage ?: return
        runCalibrationTask { settings.saveTarget(packageName, calibrationSlot(), updated) }
    }

    /**
     * The calibration overlay just committed a target. It is applied in memory before the store is
     * touched: the overlay persists on its own coroutine, so re-reading the store here could still
     * answer "nothing stored" and the user would be told the calibration they just made did not
     * happen. A result for a package the user has meanwhile moved away from is dropped.
     */
    fun onCalibrated(packageName: String?, rotation: Int, target: TapTarget) {
        if (packageName != setupState.value.targetPackage) return
        runCalibrationTask {
            if (packageName != setupState.value.targetPackage) return@runCalibrationTask
            setupState.value = setupState.value.copy(target = target)
            calibrationState.value = target
            if (packageName != null) settings.saveTarget(packageName, rotation, target)
            val state = setupState.value
            settings.saveConfig(state.toConfig(floorFor(state)))
        }
    }

    /**
     * Persists the config first, then hands it to the live service. A `false` answer means the
     * accessibility service is not enabled, which the home screen turns into a deep link.
     */
    fun start() {
        val state = setupState.value
        val config = state.toConfig(floorFor(state))
        viewModelScope.launch {
            settings.saveConfig(config)
            val started = ShutterAccessibilityService.startSession(config, advancedState.value)
            serviceUnavailableState.value = !started
        }
    }

    /** Stopping from the UI is always [StopReason.USER]; the service records it as such. */
    fun stop() {
        if (!ShutterAccessibilityService.stopSession()) {
            serviceUnavailableState.value = true
        }
    }

    /**
     * Holds the session without ending it. The same call the notification and the overlay make, so a
     * session can always be paused from whichever of the three the user can actually reach — with
     * notifications blocked and no overlay, the home screen is the only one left.
     */
    fun pause() {
        if (!ShutterAccessibilityService.pauseSession()) {
            serviceUnavailableState.value = true
        }
    }

    /** Counterpart of [pause]; also the way out of a session that paused itself. */
    fun resume() {
        if (!ShutterAccessibilityService.resumeSession()) {
            serviceUnavailableState.value = true
        }
    }

    fun dismissServiceMessage() {
        serviceUnavailableState.value = false
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(400)
            val state = setupState.value
            settings.saveConfig(state.toConfig(floorFor(state)))
        }
    }

    /**
     * Calibrations are stored per display rotation, which is also what
     * [com.dazix.caelis.service.ShutterAccessibilityService] reads them back by — a
     * `Configuration.orientation` here would write into a slot nothing ever looks in.
     */
    private fun calibrationSlot(): Int =
        DisplayGeometry.currentRotation(getApplication<Application>())

    /** Queues calibration work behind whatever is already reading or writing a calibration. */
    private fun runCalibrationTask(block: suspend () -> Unit) {
        val previous = calibrationJob
        calibrationJob = viewModelScope.launch {
            previous?.join()
            block()
        }
    }

    private fun reloadCalibration() = runCalibrationTask { refreshCalibration() }

    /**
     * Re-reads the calibration for the package that is chosen *now*. Strictly per package: a package
     * with nothing stored is reported as not calibrated, which is what blocks Start. The rotation
     * fallback lives in [com.dazix.caelis.data.SettingsStore.anyTarget], which is also what
     * [com.dazix.caelis.service.SessionEngine.loadStoredTarget] reads, so the screen never
     * disagrees with the target the session would actually tap — in particular after a rotation,
     * where this used to report the calibration the user had just made as missing and then persist
     * that miss over it.
     */
    private suspend fun refreshCalibration() {
        val packageName = setupState.value.targetPackage
        // A store that could not be read is not a package without a calibration; the two are kept
        // apart so a failed read leaves the screen (and the config behind it) exactly as it was.
        val read = packageName?.let { runCatching { settings.anyTarget(it, calibrationSlot()) } }
        if (setupState.value.targetPackage != packageName) return // a newer selection already won
        val resolved = CalibrationResolution.resolve(
            targetPackage = packageName,
            stored = read?.getOrNull(),
            inMemory = setupState.value.target,
            storeReadable = read == null || read.isSuccess,
        )
        calibrationState.value = resolved
        if (resolved != setupState.value.target) {
            setupState.value = setupState.value.copy(target = resolved)
            schedulePersist()
        }
    }

    /**
     * The shortest interval this setup would actually be allowed to run at. Asked of
     * [CameraProfiles.effectiveFloorMs] rather than of the camera profile directly, so a periodic tap
     * on a non-camera app is not held to a camera app's floor — the same rule the engine applies.
     */
    private fun floorFor(state: SetupUiState): Long =
        CameraProfiles.effectiveFloorMs(state.triggerMode(), 1L).coerceAtLeast(1L)

    private fun labelFor(packageName: String?): String? {
        if (packageName == null) return null
        val manager = getApplication<Application>().packageManager
        return runCatching {
            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    private suspend fun loadInstalledApps() {
        val manager = getApplication<Application>().packageManager
        val entries = withContext(Dispatchers.Default) {
            val cameraPackages = buildSet {
                addAll(resolvePackages(manager, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
                addAll(resolvePackages(manager, Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
            }
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            manager.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .asSequence()
                .mapNotNull { info -> info.activityInfo?.packageName }
                .distinct()
                .map { packageName ->
                    AppEntry(
                        packageName = packageName,
                        label = runCatching {
                            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0))
                                .toString()
                        }.getOrDefault(packageName),
                        isCamera = packageName in cameraPackages,
                    )
                }
                .sortedWith(
                    compareByDescending<AppEntry> { it.isCamera }
                        .thenBy { it.label.lowercase() }
                )
                .toList()
        }
        allApps.value = entries
        appsLoadingState.value = false
        val current = setupState.value
        if (current.targetPackage != null && current.targetLabel == null) {
            val label = entries.firstOrNull { it.packageName == current.targetPackage }?.label
            if (label != null) setupState.value = current.copy(targetLabel = label)
        }
    }

    private fun resolvePackages(manager: PackageManager, intent: Intent): List<String> =
        runCatching {
            manager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { info: ResolveInfo -> info.activityInfo?.packageName }
        }.getOrDefault(emptyList())
}

/**
 * The panel's live options seen as the two [AdvancedSettings] fields that mirror them. The panel is
 * what actually holds the screen awake and dims itself, so its store is the truthful answer for
 * these two and the advanced screen shows that rather than a stored value nothing is obeying.
 */
private fun AdvancedSettings.withOverlayOptions(options: OverlayOptions): AdvancedSettings =
    copy(keepScreenOn = options.keepScreenOn, dimOverlay = options.dimPanel)

private fun String.toPositiveDoubleOrNull(): Double? =
    replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0.0 }
