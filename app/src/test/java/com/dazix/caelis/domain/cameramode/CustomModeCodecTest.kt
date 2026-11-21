package com.dazix.caelis.domain.cameramode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomModeCodecTest {

    private val chip = NodeSignature(
        viewId = "com.google.android.GoogleCamera:id/mode_chip_text",
        label = "Noční vidění",
        className = "android.widget.TextView",
        left = 0.1f, top = 0.8f, right = 0.3f, bottom = 0.85f,
    )

    private val night = CustomCameraMode(
        id = "a1",
        name = "Noc",
        packageName = "com.google.android.GoogleCamera",
        appLabel = "Fotoaparát",
        steps = listOf(
            RecordedStep.Swipe(listOf(Point(0.8f, 0.82f), Point(0.5f, 0.82f), Point(0.2f, 0.83f)), 240L, 900L),
            RecordedStep.Tap(0.2f, 0.82f, chip, 600L),
            RecordedStep.Tap(0.5f, 0.1f, null, 300L),
        ),
        proof = chip,
        naturalWidthPx = 1440,
        naturalHeightPx = 3120,
        createdAtMs = 1_763_000_000_000L,
    )

    @Test
    fun `a mode survives the round trip`() {
        assertEquals(listOf(night), CustomModeCodec.decode(CustomModeCodec.encode(listOf(night))))
    }

    @Test
    fun `the interval switch survives the round trip, and a mode stored before it reads as off`() {
        val long = night.copy(intervalAfterCapture = true)
        assertEquals(listOf(long), CustomModeCodec.decode(CustomModeCodec.encode(listOf(long))))

        val old = CustomModeCodec.encode(listOf(night)).replace(Regex("(M\t[^\n]*)\t0\n"), "$1\n")
        assertEquals(8, old.lines().first { it.startsWith("M\t") }.split('\t').size)
        assertEquals(listOf(night), CustomModeCodec.decode(old))

        val garbled = CustomModeCodec.encode(listOf(long)).replace(Regex("(M\t[^\n]*)\t1\n"), "$1\tyes\n")
        assertTrue(CustomModeCodec.decode(garbled).isEmpty())
    }

    @Test
    fun `several modes keep their order`() {
        val astro = night.copy(id = "b2", name = "Astro", proof = null)
        assertEquals(listOf(night, astro), CustomModeCodec.decode(CustomModeCodec.encode(listOf(night, astro))))
    }

    @Test
    fun `names and labels with separators and backslashes round trip`() {
        val odd = night.copy(
            name = "Tab\there\nnew line \\ and \\t literal",
            appLabel = "A\\B",
            steps = listOf(RecordedStep.Tap(0.5f, 0.5f, chip.copy(label = "x\ty\\n"), 300L)),
        )
        assertEquals(listOf(odd), CustomModeCodec.decode(CustomModeCodec.encode(listOf(odd))))
    }

    @Test
    fun `empty, blank, foreign and unversioned records read as no modes`() {
        assertEquals(emptyList<CustomCameraMode>(), CustomModeCodec.decode(null))
        assertEquals(emptyList<CustomCameraMode>(), CustomModeCodec.decode(""))
        assertEquals(emptyList<CustomCameraMode>(), CustomModeCodec.decode("v9\nM\ta"))
        assertEquals(emptyList<CustomCameraMode>(), CustomModeCodec.decode(CustomModeCodec.encode(listOf(night)).removePrefix("v1\n")))
    }

    @Test
    fun `a damaged step drops its whole mode and only that one`() {
        // A mode missing one step would replay into the wrong place; better gone than half there.
        val astro = night.copy(id = "b2", name = "Astro")
        val lines = CustomModeCodec.encode(listOf(night, astro)).split('\n').toMutableList()
        val firstTap = lines.indexOfFirst { it.startsWith("T\t") }
        lines[firstTap] = "T\tnot-a-number\t0.5\t0.5\t\t\t\t\t\t\t"
        assertEquals(listOf(astro), CustomModeCodec.decode(lines.joinToString("\n")))
    }

    @Test
    fun `a damaged mode line drops its steps instead of giving them to the mode before`() {
        val astro = night.copy(id = "b2", name = "Astro")
        val lines = CustomModeCodec.encode(listOf(night, astro)).split('\n').toMutableList()
        val secondMode = lines.indexOfLast { it.startsWith("M\t") }
        lines[secondMode] = "M\tb2\tAstro"
        val decoded = CustomModeCodec.decode(lines.joinToString("\n"))
        assertEquals(listOf(night), decoded)
        assertEquals(night.steps.size, decoded.single().steps.size)
    }

    @Test
    fun `a swipe needs two points and a positive duration`() {
        val base = CustomModeCodec.encode(listOf(night.copy(steps = listOf(night.steps.first()))))
        val onePoint = base.replace(Regex("S\t900\t240\t.*"), "S\t900\t240\t0.5,0.5")
        val noTime = base.replace("S\t900\t240\t", "S\t900\t0\t")
        assertTrue(CustomModeCodec.decode(onePoint).isEmpty())
        assertTrue(CustomModeCodec.decode(noTime).isEmpty())
    }

    @Test
    fun `a mode without steps is not kept`() {
        assertTrue(CustomModeCodec.decode(CustomModeCodec.encode(listOf(night.copy(steps = emptyList())))).isEmpty())
    }

    @Test
    fun `a duplicate id keeps the first mode`() {
        val twin = night.copy(name = "Twin")
        assertEquals(listOf(night), CustomModeCodec.decode(CustomModeCodec.encode(listOf(night, twin))))
    }

    @Test
    fun `the key points at the id and nothing else parses as one`() {
        assertEquals("custom:a1", night.key)
        assertEquals("a1", CustomModeKey.idOf("custom:a1"))
        assertNull(CustomModeKey.idOf("night"))
        assertNull(CustomModeKey.idOf("custom:"))
        assertNull(CustomModeKey.idOf(null))
    }
}
