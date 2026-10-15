package io.github.zachelks.proxkeep.ui.manager

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.pdm.PdmResource
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.waitWithLooper
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RemoteGuestDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val running = PdmResource("pve-qemu", "remote/prod/guest/100", name = "web", node = "pve1", vmid = 100, status = "running", mem = 1, maxmem = 2)

    @Test
    fun stoppingAsksFirstAndShuttingDownDoesNot() {
        val actions = mutableListOf<GuestAction>()
        compose.setContent { RemoteGuestDialog("prod", running, busy = false, onAction = { actions += it }, onOpenRoute = {}, onDismiss = {}) }
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.action_shutdown))).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(context.getString(R.string.action_shutdown)).performClick()
        assertEquals(listOf(GuestAction.Shutdown), actions)

        compose.onNodeWithText(context.getString(R.string.action_stop)).performScrollTo().performClick()
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.confirm_stop_body))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(GuestAction.Shutdown), actions, "nothing happens before the person confirms")
        compose.onAllNodes(hasText(context.getString(R.string.action_stop)))[1].performClick()
        assertEquals(listOf(GuestAction.Shutdown, GuestAction.Stop), actions)
    }

    @Test
    fun aStoppedGuestStartsAndMigrates() {
        val actions = mutableListOf<GuestAction>()
        val routes = mutableListOf<Route>()
        compose.setContent {
            RemoteGuestDialog("prod", running.copy(status = "stopped"), busy = false, onAction = { actions += it }, onOpenRoute = { routes += it }, onDismiss = {})
        }
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.action_start))).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText(context.getString(R.string.action_stop))).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(context.getString(R.string.action_start)).performClick()
        assertEquals(listOf(GuestAction.Start), actions)

        compose.onNodeWithText(context.getString(R.string.pdm_migrate_across)).performScrollTo().performClick()
        assertEquals("/pve/remotes/prod/qemu/100/remote-migrate", assertIs<Route.SchemaEdit>(routes.single()).path)
    }
}
