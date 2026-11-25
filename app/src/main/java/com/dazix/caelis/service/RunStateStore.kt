package com.dazix.caelis.service

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.RampDomain
import com.dazix.caelis.domain.RampSpec
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import kotlin.math.abs

/**
 * Everything about a live run that has to survive the process, in one synchronously readable record.
 *
 * ### Why this exists
 * A session's plan and its progress used to live only in [SessionEngine]'s fields. A long-interval
 * session waits on an [android.app.AlarmManager] alarm with no CPU held, which is exactly the moment
 * the OS is most likely to reclaim the process; the alarm then restarts the process,
 * [SessionAlarmReceiver] finds no engine, and a run that was five minutes from finishing ends as
 * [StopReason.SERVICE_LOST]. With the plan on disk the alarm can wait for the accessibility service to
 * rebind and the engine can pick the series up at the shot it was on, at the original anchor, so the
 * cadence is not even disturbed.
 *
 * ### Why SharedPreferences and not DataStore
 * The alarm path ([SessionAlarmReceiver.onReceive]) runs on the main thread of a process that may have
 * existed for milliseconds, and it has to decide *right there* whether a run is resumable. DataStore
 * only offers a `Flow`, so that decision would need `runBlocking { first() }` on the main thread —
 * the one thing DataStore explicitly must not be used for. [android.content.SharedPreferences] loads
 * its file once, synchronously, and every read afterwards is an in-memory map lookup.
 *
 * Writes use `commit()` rather than `apply()`: the whole point of the record is to survive a kill,
 * and `apply()`'s disk write is queued on a background thread that a `kill -9` does not wait for.
 * Nothing here is written more than once per shot, and the file is a few hundred bytes.
 *
 * ### Boot safety
 * Every anchor in a run is a [SystemClock.elapsedRealtime] instant, which is meaningless after a
 * reboot. Each write stamps the elapsed/wall-clock pair, and [liveRun] refuses to hand back a plan
 * that was written before the current boot (see [BootIdentity]).
 */
class RunStateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("session_runtime", Context.MODE_PRIVATE)

    /** True when a session was live and never stopped cleanly. */
    val wasRunning: Boolean get() = prefs.getBoolean(KEY_RUNNING, false)

    /** True when the loss was already detected but not yet reported to the user. */
    val serviceLost: Boolean get() = prefs.getBoolean(KEY_SERVICE_LOST, false)

    val shotsTaken: Int get() = prefs.getInt(KEY_SHOTS, 0)

    // ---- writing ---------------------------------------------------------------------------------

    /**
     * Records the whole plan of a session that is starting. Supersedes [markRunning] at session start;
     * everything [markRunning] does is done here too.
     */
    fun beginRun(plan: RunPlan, progress: RunProgress = RunProgress()) {
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_SERVICE_LOST, false)
            .putString(KEY_PLAN, RunPlanCodec.encode(plan))
            .writeProgress(progress)
            .stampClock()
            .commit()
    }

    /**
     * Records what changed as the run advanced. Cheap: only the counters and the anchor are rewritten,
     * the encoded plan is left alone.
     */
    fun recordProgress(progress: RunProgress) {
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_SERVICE_LOST, false)
            .writeProgress(progress)
            .stampClock()
            .commit()
    }

    /**
     * Rewrites only the calibration inside the stored plan, for the target that is loaded
     * asynchronously just after a session starts. No-op when no plan is stored.
     */
    fun recordCalibration(target: TapTarget?, rotation: Int) {
        val plan = storedPlan() ?: return
        prefs.edit()
            .putString(
                KEY_PLAN,
                RunPlanCodec.encode(plan.copy(calibratedTarget = target, calibratedRotation = rotation)),
            )
            .stampClock()
            .commit()
    }

    /** Progress-only update, kept for callers that have nothing else to say. */
    fun markRunning(shotsTaken: Int) {
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_SERVICE_LOST, false)
            .putInt(KEY_SHOTS, shotsTaken)
            .stampClock()
            .commit()
    }

    /** The run ended on purpose: nothing is resumable any more. */
    fun markStopped() {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_SERVICE_LOST, false)
            .clearPlan()
            .commit()
    }

    /**
     * Keeps the flag set on purpose: it is cleared only once the UI has been told.
     *
     * [shotsTaken] survives — `SessionEngine.recordServiceLost` reads it straight after this call to
     * put the shot count in front of the user — but the plan does not: a lost run is terminal and must
     * never be resumed by a later reconnect.
     */
    fun markServiceLost() {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_SERVICE_LOST, true)
            .clearPlan()
            .commit()
    }

    fun clearServiceLost() {
        prefs.edit().putBoolean(KEY_SERVICE_LOST, false).commit()
    }

    // ---- reading ---------------------------------------------------------------------------------

    /**
     * The run to rebuild, or `null` when there is nothing to rebuild — no live run, a plan written
     * before the current boot, or a record too damaged to decode. Synchronous and safe to call from
     * [SessionAlarmReceiver.onReceive].
     */
    fun liveRun(): LiveRun? {
        if (!wasRunning) return null
        val plan = storedPlan() ?: return null
        if (!clockStillValid()) return null
        return LiveRun(plan = plan, progress = storedProgress())
    }

    /** Cheap "is it worth waiting for the service to come back?" check for the alarm path. */
    fun isResumable(): Boolean = liveRun() != null

    private fun storedPlan(): RunPlan? = RunPlanCodec.decode(prefs.getString(KEY_PLAN, null))

    private fun storedProgress(): RunProgress = RunProgress(
        shotsTaken = prefs.getInt(KEY_SHOTS, 0),
        attemptedShots = prefs.getInt(KEY_ATTEMPTED, 0),
        nextShotIndex = prefs.getInt(KEY_NEXT_INDEX, 0),
        scheduleAnchorElapsed = prefs.getLong(KEY_ANCHOR, 0L),
        firstShotElapsed = prefs.getLong(KEY_FIRST_SHOT, NO_LONG).takeIf { it != NO_LONG },
        paused = prefs.getBoolean(KEY_PAUSED, false),
    )

    private fun clockStillValid(): Boolean = BootIdentity.sameBoot(
        savedElapsedMs = prefs.getLong(KEY_SAVED_ELAPSED, 0L),
        savedWallMs = prefs.getLong(KEY_SAVED_WALL, 0L),
        nowElapsedMs = SystemClock.elapsedRealtime(),
        nowWallMs = System.currentTimeMillis(),
    )

    private fun SharedPreferences.Editor.writeProgress(
        progress: RunProgress,
    ): SharedPreferences.Editor = this
        .putInt(KEY_SHOTS, progress.shotsTaken)
        .putInt(KEY_ATTEMPTED, progress.attemptedShots)
        .putInt(KEY_NEXT_INDEX, progress.nextShotIndex)
        .putLong(KEY_ANCHOR, progress.scheduleAnchorElapsed)
        .putLong(KEY_FIRST_SHOT, progress.firstShotElapsed ?: NO_LONG)
        .putBoolean(KEY_PAUSED, progress.paused)

    private fun SharedPreferences.Editor.stampClock():
        SharedPreferences.Editor = this
        .putLong(KEY_SAVED_ELAPSED, SystemClock.elapsedRealtime())
        .putLong(KEY_SAVED_WALL, System.currentTimeMillis())

    private fun SharedPreferences.Editor.clearPlan():
        SharedPreferences.Editor = this
        .remove(KEY_PLAN)
        .remove(KEY_ATTEMPTED)
        .remove(KEY_NEXT_INDEX)
        .remove(KEY_ANCHOR)
        .remove(KEY_FIRST_SHOT)
        .remove(KEY_PAUSED)
        .remove(KEY_SAVED_ELAPSED)
        .remove(KEY_SAVED_WALL)

    private companion object {
        const val KEY_RUNNING = "running"
        const val KEY_SERVICE_LOST = "service_lost"
        const val KEY_SHOTS = "shots_taken"

        const val KEY_PLAN = "run_plan"
        const val KEY_ATTEMPTED = "attempted_shots"
        const val KEY_NEXT_INDEX = "next_shot_index"
        const val KEY_ANCHOR = "schedule_anchor_elapsed"
        const val KEY_FIRST_SHOT = "first_shot_elapsed"
        const val KEY_PAUSED = "paused"
        const val KEY_SAVED_ELAPSED = "saved_elapsed"
        const val KEY_SAVED_WALL = "saved_wall_clock"

        /** Out-of-band value for a `null` Long; no real elapsed instant can be negative. */
        const val NO_LONG = Long.MIN_VALUE
    }
}

/**
 * The half of a run that is fixed once it starts: what to shoot, how, from which instant, and with
 * which calibration.
 */
data class RunPlan(
    val config: SessionConfig,
    val advanced: AdvancedSettings = AdvancedSettings(),
    /** `SessionEngine.sessionOriginElapsed`: what the stop conditions measure duration from. */
    val sessionOriginElapsed: Long,
    val calibratedTarget: TapTarget? = null,
    val calibratedRotation: Int = 0,
)

/** The half of a run that moves: counters, the timing anchor and the pause flag. */
data class RunProgress(
    val shotsTaken: Int = 0,
    val attemptedShots: Int = 0,
    val nextShotIndex: Int = 0,
    /** `SessionEngine.scheduleAnchorElapsed`, which moves only when a shot overran. */
    val scheduleAnchorElapsed: Long = 0L,
    val firstShotElapsed: Long? = null,
    val paused: Boolean = false,
)

/** Everything needed to rebuild a session that was interrupted by the process going away. */
data class LiveRun(val plan: RunPlan, val progress: RunProgress)

/**
 * Distinguishes "the process died and came back" from "the device rebooted". Pure, so the rule is
 * unit-tested rather than reasoned about.
 *
 * `elapsedRealtime` restarts at zero on boot while the wall clock does not, so `wall - elapsed` is a
 * stable identity for the current boot. A wall clock nudged by NTP moves it a little, hence the
 * tolerance; a reboot moves it by however long the device was up last time, which is orders of
 * magnitude more. When the two disagree the run is dropped rather than resumed against an anchor that
 * no longer means anything.
 */
internal object BootIdentity {

    const val TOLERANCE_MS = 10_000L

    fun sameBoot(
        savedElapsedMs: Long,
        savedWallMs: Long,
        nowElapsedMs: Long,
        nowWallMs: Long,
        toleranceMs: Long = TOLERANCE_MS,
    ): Boolean {
        if (savedElapsedMs <= 0L || savedWallMs <= 0L) return false
        // Uptime cannot run backwards without a reboot, and this holds even when the wall clock is
        // unset (both boot instants would then look identical).
        if (nowElapsedMs < savedElapsedMs) return false
        return abs((savedWallMs - savedElapsedMs) - (nowWallMs - nowElapsedMs)) <= toleranceMs
    }
}

/**
 * Encodes a [RunPlan] as one string, so the plan is a single preference key that is written once per
 * session instead of thirty keys touched on every shot.
 *
 * Deliberately free of any Android type: the format is the part that can silently break a resume, so
 * it is round-tripped in plain JVM unit tests. Every reader is total — a truncated, damaged or
 * older-format record decodes to `null`, which the store reports as "nothing to resume".
 */
internal object RunPlanCodec {

    private const val VERSION = "v1"
    private const val SEP = ''
    private const val FIELDS = 35

    /** A record written before `handheldWarning` existed; it resumes with the default. */
    private const val FIELDS_BEFORE_HANDHELD = 34

    private const val MODE_TIMELAPSE = "T"
    private const val MODE_PERIODIC_TAP = "P"

    fun encode(plan: RunPlan): String {
        val config = plan.config
        val ramp = config.ramp
        val stop = config.stop
        val target = config.target
        val advanced = plan.advanced
        val calibrated = plan.calibratedTarget
        return listOf(
            VERSION,
            when (config.mode) {
                is TriggerMode.Timelapse -> MODE_TIMELAPSE
                is TriggerMode.PeriodicTap -> MODE_PERIODIC_TAP
            },
            encString(config.mode.targetPackage),
            config.intervalMs.toString(),
            config.startDelayMs.toString(),
            encBool(config.verifyCaptures),
            config.minIntervalFloorMs.toString(),
            encString(ramp?.curve?.name),
            encLong(ramp?.endIntervalMs),
            encString(ramp?.domain?.name),
            encInt(ramp?.overShots),
            encLong(ramp?.overDurationMs),
            encInt(stop.maxShots),
            encLong(stop.maxDurationMs),
            encInt(stop.minBatteryPercent),
            encLong(stop.minFreeBytes),
            encFloat(target?.xFraction),
            encFloat(target?.yFraction),
            encFloat(target?.widthFraction),
            encFloat(target?.heightFraction),
            encBool(target?.jitter ?: false),
            encString(advanced.launchCameraMode),
            encBool(advanced.intervalAfterCapture),
            encBool(advanced.ensureSelfTimerOff),
            encBool(advanced.dismissPostCaptureUi),
            encBool(advanced.keepScreenOn),
            encBool(advanced.dimOverlay),
            plan.sessionOriginElapsed.toString(),
            encFloat(calibrated?.xFraction),
            encFloat(calibrated?.yFraction),
            encFloat(calibrated?.widthFraction),
            encFloat(calibrated?.heightFraction),
            encBool(calibrated?.jitter ?: false),
            plan.calibratedRotation.toString(),
            encBool(advanced.handheldWarning),
        ).joinToString(SEP.toString())
    }

    fun decode(encoded: String?): RunPlan? = runCatching {
        val f = (encoded ?: return@runCatching null).split(SEP)
        if (f.size != FIELDS && f.size != FIELDS_BEFORE_HANDHELD) return@runCatching null
        if (f[0] != VERSION) return@runCatching null

        val targetPackage = decString(f[2])
        val mode = when (f[1]) {
            MODE_TIMELAPSE -> TriggerMode.Timelapse(targetPackage ?: return@runCatching null)
            MODE_PERIODIC_TAP -> TriggerMode.PeriodicTap(targetPackage)
            else -> return@runCatching null
        }
        val interval = f[3].toLongOrNull()?.takeIf { it > 0L } ?: return@runCatching null

        RunPlan(
            config = SessionConfig(
                mode = mode,
                intervalMs = interval,
                startDelayMs = f[4].toLongOrNull() ?: 0L,
                ramp = decRamp(f),
                stop = StopConditions(
                    maxShots = decInt(f[12]),
                    maxDurationMs = decLong(f[13]),
                    minBatteryPercent = decInt(f[14]),
                    minFreeBytes = decLong(f[15]),
                ),
                target = decTarget(f[16], f[17], f[18], f[19], f[20]),
                verifyCaptures = decBool(f[5]),
                minIntervalFloorMs = f[6].toLongOrNull() ?: 0L,
            ),
            advanced = AdvancedSettings(
                launchCameraMode = decString(f[21]),
                intervalAfterCapture = decBool(f[22]),
                ensureSelfTimerOff = decBool(f[23]),
                dismissPostCaptureUi = decBool(f[24]),
                keepScreenOn = decBool(f[25]),
                dimOverlay = decBool(f[26]),
                handheldWarning = f.getOrNull(34)?.let(::decBool) ?: AdvancedSettings().handheldWarning,
            ),
            sessionOriginElapsed = f[27].toLongOrNull() ?: return@runCatching null,
            calibratedTarget = decTarget(f[28], f[29], f[30], f[31], f[32]),
            calibratedRotation = f[33].toIntOrNull() ?: 0,
        )
    }.getOrNull()

    private fun decRamp(f: List<String>): RampSpec? {
        val curveName = decString(f[7]) ?: return null
        val curve = RampCurve.entries.firstOrNull { it.name == curveName } ?: return null
        val domainName = decString(f[9]) ?: return null
        val domain = RampDomain.entries.firstOrNull { it.name == domainName } ?: return null
        val end = decLong(f[8])?.takeIf { it > 0L } ?: return null
        return RampSpec(
            curve = curve,
            endIntervalMs = end,
            domain = domain,
            overShots = decInt(f[10]),
            overDurationMs = decLong(f[11]),
        )
    }

    private fun decTarget(x: String, y: String, w: String, h: String, jitter: String): TapTarget? {
        val xf = decFloat(x) ?: return null
        val yf = decFloat(y) ?: return null
        return TapTarget(
            xFraction = xf,
            yFraction = yf,
            widthFraction = decFloat(w) ?: 0f,
            heightFraction = decFloat(h) ?: 0f,
            jitter = decBool(jitter),
        )
    }

    // ---- field codecs ----------------------------------------------------------------------------
    // An empty field always means "absent", so a present string is marked and escaped: that keeps a
    // null package name distinguishable from an empty one, and keeps a camera mode the user typed
    // from ever splitting a record in half.

    private const val PRESENT = "s"

    private fun encString(value: String?): String = if (value == null) "" else PRESENT + escape(value)

    private fun decString(field: String): String? =
        if (field.isEmpty() || field[0] != PRESENT[0]) null else unescape(field.substring(1))

    private fun encLong(value: Long?): String = value?.toString() ?: ""

    private fun decLong(field: String): Long? = field.toLongOrNull()

    private fun encInt(value: Int?): String = value?.toString() ?: ""

    private fun decInt(field: String): Int? = field.toIntOrNull()

    private fun encFloat(value: Float?): String = value?.toString() ?: ""

    private fun decFloat(field: String): Float? = field.toFloatOrNull()

    private fun encBool(value: Boolean): String = if (value) "1" else "0"

    private fun decBool(field: String): Boolean = field == "1"

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                SEP -> out.append("\\u")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i == value.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (value[i + 1]) {
                '\\' -> out.append('\\')
                'u' -> out.append(SEP)
                else -> out.append(value[i + 1])
            }
            i += 2
        }
        return out.toString()
    }
}
