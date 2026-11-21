package com.dazix.caelis.ui.studio

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.caelis.data.SessionHistoryStore
import com.dazix.caelis.data.studio.FrameSource
import com.dazix.caelis.data.studio.StudioSettingsStore
import com.dazix.caelis.domain.studio.MediaKind
import com.dazix.caelis.domain.studio.StudioOptions
import com.dazix.caelis.domain.studio.SessionFrameWindow
import com.dazix.caelis.domain.studio.SessionRecord
import com.dazix.caelis.media.StackKind
import com.dazix.caelis.media.StackResult
import com.dazix.caelis.media.StackRunner
import com.dazix.caelis.media.TimelapseOptions
import com.dazix.caelis.media.VideoEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A finished session and how many of its photos and videos the granted folder holds (null: no
 * folder to look in).
 */
data class StudioSession(val record: SessionRecord, val photos: Int?, val videos: Int?)

enum class StudioAction(val outputMime: String) {
    VIDEO("video/mp4"),
    TRAILS("image/jpeg"),
    GROWING_TRAILS("video/mp4"),
    AVERAGE("image/jpeg"),
    JOIN("video/mp4"),
}

/** What Studio is building, or built last, for one session (keyed by its start). */
sealed interface StudioJob {
    val sessionStart: Long
    val action: StudioAction

    /** [percent] null: not measurable yet (reading the frames before the export starts). */
    data class Running(override val sessionStart: Long, override val action: StudioAction, val percent: Int?) : StudioJob
    data class Done(override val sessionStart: Long, override val action: StudioAction, val uri: Uri, val skipped: Int) : StudioJob
    data class Failed(override val sessionStart: Long, override val action: StudioAction) : StudioJob
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
    private val encoder = VideoEncoder(application)

    private val settings = StudioSettingsStore(application)

    private val state = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = state.asStateFlow()

    private val optionsState = MutableStateFlow(settings.load())
    val options: StateFlow<StudioOptions> = optionsState.asStateFlow()

    fun updateOptions(transform: (StudioOptions) -> StudioOptions) {
        val updated = transform(optionsState.value).sanitized()
        optionsState.value = updated
        settings.save(updated)
    }

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

    /** One job at a time: two encodes at once would only halve the speed of each and double the memory. */
    fun start(session: SessionRecord, action: StudioAction) {
        if (running?.isActive == true) return
        val key = session.startWallMs
        fun progress(percent: Int?) = state.update { it.copy(job = StudioJob.Running(key, action, percent)) }
        running = viewModelScope.launch {
            progress(null)
            val outcome = runCatching { withContext(Dispatchers.Default) { perform(session, action, ::progress) } }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) {
                state.update { it.copy(job = null) }
                throw error
            }
            // The class only: an export exception's message can carry the file's URI.
            if (error != null) Log.w(TAG, "$action failed: ${error.javaClass.simpleName}")
            val job = outcome.getOrNull()?.let { (uri, skipped) -> StudioJob.Done(key, action, uri, skipped) }
                ?: StudioJob.Failed(key, action)
            state.update { it.copy(job = job) }
        }
    }

    /** The output and how many inputs were left out, or null when there was nothing to build from. */
    private suspend fun perform(session: SessionRecord, action: StudioAction, progress: (Int?) -> Unit): Pair<Uri, Int>? {
        val tree = frames.treeUri() ?: return null
        val listed = frames.list(tree)
        val kind = if (action == StudioAction.JOIN) MediaKind.VIDEOS else MediaKind.IMAGES
        val picked = SessionFrameWindow.select(listed, session.startWallMs, session.endWallMs, kind)
            .map { frames.documentUri(tree, it) }
        if (picked.isEmpty()) return null
        val name = outputName(session, action)
        // Read once at the start: changing an option during a run applies to the next one.
        val chosen = optionsState.value
        val video = TimelapseOptions(chosen.fps, chosen.resolution, chosen.aspect, chosen.deflicker)
        val percent = { done: Int, total: Int -> progress(if (total > 0) done * 100 / total else null) }
        return when (action) {
            StudioAction.TRAILS, StudioAction.AVERAGE -> {
                val kindOf = if (action == StudioAction.TRAILS) StackKind.LIGHTEN else StackKind.MEAN
                when (val r = stacker.run(picked, kindOf, name, percent)) {
                    is StackResult.Done -> r.uri to r.rejected
                    StackResult.NoFrames -> null
                }
            }
            StudioAction.VIDEO -> encoder.timelapse(picked, video, name) { progress(it) } to 0
            StudioAction.JOIN -> encoder.join(picked, removeAudio = chosen.joinWithoutAudio, name) { progress(it) } to 0
            StudioAction.GROWING_TRAILS -> {
                val dir = File(getApplication<Application>().cacheDir, "studio_snapshots").apply {
                    deleteRecursively()
                    mkdirs()
                }
                try {
                    // Half of the work is the stack, half the encode.
                    val snaps = stacker.growingSnapshots(
                        picked, GROWING_EVERY, chosen.resolution.longEdge, chosen.resolution.shortEdge, dir,
                    ) { done, total -> progress(done * 50 / total) } ?: return null
                    if (snaps.isEmpty()) return null
                    encoder.timelapse(
                        snaps.map(Uri::fromFile),
                        // A growing trail only ever brightens; deflicker would fight exactly that.
                        video.copy(deflicker = false),
                        name,
                    ) { progress(50 + it / 2) } to 0
                } finally {
                    dir.deleteRecursively()
                }
            }
        }
    }

    /** Leaving the screen stops the job: the plan is to process only while the user is looking. */
    fun cancel() {
        running?.cancel()
        running = null
        state.update { s -> if (s.job is StudioJob.Running) s.copy(job = null) else s }
    }

    private fun outputName(session: SessionRecord, action: StudioAction): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(session.startWallMs))
        return "CAELIS_${action.name}_$stamp"
    }

    private fun load(): StudioUiState {
        val records = history.all()
        val tree = frames.treeUri()
            ?: return StudioUiState(loading = false, sessions = records.map { StudioSession(it, null, null) })
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
                    videos = listed?.let { SessionFrameWindow.select(it, r.startWallMs, r.endWallMs, MediaKind.VIDEOS).size },
                )
            },
        )
    }

    private companion object {
        const val TAG = "CaelisStudio"

        /** A growing star trail adds one video frame per this many photos. */
        const val GROWING_EVERY = 1
    }
}
