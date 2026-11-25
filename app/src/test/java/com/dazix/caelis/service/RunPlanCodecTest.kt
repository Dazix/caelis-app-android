package com.dazix.caelis.service

import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.RampDomain
import com.dazix.caelis.domain.RampSpec
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.TriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The encoded plan is the only thing standing between a killed process and a lost session, and it is
 * read back by a different process than the one that wrote it. Anything it drops or mangles turns into
 * a session that resumes with the wrong interval, the wrong stop condition or the wrong shutter
 * position — all of which look like the engine misbehaving, hours later, on a device.
 */
class RunPlanCodecTest {

    /** The record separator [RunPlanCodec] joins fields with; only the damage cases need it. */
    private val SEP = '\u001F'

    @Test
    fun `every field of a fully specified plan survives a round trip`() {
        val plan = RunPlan(
            config = SessionConfig(
                mode = TriggerMode.Timelapse("com.google.android.GoogleCamera"),
                intervalMs = 45_000L,
                startDelayMs = 7_500L,
                ramp = RampSpec(
                    curve = RampCurve.EXPONENTIAL,
                    endIntervalMs = 120_000L,
                    domain = RampDomain.ELAPSED_TIME,
                    overShots = 240,
                    overDurationMs = 3_600_000L,
                ),
                stop = StopConditions(
                    maxShots = 500,
                    maxDurationMs = 7_200_000L,
                    minBatteryPercent = 15,
                    minFreeBytes = 2_147_483_648L,
                ),
                target = TapTarget(0.5f, 0.8734f, 0.12f, 0.09f, jitter = true),
                verifyCaptures = true,
                minIntervalFloorMs = 1_500L,
            ),
            advanced = AdvancedSettings(
                launchCameraMode = "Night Sight",
                intervalAfterCapture = true,
                ensureSelfTimerOff = true,
                dismissPostCaptureUi = true,
                keepScreenOn = true,
                dimOverlay = true,
                // Off, because on is the default and would survive a field that was never encoded.
                handheldWarning = false,
            ),
            sessionOriginElapsed = 918_273_645L,
            calibratedTarget = TapTarget(0.5f, 0.91f),
            calibratedRotation = 3,
        )

        assertEquals(plan, RunPlanCodec.decode(RunPlanCodec.encode(plan)))
    }

    @Test
    fun `a minimal plan round trips with every optional field still absent`() {
        val plan = RunPlan(
            config = SessionConfig(
                mode = TriggerMode.PeriodicTap(null),
                intervalMs = 1_000L,
            ),
            sessionOriginElapsed = 0L,
        )

        val restored = RunPlanCodec.decode(RunPlanCodec.encode(plan))

        assertEquals(plan, restored)
        assertNull(restored?.config?.ramp)
        assertNull(restored?.config?.target)
        assertNull(restored?.calibratedTarget)
        assertNull(restored?.config?.mode?.targetPackage)
    }

    /**
     * A `PeriodicTap` with no package and one with an empty package are different sessions: the first
     * taps whatever is on screen, the second would be pinned to a package that cannot exist. The
     * encoding has to keep null and "" apart.
     */
    @Test
    fun `an absent package stays absent and an empty one stays empty`() {
        val absent = RunPlan(
            config = SessionConfig(TriggerMode.PeriodicTap(null), 1_000L),
            sessionOriginElapsed = 1L,
        )
        val empty = RunPlan(
            config = SessionConfig(TriggerMode.PeriodicTap(""), 1_000L),
            sessionOriginElapsed = 1L,
        )

        assertNull(RunPlanCodec.decode(RunPlanCodec.encode(absent))?.config?.mode?.targetPackage)
        assertEquals("", RunPlanCodec.decode(RunPlanCodec.encode(empty))?.config?.mode?.targetPackage)
    }

    /**
     * `launchCameraMode` is free text copied off a camera app's own UI. A record it could split in
     * half, or an escape it could unbalance, would take the whole session down with it.
     */
    @Test
    fun `a camera mode containing separators and backslashes round trips`() {
        val nasty = "Night${SEP}Sight \\ \"Pro\" $SEP$SEP mode\\"
        val plan = RunPlan(
            config = SessionConfig(TriggerMode.Timelapse("com.example.cam"), 2_000L),
            advanced = AdvancedSettings(launchCameraMode = nasty),
            sessionOriginElapsed = 5L,
        )

        val restored = RunPlanCodec.decode(RunPlanCodec.encode(plan))

        assertEquals(nasty, restored?.advanced?.launchCameraMode)
        assertEquals(plan, restored)
    }

    @Test
    fun `a timelapse without a package cannot be decoded`() {
        // Only reachable through a damaged record: TriggerMode.Timelapse cannot hold a null package.
        val encoded = RunPlanCodec.encode(
            RunPlan(
                config = SessionConfig(TriggerMode.PeriodicTap(null), 1_000L),
                sessionOriginElapsed = 1L,
            ),
        )
        val fields = encoded.split(SEP).toMutableList()
        fields[1] = "T"

        assertNull(RunPlanCodec.decode(fields.joinToString(SEP.toString())))
    }

    @Test
    fun `damaged, truncated and foreign records decode to null instead of throwing`() {
        val good = RunPlanCodec.encode(
            RunPlan(
                config = SessionConfig(TriggerMode.Timelapse("com.example.cam"), 3_000L),
                sessionOriginElapsed = 42L,
            ),
        )
        val fields = good.split(SEP)

        assertNull(RunPlanCodec.decode(null))
        assertNull(RunPlanCodec.decode(""))
        assertNull(RunPlanCodec.decode("garbage"))
        assertNull("truncated", RunPlanCodec.decode(fields.dropLast(3).joinToString(SEP.toString())))
        assertNull("extra fields", RunPlanCodec.decode("$good${SEP}something"))
        assertNull("newer format", RunPlanCodec.decode(good.replaceFirst("v1", "v2")))
        assertNull(
            "no interval",
            RunPlanCodec.decode(fields.toMutableList().also { it[3] = "0" }.joinToString(SEP.toString())),
        )
    }

    /**
     * A run started by the version before `handheldWarning` existed is still live after the update
     * installs: its record has one field less and must resume, with the warning at its default, rather
     * than being written off as unreadable.
     */
    @Test
    fun `a record from before the handheld flag still resumes`() {
        val plan = RunPlan(
            config = SessionConfig(TriggerMode.Timelapse("com.example.cam"), 5_000L),
            advanced = AdvancedSettings(dimOverlay = true, handheldWarning = false),
            sessionOriginElapsed = 9L,
        )
        val older = RunPlanCodec.encode(plan).split(SEP).dropLast(1).joinToString(SEP.toString())

        val restored = RunPlanCodec.decode(older)

        assertEquals(plan.copy(advanced = plan.advanced.copy(handheldWarning = true)), restored)
    }

    /**
     * A ramp whose curve or domain enum no longer exists must cost the ramp, not the session: the
     * remaining fields are still a perfectly shootable interval series.
     */
    @Test
    fun `an unknown ramp curve leaves the rest of the plan intact`() {
        val plan = RunPlan(
            config = SessionConfig(
                mode = TriggerMode.Timelapse("com.example.cam"),
                intervalMs = 4_000L,
                ramp = RampSpec(RampCurve.LINEAR, 9_000L, RampDomain.SHOT_INDEX, overShots = 10),
            ),
            sessionOriginElapsed = 3L,
        )
        val fields = RunPlanCodec.encode(plan).split(SEP).toMutableList()
        fields[7] = "sSPIRAL"

        val restored = RunPlanCodec.decode(fields.joinToString(SEP.toString()))

        assertNull(restored?.config?.ramp)
        assertEquals(4_000L, restored?.config?.intervalMs)
    }
}
