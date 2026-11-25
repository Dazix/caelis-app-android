package com.dazix.caelis.docs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.CelestialType
import com.dazix.caelis.domain.nightsky.NightVisionActivationMode
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.SearchResultItem
import com.dazix.caelis.domain.solar.BubbleInterval
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.domain.solar.LunarTrajectoryPlanner
import com.dazix.caelis.domain.solar.SolarTrajectoryPlanner
import com.dazix.caelis.service.CameraProfiles
import com.dazix.caelis.ui.AdvancedScreen
import com.dazix.caelis.ui.AppNavigationShell
import com.dazix.caelis.ui.CaelisTheme
import com.dazix.caelis.ui.HomeScreen
import com.dazix.caelis.ui.ModeChoice
import com.dazix.caelis.ui.OnboardingScreen
import com.dazix.caelis.ui.PermissionState
import com.dazix.caelis.ui.RampScreen
import com.dazix.caelis.ui.Screen
import com.dazix.caelis.ui.SettingsScreen
import com.dazix.caelis.ui.SetupScreen
import com.dazix.caelis.ui.SetupUiState
import com.dazix.caelis.ui.TimeUnitChoice
import com.dazix.caelis.ui.nightsky.CelestialDetailSheetContent
import com.dazix.caelis.ui.nightsky.CelestialSearchBar
import com.dazix.caelis.ui.nightsky.NightSkyBottomHud
import com.dazix.caelis.ui.nightsky.NightSkyLayerSettingsSheet
import com.dazix.caelis.ui.nightsky.NightSkyTopBar
import androidx.compose.runtime.remember
import com.dazix.caelis.ui.solar.SunArSettingsSheet
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi", sdk = [34])
class DocumentationScreenshotsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun screenshotPath(fileName: String): String {
        val rootDir = if (File("app").exists() && File("docs").exists()) {
            File(".")
        } else if (File("../docs").exists() || File("../app").exists()) {
            File("..")
        } else {
            File(".")
        }
        val targetDir = File(rootDir, "docs/screenshots").apply { mkdirs() }
        return File(targetDir, fileName).absolutePath
    }

    private val defaultSetupState = SetupUiState(
        mode = ModeChoice.TIMELAPSE,
        targetPackage = "com.google.android.GoogleCamera",
        targetLabel = "Pixel Camera",
        intervalValue = "5",
        intervalUnit = TimeUnitChoice.SECONDS,
        shotCount = "120",
        unlimitedShots = false,
        startDelayValue = "3",
        startDelayUnit = TimeUnitChoice.SECONDS,
    )

    private val northPortraitMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f,
    )

    private val sampleOrientation = DeviceOrientation(
        rotationMatrix = northPortraitMatrix,
        cameraAzimuthDegrees = 180f,
        cameraElevationDegrees = 25f,
        cameraRollDegrees = 0f,
        accuracy = 3,
    )

    private val pragueLocation = GeoLocation(latitude = 50.0755, longitude = 14.4378)
    private val solsticeDate = LocalDate.of(2026, 6, 21)

    private val solarTrajectory = SolarTrajectoryPlanner.planTrajectory(
        date = solsticeDate,
        location = pragueLocation,
        bubbleInterval = BubbleInterval.ONE_HOUR,
        zoneId = ZoneId.of("Europe/Prague"),
    )

    private val lunarTrajectory = LunarTrajectoryPlanner.planTrajectory(
        date = solsticeDate,
        location = pragueLocation,
        bubbleInterval = BubbleInterval.ONE_HOUR,
        zoneId = ZoneId.of("Europe/Prague"),
    )

    private val siriusDetails = CelestialBodyDetails(
        name = "Sirius (α CMa)",
        subtitle = "Main Sequence Star (A1V)",
        property1 = "Constellation" to "Canis Major",
        property2 = "Distance" to "8.6 ly",
        magnitude = -1.46f,
        raDegrees = 101.287,
        decDegrees = -16.716,
    )

    // =========================================================================
    // 1. Core Intervalometer & Settings Screens
    // =========================================================================

    @Test
    fun capture01_MainScreenIdleLight() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = false) {
                AppNavigationShell(current = Screen.HOME, onNavigate = {}, enabled = true) {
                    HomeScreen(
                        snapshot = SessionSnapshot(state = SessionState.Idle),
                        setup = defaultSetupState,
                        calibrated = true,
                        blockers = emptyList(),
                        serviceUnavailable = false,
                        handheldWarning = true,
                        onStart = {},
                        onStop = {},
                        onPause = {},
                        onResume = {},
                        onNavigate = {},
                        onCalibrate = {},
                        onOpenAccessibilitySettings = {},
                        onOpenOverlaySettings = {},
                        onDismissServiceMessage = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("01_main_idle_light.png"))
    }

    @Test
    fun capture02_MainScreenIdleDark() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                AppNavigationShell(current = Screen.HOME, onNavigate = {}, enabled = true) {
                    HomeScreen(
                        snapshot = SessionSnapshot(state = SessionState.Idle),
                        setup = defaultSetupState,
                        calibrated = true,
                        blockers = emptyList(),
                        serviceUnavailable = false,
                        handheldWarning = true,
                        onStart = {},
                        onStop = {},
                        onPause = {},
                        onResume = {},
                        onNavigate = {},
                        onCalibrate = {},
                        onOpenAccessibilitySettings = {},
                        onOpenOverlaySettings = {},
                        onDismissServiceMessage = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("02_main_idle_dark.png"))
    }

    @Test
    fun capture03_MainScreenActiveDark() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                AppNavigationShell(current = Screen.HOME, onNavigate = {}, enabled = true) {
                    HomeScreen(
                        snapshot = SessionSnapshot(
                            state = SessionState.Running,
                            shotsTaken = 24,
                            shotsPlanned = 120,
                            lastIntervalMs = 5000L,
                            nextShotAtElapsed = 3500L,
                        ),
                        setup = defaultSetupState,
                        calibrated = true,
                        blockers = emptyList(),
                        serviceUnavailable = false,
                        handheldWarning = true,
                        onStart = {},
                        onStop = {},
                        onPause = {},
                        onResume = {},
                        onNavigate = {},
                        onCalibrate = {},
                        onOpenAccessibilitySettings = {},
                        onOpenOverlaySettings = {},
                        onDismissServiceMessage = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("03_main_active_dark.png"))
    }

    @Test
    fun capture04_SetupScreen() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                SetupScreen(
                    state = defaultSetupState.copy(
                        minBatteryEnabled = true,
                        minBatteryPercent = "20",
                        minFreeSpaceEnabled = true,
                        minFreeSpaceMb = "1000",
                    ),
                    floorMs = 1000L,
                    mediaPermissionGranted = true,
                    mediaPermissionPartial = false,
                    onChange = {},
                    onJitterChange = {},
                    onRequestMediaPermission = {},
                    onOpenAppSettings = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("04_setup_screen.png"))
    }

    @Test
    fun capture05_RampScreen() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                RampScreen(
                    state = defaultSetupState.copy(
                        rampEnabled = true,
                        rampEndValue = "30",
                        rampEndUnit = TimeUnitChoice.SECONDS,
                        rampOverShots = "100",
                    ),
                    floorMs = 1000L,
                    onChange = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("05_ramp_screen.png"))
    }

    @Test
    fun capture06_AdvancedScreen() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                AdvancedScreen(
                    settings = AdvancedSettings(
                        launchCameraMode = "NIGHT_SIGHT",
                        intervalAfterCapture = true,
                        keepScreenOn = true,
                        dimOverlay = false,
                    ),
                    profile = CameraProfiles.forTrigger(TriggerMode.Timelapse("com.google.android.GoogleCamera")),
                    targetLabel = "Pixel Camera",
                    overlayAllowed = true,
                    mediaPermissionGranted = true,
                    mediaPermissionPartial = false,
                    onChange = {},
                    onRequestMediaPermission = {},
                    onOpenAppSettings = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("06_advanced_screen.png"))
    }

    @Test
    fun capture07_SettingsScreen() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                AppNavigationShell(current = Screen.SETTINGS, onNavigate = {}, enabled = true) {
                    SettingsScreen(
                        theme = NightVisionTheme.AMBER,
                        activationMode = NightVisionActivationMode.CUSTOM_TIME,
                        startTime = LocalTime.of(21, 30),
                        endTime = LocalTime.of(5, 30),
                        debugPreferences = com.dazix.caelis.data.DebugPreferences(),
                        storageMetrics = com.dazix.caelis.data.horizon.StorageMetrics(
                            demCacheSizeBytes = 145L * 1024L * 1024L,
                            profilesSizeBytes = 116L * 1024L,
                            profileCount = 20,
                        ),
                        onThemeChange = {},
                        onActivationModeChange = {},
                        onStartTimeChange = {},
                        onEndTimeChange = {},
                        onDebugPreferencesChange = {},
                        onClearDemCache = {},
                        onBack = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("07_settings_screen.png"))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun capture08_DialogTimePicker() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                val timeState = rememberTimePickerState(
                    initialHour = 21,
                    initialMinute = 30,
                    is24Hour = true,
                )
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {
                        TextButton(onClick = {}) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = {}) { Text("Cancel") }
                    },
                    text = {
                        TimePicker(state = timeState)
                    },
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("08_dialog_time_picker.png"))
    }

    // =========================================================================
    // 2. Sun & Moon AR Trajectory Screens
    // =========================================================================

    @Test
    fun capture10_SunArDaySummary() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F141C))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SunArSettingsSheet(
                        solarTrajectory = solarTrajectory,
                        lunarTrajectory = lunarTrajectory,
                        keepScreenOn = true,
                        isCalibrated = true,
                        onToggleKeepScreenOn = {},
                        onResetCalibration = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("10_sun_ar_day_summary.png"))
    }

    // =========================================================================
    // 3. Night Sky AR & Astrophotography Screens
    // =========================================================================

    @Test
    fun capture11_NightSkyArView() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF06090E))
                ) {
                    // Top Compass & Location HUD
                    NightSkyTopBar(
                        hasGpsFix = true,
                        locationText = "50.08° N, 14.44° E",
                        orientation = sampleOrientation,
                        cameraFovHorizontal = 56f,
                        zoomRatio = 1.0f,
                        isCalibrated = true,
                        redVisionMode = true,
                        nightVisionTheme = NightVisionTheme.AMBER,
                        onBack = {},
                        onOpenSearch = {},
                        onOpenLayers = {},
                        onResetZoom = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(top = 36.dp, start = 12.dp, end = 12.dp),
                    )

                    // Bottom LST & Star Count HUD
                    NightSkyBottomHud(
                        lstFormatted = "14:32:10",
                        visibleStarCount = 4820,
                        redVisionMode = true,
                        nightVisionTheme = NightVisionTheme.AMBER,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 36.dp),
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("11_night_sky_ar_view.png"))
    }

    @Test
    fun capture12_NightSkySearch() {
        val searchResults = listOf(
            SearchResultItem(
                indexId = 0,
                primaryName = "Sirius (α CMa)",
                secondaryName = "Dog Star · Canis Major",
                type = CelestialType.STAR,
                ra = 101.287f,
                dec = -16.716f,
                magnitude = -1.46f,
            ),
            SearchResultItem(
                indexId = 1,
                primaryName = "Canis Major",
                secondaryName = "Velký pes · Constellation",
                type = CelestialType.CONSTELLATION,
                ra = 105.0f,
                dec = -22.0f,
            ),
            SearchResultItem(
                indexId = 2,
                primaryName = "Betelgeuse (α Ori)",
                secondaryName = "Red Supergiant · Orion",
                type = CelestialType.STAR,
                ra = 88.79f,
                dec = 7.407f,
                magnitude = 0.50f,
            ),
        )

        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF000000))
                        .padding(top = 24.dp)
                ) {
                    CelestialSearchBar(
                        query = "Siri",
                        results = searchResults,
                        active = true,
                        onQueryChange = {},
                        onActiveChange = {},
                        onSelectItem = {},
                        redVisionMode = true,
                        nightVisionTheme = NightVisionTheme.AMBER,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("12_night_sky_search.png"))
    }

    @Test
    fun capture13_NightSkyDetailSheet() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF050505))
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CelestialDetailSheetContent(
                        selectedBody = siriusDetails,
                        redVisionMode = true,
                        nightVisionTheme = NightVisionTheme.AMBER,
                        onHeaderMeasured = {},
                        onRouteToTarget = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("13_night_sky_detail_sheet.png"))
    }

    @Test
    fun capture14_NightSkyLayerSettings() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF101010),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    NightSkyLayerSettingsSheet(
                        isLandscapeVisible = true,
                        isAtmosphereVisible = true,
                        isMilkyWayVisible = true,
                        isPlanetsVisible = true,
                        isConstellationsVisible = true,
                        isConstellationArtVisible = true,
                        isGridVisible = true,
                        isSkyBackgroundOpaque = false,
                        redVisionMode = true,
                        nightVisionTheme = NightVisionTheme.AMBER,
                        isSensorTrackingEnabled = true,
                        isKeepScreenOn = true,
                        isDebugFocusRegionVisible = false,
                        isDebugOptionsEnabled = false,
                        onToggleLandscapeVisible = {},
                        onToggleAtmosphereVisible = {},
                        onToggleMilkyWayVisible = {},
                        onTogglePlanetsVisible = {},
                        onToggleConstellationsVisible = {},
                        onToggleConstellationArtVisible = {},
                        onToggleGridVisible = {},
                        onToggleSkyBackgroundOpaque = {},
                        onToggleRedVisionMode = {},
                        onToggleSensorTracking = {},
                        onToggleKeepScreenOn = {},
                        onToggleDebugFocusRegion = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("14_night_sky_layer_settings.png"))
    }

    // =========================================================================
    // 4. Accessibility & Overlay Layers
    // =========================================================================

    @Test
    fun capture15_OverlayFloatingPanel() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                // Simulated Camera Viewfinder Viewport
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF14181F))
                ) {
                    // Viewfinder grid lines
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(2) {
                            HorizontalDivider(color = Color(0x33FFFFFF), thickness = 0.8.dp)
                        }
                    }

                    // Shutter button circle at bottom center
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .size(72.dp)
                            .border(3.dp, Color.White, CircleShape)
                            .padding(6.dp)
                            .background(Color.White, CircleShape)
                    )

                    // Floating Accessibility Overlay Panel Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xE61C1B1F),
                        border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 64.dp, start = 20.dp)
                            .size(width = 220.dp, height = 180.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Status / Shots count
                            Text(
                                text = "38 / 100",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )

                            // Countdown Timer
                            Text(
                                text = "00:04",
                                color = Color(0xFF4DD0E1),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )

                            // Primary Controls Row (Pause / Stop)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Button(
                                    onClick = {},
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF32353A),
                                        contentColor = Color.White,
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                ) {
                                    Text("Pause", fontSize = 11.sp, maxLines = 1)
                                }
                                Button(
                                    onClick = {},
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFB3261E),
                                        contentColor = Color.White,
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                ) {
                                    Text("Stop", fontSize = 11.sp, maxLines = 1)
                                }
                            }

                            // Toggles Row (Screen / Dim)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = {},
                                    shape = CircleShape,
                                    border = BorderStroke(0.8.dp, Color(0x66FFFFFF)),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp),
                                ) {
                                    Text("Screen: On", fontSize = 9.sp, maxLines = 1, color = Color.White)
                                }
                                OutlinedButton(
                                    onClick = {},
                                    shape = CircleShape,
                                    border = BorderStroke(0.8.dp, Color(0x66FFFFFF)),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp),
                                ) {
                                    Text("Dim", fontSize = 9.sp, maxLines = 1, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("15_overlay_floating_panel.png"))
    }

    @Test
    fun capture16_OverlayCalibrationTarget() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                ) {
                    // Top Instruction Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xF01C1B1F),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Klepnutím nebo tažením označte spoušť fotoaparátu Pixel Camera",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = {}) {
                                    Text("Zrušit", color = Color(0xFF4DD0E1))
                                }
                            }
                        }
                    }

                    // Designated Crosshair Target Area over Shutter Button
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 54.dp)
                            .size(80.dp)
                            .border(2.dp, Color(0xFF00E5FF), CircleShape)
                            .background(Color(0x2200E5FF), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(0xFF00E5FF), CircleShape)
                        )
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("16_overlay_calibration_target.png"))
    }

    @Test
    fun capture17_OnboardingPermissions() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                OnboardingScreen(
                    permissions = PermissionState(
                        accessibilityEnabled = true,
                        canDrawOverlays = true,
                        notificationsGranted = true,
                        ignoringBatteryOptimizations = true,
                        exactAlarmsAllowed = true,
                        readMediaImagesGranted = true,
                        readMediaPartialGranted = false,
                    ),
                    onOpenAppInfo = {},
                    onOpenAccessibilitySettings = {},
                    onOpenOverlaySettings = {},
                    onRequestNotifications = {},
                    onRequestBatteryExemption = {},
                    onOpenExactAlarmSettings = {},
                    onRefresh = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(screenshotPath("17_onboarding_permissions.png"))
    }
}
