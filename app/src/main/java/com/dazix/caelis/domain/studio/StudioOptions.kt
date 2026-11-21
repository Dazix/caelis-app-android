package com.dazix.caelis.domain.studio

/** What the user chose for Studio's outputs; one set for every session, kept between visits. */
data class StudioOptions(
    val fps: Int = 30,
    val resolution: Resolution = Resolution.FHD,
    val aspect: Aspect = Aspect.ORIGINAL,
    val deflicker: Boolean = true,
    val joinWithoutAudio: Boolean = false,
) {
    /** A stored value outside the choices (an older build, a damaged file) falls back to the default. */
    fun sanitized(): StudioOptions = if (fps in FPS_CHOICES) this else copy(fps = StudioOptions().fps)

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
