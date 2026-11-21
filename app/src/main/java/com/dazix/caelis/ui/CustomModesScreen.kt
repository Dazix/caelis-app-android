package com.dazix.caelis.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.dazix.caelis.R
import com.dazix.caelis.domain.cameramode.CustomCameraMode

/** The camera modes the user recorded, for every app, each with the app it belongs to. */
@Composable
fun CustomModesScreen(
    modes: List<CustomCameraMode>,
    onNew: () -> Unit,
    onEdit: (CustomCameraMode) -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.custom_modes_title), onBack = onBack) {
        SectionCard(subtitle = stringResource(R.string.custom_modes_intro)) {
            InfoNote(text = stringResource(R.string.custom_modes_privacy))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.custom_modes_new))
            }
        }
        if (modes.isNotEmpty()) {
            SectionCard {
                modes.forEach { mode ->
                    // The package next to the label, as in the editor: two apps can share a label.
                    val app = stringResource(R.string.custom_modes_row, mode.appLabel, mode.packageName)
                    val steps = pluralStringResource(R.plurals.custom_modes_steps, mode.steps.size, mode.steps.size)
                    ListRowButton(
                        title = mode.name,
                        subtitle = "$app\n$steps",
                        icon = AppIcons.Edit,
                        onClick = { onEdit(mode) },
                    )
                }
            }
        }
    }
}
