package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {

    private fun record(start: Long, shots: Int = 10, pkg: String? = "com.google.android.GoogleCamera") =
        SessionRecord(startWallMs = start, endWallMs = start + 60_000, shots = shots, targetPackage = pkg)

    @Test
    fun theStartIsReconstructedFromTheElapsedOriginOfTheSameBoot() {
        // Session started 10 min ago on the elapsed clock; the wall clock now reads 1_700_000_600_000.
        val r = SessionRecord.finished(
            originElapsedMs = 5_000_000,
            nowElapsedMs = 5_600_000,
            nowWallMs = 1_700_000_600_000,
            shots = 42,
            targetPackage = "pkg",
        )!!
        assertEquals(1_700_000_000_000, r.startWallMs)
        assertEquals(1_700_000_600_000, r.endWallMs)
        assertEquals(42, r.shots)
    }

    @Test
    fun aSessionWithoutAShotLeavesNoRecord() {
        assertNull(SessionRecord.finished(0, 1000, 1_700_000_000_000, shots = 0, targetPackage = "pkg"))
    }

    @Test
    fun anOriginInTheFutureIsNotARecord() {
        assertNull(SessionRecord.finished(2000, 1000, 1_700_000_000_000, shots = 3, targetPackage = "pkg"))
    }

    @Test
    fun newestFirstAndCapped() {
        var list = emptyList<SessionRecord>()
        for (i in 1..(SessionHistory.MAX + 5)) list = SessionHistory.add(list, record(i * 1000L))
        assertEquals(SessionHistory.MAX, list.size)
        assertEquals((SessionHistory.MAX + 5) * 1000L, list.first().startWallMs)
        assertEquals(6_000L, list.last().startWallMs)
    }

    @Test
    fun theSameSessionRecordedTwiceIsKeptOnce() {
        // A stop and a service-lost report can both see the same run end.
        val r = record(5000)
        assertEquals(1, SessionHistory.add(SessionHistory.add(emptyList(), r), r.copy(shots = 11)).size)
        assertEquals(11, SessionHistory.add(listOf(r), r.copy(shots = 11)).single().shots)
    }

    @Test
    fun theCodecRoundTripsIncludingAMissingPackageAndOddCharacters() {
        val list = listOf(record(1000, pkg = null), record(2000, pkg = "a|b\nc\\d"))
        assertEquals(list, SessionHistoryCodec.decode(SessionHistoryCodec.encode(list)))
    }

    @Test
    fun aDamagedLineIsSkippedNotFatal() {
        val good = record(1000)
        val text = SessionHistoryCodec.encode(listOf(good)) + "\ngarbage|x\n\n"
        assertEquals(listOf(good), SessionHistoryCodec.decode(text))
        assertTrue(SessionHistoryCodec.decode(null).isEmpty())
    }
}
