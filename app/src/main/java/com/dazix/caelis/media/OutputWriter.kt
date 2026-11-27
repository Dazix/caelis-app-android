package com.dazix.caelis.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
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
    const val MOVIES_DIR = "Movies/Caelis"

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

    /** The name the gallery shows, or null when the row is gone (deleted in the gallery meanwhile). */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * Renames an output this app saved (its own row: no permission needed) and returns the name
     * MediaStore actually kept, which may differ when the name was taken; null when it refused.
     */
    fun rename(context: Context, uri: Uri, displayName: String): String? = runCatching {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }
        if (context.contentResolver.update(uri, values, null, null) == 1) displayName(context, uri) else null
    }.getOrNull()

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
