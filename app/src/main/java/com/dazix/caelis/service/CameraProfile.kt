package com.dazix.caelis.service

/**
 * Everything the tapping engine needs to know about one camera app, expressed as data only.
 *
 * All pattern lists are matched with [Regex.containsMatchIn] against the node's
 * `viewIdResourceName`, `contentDescription` or `text`, case-insensitively (see [pattern]), so an
 * entry may be a plain word ("shutter") or a fully anchored expression
 * (`.*(shutter|capture)(_button)?$`). Nothing here is allowed to imply a code path: supporting a new
 * camera app must be a single new entry in [CameraProfiles], never a new branch in the dispatcher or
 * the engine.
 *
 * That rule is why [modeAliases] exists: the labels a camera app puts on its mode chips are
 * localized ("Photo" / "Foto", "Night Sight" / "Noční vidění"), so a mode can only be selected by
 * matching *profile data* against the on-screen label. The stable thing the rest of the app passes
 * around is a **mode key** — [MODE_PHOTO], [MODE_NIGHT], [MODE_ASTRO] … — never a display string.
 */
data class CameraProfile(
    /** Stable id used in logs. */
    val id: String,
    /** Packages this profile applies to. Empty for the generic fallback. */
    val packageNames: List<String>,
    /** Matched against `viewIdResourceName` first — the most reliable signal. */
    val shutterViewIdPatterns: List<Regex>,
    /** Matched against `contentDescription`, then `text`, when no view id matched. */
    val shutterDescriptionPatterns: List<Regex>,
    /** How long after the tap the app is expected to be busy writing the frame. */
    val captureLatencyMs: Long,
    /**
     * The floor this profile asks for. Read [minIntervalMs] instead: an interval shorter than the
     * app's own capture latency cannot actually be delivered, so the declared value is only a lower
     * bound on the real one.
     */
    private val declaredMinIntervalMs: Long,
    /** Nodes proving the app currently sits in still-photo mode. */
    val photoModeIndicatorPatterns: List<Regex> = emptyList(),
    /** Mode chips ("Photo", "Night Sight", …) that can be tapped to switch modes. */
    val modeChipPatterns: List<Regex> = emptyList(),
    /**
     * View ids of the mode carousel's chips. When set, only such a node is a mode chip, whatever its
     * label: Pixel Camera labels its minibar Night Sight icon "Noční vidění" exactly like the chip,
     * and tapping that icon opened the photo settings sheet instead of switching the mode.
     */
    val modeChipViewIdPatterns: List<Regex> = emptyList(),
    /**
     * Mode key -> patterns matching that mode's chip label, in every language this profile supports.
     * Keys are the stable identifiers in the companion ([MODE_PHOTO], [MODE_NIGHT], [MODE_ASTRO]);
     * the UI stores a key and never a label.
     */
    val modeAliases: Map<String, List<Regex>> = emptyMap(),
    /** Post-capture chips/thumbnails/toasts that should be dismissed between shots. */
    val postCaptureDismissPatterns: List<Regex> = emptyList(),
    /**
     * Nodes shown while a long exposure is still being captured or processed. Optional: a disabled
     * shutter already means busy for every app ([ShutterDispatcher.isCameraBusy]).
     */
    val longExposureBusyPatterns: List<Regex> = emptyList(),
    /** Nodes proving the self timer is currently armed. */
    val selfTimerOnPatterns: List<Regex> = emptyList(),
    /** The "timer off" choice inside the timer picker. */
    val selfTimerOffPatterns: List<Regex> = emptyList(),
    /** How long a tap is held back while the camera still looks busy *before* it, at most. */
    val longExposureMaxWaitMs: Long = 45_000L,
) {

    /**
     * Hard floor for the interval, never below what one capture physically costs.
     *
     * A profile that declares a floor shorter than `captureLatencyMs + TAP_DURATION_MS` is asking
     * for shots the app cannot deliver: the engine would hold every shot behind the capture latency,
     * re-anchor the series on every single shot, and the reported cadence would drift away from the
     * requested one for the whole session. Clamping here means the requested interval is one the
     * camera app can actually keep.
     */
    val minIntervalMs: Long =
        maxOf(declaredMinIntervalMs, captureLatencyMs + ShutterDispatcher.TAP_DURATION_MS)

    /**
     * Chips eligible to be tapped for a mode switch. An explicit [modeChipPatterns] wins; a profile
     * that only lists [modeAliases] constrains the tap to those labels, which is already specific
     * enough that a random node cannot be hit.
     */
    val eligibleModeChipPatterns: List<Regex> =
        modeChipPatterns.ifEmpty { modeAliases.values.flatten() }

    fun isShutterViewId(value: CharSequence?): Boolean = shutterViewIdPatterns.matchesAny(value)

    fun isShutterLabel(value: CharSequence?): Boolean = shutterDescriptionPatterns.matchesAny(value)

    /** Patterns for [modeKey], or empty when this profile knows no such mode. */
    fun modePatterns(modeKey: String?): List<Regex> {
        val key = modeKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return modeAliases[key] ?: emptyList()
    }

    /** True when [label] — a localized on-screen chip label — denotes the mode [modeKey]. */
    fun matchesMode(modeKey: String?, label: CharSequence?): Boolean =
        modePatterns(modeKey).matchesAny(label)

    /**
     * True when a node with [viewId] and [label] is the chip that switches to [modeKey]. The label
     * must denote the mode and be an eligible chip label, and with [modeChipViewIdPatterns] set the
     * node must also be one of the carousel's chips: other controls carry the same words (the
     * minibar icon, the shutter's "Vyfotit pomocí nočního vidění").
     */
    fun isModeChipFor(modeKey: String?, viewId: CharSequence?, label: CharSequence?): Boolean {
        if (!matchesMode(modeKey, label)) return false
        if (eligibleModeChipPatterns.isNotEmpty() && !eligibleModeChipPatterns.matchesAny(label)) return false
        return modeChipViewIdPatterns.isEmpty() || modeChipViewIdPatterns.matchesAny(viewId)
    }

    /** True when this profile can switch to [modeKey] at all; the UI uses it to enable the setting. */
    fun supportsMode(modeKey: String?): Boolean = modePatterns(modeKey).isNotEmpty()

    /** Mode keys this profile can select, for a settings screen that wants to list them. */
    fun supportedModeKeys(): Set<String> = modeAliases.keys

    companion object {
        /**
         * Stable mode keys. Everything outside this file stores these, never a display label, and a
         * profile only lists a key in [modeAliases] when switching into it makes sense for a series
         * — which is why a profile can recognise a "Portrait" chip without offering it as a mode.
         */
        const val MODE_PHOTO = "photo"
        const val MODE_NIGHT = "night"
        const val MODE_ASTRO = "astro"
    }
}

/** Builds a case-insensitive pattern list; the only place regex options are chosen. */
internal fun pattern(vararg raw: String): List<Regex> =
    raw.map { Regex(it, RegexOption.IGNORE_CASE) }

/** True when [value] is non-blank and any pattern matches somewhere inside it. */
internal fun List<Regex>.matchesAny(value: CharSequence?): Boolean {
    if (isEmpty() || value.isNullOrBlank()) return false
    return any { it.containsMatchIn(value) }
}
