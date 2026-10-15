package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
    fun clusterNeverShowsOneServersDataUnderAnother(): Unit = runBlocking {
        PinnedServer("second").use { second ->
            val repository = fixture.signedInRepository(scope)
            val cluster = ClusterRepository(repository, refresh, scope)
            fixture.route("/cluster/resources", """{"data":[{"id":"qemu/100","type":"qemu","node":"pve1","vmid":100}]}""")
            second.route("/cluster/resources", """{"data":[{"id":"qemu/200","type":"qemu","node":"pve2","vmid":200}]}""")
            val seen = CopyOnWriteArrayList<ClusterState>()
            val watcher = scope.launch { cluster.state.collect { seen += it } }
            withTimeout(TIMEOUT_MS) { cluster.state.first { it is ClusterState.Loaded } }

            repository.saveAndActivate(second.profile, ApiTokenCredentials(second.profile.principal, "secret"))
            // The watcher records states on its own thread, so wait until it has the second server's too.
            withTimeout(TIMEOUT_MS) {
                while (seen.none { (it as? ClusterState.Loaded)?.profileId == second.profile.id }) delay(10)
            }
            watcher.cancel()

            val lastFirst = seen.indexOfLast { (it as? ClusterState.Loaded)?.profileId == fixture.profile.id }
            val firstSecond = seen.indexOfFirst { (it as? ClusterState.Loaded)?.profileId == second.profile.id }
            assertTrue(lastFirst < firstSecond)
            assertTrue(seen.subList(lastFirst, firstSecond).contains(ClusterState.Loading), "the switch shows loading, not the old list")
            assertEquals(listOf("qemu/200"), (seen[firstSecond] as ClusterState.Loaded).resources.map { it.id })
        }
    }

    @Test
    fun aFirstLoadThatFailsIsReported(): Unit = runBlocking {
        val cluster = ClusterRepository(fixture.signedInRepository(scope), refresh, scope)
        fixture.route("/cluster/resources", """{"data":null}""", code = 500, reason = "Internal error")

        val failed = withTimeout(TIMEOUT_MS) { cluster.state.first { it is ClusterState.Failed } }

        assertIs<PveApiException>(assertIs<ClusterState.Failed>(failed).error)
    }

    @Test
    fun clusterWaitsForASignedInServer(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        repository.signOut()
        val cluster = ClusterRepository(repository, refresh, scope)
        val watcher = scope.launch { cluster.state.collect {} }

        delay(200)

        assertEquals(ClusterState.Loading, cluster.state.value)
        assertTrue(fixture.requestedPaths.isEmpty())
        watcher.cancel()
    }

    @Test
    fun accessKeepsWhatItReadBeforeWhenAPartFails() = runTest {
        val repository = fixture.signedInRepository(backgroundScope)
        val accessRepository = ServerAccessRepository(repository, backgroundScope)
        val versionReads = AtomicInteger()
        val permissionReads = AtomicInteger()
        fixture.route("/version") {
            if (versionReads.getAndIncrement() == 0) response("""{"data":{"version":"9.2.1"}}""") else response("{}", 500, "Internal error")
        }
        fixture.route("/access/permissions") {
            if (permissionReads.getAndIncrement() == 0) {
                response("""{"data":{"/":{"VM.Audit":1}}}""")
            } else {
                response("""{"data":{"/":{"VM.Audit":1,"VM.PowerMgmt":1}}}""")
            }
        }
        backgroundScope.launch { accessRepository.access.collect {} }

        val first = accessRepository.access.filterNotNull().first { it.permissions != null }
        // The next read is five minutes later, which the test's clock skips.
        val second = accessRepository.access.filterNotNull().first { it.allows(listOf("/"), Privilege.VM_POWER_MGMT) }

        assertFalse(first.allows(listOf("/"), Privilege.VM_POWER_MGMT))
        assertEquals("9.2.1", second.version?.version, "the version that could not be read again is kept")
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
