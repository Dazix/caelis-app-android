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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dazix.caelis.ui.CardTone
import com.dazix.caelis.ui.Permissions
import com.dazix.caelis.ui.ScreenScaffold
import com.dazix.caelis.ui.SectionCard
import com.dazix.caelis.ui.SegmentedChoice
import com.dazix.caelis.ui.SwitchRow
import com.dazix.caelis.domain.studio.Aspect
import com.dazix.caelis.domain.studio.OutputPlan
import com.dazix.caelis.domain.studio.Resolution
import com.dazix.caelis.domain.studio.StudioOptions

/**
 * Studio: building a video or a stacked picture from a finished session's photos, which it finds
 * itself with photo access (MediaStore), or in one granted folder without it.
 */
@Composable
fun StudioScreen(
    onBack: () -> Unit,
    viewModel: StudioViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // On every resume, not only on entry: coming back from the app settings may have granted access.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    JobLifecycle(viewModel, running = state.job is StudioJob.Running)
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onFolderPicked(it)
    }
    // After a refusal the system may not ask again (twice refused), so the button becomes the settings.
    var refused by rememberSaveable { mutableStateOf(false) }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refused = it[Permissions.photos()] != true
        viewModel.refresh()
    }

    ScreenScaffold(
        title = stringResource(R.string.studio_title),
        subtitle = stringResource(R.string.studio_subtitle),
        onBack = onBack,
    ) {
        PhotosCard(
            state = state,
            refused = refused,
            onAllow = { askPhotos.launch(Permissions.photoRequest()) },
            onSettings = { runCatching { context.startActivity(Permissions.appInfo(context)) } },
            onPickFolder = { pickFolder.launch(FrameSource.CAMERA_FOLDER_HINT) },
        )

        val options by viewModel.options.collectAsStateWithLifecycle()
        OptionsCard(options = options, enabled = state.job !is StudioJob.Running, onChange = viewModel::updateOptions)

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
                    fps = options.fps,
                    onStart = { action -> viewModel.start(session.record, action) },
                    onCancel = viewModel::cancel,
                )
            }
        }
    }
}


/**
 * Where the photos come from. Photo access is the way: Studio finds a session's photos itself. One
 * granted folder is the quiet fallback without it, a text button behind [FOLDER_FALLBACK_SHOWN].
 */
@Composable
private fun PhotosCard(
    state: StudioUiState,
    refused: Boolean,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val subtitle = when {
        state.photoAccess -> stringResource(R.string.studio_photos_granted)
        state.partialPhotoAccess -> stringResource(R.string.studio_photos_partial)
        state.folderGranted -> state.folderName ?: stringResource(R.string.studio_folder_unnamed)
        else -> stringResource(R.string.studio_photos_missing)
    }
    val wanting = !state.loading && !state.photoAccess && !state.folderGranted
    SectionCard(
        title = stringResource(R.string.studio_photos_title),
        subtitle = subtitle,
        tone = if (wanting) CardTone.ACCENT else CardTone.NEUTRAL,
    ) {
        if (state.photoAccess || state.loading) return@SectionCard
        if (state.folderError) {
            Text(
                text = stringResource(R.string.studio_folder_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Asking again with only selected photos opens just the picker for more of them.
        if (state.partialPhotoAccess || refused) {
            Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.studio_photos_settings))
            }
        } else {
            Button(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.studio_photos_allow))
            }
        }
        if (!FOLDER_FALLBACK_SHOWN) return@SectionCard
        TextButton(onClick = onPickFolder, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                stringResource(
                    if (state.folderGranted) R.string.studio_folder_change else R.string.studio_folder_instead,
                ),
            )
        }
    }
}

/**
 * The folder fallback is hidden for now: photo access is the one way in while Studio is in beta. A
 * folder granted earlier still works (and joining videos needs it), it just cannot be picked anew.
 */
private const val FOLDER_FALLBACK_SHOWN = false

/** One set of options for every output; read when a job starts, so a change mid-run waits for the next. */
@Composable
private fun OptionsCard(
    options: StudioOptions,
    enabled: Boolean,
    onChange: ((StudioOptions) -> StudioOptions) -> Unit,
) {
    SectionCard(title = stringResource(R.string.studio_options_title)) {
        Text(stringResource(R.string.studio_options_fps), style = MaterialTheme.typography.labelLarge)
        SegmentedChoice(
            options = StudioOptions.FPS_CHOICES,
            selected = options.fps,
            onSelect = { fps -> onChange { it.copy(fps = fps) } },
            enabled = enabled,
        ) { it.toString() }
        Text(stringResource(R.string.studio_options_resolution), style = MaterialTheme.typography.labelLarge)
        SegmentedChoice(
            options = Resolution.entries,
            selected = options.resolution,
            onSelect = { r -> onChange { it.copy(resolution = r) } },
            enabled = enabled,
        ) { if (it == Resolution.FHD) "1080p" else "4K" }
        Text(stringResource(R.string.studio_options_aspect), style = MaterialTheme.typography.labelLarge)
        SegmentedChoice(
            options = Aspect.entries,
            selected = options.aspect,
            onSelect = { a -> onChange { it.copy(aspect = a) } },
            enabled = enabled,
        ) { if (it == Aspect.ORIGINAL) stringResource(R.string.studio_options_aspect_original) else "${it.w}:${it.h}" }
        SwitchRow(
            title = stringResource(R.string.studio_options_deflicker),
            description = stringResource(R.string.studio_options_deflicker_summary),
            checked = options.deflicker,
            onCheckedChange = { on -> onChange { it.copy(deflicker = on) } },
            enabled = enabled,
        )
        SwitchRow(
            title = stringResource(R.string.studio_options_join_mute),
            checked = options.joinWithoutAudio,
            onCheckedChange = { on -> onChange { it.copy(joinWithoutAudio = on) } },
            enabled = enabled,
        )
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
    fps: Int,
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
    val length = session.photos?.takeIf { it > 0 }?.let { n ->
        stringResource(R.string.studio_session_length, OutputPlan.durationMs(n, fps) / 1000.0)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(text = period, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = listOfNotNull(shots, found, videos, length).joinToString(" · "),
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
