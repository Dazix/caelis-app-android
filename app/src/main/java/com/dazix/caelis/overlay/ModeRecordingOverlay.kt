package com.dazix.caelis.overlay

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.dazix.caelis.domain.cameramode.NodeSignature
import com.dazix.caelis.domain.cameramode.RecordedStep
import com.dazix.caelis.service.ShutterAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a finished recording produced, for the mode editor to take over. */
data class ModeRecording(
    val packageName: String,
    val steps: List<RecordedStep>,
    val proof: NodeSignature?,
    val naturalWidthPx: Int,
    val naturalHeightPx: Int,
    /** Increases with every recording of the process, so the same steps recorded twice still arrive. */
    val serial: Long,
)

/**
 * Entry point for recording a camera mode: the target app is brought to the front and a recording
 * surface goes over it, as for calibration ([CalibrationOverlay]). Unlike calibration, every gesture
 * is passed on to the camera app (through the accessibility service, the only thing that taps), so
 * the user switches the mode exactly as they would by hand while the steps are written down.
 */
object ModeRecordingOverlay {

    enum class StartResult { STARTED, NO_OVERLAY_PERMISSION, NO_SERVICE, FAILED }

    private val activeState = MutableStateFlow(false)
    private val resultState = MutableStateFlow<ModeRecording?>(null)
    private var serial = 0L

    val isActive: StateFlow<Boolean> = activeState.asStateFlow()

    /** The latest finished recording of this process; null until one is finished. */
    val lastResult: StateFlow<ModeRecording?> = resultState.asStateFlow()

    /**
     * Opens [packageName] and starts recording over it. Needs the overlay permission (the surface)
     * and the accessibility service with no session running (passing the gestures on).
     */
    fun start(context: Context, packageName: String): StartResult {
        if (!Settings.canDrawOverlays(context)) return StartResult.NO_OVERLAY_PERMISSION
        if (ShutterAccessibilityService.modeTools() == null) return StartResult.NO_SERVICE
        val launched = launchTarget(context, packageName)
        val started = runCatching {
            context.startService(ModeRecordingService.startIntent(context, packageName, launched))
        }.isSuccess
        return if (started) StartResult.STARTED else StartResult.FAILED
    }

    internal fun reportActive(active: Boolean) {
        activeState.value = active
    }

    internal fun reportResult(recording: ModeRecording) {
        resultState.value = recording.copy(serial = ++serial)
    }

    private fun launchTarget(context: Context, packageName: String): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
