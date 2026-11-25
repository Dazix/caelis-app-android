package com.dazix.caelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.data.AdvancedSettings
import com.dazix.caelis.domain.cameramode.CustomCameraMode
import com.dazix.caelis.domain.cameramode.CustomModeKey
import com.dazix.caelis.service.CameraProfile

/**
 * Device- and camera-app-specific extras. Everything is off by default and each row states plainly
 * when the chosen app's profile has no way to do it, instead of hiding the row.
 */
@Composable
fun AdvancedScreen(
    settings: AdvancedSettings,
    profile: CameraProfile,
    targetLabel: String?,
    overlayAllowed: Boolean,
    mediaPermissionGranted: Boolean,
    mediaPermissionPartial: Boolean,
    onChange: (AdvancedSettings) -> Unit,
    onRequestMediaPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit,
    targetPackage: String? = null,
    customModes: List<CustomCameraMode> = emptyList(),
    onManageCustomModes: () -> Unit = {},
) {
    val photoAccess = PhotoAccessPrompt.access(mediaPermissionGranted, mediaPermissionPartial)
    var explainingPhotoAccess by rememberSaveable { mutableStateOf(false) }
    val appName = targetLabel ?: stringResource(R.string.home_target_app_none)
    val noAppReason = stringResource(R.string.advanced_reason_needs_app)
    val unsupportedReason = stringResource(R.string.advanced_reason_needs_pixel, appName)
    val hasApp = targetLabel != null
    fun reason(): String = if (hasApp) unsupportedReason else noAppReason

    ScreenScaffold(title = stringResource(R.string.advanced_title), onBack = onBack) {

        SectionCard(subtitle = stringResource(R.string.advanced_intro)) {
            Text(
                text = stringResource(R.string.advanced_profile, profile.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The modes come from the profile, never from a literal here: a chip is labelled in the
        // device language ("Night Sight" / "Noční vidění"), so only the profile's own aliases can
        // find it. The UI stores the profile's stable key and shows a localized name for it.
        // Recorded modes work in any camera app, so with an app chosen the section is always usable;
        // only the chosen app's own recordings are offered.
        val modeKeys = LaunchModeOptions.keysFor(profile, hasApp)
        val custom = LaunchModeOptions.customFor(customModes, targetPackage.takeIf { hasApp })
        val chosenMode = LaunchModeOptions.selected(settings, profile, hasApp, custom)
        SectionCard(
            title = stringResource(R.string.advanced_launch_mode),
            subtitle = stringResource(R.string.advanced_launch_mode_desc),
        ) {
            val stored = settings.launchCameraMode?.takeIf { it.isNotBlank() }
            if (!hasApp) {
                Text(
                    text = noAppReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (stored != null && chosenMode == null) {
                // Stored for another app, or a recording since deleted: it is off, and says so.
                Text(
                    text = if (CustomModeKey.idOf(stored) != null) {
                        stringResource(R.string.advanced_launch_mode_custom_missing, appName)
                    } else {
                        stringResource(R.string.advanced_launch_mode_unsupported, modeLabel(stored), appName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (modeKeys.isEmpty() && custom.isEmpty()) {
                InfoNote(text = stringResource(R.string.advanced_launch_mode_unavailable, appName))
            }
            ChoiceRow(
                title = stringResource(R.string.advanced_launch_mode_none),
                selected = chosenMode == null,
                enabled = hasApp,
                onSelect = { onChange(LaunchModeOptions.choose(settings, null)) },
            )
            modeKeys.forEach { key ->
                ChoiceRow(
                    title = modeLabel(key),
                    selected = chosenMode == key,
                    onSelect = { onChange(LaunchModeOptions.choose(settings, key)) },
                )
            }
            custom.forEach { mode ->
                ChoiceRow(
                    title = mode.name,
                    description = stringResource(R.string.advanced_launch_mode_custom_desc),
                    selected = chosenMode == mode.key,
                    onSelect = { onChange(LaunchModeOptions.choose(settings, mode.key, custom)) },
                )
            }
            ListRowButton(
                title = stringResource(R.string.advanced_launch_mode_manage),
                subtitle = stringResource(R.string.advanced_launch_mode_manage_desc),
                icon = AppIcons.Edit,
                onClick = onManageCustomModes,
            )
        }

        SectionCard {
            // Works for any camera app (MediaStore and a disabled shutter), so only an app is needed.
            SwitchRow(
                title = stringResource(R.string.advanced_interval_after_capture),
                description = stringResource(R.string.advanced_interval_after_capture_desc),
                checked = settings.intervalAfterCapture && hasApp,
                enabled = hasApp,
                disabledReason = noAppReason,
                onCheckedChange = { checked ->
                    // On at once: without photo access it still works, by the shutter.
                    onChange(settings.copy(intervalAfterCapture = checked))
                    if (PhotoAccessPrompt.explainOnToggle(checked, photoAccess)) {
                        explainingPhotoAccess = true
                    }
                },
            )
            if (PhotoAccessPrompt.showStatus(settings.intervalAfterCapture, hasApp)) {
                PhotoAccessStatus(
                    access = photoAccess,
                    onRequestMediaPermission = onRequestMediaPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }

            val timerSupported = hasApp &&
                profile.selfTimerOnPatterns.isNotEmpty() &&
                profile.selfTimerOffPatterns.isNotEmpty()
            SwitchRow(
                title = stringResource(R.string.advanced_self_timer),
                description = stringResource(R.string.advanced_self_timer_desc),
                checked = settings.ensureSelfTimerOff && timerSupported,
                enabled = timerSupported,
                disabledReason = reason(),
                onCheckedChange = { onChange(settings.copy(ensureSelfTimerOff = it)) },
            )

            val dismissSupported = hasApp && profile.postCaptureDismissPatterns.isNotEmpty()
            SwitchRow(
                title = stringResource(R.string.advanced_dismiss_post_capture),
                description = stringResource(R.string.advanced_dismiss_post_capture_desc),
                checked = settings.dismissPostCaptureUi && dismissSupported,
                enabled = dismissSupported,
                disabledReason = reason(),
                onCheckedChange = { onChange(settings.copy(dismissPostCaptureUi = it)) },
            )

            // Needs nothing but the gyroscope, so unlike the rows above it is never disabled: a
            // phone without one simply never warns.
            SwitchRow(
                title = stringResource(R.string.advanced_handheld_warning),
                description = stringResource(R.string.advanced_handheld_warning_desc),
                checked = settings.handheldWarning,
                onCheckedChange = { onChange(settings.copy(handheldWarning = it)) },
            )
        }

        // Both of these are carried out by the floating session panel, so they are only available
        // once drawing over other apps is allowed — and both stay off until the user asks for them.
        val overlayReason = stringResource(R.string.advanced_reason_needs_overlay)
        SectionCard(title = stringResource(R.string.advanced_session_screen)) {
            SwitchRow(
                title = stringResource(R.string.advanced_keep_screen_on),
                description = stringResource(R.string.advanced_keep_screen_on_desc),
                checked = settings.keepScreenOn && overlayAllowed,
                enabled = overlayAllowed,
                disabledReason = overlayReason,
                onCheckedChange = { onChange(settings.copy(keepScreenOn = it)) },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_dim_overlay),
                description = stringResource(R.string.advanced_dim_overlay_desc),
                checked = settings.dimOverlay && overlayAllowed,
                enabled = overlayAllowed,
                disabledReason = overlayReason,
                onCheckedChange = { onChange(settings.copy(dimOverlay = it)) },
            )
        }
    }

    if (explainingPhotoAccess) {
        AlertDialog(
            onDismissRequest = { explainingPhotoAccess = false },
            title = { Text(stringResource(R.string.advanced_photo_access_title)) },
            text = {
                Text(
                    text = stringResource(R.string.advanced_photo_access_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        explainingPhotoAccess = false
                        if (PhotoAccessPrompt.allowOpensSettings(photoAccess)) {
                            onOpenAppSettings()
                        } else {
                            onRequestMediaPermission()
                        }
                    },
                ) { Text(stringResource(R.string.action_grant)) }
            },
            dismissButton = {
                TextButton(onClick = { explainingPhotoAccess = false }) {
                    Text(stringResource(R.string.advanced_photo_access_later))
                }
            },
        )
    }
}

/**
 * Says how the end of a photo is seen. Missing access is not an error here (the shutter still tells),
 * so it is worded as what happens instead and not coloured as a fault. The app settings button is
 * there because Android stops showing the request after two refusals, and then only settings remain.
 * After "Select photos" it is the only button: asking again opens just the picker for more photos.
 */
@Composable
private fun PhotoAccessStatus(
    access: PhotoAccessPrompt.Access,
    onRequestMediaPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    if (access == PhotoAccessPrompt.Access.GRANTED) {
        StatusChip(text = stringResource(R.string.setup_verify_permission_granted), granted = true)
        return
    }
    // No gap between the text and its buttons (the card spaces by 10 dp): with one the buttons read
    // as a row of their own rather than as part of this switch's status.
    Column {
        val partial = access == PhotoAccessPrompt.Access.PARTIAL
        IconText(
            text = stringResource(
                if (partial) R.string.setup_verify_permission_partial else R.string.advanced_photo_access_missing
            ),
            icon = if (partial) AppIcons.Warning else AppIcons.Info,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        // Wraps instead of clipping when the labels grow with the font scale.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!PhotoAccessPrompt.allowOpensSettings(access)) {
                TextButton(onClick = onRequestMediaPermission) {
                    Text(stringResource(R.string.action_grant))
                }
            }
            TextButton(onClick = onOpenAppSettings) {
                Text(stringResource(R.string.setup_verify_open_settings))
            }
        }
    }
}

/**
 * Localized names for the mode keys the profiles use. A profile is free to offer a key this table
 * does not know — then the key itself is shown rather than the row disappearing, because a mode the
 * profile can switch to is always worth offering.
 */
private val MODE_LABELS: Map<String, Int> = mapOf(
    CameraProfile.MODE_PHOTO to R.string.advanced_launch_mode_photo,
    CameraProfile.MODE_NIGHT to R.string.advanced_launch_mode_night,
    CameraProfile.MODE_ASTRO to R.string.advanced_launch_mode_astro,
)

@Composable
internal fun launchModeName(mode: LaunchModeOptions.InForce): String = when (mode) {
    is LaunchModeOptions.InForce.Builtin -> modeLabel(mode.key)
    is LaunchModeOptions.InForce.Recorded -> mode.mode.name
}

@Composable
private fun modeLabel(key: String): String =
    MODE_LABELS[key]?.let { stringResource(it) }
        ?: key.replaceFirstChar { it.uppercase() }
