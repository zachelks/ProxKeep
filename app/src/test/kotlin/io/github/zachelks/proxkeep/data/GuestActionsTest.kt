package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestActionsTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()
    private val guest = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, name = "web")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aFailedTaskReportsTheServersReason(): Unit = runBlocking {
        val actions = GuestActions(fixture.signedInRepository(scope), refresh, scope)
        fixture.route(START_PATH, """{"data":"$UPID"}""")
        fixture.route(TASK_STATUS_PATH, """{"data":{"status":"stopped","exitstatus":"start failed: no space left"}}""")

        val event = runAndAwaitEvent(actions, GuestAction.Start)

        assertEquals(ActionEvent(GuestAction.Start, "web", succeeded = false, detail = "start failed: no space left"), event)
    }

    @Test
    fun aRefusedActionReportsTheServersReason(): Unit = runBlocking {
        val actions = GuestActions(fixture.signedInRepository(scope), refresh, scope)
        fixture.route(START_PATH, """{"data":null,"message":"VM 100 is locked (backup)"}""", code = 500)

        val event = runAndAwaitEvent(actions, GuestAction.Start)

        assertEquals(false, event.succeeded)
        assertEquals("VM 100 is locked (backup)", event.detail)
        assertTrue(actions.busy.value.isEmpty())
    }

    @Test
    fun aGuestRunsOneActionAtATime(): Unit = runBlocking {
        val actions = GuestActions(fixture.signedInRepository(scope), refresh, scope)
        fixture.route(START_PATH, """{"data":"$UPID"}""")
        fixture.route(TASK_STATUS_PATH, """{"data":{"status":"running"}}""")

        actions.run(fixture.profile.id, guest, GuestAction.Start)
        actions.run(fixture.profile.id, guest, GuestAction.Stop)
        withTimeout(TIMEOUT_MS) { while (fixture.requestCount(TASK_STATUS_PATH) == 0) delay(10) }

        assertEquals(mapOf(BusyGuest(fixture.profile.id, guest.id) to GuestAction.Start), actions.busy.value)
        assertEquals(0, fixture.requestCount("/nodes/pve1/qemu/100/status/stop"))
    }

    @Test
    fun theSameGuestIdOnAnotherServerIsNotBusy(): Unit = runBlocking {
        val actions = GuestActions(fixture.signedInRepository(scope), refresh, scope)
        fixture.route(START_PATH, """{"data":"$UPID"}""")
        fixture.route(TASK_STATUS_PATH, """{"data":{"status":"running"}}""")

        actions.run("other-server", guest, GuestAction.Start)
        actions.run(fixture.profile.id, guest, GuestAction.Start)

        assertEquals(setOf("other-server", fixture.profile.id), actions.busy.value.keys.map { it.profileId }.toSet())
    }

    @Test
    fun aGuestWithoutNodeOrIdIsIgnored(): Unit = runBlocking {
        val actions = GuestActions(fixture.signedInRepository(scope), refresh, scope)

        actions.run(fixture.profile.id, guest.copy(node = null), GuestAction.Start)
        actions.run(fixture.profile.id, guest.copy(vmid = null), GuestAction.Start)

        assertTrue(actions.busy.value.isEmpty())
        assertTrue(fixture.requestedPaths.isEmpty())
    }

    @Test
    fun rejectedCredentialsSignOutWithoutAMessage(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        val actions = GuestActions(repository, refresh, scope)
        fixture.route(START_PATH, """{"data":null}""", code = 401, reason = "authentication failure")

        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_000) { actions.events.first() } }
        actions.run(fixture.profile.id, guest, GuestAction.Start)

        assertNull(event.await(), "the sign-in screen explains this, not an action message")
        assertIs<Session.SignedOut>(repository.session.value)
        assertTrue(actions.busy.value.isEmpty())
    }

    private suspend fun runAndAwaitEvent(actions: GuestActions, action: GuestAction): ActionEvent = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) { actions.events.first() }
        actions.run(fixture.profile.id, guest, action)
        withTimeout(TIMEOUT_MS) { event.await() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val UPID = "UPID:pve1:00001234:00005678:6712F3A1:qmstart:100:root@pam:"
        const val START_PATH = "/nodes/pve1/qemu/100/status/start"
        const val TASK_STATUS_PATH = "/nodes/pve1/tasks/$UPID/status"
    }
}
