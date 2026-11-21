package com.dazix.caelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.cameramode.RecordedStep

/**
 * Creates or edits one recorded mode: its name, the camera app it belongs to (the session's app for
 * a new mode, shown and not editable) and the steps, which are recorded over that app and can be tested from here.
 */
@Composable
fun CustomModeEditScreen(
    draft: CustomModeDraft,
    onChange: ((CustomModeDraft) -> CustomModeDraft) -> Unit,
    onRecord: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val appName = draft.appLabel ?: draft.packageName
    val focusManager = LocalFocusManager.current

    ScreenScaffold(
        title = stringResource(if (draft.id == null) R.string.custom_mode_new_title else R.string.custom_mode_edit_title),
        onBack = onBack,
    ) {
        SectionCard {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { name -> onChange { it.copy(name = name) } },
                label = { Text(stringResource(R.string.custom_mode_name)) },
                placeholder = { Text(stringResource(R.string.custom_mode_name_hint)) },
                singleLine = true,
                // The keyboard covers Save on a phone; Done puts it away.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            // Shown, not chosen: a new mode belongs to the session's camera app, and recorded steps
            // only mean something in the app they were recorded in.
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.custom_mode_app),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
                Text(
                    text = appName ?: stringResource(R.string.custom_mode_app_none),
                    style = MaterialTheme.typography.bodyLarge,
                )
                draft.packageName?.let { pkg ->
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
            if (draft.stepsFromAnotherApp) {
                Text(
                    text = stringResource(R.string.custom_mode_steps_other_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Off by default: unlike Night and Astro, nothing says a recorded mode is a long exposure.
            SwitchRow(
                title = stringResource(R.string.advanced_interval_after_capture),
                description = stringResource(R.string.custom_mode_interval_after_capture_desc),
                checked = draft.intervalAfterCapture,
                onCheckedChange = { on -> onChange { it.copy(intervalAfterCapture = on) } },
            )
        }

        SectionCard(
            title = stringResource(R.string.custom_mode_steps),
            subtitle = stringResource(R.string.custom_mode_steps_desc),
        ) {
            if (draft.steps.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_mode_steps_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
            draft.steps.forEachIndexed { index, step ->
                StepRow(index, step, onRemove = { onChange { it.withoutStep(index) } })
            }
            if (draft.steps.isNotEmpty()) {
                InfoNote(
                    text = stringResource(
                        if (draft.proof != null) R.string.custom_mode_proof_found else R.string.custom_mode_proof_none,
                    ),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRecord, enabled = draft.packageName != null) {
                    Text(
                        stringResource(
                            if (draft.steps.isEmpty()) R.string.custom_mode_record else R.string.custom_mode_record_again,
                        ),
                    )
                }
                OutlinedButton(onClick = onTest, enabled = draft.problem == null) {
                    Text(stringResource(R.string.custom_mode_test))
                }
            }
            InfoNote(text = stringResource(R.string.custom_modes_privacy))
        }

        SectionCard {
            draft.problem?.let { problem ->
                Text(
                    text = stringResource(problemText(problem)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSave, enabled = draft.problem == null) {
                    Text(stringResource(R.string.custom_mode_save))
                }
                if (draft.id != null) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(stringResource(R.string.custom_mode_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.custom_mode_delete_title, draft.name)) },
            text = { Text(stringResource(R.string.custom_mode_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.custom_mode_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.custom_mode_close)) }
            },
        )
    }
}

@Composable
private fun StepRow(index: Int, step: RecordedStep, onRemove: () -> Unit) {
    val seconds = String.format(LocalConfiguration.current.locales[0], "%.1f", step.delayBeforeMs / 1000.0)
    val what = when (step) {
        is RecordedStep.Tap -> step.signature?.label?.let { stringResource(R.string.custom_mode_step_tap_on, it) }
            ?: stringResource(R.string.custom_mode_step_tap)
        is RecordedStep.Swipe -> stringResource(R.string.custom_mode_step_swipe)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_mode_step_row, index + 1, what, seconds),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.custom_mode_step_remove, index + 1))
        }
    }
}

private fun problemText(problem: DraftProblem): Int = when (problem) {
    DraftProblem.NO_NAME -> R.string.custom_mode_problem_name
    DraftProblem.NO_APP -> R.string.custom_mode_problem_app
    DraftProblem.NO_STEPS -> R.string.custom_mode_problem_steps
    DraftProblem.STEPS_FROM_ANOTHER_APP -> R.string.custom_mode_steps_other_app
}
