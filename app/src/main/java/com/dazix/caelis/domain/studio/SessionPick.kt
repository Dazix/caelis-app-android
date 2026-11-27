package com.dazix.caelis.domain.studio

/**
 * Which finished session Studio works on, by its start. The session of a job wins (its progress or
 * result must stay in view), then the one the user picked while it is still listed, and with a
 * single session that one: there is nothing to choose. With several and no pick, none, so no action
 * runs on a session the user did not mean.
 */
object SessionPick {

    fun selected(starts: List<Long>, picked: Long?, jobStart: Long?): Long? = when {
        jobStart != null && jobStart in starts -> jobStart
        picked != null && picked in starts -> picked
        starts.size == 1 -> starts.single()
        else -> null
    }
}
