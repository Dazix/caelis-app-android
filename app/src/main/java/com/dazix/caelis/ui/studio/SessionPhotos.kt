package com.dazix.caelis.ui.studio

import android.net.Uri
import java.text.DateFormat
import java.util.Date
import android.util.Size as AndroidSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dazix.caelis.R
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import com.dazix.caelis.domain.studio.PhotoFacts
import com.dazix.caelis.domain.studio.PhotoFactsFormat
import com.dazix.caelis.media.PhotoInfoReader
import com.dazix.caelis.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** How many photos the card shows before the "+N" tile that opens them all. */
private const val STRIP_COUNT = 4

/**
 * A session's photos on the card: the first few and a tile with the rest, which opens every photo in
 * a grid. A photo opens in the app's own viewer, not in another app: the user stays in Studio and can
 * swipe through the series.
 */
@Composable
internal fun SessionPhotos(uris: List<Uri>) {
    var gridOpen by rememberSaveable(uris) { mutableStateOf(false) }
    var viewing by rememberSaveable(uris) { mutableStateOf<Int?>(null) }

    val shown = if (uris.size <= STRIP_COUNT + 1) uris.size else STRIP_COUNT
    val rest = uris.size - shown
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until STRIP_COUNT + 1) {
            val cell = Modifier
                .weight(1f)
                .aspectRatio(1f)
            when {
                i < shown -> PhotoTile(uris[i], i, onOpen = { viewing = it }, modifier = cell)
                i == shown && rest > 0 -> MoreTile(rest = rest, total = uris.size, onClick = { gridOpen = true }, modifier = cell)
                else -> Box(cell)
            }
        }
    }

    if (gridOpen) PhotoGrid(uris = uris, onOpen = { viewing = it }, onDismiss = { gridOpen = false })
    viewing?.let { start -> PhotoViewer(uris = uris, start = start, onDismiss = { viewing = null }) }
}

@Composable
private fun PhotoTile(uri: Uri, index: Int, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.studio_session_preview, index + 1)
    Thumbnail(
        uri = uri,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Image) { onOpen(index) }
            .semantics { contentDescription = description },
    )
}

@Composable
private fun MoreTile(rest: Int, total: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.studio_photos_show_all, total, total)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = "+$rest",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Every photo of the session in shot order, in a full-screen grid. */
@Composable
private fun PhotoGrid(uris: List<Uri>, onOpen: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Back, contentDescription = stringResource(R.string.custom_mode_close))
                    }
                    Text(
                        text = pluralStringResource(R.plurals.studio_photos_grid_title, uris.size, uris.size),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(uris, key = { _, uri -> uri.toString() }) { i, uri ->
                        PhotoTile(uri, i, onOpen = onOpen, modifier = Modifier.aspectRatio(1f))
                    }
                }
            }
        }
    }
}

/**
 * One photo at a time, full screen on black, swiping through the series. The info button opens a
 * panel with the photo's place in the series and what its file says about it.
 */
@Composable
private fun PhotoViewer(uris: List<Uri>, start: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, uris.lastIndex)) { uris.size }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The info sits under the photo, which gives up the height it takes: the photo stays whole
        // and visible beside its facts instead of being covered by them.
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Fixed white on the black viewer, whatever the theme.
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Back, contentDescription = stringResource(R.string.custom_mode_close))
                    }
                    Text(
                        text = pluralStringResource(R.plurals.studio_photo_position, uris.size, pager.currentPage + 1, uris.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { infoOpen = !infoOpen }) {
                        Icon(AppIcons.Info, contentDescription = stringResource(R.string.studio_photo_info))
                    }
                }
            }
            // A swipe across the photo still pages, and moves the info to the next photo.
            HorizontalPager(
                state = pager,
                key = { uris[it].toString() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // A zoomed photo pans under one finger; the pager swipes again once it is back at 1x.
                userScrollEnabled = !zoomed,
            ) { page ->
                FullPhoto(uris[page], active = page == pager.currentPage, onZoomed = { zoomed = it })
            }
            AnimatedVisibility(
                visible = infoOpen,
                enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Top),
                exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                InfoSheet(
                    uri = uris[pager.currentPage],
                    position = pager.currentPage + 1,
                    total = uris.size,
                    onClose = { infoOpen = false },
                )
            }
        }
    }
}

/**
 * The photo decoded at screen size off the main thread; a spinner until then, nothing if it fails.
 * Pinch zooms (up to [MAX_PHOTO_ZOOM]), one finger pans a zoomed photo, a double tap toggles 2x.
 * Screen size, not the full 12 MP: the pager composes neighbours too, and three full-size
 * bitmaps are 150 MB; a deep zoom is therefore softer than the file.
 */
@Composable
private fun FullPhoto(uri: Uri, active: Boolean, onZoomed: (Boolean) -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val maxSide = with(LocalDensity.current) { max(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, maxSide) {
        value = withContext(Dispatchers.IO) { PhotoInfoReader.loadBitmap(context, uri, maxSide)?.asImageBitmap() }
    }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    // Swiped away: the next visit starts whole, and the pager is not left locked by a zoom off screen.
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(active, scale) { if (active) onZoomed(scale > 1f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(uri) {
                // Two fingers zoom, one finger pans a zoomed photo; one finger on a whole photo is
                // left alone so the pager can swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_PHOTO_ZOOM)
                            // Keep the photo on the screen: it may move only by what it overhangs.
                            val limitX = (newScale - 1f) * size.width / 2
                            val limitY = (newScale - 1f) * size.height / 2
                            val moved = offset + event.calculatePan()
                            offset = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(uri) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    },
                )
            },
    ) {
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

/** How far a photo can be pinched in the viewer. */
private const val MAX_PHOTO_ZOOM = 5f

/**
 * The photo's info as a bottom sheet under the photo (not over it: the photo moves up). It has no
 * scrim and a tap on the photo does nothing to it; it goes away with the info button or a swipe down,
 * on the handle or on the content once that is scrolled to its top.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun InfoSheet(uri: Uri, position: Int, total: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val facts by produceState<PhotoFacts?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { PhotoInfoReader.facts(context, uri) }
    }
    // At most half the screen, so the photo keeps the other half; longer info scrolls.
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    val closeAt = with(LocalDensity.current) { 72.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    fun settle() {
        scope.launch { if (offset.value > closeAt) onClose() else offset.animateTo(0f) }
    }
    // What the content's scroll leaves over (a pull down at its top) drags the sheet; a push up
    // while the sheet is pulled down brings the sheet back before the content scrolls.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || offset.value <= 0f) return Offset.Zero
                val used = maxOf(available.y, -offset.value)
                scope.launch { offset.snapTo(offset.value + used) }
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                scope.launch { offset.snapTo(offset.value + available.y) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value <= 0f) return Velocity.Zero
                settle()
                return available
            }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .graphicsLayer { translationY = offset.value }
            .nestedScroll(nested)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = { settle() },
            ),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
            InfoContent(facts = facts, position = position, total = total, locale = locale)
        }
    }
}

@Composable
private fun InfoContent(facts: PhotoFacts?, position: Int, total: Int, locale: Locale) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = pluralStringResource(R.plurals.studio_photo_position, total, position, total),
            style = MaterialTheme.typography.titleMedium,
        )
        val f = facts ?: return@Column
        f.name?.let { InfoRow(stringResource(R.string.studio_info_name), it) }
        f.takenMs?.let { ms ->
            // With seconds: shots of a series are often seconds apart, and minutes alone repeat.
            val taken = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM, locale).format(Date(ms))
            InfoRow(stringResource(R.string.studio_info_taken), taken)
        }
        PhotoFactsFormat.dimensions(f.width, f.height, f.sizeBytes)?.let {
            InfoRow(stringResource(R.string.studio_info_size), it)
        }
        PhotoFactsFormat.camera(f.make, f.model)?.let { InfoRow(stringResource(R.string.studio_info_camera), it) }
        PhotoFactsFormat.exposureLine(f)?.let { InfoRow(stringResource(R.string.studio_info_exposure), it) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A photo's system thumbnail, loaded off the main thread at [loadSize]. A photo that has no thumbnail
 * (deleted since, unreadable) stays an empty tile rather than failing the list.
 */
@Composable
internal fun Thumbnail(uri: Uri, modifier: Modifier = Modifier, loadSize: Dp = 128.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { loadSize.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, px) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, AndroidSize(px, px), null).asImageBitmap() }
                .getOrNull()
        }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
