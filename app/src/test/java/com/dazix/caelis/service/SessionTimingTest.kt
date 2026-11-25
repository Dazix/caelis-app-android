package com.dazix.caelis.service

import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.SessionPlanner
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's decisions, extracted so they can be checked without a device. */
class SessionTimingTest {

    // ---- capture-latency hold --------------------------------------------------------------------

    @Test
    fun `the hold is the capture latency when the next shot is far away`() {
        assertEquals(2_200L, SessionTiming.postCaptureHoldMs(2_200L, gapToNextTargetMs = 30_000L))
    }

    @Test
    fun `the hold never eats into the next shot`() {
        // The failure this prevents: holding 2.2 s with 800 ms to go re-anchors the series, so every
        // shot slips and the interval the UI reports stops being the one the session delivers.
        assertEquals(800L, SessionTiming.postCaptureHoldMs(2_200L, gapToNextTargetMs = 800L))
    }

    @Test
    fun `a session that is already late does not hold at all`() {
        assertEquals(0L, SessionTiming.postCaptureHoldMs(2_200L, gapToNextTargetMs = 0L))
        assertEquals(0L, SessionTiming.postCaptureHoldMs(2_200L, gapToNextTargetMs = -5_000L))
    }

    @Test
    fun `a profile with no capture latency never holds`() {
        assertEquals(0L, SessionTiming.postCaptureHoldMs(0L, gapToNextTargetMs = 60_000L))
    }

    @Test
    fun `the clamped profile floor makes the hold fit the interval by construction`() {
        val pixel = CameraProfiles.forPackage("com.google.android.GoogleCamera")
        val interval = CameraProfiles.effectiveFloorMs(
            TriggerMode.Timelapse("com.google.android.GoogleCamera"),
            userFloorMs = 0L,
        )

        val hold = SessionTiming.postCaptureHoldMs(pixel.captureLatencyMs, gapToNextTargetMs = interval)

        assertEquals(pixel.captureLatencyMs, hold)
        assertTrue("the hold must leave room for the tap itself", hold < interval)
    }

    // ---- achieved interval -----------------------------------------------------------------------

    @Test
    fun `before the first tap the planned interval is reported`() {
        assertEquals(1_000L, SessionTiming.achievedIntervalMs(50_000L, lastShotElapsed = null, nominalMs = 1_000L))
    }

    @Test
    fun `the reported interval is the real gap between two taps, not the requested one`() {
        val achieved = SessionTiming.achievedIntervalMs(
            targetElapsed = 12_400L,
            lastShotElapsed = 10_000L,
            nominalMs = 1_000L,
        )

        assertEquals(2_400L, achieved)
    }

    @Test
    fun `a target in the past reports no interval rather than a negative one`() {
        assertEquals(0L, SessionTiming.achievedIntervalMs(9_000L, lastShotElapsed = 10_000L, nominalMs = 1_000L))
    }

    // ---- start delay after launching the camera app -----------------------------------------------

    @Test
    fun `launching the camera app buys the first shot time to see it`() {
        assertEquals(SessionTiming.LAUNCH_SETTLE_MS, SessionTiming.startDelayMs(0L, launchedTargetApp = true))
    }

    @Test
    fun `a start delay the user asked for is never shortened`() {
        assertEquals(30_000L, SessionTiming.startDelayMs(30_000L, launchedTargetApp = true))
    }

    @Test
    fun `nothing is added when no app was launched`() {
        assertEquals(0L, SessionTiming.startDelayMs(0L, launchedTargetApp = false))
        assertEquals(0L, SessionTiming.startDelayMs(-5L, launchedTargetApp = false))
    }

    // ---- which app has to be in front ------------------------------------------------------------

    /**
     * The launch and the foreground guard must name the same app. They did not: `launchTargetApp` ran
     * only for a time-lapse while the guard enforced `mode.targetPackage` for both modes, so a
     * periodic tap with an app chosen in the picker auto-paused on shot 0 with "not the app in front"
     * and took nothing until the user switched apps by hand.
     */
    @Test
    fun `a periodic tap that names an app has to have that app in front, so it is launched too`() {
        val mode = TriggerMode.PeriodicTap("com.example.notacamera")

        assertEquals("com.example.notacamera", SessionTiming.foregroundPackageFor(mode))
    }

    @Test
    fun `a time lapse always names its camera app`() {
        assertEquals(
            "com.google.android.GoogleCamera",
            SessionTiming.foregroundPackageFor(TriggerMode.Timelapse("com.google.android.GoogleCamera")),
        )
    }

    @Test
    fun `a periodic tap on whatever is on screen names nothing, so nothing is launched or guarded`() {
        assertNull(SessionTiming.foregroundPackageFor(TriggerMode.PeriodicTap(null)))
    }

    // ---- capture verification: whether the shot is verified at all --------------------------------

    @Test
    fun `a baseline that was taken arms verification`() {
        val arming = SessionTiming.verifyArming(requested = true, verifiable = true, baseline = "v2|100|7|99")

        assertEquals(VerifyArming.ARMED, arming)
        assertTrue(arming.armsVerification)
        assertFalse(arming.warnsUnverified)
    }

    /**
     * The defect: `available()` stays true when the MediaStore *query* fails, and
     * `hasNewImageSince(null)` is false by contract. Arming on a missing baseline therefore walked
     * every shot through KEEP_POLLING -> RETRY -> GIVE_UP — two taps per frame, ~24 s per shot on the
     * Pixel profile, and a "no new photo" warning on a camera that works. Not verifying this shot, and
     * saying so, is the honest degradation.
     */
    @Test
    fun `a baseline that could not be taken does not verify the shot, and says so`() {
        val arming = SessionTiming.verifyArming(requested = true, verifiable = true, baseline = null)

        assertEquals(VerifyArming.NO_BASELINE, arming)
        assertFalse(arming.armsVerification)
        assertTrue(arming.warnsUnverified)
    }

    @Test
    fun `without media access nothing is verified and the session does not nag once per shot`() {
        // Session setup explains this one in full; repeating it on every shot would bury the warnings
        // that are actually about the camera.
        val arming = SessionTiming.verifyArming(requested = true, verifiable = false, baseline = null)

        assertEquals(VerifyArming.NO_MEDIA_ACCESS, arming)
        assertFalse(arming.armsVerification)
        assertFalse(arming.warnsUnverified)
    }

    @Test
    fun `verification that was never asked for is silent whatever the store says`() {
        for (baseline in listOf(null, "v2|100|7|99")) {
            val arming = SessionTiming.verifyArming(requested = false, verifiable = true, baseline = baseline)

            assertEquals(VerifyArming.NOT_REQUESTED, arming)
            assertFalse(arming.armsVerification)
            assertFalse(arming.warnsUnverified)
        }
    }

    /**
     * The two halves of the fix have to agree: a shot that is not armed never reaches [awaitCapture],
     * so it can never produce the RETRY step that the missing baseline used to guarantee.
     */
    @Test
    fun `an unarmed shot never gets as far as a retry`() {
        val arming = SessionTiming.verifyArming(requested = true, verifiable = true, baseline = null)

        assertFalse(arming.armsVerification)
        // What it *would* have decided, had the engine polled with a baseline it never got.
        assertEquals(
            VerifyStep.RETRY,
            SessionTiming.verifyStep(
                verifiable = true,
                frameVisible = false,
                windowExpired = true,
                alreadyRetried = false,
            ),
        )
    }

    // ---- capture verification: what one look at MediaStore means ---------------------------------

    @Test
    fun `a frame that is already there resolves the shot on the spot`() {
        assertEquals(
            VerifyStep.RESOLVED,
            SessionTiming.verifyStep(
                verifiable = true,
                frameVisible = true,
                windowExpired = false,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun `a capture nobody can judge is resolved, not retried`() {
        // No media permission: an unverifiable capture is not a missing one, and re-tapping every
        // shot of a session the engine simply cannot see is worse than not verifying at all.
        for (retried in listOf(false, true)) {
            assertEquals(
                VerifyStep.RESOLVED,
                SessionTiming.verifyStep(
                    verifiable = false,
                    frameVisible = false,
                    windowExpired = true,
                    alreadyRetried = retried,
                ),
            )
        }
    }

    @Test
    fun `a window that is still open only polls again`() {
        // The Pixel publishes its row seconds past the capture latency: re-tapping before the window
        // has fully expired is what once doubled every shot of a verified session.
        assertEquals(
            VerifyStep.KEEP_POLLING,
            SessionTiming.verifyStep(
                verifiable = true,
                frameVisible = false,
                windowExpired = false,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun `an expired window retries once, and only once`() {
        assertEquals(
            VerifyStep.RETRY,
            SessionTiming.verifyStep(
                verifiable = true,
                frameVisible = false,
                windowExpired = true,
                alreadyRetried = false,
            ),
        )
        assertEquals(
            VerifyStep.GIVE_UP,
            SessionTiming.verifyStep(
                verifiable = true,
                frameVisible = false,
                windowExpired = true,
                alreadyRetried = true,
            ),
        )
    }

    /**
     * The ordering defect this pins down: with the shot counted at the tap instead of here, the guard
     * tick completed a `maxShots = 1` session while the first window was still open, which cancelled
     * the pending poll — the engine pressed the shutter once and never performed the documented
     * re-tap. A step that owes a re-tap must therefore not be a step that can count the shot.
     */
    @Test
    fun `a retry is not a resolution, so it can neither count a shot nor complete a session`() {
        assertFalse(VerifyStep.RETRY.resolvesShot)
        assertFalse(VerifyStep.KEEP_POLLING.resolvesShot)
        assertTrue(VerifyStep.RESOLVED.resolvesShot)
        assertTrue(VerifyStep.GIVE_UP.resolvesShot)
    }

    /**
     * The whole life of a capture that never lands, one look at a time: poll while the window is
     * open, re-tap exactly once when it expires, poll the retry's own window, and only then is the
     * shot resolved — one shot, two taps, and the session may complete on neither of the first three
     * steps.
     */
    @Test
    fun `a capture that never appears costs one retry and resolves exactly once`() {
        var retried = false
        val steps = ArrayList<VerifyStep>()
        // Two windows, sampled either side of each expiry: open, expired, open, expired.
        for (expired in listOf(false, true, false, true)) {
            val step = SessionTiming.verifyStep(
                verifiable = true,
                frameVisible = false,
                windowExpired = expired,
                alreadyRetried = retried,
            )
            steps += step
            if (step == VerifyStep.RETRY) retried = true
        }

        assertEquals(
            listOf(
                VerifyStep.KEEP_POLLING,
                VerifyStep.RETRY,
                VerifyStep.KEEP_POLLING,
                VerifyStep.GIVE_UP,
            ),
            steps,
        )
        assertEquals(1, steps.count { it == VerifyStep.RETRY })
        assertEquals(1, steps.count { it.resolvesShot })
    }

    // ---- where stop conditions apply --------------------------------------------------------------

    @Test
    fun `waiting, running and paused sessions are all still alive`() {
        assertTrue(SessionTiming.isAlive(SessionState.WaitingToStart(1_000L)))
        assertTrue(SessionTiming.isAlive(SessionState.Running))
        assertTrue(SessionTiming.isAlive(SessionState.Paused))
    }

    @Test
    fun `idle and stopped sessions are not`() {
        assertFalse(SessionTiming.isAlive(SessionState.Idle))
        assertFalse(SessionTiming.isAlive(SessionState.Stopped(StopReason.USER)))
        assertFalse(SessionTiming.isAlive(SessionState.Stopped(StopReason.COMPLETED, "done")))
    }

    /**
     * The guard tick is what makes the two evaluation points below matter: with stop conditions only
     * checked after a shot, a half-hour interval overran the limit by a whole interval and a paused
     * session never noticed at all.
     */
    @Test
    fun `a long interval session stops within one guard tick of its duration limit`() {
        val planner = SessionPlanner(
            SessionConfig(
                mode = TriggerMode.Timelapse("com.acme.cam"),
                intervalMs = 30 * 60_000L,
                stop = StopConditions(maxDurationMs = 60 * 60_000L),
            ),
        )
        val limit = 60 * 60_000L

        // Just before the limit nothing stops; one tick later it does — long before the next shot,
        // which is another 30 minutes out.
        assertNull(planner.stopReasonFor(2, limit - SessionEngine.GUARD_TICK_MS, null, null))
        assertEquals(StopReason.MAX_DURATION, planner.stopReasonFor(2, limit, null, null))
        assertTrue(SessionEngine.GUARD_TICK_MS < 30 * 60_000L)
    }

    // ---- interval after capture ---------------------------------------------------------------

    private fun step(
        frameVisible: Boolean = false,
        canSeeFrames: Boolean = true,
        cameraBusy: Boolean = false,
        sinceTapMs: Long = 5_000L,
        idleForMs: Long = 0L,
    ) = SessionTiming.captureWaitStep(frameVisible, canSeeFrames, cameraBusy, sinceTapMs, idleForMs)

    @Test
    fun `the photo appearing in MediaStore ends the capture`() {
        assertEquals(CaptureWaitStep.FINISHED, step(frameVisible = true))
    }

    @Test
    fun `a photo seen while the camera is still busy does not end the capture`() {
        // Pixel Camera inserts its row (pending) at the shutter press. A MediaStore observer that
        // counts INSERTs (PR #68, no READ_MEDIA_IMAGES) sees it then, minutes before Astro is done.
        assertEquals(CaptureWaitStep.WAIT, step(frameVisible = true, cameraBusy = true, sinceTapMs = 3_000L))
        assertEquals(
            CaptureWaitStep.FINISHED,
            step(frameVisible = true, cameraBusy = true, sinceTapMs = SessionTiming.CAPTURE_MAX_WAIT_MS),
        )
    }

    @Test
    fun `a busy camera is waited for, for minutes`() {
        // Pixel 7 Pro Astro: the countdown started at 4:01 and the shutter read "Zrušit fotku" the
        // whole time; a tap there cancels the photo.
        assertEquals(CaptureWaitStep.WAIT, step(cameraBusy = true, sinceTapMs = 4 * 60_000L))
    }

    @Test
    fun `a camera that never finishes is given up on at the cap`() {
        assertEquals(
            CaptureWaitStep.TIMED_OUT,
            step(cameraBusy = true, sinceTapMs = SessionTiming.CAPTURE_MAX_WAIT_MS),
        )
    }

    @Test
    fun `without MediaStore access an idle camera is the end of the capture`() {
        assertEquals(CaptureWaitStep.FINISHED, step(canSeeFrames = false, cameraBusy = false))
        assertEquals(CaptureWaitStep.WAIT, step(canSeeFrames = false, cameraBusy = true))
    }

    @Test
    fun `an idle camera gets a grace period for its photo to be published`() {
        // The row appeared ~4 s after the Pixel's UI came back.
        assertEquals(CaptureWaitStep.WAIT, step(idleForMs = 4_000L))
        assertEquals(
            CaptureWaitStep.NO_FRAME,
            step(idleForMs = SessionTiming.CAPTURE_PUBLISH_GRACE_MS),
        )
    }

    @Test
    fun `the next shot is one interval after the capture ended`() {
        // Shot 0 at 0, interval 10 s, so shot 1 was due at 10 s; the capture ended at 245 s.
        assertEquals(245_000L, SessionTiming.anchorShiftAfterCapture(10_000L, 245_000L, 10_000L))
    }

    @Test
    fun `a short capture still moves the next shot behind its end`() {
        // A 2 s capture: the next shot is 2 s later than the absolute schedule would put it.
        assertEquals(2_000L, SessionTiming.anchorShiftAfterCapture(10_000L, 2_000L, 10_000L))
    }

    @Test
    fun `the shift never pulls the series earlier`() {
        assertEquals(0L, SessionTiming.anchorShiftAfterCapture(30_000L, 2_000L, 10_000L))
    }

    @Test
    fun `the first shot holds until the switched mode has settled`() {
        val switchedAt = 1_000L
        val settle = SessionTiming.MODE_SETTLE_MS
        assertEquals(settle - 500L, SessionTiming.modeSettleHoldMs(switchedAt, now = switchedAt + 500L))
        assertEquals(0L, SessionTiming.modeSettleHoldMs(switchedAt, now = switchedAt + settle))
        assertEquals(0L, SessionTiming.modeSettleHoldMs(switchedAtElapsed = null, now = 10L))
    }

    @Test
    fun `a paused session still hits its battery and storage limits on the guard tick`() {
        val planner = SessionPlanner(
            SessionConfig(
                mode = TriggerMode.Timelapse("com.acme.cam"),
                intervalMs = 5_000L,
                stop = StopConditions(minBatteryPercent = 20, minFreeBytes = 500L),
            ),
        )

        assertTrue(SessionTiming.isAlive(SessionState.Paused))
        assertEquals(StopReason.LOW_BATTERY, planner.stopReasonFor(3, 1_000L, 19, 10_000L))
        assertEquals(StopReason.LOW_STORAGE, planner.stopReasonFor(3, 1_000L, 80, 100L))
        assertNull(planner.stopReasonFor(3, 1_000L, null, null))
    }
}
