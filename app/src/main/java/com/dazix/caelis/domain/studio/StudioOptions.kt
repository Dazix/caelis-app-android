package com.dazix.caelis.domain.studio

/** What the user chose for Studio's outputs; one set for every session, kept between visits. */
data class StudioOptions(
    val fps: Int = 30,
    val resolution: Resolution = Resolution.FHD,
    val aspect: Aspect = Aspect.ORIGINAL,
    /** How a picture of another aspect fills the frame; the crop's zoom and place (0..1, 0.5 centred). */
    val framing: Framing = Framing.CROP,
    val zoom: Float = 1f,
    val cropX: Float = 0.5f,
    val cropY: Float = 0.5f,
    val deflicker: Boolean = true,
    val joinWithoutAudio: Boolean = false,
) {
    /** A stored value outside the choices (an older build, a damaged file) falls back to the default. */
    fun sanitized(): StudioOptions {
        fun unit(v: Float) = v.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0.5f
        return copy(
            fps = if (fps in FPS_CHOICES) fps else StudioOptions().fps,
            zoom = zoom.takeIf { !it.isNaN() }?.coerceIn(OutputPlan.MIN_ZOOM, OutputPlan.MAX_ZOOM) ?: 1f,
            cropX = unit(cropX),
            cropY = unit(cropY),
        )
    }

    companion object {
        /** 25 for PAL editors, 24 for film, 60 for a smooth result from a fast interval. */
        val FPS_CHOICES = listOf(24, 25, 30, 60)
    }
}

/** The Home screen's way into Studio, shown under a time-lapse that just ended with frames. */
object StudioShortcut {
    /**
     * Only a time-lapse: a periodic tap drives some other app and leaves no frames Studio could use.
     * Only once stopped: during a run the frames are still being written.
     */
    fun offer(stopped: Boolean, shotsTaken: Int, timelapse: Boolean): Boolean = stopped && timelapse && shotsTaken > 0
}
