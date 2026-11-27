package com.dazix.caelis.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.dazix.caelis.domain.studio.Aspect
import com.dazix.caelis.domain.studio.DeflickerCurve
import com.dazix.caelis.domain.studio.OutputPlan
import com.dazix.caelis.domain.studio.Resolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TimelapseOptions(
    val fps: Int = 30,
    val resolution: Resolution = Resolution.FHD,
    val aspect: Aspect = Aspect.ORIGINAL,
    val deflicker: Boolean = true,
)

/**
 * Turns pictures into a time-lapse and joins clips, through Media3 Transformer. Everything here is
 * offline: the inputs are content and file URIs, read through Transformer's default data source,
 * and the app pulls in no network module of Media3.
 *
 * Transformer must be built and driven on a thread with a Looper; the main thread is used, the
 * encoding itself runs on Transformer's own threads.
 */
@OptIn(UnstableApi::class)
class VideoEncoder(private val context: Context) {

    /**
     * One frame per picture. A picture is shown for 1/fps (rounded down to whole milliseconds, so
     * it always yields exactly one frame at that rate), scaled into the plan's box with a centred
     * crop to the aspect, and with deflicker its own brightness gain.
     */
    suspend fun timelapse(
        pictures: List<Uri>,
        options: TimelapseOptions,
        name: String,
        onProgress: (percent: Int) -> Unit,
    ): Uri {
        require(pictures.isNotEmpty())
        val upright = withContext(Dispatchers.IO) { uprightSize(pictures.first()) }
        val plan = OutputPlan.frame(upright.first, upright.second, options.aspect, options.resolution)
        val gains = if (options.deflicker && pictures.size > 2) {
            withContext(Dispatchers.IO) {
                DeflickerCurve.gains(DoubleArray(pictures.size) { i ->
                    currentCoroutineContext().ensureActive()
                    meanLuma(pictures[i]) ?: 0.0
                })
            }
        } else {
            null
        }
        val presentation = Presentation.createForWidthAndHeight(
            plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        )
        val durationMs = (1000L / options.fps).coerceAtLeast(1L)
        val items = pictures.mapIndexed { i, uri ->
            val effects = buildList {
                gains?.get(i)?.takeIf { it != 1.0 }?.let { g ->
                    val f = g.toFloat()
                    add(RgbAdjustment.Builder().setRedScale(f).setGreenScale(f).setBlueScale(f).build())
                }
                add(presentation)
            }
            EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setImageDurationMs(durationMs).build())
                .setFrameRate(options.fps)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
        return export(composition, "$name.mp4", onProgress)
    }

    /** Clips one after another, in the order given, without their sound when [removeAudio]. */
    suspend fun join(
        clips: List<Uri>,
        removeAudio: Boolean,
        name: String,
        onProgress: (percent: Int) -> Unit,
    ): Uri {
        require(clips.isNotEmpty())
        val items = clips.map { uri ->
            EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setRemoveAudio(removeAudio)
                .build()
        }
        val sequence = EditedMediaItemSequence.Builder(items)
            // A clip without a sound track between clips with one would stop the export.
            .experimentalSetForceAudioTrack(!removeAudio)
            .build()
        return export(Composition.Builder(sequence).build(), "$name.mp4", onProgress)
    }

    private suspend fun export(composition: Composition, displayName: String, onProgress: (Int) -> Unit): Uri {
        val out = File.createTempFile("studio", ".mp4", context.cacheDir)
        try {
            withContext(Dispatchers.Main) {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .build()
                val poll = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress)
                        }
                        delay(PROGRESS_POLL_MS)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        cont.invokeOnCancellation { transformer.cancel() }
                        transformer.start(composition, out.absolutePath)
                    }
                } finally {
                    poll.cancel()
                }
            }
            currentCoroutineContext().ensureActive()
            return withContext(Dispatchers.IO) {
                OutputWriter.insert(context, out, displayName, MimeTypes.VIDEO_MP4, OutputWriter.MOVIES_DIR, images = false)
            }
        } finally {
            out.delete()
        }
    }

    /** The picture's size as shown, after its EXIF rotation. */
    private fun uprightSize(uri: Uri): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val rotated = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees % 180 != 0 } ?: false
        return if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    /**
     * Mean Rec. 709 luma of a heavily downsampled copy (1/16 per side): flicker is a whole-frame
     * change, and a full decode per frame would double the time of the whole export.
     */
    private fun meanLuma(uri: Uri): Double? = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = LUMA_SAMPLE }
        val small = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            var sum = 0.0
            for (p in pixels) {
                sum += 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
            }
            sum / pixels.size
        } finally {
            small.recycle()
        }
    }.getOrNull()

    private companion object {
        const val PROGRESS_POLL_MS = 250L
        const val LUMA_SAMPLE = 16
    }
}
