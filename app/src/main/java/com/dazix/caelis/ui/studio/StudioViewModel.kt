package com.dazix.caelis.ui.studio

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.caelis.data.SessionHistoryStore
import com.dazix.caelis.data.studio.FrameSource
import com.dazix.caelis.domain.studio.MediaKind
import com.dazix.caelis.domain.studio.SessionFrameWindow
import com.dazix.caelis.domain.studio.SessionRecord
import com.dazix.caelis.media.StackKind
import com.dazix.caelis.media.StackResult
import com.dazix.caelis.media.StackRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished session and how many of its frames the granted folder holds (null: no folder to look in). */
data class StudioSession(val record: SessionRecord, val photos: Int?)

/** What Studio is building, or built last, for one session (keyed by its start). */
sealed interface StudioJob {
    val sessionStart: Long
    val kind: StackKind

    data class Running(override val sessionStart: Long, override val kind: StackKind, val done: Int, val total: Int) : StudioJob
    data class Done(override val sessionStart: Long, override val kind: StackKind, val uri: Uri, val skipped: Int) : StudioJob
    data class Failed(override val sessionStart: Long, override val kind: StackKind) : StudioJob
}

data class StudioUiState(
    val loading: Boolean = true,
    /** Null when no folder is granted, or the grant was revoked in the system settings. */
    val folderName: String? = null,
    val folderGranted: Boolean = false,
    val sessions: List<StudioSession> = emptyList(),
    /** Listing the folder failed (provider gone, folder deleted): shown, never thrown. */
    val folderError: Boolean = false,
    val job: StudioJob? = null,
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val frames = FrameSource(application)
    private val history = SessionHistoryStore(application)
    private val stacker = StackRunner(application)

    private val state = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = state.asStateFlow()

    private var running: Job? = null

    /** Called on every entry (the view model outlives the screen): a session may have finished, the grant may have been revoked. */
    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            state.update { loaded.copy(job = it.job) }
        }
    }

    fun onFolderPicked(tree: Uri?) {
        if (tree == null) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val granted = runCatching { frames.grant(tree) }
                // The class only: the message carries the folder's URI, which can name where the user was.
                granted.exceptionOrNull()?.let { Log.w(TAG, "folder grant refused: ${it.javaClass.simpleName}") }
                load()
            }
            state.update { loaded.copy(job = it.job) }
        }
    }

    /** One job at a time: decoding two stacks at once would only halve the speed of each and double the memory. */
    fun stack(session: SessionRecord, kind: StackKind) {
        if (running?.isActive == true) return
        running = viewModelScope.launch {
            state.update { it.copy(job = StudioJob.Running(session.startWallMs, kind, 0, 0)) }
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    val tree = frames.treeUri() ?: return@withContext StackResult.NoFrames
                    val picked = SessionFrameWindow.select(frames.list(tree), session.startWallMs, session.endWallMs, MediaKind.IMAGES)
                    stacker.run(
                        frames = picked.map { frames.documentUri(tree, it) },
                        kind = kind,
                        name = outputName(session, kind),
                    ) { done, total ->
                        state.update { it.copy(job = StudioJob.Running(session.startWallMs, kind, done, total)) }
                    }
                }
            }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) {
                state.update { it.copy(job = null) }
                throw error
            }
            if (error != null) Log.w(TAG, "stack failed: ${error.javaClass.simpleName}")
            val job = when (val result = outcome.getOrNull()) {
                is StackResult.Done -> StudioJob.Done(session.startWallMs, kind, result.uri, result.rejected)
                else -> StudioJob.Failed(session.startWallMs, kind)
            }
            state.update { it.copy(job = job) }
        }
    }

    /** Leaving the screen stops the job: the plan is to process only while the user is looking. */
    fun cancel() {
        running?.cancel()
        running = null
        state.update { s -> if (s.job is StudioJob.Running) s.copy(job = null) else s }
    }

    private fun outputName(session: SessionRecord, kind: StackKind): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(session.startWallMs))
        val what = if (kind == StackKind.LIGHTEN) "STARTRAILS" else "AVERAGE"
        return "CAELIS_${what}_$stamp"
    }

    private fun load(): StudioUiState {
        val records = history.all()
        val tree = frames.treeUri()
            ?: return StudioUiState(loading = false, sessions = records.map { StudioSession(it, null) })
        val listed = runCatching { frames.list(tree) }.getOrNull()
        return StudioUiState(
            loading = false,
            folderName = frames.folderName(tree),
            folderGranted = true,
            folderError = listed == null,
            sessions = records.map { r ->
                StudioSession(
                    record = r,
                    photos = listed?.let { SessionFrameWindow.select(it, r.startWallMs, r.endWallMs, MediaKind.IMAGES).size },
                )
            },
        )
    }

    private companion object {
        const val TAG = "CaelisStudio"
    }
}
