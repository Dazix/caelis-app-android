package com.dazix.caelis.ui

import com.dazix.caelis.domain.cameramode.CustomCameraMode
import com.dazix.caelis.domain.cameramode.NodeSignature
import com.dazix.caelis.domain.cameramode.RecordedStep
import com.dazix.caelis.overlay.ModeRecording

/** Why a draft cannot be saved yet; shown under the Save button instead of a silent disable. */
enum class DraftProblem { NO_NAME, NO_APP, NO_STEPS, STEPS_FROM_ANOTHER_APP }

/**
 * A recorded mode being created or edited. The app is prefilled with the session's camera app and can
 * be changed; steps recorded in one app are never saved under another, because they tap that app's
 * screen.
 */
data class CustomModeDraft(
    /** Null for a mode not saved yet. */
    val id: String? = null,
    val name: String = "",
    val packageName: String? = null,
    val appLabel: String? = null,
    val steps: List<RecordedStep> = emptyList(),
    val proof: NodeSignature? = null,
    val naturalWidthPx: Int = 0,
    val naturalHeightPx: Int = 0,
    /** The app the steps were recorded in. */
    val recordedIn: String? = null,
    val createdAtMs: Long = 0L,
    val intervalAfterCapture: Boolean = false,
) {
    val stepsFromAnotherApp: Boolean
        get() = steps.isNotEmpty() && recordedIn != null && recordedIn != packageName

    val problem: DraftProblem?
        get() = when {
            name.isBlank() -> DraftProblem.NO_NAME
            packageName == null -> DraftProblem.NO_APP
            steps.isEmpty() -> DraftProblem.NO_STEPS
            stepsFromAnotherApp -> DraftProblem.STEPS_FROM_ANOTHER_APP
            else -> null
        }

    /** The draft as a mode to store or test, or null while it has a [problem]. */
    fun toMode(newId: () -> String, nowMs: Long): CustomCameraMode? {
        if (problem != null) return null
        return CustomCameraMode(
            id = id ?: newId(),
            name = name.trim(),
            packageName = packageName ?: return null,
            appLabel = appLabel ?: packageName,
            steps = steps,
            proof = proof,
            naturalWidthPx = naturalWidthPx,
            naturalHeightPx = naturalHeightPx,
            createdAtMs = if (createdAtMs > 0) createdAtMs else nowMs,
            intervalAfterCapture = intervalAfterCapture,
        )
    }

    /** A recording replaces all steps: a mode switch is one sequence, not steps to append to. */
    fun withRecording(recording: ModeRecording): CustomModeDraft = copy(
        steps = recording.steps,
        proof = recording.proof,
        naturalWidthPx = recording.naturalWidthPx,
        naturalHeightPx = recording.naturalHeightPx,
        recordedIn = recording.packageName,
    )

    /** Removing a step leaves the proof alone: it describes where the mode ends, not a step. */
    fun withoutStep(index: Int): CustomModeDraft =
        if (index in steps.indices) copy(steps = steps.filterIndexed { i, _ -> i != index }) else this

    companion object {
        fun from(mode: CustomCameraMode) = CustomModeDraft(
            id = mode.id,
            name = mode.name,
            packageName = mode.packageName,
            appLabel = mode.appLabel,
            steps = mode.steps,
            proof = mode.proof,
            naturalWidthPx = mode.naturalWidthPx,
            naturalHeightPx = mode.naturalHeightPx,
            recordedIn = mode.packageName,
            createdAtMs = mode.createdAtMs,
            intervalAfterCapture = mode.intervalAfterCapture,
        )
    }
}
