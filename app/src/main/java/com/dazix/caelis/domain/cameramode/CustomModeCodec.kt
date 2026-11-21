package com.dazix.caelis.domain.cameramode

/**
 * The recorded modes as one string for DataStore. Line based, tab separated, versioned:
 *
 * ```
 * v1
 * M  id  name  package  appLabel  naturalW  naturalH  createdAt  intervalAfterCapture (1/0)
 * P  viewId  label  class  l  t  r  b                  (optional, the proof)
 * T  delay  x  y  viewId  label  class  l  t  r  b     (a tap; the signature fields empty = none)
 * S  delay  duration  x,y;x,y;…                        (a swipe)
 * ```
 *
 * A step belongs to the `M` line above it. A damaged line drops the whole mode it belongs to rather
 * than the line alone: a mode missing one of its steps would replay into the wrong place, which is
 * worse than a mode that is gone. Nothing here throws.
 */
object CustomModeCodec {
    const val VERSION = "v1"

    fun encode(modes: List<CustomCameraMode>): String = buildString {
        append(VERSION)
        for (mode in modes) {
            append('\n')
            append(
                fields(
                    "M", mode.id, mode.name, mode.packageName, mode.appLabel,
                    mode.naturalWidthPx.toString(), mode.naturalHeightPx.toString(),
                    mode.createdAtMs.toString(), if (mode.intervalAfterCapture) "1" else "0",
                ),
            )
            mode.proof?.let { proof ->
                append('\n')
                append(fields("P", *signatureFields(proof)))
            }
            for (step in mode.steps) {
                append('\n')
                append(
                    when (step) {
                        is RecordedStep.Tap -> fields(
                            "T", step.delayBeforeMs.toString(), step.x.toString(), step.y.toString(),
                            *(step.signature?.let(::signatureFields) ?: EMPTY_SIGNATURE),
                        )
                        is RecordedStep.Swipe -> fields(
                            "S", step.delayBeforeMs.toString(), step.durationMs.toString(),
                            step.points.joinToString(";") { "${it.x},${it.y}" },
                        )
                    },
                )
            }
        }
    }

    fun decode(raw: String?): List<CustomCameraMode> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.first() != VERSION) return emptyList()

        val out = ArrayList<CustomCameraMode>()
        var current: CustomCameraMode? = null
        var damaged = false
        fun flush() {
            val mode = current
            if (mode != null && !damaged && mode.steps.isNotEmpty()) out += mode
            current = null
            damaged = false
        }

        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val tokens = line.split('\t').map(::unescape)
            when (tokens[0]) {
                "M" -> {
                    flush()
                    current = parseMode(tokens)
                    // A mode line that does not parse makes its steps orphans; skip them.
                    damaged = current == null
                    if (damaged) current = PLACEHOLDER
                }
                "P" -> {
                    val mode = current ?: continue
                    val proof = parseSignature(tokens, 1)
                    if (proof == null) damaged = true else current = mode.copy(proof = proof)
                }
                "T", "S" -> {
                    val mode = current ?: continue
                    val step = if (tokens[0] == "T") parseTap(tokens) else parseSwipe(tokens)
                    if (step == null) damaged = true else current = mode.copy(steps = mode.steps + step)
                }
                else -> if (current != null) damaged = true
            }
        }
        flush()
        // Ids are what launchCameraMode points at; a duplicate would make that pointer ambiguous.
        return out.distinctBy { it.id }
    }

    private fun parseMode(t: List<String>): CustomCameraMode? {
        // Eight fields: a mode stored before the interval switch existed, which reads as off.
        if (t.size != 8 && t.size != 9) return null
        val intervalAfterCapture = when (t.getOrNull(8)) {
            null, "0" -> false
            "1" -> true
            else -> return null
        }
        val id = t[1].takeIf { it.isNotBlank() } ?: return null
        val pkg = t[3].takeIf { it.isNotBlank() } ?: return null
        return CustomCameraMode(
            id = id,
            name = t[2],
            packageName = pkg,
            appLabel = t[4],
            steps = emptyList(),
            naturalWidthPx = t[5].toIntOrNull() ?: return null,
            naturalHeightPx = t[6].toIntOrNull() ?: return null,
            createdAtMs = t[7].toLongOrNull() ?: return null,
            intervalAfterCapture = intervalAfterCapture,
        )
    }

    private fun parseTap(t: List<String>): RecordedStep.Tap? {
        if (t.size != 11) return null
        val delay = t[1].toLongOrNull() ?: return null
        val x = t[2].toFraction() ?: return null
        val y = t[3].toFraction() ?: return null
        val noSignature = (4..10).all { t[it].isEmpty() }
        val signature = if (noSignature) null else parseSignature(t, 4) ?: return null
        return RecordedStep.Tap(x, y, signature, delay)
    }

    private fun parseSwipe(t: List<String>): RecordedStep.Swipe? {
        if (t.size != 4) return null
        val delay = t[1].toLongOrNull() ?: return null
        val duration = t[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val points = t[3].split(';').map { pair ->
            val xy = pair.split(',')
            if (xy.size != 2) return null
            Point(xy[0].toFraction() ?: return null, xy[1].toFraction() ?: return null)
        }
        if (points.size < 2) return null
        return RecordedStep.Swipe(points, duration, delay)
    }

    /** Seven fields from [from]: viewId, label, class, then the four bounds. */
    private fun parseSignature(t: List<String>, from: Int): NodeSignature? {
        if (t.size < from + 7) return null
        return NodeSignature(
            viewId = t[from].ifEmpty { null },
            label = t[from + 1].ifEmpty { null },
            className = t[from + 2].ifEmpty { null },
            left = t[from + 3].toFraction() ?: return null,
            top = t[from + 4].toFraction() ?: return null,
            right = t[from + 5].toFraction() ?: return null,
            bottom = t[from + 6].toFraction() ?: return null,
        )
    }

    private fun signatureFields(s: NodeSignature): Array<String> = arrayOf(
        s.viewId.orEmpty(), s.label.orEmpty(), s.className.orEmpty(),
        s.left.toString(), s.top.toString(), s.right.toString(), s.bottom.toString(),
    )

    /** A fraction of the screen; a little outside 0..1 is tolerated (bounds of a node half off screen). */
    private fun String.toFraction(): Float? = toFloatOrNull()?.takeIf { it.isFinite() && it in -1f..2f }

    private fun fields(vararg values: String): String = values.joinToString("\t", transform = ::escape)

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private val EMPTY_SIGNATURE = Array(7) { "" }

    private val PLACEHOLDER = CustomCameraMode("", "", "", "", emptyList())
}
