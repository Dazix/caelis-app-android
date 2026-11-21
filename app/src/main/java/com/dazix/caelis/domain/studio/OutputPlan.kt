package com.dazix.caelis.domain.studio

data class Size(val width: Int, val height: Int)

/** A rectangle in source pixels: left, top, width, height. */
data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

/** The output's aspect, as width : height; [ORIGINAL] keeps the source's. */
enum class Aspect(val w: Int, val h: Int) {
    ORIGINAL(0, 0),
    R16_9(16, 9),
    R9_16(9, 16),
    R1_1(1, 1),
}

/** The box the video must fit in, for a landscape frame; a portrait frame gets it turned. */
enum class Resolution(val longEdge: Int, val shortEdge: Int) {
    FHD(1920, 1080),
    UHD(3840, 2160),
}

data class FramePlan(val crop: Crop, val width: Int, val height: Int)

object OutputPlan {

    fun durationMs(frames: Int, fps: Int): Long {
        require(fps > 0)
        return if (frames <= 0) 0L else frames * 1000L / fps
    }

    /**
     * The centred crop of the source with the wanted aspect, scaled down (never up) into the
     * resolution's box. Every dimension is even: hardware H.264/HEVC encoders refuse odd sizes.
     */
    fun frame(sourceWidth: Int, sourceHeight: Int, aspect: Aspect, resolution: Resolution): FramePlan {
        require(sourceWidth > 0 && sourceHeight > 0)
        val crop = if (aspect == Aspect.ORIGINAL) {
            Crop(0, 0, even(sourceWidth), even(sourceHeight))
        } else if (sourceWidth.toLong() * aspect.h >= sourceHeight.toLong() * aspect.w) {
            // Source wider than wanted: keep the full height, cut the sides.
            val w = even((sourceHeight.toLong() * aspect.w / aspect.h).toInt())
            Crop((sourceWidth - w) / 2, 0, w, even(sourceHeight))
        } else {
            val h = even((sourceWidth.toLong() * aspect.h / aspect.w).toInt())
            Crop(0, (sourceHeight - h) / 2, even(sourceWidth), h)
        }
        val landscape = crop.width >= crop.height
        val boxW = if (landscape) resolution.longEdge else resolution.shortEdge
        val boxH = if (landscape) resolution.shortEdge else resolution.longEdge
        val scale = minOf(1.0, boxW.toDouble() / crop.width, boxH.toDouble() / crop.height)
        return FramePlan(crop, even((crop.width * scale + 1e-6).toInt()), even((crop.height * scale + 1e-6).toInt()))
    }

    /**
     * Which frames of a star-trail stack become a frame of the "growing" video: every [every]-th,
     * and always the last, so the video ends on the finished picture.
     */
    fun growingTrailSnapshots(frames: Int, every: Int): List<Int> {
        require(every >= 1)
        if (frames <= 0) return emptyList()
        val out = (every - 1 until frames step every).toMutableList()
        if (out.lastOrNull() != frames - 1) out += frames - 1
        return out
    }

    private fun even(v: Int) = maxOf(2, v and 1.inv())
}

/** The frames of one output must share a size; a session where the mode changed mid-way has several. */
object FrameSizes {

    data class Check(val size: Size?, val rejected: List<Int>)

    fun check(sizes: List<Size>): Check {
        if (sizes.isEmpty()) return Check(null, emptyList())
        val counts = LinkedHashMap<Size, Int>()
        sizes.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val dominant = counts.maxByOrNull { it.value }!!.key
        return Check(dominant, sizes.indices.filter { sizes[it] != dominant })
    }
}
