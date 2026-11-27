package com.dazix.caelis.data.studio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.dazix.caelis.domain.studio.FrameFile

/**
 * A session's photos, found through MediaStore with photo access. This is how Studio finds frames:
 * every folder and every storage volume (an SD card included), with the app that saved each photo,
 * so a session's frames are found wherever its camera app put them and a screenshot taken meanwhile
 * is not one of them. The folder ([FrameSource]) is only the fallback without photo access.
 *
 * Pending rows (a photo the camera is still writing) are not returned, which is MediaStore's default.
 */
class MediaStoreFrames(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Photos added within [span] (wall-clock milliseconds). [FrameFile.lastModifiedMs] is the row's
     * DATE_ADDED: the moment the camera app inserted it, which a file copied or edited later does not
     * move, unlike the file's own modification time.
     */
    fun list(span: LongRange): List<FrameFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        // DATE_ADDED is in seconds; a second either side, the exact window is SessionFrameWindow's.
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.DATE_ADDED} <= ?"
        val args = arrayOf((span.first / 1000 - 1).toString(), (span.last / 1000 + 1).toString())
        val out = ArrayList<FrameFile>()
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                out += FrameFile(
                    id = c.getLong(0).toString(),
                    mime = mime,
                    lastModifiedMs = c.getLong(2) * 1000,
                    sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                    owner = c.getString(4),
                )
            }
        }
        return out
    }

    fun uri(file: FrameFile): Uri = ContentUris.withAppendedId(collection, file.id.toLong())
}
