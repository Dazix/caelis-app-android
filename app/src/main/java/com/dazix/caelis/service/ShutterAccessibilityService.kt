package com.dazix.caelis.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dazix.caelis.R
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.cameramode.CustomCameraMode
import com.dazix.caelis.notify.SessionNotifications
import com.dazix.caelis.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The tapping engine's host. It owns exactly one [SessionEngine], publishes every bit of state through
 * [SessionRepository], and hands out a static instance so the UI (and [SessionAlarmReceiver]) can
 * reach the live session without binding.
 *
 * The instance reference is set in [onServiceConnected] and cleared in [onUnbind]/[onDestroy] — those
 * are also the moments where a mid-run teardown is turned into
 * [StopReason.SERVICE_LOST]: the accessibility service being switched off is the one failure the app
 * cannot survive, so it is recorded durably (see [RunStateStore]) and reported the next time we
 * connect.
 */
class ShutterAccessibilityService : AccessibilityService() {

    private var engine: SessionEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = this
        engine = SessionEngine(this)
        Log.i(TAG, "connected")
        reportEarlierServiceLoss()
        attachUi()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        engine?.onForegroundPackageChanged(event.packageName?.toString())
        // The panel has to know too, so it can stand down over the app's own screens instead of
        // covering the cards that already carry the same controls. It is told which *application*
        // window is in front, not which package the event came from: the panel is a window of ours as
        // well, so its own appearance fires an event naming us while the camera is still in front, and
        // trusting that made the panel hide itself the instant it was added.
        overlay?.onForegroundPackageChanged(engine?.foregroundApplicationPackage())
    }

    override fun onInterrupt() = Unit

    // ---- session control, for the UI -----------------------------------------------------------

    fun startSession(config: SessionConfig, advanced: AdvancedSettings = AdvancedSettings()) {
        engine?.start(config, advanced)
    }

    /** Starts from whatever the UI last saved through `SettingsStore`. */
    fun startSessionFromStoredSettings() {
        engine?.startFromStoredSettings()
    }

    fun stopSession(reason: StopReason = StopReason.USER) {
        engine?.stop(reason)
    }

    fun pauseSession() {
        engine?.pause()
    }

    fun resumeSession() {
        engine?.resume()
    }

    /** Called by [SessionAlarmReceiver] when a long-interval shot comes due. */
    fun onSessionAlarm(shotIndex: Int) {
        engine?.onAlarm(shotIndex)
    }

    /** Camera profile in force — useful for a settings screen that wants to show it. */
    fun activeProfile(): CameraProfile = engine?.profile ?: CameraProfiles.generic

    /** Which mechanism pressed the shutter last: node click or synthetic gesture. */
    fun lastTapOutcome(): TapOutcome? = engine?.lastTapOutcome

    private fun teardown() {
        if (instanceRef !== this && instanceRef != null) return
        Log.i(TAG, "disconnecting")
        // Turn "the OS is tearing us down mid-run" into a stop the UI can explain.
        engine?.reportServiceLost()
        detachUi()
        engine?.dispose()
        engine = null
        instanceRef = null
    }

    /**
     * A session that was live when we last went away never stopped cleanly — the service was switched
     * off, or the process was killed. Say so once, then forget it.
     *
     * Unless it can simply be picked up again: the process being reclaimed between two alarm-spaced
     * shots is the ordinary case, not a failure, and [SessionEngine.restore] rebuilds that run from
     * [RunStateStore] at its original anchor. Only a run that cannot be rebuilt is written off — and
     * it must be tested *first*, because the [RunStateStore.markServiceLost] below deliberately drops
     * the stored plan.
     */
    private fun reportEarlierServiceLoss() {
        if (engine?.restore() == true) {
            Log.i(TAG, "picked an interrupted session back up")
            return
        }
        val runState = RunStateStore(this)
        if (!runState.wasRunning && !runState.serviceLost) return
        val shots = runState.shotsTaken
        runState.markServiceLost()
        runState.clearServiceLost()
        SessionRepository.update {
            it.copy(
                state = SessionState.Stopped(
                    StopReason.SERVICE_LOST,
                    getString(R.string.session_stopped_service_lost),
                ),
                shotsTaken = shots,
                nextShotAtElapsed = null,
            )
        }
    }

    // ==== INTEGRATION POINT =====================================================================
    // The ONLY place in the tapping engine that touches notify.SessionNotifications and
    // overlay.OverlayController, both owned by other agents. If either API differs from the calls
    // below, everything that needs fixing is between here and the end of detachUi().
    //   expected: SessionNotifications(context).attachTo(service) / .update(snapshot) / .clear()
    //             plus .onPauseResume / .onStop, which must be set before attachTo
    //   expected: OverlayController(context).show() / .hide(), plus .onPauseResume / .onStop
    private var notifications: SessionNotifications? = null
    private var overlay: OverlayController? = null
    private var notificationsAttached = false

    private fun attachUi() {
        notifications = runCatching {
            SessionNotifications(this).also {
                it.onPauseResume = ::togglePause
                it.onStop = ::stopFromControls
            }
        }.onFailure { Log.w(TAG, "notifications unavailable", it) }.getOrNull()
        overlay = runCatching {
            OverlayController(this).also {
                it.onPauseResume = ::togglePause
                it.onStop = ::stopFromControls
            }
        }.onFailure { Log.w(TAG, "overlay unavailable", it) }.getOrNull()

        serviceScope.launch {
            SessionRepository.snapshot.collectLatest { snapshot ->
                runCatching {
                    // Attached on the first non-idle snapshot so that merely enabling the service
                    // does not leave an "idle" notification in the shade forever. It stays up through
                    // Stopped, which is how an auto-stop reaches a user who is not in the app.
                    if (snapshot.state !is SessionState.Idle) attachNotifications()
                    notifications?.update(snapshot)
                }
                runCatching { if (snapshot.wantsOverlay()) overlay?.show() else overlay?.hide() }
            }
        }
    }

    private fun attachNotifications() {
        if (notificationsAttached) return
        val notifications = notifications ?: return
        notificationsAttached = true
        notifications.attachTo(this)
    }

    /** One button in both surfaces, so the current state decides what it means. */
    private fun togglePause() {
        if (SessionRepository.snapshot.value.state is SessionState.Paused) {
            resumeSession()
        } else {
            pauseSession()
        }
    }

    private fun stopFromControls() {
        stopSession(StopReason.USER)
    }

    private fun detachUi() {
        serviceScope.cancel()
        runCatching { overlay?.hide() }
        runCatching { notifications?.clear() }
        notificationsAttached = false
        overlay = null
        notifications = null
    }

    private fun SessionSnapshot.wantsOverlay(): Boolean = when (state) {
        is SessionState.WaitingToStart, SessionState.Running, SessionState.Paused -> true
        else -> false
    }
    // ==== END INTEGRATION POINT =================================================================

    // ---- recorded camera modes, outside a session ----------------------------------------------

    /** Recording and testing a mode tap through the same class as a session; it holds no state. */
    private val modeDispatcher by lazy { ShutterDispatcher(this) }

    private val modeHandler = Handler(Looper.getMainLooper())

    private val sessionBusy: Boolean
        get() = when (SessionRepository.snapshot.value.state) {
            is SessionState.Idle, is SessionState.Stopped -> false
            else -> true
        }

    /**
     * Opens [mode]'s app, waits for it to be in front and replays the mode, as a session start would.
     * Refused (false) while a session runs: two things tapping one camera would fight.
     */
    private fun testCustomMode(mode: CustomCameraMode, onDone: (CustomReplayResult?) -> Unit): Boolean {
        if (sessionBusy) return false
        val intent = runCatching { packageManager.getLaunchIntentForPackage(mode.packageName) }.getOrNull()
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (runCatching { startActivity(intent) }.isFailure) return false
        val giveUpAt = SystemClock.uptimeMillis() + TEST_LAUNCH_WAIT_MS
        fun waitForApp() {
            when {
                modeDispatcher.foregroundPackage() == mode.packageName -> modeDispatcher.replayCustomMode(
                    mode,
                    firstStepDeadline = SystemClock.uptimeMillis() + SessionEngine.MODE_FIND_TIMEOUT_MS,
                    isActive = { !sessionBusy && modeDispatcher.foregroundPackage() == mode.packageName },
                    onDone = onDone,
                )
                SystemClock.uptimeMillis() > giveUpAt -> onDone(null)
                else -> modeHandler.postDelayed({ waitForApp() }, TEST_POLL_MS)
            }
        }
        modeHandler.postDelayed({ waitForApp() }, TEST_POLL_MS)
        return true
    }

    companion object {
        private const val TAG = "ShutterService"

        private const val TEST_LAUNCH_WAIT_MS = 10_000L
        private const val TEST_POLL_MS = 300L

        /** The tools a mode recording needs, or null while the service is off or a session runs. */
        fun modeTools(): ShutterDispatcher? = instanceRef?.takeIf { !it.sessionBusy }?.modeDispatcher

        /**
         * Replays [mode] now. [onDone] gets the result on the main thread, or null when the app did not
         * come to the front. Returns false when nothing was started (service off, session running).
         */
        fun testCustomMode(mode: CustomCameraMode, onDone: (CustomReplayResult?) -> Unit): Boolean =
            instanceRef?.testCustomMode(mode, onDone) ?: false

        @Volatile
        private var instanceRef: ShutterAccessibilityService? = null

        /**
         * The connected service, or `null` when the user has not enabled it.
         *
         * Deliberately available both as `instance` and as `instance()`: the UI and the instrumented
         * harness were written against different spellings, and the two compile to distinct JVM
         * methods (`getInstance()` and `instance()`), so supporting both costs nothing.
         */
        val instance: ShutterAccessibilityService? get() = instanceRef

        fun instance(): ShutterAccessibilityService? = instanceRef

        /** True while the user has the service enabled and it is bound. */
        val isRunning: Boolean get() = instanceRef != null

        /** Returns false when the service is not enabled, so the UI can send the user to settings. */
        fun startSession(config: SessionConfig, advanced: AdvancedSettings = AdvancedSettings()): Boolean {
            val service = instanceRef ?: return false
            service.startSession(config, advanced)
            return true
        }

        fun stopSession(reason: StopReason = StopReason.USER): Boolean {
            val service = instanceRef ?: return false
            service.stopSession(reason)
            return true
        }

        fun pauseSession(): Boolean {
            val service = instanceRef ?: return false
            service.pauseSession()
            return true
        }

        fun resumeSession(): Boolean {
            val service = instanceRef ?: return false
            service.resumeSession()
            return true
        }
    }
}
