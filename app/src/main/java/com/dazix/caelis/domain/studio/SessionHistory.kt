package com.dazix.caelis.domain.studio

/**
 * A finished time-lapse, as Studio needs it: the wall-clock window its frames were written in.
 * The engine runs on `elapsedRealtime`; the files carry wall-clock times, hence the conversion.
 */
data class SessionRecord(
    val startWallMs: Long,
    val endWallMs: Long,
    val shots: Int,
    val targetPackage: String?,
) {
    companion object {
        /**
         * The record of a session ending now. The start is the elapsed origin moved onto the wall
         * clock through the current pair, which is valid because a run never survives a reboot
         * (`RunStateStore.liveRun` refuses one). Null when nothing was shot: there is nothing to build.
         */
        fun finished(
            originElapsedMs: Long,
            nowElapsedMs: Long,
            nowWallMs: Long,
            shots: Int,
            targetPackage: String?,
        ): SessionRecord? {
            if (shots <= 0 || originElapsedMs > nowElapsedMs) return null
            return SessionRecord(nowWallMs - (nowElapsedMs - originElapsedMs), nowWallMs, shots, targetPackage)
        }
    }
}

object SessionHistory {

    const val MAX = 20

    /** Newest first; a record with the same start replaces the older one (stop and service-lost can both report a run). */
    fun add(list: List<SessionRecord>, record: SessionRecord): List<SessionRecord> =
        (listOf(record) + list.filter { it.startWallMs != record.startWallMs })
            .sortedByDescending { it.startWallMs }
            .take(MAX)
}

/** One line per record, `|`-separated, with `\` escapes; a damaged line is dropped, never fatal. */
object SessionHistoryCodec {

    private const val VERSION = "1"

    fun encode(list: List<SessionRecord>): String = list.joinToString("\n") {
        listOf(VERSION, it.startWallMs, it.endWallMs, it.shots, it.targetPackage?.let { p -> "+" + escape(p) } ?: "")
            .joinToString("|")
    }

    fun decode(text: String?): List<SessionRecord> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            runCatching {
                val f = splitEscaped(line)
                if (f.size != 5 || f[0] != VERSION) return@runCatching null
                SessionRecord(
                    startWallMs = f[1].toLong(),
                    endWallMs = f[2].toLong(),
                    shots = f[3].toInt(),
                    targetPackage = if (f[4].startsWith("+")) unescape(f[4].substring(1)) else null,
                )
            }.getOrNull()
        }
    }

    private fun escape(s: String) = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'p' -> append('|')
                    'n' -> append('\n')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** Escapes never produce a raw `|`, so a plain split is exact. */
    private fun splitEscaped(line: String) = line.split('|')
}
