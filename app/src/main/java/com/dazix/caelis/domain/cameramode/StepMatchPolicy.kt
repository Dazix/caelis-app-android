package com.dazix.caelis.domain.cameramode

/** Where a recorded tap lands on the live screen. */
sealed interface StepTarget {
    /** The recorded element, found again. */
    data class Element(val node: NodeInfo) : StepTarget

    /** No element was recorded for this tap; replay the stored point (same screen only). */
    data object Position : StepTarget

    /** The element is not on screen (yet). Never a reason to tap somewhere else. */
    data object NotFound : StepTarget

    /** More than one element fits equally well. Tapping either would be a guess. */
    data object Ambiguous : StepTarget

    /** The step needs its stored position, and this screen is not the one it was recorded on. */
    data object ScreenMismatch : StepTarget
}

/**
 * Finds a recorded step on the live screen. "Unknown means do not tap" (AGENTS.md) applies to every
 * answer that is not [StepTarget.Element] or [StepTarget.Position]: the replay stops there.
 *
 * Order, strongest first, visible elements only:
 *  1. view id and label both equal;
 *  2. view id alone, when exactly one element has it (a label can change with the language);
 *  3. label alone, when exactly one element has it and no view id that differs from the recorded one.
 *
 * When everything recorded matches several elements, only the one containing the recorded centre is
 * taken; none or several is [StepTarget.Ambiguous]. A partial match must be unique. Pixel Camera's
 * mode chips share one view id and differ in label,
 * and its minibar Night Sight icon has the chip's label under another id, so tier 1 is what tells
 * them apart.
 *
 * A recorded element that is not found is [StepTarget.NotFound], never a fall back to its position: an
 * element that is not drawn yet sits under something else, and the caller waits for it instead.
 */
object StepMatchPolicy {

    fun locate(
        step: RecordedStep.Tap,
        nodes: List<NodeInfo>,
        sameScreen: Boolean,
    ): StepTarget {
        val signature = step.signature?.takeIf { it.isIdentifiable }
            ?: return if (sameScreen) StepTarget.Position else StepTarget.ScreenMismatch
        val visible = nodes.filter { it.visible }

        val viewId = signature.viewId?.takeIf { it.isNotBlank() }
        val label = signature.label?.takeIf { it.isNotBlank() }
        val both = viewId != null && label != null
        // (candidates, whether they match everything that was recorded)
        val tiers = buildList {
            if (both) add(visible.filter { it.viewId == viewId && it.label == label } to true)
            if (viewId != null) add(visible.filter { it.viewId == viewId } to !both)
            // An element with another view id is another element, whatever it is labelled: Pixel's
            // minibar Night Sight icon carries the Night chip's label, and opens the settings.
            if (label != null) add(visible.filter { it.label == label && (viewId == null || it.viewId.isNullOrBlank()) } to !both)
        }
        for ((candidates, exact) in tiers) {
            if (candidates.isEmpty()) continue
            if (candidates.size == 1) return StepTarget.Element(candidates.single())
            // Several partial matches say only "this id is common": the chip at the recorded spot of
            // a carousel that has scrolled is another mode. Only exact matches may use the position.
            if (!exact) continue
            val atRecorded = candidates.filter { it.contains(signature.centreX, signature.centreY) }
            return if (atRecorded.size == 1) StepTarget.Element(atRecorded.single()) else StepTarget.Ambiguous
        }
        return StepTarget.NotFound
    }

    /** A swipe has no element to find: it is replayed by position, so only on the same screen. */
    fun canSwipe(sameScreen: Boolean): Boolean = sameScreen

    /**
     * The same screen when the natural size matches the recording's. Rotation does not matter: the
     * stored fractions are natural ones, turned to the current rotation when replayed.
     */
    fun sameScreen(mode: CustomCameraMode, naturalWidthPx: Int, naturalHeightPx: Int): Boolean =
        mode.naturalWidthPx == naturalWidthPx && mode.naturalHeightPx == naturalHeightPx
}

/** Which element a finger landed on while recording. */
object NodeAtPoint {
    /**
     * The smallest visible element containing ([x], [y]) that has a view id or a label; else null, and
     * the tap is recorded by position only. The smallest, because a camera screen nests a chip inside
     * a carousel inside a full-screen layout, and only the innermost one says which chip it is.
     */
    fun pick(nodes: List<NodeInfo>, x: Float, y: Float): NodeInfo? =
        nodes
            .filter { it.visible && it.contains(x, y) && it.toSignature().isIdentifiable }
            .minByOrNull { it.area }
}

/** Whether the camera already shows the mode, judged by the element the recording saw selected. */
object ModeProofPolicy {
    /**
     * The proof for a recording: the one identifiable element selected at the end ([after]) that was
     * not selected at the start ([before]). Several such elements (a chip and its icon, say) or none
     * leave the mode without a proof, and it is then replayed every time.
     */
    fun proofFrom(before: List<NodeInfo>, after: List<NodeInfo>): NodeSignature? {
        val wasSelected = before.filter { it.selected }.map { it.viewId to it.label }.toSet()
        val newlySelected = after.filter {
            it.selected && it.visible && !it.label.isNullOrBlank() && (it.viewId to it.label) !in wasSelected
        }
        return newlySelected.singleOrNull()?.toSignature()
    }

    /** True when the proof element is on screen and selected: the mode is on, replay nothing. */
    fun isOn(proof: NodeSignature?, nodes: List<NodeInfo>): Boolean {
        proof ?: return false
        return nodes.any { node ->
            node.selected && node.visible && node.label == proof.label &&
                (proof.viewId == null || node.viewId == proof.viewId)
        }
    }
}
