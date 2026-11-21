package com.dazix.caelis.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation table and the breakpoint that decides which of its two forms is shown.
 *
 * Both are the kind of thing that silently rots: a screen added to [Screen] without a destination
 * would simply never appear in the navigation, and a destination pointing at a screen twice would
 * light up two items at once.
 */
class NavigationDestinationTest {

    @Test
    fun `every destination points at a distinct screen`() {
        val screens = NavigationDestination.entries.map { it.screen }

        assertEquals(screens.size, screens.distinct().size)
    }

    @Test
    fun `navigation covers top-level destinations and sub-screens are reachable from home`() {
        val covered = NavigationDestination.entries.map { it.screen }.toSet()
        val uncovered = Screen.entries.filterNot { it in covered }

        // Home, Sun AR, Night Sky AR and Settings are top-level destinations. All other screens
        // (setup, ramp, advanced, onboarding, app picker, offline terrain, recorded camera modes) are
        // sub-screens and leave by Back.
        assertEquals(
            listOf(
                Screen.SETUP, Screen.RAMP, Screen.ADVANCED, Screen.ONBOARDING, Screen.APP_PICKER, Screen.OFFLINE_TERRAIN,
                Screen.CUSTOM_MODES, Screen.CUSTOM_MODE_EDIT,
            ),
            uncovered,
        )
    }

    @Test
    fun `sub-screens are not standalone navigation destinations`() {
        assertNull(NavigationDestination.forScreen(Screen.SETUP))
        assertNull(NavigationDestination.forScreen(Screen.RAMP))
        assertNull(NavigationDestination.forScreen(Screen.ADVANCED))
        assertNull(NavigationDestination.forScreen(Screen.ONBOARDING))
        assertNull(NavigationDestination.forScreen(Screen.APP_PICKER))
        assertNull(NavigationDestination.forScreen(Screen.OFFLINE_TERRAIN))
    }

    @Test
    fun `each destination screen resolves back to its own destination`() {
        NavigationDestination.entries.forEach { destination ->
            assertEquals(destination, NavigationDestination.forScreen(destination.screen))
        }
    }

    @Test
    fun `every destination has a label and an icon`() {
        NavigationDestination.entries.forEach { destination ->
            assertTrue("label of $destination", destination.labelRes != 0)
            assertNotNull("icon of $destination", destination.icon)
        }
    }

    @Test
    fun `a phone upright gets the bottom bar and the same phone on its side gets the rail`() {
        // A Pixel 7 Pro: 412dp wide upright, 892dp on its side.
        assertFalse(wantsNavigationRail(412.dp))
        assertTrue(wantsNavigationRail(892.dp))
    }

    @Test
    fun `the breakpoint itself already counts as wide`() {
        assertTrue(wantsNavigationRail(RailBreakpoint))
        assertFalse(wantsNavigationRail(RailBreakpoint - 1.dp))
    }
}
