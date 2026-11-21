package com.dazix.caelis.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.dazix.caelis.domain.studio.BandPlan
import com.dazix.caelis.domain.studio.FrameShape
import com.dazix.caelis.domain.studio.FrameSizes
import com.dazix.caelis.domain.studio.LightenStack
import com.dazix.caelis.domain.studio.MeanStack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
        val shapes = frames.map { shapeOf(it) }
        val readable = frames.indices.filter { shapes[it] != null }
        val check = FrameSizes.check(readable.map { shapes[it]!! })
        val shape = check.size ?: return StackResult.NoFrames
        val rejectedSet = check.rejected.map { readable[it] }.toSet()
        val used = readable.filter { it !in rejectedSet }.map { frames[it] }

        val bytesPerPixel = if (kind == StackKind.LIGHTEN) 4 else 12
        val rows = BandPlan.bandRows(shape.width, shape.height, bytesPerPixel, Runtime.getRuntime().maxMemory() / 4)
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
}
