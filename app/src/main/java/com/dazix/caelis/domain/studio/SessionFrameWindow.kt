package com.dazix.caelis.domain.studio

/** One file found in the folder the user granted, as the document provider describes it. */
data class FrameFile(
    val id: String,
    val mime: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
)

enum class MediaKind { IMAGES, VIDEOS }

/**
 * Which files of the granted folder belong to a session. The app cannot read MediaStore rows (no
 * READ_MEDIA_IMAGES), so the only link between a session and its frames is time: the file's last
 * modification against the session's wall-clock start and end.
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

    fun select(files: List<FrameFile>, startMs: Long, endMs: Long, kind: MediaKind): List<FrameFile> {
        if (endMs < startMs) return emptyList()
        val from = startMs - BEFORE_MS
        val to = endMs + AFTER_MS
        return files
            .filter { it.sizeBytes > 0 && it.lastModifiedMs in from..to && matches(it.mime, kind) }
            .sortedWith(compareBy<FrameFile> { it.lastModifiedMs }.thenBy { it.id })
    }

    fun matches(mime: String, kind: MediaKind): Boolean = when (kind) {
        MediaKind.IMAGES -> mime.lowercase() in IMAGE_MIMES
        MediaKind.VIDEOS -> mime.lowercase().startsWith("video/")
    }
}
