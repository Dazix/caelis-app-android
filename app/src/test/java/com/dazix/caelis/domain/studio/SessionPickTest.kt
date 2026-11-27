package com.dazix.caelis.domain.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPickTest {

    @Test
    fun aSingleSessionIsSelectedWithoutAPick() {
        assertEquals(5L, SessionPick.selected(listOf(5L), picked = null, jobStart = null))
    }

    @Test
    fun severalSessionsWithoutAPickSelectNone() {
        assertNull(SessionPick.selected(listOf(9L, 5L), picked = null, jobStart = null))
    }

    @Test
    fun thePickIsKeptWhileItIsListed() {
        assertEquals(5L, SessionPick.selected(listOf(9L, 5L), picked = 5L, jobStart = null))
    }

    @Test
    fun aPickNoLongerListedIsDropped() {
        // Fell out of the 20-session history: with several left, the user picks again.
        assertNull(SessionPick.selected(listOf(9L, 7L), picked = 5L, jobStart = null))
        assertEquals(9L, SessionPick.selected(listOf(9L), picked = 5L, jobStart = null))
    }

    @Test
    fun theJobsSessionOutranksThePick() {
        assertEquals(9L, SessionPick.selected(listOf(9L, 5L), picked = 5L, jobStart = 9L))
    }

    @Test
    fun aJobOfAnUnlistedSessionIsIgnored() {
        assertEquals(5L, SessionPick.selected(listOf(9L, 5L), picked = 5L, jobStart = 3L))
    }

    @Test
    fun noSessionsSelectNothing() {
        assertNull(SessionPick.selected(emptyList(), picked = 5L, jobStart = 5L))
    }
}
