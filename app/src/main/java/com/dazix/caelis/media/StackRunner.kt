package com.dazix.caelis.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import com.dazix.caelis.domain.studio.BandPlan
import com.dazix.caelis.domain.studio.FrameShape
import com.dazix.caelis.domain.studio.FrameSizes
import com.dazix.caelis.domain.studio.LightenStack
import com.dazix.caelis.domain.studio.MeanStack
import com.dazix.caelis.domain.studio.OutputPlan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

enum class StackKind { LIGHTEN, MEAN }

sealed interface StackResult {
    /** [rejected]: frames left out because their size or orientation differed from the rest. */
    data class Done(val uri: Uri, val used: Int, val rejected: Int) : StackResult
    data object NoFrames : StackResult
}

/**
 * Stacks a session's frames into one picture: star trails ([StackKind.LIGHTEN]) or an average
 * ([StackKind.MEAN]). The arithmetic is `domain/studio`; this file decodes and writes.
 *
 * Frames are stacked as stored, without turning them upright: every frame of the stack has the same
 * EXIF orientation (others are rejected), and the result carries that orientation too. Rotating
 * 12 MP of pixels per frame would cost time and memory for nothing.
 *
 * The output takes the colour space of the first frame: a Pixel writes Display P3, and putting P3
 * values into an sRGB bitmap would wash the picture out.
 */
class StackRunner(private val context: Context) {

    private val resolver get() = context.contentResolver

    suspend fun run(
        frames: List<Uri>,
        kind: StackKind,
        name: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): StackResult {
        val (shape, used) = usable(frames) ?: return StackResult.NoFrames

        // The band's own copy of the frame (4 B/px) plus the stack's accumulator (4 or 12 B/px).
        val bytesPerPixel = if (kind == StackKind.LIGHTEN) 8 else 16
        val rows = BandPlan.bandRows(shape.width, shape.height, bytesPerPixel, heapBudget())
        val bands = (shape.height + rows - 1) / rows
        val total = bands * used.size

        var reuse: Bitmap? = null
        var output: Bitmap? = null
        try {
            var done = 0
            for (band in 0 until bands) {
                val top = band * rows
                val height = minOf(rows, shape.height - top)
                val pixels = IntArray(shape.width * height)
                val lighten = if (kind == StackKind.LIGHTEN) LightenStack(pixels.size) else null
                val mean = if (kind == StackKind.MEAN) MeanStack(pixels.size) else null
                for (uri in used) {
                    currentCoroutineContext().ensureActive()
                    val frame = decode(uri, reuse) ?: continue
                    reuse = frame
                    if (frame.width != shape.width || frame.height != shape.height) continue
                    if (output == null) {
                        output = Bitmap.createBitmap(
                            shape.width, shape.height, Bitmap.Config.ARGB_8888, false, frame.colorSpace!!,
                        )
                    }
                    frame.getPixels(pixels, 0, shape.width, 0, top, shape.width, height)
                    lighten?.add(pixels)
                    mean?.add(pixels)
                    onProgress(++done, total)
                }
                val result = lighten?.result() ?: mean?.takeIf { it.count > 0 }?.result() ?: continue
                output?.setPixels(result, 0, shape.width, 0, top, shape.width, height)
            }
            val picture = output ?: return StackResult.NoFrames
            currentCoroutineContext().ensureActive()
            val uri = OutputWriter.saveJpeg(context, picture, name, shape.orientation)
            return StackResult.Done(uri, used.size, frames.size - used.size)
        } finally {
            reuse?.recycle()
            output?.recycle()
        }
    }

    /**
     * The star trail as it grows: the lighten stack after every [every]-th frame (and the last),
     * turned upright and scaled into [boxLong] x [boxShort], as JPEGs in [dir] for the video encoder.
     * Needs the whole frame in one band, because a snapshot is the whole picture at that moment;
     * null when the heap cannot hold it.
     */
    suspend fun growingSnapshots(
        frames: List<Uri>,
        every: Int,
        boxLong: Int,
        boxShort: Int,
        dir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<File>? {
        val (shape, used) = usable(frames) ?: return emptyList()
        if (BandPlan.bandRows(shape.width, shape.height, 8, heapBudget()) < shape.height) return null
        val snapshotAt = OutputPlan.growingTrailSnapshots(used.size, every).toSet()
        val pixels = IntArray(shape.width * shape.height)
        val stack = LightenStack(pixels.size)
        val out = ArrayList<File>()
        var reuse: Bitmap? = null
        var canvas: Bitmap? = null
        try {
            used.forEachIndexed { i, uri ->
                currentCoroutineContext().ensureActive()
                val frame = decode(uri, reuse)
                if (frame != null) {
                    reuse = frame
                    if (frame.width == shape.width && frame.height == shape.height) {
                        frame.getPixels(pixels, 0, shape.width, 0, 0, shape.width, shape.height)
                        stack.add(pixels)
                        if (canvas == null) {
                            canvas = Bitmap.createBitmap(
                                shape.width, shape.height, Bitmap.Config.ARGB_8888, false, frame.colorSpace!!,
                            )
                        }
                    }
                }
                val full = canvas
                if (i in snapshotAt && full != null) {
                    full.setPixels(stack.result(), 0, shape.width, 0, 0, shape.width, shape.height)
                    out += writeSnapshot(full, shape.orientation, boxLong, boxShort, File(dir, "snap_%05d.jpg".format(out.size)))
                }
                onProgress(i + 1, used.size)
            }
            return out
        } finally {
            reuse?.recycle()
            canvas?.recycle()
        }
    }

    private fun writeSnapshot(full: Bitmap, orientation: Int, boxLong: Int, boxShort: Int, file: File): File {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val turned = degrees % 180f != 0f
        val w = if (turned) full.height else full.width
        val h = if (turned) full.width else full.height
        val boxW = if (w >= h) boxLong else boxShort
        val boxH = if (w >= h) boxShort else boxLong
        val scale = minOf(1f, boxW.toFloat() / w, boxH.toFloat() / h)
        val matrix = Matrix().apply {
            postRotate(degrees)
            postScale(scale, scale)
        }
        val small = Bitmap.createBitmap(full, 0, 0, full.width, full.height, matrix, true)
        try {
            file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_QUALITY, it) }
        } finally {
            if (small !== full) small.recycle()
        }
        return file
    }

    /** The frames that share the most common shape, with that shape; null when none could be read. */
    private fun usable(frames: List<Uri>): Pair<FrameShape, List<Uri>>? {
        val shapes = frames.map { shapeOf(it) }
        val readable = frames.indices.filter { shapes[it] != null }
        val check = FrameSizes.check(readable.map { shapes[it]!! })
        val shape = check.size ?: return null
        val rejectedSet = check.rejected.map { readable[it] }.toSet()
        return shape to readable.filter { it !in rejectedSet }.map { frames[it] }
    }

    /**
     * What a stack may take from the Java heap: most of what is free now. A fixed share of the
     * maximum ignored what the screen already holds; the decoded frames are native and not counted.
     */
    private fun heapBudget(): Long {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return (free * 6 / 10).coerceAtLeast(MIN_BUDGET_BYTES)
    }

    private fun shapeOf(uri: Uri): FrameShape? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        FrameShape(bounds.outWidth, bounds.outHeight, orientation)
    }.getOrNull()

    /**
     * Decodes into [reuse] when it fits, so a 300-frame stack does not allocate 300 bitmaps of 48 MB;
     * a frame the decoder refuses to put there (HEIC on some versions) is decoded fresh.
     */
    private fun decode(uri: Uri, reuse: Bitmap?): Bitmap? {
        fun attempt(into: Bitmap?) = runCatching {
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inBitmap = into
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
        return attempt(reuse) ?: run {
            reuse?.recycle()
            attempt(null)
        }
    }

    private companion object {
        const val SNAPSHOT_QUALITY = 92
        /** Below this a band would be a handful of rows and every frame decoded hundreds of times. */
        const val MIN_BUDGET_BYTES = 32L * 1024 * 1024
    }
}
