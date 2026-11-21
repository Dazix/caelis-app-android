package com.dazix.caelis.ui.studio

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
                SessionRow(session)
            }
        }
    }
}

@Composable
private fun SessionRow(session: StudioSession) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(text = period, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "$shots · $found",
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
    }
}
