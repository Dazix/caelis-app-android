package com.dazix.caelis.ui.studio

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import com.dazix.caelis.domain.studio.OutputName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.dazix.caelis.domain.studio.SessionPick
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.ListRowButton
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

    val options by viewModel.options.collectAsStateWithLifecycle()
    val running = state.job is StudioJob.Running
    // Only a pick is remembered; a single session or a running job's session is derived (SessionPick).
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val selectedStart = SessionPick.selected(
        starts = state.sessions.map { it.record.startWallMs },
        picked = picked,
        jobStart = state.job?.sessionStart,
    )
    val selected = state.sessions.firstOrNull { it.record.startWallMs == selectedStart }

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

        SessionCard(
            loading = state.loading,
            sessions = state.sessions,
            selected = selected,
            job = state.job?.takeIf { it.sessionStart == selectedStart },
            fps = options.fps,
            canPick = state.sessions.size > 1 && !running,
            onPick = { picking = true },
            onStart = { action -> selected?.let { viewModel.start(it.record, action) } },
            onCancel = viewModel::cancel,
            onRename = viewModel::rename,
        )

        OptionsCard(options = options, enabled = !running, onChange = viewModel::updateOptions)
    }

    if (picking) {
        SessionSheet(
            sessions = state.sessions,
            selectedStart = selectedStart,
            fps = options.fps,
            onPick = { start ->
                picked = start
                picking = false
            },
            onDismiss = { picking = false },
        )
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

/**
 * The session Studio works on and what it can build from it. With several sessions the user picks
 * one in a sheet; with one it is simply shown. The actions are listed under it, each with a line on
 * what it makes, and give way to the progress while a job runs.
 */
@Composable
private fun SessionCard(
    loading: Boolean,
    sessions: List<StudioSession>,
    selected: StudioSession?,
    job: StudioJob?,
    fps: Int,
    canPick: Boolean,
    onPick: () -> Unit,
    onStart: (StudioAction) -> Unit,
    onCancel: () -> Unit,
    onRename: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.studio_sessions_title)) {
        if (loading) return@SectionCard
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.studio_sessions_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        SessionPicker(selected = selected, fps = fps, canPick = canPick, onPick = onPick)
        if (selected == null) return@SectionCard
        if (selected.photoUris.isNotEmpty()) SessionPhotos(selected.photoUris)
        JobStatus(job = job, onCancel = onCancel, onRename = onRename)
        if (job is StudioJob.Running) return@SectionCard
        val actions = selected.actions()
        if (actions.isNotEmpty()) HorizontalDivider()
        actions.forEach { action ->
            val label = stringResource(action.label())
            // A start button beside the line: a title and a description alone read as text, not as an action.
            ListRowButton(
                title = label,
                subtitle = stringResource(action.description()),
                onClick = { onStart(action) },
                trailing = {
                    FilledTonalIconButton(
                        onClick = { onStart(action) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Play,
                            contentDescription = stringResource(R.string.studio_action_start, label),
                        )
                    }
                },
            )
        }
    }
}

/** The chosen session, or the call to choose one; opens the sheet only when there is a choice. */
@Composable
private fun SessionPicker(selected: StudioSession?, fps: Int, canPick: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                enabled = canPick,
                role = Role.Button,
                onClickLabel = stringResource(R.string.studio_session_change),
                onClick = onPick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (selected == null) {
                Text(text = stringResource(R.string.studio_session_pick), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(text = sessionPeriod(selected), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = sessionDetail(selected, fps),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
        }
        if (canPick) {
            Icon(
                imageVector = AppIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(24.dp),
            )
        }
    }
}

/** Every finished session, newest first; the chosen one carries a check, not only a colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    sessions: List<StudioSession>,
    selectedStart: Long?,
    fps: Int,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.studio_sessions_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            sessions.forEach { session ->
                val start = session.record.startWallMs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClick = { onPick(start) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The middle photo: the first and last of a run are often a dark or a bright edge.
                    session.photoUris.getOrNull(session.photoUris.size / 2)?.let { uri ->
                        Thumbnail(
                            uri = uri,
                            modifier = Modifier
                                .padding(end = 14.dp)
                                .size(56.dp)
                                .clip(MaterialTheme.shapes.small),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = sessionPeriod(session), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = sessionDetail(session, fps),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.75f),
                        )
                    }
                    if (start == selectedStart) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = stringResource(R.string.studio_session_selected),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Progress, result or failure of the job on the shown session. */
@Composable
private fun JobStatus(job: StudioJob?, onCancel: () -> Unit, onRename: (String) -> Unit) {
    val context = LocalContext.current
    when (job) {
        is StudioJob.Running -> {
            Text(
                text = job.percent?.let { stringResource(R.string.studio_job_progress, it) }
                    ?: stringResource(R.string.studio_job_preparing),
                style = MaterialTheme.typography.bodyMedium,
            )
            val percent = job.percent
            if (percent != null) {
                // No stop dot at the track's end: on this card it read as a stray light speck, not as the goal.
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.studio_job_cancel))
            }
        }
        is StudioJob.Done -> {
            val video = job.action.outputMime.startsWith("video/")
            Text(
                text = stringResource(if (video) R.string.studio_job_done_video else R.string.studio_job_done),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (job.skipped > 0) {
                Text(
                    text = pluralStringResource(R.plurals.studio_job_skipped, job.skipped, job.skipped),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val name = job.name
            if (name != null) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
            if (job.renameFailed) {
                Text(
                    text = stringResource(R.string.studio_rename_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            var renaming by rememberSaveable(job.uri) { mutableStateOf(false) }
            // Container buttons, flush with the text above: a text button's own padding pushed "Open"
            // visibly to the right of the line it belongs to.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
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
                if (name != null) {
                    OutlinedButton(onClick = { renaming = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.studio_job_rename))
                    }
                }
            }
            if (renaming && name != null) {
                RenameDialog(
                    current = name,
                    onRename = { typed ->
                        renaming = false
                        onRename(typed)
                    },
                    onDismiss = { renaming = false },
                )
            }
        }
        is StudioJob.Failed -> Text(
            text = stringResource(R.string.studio_job_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        null -> Unit
    }
}

/**
 * The new name for a finished output. Only the part before the extension is edited; the extension is
 * shown beside the field and kept (OutputName), so a picture cannot be renamed into a video.
 */
@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val base = OutputName.base(current)
    // The whole name selected and the keyboard up: renaming is nearly always typing a new name.
    var field by rememberSaveable(current, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(base, selection = TextRange(0, base.length)))
    }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val extension = current.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != current }
    val valid = OutputName.renamed(current, text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.studio_rename_title)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    field = if (v.text.length <= OutputName.MAX_LENGTH) v else v.copy(text = v.text.take(OutputName.MAX_LENGTH))
                },
                label = { Text(stringResource(R.string.studio_rename_name)) },
                suffix = extension?.let { ext -> { Text(".$ext") } },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onRename(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(text) }, enabled = valid) {
                Text(stringResource(R.string.studio_job_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.studio_job_cancel)) }
        },
    )
}

@Composable
private fun sessionPeriod(session: StudioSession): String {
    val context = LocalContext.current
    return DateUtils.formatDateRange(
        context,
        session.record.startWallMs,
        session.record.endWallMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

@Composable
private fun sessionDetail(session: StudioSession, fps: Int): String {
    val r = session.record
    val shots = pluralStringResource(R.plurals.studio_session_shots, r.shots, r.shots)
    val found = session.photos?.let { pluralStringResource(R.plurals.studio_session_found, it, it) }
        ?: stringResource(R.string.studio_session_found_unknown)
    val videos = session.videos?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.studio_session_videos, it, it) }
    val length = session.photos?.takeIf { it > 0 }?.let { n ->
        stringResource(R.string.studio_session_length, OutputPlan.durationMs(n, fps) / 1000.0)
    }
    return listOfNotNull(shots, found, videos, length).joinToString(" · ")
}

private fun StudioSession.actions(): List<StudioAction> = buildList {
    if ((photos ?: 0) > 0) {
        add(StudioAction.VIDEO)
        add(StudioAction.TRAILS)
        add(StudioAction.GROWING_TRAILS)
        add(StudioAction.AVERAGE)
    }
    if ((videos ?: 0) > 0) add(StudioAction.JOIN)
}

private fun StudioAction.description(): Int = when (this) {
    StudioAction.VIDEO -> R.string.studio_action_video_summary
    StudioAction.TRAILS -> R.string.studio_action_trails_summary
    StudioAction.GROWING_TRAILS -> R.string.studio_action_growing_summary
    StudioAction.AVERAGE -> R.string.studio_action_average_summary
    StudioAction.JOIN -> R.string.studio_action_join_summary
}

private fun StudioAction.label(): Int = when (this) {
    StudioAction.VIDEO -> R.string.studio_action_video
    StudioAction.TRAILS -> R.string.studio_action_trails
    StudioAction.GROWING_TRAILS -> R.string.studio_action_growing
    StudioAction.AVERAGE -> R.string.studio_action_average
    StudioAction.JOIN -> R.string.studio_action_join
}
