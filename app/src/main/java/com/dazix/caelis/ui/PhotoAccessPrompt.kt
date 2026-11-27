package com.dazix.caelis.ui

/**
 * Photo access for [com.dazix.caelis.data.AdvancedSettings.intervalAfterCapture].
 *
 * Unlike capture verification, that setting works without photo access: the end of a photo is then
 * judged by the camera app's shutter. Access only makes it firmer (a finished photo in MediaStore), so
 * the switch never waits for an answer and is never turned off by a refusal; it explains first and
 * asks only when the user agrees. Framework-free so the rules are pinned by a JVM test.
 */
object PhotoAccessPrompt {

    enum class Access { GRANTED, PARTIAL, MISSING }

    /** Full access wins: "Select photos" may be recorded next to a later "Allow all". */
    fun access(granted: Boolean, partial: Boolean): Access = when {
        granted -> Access.GRANTED
        partial -> Access.PARTIAL
        else -> Access.MISSING
    }

    /**
     * Only the user's own flip of the switch explains and asks. Choosing Night Sight or Astro turns the
     * setting on too ([LaunchModeOptions.choose]), and a dialog popping up over a mode choice would
     * read as something the mode requires; the status under the switch says it there instead.
     * Partial access explains as well, and its Allow leads to the app's settings ([allowOpensSettings]).
     */
    fun explainOnToggle(turnedOn: Boolean, access: Access): Boolean =
        turnedOn && access != Access.GRANTED

    /**
     * With "Select photos" already granted, asking again only opens the picker for more photos
     * (seen on Android 17); "Allow all" is then reachable only in the app's settings, so that is
     * where Allow goes.
     */
    fun allowOpensSettings(access: Access): Boolean = access == Access.PARTIAL

    /**
     * Capture verification after the system dialog: refused or dismissed, it is switched off, since it
     * cannot work without photos. "Select photos" keeps it on, as the user did grant something, and
     * the screen says what is missing.
     */
    fun verifyOffAfterAnswer(access: Access): Boolean = access == Access.MISSING

    /** The status line is shown while the setting can take effect. */
    fun showStatus(enabled: Boolean, hasApp: Boolean): Boolean = enabled && hasApp
}
