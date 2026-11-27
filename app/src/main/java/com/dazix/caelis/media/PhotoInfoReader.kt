package com.dazix.caelis.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.dazix.caelis.domain.studio.PhotoFacts
import com.dazix.caelis.domain.studio.PhotoFactsFormat
import java.time.ZoneId
import kotlin.math.max

/**
 * Reads one session photo for the in-app viewer: the picture at screen size, and the facts its
 * MediaStore row and EXIF carry. Nothing here is written or sent anywhere.
 *
 * GPS: without ACCESS_MEDIA_LOCATION (not declared) Android redacts the EXIF position of another
 * app's photo to 0, 0, so the position is shown only for a file that carries a real one.
 */
object PhotoInfoReader {

    /** Decoded no larger than [maxSide] on its long side; EXIF rotation is applied by the decoder. */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val scale = maxSide.toDouble() / long
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            // Software: a hardware bitmap cannot be read back, and the viewer may be asked for pixels later.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    fun facts(context: Context, uri: Uri): PhotoFacts {
        val row = runCatching { row(context, uri) }.getOrNull() ?: PhotoFacts()
        val exif = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull() ?: return row
        val latLong = exif.latLong
        return row.copy(
            width = row.width ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 },
            height = row.height ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 },
            takenMs = row.takenMs ?: PhotoFactsFormat.exifTime(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                ZoneId.systemDefault(),
            ),
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            exposureS = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 },
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
            latitude = latLong?.getOrNull(0),
            longitude = latLong?.getOrNull(1),
        )
    }

    private fun row(context: Context, uri: Uri): PhotoFacts? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun long(i: Int) = if (c.isNull(i)) null else c.getLong(i)
            fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i).takeIf { it > 0 }
            PhotoFacts(
                name = c.getString(0),
                takenMs = long(1)?.takeIf { it > 0 },
                width = int(2),
                height = int(3),
                sizeBytes = long(4)?.takeIf { it > 0 },
            )
        }
    }
}
