# Test harness scripts

Everything here targets **one Android user on the attached phone — the one you name in
`ANDROID_USER_ID`** — and never the phone's primary profile. Every script asserts that
`adb shell am get-current-user` returns exactly that id and aborts otherwise, so a script can never
modify somebody's own profile by accident.

## Configuration

The scripts assume nothing is on `PATH` and guess nothing. Three values come from
`scripts/env.local.sh`, which is gitignored because it describes one machine and one phone:

```bash
cp scripts/env.example.sh scripts/env.local.sh    # then fill it in
```

| Variable | What it is |
| --- | --- |
| `ADB` | full path to the `adb` binary, usually `<sdk>/platform-tools/adb` |
| `JAVA_HOME` | the JDK Gradle runs on; `scripts/env.sh` exports it, so `./gradlew` needs nothing from your shell profile |
| `ANDROID_USER_ID` | the Android user the harness may modify. Read it off `adb shell pm list users`. |

Anything already exported wins over the file, so `ANDROID_USER_ID=12 scripts/a11y_setup.sh` works for
a one-off. `PROJECT_DIR` is not configurable — `scripts/env.sh` derives it from its own location.

There is deliberately **no default** for these. An `ANDROID_USER_ID` that silently fell back to a
number would mean a clone on somebody else's phone rewriting their secure settings and deleting their
photos; the scripts abort with a pointer to `scripts/env.example.sh` instead:

```
env.sh: FATAL: ANDROID_USER_ID is not set. Copy scripts/env.example.sh to scripts/env.local.sh and
fill it in (it is gitignored), or export ANDROID_USER_ID for this run.
```

The `adb` snippets quoted further down use `--user "$ANDROID_USER_ID"` verbatim, so they can be
pasted into a shell that has the variable exported.

## Order

```
scripts/run_instrumented.sh          # the normal loop: setup -> instrumented tests -> teardown
scripts/e2e_pixel_camera.sh          # the proof: real Pixel camera, real photos on disk
```

Both are self-contained. `a11y_setup.sh` / `a11y_teardown.sh` are the building blocks and can be run
by hand when you want the service enabled for manual poking:

```
scripts/a11y_setup.sh                # snapshot + enable
... poke at the app by hand ...
scripts/a11y_teardown.sh             # restore exactly what was snapshotted
```

| Script | What it does |
| --- | --- |
| `wake_device.sh` | Wakes the device and dismisses the keyguard, then asserts `mWakefulness=Awake` and `isKeyguardShowing=false`. A Dozing, locked device gives the camera no usable shutter, so this runs before anything else. |
| `a11y_setup.sh` | Snapshots the original device state, allows `SYSTEM_ALERT_WINDOW` / `ACCESS_RESTRICTED_SETTINGS`, grants `POST_NOTIFICATIONS`, **cycles** the enabled-services list off and on with our service **appended**, and does not return until the service is verifiably bound - it `die`s if it never binds. |
| `a11y_assert_bound.sh` | Standalone: exits 0 only when the service is really bound for the harness user, non-zero otherwise. Takes an optional timeout in seconds (default 30, `0` = check once). `a11y_setup.sh` ends by handing over to it. |
| `a11y_teardown.sh` | Restores every value the snapshot recorded (secure settings first, per-package state after), then deletes the snapshot. |
| `install_debug.sh` | Builds the debug APK with Gradle and installs it exclusively for `$ANDROID_USER_ID` via `adb install -r --user`. |
| `run_tests.sh` | Runs scoped domain JVM unit tests (`caelis`, `solar`, `nightsky`) or full test gate (`all`) with strict Python result XML validation. |
| `run_instrumented.sh` | wake → `assembleDebug` + `adb install -r --user "$ANDROID_USER_ID"` → setup → `./gradlew :app:connectedDebugAndroidTest` → teardown (in a trap). Prints the HTML report path and then **decides the verdict from the result XML**, not from Gradle's exit code. Optional first argument filters to one test class or `Class#method`. |
| `e2e_pixel_camera.sh` | Wakes/unlocks, lists MediaStore image ids, launches the Pixel camera, runs a 5 shot / 6 s session with the adaptive long-exposure wait on, waits for the rows, asserts them, screenshots into `build/e2e/`, **deletes exactly the rows it created**, always tears down. |
| `build_release_bundle.sh` | Builds and signs a release Android App Bundle (`.aab`) locally, prompting interactively for keystore passwords and automatically locating `keys/release.keystore`. |
| `check_test_results.py` | Standalone Python validator that parses JUnit test result XML files, counts tests/failures/errors/skipped, and fails strictly on any regression or empty test run. |
| `check_portability.sh` | Enforces portability gates: ensures no machine-specific paths or personal values are committed, local configurations remain untracked, and template exists. |
| `decode_keystore.sh` | Decodes base64-encoded release keystore from environment into target file for CI/CD signing. |
| `set_build_version_vars.sh` | Calculates version names and release names across `internal`, `beta`, and `production` tracks and writes to `$GITHUB_OUTPUT`. |

## Device-hygiene guarantee

The device is shared with a human profile that has its own accessibility services enabled. The
harness therefore never *overwrites* accessibility state, it only *adds* to it:

1. **Snapshot before touch.** `a11y_setup.sh` writes the original values to
   `.device_snapshot` in the repository root, one `KEY=VALUE` per line, before it
   changes anything.
2. **Append, never replace.** Our service is appended to the existing
   `enabled_accessibility_services` list with a `:` separator. Any service the human had enabled is
   written back verbatim; only our own component is ever added or removed.
3. **Cycle, never assume.** The list is always written twice: first without our component (plus
   `accessibility_enabled=0`), then with it. Writing the value the setting already holds is a no-op
   for the framework, and a service the framework holds in its `Crashed services` set - which is the
   state a fresh install leaves behind - is only forgotten when the list actually changes. Without
   the cycle "already enabled" silently means "still dead". The other services blip off for two
   seconds during the cycle and come back with the same list they had.
4. **Idempotent setup.** Re-running `a11y_setup.sh` will **not** overwrite an existing snapshot -
   otherwise the second run would record our own modifications as if they were the original state
   and teardown would leave our service enabled forever.
5. **Teardown in a trap.** `run_instrumented.sh` and `e2e_pixel_camera.sh` install the teardown trap
   *before* they call setup, on `EXIT INT TERM`. The device is restored whether the tests pass, fail,
   crash, or you Ctrl-C the run.
6. **Restore, then forget.** `a11y_teardown.sh` puts the recorded values back - using
   `settings delete` when the original was unset - and removes the snapshot so the next setup takes a
   fresh, honest one. With no snapshot present it prints a notice and exits `0`, which is what makes
   it safe inside a trap.
7. **Installed for the harness user only, not for everybody.** `:app:installDebug` runs `pm install`
   with no `--user`, which installs the app for *every* user on the device - the human's profile
   included. `run_instrumented.sh` therefore builds with Gradle and installs with
   `adb install -r --user "$ANDROID_USER_ID"`. (`:app:connectedDebugAndroidTest` does its own install
   and uninstalls both APKs when it finishes, which is also why teardown has to survive a missing
   package.) If you install by hand, use
   `adb install -r --user "$ANDROID_USER_ID" app/build/outputs/apk/debug/app-debug.apk`, never
   `installDebug`, or the app lands in user 0 as well.
8. **Secure settings first, package state second.** `:app:connectedDebugAndroidTest` uninstalls the
   app when it finishes, so `cmd appops set` / `pm revoke` would fail with *No UID for ...*. The
   secure settings are therefore restored before anything package-scoped, and app ops and runtime
   grants are skipped entirely when the package is gone - they died with it. Anything that could not
   be restored keeps the snapshot on disk and makes teardown exit non-zero, so a re-run can finish
   the job.

### Snapshot keys

`.device_snapshot` in the repository root, `KEY=VALUE`, one per line. Everything after
the first `=` is the value, so service lists keep their `:` separators. `__NULL__` is the sentinel
for "this key was unset", and teardown answers it with `settings delete`.

| Key | Source | Restored with |
| --- | --- | --- |
| `SNAPSHOT_VERSION` | literal `2` | – (format marker; a `1` snapshot has no POST_NOTIFICATIONS row and teardown then leaves that permission alone) |
| `SNAPSHOT_USER_ID` | `$ANDROID_USER_ID` | – (teardown refuses a snapshot from another user) |
| `SNAPSHOT_PACKAGE` | `com.dazix.caelis` | – |
| `SNAPSHOT_TAKEN_AT` | UTC ISO-8601 timestamp | – |
| `ORIG_SECURE_ENABLED_ACCESSIBILITY_SERVICES` | `settings --user "$ANDROID_USER_ID" get secure enabled_accessibility_services` | `settings --user "$ANDROID_USER_ID" put secure enabled_accessibility_services '<value>'`, or `settings --user "$ANDROID_USER_ID" delete secure enabled_accessibility_services` when `__NULL__` |
| `ORIG_SECURE_ACCESSIBILITY_ENABLED` | `settings --user "$ANDROID_USER_ID" get secure accessibility_enabled` | `settings --user "$ANDROID_USER_ID" put secure accessibility_enabled '<value>'`, or `settings --user "$ANDROID_USER_ID" delete secure accessibility_enabled` when `__NULL__` |
| `ORIG_APPOP_SYSTEM_ALERT_WINDOW` | `cmd appops get --user "$ANDROID_USER_ID" com.dazix.caelis SYSTEM_ALERT_WINDOW` (`default` when no explicit entry) | `cmd appops set --user "$ANDROID_USER_ID" com.dazix.caelis SYSTEM_ALERT_WINDOW <mode>` |
| `ORIG_APPOP_ACCESS_RESTRICTED_SETTINGS` | `cmd appops get --user "$ANDROID_USER_ID" com.dazix.caelis ACCESS_RESTRICTED_SETTINGS` (`default` when no explicit entry) | `cmd appops set --user "$ANDROID_USER_ID" com.dazix.caelis ACCESS_RESTRICTED_SETTINGS <mode>` |
| `ORIG_PERM_POST_NOTIFICATIONS` | `dumpsys package com.dazix.caelis`, the `POST_NOTIFICATIONS: granted=` line inside the `User <id>:` block (`granted` / `revoked` / `unknown` when the package is not installed) | `pm grant --user "$ANDROID_USER_ID" ...` when it was `granted`, otherwise `pm revoke --user "$ANDROID_USER_ID" ...`; skipped when the package is gone |
| `ORIG_PERM_READ_MEDIA_IMAGES` | same line for `READ_MEDIA_IMAGES` (granted so `CaptureVerifier` can actually see the camera's output instead of degrading to a silent no-op) | as above |

Some adb calls in these scripts carry no `--user` flag, because the commands have none:
`am get-current-user` (it is the thing being asserted), `exec-out screencap` (it renders the
display), `input keyevent` / `wm dismiss-keyguard` / `dumpsys power` / `dumpsys window` (power state
and the keyguard are global), and `dumpsys accessibility` / `dumpsys activity services`, which report
*every* user - so the checks below cut out the block for the harness user instead of grepping the whole dump.

### How "is it bound?" is decided

Implemented once, in `a11y_assert_bound.sh`, so it can also be run on its own while poking at the
device by hand:

```
scripts/a11y_assert_bound.sh        # wait up to 30s, exit 0 when bound
scripts/a11y_assert_bound.sh 0      # check once
```

`dumpsys accessibility` prints one `User state[` block per user. Grepping the whole dump for
`ShutterAccessibilityService` also matches the `Enabled services:` line, which stays true while the
service is **dead** - that false positive is why a run could report "service is bound" next to
`Crashed services:{{...ShutterAccessibilityService}}`. `a11y_setup.sh` therefore cuts out the block
whose `attributes:{id=11,` marker matches and requires all of:

* `Bound services:{Service[label=...]}` - a non-empty bound set,
* `Crashed services:{}` - an empty crashed set,
* `Enabled services:{...ShutterAccessibilityService...}` - the framework really saw our setting,
* and, as a per-component confirmation, a `ServiceRecord` for `ShutterAccessibilityService` with an
  `app=ProcessRecord{...}` line in `dumpsys activity services com.dazix.caelis`, i.e. a live
  process is hosting it. If that dump has no record for the class at all the check says so and falls
  back on the accessibility sets, so a dump-format change can only warn, never fail wrongly.

None of it binding within 30 s is a **fatal** error, not a warning: every step after it is
meaningless without a bound service. A non-empty `Crashed services` means the process died on
connect; an **empty** `Bound services` next to an **empty** `Crashed services` means something is
suppressing accessibility services - which is exactly what a `UiAutomation` connection does unless it
carries `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` (see the instrumented-test section below).

## Why MediaStore instead of `ls`

`e2e_pixel_camera.sh` lists photos with

```
adb shell content query --user "$ANDROID_USER_ID" --uri content://media/external/images/media --projection _id
```

The adb shell uid cannot read another user's `DCIM`, so `ls /storage/emulated/11/DCIM/Camera` reports
nothing at all rather than failing loudly. The MediaStore query runs *as* the harness user and is the only
count that can be trusted.

It lists **ids**, not just a count: the ids present before the session are subtracted from the ids
present after it, so the delta is the set of rows this run created even if something else on the
device wrote a photo meanwhile. The subtraction (`ids_added`) keys off `FILENAME`, not off the usual
`NR == FNR` awk idiom, because that idiom is wrong for an *empty* baseline — a Developer profile with no
photos at all — where the first record of the second file also satisfies `NR == FNR` and the whole
after-list is swallowed as "already seen". That bug produced a run reporting
`images_before=0, images_after=5, images_delta=0`, failing a session that had in fact taken all five
photos, and then leaving those five frames on the phone because the delete list came out empty. That set is written to `build/e2e/ids-created-<stamp>.txt` *before*
any assertion can abort the script, and the cleanup trap deletes exactly those rows

```
adb shell content delete --user "$ANDROID_USER_ID" --uri content://media/external/images/media --where _id=<id>
```

so a Developer profile does not slowly fill up with test frames, on a passing run or a failing one.
Pre-existing media is never touched. `KEEP_PHOTOS=1` keeps the captures; anything that could not be
deleted is listed in `build/e2e/ids-leftover-<stamp>.txt` and warned about.

## Debug-only entry points

These live in the **debug source set only** (`app/src/debug/`), so nothing below exists in a release
build.

One class per file, all under `app/src/debug/java/com/dazix/caelis/debug/`:
`FakeCameraActivity.kt` (the activity), `FakeCameraViews.kt` (its two tap surfaces),
`FakeCameraTapLog.kt` (the log format) and `DebugSessionReceiver.kt` (the broadcast entry point).

### Fake camera

`com.dazix.caelis.debug.FakeCameraActivity` - a stand-in camera used by the instrumented
tests. Its shutter node reports `viewIdResourceName = com.dazix.caelis:id/shutter_button`
and `contentDescription = "Take photo"`, so the service runs the same node search it uses on a real
camera app. Every tap appends `elapsedRealtime,source` to `filesDir/fake_camera_taps.csv`; `source`
is `node` (accessibility `ACTION_CLICK`), `gesture` (a dispatched touch that landed on the labeled
shutter) or `coord` (a dispatched touch on the unlabeled variant, i.e. the coordinate fallback).

```
# labeled shutter, tap log cleared
adb shell am start --user "$ANDROID_USER_ID" \
  -n com.dazix.caelis/com.dazix.caelis.debug.FakeCameraActivity \
  --ez clear true

# unlabeled variant: no findable node at all, forces the coordinate fallback
adb shell am start --user "$ANDROID_USER_ID" \
  -n com.dazix.caelis/com.dazix.caelis.debug.FakeCameraActivity \
  --ez clear true --ez unlabeled true
```

### Session broadcasts

`com.dazix.caelis.debug.DebugSessionReceiver` starts and stops a session without any UI
scripting. This is what `e2e_pixel_camera.sh` drives. It requires `android.permission.DUMP`, which
the shell holds and no installed app can, so these commands work from `adb shell` and nothing else on
the phone can start a session through it.

```
# start
adb shell am broadcast --user "$ANDROID_USER_ID" \
  -a com.dazix.caelis.debug.START_SESSION \
  -n com.dazix.caelis/com.dazix.caelis.debug.DebugSessionReceiver \
  -f 0x00000020 \
  --es target_package com.google.android.GoogleCamera \
  --el interval_ms 3000 --ei max_shots 5 --el start_delay_ms 0

# stop / clear the fake camera tap log
adb shell am broadcast --user "$ANDROID_USER_ID" -a com.dazix.caelis.debug.STOP_SESSION   -n ... -f 0x00000020
adb shell am broadcast --user "$ANDROID_USER_ID" -a com.dazix.caelis.debug.CLEAR_TAP_LOG -n ... -f 0x00000020

# log the window in front as the accessibility service sees it (logcat tag CaelisNodes)
adb shell am broadcast --user "$ANDROID_USER_ID" -a com.dazix.caelis.debug.DUMP_NODES    -n ... -f 0x00000020
```

`DUMP_NODES` exists because `uiautomator dump` fails with "could not get idle state" on Pixel
Camera's Night Sight and Astro screens, which are the ones a camera profile has to be written
against: one line per node with view id, description, text, clickable/selected/enabled/visible and
bounds.

Extras: `target_package` (string), `interval_ms`, `start_delay_ms`, `max_shots`, `max_duration_ms`,
`ramp_curve` (`linear`/`exponential`), `ramp_end_interval_ms`, `ramp_over_shots` (all accepted as
`--el`, `--ei` or `--es`), and `x_fraction` / `y_fraction` (`--ef`) to force a coordinate
`TapTarget`. `-f 0x00000020` is `FLAG_INCLUDE_STOPPED_PACKAGES`, needed right after an install.

The advanced, device-specific behaviour is reachable too - `--ez` or `--es`, all default false:
`verify_captures`, `adaptive_long_exposure_wait`, `ensure_self_timer_off`, `dismiss_post_capture_ui`,
plus `--es launch_camera_mode <name>` (a profile key such as `night`, or `custom:<id>` for a recorded mode). `adaptive_long_exposure_wait` keeps its old name and now
switches on **Interval after the photo is finished** (`intervalAfterCapture`). Without them a scripted session would be testing a
configuration no user shoots in: a Pixel decides by itself to spend ~3 s on a Night Sight frame and
ignores a shutter tap that arrives mid-capture.

### What the e2e asks for, and why

`SHOTS=5`, `INTERVAL_MS=6000`, `adaptive_long_exposure_wait` **on**, `verify_captures` **off**.

* 6 s rather than 3 s because in dim light the Pixel camera switches itself to Night Sight and spends
  ~3 s per frame on the exposure alone, on top of the profile's 2.2 s capture latency. Measured at
  3 s: all five taps landed on the shutter node and only **three** files appeared. A cadence the
  camera cannot deliver would fail the test for a reason that has nothing to do with the app.
* `adaptive_long_exposure_wait` on because that is the app's own answer to a busy camera: each next
  shot comes one interval after the previous capture ended, and never while the camera is busy.
* `verify_captures` off, deliberately. Verification looks at MediaStore one `captureLatencyMs`
  (2.2 s) after the tap, and the Pixel inserts its row later than that, so the engine decides the
  capture failed and re-taps **every** shot - measured: 10 shutter presses for 5 requested shots.
  With the count assertion below, a session pressing twice per shot would pass this test for the
  wrong reason.

The wait for the frames is polled (up to a bound), not slept: with the adaptive wait on, the session
legitimately runs longer than `(SHOTS - 1)` gaps. `KEEP_PHOTOS=1` keeps the captures instead of
deleting them, and the deletion is retried, because a row the camera has not finished writing
(`IS_PENDING`) refuses a shell delete for several seconds after the shutter.

## Instrumented tests

`app/src/androidTest/java/com/dazix/caelis/`

| Class | Asserts |
| --- | --- |
| `TapAccuracyTest` | shot count, every gap within ±150 ms of the configured interval on the Handler path, no accumulated drift over an 8 shot series (fixed 250 ms span budget), and the coordinate fallback firing when no node is findable. |
| `RampSessionTest` | linear and exponential ramps produce gaps matching `IntervalSchedule`'s own numbers (±250 ms) and moving monotonically in one direction. |
| `AutoStopTest` | `maxShots` stops exactly on count with `StopReason.COMPLETED`, `maxDurationMs` stops inside a one-interval window with `StopReason.MAX_DURATION`, and no tap fires after either stop. |
| `CaptureVerificationTest` | with `verifyCaptures` on, a MediaStore row published later than the profile's capture latency still costs exactly one shutter press, and a capture that never appears is re-tapped exactly once and counted once. |
| `ScreenOffTest` | the screen going off mid-series parks the session (`SessionState.Paused` + the screen-off warning, recorded in `RunStateStore` too), **no tap is logged after the instant the screen went off** and `shotsTaken` does not move, and the session resumes by itself once the screen is live again. Snapshots and restores the display. |
| `CalibratedTargetTest` | a calibrated point is tapped at the pixel `DisplayGeometry` resolves it to - `matchedBy=calibrated`, `GESTURE_ON_TARGET`, provably outside `shutterSearchRegion()` - and, from the other side, a findable shutter node still wins over a calibration elsewhere (`matchedBy=viewId:…`, `NODE_CLICK`). |
| `PeriodicTapCadenceTest` | `TriggerMode.PeriodicTap` at 300 ms really taps at 300 ms (±150 ms per gap, fixed 500 ms span budget) on the `passthrough` profile, i.e. no camera profile's floor (970 ms generic, 2270 ms Pixel) is applied to it. |
| `InterruptedRunTest` | a run record left on disk is picked back up at its stored progress and calibration when the service rebinds, and takes only the frames it still owed; a service torn down mid-run stops as `SERVICE_LOST`, drops its plan, and the rebound service reports the loss and the shot count from disk alone. |
| `ForegroundGuardTest` | leaving the target app parks the session with the wrong-app warning, nothing is tapped and `shotsTaken` does not move while another app is in front, and bringing the target app back resumes it without any user action. |
| `GuardTickTest` | `maxDurationMs` also ends a session parked in `WaitingToStart` behind a long start delay (with no frame ever taken) and one the engine paused itself, both within one `SessionEngine.GUARD_TICK_MS` of the limit. |
| `StarCatalogDeviceTest` | verifies `catalog.bin` binary FlatBuffers loading directly from Android `res/raw` on real ART runtime, asserts minimum star/constellation thresholds and coordinates, and executes full `NightSkyRenderer` canvas render loop with celestial dome grid and star batch projection without allocations or exceptions. |

Every test starts its session the same way, through the running service instance:

```kotlin
ShutterAccessibilityService.instance!!.startSession(config)   // SessionHarness.startSession(config)
```

and `@Before` calls `SessionHarness.requireServiceBound()`, which **gets the service bound and fails
if it cannot**. It never skips: a skipped run proves nothing about the engine, and a green report full
of skips is exactly how a dead service used to pass the gate.

### Why the tests have to re-enable the service themselves

Starting instrumentation restarts the app process. A process that dies while the framework has its
accessibility service bound goes into the framework's `Crashed services` set - the binding is gone,
and it is not retried while the crash is remembered, which the framework only forgets when
`enabled_accessibility_services` actually *changes*. So `scripts/a11y_setup.sh` enabling the service
before the run is undone by the very act of starting the tests, and
`ShutterAccessibilityService.instance` is null in every `@Before` unless the list is cycled **again**
from inside the instrumentation. `AccessibilityBinding` (in the test source set) does exactly that,
through `UiDevice.executeShellCommand` once instrumentation is attached:

1. wake the screen and dismiss the keyguard,
2. write `accessibility_enabled=0` and the enabled list **without** our component,
3. write the list **with** our component back and `accessibility_enabled=1`,
4. poll `ShutterAccessibilityService.instance` for up to 30 s and throw an `AssertionError` carrying
   the bound/enabled/crashed sets if it stays null.

Only our own component is ever added or removed there; snapshot and restore stay the scripts' job.

`run_instrumented.sh` then refuses to trust Gradle's exit code, which is `0` even when every test
skipped itself: it sums the `tests` / `skipped` / `failures` / `errors` attributes of the
`<testsuite>` elements in `app/build/outputs/androidTest-results/connected/**/*.xml` (deleted before
the run, so stale results cannot vouch for a new one) and fails the script when there are no tests, or
any skip, failure or error.

The helpers live next to the tests: `SessionHarness.kt` (session plumbing), `AccessibilityBinding.kt`
(the binding above) and `ScreenState.kt` (turning the display off and on again, and proving it
happened). `SessionHarness` is the single place in the test sources that names the service API:

* `ShutterAccessibilityService.instance: ShutterAccessibilityService?` (a property, not a function)
* `startSession(config: SessionConfig, advanced: AdvancedSettings = AdvancedSettings())`
* `stopSession()` - takes no argument and records `StopReason.USER`
* `activeProfile(): CameraProfile` and `lastTapOutcome(): TapOutcome?` - which profile is in force and
  where the last shutter press actually landed
* `SessionRepository.snapshot: StateFlow<SessionSnapshot>`
* `RunStateStore(context)` - the durable half of a run, as `SessionHarness.runState`

`DebugSessionReceiver` in the debug source set uses the same calls.

`SessionHarness.reset()` also clears that durable record. A plan left behind by one test is exactly
what `SessionEngine.restore()` is built to pick up, so without this the next accessibility binding
would start a phantom session inside the next test.

`AccessibilityBinding` can also take the service **off** and put it back (`unbind()` / `enable()` /
`rebind()`), which is what `InterruptedRunTest` uses to reach the service-loss and resume paths. It
waits for `ShutterAccessibilityService.instance` to actually become null, so the service's own teardown
- including the mid-run `SERVICE_LOST` bookkeeping - has provably run before anything is asserted.

### Why there is no force-stop test

A real `adb shell am force-stop --user "$ANDROID_USER_ID" com.dazix.caelis` cannot be expressed as an
instrumented test: the instrumentation runs *in* the app process, so however it is issued -
`UiDevice.executeShellCommand` included - it kills the test with the app and the run reports as a
crashed instrumentation instead of a pass or a fail. `InterruptedRunTest` therefore asserts the two
halves that survive a kill and can be observed: what `RunStateStore` persisted while the session was
live, and what a rebound service does with that record (resume it, or report the loss exactly once).
The record format itself is round-tripped on the JVM in
`app/src/test/java/com/dazix/caelis/service/RunPlanCodecTest.kt`.

### Tolerances, and where they come from

They are pinned to the engine's own constants rather than picked by feel:

* `SessionEngine.HANDLER_GAP_LIMIT_MS = 60_000` - every interval used by these tests (0.3-3 s) is
  well under it, so the tests really do exercise the `Handler` path and not the `AlarmManager` one.
* `SessionEngine.LATE_TOLERANCE_MS = 250` - a shot later than this re-anchors the series on purpose.
  The drift assertion therefore allows a **fixed** 250 ms of first-to-last span error regardless of
  how many shots ran; it is explicitly *not* `shots * tolerance`, since a budget that grows with the
  series is exactly what would hide accumulated drift.
* `maxDurationMs` is evaluated in `finishShot()`, i.e. at shot boundaries, so `AutoStopTest` accepts
  a stop anywhere in `[limit - interval, limit + interval + grace]` instead of on an exact
  millisecond.
* `SessionEngine.GUARD_TICK_MS = 5_000` - the resolution of the guard tick, and therefore the window
  `GuardTickTest` allows a stop in when the session is not shooting: `[limit - grace, limit +
  GUARD_TICK_MS + grace]`.
* `CameraProfile.minIntervalMs` - 970 ms generic, 2270 ms for the Pixel profile
  (`captureLatencyMs + ShutterDispatcher.TAP_DURATION_MS`). `PeriodicTapCadenceTest` asserts its
  300 ms series finishes in less than the generic floor would have needed for the same shot count,
  so a clamp cannot hide inside a per-gap tolerance.
