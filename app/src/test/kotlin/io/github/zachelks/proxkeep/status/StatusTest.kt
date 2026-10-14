package io.github.zachelks.proxkeep.status

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.alerts.StatusWorker
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.StatusSnapshot
import io.github.zachelks.proxkeep.ui.guests.pushGuestShortcut
import io.github.zachelks.proxkeep.ui.guests.removeGuestShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StatusTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val resources = listOf(
        ClusterResource(id = "node/pve1", type = "node", node = "pve1", status = "online"),
        ClusterResource(id = "node/pve2", type = "node", node = "pve2", status = "offline"),
        ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, status = "running"),
        ClusterResource(id = "lxc/101", type = "lxc", node = "pve1", vmid = 101, status = "stopped"),
        ClusterResource(id = "qemu/9000", type = "qemu", node = "pve1", vmid = 9000, status = "stopped", template = true),
        ClusterResource(id = "storage/pve1/local", type = "storage", node = "pve1", storage = "local"),
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aSnapshotCountsNodesAndGuestsButNotTemplates() {
        val snapshot = StatusSnapshot.of(fixture.profile, resources, 1_000)
        assertEquals(StatusSnapshot(fixture.profile.id, fixture.profile.name, 1, 2, 1, 2, 1_000), snapshot)
        assertFalse(snapshot.allNodesOnline)
    }

    @Test
    fun theWidgetSaysWhatIsWrongOrWhyItShowsNothing() {
        val snapshot = StatusSnapshot.of(fixture.profile, resources, 1_000)
        val lines = describe(context, snapshot, locked = false)
        assertEquals(fixture.profile.name, lines.title)
        assertEquals(
            listOf(
                context.resources.getQuantityString(R.plurals.status_nodes_offline, 2, 1, 2),
                context.resources.getQuantityString(R.plurals.status_guests_running, 2, 1, 2),
            ),
            lines.body,
        )
        assertEquals(listOf(context.getString(R.string.status_locked)), describe(context, snapshot, locked = true).body)
        assertEquals(listOf(context.getString(R.string.status_none)), describe(context, null, locked = false).body)
    }

    @Test
    fun anUnchangedSnapshotIsNotStoredAgainAtOnce(): Unit = runBlocking {
        val surfaces = StatusSurfaces(context)
        assertFalse(surfaces.placed.value)
        surfaces.setWidgetPlaced(true)
        assertTrue(surfaces.placed.value)
        surfaces.setTilePlaced(true)
        surfaces.setWidgetPlaced(false)
        assertTrue(surfaces.placed.value, "the tile still needs it")

        val first = StatusSnapshot.of(fixture.profile, resources, 1_000)
        surfaces.update(first)
        surfaces.update(first.copy(updatedAt = 60_000))
        assertEquals(1_000, surfaces.store.read()?.updatedAt)
        surfaces.update(first.copy(updatedAt = 1_000 + 10 * 60_000))
        assertEquals(601_000, surfaces.store.read()?.updatedAt)
        surfaces.update(first.copy(guestsRunning = 2, updatedAt = 602_000))
        assertEquals(2, surfaces.store.read()?.guestsRunning)
    }

    @Test
    fun theBackgroundCheckReadsTheSignedInServer(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":[{"id":"node/pve1","type":"node","node":"pve1","status":"online"},{"id":"qemu/100","type":"qemu","vmid":100,"node":"pve1","status":"running"}]}""")
        val snapshot = assertNotNull(StatusWorker.snapshot(fixture.signedInRepository(scope)))
        assertEquals(1, snapshot.nodesTotal)
        assertEquals(1, snapshot.guestsRunning)
    }

    @Test
    fun anUnreachableServerHasNoSnapshot(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":null,"message":"down"}""", code = 500)
        assertNull(StatusWorker.snapshot(fixture.signedInRepository(scope)))
    }

    @Test
    fun guestsOpenedRecentlyBecomeShortcutsUntilTheirServerIsRemoved() {
        pushGuestShortcut(context, "home", "Home lab", ClusterResource(id = "qemu/100", type = "qemu", vmid = 100, name = "web"))
        pushGuestShortcut(context, "work", "Work", ClusterResource(id = "lxc/200", type = "lxc", vmid = 200, name = "dns"))
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(setOf("guest:home:qemu/100", "guest:work:lxc/200"), shortcuts.map { it.id }.toSet())
        assertEquals("web", shortcuts.first { it.id == "guest:home:qemu/100" }.shortLabel)

        removeGuestShortcuts(context, "home")
        assertEquals(listOf("guest:work:lxc/200"), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
    }
}
