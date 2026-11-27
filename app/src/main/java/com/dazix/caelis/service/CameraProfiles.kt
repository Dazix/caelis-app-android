package com.dazix.caelis.service

import com.dazix.caelis.domain.TriggerMode

// Mode chip labels, EN + CS (with and without diacritics: OEM labels are inconsistent). Shared by
// several profiles, which is why they are file-level rather than repeated per entry.
private val photoAliases: List<Regex> = pattern(
    "\\bphoto\\b",
    "\\bfoto\\b|\\bfotka\\b|\\bfotografie\\b",
)

private val nightAliases: List<Regex> = pattern(
    "night ?sight",
    "\\bnight( mode)?\\b",
    "noční|nocni",
)

private val astroAliases: List<Regex> = pattern(
    "\\bastro",
    "hvězdná obloha|hvezdna obloha",
)

private val portraitAliases: List<Regex> = pattern(
    "\\bportrait\\b",
    "\\bportrét\\b|\\bportret\\b",
)

/**
 * The profile table. Adding a camera app is one new [CameraProfile] entry in [table] — no new code
 * path anywhere else. Unknown packages fall back to [generic], which is deliberately conservative.
 */
object CameraProfiles {

    /**
     * Works with most AOSP-derived camera apps: the shutter is nearly always a view whose id ends in
     * `shutter`/`capture`, optionally suffixed `_button`, and is labelled in the device language.
     */
    val generic: CameraProfile = CameraProfile(
        id = "generic",
        packageNames = emptyList(),
        shutterViewIdPatterns = pattern(
            ".*(shutter|capture)(_button)?$",
        ),
        shutterDescriptionPatterns = pattern(
            // English
            "\\bshutter\\b",
            "take (a )?(photo|picture|shot)",
            "\\bcapture\\b",
            // Czech, with and without diacritics because OEM labels are inconsistent
            "spoušť|spoust",
            "závěrka|zaverka",
            "vyfot",
            "fotografovat",
            "pořídit snímek|poridit snimek",
        ),
        captureLatencyMs = 900L,
        declaredMinIntervalMs = 900L,
        modeAliases = mapOf(
            CameraProfile.MODE_PHOTO to photoAliases,
            CameraProfile.MODE_NIGHT to nightAliases,
        ),
        postCaptureDismissPatterns = pattern(
            "\\bdismiss\\b",
            "zavřít|zavrit",
            "\\bzrušit\\b|\\bzrusit\\b",
        ),
    )

    /**
     * [TriggerMode.PeriodicTap] drives whatever the user aims at — a metronome for an arbitrary app,
     * not a camera. Nothing may be inferred about it: no shutter patterns (the calibrated point is
     * the whole truth), no capture latency to hold the next tap behind, and a floor that is only the
     * physical length of one synthetic tap. Using a camera profile here is what made tapping a
     * non-camera app faster than 900 ms impossible.
     */
    val passthrough: CameraProfile = CameraProfile(
        id = "passthrough",
        packageNames = emptyList(),
        shutterViewIdPatterns = emptyList(),
        shutterDescriptionPatterns = emptyList(),
        captureLatencyMs = 0L,
        declaredMinIntervalMs = 0L,
    )

    /**
     * Google Camera (Pixel). HDR+ and especially Night Sight keep the pipeline busy far longer than
     * a plain AOSP capture, so the latency and the interval floor are both raised and the busy
     * patterns tell [com.dazix.caelis.data.AdvancedSettings.intervalAfterCapture] that a
     * capture is still running.
     */
    private val pixelCamera: CameraProfile = CameraProfile(
        id = "pixel-camera",
        packageNames = listOf(
            "com.google.android.GoogleCamera",
            "com.google.android.apps.camera",
            "com.google.android.apps.cameralite",
        ),
        shutterViewIdPatterns = pattern(
            ".*/(shutter_button|shutter_button_container|shutter_button_layout)$",
            ".*/(camera_shutter|capture_button|shutter)$",
        ),
        shutterDescriptionPatterns = pattern(
            "\\bshutter\\b",
            "take (a )?(photo|picture)",
            "start (recording|video)",
            "spoušť|spoust",
            "vyfot",
        ),
        // HDR+ / Night Sight keep writing well past the tap; a shorter value re-taps into a busy UI.
        captureLatencyMs = 2_200L,
        declaredMinIntervalMs = 1_500L,
        photoModeIndicatorPatterns = pattern(
            "\\bphoto\\b",
            "\\bfoto(grafie|ka)?\\b",
        ),
        modeChipPatterns = photoAliases + nightAliases + astroAliases + portraitAliases +
            pattern("\\bmotion\\b", "\\bpohyb\\b"),
        // The minibar's Night Sight icon (`minibar_item_ext2`, "Noční vidění") sits above the
        // carousel in the tree with the chip's own label; only the carousel switches the mode.
        modeChipViewIdPatterns = pattern(".*/mode_chip_text$"),
        modeAliases = mapOf(
            CameraProfile.MODE_PHOTO to photoAliases,
            CameraProfile.MODE_NIGHT to nightAliases,
            CameraProfile.MODE_ASTRO to astroAliases,
        ),
        postCaptureDismissPatterns = pattern(
            "\\bdismiss\\b",
            "close (preview|review)",
            "\\bretake\\b",
            "zavřít|zavrit",
        ),
        longExposureBusyPatterns = pattern(
            "hold still",
            "keep still",
            "\\bcapturing\\b",
            "\\bprocessing\\b",
            "night sight.*(active|in progress)",
            "astrophotography.*(active|in progress)",
            "držte|drzte",
            "zpracování|zpracovani",
            "\\bsnímání\\b|\\bsnimani\\b",
            // During Astro (Pixel 7 Pro, measured) the shutter itself reads this for the whole
            // exposure, and pressing it cancels the photo.
            "zrušit fotku|zrusit fotku",
            "cancel (photo|capture)",
        ),
        selfTimerOnPatterns = pattern(
            "timer (3|5|10)",
            "\\b(3|5|10) ?s(ec(onds)?)? timer",
            "samospoušť.*(3|5|10)|samospoust.*(3|5|10)",
        ),
        selfTimerOffPatterns = pattern(
            "timer off",
            "no timer",
            "samospoušť vypnuta|samospoust vypnuta",
            "\\bvypnuto\\b",
        ),
        longExposureMaxWaitMs = 60_000L,
    )

    /** The whole table. One entry per camera app. */
    private val table: List<CameraProfile> = listOf(
        pixelCamera,
    )

    /** Never null: an unknown or absent package resolves to [generic]. */
    fun forPackage(packageName: String?): CameraProfile {
        if (packageName.isNullOrBlank()) return generic
        return table.firstOrNull { packageName in it.packageNames } ?: generic
    }

    /**
     * The profile a session should actually run with. Only [TriggerMode.Timelapse] is driving a
     * known camera app; [TriggerMode.PeriodicTap] gets [passthrough] so no camera assumption (floor,
     * capture latency, busy patterns, node search) applies to it.
     */
    fun forTrigger(mode: TriggerMode): CameraProfile = when (mode) {
        is TriggerMode.Timelapse -> forPackage(mode.targetPackage)
        is TriggerMode.PeriodicTap -> passthrough
    }

    /**
     * The interval floor actually enforced for [mode]: the camera app's floor for a time-lapse, and
     * for a periodic tap only the length of one synthetic tap, so a non-camera app can be tapped as
     * fast as the user wants. [userFloorMs] is whatever the user configured.
     */
    fun effectiveFloorMs(mode: TriggerMode, userFloorMs: Long): Long = when (mode) {
        is TriggerMode.Timelapse -> maxOf(userFloorMs, forPackage(mode.targetPackage).minIntervalMs)
        is TriggerMode.PeriodicTap -> maxOf(userFloorMs, ShutterDispatcher.TAP_DURATION_MS)
    }

    /** All profiles, generic last — for a settings screen that wants to show what is supported. */
    fun all(): List<CameraProfile> = table + generic
}
