package com.dazix.caelis.ui

import com.dazix.caelis.domain.cameramode.RecordedStep
import com.dazix.caelis.overlay.ModeRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomModeDraftTest {

    private val tap = RecordedStep.Tap(0.5f, 0.8f, null, 300L)
    private val recording = ModeRecording("com.example.cam", listOf(tap, tap), null, 1080, 2400, serial = 1)

    @Test
    fun `a draft needs a name, an app and steps, in that order`() {
        assertEquals(DraftProblem.NO_NAME, CustomModeDraft().problem)
        assertEquals(DraftProblem.NO_APP, CustomModeDraft(name = "Noc").problem)
        assertEquals(DraftProblem.NO_STEPS, CustomModeDraft(name = "Noc", packageName = "com.example.cam").problem)
        assertNull(CustomModeDraft(name = "Noc", packageName = "com.example.cam").withRecording(recording).problem)
    }

    @Test
    fun `steps recorded in one app are not saved under another`() {
        val draft = CustomModeDraft(name = "Noc", packageName = "com.example.cam").withRecording(recording)
            .copy(packageName = "com.other.cam")
        assertEquals(DraftProblem.STEPS_FROM_ANOTHER_APP, draft.problem)
        assertNull(draft.toMode({ "id" }, 1L))
    }

    @Test
    fun `a new mode gets an id and a creation time, an edited one keeps its own`() {
        val fresh = CustomModeDraft(name = " Noc ", packageName = "com.example.cam", appLabel = "Cam")
            .withRecording(recording)
            .toMode({ "new" }, 42L)!!
        assertEquals("new", fresh.id)
        assertEquals("Noc", fresh.name)
        assertEquals(42L, fresh.createdAtMs)
        assertEquals(1080, fresh.naturalWidthPx)

        assertEquals(false, fresh.intervalAfterCapture)

        val edited = CustomModeDraft.from(fresh).copy(name = "Night", intervalAfterCapture = true).toMode({ "other" }, 99L)!!
        assertEquals("new", edited.id)
        assertEquals(42L, edited.createdAtMs)
        assertEquals("Night", edited.name)
        assertEquals(true, edited.intervalAfterCapture)
        assertEquals(true, CustomModeDraft.from(edited).intervalAfterCapture)
    }

    @Test
    fun `a recording replaces the steps, removing one keeps the rest`() {
        val draft = CustomModeDraft(steps = listOf(tap)).withRecording(recording)
        assertEquals(2, draft.steps.size)
        assertEquals(1, draft.withoutStep(0).steps.size)
        assertEquals(draft, draft.withoutStep(5))
    }
}
