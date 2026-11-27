package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFrameWindowTest {

    private val start = 1_000_000L
    private val end = 1_600_000L

    private fun file(id: String, t: Long, mime: String = "image/jpeg", size: Long = 1000) =
        FrameFile(id = id, mime = mime, lastModifiedMs = t, sizeBytes = size)

    @Test
    fun keepsImagesInsideTheWindowSortedByTime() {
        val files = listOf(file("b", start + 2000), file("a", start + 1000), file("c", end - 1))
        val picked = SessionFrameWindow.select(files, start, end, MediaKind.IMAGES)
        assertEquals(listOf("a", "b", "c"), picked.map { it.id })
    }

    @Test
    fun aFrameWrittenAfterTheLastTapStillBelongsToTheSession() {
        // Night Sight finishes writing seconds after the tap; the last frame lands after `end`.
        val late = file("late", end + SessionFrameWindow.AFTER_MS - 1)
        val tooLate = file("next", end + SessionFrameWindow.AFTER_MS + 1)
        val picked = SessionFrameWindow.select(listOf(late, tooLate), start, end, MediaKind.IMAGES)
        assertEquals(listOf("late"), picked.map { it.id })
    }

    @Test
    fun aFrameFromBeforeTheStartIsNotPartOfIt() {
        val before = file("before", start - SessionFrameWindow.BEFORE_MS - 1)
        val edge = file("edge", start - SessionFrameWindow.BEFORE_MS)
        val picked = SessionFrameWindow.select(listOf(before, edge), start, end, MediaKind.IMAGES)
        assertEquals(listOf("edge"), picked.map { it.id })
    }

    @Test
    fun filtersByKindAndDropsEmptyFiles() {
        val files = listOf(
            file("img", start + 1),
            file("vid", start + 2, mime = "video/mp4"),
            file("empty", start + 3, size = 0),
            file("dng", start + 4, mime = "image/x-adobe-dng"),
        )
        assertEquals(listOf("img"), SessionFrameWindow.select(files, start, end, MediaKind.IMAGES).map { it.id })
        assertEquals(listOf("vid"), SessionFrameWindow.select(files, start, end, MediaKind.VIDEOS).map { it.id })
    }

    @Test
    fun equalTimesAreOrderedByIdSoTheOrderIsStable() {
        val files = listOf(file("2", start + 5), file("1", start + 5))
        assertEquals(listOf("1", "2"), SessionFrameWindow.select(files, start, end, MediaKind.IMAGES).map { it.id })
    }

    @Test
    fun anEmptyOrInvertedWindowSelectsNothing() {
        assertTrue(SessionFrameWindow.select(listOf(file("a", start)), end, start, MediaKind.IMAGES).isEmpty())
    }

    @Test
    fun mediaStoreIdsWithinOneSecondKeepTheShotOrder() {
        // DATE_ADDED has whole seconds; ids are numbered in insertion order, and as text "10" < "9".
        val second = start + 5_000
        val files = listOf(file("10", second), file("9", second), file("11", second))
        assertEquals(listOf("9", "10", "11"), SessionFrameWindow.select(files, start, end, MediaKind.IMAGES).map { it.id })
    }

    @Test
    fun aPhotoAnotherAppSavedDuringTheSessionIsLeftOut() {
        val camera = "com.google.android.GoogleCamera"
        val files = listOf(
            file("1", start + 1).copy(owner = camera),
            file("2", start + 2).copy(owner = "com.android.systemui"),
            file("3", start + 3).copy(owner = null),
        )
        assertEquals(
            listOf("1", "3"),
            SessionFrameWindow.select(files, start, end, MediaKind.IMAGES, cameraPackage = camera).map { it.id },
        )
        // Without a known camera app nothing is filtered by owner.
        assertEquals(listOf("1", "2", "3"), SessionFrameWindow.select(files, start, end, MediaKind.IMAGES).map { it.id })
    }

    @Test
    fun theSpanCoversEverySessionWithItsMargins() {
        val a = SessionRecord(start, end, 10, null)
        val b = SessionRecord(start + 10_000_000, start + 11_000_000, 5, null)
        val span = SessionFrameWindow.span(listOf(b, a))!!
        assertEquals(start - SessionFrameWindow.BEFORE_MS, span.first)
        assertEquals(start + 11_000_000 + SessionFrameWindow.AFTER_MS, span.last)
        assertEquals(null, SessionFrameWindow.span(emptyList()))
    }

    @Test
    fun photoAccessFindsPhotosAndTheFolderIsOnlyTheFallback() {
        assertEquals(FrameOrigin.MEDIA_STORE, FrameOrigin.of(MediaKind.IMAGES, photoAccess = true, folderGranted = true))
        assertEquals(FrameOrigin.MEDIA_STORE, FrameOrigin.of(MediaKind.IMAGES, photoAccess = true, folderGranted = false))
        assertEquals(FrameOrigin.FOLDER, FrameOrigin.of(MediaKind.IMAGES, photoAccess = false, folderGranted = true))
        assertEquals(FrameOrigin.NONE, FrameOrigin.of(MediaKind.IMAGES, photoAccess = false, folderGranted = false))
        // Videos need the folder: there is no video permission.
        assertEquals(FrameOrigin.FOLDER, FrameOrigin.of(MediaKind.VIDEOS, photoAccess = true, folderGranted = true))
        assertEquals(FrameOrigin.NONE, FrameOrigin.of(MediaKind.VIDEOS, photoAccess = true, folderGranted = false))
    }
}
