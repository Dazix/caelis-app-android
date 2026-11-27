package com.dazix.caelis.domain.studio

import kotlin.math.roundToInt

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

/**
 * How a source of another aspect fills the output: [CROP] places a window of the output's aspect
 * on it (zoom and place, the user's), [FIT] keeps the whole picture centred with black bars.
 */
enum class Framing { CROP, FIT }

/**
 * [crop] is the window of the source the frame shows, in source pixels. It may reach past the
 * source (zoomed out, or FIT): what it covers outside the picture is black in the video.
 */
data class FramePlan(val crop: Crop, val width: Int, val height: Int)

/** A window in normalised device coordinates of the source (-1..1 is the picture, y up). */
data class NdcCrop(val left: Float, val right: Float, val bottom: Float, val top: Float)

/** Maps source NDC to output NDC: out = in * scale + translate, per axis. */
data class NdcTransform(val scaleX: Float, val scaleY: Float, val translateX: Float, val translateY: Float)

object OutputPlan {

    fun durationMs(frames: Int, fps: Int): Long {
        require(fps > 0)
        return if (frames <= 0) 0L else frames * 1000L / fps
    }

    /** The furthest the crop zooms in; past it a 12 MP photo is upscaled beyond what holds up. */
    const val MAX_ZOOM = 4f

    /**
     * The furthest it zooms out: enough to see a 4:3 photo whole in a 9:16 frame (0.42) and then
     * some, with black around it.
     */
    const val MIN_ZOOM = 0.25f

    /**
     * The window of the source the output shows, and the output size: the window's unzoomed shape
     * scaled down (never up) into the resolution's box. With [Framing.CROP] the largest window of
     * the aspect that fits the source is made [zoom] times smaller (zoom above 1 cuts in, below 1
     * reaches past the picture and adds black) and placed by [x] and [y]: 0 = flush with the
     * left / top edge, 0.5 = centred, 1 = right / bottom edge, whether the window is smaller or
     * larger than the picture on that axis. With [Framing.FIT] the window is the smallest of the
     * aspect around the whole picture, centred. The output size never follows the zoom, so zooming
     * in enlarges rather than shrinks the video. Every output dimension is even: hardware H.264/HEVC
     * encoders refuse odd sizes.
     */
    fun frame(
        sourceWidth: Int,
        sourceHeight: Int,
        aspect: Aspect,
        resolution: Resolution,
        framing: Framing = Framing.CROP,
        zoom: Float = 1f,
        x: Float = 0.5f,
        y: Float = 0.5f,
    ): FramePlan {
        require(sourceWidth > 0 && sourceHeight > 0)
        val fit = framing == Framing.FIT && aspect != Aspect.ORIGINAL
        val wider = aspect != Aspect.ORIGINAL && sourceWidth.toLong() * aspect.h >= sourceHeight.toLong() * aspect.w
        // The largest cut of the aspect (the whole source for ORIGINAL or FIT).
        val (fullW, fullH) = when {
            aspect == Aspect.ORIGINAL || fit -> even(sourceWidth) to even(sourceHeight)
            wider -> even((sourceHeight.toLong() * aspect.w / aspect.h).toInt()) to even(sourceHeight)
            else -> even(sourceWidth) to even((sourceWidth.toLong() * aspect.h / aspect.w).toInt())
        }
        // The frame's shape: the unzoomed cut's, or with FIT the aspect's around the whole source.
        val (frameW, frameH) = when {
            !fit -> fullW.toDouble() to fullH.toDouble()
            wider -> fullW.toDouble() to fullW.toDouble() * aspect.h / aspect.w
            else -> fullH.toDouble() * aspect.w / aspect.h to fullH.toDouble()
        }
        val z = if (fit) 1.0 else zoom.takeIf { !it.isNaN() }?.coerceIn(MIN_ZOOM, MAX_ZOOM)?.toDouble() ?: 1.0
        val w = (frameW / z).roundToInt().coerceAtLeast(2)
        val h = (frameH / z).roundToInt().coerceAtLeast(2)
        val px = if (fit) 0.5 else x.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)?.toDouble() ?: 0.5
        val py = if (fit) 0.5 else y.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)?.toDouble() ?: 0.5
        val crop = Crop(((sourceWidth - w) * px).roundToInt(), ((sourceHeight - h) * py).roundToInt(), w, h)
        val landscape = frameW >= frameH
        val boxW = if (landscape) resolution.longEdge else resolution.shortEdge
        val boxH = if (landscape) resolution.shortEdge else resolution.longEdge
        val scale = minOf(1.0, boxW / frameW, boxH / frameH)
        return FramePlan(crop, even((frameW * scale + 1e-6).toInt()), even((frameH * scale + 1e-6).toInt()))
    }

    /** [crop] of a [sourceWidth] x [sourceHeight] picture in Media3's coordinates (y up), unclamped. */
    fun ndc(crop: Crop, sourceWidth: Int, sourceHeight: Int): NdcCrop = NdcCrop(
        left = (2.0 * crop.x / sourceWidth - 1).toFloat(),
        right = (2.0 * (crop.x + crop.width) / sourceWidth - 1).toFloat(),
        bottom = (1 - 2.0 * (crop.y + crop.height) / sourceHeight).toFloat(),
        top = (1 - 2.0 * crop.y / sourceHeight).toFloat(),
    )

    /** The affine map that stretches [window] onto the whole output (-1..1 on both axes). */
    fun windowTransform(window: NdcCrop): NdcTransform {
        val w = window.right - window.left
        val h = window.top - window.bottom
        return NdcTransform(
            scaleX = 2f / w,
            scaleY = 2f / h,
            translateX = -(window.right + window.left) / w,
            translateY = -(window.top + window.bottom) / h,
        )
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

/**
 * The frames of one output must share a shape; a session where the mode changed mid-way has several.
 * Generic so a caller can fold the EXIF orientation into the shape: same pixel size, other rotation
 * is a different layout of the pixels and cannot be stacked with the rest.
 */
object FrameSizes {

    data class Check<T>(val size: T?, val rejected: List<Int>)

    /** The most common shape (the first one on a tie) and the indices of every other frame. */
    fun <T> check(shapes: List<T>): Check<T> {
        if (shapes.isEmpty()) return Check(null, emptyList())
        val counts = LinkedHashMap<T, Int>()
        shapes.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val dominant = counts.maxByOrNull { it.value }!!.key
        return Check(dominant, shapes.indices.filter { shapes[it] != dominant })
    }
}

/** A frame as stacking sees it: pixel size as stored, plus the EXIF orientation that turns it upright. */
data class FrameShape(val width: Int, val height: Int, val orientation: Int)

/**
 * How many rows a stacking pass takes at once. The accumulator lives on the Java heap (256 MB on a
 * Pixel 7 Pro), the decoded frames do not; a band is as tall as the [budgetBytes] allow, and every
 * extra band costs one more decode of every frame, so the fewest bands that fit win.
 */
object BandPlan {

    fun bandRows(width: Int, height: Int, accumulatorBytesPerPixel: Int, budgetBytes: Long): Int {
        require(width > 0 && height > 0 && accumulatorBytesPerPixel > 0)
        val rowBytes = width.toLong() * accumulatorBytesPerPixel
        val fit = (budgetBytes / rowBytes).coerceAtLeast(1L)
        if (fit >= height) return height
        val bands = ((height + fit - 1) / fit).toInt()
        // Even split: three bands of 1000 rows decode the same as 1400 + 1400 + 200, and use less memory.
        return (height + bands - 1) / bands
    }
}
