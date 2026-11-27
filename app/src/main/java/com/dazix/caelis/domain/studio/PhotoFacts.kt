package com.dazix.caelis.domain.studio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the photo viewer's info panel says about one photo, read from MediaStore and the file's EXIF.
 * Every field is optional: camera apps write different tags, and a missing one is left out, never
 * shown as zero.
 */
data class PhotoFacts(
    val name: String? = null,
    val takenMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val make: String? = null,
    val model: String? = null,
    val exposureS: Double? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalMm: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** The panel's values as text; units and number formats are not translated, labels are. */
object PhotoFactsFormat {

    /** "1/250 s" below a second (the way a camera shows it), "2.5 s" or "30 s" from one second up. */
    fun exposure(seconds: Double): String? {
        if (seconds <= 0.0 || seconds.isNaN()) return null
        if (seconds >= 1.0) {
            val whole = seconds.roundToInt().toDouble()
            return if (abs(seconds - whole) < 0.05) "${whole.toInt()} s" else String.format(Locale.ROOT, "%.1f s", seconds)
        }
        return "1/${(1.0 / seconds).roundToInt()} s"
    }

    /**
     * EXIF "2026:11:27 15:38:37" (+ optional offset "+01:00") as epoch ms; without an offset the time
     * is the camera's local time, read in [zone]. Null for a blank or malformed value ("0000:00:00").
     */
    fun exifTime(value: String?, offset: String?, zone: java.time.ZoneId): Long? = runCatching {
        val local = java.time.LocalDateTime.parse(
            value!!.trim(),
            java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"),
        )
        val at = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { java.time.ZoneOffset.of(it) }
            ?: zone.rules.getOffset(local)
        local.toInstant(at).toEpochMilli()
    }.getOrNull()

    fun aperture(f: Double): String? = if (f > 0.0) String.format(Locale.ROOT, "f/%.1f", f) else null

    fun focal(mm: Double): String? = when {
        mm <= 0.0 -> null
        abs(mm - mm.roundToInt()) < 0.05 -> "${mm.roundToInt()} mm"
        else -> String.format(Locale.ROOT, "%.1f mm", mm)
    }

    /** "1/250 s · f/1.8 · ISO 100 · 6.8 mm", or null when the file carries none of them. */
    fun exposureLine(facts: PhotoFacts): String? = listOfNotNull(
        facts.exposureS?.let(::exposure),
        facts.fNumber?.let(::aperture),
        facts.iso?.takeIf { it > 0 }?.let { "ISO $it" },
        facts.focalMm?.let(::focal),
    ).joinToString(" · ").ifEmpty { null }

    /**
     * Maker and model, without the maker twice: phones often write "Google" / "Pixel 7 Pro" but also
     * "samsung" / "samsung SM-S911B".
     */
    fun camera(make: String?, model: String?): String? {
        val m = make?.trim().orEmpty()
        val d = model?.trim().orEmpty()
        return when {
            m.isEmpty() && d.isEmpty() -> null
            m.isEmpty() -> d
            d.isEmpty() -> m
            d.startsWith(m, ignoreCase = true) -> d
            else -> "$m $d"
        }
    }

    /**
     * Five decimals (about a metre), or null for no position: Android hands an app without the media
     * location permission the coordinates as 0, 0, which is a real place in the Gulf of Guinea.
     */
    fun coordinates(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null || lat.isNaN() || lon.isNaN()) return null
        if (lat == 0.0 && lon == 0.0) return null
        if (abs(lat) > 90.0 || abs(lon) > 180.0) return null
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)
    }

    /** "4080 × 3072 · 3.2 MB"; sizes in decimal megabytes, as galleries show them. */
    fun dimensions(width: Int?, height: Int?, sizeBytes: Long?): String? = listOfNotNull(
        if (width != null && height != null && width > 0 && height > 0) "$width × $height" else null,
        sizeBytes?.takeIf { it > 0 }?.let { bytes ->
            if (bytes >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
            else "${(bytes / 1000.0).roundToInt()} kB"
        },
    ).joinToString(" · ").ifEmpty { null }
}
