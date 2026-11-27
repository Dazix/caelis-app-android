package com.dazix.caelis.domain.cameramode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepMatchPolicyTest {

    private val chipId = "com.google.android.GoogleCamera:id/mode_chip_text"
    private val minibarId = "com.google.android.GoogleCamera:id/minibar_item_ext2"

    private fun node(
        index: Int,
        viewId: String?,
        label: String?,
        left: Float,
        top: Float = 0.8f,
        width: Float = 0.15f,
        height: Float = 0.04f,
        visible: Boolean = true,
        selected: Boolean = false,
        clickable: Boolean = false,
    ) = NodeInfo(index, viewId, label, "android.widget.TextView", left, top, left + width, top + height, clickable, visible, selected)

    // Pixel Camera, Czech: the carousel chips share one id; the minibar icon has the chip's label.
    private val screen = listOf(
        node(0, null, null, 0f, top = 0f, width = 1f, height = 1f),
        node(1, chipId, "Fotka", 0.4f),
        node(2, chipId, "Noční vidění", 0.6f),
        node(3, chipId, "Portrét", 0.2f),
        node(4, minibarId, "Noční vidění", 0.8f, top = 0.05f),
    )

    private fun tapOn(signature: NodeSignature?) = RecordedStep.Tap(0.5f, 0.5f, signature, 300L)

    @Test
    fun `the chip is found by id and label, not the minibar icon with the same label`() {
        val target = StepMatchPolicy.locate(tapOn(screen[2].toSignature()), screen, sameScreen = true)
        assertEquals(StepTarget.Element(screen[2]), target)
    }

    @Test
    fun `a unique view id still finds the element after its label changed`() {
        val recorded = screen[4].toSignature().copy(label = "Night Sight")
        assertEquals(StepTarget.Element(screen[4]), StepMatchPolicy.locate(tapOn(recorded), screen, sameScreen = true))
    }

    @Test
    fun `a shared view id with an unknown label is not guessed`() {
        // Three chips share the id; none says "Night". Picking one would switch into some mode.
        val recorded = screen[2].toSignature().copy(label = "Night")
        assertEquals(StepTarget.NotFound, StepMatchPolicy.locate(tapOn(recorded), screen, sameScreen = true))
    }

    @Test
    fun `a label alone finds an element without an id`() {
        val noId = listOf(node(0, null, "More", 0.9f, top = 0.05f))
        val recorded = noId[0].toSignature()
        assertEquals(StepTarget.Element(noId[0]), StepMatchPolicy.locate(tapOn(recorded), noId, sameScreen = false))
    }

    @Test
    fun `two equal elements are told apart by the recorded position or refused`() {
        val twins = listOf(node(0, "x:id/b", "Go", 0.1f), node(1, "x:id/b", "Go", 0.6f))
        assertEquals(
            StepTarget.Element(twins[1]),
            StepMatchPolicy.locate(tapOn(twins[1].toSignature()), twins, sameScreen = true),
        )
        val between = twins[0].toSignature().copy(left = 0.4f, right = 0.5f)
        assertEquals(StepTarget.Ambiguous, StepMatchPolicy.locate(tapOn(between), twins, sameScreen = true))
    }

    @Test
    fun `an element not drawn yet is not found, never replaced by its position`() {
        val hidden = screen.map { if (it.index == 2) it.copy(visible = false) else it }
        assertEquals(StepTarget.NotFound, StepMatchPolicy.locate(tapOn(screen[2].toSignature()), hidden, sameScreen = true))
    }

    @Test
    fun `a tap without an element replays its position on the same screen only`() {
        assertEquals(StepTarget.Position, StepMatchPolicy.locate(tapOn(null), screen, sameScreen = true))
        assertEquals(StepTarget.ScreenMismatch, StepMatchPolicy.locate(tapOn(null), screen, sameScreen = false))
        assertTrue(StepMatchPolicy.canSwipe(sameScreen = true))
        assertFalse(StepMatchPolicy.canSwipe(sameScreen = false))
    }

    @Test
    fun `the same screen is the same natural size, whatever the rotation`() {
        val mode = CustomCameraMode("a", "n", "p", "l", emptyList(), naturalWidthPx = 1440, naturalHeightPx = 3120)
        assertTrue(StepMatchPolicy.sameScreen(mode, 1440, 3120))
        assertFalse(StepMatchPolicy.sameScreen(mode, 1080, 2400))
    }

    @Test
    fun `the finger is on the innermost identifiable element`() {
        // Chip text sits inside the full-screen layout; only the chip says which mode it is.
        assertEquals(screen[2], NodeAtPoint.pick(screen, 0.65f, 0.82f))
        assertNull(NodeAtPoint.pick(screen, 0.05f, 0.5f))
    }

    @Test
    fun `the proof is the one element that became selected`() {
        val before = screen.map { if (it.index == 1) it.copy(selected = true) else it }
        val after = screen.map { if (it.index == 2) it.copy(selected = true) else it }
        assertEquals(screen[2].toSignature(), ModeProofPolicy.proofFrom(before, after))
    }

    @Test
    fun `no change or two changes give no proof`() {
        val selected = screen.map { if (it.index == 2) it.copy(selected = true) else it }
        assertNull(ModeProofPolicy.proofFrom(selected, selected))
        val two = screen.map { if (it.index == 2 || it.index == 4) it.copy(selected = true) else it }
        assertNull(ModeProofPolicy.proofFrom(screen, two))
    }

    @Test
    fun `the mode is on only while its proof is selected`() {
        val proof = screen[2].toSignature()
        val on = screen.map { if (it.index == 2) it.copy(selected = true) else it }
        val minibarSelected = screen.map { if (it.index == 4) it.copy(selected = true) else it }
        assertTrue(ModeProofPolicy.isOn(proof, on))
        assertFalse(ModeProofPolicy.isOn(proof, screen))
        assertFalse(ModeProofPolicy.isOn(proof, minibarSelected))
        assertFalse(ModeProofPolicy.isOn(null, on))
    }
}
