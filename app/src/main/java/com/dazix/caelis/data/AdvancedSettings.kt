package com.dazix.caelis.data

/**
 * Every device/camera-app-specific extra. All opt-in, all default off, so a default-constructed
 * instance behaves exactly like a build that never had any of them.
 */
data class AdvancedSettings(
    val launchCameraMode: String? = null,
    /**
     * Count the interval from the end of each capture instead of from each tap: the next shot is
     * taken one interval after the previous photo is finished (in MediaStore, or the camera idle
     * again), and never while the camera is busy. For Night Sight / Astro, which run for minutes and
     * cancel the photo when the shutter is pressed again. Stored under the key of the adaptive
     * long-exposure wait it replaced, which only held the tap while known busy texts were on screen.
     */
    val intervalAfterCapture: Boolean = false,
    val ensureSelfTimerOff: Boolean = false,
    val dismissPostCaptureUi: Boolean = false,
    /**
     * Hold the screen awake while the session is *running*.
     *
     * On by default, and the one flag here that is not "off = today's behaviour": the engine holds a
     * `PARTIAL_WAKE_LOCK`, which keeps the CPU up but lets the display time out, and a dark screen
     * makes [com.dazix.caelis.service.SessionEngine] refuse to tap (there is no way to press
     * a shutter that is not on screen) — so an unattended run with this off pauses at the display
     * timeout and shoots nothing until somebody wakes the phone. Turning it off is meaningful only
     * for a supervised run.
     *
     * ### Where this actually lives
     * The floating panel is what holds the flag on its window, and it needs the value synchronously
     * inside a `WindowManager` layout pass, so the authoritative copy is
     * [com.dazix.caelis.overlay.OverlaySettings] (SharedPreferences) and this field mirrors
     * it — including the default, which is why it is `true` here as well. `MainViewModel` writes both
     * stores and displays the panel's value; nothing reads this copy to decide behaviour.
     */
    val keepScreenOn: Boolean = true,
    /**
     * Draw the session overlay dimmed, for shooting in the dark without the overlay lighting the
     * scene or the viewfinder. Off by default: the overlay is drawn at full opacity. Mirrors
     * [com.dazix.caelis.overlay.OverlayOptions.dimPanel] exactly as [keepScreenOn] does.
     */
    val dimOverlay: Boolean = false,
    /**
     * Warn when a time-lapse looks shot from the hand rather than a tripod: on the home screen before
     * the start, as a question when Start is pressed, and on the panel and the notification while it
     * runs. Only ever a warning. On by default; off is for a hyperlapse shot from the hand on purpose.
     */
    val handheldWarning: Boolean = true,
)
