package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The loaders that feed the screens, against a server that answers like Proxmox VE. */
class DataLoadingTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun clusterLoadsOnlyWhileWatchedAndKeepsDataWhenARefreshFails(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        val cluster = ClusterRepository(repository, refresh, scope)
        delay(200)
        assertEquals(0, fixture.server.requestCount, "nothing is read before a screen watches")

        fixture.enqueue("""{"data":[{"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"status":"running"}]}""")
        fixture.enqueue("""{"data":null}""", code = 500, reason = "Internal error")
        val watcher = scope.launch { cluster.state.collect {} }

        val loaded = withTimeout(TIMEOUT_MS) { cluster.state.first { it is ClusterState.Loaded } }
        assertIs<ClusterState.Loaded>(loaded)
        assertEquals(listOf("qemu/100"), loaded.resources.map { it.id })
        assertEquals(fixture.profile.id, loaded.profileId)

        refresh.request()
        val stale = withTimeout(TIMEOUT_MS) {
            cluster.state.first { (it as? ClusterState.Loaded)?.refreshError != null }
        }
        assertIs<ClusterState.Loaded>(stale)
        assertEquals(loaded.resources, stale.resources)
        assertIs<PveApiException>(stale.refreshError)
        watcher.cancel()
    }

    @Test
    fun accessIsReadForTheSignedInServer(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        val accessRepository = ServerAccessRepository(repository, scope)
        fixture.enqueue("""{"data":{"version":"9.2.1","release":"9.2"}}""")
        fixture.enqueue("""{"data":{"/":{"VM.Audit":1},"/vms/100":{"VM.PowerMgmt":1}}}""")

        val access = withTimeout(TIMEOUT_MS) {
            accessRepository.access.filterNotNull().first { it.permissions != null }
        }

        assertEquals(9, access.version?.major)
        assertTrue(access.allows(Permissions.guestPaths(100, null), Privilege.VM_POWER_MGMT))
        assertFalse(access.allows(Permissions.guestPaths(101, null), Privilege.VM_POWER_MGMT))
    }

    @Test
    fun unknownPermissionsAllowEverything() {
        assertTrue(ServerAccess("any").allows(listOf("/vms/100"), Privilege.VM_POWER_MGMT))
    }

    @Test
    fun actionsReportTheirOutcomeAndAskForARefresh(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        val actions = GuestActions(repository, refresh, scope)
        val guest = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, name = "web")
        fixture.enqueue("""{"data":"UPID:pve1:00001234:00005678:6712F3A1:qmstart:100:root@pam:"}""")
        fixture.enqueue("""{"data":{"status":"stopped","exitstatus":"OK"}}""")

        val event = async(start = CoroutineStart.UNDISPATCHED) { actions.events.first() }
        val refreshed = async(start = CoroutineStart.UNDISPATCHED) { refresh.ticks(intervalMs = 60_000).take(2).toList() }
        actions.run(fixture.profile.id, guest, GuestAction.Start)
        assertEquals(GuestAction.Start, actions.busy.value[BusyGuest(fixture.profile.id, "qemu/100")])

        val outcome = withTimeout(TIMEOUT_MS) { event.await() }
        assertEquals(ActionEvent(GuestAction.Start, "web", succeeded = true), outcome)
        withTimeout(TIMEOUT_MS) { refreshed.await() }
        assertTrue(actions.busy.value.isEmpty())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
