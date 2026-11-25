package com.dazix.caelis.service

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dazix.caelis.R
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.domain.cameramode.CustomModeKey
import com.dazix.caelis.domain.IntervalSchedule
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionPlanner
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.domain.stability.HandheldPolicy
import com.dazix.caelis.domain.stability.HandheldState
import com.dazix.caelis.monitor.BatteryMonitor
import com.dazix.caelis.monitor.MotionMonitor
import com.dazix.caelis.monitor.MotionSource
import com.dazix.caelis.monitor.StorageMonitor
import com.dazix.caelis.verify.CaptureVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives one shooting session: wait out the start delay, shoot, work out the next absolute target,
 * ask [SessionPlanner] whether to stop, repeat.
 *
 * ### Timing
 * Every shot time comes from [IntervalSchedule.targetElapsedFor], an absolute
 * [SystemClock.elapsedRealtime] instant derived from the session anchor — never from "now + interval".
 * A shot that fires 40 ms late therefore cannot push the following ones, so error does not accumulate
 * no matter how long the session runs.
 *
 * Two waiting mechanisms, chosen per gap:
 *  * gap < [HANDLER_GAP_LIMIT_MS] — a main-looper [Handler] posted to the *absolute* uptime that
 *    corresponds to the target, with a `PARTIAL_WAKE_LOCK` held continuously (the wake lock is what
 *    keeps `uptimeMillis` advancing, which is what makes `postAtTime` reliable).
 *  * gap >= [HANDLER_GAP_LIMIT_MS] — [AlarmManager.setExactAndAllowWhileIdle] on
 *    `ELAPSED_REALTIME_WAKEUP` firing [SessionAlarmReceiver], with the wake lock released in between
 *    and re-acquired briefly around each shot, so a long-interval session does not hold the CPU awake
 *    for hours.
 *
 * No thread is ever slept or blocked: waits are handler posts or alarms, including the two polling
 * waits — the adaptive long-exposure wait before a tap and the capture verification after it.
 *
 * ### Guard rails
 * A gesture is only ever dispatched with proof that it can land: the screen is interactive, the
 * keyguard is not up, and the app in front is the one the user chose. None of those is inferred —
 * "unknown" always means "do not tap". When any of them fails the engine pauses itself with a
 * warning and a reason, and [scheduleGuardTick] lifts that pause on its own as soon as the condition
 * clears. The same tick is where stop conditions are evaluated, so a session that is waiting, paused
 * or between two half-hour shots still ends when the battery, the space or the clock runs out.
 *
 * ### What counts as a shot
 * A tap is not a shot. With `verifyCaptures` on, a shot counts as taken only once its verification has
 * resolved — the frame was seen, it could not be judged at all, or the window expired twice and the
 * session gave up with the "capture unverified" warning. Only then may a stop condition complete the
 * session on it, and the single re-tap never counts a second time. With verification off — or on a
 * shot whose baseline could not be taken, see [VerifyArming] — there is nothing to wait for and the
 * tap landing is the shot. [countShot] is the only place the counter moves; [PendingShot] holds the
 * tap in between, and [VerifyStep.resolvesShot] decides when a shot held back for verification may
 * reach it.
 *
 * ### Threading
 * All internal work runs on the main looper. The public entry points ([start], [stop], [pause],
 * [resume]) may be called from any thread — they only touch the [Handler], the [AlarmManager] and
 * [SessionRepository], all of which are safe, and the handler's own queue lock publishes the field
 * writes to the main thread that later runs the posted work. They deliberately do *not* re-post
 * themselves, so a caller that stops a session and then inspects [SessionRepository] sees the result
 * immediately.
 */
class SessionEngine(
    private val service: AccessibilityService,
    private val dispatcher: ShutterDispatcher = ShutterDispatcher(service),
    private val battery: BatteryMonitor = BatteryMonitor(service),
    private val storage: StorageMonitor = StorageMonitor(service),
    private val verifier: CaptureVerifier = CaptureVerifier(service),
    private val settings: SettingsStore = SettingsStore(service),
    private val runState: RunStateStore = RunStateStore(service),
    private val motion: MotionSource = MotionMonitor(service),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val handler = Handler(Looper.getMainLooper())
    private val shotToken = Any()
    private val postShotToken = Any()
    private val busyWaitToken = Any()
    private val watchdogToken = Any()
    private val verifyToken = Any()
    private val setupToken = Any()

    /** Deliberately *not* cancelled by [cancelTimers]: the guard must keep ticking while paused. */
    private val guardToken = Any()

    private val powerManager: PowerManager? = service.getSystemService(PowerManager::class.java)
    private val keyguardManager: KeyguardManager? = service.getSystemService(KeyguardManager::class.java)
    private val alarmManager: AlarmManager? = service.getSystemService(AlarmManager::class.java)
    private val wakeLock: PowerManager.WakeLock? = runCatching {
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private var config: SessionConfig? = null
    private var advanced: AdvancedSettings = AdvancedSettings()
    private var schedule: IntervalSchedule? = null
    private var planner: SessionPlanner? = null

    /** Immovable: what the user asked for, used for elapsed time and stop conditions. */
    private var sessionOriginElapsed = 0L

    /** Timing anchor for [IntervalSchedule]; only moves when a shot overran (see [resolveTarget]). */
    private var scheduleAnchorElapsed = 0L
    private var firstShotElapsed: Long? = null

    private var shotsTaken = 0

    /**
     * Shot slots consumed so far, whether or not the tap landed. The schedule is indexed by this and
     * not by [shotsTaken], so a tap that could not be delivered still spends its slot: the next
     * attempt happens one interval later instead of immediately at a target that is already in the
     * past. On a session where every tap lands the two counters are identical.
     */
    private var attemptedShots = 0
    private var nextShotIndex = 0
    private var running = false
    private var paused = false
    private var preShotSetupDone = false

    /** The pre-shot setup is still clicking around the camera app; the first shot waits for it. */
    private var preShotSetupRunning = false

    /** What the first shot does once a running setup finishes; see [onShotDue]. */
    private var afterPreShotSetup: (() -> Unit)? = null

    /** When the camera mode was switched; the first shot is held [SessionTiming.MODE_SETTLE_MS] past it. */
    private var modeSwitchedAtElapsed: Long? = null

    /**
     * `intervalAfterCapture`: the MediaStore baseline of the capture in flight (null without access),
     * when its latest tap was dispatched, since when the camera has looked idle, and when the capture
     * ended — which is what the next shot is scheduled from.
     */
    private var captureBaseline: String? = null
    private var captureTapElapsed = 0L
    private var captureIdleSinceElapsed: Long? = null
    private var captureEndedElapsed: Long? = null

    /** Why the engine paused itself, or null when the pause came from the user (never auto-resumed). */
    private var autoPauseReason: AutoPauseReason? = null

    /** When the last non-retry tap was dispatched; the basis of the *achieved* interval. */
    private var lastShotFiredElapsed: Long? = null

    private var calibratedTarget: TapTarget? = null
    private var calibratedRotation: Int = Surface.ROTATION_0

    private var verifyBaseline: String? = null
    private var verifyArmed = false

    /**
     * The tap of the shot in flight, from its dispatch until [commitPendingShot] counts the shot.
     *
     * A verified shot only counts as *taken* once its verification has resolved: confirmed, or
     * retried once and given up on with the "capture unverified" warning. Counting it in
     * [onShutterFired] instead let the guard tick see the last shot of a `maxShots` session as done
     * while the engine was still polling MediaStore for its frame — the session completed,
     * [cancelTimers] dropped the pending poll, and the re-tap the engine owed never happened. With
     * `maxShots = 1` that swallowed the single documented retry outright. See [PendingShot] for the
     * two invariants it keeps and for why it is opened at the dispatch rather than at the result.
     */
    private val pendingShot = PendingShot()

    /** Absolute instant a capture must have shown up in MediaStore by; see [verifyWindowMs]. */
    private var verifyDeadlineElapsed = 0L
    private var transientWarning = false
    private var awaitingShutterResult = false

    /** Whether [motion] is listening; see [syncMotionWatch]. */
    private var motionWatching = false

    /**
     * The shot currently between [onShotDue] and [finishShot], or null when none is in flight.
     *
     * Two things can ask for the same shot at almost the same moment: [restore] schedules the shot the
     * stored plan was waiting for, and the very alarm that restarted the process is still being
     * delivered by [SessionAlarmReceiver] a few milliseconds later. Without this the camera would be
     * tapped twice for one slot. [onAlarm]'s stale-index check catches the case where the shot has
     * already finished; this catches the case where it has not.
     */
    private var shotInFlightIndex: Int? = null

    /** Which mechanism pressed the shutter last; for logs and for the UI to surface if it wants to. */
    var lastTapOutcome: TapOutcome? = null
        private set

    /** Profile in force for the current (or last) session. */
    var profile: CameraProfile = CameraProfiles.generic
        private set

    val isActive: Boolean get() = running

    // ---- lifecycle -----------------------------------------------------------------------------

    /** Starts a session with an explicit config. Any previous session is dropped. */
    fun start(config: SessionConfig, advanced: AdvancedSettings = AdvancedSettings()) {
        cancelTimers()
        handler.removeCallbacksAndMessages(guardToken)
        val mode = config.mode

        // Only a time-lapse is driving a camera app. A periodic tap is a metronome for whatever the
        // user aims at, so it gets the passthrough profile: no capture latency, no busy patterns, no
        // 900 ms camera floor standing between the user and a fast tap.
        profile = CameraProfiles.forTrigger(mode)

        // Verification must never accept a photo some other app wrote as proof that our tap worked,
        // and only the verifier can judge that — so it is told whose rows count. A periodic tap has
        // no camera app to name, which is exactly the null case the verifier documents.
        verifier.expectedPackage = (mode as? TriggerMode.Timelapse)?.targetPackage

        // Nothing taps an app that is not on screen: bring the chosen app up before the start delay
        // runs out, and give the launch a moment to settle when the user asked for no delay at all.
        // Driven by the *same* package the shot path refuses to tap without ([foregroundPackage]) —
        // launching only for a time-lapse left a periodic tap that named an app auto-paused on shot
        // 0 with "not the app in front", taking nothing until the user switched apps by hand.
        val wantedPackage = SessionTiming.foregroundPackageFor(mode)
        val launched = wantedPackage != null && launchTargetApp(wantedPackage)

        // The floor is the camera app's for a time-lapse (an app that needs 1.5 s cannot be driven
        // faster without dropping frames) and one synthetic tap for a periodic tap.
        val effective = config.copy(
            minIntervalFloorMs = CameraProfiles.effectiveFloorMs(mode, config.minIntervalFloorMs),
            startDelayMs = SessionTiming.startDelayMs(config.startDelayMs, launched),
        )
        this.config = effective
        this.advanced = advanced
        schedule = IntervalSchedule(effective)
        planner = SessionPlanner(effective)

        val now = SystemClock.elapsedRealtime()
        sessionOriginElapsed = now
        scheduleAnchorElapsed = now
        firstShotElapsed = null
        shotsTaken = 0
        attemptedShots = 0
        nextShotIndex = 0
        running = true
        paused = false
        autoPauseReason = null
        lastShotFiredElapsed = null
        preShotSetupDone = false
        preShotSetupRunning = false
        afterPreShotSetup = null
        modeSwitchedAtElapsed = null
        clearCaptureWait()
        verifyArmed = false
        verifyBaseline = null
        verifyDeadlineElapsed = 0L
        pendingShot.clear()
        lastTapOutcome = null

        calibratedTarget = effective.target
        calibratedRotation = Surface.ROTATION_0

        // The whole plan goes to disk before the first shot is even scheduled: from here on the run
        // can be rebuilt by [restore] if the process is reclaimed while an alarm is pending.
        runState.beginRun(
            RunPlan(
                config = effective,
                advanced = advanced,
                sessionOriginElapsed = now,
                calibratedTarget = calibratedTarget,
                calibratedRotation = calibratedRotation,
            ),
            currentProgress(),
        )

        SessionRepository.update {
            SessionSnapshot(
                state = SessionState.WaitingToStart(now + effective.startDelayMs),
                shotsTaken = 0,
                shotsPlanned = effective.stop.maxShots,
                nextShotAtElapsed = now + effective.startDelayMs,
                lastIntervalMs = effective.intervalMs,
                warning = null,
            )
        }

        Log.i(
            TAG,
            "session start profile=${profile.id} interval=${effective.intervalMs}ms " +
                "delay=${effective.startDelayMs}ms mode=${effective.mode}",
        )

        // A stored per-rotation calibration is the fallback when the config carries none. The read is
        // suspending, so it lands shortly after the session anchor is already fixed — which is exactly
        // what we want: the load latency cannot move shot 0's absolute target.
        if (calibratedTarget == null) scope.launch { loadStoredTarget(effective) }

        syncMotionWatch()
        scheduleGuardTick()
        scheduleShot(0)
        scheduleEarlySetup(now)
    }

    /**
     * Brings the target app to the front — the camera app of a time-lapse, or whatever app a periodic
     * tap named in the picker. Without this, Start left CAELIS itself in front and the very
     * first shot auto-paused with "not the app in front".
     *
     * An app that is *already* in front is left strictly alone. Its launcher intent would otherwise
     * be resolved and started anyway, which re-enters the app at its launcher activity — for a camera
     * app that is a pointless re-init of the preview, and for a package whose launcher entry is not
     * the screen the user is looking at (a test harness, a multi-activity app) it actively replaces
     * the very screen the calibrated point was measured against.
     *
     * Failure is not fatal and does not warn: the shot path already pauses with the right message
     * when the app is not in front, and the user putting the camera app up by hand resumes it.
     */
    private fun launchTargetApp(packageName: String): Boolean {
        if (isForeground(packageName)) {
            Log.i(TAG, "$packageName is already in front, not restarting it")
            return false
        }
        val intent = runCatching { service.packageManager.getLaunchIntentForPackage(packageName) }
            .getOrNull()
        if (intent == null) {
            Log.w(TAG, "no launch intent for $packageName, leaving the foreground alone")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val started = runCatching { service.startActivity(intent) }.isSuccess
        Log.i(TAG, if (started) "launched $packageName" else "could not launch $packageName")
        return started
    }

    /**
     * Rebuilds the session this device was running when the process went away, and returns true when
     * it did. Called once, as the accessibility service connects, *before* the earlier run is written
     * off as [StopReason.SERVICE_LOST].
     *
     * A long-interval session waits on an alarm with no wake lock held, which is precisely when the
     * OS reclaims the process. Everything needed to carry on is in [RunStateStore]: the plan, the
     * counters, and — crucially — the original [scheduleAnchorElapsed], so the resumed series keeps
     * the cadence it had rather than restarting from now. [RunStateStore.liveRun] already refuses a
     * record from before the current boot, where those elapsed-realtime anchors mean nothing.
     *
     * Returns false (changing nothing at all) when there is no live run, when the plan cannot be
     * decoded, or when the run ended as a service loss — a lost run is terminal by design.
     */
    fun restore(): Boolean {
        val live = runState.liveRun() ?: return false
        val plan = live.plan
        val progress = live.progress
        val effective = plan.config

        cancelTimers()
        handler.removeCallbacksAndMessages(guardToken)

        config = effective
        advanced = plan.advanced
        profile = CameraProfiles.forTrigger(effective.mode)
        schedule = IntervalSchedule(effective)
        planner = SessionPlanner(effective)
        verifier.expectedPackage = (effective.mode as? TriggerMode.Timelapse)?.targetPackage

        sessionOriginElapsed = plan.sessionOriginElapsed
        scheduleAnchorElapsed = progress.scheduleAnchorElapsed
        firstShotElapsed = progress.firstShotElapsed
        shotsTaken = progress.shotsTaken
        attemptedShots = progress.attemptedShots
        nextShotIndex = progress.nextShotIndex
        calibratedTarget = plan.calibratedTarget
        calibratedRotation = plan.calibratedRotation

        running = true
        paused = progress.paused
        // The mode chip and the self timer were already dealt with before the process died; running
        // them again would tap around the camera UI for nothing.
        preShotSetupDone = true
        preShotSetupRunning = false
        afterPreShotSetup = null
        modeSwitchedAtElapsed = null
        clearCaptureWait()
        autoPauseReason = null
        // Nothing was measured in *this* process, so the first reported interval is the planned one
        // rather than a bogus gap spanning however long the process was gone.
        lastShotFiredElapsed = null
        verifyArmed = false
        verifyBaseline = null
        verifyDeadlineElapsed = 0L
        pendingShot.clear()
        transientWarning = false
        awaitingShutterResult = false
        lastTapOutcome = null

        SessionRepository.update {
            SessionSnapshot(
                state = if (paused) SessionState.Paused else SessionState.Running,
                shotsTaken = shotsTaken,
                shotsPlanned = effective.stop.maxShots,
                nextShotAtElapsed = null,
                lastIntervalMs = effective.intervalMs,
                warning = null,
            )
        }
        Log.i(
            TAG,
            "restored session profile=${profile.id} shots=$shotsTaken next=$nextShotIndex " +
                "paused=$paused mode=${effective.mode}",
        )

        // The target app is a separate process that may have been reclaimed alongside ours. Putting
        // it back in front is the same thing [start] does — for whichever mode named an app, because
        // the shot path guards on both — and without it the resumed session would only ever
        // auto-pause with "not the app in front".
        val wantedPackage = SessionTiming.foregroundPackageFor(effective.mode)
        if (!paused && wantedPackage != null) launchTargetApp(wantedPackage)

        syncMotionWatch()
        scheduleGuardTick()
        if (!paused) scheduleShot(nextShotIndex)
        return true
    }

    /** The moving half of the run, as [RunStateStore] wants it. */
    private fun currentProgress(): RunProgress = RunProgress(
        shotsTaken = shotsTaken,
        attemptedShots = attemptedShots,
        nextShotIndex = nextShotIndex,
        scheduleAnchorElapsed = scheduleAnchorElapsed,
        firstShotElapsed = firstShotElapsed,
        paused = paused,
    )

    /** Starts using the last config the UI saved. No-op when nothing has been saved yet. */
    fun startFromStoredSettings() {
        scope.launch {
            val stored = settings.lastConfig.first()
            if (stored == null) {
                Log.w(TAG, "no stored config to start from")
                return@launch
            }
            start(stored, settings.advanced.first())
        }
    }

    fun stop(reason: StopReason = StopReason.USER, message: String? = null) {
        if (!running && SessionRepository.snapshot.value.state is SessionState.Stopped) return
        // A shot whose verification was still open is a photo that exists: count it before the final
        // snapshot, or a session stopped mid-verification would under-report by one.
        commitPendingShot()
        running = false
        paused = false
        autoPauseReason = null
        cancelTimers()
        handler.removeCallbacksAndMessages(guardToken)
        releaseWakeLock()
        if (reason == StopReason.SERVICE_LOST) runState.markServiceLost() else runState.markStopped()
        Log.i(TAG, "session stop reason=$reason shots=$shotsTaken")
        SessionRepository.update {
            it.copy(
                state = SessionState.Stopped(reason, message),
                nextShotAtElapsed = null,
            )
        }
        syncMotionWatch()
    }

    fun pause() {
        if (!running || paused) return
        // Pausing abandons the verification poll for the shot in flight; its tap still happened.
        commitPendingShot()
        paused = true
        // A pause the user asked for is never undone by the guard tick.
        autoPauseReason = null
        cancelTimers()
        releaseWakeLock()
        runState.recordProgress(currentProgress())
        SessionRepository.update { it.copy(state = SessionState.Paused, nextShotAtElapsed = null) }
        syncMotionWatch()
    }

    /**
     * Resumes from [SessionState.Paused]. The missed absolute targets are in the past, so
     * [resolveTarget] re-anchors and the next shot fires right away, then the cadence continues from
     * there instead of firing a burst of catch-up shots.
     */
    fun resume() {
        if (!running || !paused) return
        paused = false
        autoPauseReason = null
        runState.recordProgress(currentProgress())
        SessionRepository.update { it.copy(state = SessionState.Running, warning = null) }
        syncMotionWatch()
        scheduleGuardTick()
        scheduleShot(attemptedShots)
    }

    /** Called from [SessionAlarmReceiver] on the main thread. */
    fun onAlarm(shotIndex: Int) {
        if (!running || paused) return
        if (shotIndex >= 0 && shotIndex != nextShotIndex) {
            Log.w(TAG, "stale alarm for shot $shotIndex, expecting $nextShotIndex")
            return
        }
        onShotDue(nextShotIndex)
    }

    /**
     * Foreground app changed. Used only to auto-resume a session the engine paused itself; a running
     * session re-checks the foreground app at every shot anyway, and a pause the user asked for is
     * never undone here. The periodic guard tick does the same check, so a session cannot stay parked
     * just because no window event happened to arrive.
     */
    fun onForegroundPackageChanged(packageName: String?) {
        if (!running || !paused || autoPauseReason == null) return
        val wanted = config?.mode?.let { SessionTiming.foregroundPackageFor(it) }
        if (wanted != null && packageName != wanted) return
        tryAutoResume()
    }

    /**
     * Which application window is in front right now, as the dispatcher sees it.
     *
     * Not the same thing as the package on an accessibility event: our own floating panel is a window
     * of ours too, so adding or moving it fires `TYPE_WINDOW_STATE_CHANGED` with *our* package while the
     * camera app is still the app in front. Anything deciding "is our UI on screen" has to ask this
     * instead of trusting the event, or the panel hides itself the moment it appears.
     */
    fun foregroundApplicationPackage(): String? = dispatcher.foregroundPackage()

    /** The service is going away mid-run: record it so the UI can explain the stop after reconnect. */
    fun reportServiceLost() {
        if (!running) return
        stop(StopReason.SERVICE_LOST, service.getString(R.string.session_stopped_service_lost))
    }

    fun dispose() {
        cancelTimers()
        handler.removeCallbacksAndMessages(guardToken)
        releaseWakeLock()
        motionWatching = false
        motion.stop()
        scope.cancel()
    }

    // ---- guard tick ----------------------------------------------------------------------------

    /**
     * Stop conditions used to be looked at only after a shot, so a 30-minute interval overran
     * `maxDurationMs` by a whole interval, and a session parked in [SessionState.WaitingToStart] or
     * [SessionState.Paused] never noticed the battery or the free space running out at all. The
     * guard tick evaluates them in every state where the session is alive, and it is also the thing
     * that lifts an automatic pause once the screen and the foreground app are usable again.
     *
     * It runs on the handler, so it does not fire while the device sleeps between two alarm-spaced
     * shots — nothing needs it there, because the alarm's own shot path re-checks everything.
     */
    private fun scheduleGuardTick() {
        handler.removeCallbacksAndMessages(guardToken)
        if (!running) return
        handler.postAtTime(
            { onGuardTick() },
            guardToken,
            SystemClock.uptimeMillis() + GUARD_TICK_MS,
        )
    }

    private fun onGuardTick() {
        if (!running) return
        if (evaluateStopConditions()) return
        tryAutoResume()
        scheduleGuardTick()
    }

    /**
     * Asks [SessionPlanner] whether the session should end now. Returns true when it stopped it, so
     * every caller can bail out immediately.
     */
    private fun evaluateStopConditions(): Boolean {
        if (!running) return false
        val planner = planner ?: return false
        if (!SessionTiming.isAlive(SessionRepository.snapshot.value.state)) return false
        val reason = planner.stopReasonFor(
            shotsTaken = shotsTaken,
            elapsedMs = SystemClock.elapsedRealtime() - sessionOriginElapsed,
            batteryPercent = battery.percent(),
            freeBytes = storage.freeBytes(),
        ) ?: return false
        stop(reason)
        return true
    }

    /** Lifts an automatic pause the moment the screen and the foreground app allow tapping again. */
    private fun tryAutoResume() {
        if (!running || !paused) return
        val reason = autoPauseReason ?: return
        if (!isScreenLive()) return
        val wanted = config?.mode?.let { SessionTiming.foregroundPackageFor(it) }
        if (wanted != null && !isForeground(wanted)) return
        Log.i(TAG, "resuming, $reason cleared")
        resume()
    }

    // ---- scheduling ----------------------------------------------------------------------------

    private fun scheduleShot(index: Int) {
        val schedule = schedule ?: return
        if (!running || paused) return

        nextShotIndex = index
        val target = resolveTarget(schedule, index)

        // Written *before* the wait, not after it: a long gap hands the CPU back to the OS, which is
        // exactly when the process gets reclaimed, and the anchor plus this index are what let
        // [restore] pick the series up at the right shot instead of starting it over.
        runState.recordProgress(currentProgress())

        val now = SystemClock.elapsedRealtime()
        val gap = target - now
        val nominalInterval = schedule.intervalForShot(
            (index - 1).coerceAtLeast(0),
            elapsedSinceFirstShot(now),
        )

        // What the user will actually see between the last tap and the next one, which is not the
        // requested interval whenever the series had to re-anchor. Reporting the request here is how
        // the UI came to claim a 1 s cadence a camera app was never going to deliver.
        val achievedInterval =
            SessionTiming.achievedIntervalMs(target, lastShotFiredElapsed, nominalInterval)

        SessionRepository.update {
            it.copy(
                state = if (index == 0) it.state else SessionState.Running,
                nextShotAtElapsed = target,
                lastIntervalMs = achievedInterval,
            )
        }

        if (gap < HANDLER_GAP_LIMIT_MS) {
            // Short gap: stay awake and post to the absolute instant.
            cancelAlarm()
            acquireWakeLock(gap.coerceAtLeast(0L) + WAKE_LOCK_SLACK_MS)
            handler.removeCallbacksAndMessages(shotToken)
            handler.postAtTime({ onShotDue(index) }, shotToken, uptimeFor(target))
        } else {
            // Long gap: hand the wait to the OS and stop holding the CPU.
            handler.removeCallbacksAndMessages(shotToken)
            scheduleAlarm(index, target)
            releaseWakeLock()
        }
    }

    /**
     * The absolute instant shot [index] should fire at.
     *
     * Normally this is purely a function of the session anchor, which is what keeps the series
     * drift-free. If the target is already in the past by more than [LATE_TOLERANCE_MS] — a shot
     * overran, an adaptive long-exposure wait ran long, or the session was paused — the anchor is
     * shifted by exactly that lateness. The overrun is absorbed once instead of turning into a burst
     * of catch-up shots, and the shots after it are again spaced by the requested interval.
     */
    private fun resolveTarget(schedule: IntervalSchedule, index: Int): Long {
        val target = schedule.targetElapsedFor(index, scheduleAnchorElapsed)
        val now = SystemClock.elapsedRealtime()
        if (now - target <= LATE_TOLERANCE_MS) return target
        val lateBy = now - target
        scheduleAnchorElapsed += lateBy
        Log.i(TAG, "shot $index was ${lateBy}ms late, re-anchoring the series")
        return schedule.targetElapsedFor(index, scheduleAnchorElapsed)
    }

    private fun scheduleAlarm(index: Int, targetElapsed: Long) {
        val alarmManager = alarmManager
        val pending = SessionAlarmReceiver.pendingIntent(service, index)
        val exact = alarmManager != null && runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                targetElapsed,
                pending,
            )
        }.isSuccess
        if (!exact) {
            // Exact alarms revoked: still shoot, but say that the timing may slip.
            val loose = alarmManager != null && runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    targetElapsed,
                    pending,
                )
            }.isSuccess
            warn(service.getString(R.string.session_warning_inexact_alarms))
            if (!loose) {
                // No alarm at all: fall back to the handler so the session does not silently die.
                handler.postAtTime({ onShotDue(index) }, shotToken, uptimeFor(targetElapsed))
                return
            }
        }
    }

    /**
     * Unconditional on purpose: an alarm from a run that ended with the process being killed would
     * otherwise survive into the next session and fire a stray shot.
     */
    private fun cancelAlarm() {
        SessionAlarmReceiver.cancel(service)
    }

    private fun cancelTimers() {
        handler.removeCallbacksAndMessages(shotToken)
        handler.removeCallbacksAndMessages(postShotToken)
        handler.removeCallbacksAndMessages(busyWaitToken)
        handler.removeCallbacksAndMessages(watchdogToken)
        handler.removeCallbacksAndMessages(verifyToken)
        handler.removeCallbacksAndMessages(setupToken)
        // A setup cut short must not leave the first shot parked behind it for good.
        preShotSetupRunning = false
        afterPreShotSetup = null
        awaitingShutterResult = false
        shotInFlightIndex = null
        cancelAlarm()
    }

    /** Absolute uptime instant matching an absolute elapsed-realtime instant. */
    private fun uptimeFor(targetElapsed: Long): Long {
        val delta = targetElapsed - SystemClock.elapsedRealtime()
        return SystemClock.uptimeMillis() + delta.coerceAtLeast(0L)
    }

    // ---- one shot ------------------------------------------------------------------------------

    private fun onShotDue(index: Int) {
        if (!running || paused) return
        val config = config ?: return
        if (shotInFlightIndex == index) {
            Log.w(TAG, "shot $index is already in flight, ignoring the duplicate trigger")
            return
        }
        acquireWakeLock(SHOT_WAKE_LOCK_MS)

        // A session that should already have ended must not dispatch one more shot.
        if (evaluateStopConditions()) return

        if (!isScreenLive()) {
            pauseForScreenOff()
            return
        }

        val wanted = SessionTiming.foregroundPackageFor(config.mode)
        if (wanted != null && !isForeground(wanted)) {
            // Tapping now would press whatever the other app has under that pixel.
            pauseForForeground(wanted)
            return
        }

        // A warning about the previous shot has had its whole interval on screen by now.
        clearTransientWarning()
        SessionRepository.update { it.copy(state = SessionState.Running) }

        // Claimed only once every guard has passed: an early return above leaves the slot free so the
        // resume path can legitimately ask for the same index again.
        shotInFlightIndex = index

        val fire = { waitForCameraThenFire(index, SystemClock.elapsedRealtime()) }
        when {
            // The early setup (see [scheduleEarlySetup]) is still switching the mode: tapping the
            // shutter now would land in the middle of it.
            preShotSetupRunning -> afterPreShotSetup = fire
            !preShotSetupDone -> {
                handler.removeCallbacksAndMessages(setupToken)
                startPreShotSetup(fireAfter = fire)
            }
            else -> fire()
        }
    }

    /**
     * Switches the camera mode (and the self timer) as soon as the camera app is in front after
     * Start, instead of right before the first shot: Pixel Camera needs a few seconds after a switch
     * to Night Sight before Astro engages, and a mode switched at the shutter press had none.
     * Polled because the app launched by [start] takes a moment to come up; [onShotDue] runs the
     * setup itself if the first shot comes first.
     */
    private fun scheduleEarlySetup(startedAt: Long) {
        handler.removeCallbacksAndMessages(setupToken)
        if (!running || paused || preShotSetupDone || preShotSetupRunning) return
        if (!SessionTiming.hasPreShotSetup(advanced)) {
            preShotSetupDone = true
            return
        }
        val wanted = config?.mode?.let { SessionTiming.foregroundPackageFor(it) }
        if (wanted != null && isForeground(wanted)) {
            startPreShotSetup(fireAfter = null)
            return
        }
        if (SystemClock.elapsedRealtime() - startedAt > EARLY_SETUP_GIVE_UP_MS) return
        handler.postAtTime({ scheduleEarlySetup(startedAt) }, setupToken, SystemClock.uptimeMillis() + SETUP_POLL_MS)
    }

    private fun startPreShotSetup(fireAfter: (() -> Unit)?) {
        preShotSetupDone = true
        preShotSetupRunning = true
        afterPreShotSetup = fireAfter
        runPreShotSetup {
            preShotSetupRunning = false
            val next = afterPreShotSetup
            afterPreShotSetup = null
            next?.invoke()
        }
    }

    /**
     * Holds the tap while the camera cannot take it: a freshly switched mode still settling, or (with
     * `intervalAfterCapture`) a camera that is still busy. Tapping a busy camera is not harmless —
     * Pixel Camera's shutter reads "Zrušit fotku" during Astro, and a tap cancels the photo.
     */
    private fun waitForCameraThenFire(index: Int, waitStartedAt: Long) {
        if (!running || paused) return
        val now = SystemClock.elapsedRealtime()
        val settle = SessionTiming.modeSettleHoldMs(modeSwitchedAtElapsed, now)
        if (settle > 0L) {
            // The cadence is kept: the whole series moves by the hold instead of shot 1 coming early.
            scheduleAnchorElapsed += settle
            modeSwitchedAtElapsed = null
            acquireWakeLock(SHOT_WAKE_LOCK_MS)
            handler.removeCallbacksAndMessages(busyWaitToken)
            handler.postAtTime(
                { waitForCameraThenFire(index, waitStartedAt + settle) },
                busyWaitToken,
                SystemClock.uptimeMillis() + settle,
            )
            return
        }
        modeSwitchedAtElapsed = null
        if (waitsForCapture() && dispatcher.isCameraBusy(profile)) {
            if (now - waitStartedAt < SessionTiming.CAPTURE_MAX_WAIT_MS) {
                acquireWakeLock(SHOT_WAKE_LOCK_MS)
                handler.removeCallbacksAndMessages(busyWaitToken)
                handler.postAtTime(
                    { waitForCameraThenFire(index, waitStartedAt) },
                    busyWaitToken,
                    SystemClock.uptimeMillis() + BUSY_POLL_MS,
                )
                return
            }
            warn(service.getString(R.string.session_warning_long_exposure_timeout), transient = true)
        }
        fireShutter(index, isRetry = false)
    }

    /** `intervalAfterCapture` applies to a time-lapse only: a periodic tap drives no camera. */
    private fun waitsForCapture(): Boolean =
        advanced.intervalAfterCapture && config?.mode is TriggerMode.Timelapse

    private fun clearCaptureWait() {
        captureBaseline = null
        captureTapElapsed = 0L
        captureIdleSinceElapsed = null
        captureEndedElapsed = null
    }

    private fun fireShutter(index: Int, isRetry: Boolean) {
        val config = config ?: return
        // The busy wait and the verification retry both land here well after onShotDue checked, and
        // the user can press power at any point in between: re-check before every single gesture.
        if (!isScreenLive()) {
            pauseForScreenOff()
            return
        }
        if (!isRetry) {
            val firedAt = SystemClock.elapsedRealtime()
            lastShotFiredElapsed = firedAt

            // A baseline the verifier could not take is *not* a failed verification. hasNewImageSince
            // answers "cannot confirm" for a null baseline by contract, so arming anyway made every
            // shot walk KEEP_POLLING -> RETRY -> GIVE_UP: two taps per frame, a whole verification
            // window twice over, and a "no new photo" warning on a camera that works perfectly.
            // Without a baseline this shot is simply not verified, and the warning says exactly that.
            val requested = config.verifyCaptures
            // `intervalAfterCapture` needs the baseline too, to see when the photo is finished; only
            // `verifyCaptures` turns a missing photo into a retry and a deferred count.
            val readable = (requested || waitsForCapture()) && verifier.available()
            val baseline = if (readable) verifier.newestToken() else null
            val arming = SessionTiming.verifyArming(
                requested,
                verifiable = requested && readable,
                baseline = baseline,
            )
            verifyArmed = arming.armsVerification
            verifyBaseline = if (arming.armsVerification) baseline else null
            captureBaseline = if (waitsForCapture()) baseline else null
            captureEndedElapsed = null
            if (arming.warnsUnverified) {
                warn(service.getString(R.string.session_warning_verification_unavailable), transient = true)
            }

            // Recorded here, where the tap is actually dispatched, and not in the result callback: a
            // callback that never arrives runs the watchdog instead, and MediaStore may still confirm
            // that very frame. With the pending shot opened in the callback, such a frame existed and
            // was never counted.
            pendingShot.onTapDispatched(isRetry = false, verifyArmed = verifyArmed, firedElapsed = firedAt)
        } else {
            // The retry re-opens the shot if — and only if — the first tap failed to be delivered and
            // dropped it. PendingShot enforces the "only if"; this is what gives it the chance.
            pendingShot.onTapDispatched(
                isRetry = true,
                verifyArmed = verifyArmed,
                firedElapsed = SystemClock.elapsedRealtime(),
            )
        }
        // Each tap gets its own window: the retry's frame is no faster to land than the first one's.
        if (verifyArmed) verifyDeadlineElapsed = SystemClock.elapsedRealtime() + verifyWindowMs()
        captureTapElapsed = SystemClock.elapsedRealtime()
        captureIdleSinceElapsed = null
        val request = ShutterRequest(
            profile = profile,
            calibrated = calibratedTarget,
            capturedRotation = calibratedRotation,
            // PeriodicTap is not driving a known camera app: the calibrated point is the whole truth.
            gestureOnly = config.mode is TriggerMode.PeriodicTap,
        )
        awaitingShutterResult = true
        armGestureWatchdog(index, isRetry)
        dispatcher.fireShutter(request) { outcome -> onShutterFired(index, isRetry, outcome) }
    }

    /**
     * A gesture the platform accepts always completes or cancels, but a session that lost its result
     * callback would otherwise wait for it forever. The watchdog keeps the series alive instead.
     */
    private fun armGestureWatchdog(index: Int, isRetry: Boolean) {
        handler.removeCallbacksAndMessages(watchdogToken)
        handler.postAtTime(
            {
                if (!awaitingShutterResult || !running || paused) return@postAtTime
                awaitingShutterResult = false
                Log.w(TAG, "no gesture result for shot $index, carrying on")
                warn(service.getString(R.string.session_warning_tap_failed), transient = true)
                afterCapture(index, isRetry)
            },
            watchdogToken,
            SystemClock.uptimeMillis() + ShutterDispatcher.TAP_DURATION_MS + GESTURE_TIMEOUT_MS,
        )
    }

    private fun onShutterFired(index: Int, isRetry: Boolean, outcome: TapOutcome) {
        if (!awaitingShutterResult) return // the watchdog already moved the session on
        awaitingShutterResult = false
        handler.removeCallbacksAndMessages(watchdogToken)
        if (!running || paused) return
        lastTapOutcome = outcome
        Log.i(TAG, "shot $index${if (isRetry) " (retry)" else ""}: $outcome")

        if (!outcome.fired) {
            warn(
                when (outcome.failure) {
                    ShutterDispatcher.FAILURE_NO_TARGET ->
                        service.getString(R.string.session_warning_no_shutter_found)
                    else -> service.getString(R.string.session_warning_tap_failed)
                },
                transient = true,
            )
            // The gesture never reached the app, so no frame is coming: the pending shot this tap
            // opened is dropped rather than counted as a photo that does not exist. A failed *retry*
            // drops nothing — the pending shot belongs to the first tap, whose frame may well be
            // there, and giving up on it still counts that shot honestly.
            pendingShot.onTapFailed(isRetry)
        } else if (!isRetry && !verifyArmed) {
            // Nothing is being verified, so the tap landing *is* the shot being taken. Deferring it
            // here too would only make the count lag the camera by the post-capture hold. A verified
            // shot is already pending from the dispatch and is counted by [commitPendingShot]; a
            // retry re-taps the same frame and must never count a second time.
            countShot(SystemClock.elapsedRealtime())
        }

        handler.removeCallbacksAndMessages(postShotToken)
        handler.postAtTime(
            { afterCapture(index, isRetry) },
            postShotToken,
            SystemClock.uptimeMillis() + postCaptureHoldMs(),
        )
    }

    /**
     * How long to let the camera app write the frame before looking at its UI again.
     *
     * The hold is only ever as long as the gap to the next shot: holding past it would push the next
     * shot late, [resolveTarget] would re-anchor the whole series on every single shot, and the
     * session would silently run at "capture latency" instead of at the interval the user asked for.
     * A profile's floor now guarantees the gap is long enough (see [CameraProfile.minIntervalMs]);
     * this clamp is what keeps a ramp or a re-anchor from breaking that guarantee again.
     */
    private fun postCaptureHoldMs(): Long {
        // The next shot is scheduled from the end of this capture anyway, so nothing is pushed late.
        if (waitsForCapture()) return profile.captureLatencyMs
        val schedule = schedule ?: return profile.captureLatencyMs
        val nextTarget = schedule.targetElapsedFor(attemptedShots + 1, scheduleAnchorElapsed)
        return SessionTiming.postCaptureHoldMs(
            captureLatencyMs = profile.captureLatencyMs,
            gapToNextTargetMs = nextTarget - SystemClock.elapsedRealtime(),
        )
    }

    /** Runs once the camera has had [CameraProfile.captureLatencyMs] to write the frame. */
    private fun afterCapture(index: Int, isRetry: Boolean) {
        if (!running || paused) return

        if (advanced.dismissPostCaptureUi) {
            val dismissed = dispatcher.dismissPostCaptureUi(profile)
            if (dismissed > 0) Log.d(TAG, "dismissed $dismissed post-capture node(s)")
        }

        if (waitsForCapture()) {
            awaitCaptureEnd(index, isRetry)
            return
        }
        if (!verifyArmed) {
            finishShot()
            return
        }
        awaitCapture(index, isRetry)
    }

    /**
     * `intervalAfterCapture`: waits until the capture is finished — its photo is in MediaStore, or
     * without access the camera is idle again — and only then lets the next shot be scheduled, one
     * interval after that moment ([finishShot]). Polled; [SessionTiming.captureWaitStep] decides.
     * Replaces [awaitCapture] for these sessions, keeping its rules: a missing photo is re-tapped
     * once only with `verifyCaptures` on, and a shot is counted when the wait resolves.
     */
    private fun awaitCaptureEnd(index: Int, isRetry: Boolean) {
        if (!running || paused) return
        val now = SystemClock.elapsedRealtime()
        val busy = dispatcher.isCameraBusy(profile)
        if (busy) {
            captureIdleSinceElapsed = null
        } else if (captureIdleSinceElapsed == null) {
            captureIdleSinceElapsed = now
        }
        val baseline = captureBaseline
        val canSee = baseline != null && verifier.available()
        val step = SessionTiming.captureWaitStep(
            frameVisible = canSee && verifier.hasNewImageSince(baseline),
            canSeeFrames = canSee,
            cameraBusy = busy,
            sinceTapMs = now - captureTapElapsed,
            idleForMs = captureIdleSinceElapsed?.let { now - it } ?: 0L,
        )
        when (step) {
            CaptureWaitStep.WAIT -> {
                // A capture can run for minutes; the CPU must stay up to see it end.
                acquireWakeLock(SHOT_WAKE_LOCK_MS)
                handler.removeCallbacksAndMessages(verifyToken)
                handler.postAtTime(
                    { awaitCaptureEnd(index, isRetry) },
                    verifyToken,
                    SystemClock.uptimeMillis() + VERIFY_POLL_MS,
                )
            }

            CaptureWaitStep.FINISHED -> endCapture(now)

            CaptureWaitStep.TIMED_OUT -> {
                Log.w(TAG, "shot $index still busy after ${now - captureTapElapsed}ms, carrying on")
                warn(service.getString(R.string.session_warning_long_exposure_timeout), transient = true)
                endCapture(now)
            }

            CaptureWaitStep.NO_FRAME -> when {
                verifyArmed && !isRetry -> {
                    Log.w(TAG, "no photo after shot $index although the camera is idle, re-tapping once")
                    fireShutter(index, isRetry = true)
                }
                else -> {
                    if (verifyArmed) {
                        warn(service.getString(R.string.session_warning_capture_unverified), transient = true)
                    }
                    endCapture(now)
                }
            }
        }
    }

    private fun endCapture(endedAt: Long) {
        captureEndedElapsed = endedAt
        if (verifyArmed) resolveShot(VerifyStep.RESOLVED) else finishShot()
    }

    /**
     * Polls MediaStore until the frame shows up or the verification window closes.
     *
     * A camera app publishes its row when it has finished writing the file, which on a Pixel is
     * seconds *past* [CameraProfile.captureLatencyMs] — HDR+ and Night Sight keep processing long
     * after the shutter. Checking exactly once at that instant therefore reports every good capture
     * as missing and re-taps the whole session, which is worse than not verifying at all.
     *
     * Only a window that has fully expired justifies the single re-tap. Waiting for it makes the
     * interval a minimum for that shot, the same trade-off `intervalAfterCapture` makes, and it
     * costs nothing on a shot whose frame lands on time: the poll ends the moment the row appears.
     *
     * available() is re-checked so a permission revoked mid-session degrades silently instead of
     * reporting an unverifiable capture as a missing one.
     *
     * The shot being verified here is *not* counted yet — [commitPendingShot] does that from
     * [finishShot], once one of the two resolving steps is reached. That ordering is the feature, not
     * an accident of structure: a shot counted at the tap made the guard tick complete a `maxShots`
     * session while this poll was still running, [cancelTimers] dropped the poll, and the re-tap the
     * engine owed was silently skipped — with `maxShots = 1` the retry never happened at all.
     *
     * ==== INTEGRATION POINT ===================================================================
     * The only three calls the engine makes into [CaptureVerifier], all of them here and in
     * [fireShutter]:
     *   verifier.available() / verifier.newestToken() / verifier.hasNewImageSince(baseline)
     * The verifier owns the question of *whose* row counts — a photo written by some other app must
     * never be accepted as proof that our tap took a picture, and the engine deliberately holds no
     * opinion about MediaStore beyond the opaque token it is handed. If the persistence slice
     * narrows that API (an owner-filtered token, a package argument, a richer result), the whole
     * adaptation is these three call sites; nothing else in the engine reads MediaStore.
     * ==== END INTEGRATION POINT ===============================================================
     */
    private fun awaitCapture(index: Int, isRetry: Boolean) {
        if (!running || paused) return
        val step = SessionTiming.verifyStep(
            verifiable = verifier.available(),
            frameVisible = verifier.hasNewImageSince(verifyBaseline),
            windowExpired = SystemClock.elapsedRealtime() >= verifyDeadlineElapsed,
            alreadyRetried = isRetry,
        )
        when (step) {
            VerifyStep.RESOLVED -> resolveShot(step)

            VerifyStep.KEEP_POLLING -> {
                handler.removeCallbacksAndMessages(verifyToken)
                handler.postAtTime(
                    { awaitCapture(index, isRetry) },
                    verifyToken,
                    SystemClock.uptimeMillis() + VERIFY_POLL_MS,
                )
            }

            VerifyStep.RETRY -> {
                Log.w(TAG, "no photo within ${verifyWindowMs()}ms of shot $index, re-tapping once")
                fireShutter(index, isRetry = true)
            }

            VerifyStep.GIVE_UP -> {
                // Still nothing: say so, but a mis-detected capture must not end the session.
                warn(service.getString(R.string.session_warning_capture_unverified), transient = true)
                resolveShot(step)
            }
        }
    }

    /**
     * Ends the verification of the shot in flight and hands it to [finishShot], which is where it is
     * finally counted and where the session may complete on it.
     *
     * The [VerifyStep.resolvesShot] gate is the whole ordering rule in one line: a
     * [VerifyStep.RETRY] routed here would count a shot whose re-tap is still owed, complete a
     * `maxShots` session on it, and cancel that re-tap — which is precisely the defect that made a
     * one-shot verified session press the shutter once and never retry. Such a step leaves the shot
     * open instead.
     */
    private fun resolveShot(step: VerifyStep) {
        if (!step.resolvesShot) return
        clearVerification()
        finishShot()
    }

    /**
     * How long after a tap a real capture must have become visible in MediaStore: the app's own busy
     * window plus the grace it needs to publish the row, bounded by the profile's long-exposure
     * ceiling so no profile can stall a session indefinitely.
     */
    private fun verifyWindowMs(): Long =
        (profile.captureLatencyMs + VERIFY_GRACE_MS).coerceAtMost(profile.longExposureMaxWaitMs)

    private fun clearVerification() {
        handler.removeCallbacksAndMessages(verifyToken)
        verifyArmed = false
        verifyBaseline = null
        verifyDeadlineElapsed = 0L
    }

    /**
     * Counts one taken shot, anchoring the ramp on the instant its tap was dispatched rather than on
     * whenever the shot came to be counted. The only place [shotsTaken] grows.
     */
    private fun countShot(firedAtElapsed: Long) {
        shotsTaken++
        if (firstShotElapsed == null) firstShotElapsed = firedAtElapsed
        runState.recordProgress(currentProgress())
        SessionRepository.update { it.copy(shotsTaken = shotsTaken) }
    }

    /**
     * Counts the landed tap that was waiting for its verification, and does nothing when none is
     * waiting — no tap landed, the shot was counted at the tap because nothing was being verified, or
     * it has been counted already.
     *
     * Every caller is a point where the shot has stopped being in doubt: verification resolved, in
     * [finishShot], or the session left the shot path altogether in [stop], [pause] and [autoPause],
     * where a frame that was already taken must still be counted honestly rather than dropped with
     * the poll. A retry never arrives here with a pending tap of its own, so it cannot inflate the
     * count.
     */
    private fun commitPendingShot() {
        countShot(pendingShot.commit() ?: return)
    }

    /**
     * The end of one shot: verification has resolved one way or the other, so the shot may now be
     * counted — and only now may the count complete the session, which is why [commitPendingShot]
     * runs before the stop conditions are looked at.
     */
    private fun finishShot() {
        commitPendingShot()
        shotInFlightIndex = null
        if (evaluateStopConditions()) return
        attemptedShots++
        captureEndedElapsed?.let { ended -> scheduleFromCaptureEnd(attemptedShots, ended) }
        captureEndedElapsed = null
        scheduleShot(attemptedShots)
    }

    /**
     * `intervalAfterCapture`: moves the anchor so shot [index] is one interval after the capture
     * before it ended. The series keeps every other rule of the absolute schedule (a ramp still
     * changes the interval per shot, a restore picks up the stored anchor); only the gap is now
     * measured from the end of the photo, which is what a minutes-long Astro exposure needs.
     */
    private fun scheduleFromCaptureEnd(index: Int, endedAt: Long) {
        val schedule = schedule ?: return
        val shift = SessionTiming.anchorShiftAfterCapture(
            nextTargetElapsed = schedule.targetElapsedFor(index, scheduleAnchorElapsed),
            captureEndedElapsed = endedAt,
            intervalMs = schedule.intervalForShot((index - 1).coerceAtLeast(0), elapsedSinceFirstShot(endedAt)),
        )
        if (shift > 0L) {
            scheduleAnchorElapsed += shift
            Log.i(TAG, "capture before shot $index ended late by ${shift}ms, next shot one interval after it")
        }
    }

    // ---- guard rails ---------------------------------------------------------------------------

    /**
     * Explicit proof that a gesture can reach the app the user chose. Inferring it from a window
     * query was the bug: a locked or blank screen answers "unknown package", the engine read that as
     * "close enough", and every shot then dispatched a tap into the keyguard, completed happily and
     * counted a photo that does not exist. The screen state is asked for directly instead, and an
     * unreadable [PowerManager] counts as *not* live — refusing to tap is always the safe answer.
     */
    private fun isScreenLive(): Boolean {
        val interactive = runCatching { powerManager?.isInteractive }.getOrNull()
        if (interactive != true) return false
        val locked = runCatching { keyguardManager?.isKeyguardLocked }.getOrNull() ?: false
        return !locked
    }

    /**
     * Strict equality: "cannot tell which app is in front" is not permission to tap into it. The
     * cost of being strict is an occasional pause while a window is still coming up, and both the
     * window-state event and the guard tick lift that pause on their own.
     */
    private fun isForeground(wantedPackage: String): Boolean =
        dispatcher.foregroundPackage() == wantedPackage

    private fun pauseForForeground(wantedPackage: String) {
        Log.w(TAG, "$wantedPackage is not in the foreground, pausing instead of tapping")
        autoPause(
            AutoPauseReason.WRONG_APP,
            service.getString(R.string.session_warning_wrong_app, wantedPackage),
        )
    }

    private fun pauseForScreenOff() {
        Log.w(TAG, "screen is off or locked, pausing instead of tapping")
        autoPause(
            AutoPauseReason.SCREEN_OFF,
            service.getString(R.string.session_warning_screen_off),
        )
    }

    /** Pauses the session itself, with a warning that stays up until the condition clears. */
    private fun autoPause(reason: AutoPauseReason, message: String) {
        // Same as [pause]: the poll is about to be cancelled, so a tap that landed is counted now.
        commitPendingShot()
        autoPauseReason = reason
        paused = true
        transientWarning = false // stays up until tapping is possible again
        cancelTimers()
        releaseWakeLock()
        runState.recordProgress(currentProgress())
        SessionRepository.update {
            it.copy(
                state = SessionState.Paused,
                nextShotAtElapsed = null,
                warning = message,
            )
        }
        syncMotionWatch()
        scheduleGuardTick()
    }

    /**
     * Starts or stops the "phone in hand" watch so it runs exactly while [HandheldPolicy] wants it,
     * and drops a raised flag the moment it stops — a paused or finished session must not keep
     * telling the user to put the phone down. Called after every state change above; the
     * WaitingToStart -> Running step needs no call because both states want the watch.
     *
     * It only ever writes [SessionSnapshot.handheld]: the warning never pauses, stops or delays a shot.
     */
    private fun syncMotionWatch() {
        val want = running && motion.isAvailable && HandheldPolicy.watchDuringSession(
            SessionRepository.snapshot.value.state,
            config?.mode,
            advanced.handheldWarning,
        )
        if (want == motionWatching) return
        motionWatching = want
        if (want) {
            motion.start { state ->
                if (!motionWatching) return@start
                val handheld = state == HandheldState.HANDHELD
                SessionRepository.update { if (it.handheld == handheld) it else it.copy(handheld = handheld) }
            }
        } else {
            motion.stop()
            SessionRepository.update { if (it.handheld) it.copy(handheld = false) else it }
        }
    }

    // ---- advanced pre-shot setup ---------------------------------------------------------------

    /**
     * `launchCameraMode` and `ensureSelfTimerOff`, in that order because switching mode can re-arm
     * the timer chip. Both are strictly opt-in: with the flags off this runs [onDone] immediately and
     * touches nothing.
     *
     * `launchCameraMode` holds a **mode key** ([CameraProfile.MODE_PHOTO], `MODE_NIGHT`,
     * `MODE_ASTRO`), never a label — the profile owns the localized labels — or `custom:<id>` for a
     * mode the user recorded ([CustomModeKey]).
     */
    private fun runPreShotSetup(onDone: () -> Unit) {
        val steps = ArrayList<((() -> Unit)) -> Unit>()
        val mode = advanced.launchCameraMode?.takeIf { it.isNotBlank() }
        val customId = CustomModeKey.idOf(mode)
        if (customId != null) {
            steps += { next -> replayCustomMode(customId, next) }
        } else if (mode != null) {
            val deadline = SystemClock.elapsedRealtime() + MODE_FIND_TIMEOUT_MS
            steps += { next -> selectModeUntil(mode, deadline, next) }
        }
        if (advanced.ensureSelfTimerOff) {
            steps += { next -> dispatcher.ensureSelfTimerOff(profile) { next() } }
        }
        runSteps(steps, 0, onDone)
    }

    /**
     * Selects [mode], looking again while its chip is not on screen yet: right after a launch the
     * camera app draws its carousel late, and one look found nothing and switched nothing. A
     * successful switch starts the settle hold of the first shot.
     */
    private fun selectModeUntil(mode: String, deadline: Long, next: () -> Unit) {
        if (!running || paused) {
            next()
            return
        }
        dispatcher.selectCameraMode(profile, mode) { result ->
            when {
                result == ModeSelection.SWITCHED -> {
                    modeSwitchedAtElapsed = SystemClock.elapsedRealtime()
                    Log.i(TAG, "switched the camera to '$mode'")
                    next()
                }
                result == ModeSelection.NOT_FOUND && SystemClock.elapsedRealtime() < deadline ->
                    handler.postAtTime(
                        { selectModeUntil(mode, deadline, next) },
                        setupToken,
                        SystemClock.uptimeMillis() + SETUP_POLL_MS,
                    )
                else -> {
                    if (result == ModeSelection.NOT_FOUND) Log.w(TAG, "no '$mode' chip found, mode left as it is")
                    next()
                }
            }
        }
    }

    /**
     * Replays the mode the user recorded. Read from the settings here rather than carried in the run
     * plan, so a restored run replays what is stored now. A mode that is gone, belongs to another app
     * or stops at a step does not stop the session: it shoots in whatever mode the camera is in, and
     * says so, where the built-in switch used to fail without a word.
     */
    private fun replayCustomMode(id: String, next: () -> Unit) {
        val wantedPackage = config?.mode?.targetPackage
        scope.launch {
            val mode = settings.customMode(id)
            if (!running || paused) {
                next()
                return@launch
            }
            if (mode == null || mode.packageName != wantedPackage) {
                Log.w(TAG, "recorded mode $id is not stored for $wantedPackage")
                warn(service.getString(R.string.session_warning_custom_mode_missing))
                next()
                return@launch
            }
            val deadline = SystemClock.uptimeMillis() + MODE_FIND_TIMEOUT_MS
            dispatcher.replayCustomMode(mode, deadline, isActive = { running && !paused }) { result ->
                Log.i(TAG, "recorded mode '${mode.name}': $result")
                val failedStep = when (result) {
                    CustomReplayResult.Switched -> {
                        modeSwitchedAtElapsed = SystemClock.elapsedRealtime()
                        null
                    }
                    CustomReplayResult.Already, CustomReplayResult.Cancelled -> null
                    is CustomReplayResult.StepNotFound -> result.index
                    is CustomReplayResult.ScreenMismatch -> result.index
                    is CustomReplayResult.GestureRejected -> result.index
                }
                if (failedStep != null) {
                    warn(service.getString(R.string.session_warning_custom_mode_failed, mode.name, failedStep + 1))
                }
                next()
            }
        }
    }

    private fun runSteps(steps: List<((() -> Unit)) -> Unit>, index: Int, onDone: () -> Unit) {
        if (!running || paused || index >= steps.size) {
            onDone()
            return
        }
        steps[index] { runSteps(steps, index + 1, onDone) }
    }

    // ---- misc ----------------------------------------------------------------------------------

    /**
     * Stored calibrations are keyed by the rotation the user calibrated in — the shutter is rarely at
     * the mirrored spot after a quarter turn — but their fractions are always in natural space (see
     * [DisplayGeometry]), so any slot is usable and [calibratedRotation] stays [Surface.ROTATION_0]
     * whichever one answers. The preference order (this rotation, the natural one, any) lives in
     * [SettingsStore.anyTarget], which is also what the setup screen reads, so a session never taps
     * somewhere the UI called "not calibrated" and never refuses to start on a calibration it has.
     */
    private suspend fun loadStoredTarget(config: SessionConfig) {
        val packageName = config.mode.targetPackage ?: return
        val rotation = DisplayGeometry.currentRotation(service)
        val stored = runCatching { settings.anyTarget(packageName, rotation) }.getOrNull() ?: return
        calibratedTarget = stored
        calibratedRotation = Surface.ROTATION_0
        // The stored plan was written before this read finished, so it still says "no calibration".
        // Without this a restored run would fall back to the region guess the calibration exists to
        // override — the very defect the dispatcher's resolution order fixes.
        runState.recordCalibration(calibratedTarget, calibratedRotation)
    }

    private fun elapsedSinceFirstShot(now: Long): Long =
        firstShotElapsed?.let { now - it } ?: 0L

    /**
     * A *transient* warning describes one shot ("the tap did not go through") and is cleared by the
     * next shot that works. A sticky one describes the session ("exact alarms are not allowed") and
     * stays until the session ends.
     */
    private fun warn(message: String, transient: Boolean = false) {
        transientWarning = transient
        SessionRepository.update { it.copy(warning = message) }
    }

    private fun clearTransientWarning() {
        if (!transientWarning) return
        transientWarning = false
        SessionRepository.update { it.copy(warning = null) }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        runCatching { wakeLock?.acquire(timeoutMs.coerceIn(MIN_WAKE_LOCK_MS, MAX_WAKE_LOCK_MS)) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }

    companion object {
        private const val TAG = "SessionEngine"
        private const val WAKE_LOCK_TAG = "Caelis:session"

        /** Gaps at or above this go to [AlarmManager]; shorter ones stay on the handler. */
        const val HANDLER_GAP_LIMIT_MS = 60_000L

        /**
         * How often stop conditions are re-evaluated and an automatic pause is re-tested. Fine
         * enough that a 30-minute interval cannot overrun `maxDurationMs` by more than this, cheap
         * enough to run for hours: one planner call plus two sensor reads.
         */
        const val GUARD_TICK_MS = 5_000L

        /** Handler slack tolerated before a target counts as overrun and the series re-anchors. */
        const val LATE_TOLERANCE_MS = 250L

        private const val BUSY_POLL_MS = 250L

        /** How often the early setup looks for the camera app in front, and for its mode chip. */
        private const val SETUP_POLL_MS = 500L

        /** The early setup stops waiting for the camera app here; the first shot then runs it. */
        private const val EARLY_SETUP_GIVE_UP_MS = 30_000L

        /** How long a mode chip that is not on screen yet is looked for. */
        const val MODE_FIND_TIMEOUT_MS = 8_000L

        /** How often MediaStore is asked whether the frame landed, while a capture is unverified. */
        private const val VERIFY_POLL_MS = 500L

        /**
         * Head room past [CameraProfile.captureLatencyMs] for the camera app to publish its MediaStore
         * row. Measured on a Pixel 7 Pro (Android 17): Google Camera's row shows up about 6.3 s after
         * the shutter tap, nearly three times its 2.2 s capture latency, because the row is published
         * when the file is finished, not when the frame is taken. Generous on purpose — waiting longer
         * for a frame that did land costs nothing, while giving up too early re-taps a camera that is
         * merely still processing, which is exactly the failure this window replaced.
         */
        private const val VERIFY_GRACE_MS = 10_000L

        /** Grace after the tap's own duration before the gesture result is written off as lost. */
        private const val GESTURE_TIMEOUT_MS = 2_000L

        private const val WAKE_LOCK_SLACK_MS = 30_000L
        private const val SHOT_WAKE_LOCK_MS = 90_000L
        private const val MIN_WAKE_LOCK_MS = 5_000L
        private const val MAX_WAKE_LOCK_MS = 10 * 60_000L

        /**
         * Records that a run ended because the accessibility service went away, from a context that
         * has no engine left to ask (see [SessionAlarmReceiver]).
         */
        fun recordServiceLost(context: Context) {
            val runState = RunStateStore(context)
            if (!runState.wasRunning) return
            runState.markServiceLost()
            SessionRepository.update {
                it.copy(
                    state = SessionState.Stopped(
                        StopReason.SERVICE_LOST,
                        context.getString(R.string.session_stopped_service_lost),
                    ),
                    shotsTaken = runState.shotsTaken,
                    nextShotAtElapsed = null,
                )
            }
        }
    }
}

/** Why the engine paused itself. A user pause carries none of these and is never auto-resumed. */
private enum class AutoPauseReason { WRONG_APP, SCREEN_OFF }

/**
 * The one tap that has been dispatched for the shot in flight and not yet counted as a shot.
 *
 * With `verifyCaptures` on, a shot is counted only when its verification resolves, which is anywhere
 * from milliseconds to a whole verification window after the tap. In between, the gesture result can
 * be lost, the shot can be re-tapped once, and the session can be paused or stopped — so the single
 * fact all of those need (the instant the shot's *first* tap was dispatched, or nothing) lives here
 * rather than as a nullable field poked at from five methods of the engine.
 *
 * Three rules, and they are the whole reason this is a type:
 *  * it opens where the gesture is **dispatched**, not where its result arrives. A result callback
 *    that never comes leaves the watchdog to move the session on, while MediaStore may still confirm
 *    that very frame; opened in the callback, such a photo existed and was never counted.
 *  * a **retry** never opens one of its own, so re-tapping a frame can never add a second shot.
 *  * [commit] is a *take*, so however many resolution paths run — verification resolving, the session
 *    being paused, stopped or auto-paused — the shot is counted exactly once.
 */
internal class PendingShot {

    private var firedElapsed: Long? = null

    /**
     * True when a *first* tap opened a shot and was then refused by the platform. Only in that state may
     * the retry re-open the shot — a retry never opens one of its own.
     */
    private var droppedByFailure = false

    /**
     * True while the open shot exists only because a retry re-opened it, i.e. no gesture of this shot
     * has ever been delivered. If that retry fails too, the shot is dropped rather than counted: a shot
     * whose every tap was refused produced no photo, and counting it is what verification exists to
     * prevent. A retry over a *landed* first tap is a different case and leaves the shot alone.
     */
    private var openedByRetry = false

    /** True while a dispatched tap is waiting to be counted. */
    val isOpen: Boolean get() = firedElapsed != null

    /**
     * A gesture has just been handed to the platform. Only a first tap whose shot is being verified
     * is remembered: with verification off the tap landing is itself the shot and is counted straight
     * away, and a retry belongs to the shot that is already open.
     */
    fun onTapDispatched(isRetry: Boolean, verifyArmed: Boolean, firedElapsed: Long) {
        if (!verifyArmed) return
        if (!isRetry) {
            firedElapsed(firedElapsed)
            return
        }
        // A retry re-opens the shot in exactly one case: the first tap was refused by the platform and
        // dropped it. It must not open a shot when one is already pending (that shot belongs to the
        // first tap, and moving its instant here would count the frame against a later target), and it
        // must not open one after the shot has been committed, which would count the same frame twice.
        // Without the re-open, a refused first tap followed by a retry whose frame MediaStore *does*
        // confirm left nothing to commit: a photo that exists was never counted, and a `maxShots`
        // session then took one frame more than the user asked for.
        if (!droppedByFailure) return
        firedElapsed(value = firedElapsed, byRetry = true)
    }

    private fun firedElapsed(value: Long, byRetry: Boolean = false) {
        this.firedElapsed = value
        droppedByFailure = false
        openedByRetry = byRetry
    }

    /**
     * The gesture came back as *not delivered*: no frame is coming, so the shot this tap opened is
     * dropped instead of being counted as a photo that does not exist. A failed **retry** drops
     * nothing — the pending shot belongs to the first tap, whose frame may well have landed, and
     * giving up on the verification still counts that shot.
     */
    fun onTapFailed(isRetry: Boolean) {
        if (isRetry) {
            // A retry over a shot whose first tap landed changes nothing: that frame may still be in
            // MediaStore, and giving up on verifying it still counts the shot. A retry over a shot the
            // retry itself re-opened is the other case — no gesture of this shot ever reached the app,
            // so there is no photo to count, and counting one would report a series that does not exist.
            if (openedByRetry) {
                firedElapsed = null
                openedByRetry = false
            }
            return
        }
        droppedByFailure = firedElapsed != null
        firedElapsed = null
        openedByRetry = false
    }

    /** The instant to count the shot at, once; null when no tap is waiting. */
    fun commit(): Long? {
        val fired = firedElapsed
        firedElapsed = null
        droppedByFailure = false
        openedByRetry = false
        return fired
    }

    /** Forgets the tap without counting it: for a session being started or rebuilt from disk. */
    fun clear() {
        firedElapsed = null
        droppedByFailure = false
        openedByRetry = false
    }
}

/**
 * Whether the shot about to be tapped will be verified, and what the user is owed if not. Exhaustive
 * and mutually exclusive: the engine reads this instead of deciding it inline, where "no baseline"
 * was silently indistinguishable from "baseline says nothing has been saved yet".
 */
internal enum class VerifyArming {
    /** A baseline was taken, so this shot's frame can be told from every photo that came before it. */
    ARMED,

    /** `verifyCaptures` is off: nothing to do, nothing to say. */
    NOT_REQUESTED,

    /**
     * The user asked for verification but the photo library cannot be read. Session setup already
     * explains this one at length, so the session does not repeat it once per shot.
     */
    NO_MEDIA_ACCESS,

    /**
     * Permission is there but no baseline came back — a provider error, a query that failed. This shot
     * is not verified and the user is told so, rather than being told the capture failed.
     */
    NO_BASELINE,
    ;

    /** Whether the engine should poll MediaStore for this shot's frame at all. */
    val armsVerification: Boolean get() = this == ARMED

    /** Whether this shot owes a "not verified" warning. */
    val warnsUnverified: Boolean get() = this == NO_BASELINE
}

/**
 * What one look at MediaStore means for the shot in flight. The four cases are exhaustive and
 * mutually exclusive, which is the point: the engine reads this table and does nothing else.
 */
internal enum class VerifyStep {
    /** The frame is there, or nothing can be verified at all. The shot is done. */
    RESOLVED,

    /** The window is still open, so look again shortly rather than re-tapping a busy camera. */
    KEEP_POLLING,

    /** The window expired on the first tap: press the shutter once more for the *same* shot. */
    RETRY,

    /** The window expired on the retry too. Warn, and let the shot be done anyway. */
    GIVE_UP,
    ;

    /**
     * Whether verification has finished with this shot. Only a resolved shot may be counted as taken
     * and only a counted shot may complete a `maxShots` session — [RETRY] is deliberately *not* a
     * resolution, because a session that completed while a retry was still owed cancelled that very
     * retry (with `maxShots = 1` it cancelled the only one there was).
     */
    val resolvesShot: Boolean get() = this == RESOLVED || this == GIVE_UP
}

/** What [SessionTiming.captureWaitStep] decided about the capture in flight. */
internal enum class CaptureWaitStep {
    /** The photo is in MediaStore, or (without access) the camera is idle again. */
    FINISHED,

    /** The camera is still busy, or its photo may still be published: look again shortly. */
    WAIT,

    /** Idle for the whole grace and still no photo: the tap took nothing (or it is not visible). */
    NO_FRAME,

    /** Waited [SessionTiming.CAPTURE_MAX_WAIT_MS]: carry on with a warning. */
    TIMED_OUT,
}

/**
 * The engine's arithmetic, with no Android in it, so every rule below is unit-testable on the JVM.
 * [SessionEngine] holds the clocks and the handlers; this holds the decisions they feed.
 */
internal object SessionTiming {

    /**
     * Time given to a freshly launched camera app to reach the foreground before the first shot.
     * A user who asked for a longer start delay keeps it; a user who asked for none gets this,
     * because otherwise shot 0 fires into whatever was still on screen and immediately auto-pauses.
     */
    const val LAUNCH_SETTLE_MS = 1_500L

    /**
     * The package that must be in front for this mode, which is one and the same answer for two
     * questions: which app the engine brings up when the session starts or is restored, and which app
     * the shot path refuses to tap without.
     *
     * They have to be the same, and asking one function is how they stay that way. Launching only for
     * [TriggerMode.Timelapse] while the guard enforced `mode.targetPackage` for *both* meant a
     * periodic tap that named an app in the picker auto-paused on shot 0 with "not the app in front"
     * and took nothing at all until the user brought that app up by hand.
     */
    fun foregroundPackageFor(mode: TriggerMode): String? = mode.targetPackage

    /** Whether the advanced settings ask for anything to be done in the camera app before shot 0. */
    fun hasPreShotSetup(advanced: AdvancedSettings): Boolean =
        !advanced.launchCameraMode.isNullOrBlank() || advanced.ensureSelfTimerOff

    /** The start delay actually used, given whether the engine just launched the target app. */
    fun startDelayMs(requestedMs: Long, launchedTargetApp: Boolean): Long {
        val requested = requestedMs.coerceAtLeast(0L)
        return if (launchedTargetApp) maxOf(requested, LAUNCH_SETTLE_MS) else requested
    }

    /**
     * How long a shot may sit waiting for the camera app to write its frame: the app's own capture
     * latency, but never more than the time left before the next shot is due. Holding longer makes
     * the next shot late, which re-anchors the series and turns the requested interval into a lie.
     * A gap that has already run out yields no hold at all — the session is behind as it is.
     */
    fun postCaptureHoldMs(captureLatencyMs: Long, gapToNextTargetMs: Long): Long = when {
        captureLatencyMs <= 0L || gapToNextTargetMs <= 0L -> 0L
        else -> minOf(captureLatencyMs, gapToNextTargetMs)
    }

    /**
     * Whether this shot's capture is going to be verified at all, decided *before* the tap.
     *
     * [baseline] is the token the verifier handed back, and a null one is the case that matters: with
     * `READ_MEDIA_IMAGES` granted, [CaptureVerifier.available] stays true even when the MediaStore
     * query itself fails, and `hasNewImageSince(null)` is false by contract. Arming verification on a
     * baseline that could not be taken therefore walked every shot through
     * KEEP_POLLING -> RETRY -> GIVE_UP — two taps per frame and a "no new photo" warning on a camera
     * that is working. A baseline that could not be taken means *this shot is not verified*, which is
     * the one case that owes the user a word about it ([VerifyArming.warnsUnverified]); the other two
     * are already explained where they are chosen.
     */
    fun verifyArming(requested: Boolean, verifiable: Boolean, baseline: String?): VerifyArming = when {
        !requested -> VerifyArming.NOT_REQUESTED
        !verifiable -> VerifyArming.NO_MEDIA_ACCESS
        baseline == null -> VerifyArming.NO_BASELINE
        else -> VerifyArming.ARMED
    }

    /**
     * What to do with the shot in flight, given one look at MediaStore.
     *
     * [verifiable] is false when the capture cannot be judged at all (no media permission): an
     * unverifiable capture is not a missing one, so it resolves rather than triggering a re-tap.
     * [windowExpired] is what makes the retry cost a whole verification window — a camera that is
     * merely still processing must not be tapped again — and [alreadyRetried] is what caps the
     * re-taps at exactly one.
     */
    fun verifyStep(
        verifiable: Boolean,
        frameVisible: Boolean,
        windowExpired: Boolean,
        alreadyRetried: Boolean,
    ): VerifyStep = when {
        !verifiable || frameVisible -> VerifyStep.RESOLVED
        !windowExpired -> VerifyStep.KEEP_POLLING
        !alreadyRetried -> VerifyStep.RETRY
        else -> VerifyStep.GIVE_UP
    }

    /**
     * Longest a single capture is waited for with `intervalAfterCapture` on. Astro on a Pixel runs
     * up to 4 minutes plus processing; past this the camera is assumed stuck and the series goes on.
     */
    const val CAPTURE_MAX_WAIT_MS = 10 * 60_000L

    /**
     * How long an idle camera is given for its photo to appear in MediaStore. A camera app inserts
     * the row pending at the tap and publishes it when the file is written: on a Pixel 7 Pro that
     * was ~4 s after the Astro UI came back.
     */
    const val CAPTURE_PUBLISH_GRACE_MS = 10_000L

    /**
     * Time a freshly switched camera mode gets before the first shot. Pixel Camera's Night Sight
     * engages Astro on its own once the phone is still, which takes a moment after the switch.
     */
    const val MODE_SETTLE_MS = 5_000L

    /**
     * What one look at the camera means while `intervalAfterCapture` waits for a capture to end.
     *
     * [canSeeFrames] is false without MediaStore access (or without a baseline); then an idle camera
     * is the only signal there is. With it, an idle camera still gets [CAPTURE_PUBLISH_GRACE_MS] for
     * its row, because the row is published after the UI comes back.
     */
    fun captureWaitStep(
        frameVisible: Boolean,
        canSeeFrames: Boolean,
        cameraBusy: Boolean,
        sinceTapMs: Long,
        idleForMs: Long,
    ): CaptureWaitStep = when {
        // A busy camera outranks a seen photo: Pixel Camera inserts its row, pending, at the shutter
        // press, so a check that sees inserts (a MediaStore observer without READ_MEDIA_IMAGES)
        // reports the photo minutes before Astro is finished.
        cameraBusy && sinceTapMs < CAPTURE_MAX_WAIT_MS -> CaptureWaitStep.WAIT
        frameVisible -> CaptureWaitStep.FINISHED
        sinceTapMs >= CAPTURE_MAX_WAIT_MS -> CaptureWaitStep.TIMED_OUT
        !canSeeFrames -> CaptureWaitStep.FINISHED
        idleForMs < CAPTURE_PUBLISH_GRACE_MS -> CaptureWaitStep.WAIT
        else -> CaptureWaitStep.NO_FRAME
    }

    /**
     * How far to move the schedule anchor so the next shot is one interval after the capture ended:
     * `captureEnded + interval - nextTarget`, never negative, because a capture that ended early
     * must not pull the series ahead of its absolute schedule.
     */
    fun anchorShiftAfterCapture(nextTargetElapsed: Long, captureEndedElapsed: Long, intervalMs: Long): Long =
        (captureEndedElapsed + intervalMs - nextTargetElapsed).coerceAtLeast(0L)

    /** How much longer the first shot must wait for a mode switched at [switchedAtElapsed]. */
    fun modeSettleHoldMs(switchedAtElapsed: Long?, now: Long): Long =
        if (switchedAtElapsed == null) 0L else (switchedAtElapsed + MODE_SETTLE_MS - now).coerceAtLeast(0L)

    /**
     * The interval the session is really delivering: last tap to next shot. Falls back to the
     * planned interval before the first tap, when there is nothing measured yet.
     */
    fun achievedIntervalMs(targetElapsed: Long, lastShotElapsed: Long?, nominalMs: Long): Long {
        if (lastShotElapsed == null) return nominalMs
        return (targetElapsed - lastShotElapsed).coerceAtLeast(0L)
    }

    /**
     * States in which stop conditions still apply. Everything except [SessionState.Idle] and
     * [SessionState.Stopped] is a live session — including one waiting for its start delay and one
     * that is paused, which is exactly where an unattended session used to sit and drain the battery
     * past the limit the user set.
     */
    fun isAlive(state: SessionState): Boolean = when (state) {
        is SessionState.WaitingToStart, SessionState.Running, SessionState.Paused -> true
        SessionState.Idle, is SessionState.Stopped -> false
    }
}
