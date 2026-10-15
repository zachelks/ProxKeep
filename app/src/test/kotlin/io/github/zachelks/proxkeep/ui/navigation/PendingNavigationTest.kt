package io.github.zachelks.proxkeep.ui.navigation

import io.github.zachelks.proxkeep.ui.TopLevelDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingNavigationTest {

    @Test
    fun requestsWaitUntilTakenOnce() {
        val pending = PendingNavigation()
        pending.requestDestination(TopLevelDestination.Tasks)
        assertEquals(TopLevelDestination.Tasks, pending.takeDestination())
        assertNull(pending.takeDestination())

        pending.requestSearch()
        assertTrue(pending.takeSearch())
        assertFalse(pending.takeSearch())
    }

    @Test
    fun aGuestRequestAlsoAsksForTheGuestList() {
        val pending = PendingNavigation()
        pending.requestGuest(GuestRequest("home", "qemu/100"))
        assertEquals(TopLevelDestination.Guests, pending.destination.value)
        assertEquals(GuestRequest("home", "qemu/100"), pending.takeGuest())
        assertNull(pending.takeGuest())
    }
}
