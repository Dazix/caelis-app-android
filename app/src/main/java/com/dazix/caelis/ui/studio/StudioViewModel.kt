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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A finished session and how many of its frames the granted folder holds (null: no folder to look in). */
data class StudioSession(val record: SessionRecord, val photos: Int?)

data class StudioUiState(
    val loading: Boolean = true,
    /** Null when no folder is granted, or the grant was revoked in the system settings. */
    val folderName: String? = null,
    val folderGranted: Boolean = false,
    val sessions: List<StudioSession> = emptyList(),
    /** Listing the folder failed (provider gone, folder deleted): shown, never thrown. */
    val folderError: Boolean = false,
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val frames = FrameSource(application)
    private val history = SessionHistoryStore(application)

    private val state = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = state.asStateFlow()

    /** Called on every entry (the view model outlives the screen): a session may have finished, the grant may have been revoked. */
    fun refresh() {
        viewModelScope.launch {
            state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    fun onFolderPicked(tree: Uri?) {
        if (tree == null) return
        viewModelScope.launch {
            state.value = withContext(Dispatchers.IO) {
                val granted = runCatching { frames.grant(tree) }
                // The class only: the message carries the folder's URI, which can name where the user was.
                granted.exceptionOrNull()?.let { Log.w(TAG, "folder grant refused: ${it.javaClass.simpleName}") }
                load()
            }
        }
    }

    private fun load(): StudioUiState {
        val records = history.all()
        val tree = frames.treeUri()
            ?: return StudioUiState(loading = false, sessions = records.map { StudioSession(it, null) })
        val files = runCatching { frames.list(tree) }
        val listed = files.getOrNull()
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
