package io.github.zachelks.proxkeep.ui.guests

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.data.ActionEvent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words for power actions, read from the app's own resources. */
@RunWith(RobolectricTestRunner::class)
class GuestActionTextTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun aSuccessNamesTheGuest() {
        val message = ActionEvent(GuestAction.Start, "web", succeeded = true).message(resources)

        assertEquals(resources.getString(R.string.done_start, "web"), message)
    }

    @Test
    fun aShutdownTheServerGaveUpOnSaysItMayStillHappen() {
        val message = ActionEvent(GuestAction.Shutdown, "web", succeeded = false, detail = "VM quit/powerdown failed - got timeout").message(resources)
        assertEquals(resources.getString(R.string.action_graceful_timed_out, "web"), message)
        // A forceful action that times out is a plain failure.
        val stop = ActionEvent(GuestAction.Stop, "web", succeeded = false, detail = "got timeout").message(resources)
        assertTrue(stop.endsWith("got timeout"))
    }

    @Test
    fun aFailureAddsTheServersReason() {
        val message = ActionEvent(GuestAction.Stop, "web", succeeded = false, detail = "  VM 100 is locked (backup)\n").message(resources)

        assertEquals(resources.getString(R.string.failed_stop, "web") + "\nVM 100 is locked (backup)", message)
    }

    @Test
    fun aBlankReasonIsLeftOut() {
        val message = ActionEvent(GuestAction.Stop, "web", succeeded = false, detail = " ").message(resources)

        assertEquals(resources.getString(R.string.failed_stop, "web"), message)
    }

    @Test
    fun everyActionHasWordsThatNameTheGuest() {
        for (action in GuestAction.entries) {
            assertTrue(resources.getString(action.label).isNotBlank(), "$action label")
            assertTrue(resources.getString(action.inProgressLabel).isNotBlank(), "$action progress")
            assertTrue("web" in ActionEvent(action, "web", succeeded = true).message(resources), "$action done")
            assertTrue("web" in ActionEvent(action, "web", succeeded = false).message(resources), "$action failed")
        }
    }

    @Test
    fun onlyActionsThatInterruptTheGuestAskFirst() {
        for (action in listOf(GuestAction.Start, GuestAction.Resume, GuestAction.Pause)) {
            assertNull(action.confirmTitle, "$action")
        }
        for (action in listOf(GuestAction.Shutdown, GuestAction.Reboot, GuestAction.Stop, GuestAction.Reset)) {
            assertTrue("web" in resources.getString(assertNotNull(action.confirmTitle, "$action"), "web"))
            assertTrue(resources.getString(assertNotNull(action.confirmBody, "$action")).isNotBlank())
        }
    }
}
