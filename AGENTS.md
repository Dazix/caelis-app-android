# Working in this repository

Android intervalometer. It presses the shutter inside another app through an
AccessibilityService. Read `README.md` first for what the app is (and `docs/DEVELOPMENT.md` for the build, release and architecture detail); this file is how to work on it
without breaking it.

## Non-negotiables

1. **The attached phone is a person's daily driver, not a lab device.** Every setting you change over
   adb must be snapshotted before and restored after — including when the run fails halfway, so the
   restore belongs in a `trap`/`finally`. Use `scripts/a11y_setup.sh` + `scripts/a11y_teardown.sh`
   rather than hand-rolling `settings put`.
2. **All device work happens in the dedicated development user, never the phone's primary profile.**
   Its id is `ANDROID_USER_ID` from `scripts/env.local.sh`. Pass `--user "$ANDROID_USER_ID"` on every
   adb command that takes it, and assert `adb shell am get-current-user` returns that id before
   starting instead of assuming. Install with `adb install -r --user "$ANDROID_USER_ID" …`, never
   `:app:installDebug` — that installs for every user.
3. **Delete the photos a test run created.** Retry the deletes: a frame the camera has not finished
   writing refuses a shell delete for several seconds and is invisible to `content query` while
   pending, so "the query no longer lists it" is not proof it is gone.
4. **Never weaken a test to get green.** No `@Ignore`, no deleted assertions, no lint baselines, no
   `tools:ignore` to silence a warning. If a test is wrong, fix it to be *stricter* and say why.
5. **Evidence before claims.** Do not say something passes without having run it and seen the output.
   Gradle exits 0 on skipped tests — read the result XML.
6. **No tests during planning or documentation.** When planning (`/plan`), designing implementation
   plans, answering questions, or creating/editing Markdown and documentation files, NEVER run unit tests,
   instrumented tests, or build commands. Test suites belong strictly in the verification step AFTER code implementation.
7. **Commit only on explicit user request.** Never run git commit proactively or automatically. Commits
   must be performed strictly when explicitly requested by the user.
8. **Every commit must include Co-authored-by for the active agent.** When creating a commit upon
   explicit user request, always include the trailer `Co-authored-by: <AgentName> <agent-email>` (e.g.
   `Co-authored-by: Antigravity <antigravity@google.com>`) at the end of the commit message body.

## Toolchain

Nothing Android is on `PATH`. The paths live in `scripts/env.local.sh` (gitignored; template in
`scripts/env.example.sh`), which is where `ADB`, `JAVA_HOME` and `ANDROID_USER_ID` come from:

```bash
. scripts/env.local.sh          # ADB, JAVA_HOME, ANDROID_USER_ID for this machine
JAVA_HOME="$JAVA_HOME" ./gradlew <tasks>
"$ADB" …
```

The `scripts/*.sh` harness sources `scripts/env.sh` itself, so it needs none of that done by hand —
and it aborts rather than guessing when a value is missing.

AGP 9.3.1 · Gradle 9.5.0 · Kotlin 2.3.20 · compileSdk 37 (`compileSdkMinor = 1`, platform
`android-37.1`) · minSdk 30 · targetSdk 37 · JVM target 17.

Filament 1.77.1 (raw, no SceneView) draws both AR skies and the camera picture. `com.google.ar:core`
1.56.0 is a dependency, but nothing in the app starts it unless the user has turned ARCore on in
Settings (see the consent invariant below); a Geospatial API key, if you have one, goes in
`local.properties` as `geospatial.api.key` or in `CAELIS_GEOSPATIAL_API_KEY`, and a build without one is
a fully working build.

Two AGP 9 facts that each cost a build cycle: the `org.jetbrains.kotlin.android` plugin must **not** be
applied (Kotlin support is built into AGP 9), and current AndroidX releases refuse AGP below 9.1.

`androidx.compose.material3` is pinned to `1.5.0-alpha26` in `gradle/libs.versions.toml`, ahead of the
Compose BOM. This is deliberate: the BOM's 1.4.0 ships the Material 3 Expressive tokens but keeps
`MaterialShapes`, `LoadingIndicator`, `ButtonGroup`, `FloatingToolbar`, the flexible app bars and
`MotionScheme.expressive()` out of its public API, so an app cannot actually build in the expressive
system with it. Do not "fix" the pin back to the BOM.

## The gates and scoped test suites

**Never run tests during planning, research, or documentation/markdown tasks.** Tests belong exclusively to the verification phase AFTER code changes are made.

To keep iteration fast (6-7s), unit test suites are partitioned by functional domain. **Always run only the test suite(s) relevant to the current changes during development:**

```bash
# 1. Scoped domain test runners (fast JVM execution):
scripts/run_tests.sh caelis # Intervalometer core, service, overlay, session planner, calibration, network isolation, workflow hardening, exported components, log privacy, release build, permissions per platform level, handheld detection, translation parity (373 tests)
scripts/run_tests.sh solar          # Sun & Moon trajectory, ephemeris, AR projection, terrain horizon, skyline matching, tile cache and download (747 tests)
scripts/run_tests.sh nightsky       # Night sky AR, Meeus celestial math, FlatBuffers catalog, overlay & hit test, sky materials, sky names (457 tests)

# 2. Full gate before final commit (all suites + assemble + Android Lint):
scripts/run_tests.sh all
# or directly:
# JAVA_HOME="$JAVA_HOME" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks

# 3. Instrumented device tests on dedicated dev user (when testing device behaviour):
scripts/run_instrumented.sh                    # optional filter: scripts/run_instrumented.sh ScreenOffTest
scripts/run_instrumented.sh StarCatalogDeviceTest

# 4. The end-to-end proof: real Pixel camera, real photos
scripts/e2e_pixel_camera.sh
```

Read the totals from `app/build/test-results/testDebugUnitTest/*.xml` (`tests` / `failures` / `errors` /
`skipped`), not from the console. Current baseline: **1420 JVM tests (379 intervalometer, 828 solar, horizon & pose, 495 night sky — `solar` and `nightsky` overlap on `domain.arframe`, `ar.scene` and the virtual places (`domain.location`, `data.location`), which is why the three do not add up — plus 31 in `docs`, which no scoped suite runs), 53
instrumented tests, 0 failures, 0 skips, lint 0 errors.** Anything less is a regression.

`run_tests.sh` filters by package, and a new `domain/` package is picked up by **no** suite until it is added
to one. `domain.horizon.*`, `data.horizon.*`, `ar.*` (so `ar.pose.*` and `ar.scene.*` too) and `domain.arframe.*` are in `solar`; `ar.scene.*` and `domain.arframe.*` are in `nightsky` as well; `domain.location.*` and `data.location.*` are in both `solar` and `nightsky`; `domain.stability.*` is in `caelis`; `NetworkIsolationTest`, `WorkflowHardeningTest`, `ExportedComponentsTest`, `LogPrivacyTest`, `ReleaseBuildTest` and `SettingsMappingTest` (the ARCore consent keys) are in `caelis`. `docs.*` is in none of them and only runs under `all`. `ar.pose.ArCoreSessionControllerTest` is where consent-off-means-zero-calls is proven on the JVM.

`scripts/README.md` documents the whole harness: the snapshot keys, why the accessibility list has to be
*cycled* rather than written, why the instrumented tests re-enable the service themselves, the debug
fake camera (`--ez unlabeled true` forces the coordinate path) and the broadcast entry points that start
a session from the shell.

## Layout and who owns what

| Package | Rule |
| --- | --- |
| `domain/` | Pure Kotlin, no Android imports. Everything here is unit-tested; keep it that way. |
| `domain/stability/` | Pure Kotlin "phone in hand, not on a tripod" check: `HandheldDetector` (gyroscope over a 3 s window) and `HandheldPolicy` (when it runs and when Start asks first). |
| `domain/solar/` | Pure Kotlin astronomical & ephemeris calculations (PSA solar positioning, sunrise/sunset, golden hour, twilight, day trajectories, lunar position & ephemeris, terrain rise/set). |
| `domain/horizon/` | Pure Kotlin terrain horizon: `.hgt` grid decoder, the peak model and `.peaks` tile codec (`HorizonLandmark`, `LandmarkTileCodec`) and which peaks stand on the skyline (`LandmarkVisibility`), ray-marched skyline with Earth curvature and refraction, the 1440-sample `HorizonProfile`, the automatic search-radius estimate (`HorizonRangeEstimator`), and the sky/terrain boundary finder and fitter behind automatic alignment (`SkylineExtractor`, `SkylineMatcher`). No Android imports, no network. |
| `domain/arframe/` | Pure Kotlin geometry and decisions behind the Filament sky and the pose hand-over, no Android imports and no ARCore types: frames and projection (`SkyFrameMath`, `SkyProjector`, `SkyQuadMath`, `AltAzGridBuilder`, `HorizonMeshBuilder`, `HorizonMorph`), the camera picture's crop (`CameraBackgroundUv`), the instant the overlay is drawn at (`PoseTimestampPolicy`), heading (`HeadingLock`, `YawOffset`), and ARCore's side: `ArWorldAlignment`, `ArYawEstimator` + `HeadingFusion`, `RealignTrigger`, `ArCoreFov`, `LumaRotation`, `PoseArbiter` (which tracker draws) and `CameraOwnerPolicy` (which client owns the camera). Tested by both the `solar` and the `nightsky` suite. |
| `domain/nightsky/` | Pure Kotlin celestial astronomy (Meeus GMST/LST, J2000.0 RA/Dec to Az/Alt, vector frustum culling, star optics & B-V color temperature, planetary ephemeris, constellation tables, DSOs, meteor showers, visibility schedule `CelestialVisibilityCalculator`, search index, `SkyNames` — the names of all of it, per language). |
| `domain/location/` | Pure Kotlin virtual places: `SavedPlace` (name, coordinates, the place's own zone), its codec, what a share or `geo:` link from a map app carried (`IncomingPlaceParser`: read from the text only, a short link is reported and never followed), which place each AR screen shows (`PlaceSelection` + `PlaceSelectionPolicy`, per screen or synced) and `ObserverPolicy`, the one decision of where the sky is computed from and what that allows (profile without a GPS fix, no auto-alignment, no camera, the place's zone). In `solar` and `nightsky`. |
| `ar/` | AR projection math (`CameraProjection`), aspect-ratio crop aware FOV calculator (`CameraFovCalculator`), rotation vector tracker with True-North declination & user calibration (`OrientationTracker`), timestamp ring buffer (`SensorTimestampRingBuffer`), quaternion interpolation (`QuaternionSlerp`), GPS location provider (`SolarLocationProvider`), CameraX dynamic preview with measured FOV and single-frame analysis (`CameraPreview`), the app-owned preview surface that hands every camera image to the GPU (`CameraFrameSource`, `PreviewFrameClock`, and `CameraBackgroundSink`, the interface a test drives in place of the GPU picture), and the camera-geometry half of automatic horizon alignment (`SkylineAligner`, which takes a `CameraFrameGeometry` — frame size, viewport, fill scale, rotation, FOV — rather than loose parameters, so a CameraX frame and an ARCore frame go through the same fit). |
| `ar/pose/` | `ArCoreSessionController`: the only place a `com.google.ar.core.Session` is constructed, resumed or installed, behind per-call consent. Other pose code may import ARCore types here and nowhere else. The rest of the package is ARCore-free and what the screens use: `PoseSource` (`SensorPoseSource` wraps `OrientationTracker`, `ArCorePoseSource` turns an ARCore frame into the sky's device→ENU), `PoseHandoff` (which of the two draws), `SkyPoseRig` (who owns the camera and the whole per-vsync decision; built by `ui/rememberSkyPoseRig`), `ArCoreHost` + `ArCoreFrameSource` (the live session, behind the `ArFrameSource` seam a test scripts). |
| `ar/scene/` | The Filament sky renderer both AR screens draw with: `SkySurface` (engine, camera, TextureView or headless swap chain), `NightSkyScene` and `SunArScene`, the camera picture (`CameraBackground`), the line/disc/star/textured batches, the marks (`SpriteAtlas` + `LabelBatch` for pre-rendered names and badges, `GlyphBatch` for distance-field glyphs; the packing is `domain/arframe/AtlasPacker`), `TerrainRing`, `SkyGridMarkers` and the shared palette (`SkyPalette`). Materials live in `app/src/main/materials/` and are compiled by `compileFilamentMaterials`. |
| `data/` | DataStore settings (`SettingsStore`), advanced settings, the ARCore and Geospatial consent pair (`ArCoreSettings`, both default off, Geospatial never on without ARCore), and the process-wide `SessionRepository` snapshot. |
| `data/horizon/` | Elevation and peak tile download (`DemTileDownloader` — **the only file in the app that touches the network**), hand import (`DemTileImporter`), the mmap tile cache (`DemTileCache`), the peak tile cache (`LandmarkTileCache`), the profile codec (v3 carries the named peaks), `LandmarkSource` + `ProfileNamer` (peak tiles -> names on a stored profile), and `HorizonRepository` which orchestrates them. |
| `data/nightsky/` | FlatBuffers zero-copy binary catalog schemas and generated accessors (`NightSkyCatalog`, `Star`, `Constellation`, `Dso`, `MeteorShower`, `StarMetadata`; the accessors are hand-kept in step with `night_sky.fbs` and `data_pipeline/generate_catalog.py`, there is no `flatc` in the build) and `SkyNamesResource`, which loads `res/raw*/sky_names.tsv` for the current locale. |
| `data/location/` | `SavedPlaceRepository`, a process singleton like `HorizonRepository`: the saved places and the selection in `filesDir/locations/` (out of the cloud backup), atomic writes, the place each screen shows resolved in the same call that writes. |
| `service/` | The accessibility service owns the session. `SessionEngine` is the state machine, `ShutterDispatcher` is the only thing that taps, `CameraProfiles` is data, `RunStateStore` is the durable run. |
| `overlay/` | The floating panel and the calibration overlay. Timing/placement decisions live in `OverlayPolicy` and `PanelPlacement`, which are framework-free and unit-tested — put new decisions there, not in the view code. |
| `notify/` | The ongoing session notification (`SessionNotifications`, re-arms its own tick, must never post while unattached) and the terrain download progress notification (`TerrainNotifications`, its own channel and id). |
| `verify/` | The optional MediaStore check. It owns the question of *whose* row counts. |
| `monitor/` | Battery percentage and available storage monitoring (`BatteryMonitor`, `StorageMonitor`), and the gyroscope behind the "phone in hand" warning (`MotionMonitor`, behind the `MotionSource` seam). |
| `ui/` | One activity (`MainActivity`), Compose screens (Home, Setup, Ramp, Advanced, Permissions, Settings), Material 3 Expressive theme (`CaelisTheme`); Settings holds the ARCore and Geospatial switches and their disclosure dialogs. `rememberSkyPoseRig` (`SkyPoseRigState.kt`) wires a `SkyPoseRig` to an AR screen's lifecycle and to the consent flow, and `PoseSourceChip` says which tracker is drawing (shown only once the user has consented to ARCore). |
| `ui/solar/` | Sun AR Compose screen (`SunArScreen`) and ViewModel (`SunArViewModel`). |
| `ui/horizon/` | Offline terrain management screen (`OfflineTerrainScreen`) and the terrain metrics debug HUD (`TerrainDebugOverlay`). The standalone Terrain AR screen is gone: the terrain skyline is drawn by Sun AR and Night Sky. |
| `ui/nightsky/` | Night Sky AR screen (`NightSkyArScreen`), ViewModel (`NightSkyArViewModel`), the GPU sky composable (`NightSkyGpuSky`), the CPU overlay and tap hit test over it (`NightSkyRenderer`), search bar, Virtual Clock, DSO legend, detail bottom sheets with local date override. |
| `ui/location/` | The virtual places UI shared by both AR screens: `PlacesSheet` (GPS or a saved place, add/edit/delete, zone picker, the sync switch), `PlacesButton` (the map button; filled with a pin badge while a place is shown), `placeChipLabel`, and `IncomingPlaceSheet` (a place shared from another app becomes a saved place or a prefilled Offline terrain region). The coordinate field is `ui/CoordinateInputField`, shared with Offline terrain. |
| `site/` (repository root, not the app) | The presentation site at https://caelis-app.com: `template.html` with `{{key}}` placeholders, `strings.json` (every app language, keys checked at build) and `assets/`. `scripts/build_site.py` writes one static page per language plus sitemap and robots into `site/dist/`, which is untracked; `pages.yml` builds it on pull requests and deploys it from `main`. Its screenshots are cropped like the store cards so the top bar, which can carry coordinates, stays out. |

`ShutterAccessibilityService` marks its UI wiring with an `==== INTEGRATION POINT ====` block: those are
the only calls into the notification and the panel.

## Invariants that were bugs once

Each of these was found by review or on the device and is now covered by a test. Do not undo them.

- **Disable OIS and EIS on CameraX preview and analysis during AR tracking.** Optical stabilization
  physically shifts the lens and video stabilization distorts the frame, desynchronizing the camera feed
  from the device's IMU data. `Camera2Interop` forces `CONTROL_VIDEO_STABILIZATION_MODE = OFF` and
  `LENS_OPTICAL_STABILIZATION_MODE = OFF`.
- **Camera frame orientation must use timestamp-interpolated sensor readings (SLERP).** Camera frames
  arrive tens of milliseconds after exposure; using the latest live sensor matrix causes drift during
  camera rotation. `SensorTimestampRingBuffer` + `QuaternionSlerp` queries orientation at the exact
  `imageInfo.timestamp`.
- **AR world-to-device mapping is $R^T$ (Transpose).** Android's `SensorManager` rotation matrix $R$
  maps device vectors to world coordinates ($V_{\text{world}} = R \cdot V_{\text{dev}}$). Transforming
  world points (sun, horizon, celestial grid) onto the camera viewport requires the orthogonal inverse
  $V_{\text{dev}} = R^T \cdot V_{\text{world}}$. Direct row multiplication inverts camera motion and
  breaks spatial locking.
- **World frame rotation is pre-multiplied.** True-North magnetic declination and user calibration
  rotate the ENU world frame around world $Z$ (Up): $R_{\text{true}} = R_z(\text{declination} + \text{offset}) \cdot R_{\text{sensor}}$.
  The back camera optical axis in true world coordinates is $[-R_{\text{true}}[2], -R_{\text{true}}[6], -R_{\text{true}}[10]]^T$.
- **AR FOV must account for viewfinder aspect crop.** CameraX `FILL_CENTER` crops the sensor frame to
  fit tall screens (20:9, 19.5:9), altering the horizontal FOV:
  $\text{cropRatio} = \text{viewportAspect} / \text{sensorAspect}$,
  $\text{effectiveFovH} = 2 \arctan(\tan(\text{rawFovH} / 2) \cdot \text{cropRatio})$.
- **The AR FOV is measured from the stream, not derived from the lens.** `SENSOR_INFO_PHYSICAL_SIZE`
  describes the whole pixel array; what reaches the screen is a `SCALER_CROP_REGION` inside the
  active array, at the resolution CameraX chose, scaled by FILL_CENTER (`CameraBackgroundUv` now).
  `CameraFovCalculator.computeStreamHorizontalFov` takes all three and
  `CameraPreview` reads the crop region out of the capture results. Deriving it from the lens alone
  reported 36.7° where fitting the terrain profile to the camera image measured ~32° — 14 % wide,
  which squeezes the whole overlay towards the centre and cannot be corrected by panning. The
  geometry-only path stays as the fallback for devices that report no crop region.
- **A measured FOV already contains the zoom; an unmeasured one does not.** `CameraPreview` says
  which it is handing over, and the view models divide only by the zoom the camera did *not*
  perform. Dividing a measured FOV by the full zoom ratio counts the zoom twice.
- **Zoom belongs to the camera and the overlay, or to neither.** `cameraFovHorizontal` is
  `baseFov / zoomRatio`, so a pinch that never reaches `CameraControl.setZoomRatio` shrinks the
  overlay while the image stays put. `attachCamera` is how the control gets there. Sun AR has no
  zoom at all — it only added a variable to the alignment — so its FOV *is* the measured one.
- **A drag in Sun AR is a calibration, not a pan.** It moves `calibration*` through
  `nudgeCalibrationBy` and never pauses the sensors: the overlay keeps tracking while the finger
  walks it onto the ridge, and letting go snaps nothing back. The freezing pan stays for Night Sky's
  manual mode, and for Sun AR under a virtual place (`Observer.dragPans`), where there is no ridge to
  calibrate against and a drag that did nothing left turning the phone as the only way to look round.
  That pan starts level, as Night Sky's does (`OrientationTracker.levelPanMatrix`: the phone's roll
  dropped over 350 ms of ease-out rather than in one frame, where the camera looks kept; `RollEase`,
  which Night Sky's manual take-over uses too, except within 10° of the zenith or nadir); frozen as it was, a phone held at a slant kept the ridge
  slanted for the whole pan. It stores nothing and leaves the calibration alone; the top bar's compass button ends it, and
  so do changing the place and re-entering the screen (`SunArViewModel.endPan`). The *stored* calibration is clamped to ±20° in both axes — a compass is wrong by
  degrees, and anything larger kept for the next launch would be indistinguishable from a broken
  magnetometer. The drag itself is not bounded in azimuth (next invariant but one).
- **A compass is judged by what it does, never by `values[4]`.** The rotation vector's heading accuracy
  is a coarse step per session on the Pixels (30° all along on a Pixel 7 Pro, 60° or 120° on a Pixel 10
  Pro, and 30° there too when the overlay stood 180° out), and the old "beyond ±20° is UNRELIABLE" rule
  kept the compass chip up forever on a compass that was right, figure eights or not. `event.accuracy`
  said MEDIUM/HIGH on both phones. `CompassConsistency` compares ψ (`YawOffset`, the compass minus the
  game vector) across directions: binned by the game vector's camera azimuth over 20 s, slow turns only
  (3–120 °/s, camera within 60° of the horizon, game vector settled). A spread over 8° across 90° of
  directions is INCONSISTENT (Pixel 10 Pro: ±22°, the same on every turn, a residual bias turning with
  the phone), within 4° CONSISTENT (Pixel 7 Pro: 1.8° over a full turn); a still phone adds nothing,
  because ψ also drifts slowly and a radiator moved it 3° with the phone still. A field strength
  outside ±40 % of the WMM value (20–75 µT without a position) for 2 s is INCONSISTENT at once (a
  magnet read 202 µT). `HeadingLock.compassState`: accuracy 0 is UNRELIABLE, then the evidence decides,
  and only without evidence the accuracy. `CaelisCompass` logs it in debug builds behind the Settings
  debug switch (off by default; the switch is not shown in a release, the log carries the expected field).
- **A hand alignment past ±20° is never refused; the excess holds for the session only.**
  On a Pixel 10 Pro's first start the overlay stood ~180° out: the built-in magnets put a ~450 µT
  offset on the magnetometer (ten times the Earth's field) and until the fusion has estimated it the
  rotation vector's north is anywhere. It was once allowed only while the compass was not OK and the
  gyro held the heading; that blocked real corrections, because steel nearby adds a field fixed in the
  world, which shifts north equally in every direction and passes `CompassConsistency` as CONSISTENT.
  A hand alignment (drag, nudge) puts what the ±20°
  calibration cannot hold into `OrientationTracker.coarseHeadingOffsetDeg` (`CoarseHeading`): in the world-Z term and
  in `getReferenceMatrix` (the automatic alignment searches around it), but not in ARCore's north
  (`getCompassReferenceMatrix`; `SensorPoseSource` hands it over with the calibration, applied on top
  — inside the reference `HeadingFusion` would reject the jump as an outlier for 30 samples), never stored,
  cleared by a new session and by a reset. An azimuth alignment locks the heading, so the compass does
  not turn the overlay away again; on a phone without a game rotation vector there is no lock, and a
  compass that converges later moves it (drag again). A wrong drag costs only this session. The
  automatic alignment's window stays ±20° around it, and the pitch stays clamped to ±20°.
- **One compass correction per phone.** `ar_azimuth_offset` / `ar_elevation_offset` are shared by
  both AR screens; the old `sun_ar_*` and `night_sky_*` keys are read only to migrate from and never
  written again. Aligning against a ridge by day is what makes the stars line up at night.
- **Automatic alignment refuses rather than guesses.** `SkylineMatcher` returns null unless enough
  columns carried a boundary, the photographed skyline has at least half the relief the terrain does,
  the residual minimum stands out from the average candidate, no second minimum 2° or more away fits
  nearly as well (a repeated ridge), the winner is not on the window's edge, and the fit is under 0.5°. A confident
  wrong alignment is worse than none, because from the outside it looks measured. Its vertical offset
  comes from a low quantile of the differences, never the median: trees and roofs stand above the
  bare-earth model and never below it, so a median folds the average tree height into the calibration.
  Every refusal carries its reason (`SkylineFit.refusal`), shown to the user and logged as `CaelisAlign`
  in debug builds, which also keep the frame, pose and profile under `filesDir/debug/` (`AlignDiagnostics`).
- **The extractor proposes, the terrain chooses.** `SkylineExtractor` keeps every clear downward step
  of a column as a candidate (up to six) and never decides which one is the skyline; each shift of
  `SkylineMatcher.fitCandidates` takes, column by column, the candidate nearest the terrain under that
  shift, and the gates are judged on that curve, where every shift had the same freedom — never after
  the winner had chosen, which would make the fit confirm itself. On top of the gates above, 60 % of
  the columns must have a candidate on the terrain at the winner, and the relief the photographed
  skyline is compared against is the terrain's under the *corrected* compass. Any single rule per
  column was wrong somewhere: the strongest step put the boundary on a sunlit wall below a hazy ridge,
  the first step from the top put it on the underside of a cloud above it on a backlit roof, and all
  six attempts there were refused. With one candidate per column the fit decides exactly what
  `SkylineMatcher.fit` does.
- **Sun AR aligns itself at every start, and the fit is the whole calibration.** A stored calibration
  does not survive a restart: the heading it was measured against was gyro-held from that session's
  compass reading, and on the device the next start put the ridge 11° out. `AutoAlignPolicy` attempts
  as soon as a profile, a camera frame and a still phone are there, retries a refusal after 3 s backing
  off to 10 s, stops for the session on the first accepted fit, and stops for good on any hand alignment
  (drag, nudge, or a successful press of the button) until a reset. Sensor path only. The fit is made
  against `getReferenceMatrix` (no calibration in it) over the whole ±20° azimuth range and replaces the
  calibration with `setCalibration`; a stored value, even one stuck at the clamp, cannot bias it, and an
  answer outside ±20° is refused rather than clamped. The search window was ±8° once and refused that
  11° every time.
- **The search radius is estimated before the ring is fetched, not after.** `HorizonRangeEstimator`
  works from the observer's own tile, which is mandatory anyway, so a valley floor never downloads
  eight neighbours to discover they change nothing. The bound is real: past the distance where a peak
  of the highest elevation nearby would sink below the skyline already found, nothing out there can
  change the answer.
- **A stored calibration must never freeze the sensors.** `OrientationTracker.setPanOffsets` pauses
  tracking on purpose — it is the drag gesture. Restoring a saved offset goes through
  `setCalibration`, which folds into the live matrix on every sensor sample and leaves tracking
  alone; restoring it through `setPanOffsets` opened the AR screen with the overlay frozen for
  anyone who had ever calibrated, which made the stored value worse than useless.
- **After an alignment the heading is gyro-held, not compass-held.** `OrientationTracker` still draws
  `TYPE_ROTATION_VECTOR` and also listens to `TYPE_GAME_ROTATION_VECTOR`; ψ between the two
  (`YawOffset`, same-timestamp pairs only) carries every compass excursion. An azimuth alignment
  (drag, nudge, determined auto-align) locks ψ for the session (`HeadingLock`) and from then on the
  correction cancels later changes of ψ; a restored calibration does not lock, and a reset or a new
  session unlocks it. Indoors the compass needed +14° by a window and −7° on the roof, and the
  rotation vector alone pulled an aligned overlay off again as soon as the phone turned towards
  different steel. Do not draw the game vector itself: on a Pixel 7 Pro it visibly trailed a tilt.
- **No heading is locked before the game vector has settled.** After a start the game vector's tilt
  stood 10.8° off the rotation vector's and converged over ~15 s, its yaw with it; an auto-align
  10 s in locked ψ mid-convergence and the lock then "cancelled" 5.8° of convergence in 12 s, which
  turned the overlay off the ridge it had just matched (it looked like the horizon sinking).
  `GameVectorSettle` calls it settled once the two tilts agree within 0.1° for 2 s (or after 20 s);
  the automatic alignment waits for it, and a lock requested earlier (`HeadingLock.isLocked`) only
  takes hold (`isHolding`) at the first settled sample, staying on the compass the fit was made
  against until then.
- **After a fast swing the tilt is brought back onto gravity, and only then.** On a Pixel 10 Pro a
  300-380 °/s swing left the rotation vector's camera elevation 1-2° above the accelerometer's for
  about 4 s, and the overlay sank below the ridge and crept back (the capture results showed OIS and
  EIS off, so it was not the picture). `TiltRecovery` follows the gravity error only within 4 s of a
  swing over 100 °/s, with the phone still (gyroscope, after a 0.25 s hold) and the accelerometer near
  1 g, fast while it stands 0.3° off and slowly closer in; outside that window it fades out and the
  accelerometer is not consulted at all. Following gravity at rest, even slowly, put the hand's tremor
  into the line: a quarter-second version bobbed visibly, a slow one was still visible next to the
  fusion alone. Debug builds have an A/B switch (`disableTiltRecovery`).
- **An aligned heading is checked against the skyline again, and moved only by what changed since.**
  On a Pixel 10 Pro the gyro-held heading came back 0.6-1.8° beside the ridge after 300-380 °/s
  swings, and nothing on the phone can tell whether the game vector or the compass moved (on a
  later run the compass jumped 3.1° over three swings while the skyline said 0.28°). Once a session
  is aligned and the gyro holds the heading, Sun AR fits the skyline again 1.5 s after a swing over
  100 °/s (`OrientationTracker.swingCount`) and every 20 s at rest (`HeadingRefinePolicy`), and turns
  the heading (`shiftHeadingBy`, which is not an alignment: no lock, no stop of anything) by the fit
  minus a baseline, gliding over 250 ms. The baseline is zero after an accepted fit; after a hand
  alignment it is measured once, before the next swing, as where the fit puts the ridge against where
  the hand did, so the hand's choice is kept — the hand still outranks the fit ("Sun AR aligns
  itself at every start" above). A step under 0.2° is noise and is left alone, one over 3° is refused, a frame from before
  a later swing is dropped, and a flat skyline answers nothing. Sensor path only, silent; debug builds
  log it as `CaelisAlign headingCheck`.
- **Sensor matrices are row-major.** `SensorManager` writes them row-major; convert a slerped
  quaternion with `QuaternionSlerp.quaternionToRowMajorMatrix`, never the column-major
  `quaternionToRotationMatrix`. Mixing the two transposed the auto-align's synchronized matrix and
  put the first heading of the gyro-held tracker ~90° out.
- **Dropping a pan is not forgetting the calibration.** `resumeSensorTracking` and `startSensors`
  clear `pan*` and keep `calibration*`; only an explicit reset clears both.
- **Refraction is never taken away below the horizon.** `AtmosphericRefraction` is the one correction for
  Sun and Moon. Saemundsson stops at −0.8333°, and returning 0 there made the apparent elevation jump 0.6°
  (a step in the day arc); fading it out afterwards (over 1°, then 5°) left a bend, because above the
  horizon the arc lies 0.1–0.6° over its geometric position and underground it has to be brought back.
  Measured by `ArcBendTest`: 1.5–2.5° of turn per two-minute step at the horizon against 0.3–0.5°
  elsewhere. Now the correction keeps growing at the formula's slope and settles on a constant, so the
  arc underground runs parallel to the geometric one, about a degree above it. Only the part of the
  arc that is underground is affected, and nothing there is compared with a real object.
- **Zero allocations on the AR render loop.** A frame of either AR screen is matrix and uniform writes
  plus uploads only when data changed; geometry is rebuilt on data changes (`SunArScene`,
  `NightSkyScene`), the mark batches (`LabelBatch`, `GlyphBatch`) upload only a listing that differs
  from the last one, the atlas is redrawn only when its text or style changes, and the Compose
  overlays draw through pre-allocated paints and buffers.
- **The AR skies render into a TextureView, never a SurfaceView.** SurfaceFlinger presents a
  SurfaceView's buffers independently of the window, and on a Pixel 7 Pro the Compose labels slid
  visibly against the stars during a pan. A TextureView is composited by the window, but **not in the
  vsync its frame was rendered in** (next invariant). The camera picture is part of the Filament frame
  (`CameraBackground`, fed by `CameraFrameSource`); there is no `PreviewView` under the sky any more,
  and putting one back would show a picture composited apart from the sky, tens of milliseconds older.
- **Everything anchored to the sky is drawn in the Filament frame; Compose draws only what is fixed to
  the screen.** Names, DSO and meteor glyphs, the selection reticle, the grid's labels and dots, Sun
  AR's time bubbles, rise/set badges and the sun and moon discs are sprites of a `SpriteAtlas`
  (`label.mat`, drawn with Android's own text stack, colours baked in) and distance-field glyphs
  (`glyph.mat`), pinned to a sky direction and sized in screen pixels. They were a Compose overlay
  drawn in the same vsync from the same pose; measured on a Pixel 7 Pro (`onSurfaceTextureUpdated`
  against `renderFrame`), the TextureView latched a GPU frame a median 19.8 ms after it was rendered,
  only every other frame at 120 Hz, so the overlay ran 2 or 3 renders ahead of the stars, changing
  frame to frame — held in the hand the names shook against the sky, still they did not. Only the
  off-screen chevron and pointers (on the screen edge, not the sky) and debug layers stay in Compose.
  `OverlaySpriteDeviceTest` checks on the GPU that a mark lands within a pixel of `SkyProjector`.
- **The overlay is drawn with the pose at the timestamp of the camera image it is composited with.**
  Each frame latches the newest image first and takes the pose at *that image's* instant
  (`CameraFrameSource.poseTimestampNs` -> `OrientationTracker.getRenderMatrix`), and keeps that
  image's instant for as long as the image stays on screen. The newest sensor sample is only for
  frames with no picture to match (Night Sky outside planetarium mode, no image yet, a stalled or
  non-realtime-clock camera — `PoseTimestampPolicy`). The newest pose over an older picture ran the
  overlay ahead of the ridge on every turn.
- **Night Sky runs the camera only in planetarium mode, and only while its picture can be seen.**
  Outside planetarium mode the sky is a dark dome with the stars (turning the Milky Way off does not
  bring the camera in), and the Milky Way panorama has no alpha and covers the whole sphere, so with it
  on the camera stays off in planetarium mode too. Otherwise the camera is unbound
  (`NightSkyCameraPolicy`) and the sky is drawn with the live pose every vsync. With
  the camera running under the default Milky Way, the previous invariant posed the sky at the images'
  instants and the stars moved 15-30 times a second on a 120 Hz Pixel 7 Pro, invisibly to the user
  and far less smoothly than a planetarium app. Without the camera the field of view is predicted from
  its characteristics (`stillBackCameraFov`, no device opened) and a FOV measured earlier stays. The
  camera permission is asked for only when the picture becomes wanted, never on entry, and a refusal
  leaves the sky drawn with a chip that offers it again, not a screen that blocks it. The
  orientation flow is never read in the Night Sky composition: it changes every 15 ms, and reading
  it there recomposed the sky 61 times a second; the HUD reads it through `WholeDegreesPolicy`.
- **The camera picture is cropped by `CameraBackgroundUv`, from the same FILL_CENTER fit as the FOV.**
  `CameraFovCalculator` assumes the stream is turned upright and scaled until it covers the
  viewport; the UVs do exactly that, so the picture and the overlay cannot disagree about what is
  on screen. Texture origin is the first row in memory (top-left), v down; `CameraBackgroundDeviceTest`
  checks that on the GPU. Night vision never tints the picture, only the sky layers.
- **Every camera image handed to Filament is closed in its release callback, from a fixed pool.**
  `CameraBackground` owns a few preallocated `Runnable`s (no lambda per frame); a lost callback
  leaks one of the reader's four images and, after enough of them, freezes the picture.
- **The camera is bound first, and Sun AR's sky is shown with its picture.** Opening the camera is
  the slowest part of entering an AR screen (~250-300 ms from open to first image on a Pixel 7 Pro),
  so `CameraPreview` binds in the effect phase (`DisposableEffect` + undispatched coroutine, the
  provider used at once when ready), before the sky's scene is built, and `MainActivity` starts CameraX
  at launch. Sun AR's sky stays hidden until a frame contains the picture (`SkyRevealPolicy`; at most
  700 ms after its first frame, at once without a wanted picture); revealed on its first frame, the grid
  stood on an empty dark sky for ~0.4 s (~0.5 s cold) on every entry. Effects that build from the sky's
  surface are keyed by the value read in composition: reading the state inside the effect built the
  scene and the picture twice per entry.
- **One matrix feeds the GPU and the CPU.** The tap hit test, the off-screen pointers and which marks
  are listed project through the scene's `SkyProjector`, updated in the same call that sets the
  Filament camera; the marks themselves are projected by the GPU from that camera.
- **Timing is absolute.** Shot times come from `IntervalSchedule.targetElapsedFor`, never "now +
  interval". A late shot must not push the series. The one exception is the user's own switch
  **Interval after the photo is finished** (`AdvancedSettings.intervalAfterCapture`, time-lapse only):
  the next shot is one interval after the capture *ended*, done by moving the anchor
  (`SessionTiming.anchorShiftAfterCapture`), never by "now + interval".
- **Never tap a busy camera.** On a Pixel 7 Pro, Astro runs up to 4 min and the shutter reads "Zrušit
  fotku" (cancel photo), disabled, for the whole exposure; a 10 s series cancelled every photo. With
  `intervalAfterCapture` a capture ends when its photo appears in MediaStore (the row is pending, so
  invisible, until written: ~4 s after the UI came back), or without photo access when the camera is
  idle again; busy is a profile busy pattern **or any shutter node shown disabled**
  (`ShutterDispatcher.isCameraBusy`), which is what makes it work for camera apps without a profile.
  Capped at 10 min (`SessionTiming.captureWaitStep`), then the series goes on with a warning.
- **A mode chip is found where the modes are.** The Pixel's minibar Night Sight icon is labelled
  "Noční vidění" exactly like the carousel chip and comes first in the tree; clicking it opened the
  photo settings sheet. `CameraProfile.modeChipViewIdPatterns` (`mode_chip_text`) restricts the
  switch to the carousel, and the Czech photo chip is "Fotka". The mode is switched as soon as the
  camera app is in front after Start, retried while its chip is not drawn yet, and the first shot
  waits `MODE_SETTLE_MS` (5 s) after a switch: Astro engages only some time after Night Sight.
- **A tap is not a shot.** With verification armed, `shotsTaken` moves only when verification resolves
  (`PendingShot` / `VerifyStep.resolvesShot`). A retry never increments it; a shot whose every gesture
  the platform refused is not counted at all; a shot is never counted twice on stop/pause.
- **"Unknown" always means "do not tap."** Screen state, keyguard and the foreground package are checked
  before every gesture with strict equality; an unreadable answer is a pause, not a tap.
- **The calibrated point outranks the region guess.** The bottom-centre "largest clickable node"
  heuristic is consulted last and discarded unless its bounds contain the calibrated point.
- **Calibrations are rotation-independent.** They are stored per package *and* rotation, but read with
  `SettingsStore.anyTarget` — any stored slot is usable, and a read-back miss must never erase one.
  A failed *read* is not "nothing stored": one-shot reads propagate the failure on purpose.
- **PeriodicTap is not a camera.** It runs `CameraProfiles.passthrough`; no camera profile's floor,
  latency or mode aliases may apply to it. Anything gating on a profile must use
  `CameraProfiles.forTrigger(mode)`, not `forPackage(package)`.
- **The panel must never eat a shutter tap, and must never become unreachable.** `PanelPlacement` keeps
  it off every pixel a tap can target (raw display pixels; the window uses `FLAG_LAYOUT_NO_LIMITS` and a
  separate `safe` box for the system bars), and the cloak window is scaled to the interval — at a fast
  periodic tap it is switched off entirely rather than holding `FLAG_NOT_TOUCHABLE` forever.
- **The panel stands down over our own screens** (`OverlayPolicy.suppressForOwnUi`), fed by both the
  accessibility events and our own activity lifecycle. The remembered flag survives hide/show.
- **Stop conditions are evaluated on a guard tick**, not only after a completed shot, so a session that
  is waiting or paused still stops on battery, storage or duration.
- **`FLAG_KEEP_SCREEN_ON` belongs to a session that is actually shooting**, never to a paused or waiting
  one.
- **"Phone in hand" only ever warns, and only for a time-lapse.** `HandheldDetector` decides from the
  *fraction* of gyroscope samples above 0.3 °/s over 3 s (hand ≥ 60 %, tripod ≤ 30 %, hysteresis between),
  never from the RMS or the peak: the shutter tap on a tripod is one short spike, and a peak detector
  would warn on every shot. An incomplete window, a gap over 500 ms or no gyroscope is `UNKNOWN`, which
  shows nothing and never asks. The engine writes `SessionSnapshot.handheld`, a field of its own and not
  `warning` (that slot is overwritten per shot and cleared by a resume); `OverlayPolicy.warningText`
  lets the session's own warning outrank it. It watches while counting down and shooting, not while
  paused; the home screen watches before Start and asks "start anyway?" on a positive answer only. It
  never pauses or delays a shot: a hyperlapse from the hand is a real use, and the switch in Advanced
  (`handheldWarning`, default on) is for it. The thresholds come from synthetic traces; `CaelisMotion`
  in debug builds logs the fraction and median rate they are tuned against on the device.
- **No `startForeground` on the accessibility service.** It has no foreground service type and the
  manifest declares no `FOREGROUND_SERVICE`; the notification goes through `NotificationManager`.
- **`SCHEDULE_EXACT_ALARM`, never `USE_EXACT_ALARM`.** Google Play reserves `USE_EXACT_ALARM` for alarm
  clocks and calendars and refused a release that declared it. The user grants the other one on the
  Permissions screen; without it `SessionEngine.scheduleAlarm` falls back to an inexact alarm and warns.
  `NetworkIsolationTest` pins the permission set.
- **The accessibility settings open only after the prominent disclosure's "Agree".** Play requires it for
  an app that is not an accessibility tool. Every button that opens them goes through
  `rememberAccessibilityDisclosure`, which asks each time; `AccessibilityDisclosureTest` pins that
  pressing, dismissing and declining open nothing. Keep its text true to what the service reads and does.
- **`data/horizon/DemTileDownloader.kt` is the only file in `app/src/main` allowed to touch the
  network.** `NetworkIsolationTest` walks the sources and fails the build on a second one. The app
  used to prove "nothing leaves the device" through the *absence* of `INTERNET`; that permission is
  `normal`, so once declared it cannot be withheld or revoked, and the guarantee is now the source's
  to keep. Do not add a second network caller, an analytics SDK, or an HTTP client dependency without
  deciding, out loud, to give that guarantee up. **That guarantee now covers this app's own code only.**
  The optional ARCore integration (below) puts Google Play Services for AR, which this repository cannot
  inspect, inside an AR session; what *it* sends is not covered by `NetworkIsolationTest` and the
  README says so in as many words. What the test does pin is the part the app controls: the one socket
  file, the one file that may start ARCore, consent in front of it, and the exact permission set (the
  ARCore library adds none). Do not word a README line, a string or a comment as "nothing leaves the
  device" without the ARCore qualifier.
- **The app sees only the packages it can target.** `<queries>` in the manifest (launcher, the two
  camera intents, `com.google.ar.core`) replaces `QUERY_ALL_PACKAGES`, which was declared behind a
  `tools:ignore`. `NetworkIsolationTest` pins both. A new package lookup by name needs its own
  `<package>` entry, or it silently finds nothing.
- **The cloud backup takes no location history.** `filesDir/horizons/` is named after where the user
  stood; `data_extraction_rules.xml` keeps it, `locations/` (the saved virtual places), `dem_offline/`
  (over Auto Backup's 25 MB, which would stop the whole backup), `debug/` and the live run out of it. A
  new file holding a position goes on that list.
- **A virtual place is an observer, never a calibration.** `ObserverPolicy` decides it once: the sky,
  the trajectories, LST, visibility and the terrain profile are computed for the place, a profile is
  asked for without a GPS fix (the place is exact), and every time is shown in the place's own zone
  (stored with it; Android cannot tell a zone from a coordinate offline). The camera is off (CameraX
  unbound, the rig parks ARCore, `NightSkyCameraPolicy` says no even in planetarium mode): its picture
  shows this street under that skyline. Sun AR draws `virtual_backdrop.mat` and the place's terrain filled
  below its ridge instead. Automatic alignment, nudges, drag-calibration and the calibration reset are
  disabled in both screens (shown greyed with the reason, not hidden) and refused by the view models; Night
  Sky's bottom reset button then only ends a pan and keeps the stored correction, and Sun AR's drag pans
  instead of calibrating, the same way. There is no real
  skyline or sky to align against, and a correction stored then would be wrong at home. Magnetic
  declination and night vision stay on the GPS position: they belong to the phone and the eyes holding it.
- **One horizon profile, so the screen in front asks for its own.** `HorizonRepository` holds a single
  profile; with the selection not synced, Sun AR may show a place while Night Sky shows GPS. Each view
  model filters the shared profile by `ObserverPolicy.profileBelongsTo` (a foreign skyline is null, never
  drawn) and `startSensors` asks for its own profile again (stored, so instant).
- **The deploy workflow never interpolates `${{ }}` into a `run:` script, third-party actions are
  pinned to a commit and `pip install` to exact versions.** The jobs hold the Play key; a release body
  pasted into bash would run as a command next to it, and a moved tag or a fresh package release would
  run someone else's code there. `WorkflowHardeningTest` pins all three.
- **Release notes are computed from git, never committed by the pipeline.** `scripts/release_notes.py`
  sends the lines of `release-notes/<locale>.txt` added since the latest GitHub release (the API's
  `releases/latest`, which skips pre-releases); production promotes beta's notes and the release body is
  not sent. A job that commits would need `contents: write` next to the Play key, letting a compromised
  build dependency push to `main` or move a tag, and its commit would land after the tag it describes.
  The `release notes` check is not a required status check (the owner merges past the ruleset, below),
  so a red check does not block a merge; the deploy stops before the upload instead. Only a 404 means
  "no release yet":
  any other failure to read the latest release fails the run, because guessing would send every line ever
  written.
- **`main` and `internal` change only through a pull request the owner merges.** The repository is
  public; the ruleset "protect main and internal" forbids deleting either branch, force pushes and
  direct pushes, and asks for one approving review. GitHub does not let an author approve their own pull
  request and every pull request here is opened from the owner's account, so the owner (admin role) may
  bypass the review, in pull requests only: merging is the approval, and a direct push, an agent's
  included, is refused. `internal` is advanced by a pull request from `main`, no longer by a push. Workflow
  runs from a fork wait for the owner's approval (all external contributors), and forks never see a
  secret: the deploy runs only on push and release.
- **Peak names have their own source, and it is pinned.** `DemTileDownloader.downloadLandmarks` fetches
  `https://raw.githubusercontent.com/Dazix/caelis-landmarks/v1/tiles/N50/N50E015.peaks` — the public
  data repository (built by `data_pipeline/generate_peaks.py` from mapsforge POI extracts of OpenStreetMap),
  never the app repository, never a branch. A change of file format is a new tag **and** a new
  constant in the code. The request carries the tile name and nothing else, behind the same per-call
  consent as terrain; it is the same file, so `NetworkIsolationTest` is unchanged. The pipeline writes an
  empty file for every tile of a region it covers, which is why a 404 means "not covered" and not "no
  peaks". OSM data is ODbL: the attribution in Settings (`horizon_attribution_body`) is a licence
  condition, keep it when the screen changes.
- **A peak is drawn only on the ridge.** `LandmarkVisibility.select` keeps a summit only when its apparent
  altitude (Earth curvature and refraction as in `HorizonProfileCalculator`, same effective radius) reaches
  the profile at its azimuth within a tolerance that is a *height*, and that ridge has a top within half a
  degree. A peak behind a nearer hill, or a slope or saddle that happens to lie on the line, is not
  labelled: a label on a mountain the user cannot see is worse than none. Do not loosen it to get more
  names; thin the result instead.
- **A peak's tick stands on the drawn top, not on the summit's own bearing.** The skyline is drawn
  through its 0.25° samples, and an OSM summit node is not the elevation model's highest post: at its
  own bearing and altitude the tick stood up to half a degree beside the drawn top, a few pixels off
  the hill (Ještěd on a Pixel 10 Pro). The renderers draw `HorizonProfile.drawnLandmarks`, which
  `LandmarkVisibility.pinToSkyline` moves onto the nearest top sample within the same half degree
  `select` demands; the stored landmark keeps the summit's real bearing and altitude, and pinning at
  draw time covers profiles stored before the rule.
- **Landmark failure never fails a profile.** The skyline is stored and shown first; `ProfileNamer` adds
  names afterwards and rewrites the profile atomically. No network, no consent, a 404, a server error, a
  corrupt tile or a full disk all end as "not resolved yet" (`HorizonProfile.landmarksResolved`), never as
  an exception, and a stored profile without names gets them later from the peak tiles alone, without the
  DEM. The retry is not only at the first resolve: a repeated `ensureProfile` for the same spot (screen
  entry, a consent change) names a shown unresolved profile again (`HorizonRepository.retryNames`,
  `ProfileNamer.needsNames`: not while a naming job runs, at most every 30 s, because a failed attempt
  costs a connection timeout). The names live with the profile (`filesDir/horizons/`, codec v3, v2 still
  read); the peak tiles in `cacheDir/landmarks/` are cache, like the elevation tiles. An offline region
  prefetches its peak tiles into that cache and names no profile, so names for a region are best effort:
  Android may reclaim the tiles, and the profile is then named from the network when the user is there.
- **Peak labels are atlas sprites and cost nothing per frame.** `LandmarkMarks` bakes each "Name · 1603 m"
  and one shared tick into a `SpriteAtlas` only when the list, the language, the colour (night vision tints
  it in Night Sky), the density or the font scale change, and a frame only lists them. Both stand on the
  local vertical, not on the screen's up (`label.mat`'s align path, `LabelBatch.add(alignToHorizon)`): the
  tick rises from the summit perpendicular to the horizon and the name starts at its top, 60° counter-clockwise
  from the horizon, so a roll of the phone turns the label with the ridge; held screen-upright, it swung
  against the skyline. The text is drawn level and turned on the GPU, never baked at an angle (several times
  the atlas area per name). The clearance lays the turned box out with `TiltedLabel`, the shader's CPU twin,
  from the same projector; peak names are compared with each other as parallel boxes, not by their
  axis-aligned bounds, which would drop every other name. They yield to what
  has priority (`MarkClearance`): Sun AR reserves its badges and the bubbles it drew first. A badge's pill
  lies on its own arc, on the stretch below the horizon (before the dot at a rise, after it at a set,
  `BadgeOnArc`): that stretch is the faint one and nothing else needs it, and a pill searching for a free
  spot beside the arc drifted off it whenever the phone turned. Night Sky
  reserves nothing, and draws its peak names *under* the constellation artwork and lines
  (`NightSkyScene.PEAK_LABEL_PRIORITY` = CELESTIAL, blended before the artwork's `ART_BLEND_ORDER`;
  `NightSkyLayerOrderTest`): on top, a name on the ridge drew the eye away from a figure standing there.
  The other marks print over them. Translucent drawings (35 % by default) still let a name through at
  nearly full strength, so a name lying on a shown drawing's paint is also faded to 30 % (`LandmarkMarks.cover`, asked at
  9 points along the name, answered by `NightSkyScene.covers` from a 32x32 alpha mask of the picture through
  `ArtCoverage`; the quad's rectangle faded names beside a figure, in its picture's empty corner). Night Sky passes font scale 1f on purpose, as every other
  name on that screen is a fixed pixel size; Sun AR scales all of its marks with the real one. A name
  longer than 260 dp (or 60 % of the atlas) is ellipsized, never dropped by a full page. The per-screen
  switches (`peakNamesInSunAr`, `peakNamesInNightSky`) work only while the terrain is on.
- **Constellation and asterism names clear each other.** Asterisms sit inside constellations, and
  printed at their centroids "Orion's Belt" and the "Winter Hexagon" landed on other names and none of
  them could be read. `LabelDeclutter` places them in priority order (the selection, which never moves,
  then constellations, then asterisms): a taken spot moves the name one line down, then one line up,
  and only then leaves it out of the frame. A moved or hidden name goes back only once its own spot is
  free by 4 dp, or hand tremor swaps it every frame. A name off screen or behind the camera is listed at
  its own spot without a check, so a pan over a settled sky still uploads nothing.
- **Deleting the `INTERNET` line from the manifest must keep working.** It is a documented way to
  build from source without a network. `NetworkAvailability.hasInternetPermission()` is how the UI
  finds out at runtime; never gate that on a build flag, which would drift from the manifest.
- **Consent is read per call, never captured.** `DemTileDownloader` takes a `suspend () -> Boolean`
  and evaluates it on every download, so revoking consent in settings takes effect immediately rather
  than at the next process start.
- **ARCore is consent-gated, and `ar/pose/ArCoreSessionController.kt` is the only file that can start
  it.** ARCore is a client for Google Play Services for AR, which reports to Google on its own terms
  and cannot be inspected from here. `arcore_consent` and `geospatial_consent` default to off
  (`ArCoreSettings`; Geospatial can never be on without ARCore), and the controller reads them through
  a `suspend () -> Boolean` in front of every availability probe, install request and `Session(`
  construction, treating a failed read as "no". Not even `ArCoreApk.checkAvailability` runs without
  consent, `requestInstall` is reachable only from a click (`requestInstallFromUserAction`, callable
  from `ui/` alone), and Geospatial needs a build-time API key (`local.properties`
  `geospatial.api.key` or `CAELIS_GEOSPATIAL_API_KEY`, never committed; the switch is hidden without
  it). `NetworkIsolationTest` pins all of this, plus the exact manifest permission set.
- **ARCore and CameraX never own the camera together, and the hand-over has an order.** ARCore opens
  the camera device itself. Before anything moves, `ArCoreHost.availability` is asked *while CameraX
  still has the camera*, so a phone that cannot run ARCore never loses its picture to a failed start
  (not asked again until consent is flipped). CameraX -> ARCore: `cameraXEnabled` goes false, the preview unbinds and
  reports the device really closed (`onCameraReleased`; `unbind` alone only starts closing it), then
  the session starts. ARCore -> CameraX: the session is paused first, then CameraX is re-enabled.
  A pinch above 1x, a withdrawn consent, a failed start (30 s backoff) or a session that delivers
  nothing for 4 s each return the camera to CameraX at once; a session still *starting* is `close`d,
  not paused, because only close makes the controller's suspended start give up before opening the
  camera. The activity pausing pauses the session and resuming restarts it with no CameraX in between.
  `CameraOwnerPolicy` decides, `SkyPoseRig` executes, `SkyPoseRigTest` pins the order. With consent
  off the rig never builds the ARCore host at all.
- **ARCore may own the picture while the sensors own the pose.** `PoseArbiter` hands the pose to ARCore
  only when consented, TRACKING, the heading estimate has converged and the zoom is exactly 1x, and
  hands it back on the very frame any of those fails (a stalled session counts as lost after 250 ms).
  A dark sky pauses ARCore within moments; the sensors then draw at the ARCore image's own timestamp
  and the camera does not change. Tracking regained or a new session starts the yaw estimate over
  (`RealignTrigger`): relocalisation can leave ARCore's world rotated while looking healthy.
- **The standing calibration is applied on top of both pose sources, identically, and is never in the
  ARCore north reference.** ARCore's world is turned to ENU by one yaw (`ArYawEstimator`: Geospatial's
  orientation when consented, keyed and accurate, else the sensor path's heading incl. declination and
  heading lock, averaged by `HeadingFusion`), measured against `OrientationTracker.getCompassReferenceMatrix`,
  which excludes the calibration and the coarse heading correction. `ArCorePoseSource.renderMatrix` then applies `applyCalibration` with
  the tracker's current coarse correction plus calibration on every call, so a drag or nudge moves the overlay at once and is
  never counted twice. `getReferenceMatrix` also refuses a timestamp the history does not cover: the
  ring buffer clamps, and a clamped pose passed off as the right instant is a yaw error.
- **In ARCore mode one field of view feeds everything.** It comes from ARCore's projection matrix
  (`ArCoreFov`, the viewport's own horizontal FOV) and is published through the same `setCameraFov` as
  CameraX's measured one; the rig forwards CameraX's only while CameraX is the camera, because the
  layout listener of an unbound preview would otherwise overwrite it. The picture is fitted by ARCore's
  own display geometry (`Frame.transformCoordinates2d` -> `CameraBackgroundUv.fromCornerSamples`);
  whether ARCore's TEXTURE_NORMALIZED has v down is `ArCoreFrameSource.TEXTURE_V_IS_DOWN`, the one
  switch that mirrors the picture and that only a device can confirm. Automatic alignment in ARCore
  mode uses the frame's own Y plane (`LumaRotation`), pose and fill scale (`LumaFramePose`).
- **A device that cannot receive satellites does not wait for them.** A Pixel Tablet has no GPS
  receiver, and the satellite-only rule left it on "Waiting for GPS signal…" for two minutes, then
  reported the location as failed with a Wi-Fi position already in hand. `SolarLocationProvider.satellitesAvailable()` (the
  `FEATURE_LOCATION_GPS` feature *and* the GPS provider on) picks the rules in `LocationFixPolicy`:
  with satellites, GPS/fused within 100 m and a minute, waited for up to 2 min; without, a network
  position within 500 m and 10 min, waited for 15 s, shown as approximate ("≈") and reported as
  "Determining location" rather than GPS. Location switched off fails at once. Do not loosen the
  satellite rule to match: a network position on a phone is what moved near ridges.
- **A profile is never computed without the tile the observer is standing in.** Without it every ray
  runs out of data inside two kilometres, gives up, and the profile comes back as a flat ring at the
  geometric dip — which renders exactly like a real skyline. `HorizonRepository` treats that one tile
  as mandatory and `HorizonProfile.coveredSamples` counts how many azimuths were actually backed by
  data; below `MIN_USABLE_SAMPLES` the profile is refused rather than drawn. This was a real device
  bug, caused by the next invariant.
- **`DemTileCache.store` must not trim, and `trim` must never evict the working set.** The cap was
  once smaller than a single 60 km working set and trimming ran after every tile, so tile three was
  reclaimed while tile five was still downloading. Reclaiming space from tiles the current run needs
  does not save a download; it corrupts the answer.
- **`HorizonRepository` is a process singleton**, like `SessionRepository`. Three screens read the
  same state; one instance per view model meant the same 26 MB download started twice and two
  coroutines drove one progress notification. Its scope outlives every view model on purpose — a
  download has to survive leaving the screen that started it.
- **Shared horizon settings live in the app's Settings, not on an AR screen.** Radius, download and
  cache are shared by both AR screens; each AR screen keeps only its own show/hide switch. The one
  exception is the **height above terrain**: it describes where the user stands right now (a roof, a
  lookout tower), so it is set in either AR screen's sheet, shared by both, held by `HorizonRepository`
  for the process and never stored (`horizon_eye_height_cm` is removed on the next save). A stored
  tower height would skew the next day's horizon unseen; a chip shows it while it is not 1.6 m. The
  sheet commits it on close, not per step, because each value is a profile computation.
- **No terrain run starts unseen above the user's limit.** Before any tile is fetched,
  `HorizonRepository` asks each missing tile's size with a HEAD request (`DemTileDownloader.contentLength`,
  same file, same per-call consent, never on a metered network with Wi-Fi only on) and `DownloadGate`
  decides: Never refuses, On demand always waits for a tap, Automatic proceeds only while the run's
  bytes — including the observer's tile an automatic radius fetched first — stay within
  `confirmAboveMb`; an unannounced size counts as over, unless the user set the limit to unlimited. Waiting is `HorizonState.AwaitingConfirmation`,
  offered on the AR screens and in Settings, never in a notification; a repeated `ensureProfile` for the
  same request does not probe again. `Downloading` counts the whole run, not one tile.
- **The terrain profile is the artefact; the elevation tiles are cache.** Profiles live in
  `filesDir/horizons/` (5.8 kB each) and are what makes the layer work with no signal. Tiles live in
  `cacheDir/dem/` (up to 26 MB each) and Android may reclaim them at any time. Never move a tile into
  `filesDir`, and never make a drawn horizon depend on one still being there.
- **A tile is stored atomically or not at all.** `DemTileCache.store` writes to a `.part` file of its
  own per call (`createTempFile`, as `LandmarkTileCache`; a shared `<name>.part` let a download and an
  import of one tile truncate each other) and renames only after the length is checked against
  `HgtTile.isPlausibleGridLength`. A truncated transfer is still a mappable blob that would invent a
  skyline out of whatever the missing rows decoded to. `trim` and `clear` leave a `.part` written to in
  the last 10 minutes alone; older ones are crash leftovers and are swept. `OfflineTerrainDownloader`
  follows the same rules in `dem_offline/`, which Android never reclaims, and promotes a cached tile by
  rename or by a copy through a temporary file, never by writing into the final name.
- **A tile is never written past `HgtTile.MAX_GRID_BYTES`.** Download and hand import inflate gzip
  straight to disk through `BoundedOutputStream`, which throws at 3601² × 2 bytes. A gzip stream inflates
  without bound, and a length check after the write cannot stop a few kilobytes from filling the storage.
- **A release is signed with the release key or not built.** Without the four `SIGNING_*` variables
  `checkReleaseSigning` fails `packageRelease` and `packageReleaseBundle`; the build once fell back to
  the debug key silently. Release unit tests and lint do not depend on it.
- **The release is shrunk by R8, and the mapping goes to Play with every upload.** `app/proguard-rules.pro`
  adds no keeps because nothing in `app/src/main` uses reflection; a new `Class.forName`, reflective
  member lookup or JNI callback into app code needs a keep there, with the reason. Resources found by
  name (`Resources.getIdentifier`: the `art_`, `dso_` and `planet_` drawables) are invisible to the
  resource shrinker and are kept by `res/raw/keep.xml`; a new family found that way goes on that list.
  `ReleaseBuildTest` pins both. A JVM test cannot run R8: a change in this area is checked by installing
  a minified build on the device, beside the debug one under an `applicationIdSuffix`, and going
  through every screen, the Filament and FlatBuffers ones above all.
- **A release log does not say where the user is.** A tile name is a 1° square around them, a profile
  file is named after the spot, and an exception's message carries either. `data/horizon/TileLog.kt`
  (`logName()`, `regionLogName()`, `inDebug()`, `logClass`) names them in debug builds only.
  `LogPrivacyTest` fails on a log line that names a key, a coordinate or a raw terrain throwable.
- **The Gradle wrapper pins its distribution's SHA-256.** `./gradlew wrapper --gradle-version X` drops
  `distributionSha256Sum` unless `--gradle-distribution-sha256-sum` is passed; `WorkflowHardeningTest`
  catches it.
- **Every Gradle dependency is checked against `gradle/verification-metadata.xml`.** A library, plugin or
  tool jar whose SHA-256 is not listed fails the build; only IDE sources and javadoc are trusted
  unchecked. After a version change in `libs.versions.toml`, or a new AGP / Kotlin / Gradle, regenerate it
  from every task CI and the deploy run, the release included (it needs the `SIGNING_*` variables, a
  throwaway keystore will do):
  `./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug :app:bundleRelease`,
  then read the diff: a new hash for a version that did not change is the attack this exists for. Entries
  are added, never removed, so prune old versions now and then by regenerating from an empty file.
- **The deploy's Python is hash-locked, dependencies included.** `scripts/requirements-promote.in` lists
  what `promote_release.py` imports; `scripts/requirements-promote.txt` is its closure from
  `uv pip compile --generate-hashes --exclude-newer <a week back>` (the command is in the `.in`), and the
  job installs it with `--require-hashes`. `WorkflowHardeningTest` refuses any other `pip install`.
- **No exported receiver or service is open to every app.** The accessibility service is bound by the
  system alone; the debug `DebugSessionReceiver` requires `DUMP`, which only the shell holds.
  `ExportedComponentsTest` pins both. Anything that can start a session lets its caller tap through
  the accessibility service.
- **A void DEM sample is `null`, never 0 m.** `-32768` marks "no measurement", and those sit in steep
  valleys and on glaciers — exactly where a sea-level reading would invent a horizon dip. The
  calculator skips them; `blendCorners` renormalises around them.
- **Observer height comes from the DEM plus eye height, not from `Location.altitude`.** A phone's GPS
  altitude is routinely tens of metres out, which tilts the entire skyline visibly. The DEM value is
  the same surface the ray march measures against.
- **Terrain crossings are compared against the *apparent* elevation.** The profile already has
  refraction folded into an enlarged Earth radius ($R/(1-k)$, $k = 0.13$); comparing the geometric
  elevation would count it once on one side and not the other.
- **The terrain horizon's orientation lives in geometry, not in a texture coordinate.** The AGSL
  shader once read the profile strip with the panorama's `texU` (north in the middle of the image)
  and drew the whole skyline half a turn out — a ridge due north appeared due south, and it looked
  entirely plausible. `HorizonMeshBuilder` puts vertex i at azimuth i / 4°, so north is sample 0 by
  construction; the panorama keeps its own `+ 0.5` in `landscape.mat`. `NightSkyHorizonOrientationTest`
  renders the real `TerrainRing` through `terrain.mat` offscreen against a profile with terrain in
  one direction only and checks all four cardinal points, so a quarter-turn is caught as well.
- **Polyline quads are wound as an outline.** `LineBatch` writes A-left, A-right, B-right, B-left
  with `side` relative to the A -> B normal at both ends; with B's sides swapped the two triangles
  crossed into a bow tie and every densely sampled arc read as a saw blade. Curves pass their
  neighbours (`addJoinedSegment`) so joints are mitred instead of notched.
- **Terrain download notifications use `IMPORTANCE_DEFAULT` with muted sound and vibration.**
  Android's `IMPORTANCE_LOW` categorises notifications into the Silent section, where Android
  SystemUI suppresses the status bar icon by default. `IMPORTANCE_DEFAULT` with `setSound(null, null)`
  and `enableVibration(false)` guarantees visibility in the status bar next to the clock while keeping
  the download and compute operations completely silent.
- **The notification permission prompt on first launch is one-shot.** On Android 13+ (API 33+),
  `POST_NOTIFICATIONS` is prompted once on first launch and remembered in DataStore
  (`notification_permission_prompted`). The app never nags the user again on subsequent opens
  regardless of whether they accepted or declined.
- **Below Android 13 the newer permission names do not exist, and checking one answers "denied"
  forever.** `minSdk` is 30. Photos are `Permissions.photos()` (`READ_EXTERNAL_STORAGE`, capped at
  `maxSdkVersion="32"`, below 13), notifications below 13 are `areNotificationsEnabled()` and their
  button opens the app's notification settings, exact alarms need no grant on Android 11 (the row is
  hidden there), partial photo access exists from 14, the fused location provider from 12 and dynamic
  colour from 12 (Android 11 gets `FallbackColorSchemes`). Lint has `InlinedApi` as an error so a
  newer constant cannot slip in, `PermissionsPlatformLevelTest` pins both sides of each level, and the
  CI job `api30-launch` opens the app on an API 30 emulator (`scripts/ci_launch_smoke.sh`). That job
  has its own workflow (`api30-launch.yml`) and runs only when a pull request touches the manifests,
  the build and dependencies, the theme or the top-level `ui/` files the launch opens; a change
  elsewhere that could break Android 11 at runtime is checked with "Run workflow" by hand.
- **AR projections scale immediately on zoom without waiting for camera metadata.** CameraX capture
  results and crop regions lag behind `setZoomRatio` by tens of milliseconds. Decoupling the AR geometry
  scaling via `zoomFactor` computed directly from the requested zoom ensures instant 60/120 Hz response
  without overlay tearing or lag.
- **A gesture that starts in a system zone belongs to the system.** `SystemGestureZone`: 28 dp on
  either side (back) and the bottom system-gesture inset, at least 28 dp (home, recents). Such a drag
  neither pans nor calibrates and is not consumed; a tap there is still a tap. The sides were once only
  left unconsumed, and swiping Sun AR away to the home screen still moved the stored calibration twice
  (−2.4/−11.1° to −5.6/−6.7° to 3.0/−6.5°).
- **Contextual back handler in AR screens prioritizes dismissing active overlays.** Pressing back must
  dismiss open bottom sheets (layer settings, object details), search bar, or disambiguation dialogs
  before navigating out of the AR screen. A Night Sky detail reached by tapping a name in another
  one goes back to that one first (`DetailHistory`); a body chosen any other way starts the history
  over, so back never returns to something from before the user picked a new body.
- **Planetarium mode memorizes and restores prior Milky Way state.** Turning planetarium mode on (the
  camera picture behind the sky, opaque fill until the first image) turns off the Milky Way, which
  would cover the picture, and turning it off restores the Milky Way to its previous state unless
  explicitly changed by the user.
- **Celestial hit-testing scores both brightness and proximity.** In dense star fields, Euclidean distance
  alone selects faint background stars over prominent navigation stars. `StarHitTester` combines
  normalized distance and magnitude weights with a disambiguation popup for close candidate targets.
  A constellation or asterism name is selected at once only when the tap is on its drawn text (the box
  the last frame listed, no planet there); a tap merely inside a name's wide hit area puts every such
  name in the popup beside the bodies. The name used to win outright, and a star beside it, or an
  asterism inside its constellation's area, could not be picked.
- **Local date override in celestial detail sheet must not alter global AR time or trigger render loop allocations.**
  `CelestialDetailBottomSheet` calculates visibility asynchronously for the chosen date via `CelestialVisibilityCalculator`
  using an isolated local `Instant`, leaving `NightSkyArViewModel.timeState` and the 60 FPS render loop untouched.
- **DSO and constellation artwork are 3D quads, glyphs are overlay.** Photographs are textured quads on
  the sky sphere at their catalogue RA/Dec corners (perspective comes from the GPU, north from the
  celestial frame); where no photograph is on screen the overlay draws the vector glyph instead.
- **Virtual Clock time scrubber preserves stable layout during drag.** The timeline scrubber provides continuous
  $\pm 12\,\text{h}$ simulation without resizing bottom bar containers or triggering recomposition jitter.

## Conventions

- **Comments explain *why*, and especially why not.** Most comments in this codebase record a failure
  mode that was actually observed. If you change such code, update the comment to match; if you remove
  the guard, remove the comment and say what replaced it.
- **Release notes**: a pull request with a change a user will notice adds one line to every
  `release-notes/<locale>.txt` (one per store listing language, same line in the same place). Written for
  the user, not the code: what they now see or can do, no class names, no "fix(...)". Only add lines; an
  edited line that was already released goes out again as new, a deleted one is simply not sent. The
  unreleased lines of each language must fit in Play's 500 characters with their bullets; when they do
  not, merge them into fewer. The `release notes` CI job checks all of it.
- **Strings**: every user-visible string is a resource in English (`values/`) and in every shipped
  language: Czech, Slovak, Polish, German (`values-cs/`, `-sk/`, `-pl/`, `-de/`; there is no `-de-rAT`,
  nothing in the strings differs for Austria). Countable nouns are `<plurals>` — cs/sk/pl need `one` /
  `few` / `many` / `other`, de `one` / `other` — and the quantity argument for "x of y" is the **total**,
  not the running count. Keep a translation about as long as the English or Czech one: the overlay
  panel and the navigation bar have no room (`OverlayPanelLayoutTest` lays the panel out in every
  language). `TranslationParityTest` pins keys, plural forms, format arguments, debug strings, sky
  names for every language, and one store listing per language; adding one is adding files, and that
  test lists what is still missing. The listing (`metadata/android/`) goes live on merge to `main`, not
  with a release, so a new language's listing comes in a PR of its own once the release that speaks it
  is out: the strings PR adds the language to the test's `listingPending`, the listing PR removes it.
  The system's per-app language picker (Android 13+) lists exactly these languages: AGP generates the
  locale config from the `values-<language>` directories (`generateLocaleConfig`, with
  `res/resources.properties` saying `values/` is `en-US`), so a new language needs nothing more there.
- **Night-sky names live in `res/raw/sky_names.tsv` (English) and `res/raw-<language>/`, never in the
  catalog or the code.** `catalog.bin` carries ids and English only (`Constellation.id`, Messier number,
  shower id, IAU code on a star, English body type; the old `localized_name` slots are deprecated and
  empty); `SkyNames` looks a name up by key and falls back to the English the caller passes, and
  `SkyNamesResource` loads the file Android resolves for the current locale. There is no `isCzech`
  branch anywhere: a language check in code is how Czech once leaked into the English UI. A language
  split delivers only the user's own file. Peak names (`HorizonLandmark`) are not part of this yet: the
  peak tiles carry Czech and English only.
- **Compose**: read colour, shape, type and motion off the theme (`MaterialTheme.*`, `ExpressiveMotion`,
  `ExpressiveType`). Inside a card use `LocalContentColor` rather than naming a colour role, unless the
  colour carries meaning (an error really is `colorScheme.error`). Icon-only controls always carry a
  content description; touch targets are at least 48dp; no state is signalled by colour alone.
- **Tests**: a new decision that can be expressed as a pure function goes in `domain/` or a policy object
  and gets a JVM test. Device behaviour gets an instrumented test that fails loudly rather than skipping.
- **Git Commits & Signatures**: Every commit created by an agent must follow conventional commits
  (`feat(...)`, `fix(...)`, `refactor(...)`, `test(...)`), list clear bullet points of changes, and
  always end with the co-author signature trailer:
  ```text
  Co-authored-by: Antigravity <antigravity@google.com>
  ```

## Style of work

- **Scoped test runs during development**: Run only the domain test suite corresponding to your changes (`scripts/run_tests.sh caelis|solar|nightsky`). Run the full suite (`scripts/run_tests.sh all`) and device tests only for final verification before commit.
- Do the whole task and report what actually happened, including what failed and what you left out.
- When a subagent reports success, verify it — several "green" reports in this repo's history were not.
- Keep the device restored at the end of every session, and say so with the values, not with a claim.
