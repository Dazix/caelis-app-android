package com.dazix.caelis.ui.studio

import android.net.Uri
import android.util.Size as AndroidSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.remember
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Slider
import com.dazix.caelis.domain.studio.Aspect
import com.dazix.caelis.domain.studio.Framing
import com.dazix.caelis.domain.studio.OutputPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The preview's longest side: a 9:16 frame at full width would take the whole screen. */
private val PREVIEW_MAX_HEIGHT = 280.dp

/** One press of − or +: 1.25× per press, from 0.25× (whole picture inside black) to 4×. */
private const val ZOOM_STEP = 1.25f

/** The crop's zoom and place, as stored in StudioOptions. */
internal data class CropView(val zoom: Float, val x: Float, val y: Float)

/**
 * The output frame as it will look, drawn from one of the session's photos. With [Framing.CROP]
 * the photo fills the frame: a drag moves it (along whichever axes it overhangs), a pinch or the
 * − / + buttons zoom it. With [Framing.FIT] the whole photo sits in the frame with black bars.
 * Without a photo to show (none found yet) the frame is drawn empty.
 */
@Composable
internal fun FramingPreview(
    uris: List<Uri>,
    aspect: Aspect,
    framing: Framing,
    view: CropView,
    enabled: Boolean,
    onChange: (CropView) -> Unit,
) {
    val context = LocalContext.current
    // Which photo of the session the preview shows: the middle one first, the slider picks another.
    var index by rememberSaveable(uris) { mutableIntStateOf(uris.size / 2) }
    val uri = uris.getOrNull(index.coerceIn(0, (uris.size - 1).coerceAtLeast(0)))
    // The last photo stays on screen while the next one loads, so sliding does not flash black.
    val photo by produceState<ImageBitmap?>(initialValue = null, uri) {
        uri?.let {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(it, AndroidSize(1280, 1280), null).asImageBitmap() }
                    .getOrNull()
            }
        }?.let { value = it }
    }
    val crop = framing == Framing.CROP || aspect == Aspect.ORIGINAL
    val hint = stringResource(if (crop) R.string.studio_framing_drag else R.string.studio_framing_bars)
    val current by rememberUpdatedState(view)
    val bitmap = photo
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // ORIGINAL takes the photo's own shape; 4:3 until the photo is there.
            val ratio = if (aspect == Aspect.ORIGINAL) {
                bitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
            } else {
                aspect.w.toFloat() / aspect.h
            }
            val width = minOf(maxWidth, PREVIEW_MAX_HEIGHT * ratio)
            val height = width / ratio
            Box(
                modifier = Modifier
                    .size(width, height)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black)
                    .pointerInput(enabled, crop, bitmap) {
                        if (!enabled || !crop || bitmap == null) return@pointerInput
                        // Every move in the preview is the preview's, from the first pixel: with a
                        // touch slop the page's scroll took the first finger before the second landed,
                        // and a pinch scrolled the page instead of zooming.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoomChange != 1f || pan != Offset.Zero) {
                                    onChange(moved(current, bitmap, IntSize(size.width, size.height), pan.x, pan.y, zoomChange))
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .semantics { contentDescription = hint },
            ) {
                Canvas(modifier = Modifier.size(width, height)) {
                    val b = bitmap ?: return@Canvas
                    val frame = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    val (dst, off) = if (crop) placed(b, frame, view) else fitted(b, frame)
                    drawImage(b, dstOffset = off, dstSize = dst)
                }
            }
        }
        // Under the preview it describes.
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
        if (crop) {
            val zoomOut = stringResource(R.string.studio_zoom_out)
            val zoomIn = stringResource(R.string.studio_zoom_in)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(
                    onClick = { onChange(view.copy(zoom = (view.zoom / ZOOM_STEP).coerceAtLeast(OutputPlan.MIN_ZOOM))) },
                    enabled = enabled && view.zoom > OutputPlan.MIN_ZOOM,
                    modifier = Modifier.semantics { contentDescription = zoomOut },
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    text = String.format(Locale.ROOT, "%.1f×", view.zoom),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 56.dp),
                )
                FilledTonalIconButton(
                    onClick = { onChange(view.copy(zoom = (view.zoom * ZOOM_STEP).coerceAtMost(OutputPlan.MAX_ZOOM))) },
                    enabled = enabled && view.zoom < OutputPlan.MAX_ZOOM,
                    modifier = Modifier.semantics { contentDescription = zoomIn },
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }
        if (uris.size > 1) {
            // Pages the preview through the series, to check the crop on a photo where the subject moved.
            // Label and slider as one block, without the column's gap between them.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = pluralStringResource(R.plurals.studio_photo_position, uris.size, index + 1, uris.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                val sliderSource = remember { MutableInteractionSource() }
                Slider(
                    value = index.toFloat(),
                    onValueChange = { index = it.roundToInt().coerceIn(0, uris.lastIndex) },
                    valueRange = 0f..uris.lastIndex.toFloat(),
                    interactionSource = sliderSource,
                    // A shorter handle than the default 44 dp: it sat tall against a one-line label.
                    thumb = { SliderDefaults.Thumb(interactionSource = sliderSource, thumbSize = DpSize(4.dp, 32.dp)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Air before the next option, which read as part of the slider.
                        .padding(bottom = 12.dp),
                )
            }
        }
    }
}

/** The photo scaled to cover the frame times the zoom, and its offset for the place (x, y in 0..1). */
private fun placed(b: ImageBitmap, frame: IntSize, view: CropView): Pair<IntSize, IntOffset> {
    val s = maxOf(frame.width.toFloat() / b.width, frame.height.toFloat() / b.height) * view.zoom
    val w = (b.width * s).roundToInt()
    val h = (b.height * s).roundToInt()
    // Overhang negative when zoomed out: the photo is smaller than the frame and the place moves it inside.
    val x = -((w - frame.width) * view.x).roundToInt()
    val y = -((h - frame.height) * view.y).roundToInt()
    return IntSize(w, h) to IntOffset(x, y)
}

private fun fitted(b: ImageBitmap, frame: IntSize): Pair<IntSize, IntOffset> {
    val s = minOf(frame.width.toFloat() / b.width, frame.height.toFloat() / b.height)
    val w = (b.width * s).roundToInt()
    val h = (b.height * s).roundToInt()
    return IntSize(w, h) to IntOffset((frame.width - w) / 2, (frame.height - h) / 2)
}

/**
 * A drag of ([dx], [dy]) pixels and a pinch of [zoomChange] applied to [view]. Dragging the photo
 * right shows more of its left side, so the place moves against the finger, by the share of the
 * overhang the finger covered.
 */
private fun moved(view: CropView, b: ImageBitmap, frame: IntSize, dx: Float, dy: Float, zoomChange: Float): CropView {
    val zoom = (view.zoom * zoomChange).coerceIn(OutputPlan.MIN_ZOOM, OutputPlan.MAX_ZOOM)
    val s = maxOf(frame.width.toFloat() / b.width, frame.height.toFloat() / b.height) * zoom
    val overX = b.width * s - frame.width
    val overY = b.height * s - frame.height
    val x = if (abs(overX) > 1f) (view.x - dx / overX).coerceIn(0f, 1f) else view.x
    val y = if (abs(overY) > 1f) (view.y - dy / overY).coerceIn(0f, 1f) else view.y
    return CropView(zoom, x, y)
}
