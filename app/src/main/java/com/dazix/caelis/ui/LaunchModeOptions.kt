package com.dazix.caelis.ui

import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.domain.cameramode.CustomCameraMode
import com.dazix.caelis.domain.cameramode.CustomModeKey
import com.dazix.caelis.service.CameraProfile

/**
 * Which "launch the camera in a mode" choices the advanced screen may offer, and which one is
 * actually in force.
 *
 * Both answers come from the chosen app's [CameraProfile] and never from a literal in the UI: mode
 * chips are labelled in the device language, so only the profile's own aliases can find them. The
 * settings store therefore holds a profile *key* ("night"), never a display string ("Night Sight").
 */
internal object LaunchModeOptions {

    /** The keys offerable right now. Empty when no app is chosen or its profile knows no modes. */
    fun keysFor(profile: CameraProfile, hasApp: Boolean): List<String> =
        if (hasApp) profile.supportedModeKeys().toList() else emptyList()

    /**
     * The settings after the user picked [key] (null: leave the mode alone). Night and Astro turn
     * [AdvancedSettings.intervalAfterCapture] on, because their captures run for minutes and a
     * tap during one cancels it; the user can turn it off again. Nothing turns it off on its own.
     * A recorded mode in [custom] does the same when the user set it to
     * ([CustomCameraMode.intervalAfterCapture]).
     */
    fun choose(
        settings: AdvancedSettings,
        key: String?,
        custom: List<CustomCameraMode> = emptyList(),
    ): AdvancedSettings {
        val recorded = CustomModeKey.idOf(key)?.let { id -> custom.firstOrNull { it.id == id } }
        val longExposure = key == CameraProfile.MODE_NIGHT || key == CameraProfile.MODE_ASTRO ||
            recorded?.intervalAfterCapture == true
        return settings.copy(
            launchCameraMode = key,
            intervalAfterCapture = settings.intervalAfterCapture || longExposure,
        )
    }

    /**
     * The stored mode, but only while this profile can actually switch into it. A key kept from a
     * previously chosen app leaves the setting off — the same rule every other advanced extra
     * follows — and the screen says why instead of pretending the mode is armed. A recorded mode is
     * selected only while it is in [custom], the chosen app's own recorded modes.
     */
    fun selected(
        settings: AdvancedSettings,
        profile: CameraProfile,
        hasApp: Boolean,
        custom: List<CustomCameraMode> = emptyList(),
    ): String? {
        val stored = settings.launchCameraMode?.takeIf { it.isNotBlank() } ?: return null
        if (!hasApp) return null
        CustomModeKey.idOf(stored)?.let { id -> return stored.takeIf { custom.any { it.id == id } } }
        if (keysFor(profile, hasApp).isEmpty()) return null
        return stored.takeIf { profile.supportsMode(it) }
    }

    /**
     * The recorded modes offered for the chosen app: only its own. A mode recorded in another camera
     * app taps that app's screen and means nothing here.
     */
    fun customFor(modes: List<CustomCameraMode>, packageName: String?): List<CustomCameraMode> =
        if (packageName == null) emptyList() else modes.filter { it.packageName == packageName }
}
