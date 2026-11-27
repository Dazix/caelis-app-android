package com.dazix.caelis.domain.studio

/**
 * One photo or video Studio may build from: a MediaStore row (with photo access) or a file of the
 * folder the user granted.
 *
 * [id] is the MediaStore `_ID` or the folder's document id. [owner] is the app that saved it, as
 * MediaStore records it; null when unknown, which is always the case for a folder's file.
 */
data class FrameFile(
    val id: String,
    val mime: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val owner: String? = null,
)

enum class MediaKind { IMAGES, VIDEOS }

/**
 * Which files belong to a session: those written between the session's wall-clock start and end,
 * and, when both sides know it, saved by the camera app the session drove.
 */
object SessionFrameWindow {

    /** A clock skew of a second or two between the camera app's write and our own start stamp. */
    const val BEFORE_MS = 2_000L

    /**
     * The last frame is written after the last tap: Night Sight and long exposures finish seconds
     * later, and the camera app may still be saving when the session is stopped.
     */
    const val AFTER_MS = 30_000L

    /** JPEG and HEIC only for images: DNG is out of scope and would be decoded as a tiny preview. */
    private val IMAGE_MIMES = setOf("image/jpeg", "image/heic", "image/heif")

    /**
     * Ordered by time, then by MediaStore id: rows are numbered in insertion order, so frames saved
     * within the same second keep the order they were shot in. Ids compare as numbers ("10" after
     * "9"); a folder's document ids are not numbers and compare as text.
     */
    private val ORDER = compareBy<FrameFile> { it.lastModifiedMs }
        .thenBy(nullsLast()) { it.id.toLongOrNull() }
        .thenBy { it.id }

    /**
     * [cameraPackage]: the app the session tapped. A file another app saved in the same minutes (a
     * screenshot, a messenger's download) is left out; a file whose owner is unknown is kept, because
     * a folder carries no owner and an owner may be missing for files copied in by hand.
     */
    fun select(
        files: List<FrameFile>,
        startMs: Long,
        endMs: Long,
        kind: MediaKind,
        cameraPackage: String? = null,
    ): List<FrameFile> {
        if (endMs < startMs) return emptyList()
        val from = startMs - BEFORE_MS
        val to = endMs + AFTER_MS
        return files
            .filter { it.sizeBytes > 0 && it.lastModifiedMs in from..to && matches(it.mime, kind) }
            .filter { cameraPackage == null || it.owner == null || it.owner == cameraPackage }
            .sortedWith(ORDER)
    }

    /** The span to ask MediaStore for, so one query covers every session in [records]. */
    fun span(records: List<SessionRecord>): LongRange? {
        val valid = records.filter { it.endWallMs >= it.startWallMs }
        if (valid.isEmpty()) return null
        return (valid.minOf { it.startWallMs } - BEFORE_MS)..(valid.maxOf { it.endWallMs } + AFTER_MS)
    }

    fun matches(mime: String, kind: MediaKind): Boolean = when (kind) {
        MediaKind.IMAGES -> mime.lowercase() in IMAGE_MIMES
        MediaKind.VIDEOS -> mime.lowercase().startsWith("video/")
    }
}

/** Where Studio looks for a session's photos. */
enum class FrameOrigin {
    /** MediaStore, with full photo access: every folder and storage volume, owner known. */
    MEDIA_STORE,

    /** Only the folder the user granted, without photo access. */
    FOLDER,

    /** Neither: Studio asks for photo access. */
    NONE,
    ;

    companion object {
        /**
         * Photo access wins: it finds the frames wherever the camera app saved them. Selected-photos
         * access does not count, it shows none of the photos a session takes after the choice. Videos
         * are only ever in the folder: READ_MEDIA_VIDEO is not declared.
         */
        fun of(kind: MediaKind, photoAccess: Boolean, folderGranted: Boolean): FrameOrigin = when {
            kind == MediaKind.IMAGES && photoAccess -> MEDIA_STORE
            folderGranted -> FOLDER
            else -> NONE
        }
    }
}
