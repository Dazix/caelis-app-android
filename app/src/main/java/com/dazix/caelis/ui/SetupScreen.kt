package com.dazix.caelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R

@Composable
fun SetupScreen(
    state: SetupUiState,
    floorMs: Long,
    mediaPermissionGranted: Boolean,
    mediaPermissionPartial: Boolean,
    onChange: (SetupUiState) -> Unit,
    onJitterChange: (Boolean) -> Unit,
    onRequestMediaPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val target = state.target
    ScreenScaffold(title = stringResource(R.string.setup_title), onBack = onBack) {

        // The trigger mode is one choice out of two, and the two are opposites — a connected group
        // shows both at once and which one is in force, where two radio rows only showed a list.
        SectionCard(title = stringResource(R.string.setup_mode)) {
            SegmentedChoice(
                options = ModeChoice.entries,
                selected = state.mode,
                onSelect = { mode -> onChange(state.copy(mode = mode)) },
            ) { mode -> stringResource(modeLabel(mode)) }
            // Secondary text inside a card follows the card's own content colour, which a card tone
            // or a dynamic scheme can change under it; a named role here would not.
            Text(
                text = stringResource(modeDescription(state.mode)),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }

        SectionCard(title = stringResource(R.string.setup_tap_area)) {
            Text(
                text = stringResource(
                    when {
                        target == null -> R.string.setup_tap_area_none
                        target.isRect -> R.string.setup_tap_area_rect
                        else -> R.string.setup_tap_area_point
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            SwitchRow(
                title = stringResource(R.string.setup_tap_jitter),
                description = stringResource(R.string.setup_tap_jitter_desc),
                checked = target?.jitter == true,
                enabled = target?.isRect == true,
                disabledReason = stringResource(R.string.setup_tap_jitter_needs_area),
                disabledReasonAsInfo = true,
                onCheckedChange = onJitterChange,
            )
        }

        SectionCard(
            title = stringResource(R.string.setup_interval),
            subtitle = stringResource(R.string.setup_interval_desc),
        ) {
            DurationRow(
                label = stringResource(R.string.setup_interval),
                value = state.intervalValue,
                unit = state.intervalUnit,
                onValueChange = { onChange(state.copy(intervalValue = it)) },
                onUnitChange = { onChange(state.copy(intervalUnit = it)) },
            )
            Text(
                text = stringResource(R.string.setup_interval_floor, durationText(floorMs)),
                style = MaterialTheme.typography.bodySmall,
                // The error colour is the meaning here — the interval is too short — so it stays a
                // named role; the ordinary case is just secondary text on the card.
                color = if (state.intervalMs < floorMs) {
                    MaterialTheme.colorScheme.error
                } else {
                    LocalContentColor.current.copy(alpha = 0.75f)
                },
            )
            // Night Sight and Astro keep the camera busy for seconds to minutes per photo, so a
            // cadence that suits daylight cannot be delivered there.
            InfoNote(text = stringResource(R.string.setup_interval_night_hint))
        }

        SectionCard(title = stringResource(R.string.setup_shots)) {
            SwitchRow(
                title = stringResource(R.string.setup_shots_unlimited),
                checked = state.unlimitedShots,
                onCheckedChange = { onChange(state.copy(unlimitedShots = it)) },
            )
            NumberField(
                label = stringResource(R.string.setup_shots_count),
                value = state.shotCount,
                onValueChange = { onChange(state.copy(shotCount = it)) },
                suffix = stringResource(R.string.unit_shots),
                enabled = !state.unlimitedShots,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(
            title = stringResource(R.string.setup_start_delay),
            subtitle = stringResource(R.string.setup_start_delay_desc),
        ) {
            DurationRow(
                label = stringResource(R.string.setup_start_delay),
                value = state.startDelayValue,
                unit = state.startDelayUnit,
                onValueChange = { onChange(state.copy(startDelayValue = it)) },
                onUnitChange = { onChange(state.copy(startDelayUnit = it)) },
            )
        }

        SectionCard(
            title = stringResource(R.string.setup_stop_conditions),
            subtitle = stringResource(R.string.setup_stop_conditions_desc),
        ) {
            SwitchRow(
                title = stringResource(R.string.setup_max_duration),
                checked = state.maxDurationEnabled,
                onCheckedChange = { onChange(state.copy(maxDurationEnabled = it)) },
            )
            DurationRow(
                label = stringResource(R.string.setup_max_duration),
                value = state.maxDurationValue,
                unit = state.maxDurationUnit,
                enabled = state.maxDurationEnabled,
                onValueChange = { onChange(state.copy(maxDurationValue = it)) },
                onUnitChange = { onChange(state.copy(maxDurationUnit = it)) },
            )

            SwitchRow(
                title = stringResource(R.string.setup_min_battery),
                checked = state.minBatteryEnabled,
                onCheckedChange = { onChange(state.copy(minBatteryEnabled = it)) },
            )
            // A battery level is a bounded number, so it is a slider: the range is visible, 250% can
            // no longer be typed, and the value stays on screen as text beside it.
            val battery = state.minBatteryPercent.toIntOrNull()?.coerceIn(1, 100) ?: 15
            SliderRow(
                label = stringResource(R.string.setup_min_battery),
                valueLabel = stringResource(R.string.setup_min_battery_value, battery),
                valueDescription = stringResource(
                    R.string.value_with_unit,
                    battery,
                    stringResource(R.string.unit_percent),
                ),
                value = battery.toFloat(),
                onValueChange = { picked ->
                    onChange(state.copy(minBatteryPercent = picked.toInt().toString()))
                },
                valueRange = 1f..100f,
                steps = 98,
                enabled = state.minBatteryEnabled,
            )

            SwitchRow(
                title = stringResource(R.string.setup_min_free_space),
                checked = state.minFreeSpaceEnabled,
                onCheckedChange = { onChange(state.copy(minFreeSpaceEnabled = it)) },
            )
            NumberField(
                label = stringResource(R.string.setup_min_free_space),
                value = state.minFreeSpaceMb,
                onValueChange = { onChange(state.copy(minFreeSpaceMb = it)) },
                suffix = stringResource(R.string.unit_megabytes),
                enabled = state.minFreeSpaceEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val photoAccess = PhotoAccessPrompt.access(mediaPermissionGranted, mediaPermissionPartial)
        SectionCard(title = stringResource(R.string.setup_verify_captures)) {
            SwitchRow(
                // The card's title already says it; repeating it on the switch read as a second heading.
                title = null,
                description = stringResource(R.string.setup_verify_captures_desc),
                checked = state.verifyCaptures,
                onCheckedChange = { checked ->
                    onChange(state.copy(verifyCaptures = checked))
                    // Switching it on is the only moment photo access is asked for. Not after
                    // "Select photos": asking again then opens only the picker for more photos, and
                    // the status below points to the app's settings instead.
                    if (checked && photoAccess == PhotoAccessPrompt.Access.MISSING) {
                        onRequestMediaPermission()
                    }
                },
            )
            if (state.verifyCaptures) {
                if (mediaPermissionGranted) {
                    StatusChip(
                        text = stringResource(R.string.setup_verify_permission_granted),
                        granted = true,
                    )
                } else {
                    // On with "Select photos" (a refusal or Back turns it off, in MainActivity): the
                    // user granted something, and turning it off behind their back would undo that
                    // choice instead of telling them what is missing.
                    // No gap between the text and its buttons (the card spaces by 10 dp), so they read
                    // as part of the switch's status and not as a row of their own (as in Advanced).
                    Column {
                        if (photoAccess == PhotoAccessPrompt.Access.PARTIAL) {
                            IconText(
                                text = stringResource(R.string.setup_verify_permission_partial),
                                icon = AppIcons.Warning,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.setup_verify_permission_needed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Wraps instead of clipping when the labels grow with the font scale.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!PhotoAccessPrompt.allowOpensSettings(photoAccess)) {
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
            }
        }
    }
}

private fun modeLabel(mode: ModeChoice): Int = when (mode) {
    ModeChoice.TIMELAPSE -> R.string.setup_mode_timelapse
    ModeChoice.PERIODIC_TAP -> R.string.setup_mode_periodic
}

private fun modeDescription(mode: ModeChoice): Int = when (mode) {
    ModeChoice.TIMELAPSE -> R.string.setup_mode_timelapse_desc
    ModeChoice.PERIODIC_TAP -> R.string.setup_mode_periodic_desc
}
