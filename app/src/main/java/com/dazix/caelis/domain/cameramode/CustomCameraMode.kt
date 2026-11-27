package com.dazix.caelis.domain.cameramode

/**
 * A camera mode the user taught the app: the taps and swipes that switch [packageName] into it,
 * recorded once over the camera app and replayed at session start.
 *
 * No Android API selects a mode in another app's camera, and the built-in profiles only know the
 * chips they have patterns for. A recording needs nothing from the camera app except that the user
 * can do it by hand, which is what makes it work for any of them.
 *
 * Positions are fractions of the screen in natural orientation (as [com.dazix.caelis.domain.TapTarget]),
 * [naturalWidthPx] x [naturalHeightPx] is the screen they were recorded on: a step replayed by position
 * is only replayed on that same screen.
 */
data class CustomCameraMode(
    val id: String,
    val name: String,
    val packageName: String,
    /** The app's label when the mode was saved; shown beside the name, never used to match. */
    val appLabel: String,
    val steps: List<RecordedStep>,
    /** What is selected once the mode is on, or null when the recording showed nothing that changed. */
    val proof: NodeSignature? = null,
    val naturalWidthPx: Int = 0,
    val naturalHeightPx: Int = 0,
    val createdAtMs: Long = 0L,
    /**
     * Choosing this mode also turns on "interval after the photo is finished", as the built-in Night
     * and Astro modes do: a mode whose captures run for seconds to minutes is cancelled by the next tap.
     */
    val intervalAfterCapture: Boolean = false,
) {
    /** The value stored in `launchCameraMode` for this mode. */
    val key: String get() = CustomModeKey.format(id)
}

/** One recorded gesture. [delayBeforeMs] is the pause the user left before it, already clamped. */
sealed interface RecordedStep {
    val delayBeforeMs: Long

    /**
     * A tap at ([x], [y]), natural fractions. [signature] identifies the element under the finger when
     * it had a view id or a label; without one the tap is replayed by position only.
     */
    data class Tap(
        val x: Float,
        val y: Float,
        val signature: NodeSignature?,
        override val delayBeforeMs: Long,
    ) : RecordedStep

    /** A swipe through [points] (natural fractions, at least two) taking [durationMs]. */
    data class Swipe(
        val points: List<Point>,
        val durationMs: Long,
        override val delayBeforeMs: Long,
    ) : RecordedStep
}

data class Point(val x: Float, val y: Float)

/**
 * What identifies an element of the camera app's screen: its view id, its label (content
 * description, else text) and class, plus where it was, in natural fractions.
 */
data class NodeSignature(
    val viewId: String?,
    val label: String?,
    val className: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** True when there is something to find it by other than its position. */
    val isIdentifiable: Boolean get() = !viewId.isNullOrBlank() || !label.isNullOrBlank()

    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f
}

/**
 * One element of the live screen, as the pure policies see it. [index] points back into the list
 * the caller built, so the caller can act on the real node. Bounds are natural fractions.
 */
data class NodeInfo(
    val index: Int,
    val viewId: String?,
    val label: String?,
    val className: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val clickable: Boolean,
    val visible: Boolean,
    val selected: Boolean,
) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun toSignature(): NodeSignature = NodeSignature(viewId, label, className, left, top, right, bottom)
}

/** `launchCameraMode` holds either a profile key ("night") or `custom:<id>` for a recorded mode. */
object CustomModeKey {
    const val PREFIX = "custom:"

    fun format(id: String): String = PREFIX + id

    /** The id of a recorded mode, or null when [key] is a profile key, blank or null. */
    fun idOf(key: String?): String? =
        key?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }
}
