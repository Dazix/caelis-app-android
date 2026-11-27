package com.dazix.caelis.data.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.dazix.caelis.domain.studio.FrameFile

/**
 * The fallback for Studio without photo access: one folder, granted by the user through the
 * system's folder picker. It sees only that folder, carries no owner (a screenshot saved there
 * counts), and is the only source of a session's videos, as READ_MEDIA_VIDEO is not declared. The
 * photo picker is no substitute: it stops at about a hundred items, a time-lapse is several hundred
 * frames. The grant is persisted, and it is read access only.
 */
class FrameSource(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("studio", Context.MODE_PRIVATE)

    /** The granted tree, or null when none was chosen or the grant has since been revoked. */
    fun treeUri(): Uri? {
        val stored = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
        val held = app.contentResolver.persistedUriPermissions.any { it.uri == stored && it.isReadPermission }
        return stored.takeIf { held }
    }

    /** Called with the picker's answer: keeps the grant across restarts and drops the previous one. */
    fun grant(tree: Uri) {
        val resolver = app.contentResolver
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.getString(KEY_TREE, null)?.let(Uri::parse)?.takeIf { it != tree }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        // Synchronous: grant() runs on IO and the load right after it reads the folder back.
        prefs.edit(commit = true) { putString(KEY_TREE, tree.toString()) }
    }

    /** The folder's name as the provider shows it ("Camera"), for the screen. */
    fun folderName(tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * The files directly in the folder. Not recursive: the camera apps this drives write into one
     * flat folder, and walking subfolders of a wrongly chosen DCIM would be slow for nothing.
     */
    fun list(tree: Uri): List<FrameFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<FrameFile>()
        app.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                out += FrameFile(
                    id = c.getString(0),
                    mime = mime,
                    lastModifiedMs = if (c.isNull(2)) 0L else c.getLong(2),
                    sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                )
            }
        }
        return out
    }

    fun documentUri(tree: Uri, file: FrameFile): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.id)

    companion object {
        private const val KEY_TREE = "frame_tree"

        /**
         * Where the folder picker opens: the primary storage's DCIM/Camera, where Google Camera and
         * most others write. Only a hint; the user may pick any folder.
         */
        val CAMERA_FOLDER_HINT: Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:DCIM/Camera",
        )
    }
}
