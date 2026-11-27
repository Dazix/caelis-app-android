package com.dazix.caelis.domain.studio

/**
 * A Studio output's file name: what the user typed when renaming it, made safe for a file name, with
 * the extension the file already has. The extension is never the user's to change: the gallery reads
 * the type from it, and "trails.mp4" for a JPEG would not open.
 */
object OutputName {

    /** Long enough for any sensible title, well under the 255 bytes a file name may take in UTF-8. */
    const val MAX_LENGTH = 80

    private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /** The name without its extension, as the rename field starts. */
    fun base(displayName: String): String = displayName.substringBeforeLast('.', displayName)

    /**
     * [typed] cleaned and given [current]'s extension, or null when nothing usable is left (empty, only
     * dots or spaces). A trailing extension the user typed themselves is not doubled.
     */
    fun renamed(current: String, typed: String): String? {
        val ext = current.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != current }
        var base = typed.replace(FORBIDDEN, "_").trim()
        if (ext != null && base.endsWith(".$ext", ignoreCase = true)) base = base.dropLast(ext.length + 1)
        // A leading dot hides the file on Linux; trailing dots and spaces are dropped by some file systems.
        base = base.trim().trimStart('.').trimEnd('.', ' ').take(MAX_LENGTH).trimEnd()
        if (base.isEmpty()) return null
        return if (ext != null) "$base.$ext" else base
    }
}
