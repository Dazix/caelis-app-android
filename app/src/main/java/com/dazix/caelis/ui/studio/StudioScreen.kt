package com.dazix.caelis.ui.studio

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.caelis.R
import com.dazix.caelis.data.studio.FrameSource
import com.dazix.caelis.ui.CardTone
import com.dazix.caelis.ui.ScreenScaffold
import com.dazix.caelis.ui.SectionCard

/**
 * Studio: building a video or a stacked picture from a finished session's frames. This first version
 * only grants the folder and shows which sessions it can find frames for.
 */
@Composable
fun StudioScreen(
    onBack: () -> Unit,
    viewModel: StudioViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    JobLifecycle(viewModel, running = state.job is StudioJob.Running)
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onFolderPicked(it)
    }

    ScreenScaffold(
        title = stringResource(R.string.studio_title),
        subtitle = stringResource(R.string.studio_subtitle),
        onBack = onBack,
    ) {
        SectionCard(
            title = stringResource(R.string.studio_folder_title),
            subtitle = if (state.folderGranted) {
                state.folderName ?: stringResource(R.string.studio_folder_unnamed)
            } else {
                stringResource(R.string.studio_folder_missing)
            },
            tone = if (state.folderGranted || state.loading) CardTone.NEUTRAL else CardTone.ACCENT,
        ) {
            if (state.folderError) {
                Text(
                    text = stringResource(R.string.studio_folder_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { pickFolder.launch(FrameSource.CAMERA_FOLDER_HINT) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        if (state.folderGranted) R.string.studio_folder_change else R.string.studio_folder_choose,
                    ),
                )
            }
        }

        SectionCard(title = stringResource(R.string.studio_sessions_title)) {
            if (!state.loading && state.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.studio_sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.sessions.forEachIndexed { i, session ->
                if (i > 0) HorizontalDivider()
                SessionRow(
                    session = session,
                    job = state.job?.takeIf { it.sessionStart == session.record.startWallMs },
                    busy = state.job is StudioJob.Running,
                    onStart = { action -> viewModel.start(session.record, action) },
                    onCancel = viewModel::cancel,
                )
            }
        }
    }
}


/** Keeps the screen on while a job runs, and stops the job when the screen goes away. */
@Composable
private fun JobLifecycle(viewModel: StudioViewModel, running: Boolean) {
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
}

@Composable
private fun SessionRow(
    session: StudioSession,
    job: StudioJob?,
    busy: Boolean,
    onStart: (StudioAction) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val r = session.record
    val period = DateUtils.formatDateRange(
        context,
        r.startWallMs,
        r.endWallMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val shots = pluralStringResource(R.plurals.studio_session_shots, r.shots, r.shots)
    val found = session.photos?.let { pluralStringResource(R.plurals.studio_session_found, it, it) }
        ?: stringResource(R.string.studio_session_found_unknown)
    val videos = session.videos?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.studio_session_videos, it, it) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(text = period, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = listOfNotNull(shots, found, videos).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
        when (job) {
            is StudioJob.Running -> {
                Text(
                    text = job.percent?.let { stringResource(R.string.studio_job_progress, it) }
                        ?: stringResource(R.string.studio_job_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val percent = job.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.studio_job_cancel))
                }
            }
            is StudioJob.Done -> {
                val video = job.action.outputMime.startsWith("video/")
                Text(
                    text = stringResource(if (video) R.string.studio_job_done_video else R.string.studio_job_done),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (job.skipped > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.studio_job_skipped, job.skipped, job.skipped),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        val open = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(job.uri, job.action.outputMime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(open) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.studio_job_open))
                }
            }
            is StudioJob.Failed -> Text(
                text = stringResource(R.string.studio_job_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            null -> Unit
        }
        if (job !is StudioJob.Running) {
            val actions = buildList {
                if ((session.photos ?: 0) > 0) {
                    add(StudioAction.VIDEO)
                    add(StudioAction.TRAILS)
                    add(StudioAction.GROWING_TRAILS)
                    add(StudioAction.AVERAGE)
                }
                if ((session.videos ?: 0) > 0) add(StudioAction.JOIN)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                actions.forEach { action ->
                    OutlinedButton(
                        onClick = { onStart(action) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(action.label())) }
                }
            }
        }
    }
}

private fun StudioAction.label(): Int = when (this) {
    StudioAction.VIDEO -> R.string.studio_action_video
    StudioAction.TRAILS -> R.string.studio_action_trails
    StudioAction.GROWING_TRAILS -> R.string.studio_action_growing
    StudioAction.AVERAGE -> R.string.studio_action_average
    StudioAction.JOIN -> R.string.studio_action_join
}
