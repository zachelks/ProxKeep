package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestLifecycleTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guest = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, name = "web")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    private suspend fun lifecycle() = GuestLifecycle(fixture.signedInRepository(scope), RefreshSignal(), scope)

    private suspend fun runAndAwait(lifecycle: GuestLifecycle, operation: LifecycleOperation, start: suspend () -> String?): LifecycleEvent =
        coroutineScope {
            val event = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.events.first() }
            assertTrue(lifecycle.run(fixture.profile.id, guest, operation) { _, _ -> start() })
            withTimeout(TIMEOUT_MS) { event.await() }
        }

    @Test
    fun anOperationIsFollowedToTheEndOfItsTask(): Unit = runBlocking {
        val lifecycle = lifecycle()
        // The task ID names pve2, where a migration's task would run, and that is where it is followed.
        fixture.route("/nodes/pve2/tasks/$UPID/status", """{"data":{"status":"stopped","exitstatus":"OK"}}""")

        val event = runAndAwait(lifecycle, LifecycleOperation.Migrate) { UPID }

        assertEquals(LifecycleEvent(LifecycleOperation.Migrate, "web", succeeded = true), event)
        withTimeout(TIMEOUT_MS) { lifecycle.busy.first { it.isEmpty() } }
    }

    @Test
    fun aBackupIsRestoredAsANewGuest(): Unit = runBlocking {
        val lifecycle = lifecycle()
        var form = ""
        fixture.route("/nodes/pve1/qemu") { request ->
            form = request.body.readUtf8()
            response("""{"data":"UPID:pve1:1:2:3:qmrestore:150:root@pam:"}""")
        }
        fixture.route("/nodes/pve1/tasks/UPID:pve1:1:2:3:qmrestore:150:root@pam:/status", """{"data":{"status":"stopped","exitstatus":"OK"}}""")
        val event = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.events.first() }

        assertTrue(lifecycle.restoreAsNew(fixture.profile.id, "pve1", ResourceKind.Qemu, "pbs:backup/vm/100/x", 150, null, unique = true, start = false))

        assertEquals(LifecycleEvent(LifecycleOperation.RestoreAsNew, "150", succeeded = true), withTimeout(TIMEOUT_MS) { event.await() })
        assertTrue("vmid=150" in form && "unique=1" in form && "force" !in form, form)
    }

    @Test
    fun anOperationTheServerFinishedAtOnceSucceeds(): Unit = runBlocking {
        val event = runAndAwait(lifecycle(), LifecycleOperation.ConvertToTemplate) { null }

        assertEquals(LifecycleEvent(LifecycleOperation.ConvertToTemplate, "web", succeeded = true), event)
    }

    @Test
    fun aFailedTaskReportsWhy(): Unit = runBlocking {
        val lifecycle = lifecycle()
        fixture.route("/nodes/pve2/tasks/$UPID/status", """{"data":{"status":"stopped","exitstatus":"backup failed: storage full"}}""")

        val event = runAndAwait(lifecycle, LifecycleOperation.Backup) { UPID }

        assertEquals("backup failed: storage full", event.detail)
        assertFalse(event.succeeded)
    }

    @Test
    fun aGuestRunsOneOperationAtATime(): Unit = runBlocking {
        val lifecycle = lifecycle()
        fixture.route("/nodes/pve2/tasks/$UPID/status", """{"data":{"status":"running"}}""")

        assertTrue(lifecycle.run(fixture.profile.id, guest, LifecycleOperation.Clone) { _, _ -> UPID })
        assertFalse(lifecycle.run(fixture.profile.id, guest, LifecycleOperation.Delete) { _, _ -> UPID })

        assertEquals(mapOf(BusyGuest(fixture.profile.id, guest.id) to LifecycleOperation.Clone), lifecycle.busy.value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val UPID = "UPID:pve2:00001234:00005678:6712F3A1:qmigrate:100:root@pam:"
    }
}
