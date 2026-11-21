package com.dazix.caelis.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Saves a Studio result into the shared gallery. An app may insert its own media without any media
 * permission (scoped storage); the row stays IS_PENDING until the file is complete, so a gallery never
 * shows half a picture, and a failed or cancelled write removes the row again.
 */
object OutputWriter {

    const val PICTURES_DIR = "Pictures/Caelis"

    /** JPEG at 95: the result of a few hundred frames should not lose what the stack gained. */
    private const val JPEG_QUALITY = 95

    fun saveJpeg(context: Context, bitmap: Bitmap, name: String, orientation: Int): Uri {
        // Written to a private file first: EXIF is set on a file path, then copied in one stream.
        val temp = File.createTempFile("studio", ".jpg", context.cacheDir)
        try {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                ExifInterface(temp.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
            }
            return insert(context, temp, "$name.jpg", "image/jpeg", PICTURES_DIR, images = true)
        } finally {
            temp.delete()
        }
    }

    internal fun insert(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
        relativePath: String,
        images: Boolean,
    ): Uri {
        val resolver = context.contentResolver
        val collection = if (images) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused the insert")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}
