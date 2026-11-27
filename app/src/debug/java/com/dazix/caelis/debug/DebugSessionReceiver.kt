package com.dazix.caelis.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dazix.caelis.ar.PreviewFrameClock
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.RampDomain
import com.dazix.caelis.domain.RampSpec
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.service.ShutterAccessibilityService

/**
 * Debug-only entry point so a shell script can drive a real session without touching the UI.
 * Lives in the debug source set only; nothing in the release build can reach it.
 *
 * See `scripts/README.md` for the exact `am broadcast` invocations.
 */
class DebugSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                ShutterAccessibilityService.instance?.stopSession()
            }
            ACTION_CLEAR_LOG -> {
                FakeCameraTapLog.clear(context)
                Log.i(TAG, "tap log cleared")
            }
            ACTION_SET_AR_DISPLAY_LAG -> {
                val lagMs = intent.longExtra(EXTRA_LAG_MS, 0L)
                PreviewFrameClock.displayLagNs = lagMs * 1_000_000L
                Log.i(TAG, "AR display lag set to $lagMs ms")
            }
            ACTION_DUMP_NODES -> dumpNodes()
            else -> Log.w(TAG, "ignoring unknown action ${intent.action}")
        }
    }

    /**
     * Logs the active window's nodes as the accessibility service sees them, one line each, under
     * [NODES_TAG]. `uiautomator dump` fails with "could not get idle state" on Pixel Camera's Night
     * Sight and Astro screens, which are exactly the ones a camera profile has to be written against.
     */
    private fun dumpNodes() {
        val root = ShutterAccessibilityService.instance?.rootInActiveWindow
        if (root == null) {
            Log.w(NODES_TAG, "no active window")
            return
        }
        val bounds = android.graphics.Rect()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var count = 0
        while (queue.isNotEmpty() && count < 500) {
            val node = queue.removeFirst()
            count++
            node.getBoundsInScreen(bounds)
            Log.i(
                NODES_TAG,
                "id=${node.viewIdResourceName} desc=${node.contentDescription} text=${node.text} " +
                    "click=${node.isClickable} sel=${node.isSelected} en=${node.isEnabled} " +
                    "vis=${node.isVisibleToUser} b=${bounds.toShortString()}",
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        Log.i(NODES_TAG, "END $count nodes")
    }

    private fun startSession(intent: Intent) {
        val service = ShutterAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "SESSION_START_FAILED accessibility service is not running")
            return
        }
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val mode = if (targetPackage != null) {
            TriggerMode.Timelapse(targetPackage)
        } else {
            TriggerMode.PeriodicTap(null)
        }
        val ramp = intent.getStringExtra(EXTRA_RAMP_CURVE)?.let { name ->
            RampSpec(
                curve = if (name.startsWith("exp", ignoreCase = true)) {
                    RampCurve.EXPONENTIAL
                } else {
                    RampCurve.LINEAR
                },
                endIntervalMs = intent.longExtra(EXTRA_RAMP_END_INTERVAL_MS, 0L),
                domain = RampDomain.SHOT_INDEX,
                overShots = intent.longExtra(EXTRA_RAMP_OVER_SHOTS, 0L).toInt(),
            )
        }
        val target = if (intent.hasExtra(EXTRA_X_FRACTION) && intent.hasExtra(EXTRA_Y_FRACTION)) {
            TapTarget(
                xFraction = intent.getFloatExtra(EXTRA_X_FRACTION, 0.5f),
                yFraction = intent.getFloatExtra(EXTRA_Y_FRACTION, 0.9f),
            )
        } else {
            null
        }
        val config = SessionConfig(
            mode = mode,
            intervalMs = intent.longExtra(EXTRA_INTERVAL_MS, 3_000L),
            startDelayMs = intent.longExtra(EXTRA_START_DELAY_MS, 0L),
            ramp = ramp,
            stop = StopConditions(
                maxShots = intent.longExtra(EXTRA_MAX_SHOTS, 0L).toInt().takeIf { it > 0 },
                maxDurationMs = intent.longExtra(EXTRA_MAX_DURATION_MS, 0L).takeIf { it > 0L },
            ),
            target = target,
            verifyCaptures = intent.boolExtra(EXTRA_VERIFY_CAPTURES),
        )
        // Reachable from a script for the same reason the UI has them: a Pixel that decides on a
        // 3 s Night Sight exposure ignores a shutter tap that arrives mid-capture, and these are the
        // app's answer to it. A scripted run that could not switch them on would be testing a
        // configuration no user shoots in.
        val advanced = AdvancedSettings(
            launchCameraMode = intent.getStringExtra(EXTRA_LAUNCH_CAMERA_MODE),
            intervalAfterCapture = intent.boolExtra(EXTRA_ADAPTIVE_LONG_EXPOSURE_WAIT),
            ensureSelfTimerOff = intent.boolExtra(EXTRA_ENSURE_SELF_TIMER_OFF),
            dismissPostCaptureUi = intent.boolExtra(EXTRA_DISMISS_POST_CAPTURE_UI),
        )
        Log.i(TAG, "SESSION_START $config $advanced")
        service.startSession(config, advanced)
    }

    /**
     * `am broadcast` may hand a long over as `--el`, `--ei` or `--es`; accept all three so a script
     * cannot fail silently on the wrong flag. A typed getter returns its default when the stored
     * extra has a different type, which is what makes the fall-through work.
     */
    private fun Intent.longExtra(key: String, fallback: Long): Long {
        if (!hasExtra(key)) return fallback
        val asLong = getLongExtra(key, Long.MIN_VALUE)
        if (asLong != Long.MIN_VALUE) return asLong
        val asInt = getIntExtra(key, Int.MIN_VALUE)
        if (asInt != Int.MIN_VALUE) return asInt.toLong()
        return getStringExtra(key)?.toLongOrNull() ?: fallback
    }

    /** `--ez key true` delivers a boolean, `--es key true` a string; both spellings work. */
    private fun Intent.boolExtra(key: String, fallback: Boolean = false): Boolean {
        if (!hasExtra(key)) return fallback
        val asString = getStringExtra(key)
        if (asString != null) return asString.equals("true", ignoreCase = true) || asString == "1"
        return getBooleanExtra(key, fallback)
    }

    companion object {
        private const val TAG = "DebugSession"

        const val ACTION_START = "com.dazix.caelis.debug.START_SESSION"
        const val ACTION_STOP = "com.dazix.caelis.debug.STOP_SESSION"
        const val ACTION_CLEAR_LOG = "com.dazix.caelis.debug.CLEAR_TAP_LOG"

        /** Logs the active window's accessibility nodes under [NODES_TAG]. */
        const val ACTION_DUMP_NODES = "com.dazix.caelis.debug.DUMP_NODES"
        private const val NODES_TAG = "CaelisNodes"

        /** Tunes `PreviewFrameClock.displayLagNs` live; extra `lag_ms` (may be negative). */
        const val ACTION_SET_AR_DISPLAY_LAG = "com.dazix.caelis.debug.SET_AR_DISPLAY_LAG"
        const val EXTRA_LAG_MS = "lag_ms"

        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_START_DELAY_MS = "start_delay_ms"
        const val EXTRA_MAX_SHOTS = "max_shots"
        const val EXTRA_MAX_DURATION_MS = "max_duration_ms"
        const val EXTRA_RAMP_CURVE = "ramp_curve"
        const val EXTRA_RAMP_END_INTERVAL_MS = "ramp_end_interval_ms"
        const val EXTRA_RAMP_OVER_SHOTS = "ramp_over_shots"
        const val EXTRA_X_FRACTION = "x_fraction"
        const val EXTRA_Y_FRACTION = "y_fraction"
        const val EXTRA_VERIFY_CAPTURES = "verify_captures"
        const val EXTRA_ADAPTIVE_LONG_EXPOSURE_WAIT = "adaptive_long_exposure_wait"
        const val EXTRA_ENSURE_SELF_TIMER_OFF = "ensure_self_timer_off"
        const val EXTRA_DISMISS_POST_CAPTURE_UI = "dismiss_post_capture_ui"
        const val EXTRA_LAUNCH_CAMERA_MODE = "launch_camera_mode"
    }
}
