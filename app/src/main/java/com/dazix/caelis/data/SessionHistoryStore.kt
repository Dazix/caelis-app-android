package com.dazix.caelis.data

import android.content.Context
import androidx.core.content.edit
import com.dazix.caelis.domain.studio.SessionHistory
import com.dazix.caelis.domain.studio.SessionHistoryCodec
import com.dazix.caelis.domain.studio.SessionRecord

/**
 * The last [SessionHistory.MAX] finished time-lapses, for Studio to find their frames by time.
 * Times and a package name only, no position, so it may go to the cloud backup like the settings.
 * `commit`, not `apply`: the engine writes it on stop, and the process may be reclaimed right after.
 */
class SessionHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("session_history", Context.MODE_PRIVATE)

    fun all(): List<SessionRecord> = SessionHistoryCodec.decode(prefs.getString(KEY, null))

    fun add(record: SessionRecord) {
        prefs.edit(commit = true) { putString(KEY, SessionHistoryCodec.encode(SessionHistory.add(all(), record))) }
    }

    private companion object {
        const val KEY = "records"
    }
}
