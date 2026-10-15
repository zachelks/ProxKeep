package io.github.zachelks.proxkeep.ui.lifecycle

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.core.api.model.BackupCompression
import io.github.zachelks.proxkeep.core.api.model.BackupMode
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LifecycleViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()
    private val forms = CopyOnWriteArrayList<String>()
    private lateinit var viewModel: LifecycleViewModel

    private fun start(resourceId: String = "qemu/100") = runBlocking {
        fixture.route("/cluster/resources", RESOURCES)
        val repository = fixture.signedInRepository(scope)
        viewModel = LifecycleViewModel(repository, ClusterRepository(repository, refresh, scope), GuestLifecycle(repository, refresh, scope), refresh, resourceId)
        scope.launch { viewModel.guest.collect {} }
        withTimeout(TIMEOUT_MS) { viewModel.guest.filterNotNull().first() }
    }

    @AfterTest
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    private fun recordPost(path: String) {
        fixture.route(path) { request ->
            forms += "$path ${request.body.readUtf8()}"
            response("""{"data":"$UPID"}""")
        }
    }

    @Test
    fun theGuestsSnapshotsAreListed(): Unit = runBlocking {
        fixture.route("$VM/snapshot", """{"data":[{"name":"nightly","snaptime":1760400000},{"name":"current","parent":"nightly"}]}""")
        start()
        scope.launch { viewModel.snapshots.collect {} }

        val loaded = withTimeout(TIMEOUT_MS) { viewModel.snapshots.first { it is Loadable.Loaded } }

        assertEquals(listOf("nightly", "current"), assertIs<Loadable.Loaded<*>>(loaded).value.let { (it as List<*>).map { s -> (s as io.github.zachelks.proxkeep.core.api.model.Snapshot).name } })
    }

    @Test
    fun backupsFromEveryStorageAreListedNewestFirstAndADownStorageIsSkipped(): Unit = runBlocking {
        fixture.route("/nodes/pve1/storage", """{"data":[{"storage":"pbs","content":"backup","active":1},{"storage":"nfs","content":"backup","active":1},{"storage":"old","content":"backup","active":0}]}""")
        fixture.route("/nodes/pve1/storage/pbs/content", """{"data":[{"volid":"pbs:backup/vm/100/a","ctime":100,"vmid":100}]}""")
        fixture.route("/nodes/pve1/storage/nfs/content", """{"data":null,"message":"storage is not online"}""", code = 500)
        start()
        scope.launch { viewModel.backups.collect {} }

        val loaded = withTimeout(TIMEOUT_MS) { viewModel.backups.first { it is Loadable.Loaded } }

        val overview = assertIs<Loadable.Loaded<BackupOverview>>(loaded).value
        assertEquals(listOf("nfs", "pbs"), overview.storages.map { it.storage }, "an inactive storage is not offered")
        assertEquals(listOf("pbs:backup/vm/100/a"), overview.backups.map { it.volume.volid })
    }

    @Test
    fun operationsCallTheServerAndMarkTheGuestBusy(): Unit = runBlocking {
        recordPost("$VM/snapshot")
        recordPost("/nodes/pve1/vzdump")
        fixture.route("/nodes/pve1/tasks/$UPID/status", """{"data":{"status":"running"}}""")
        start()
        scope.launch { viewModel.busy.collect {} }

        assertTrue(viewModel.takeSnapshot("nightly", "", includeMemory = false))
        withTimeout(TIMEOUT_MS) { viewModel.busy.first { it == LifecycleOperation.TakeSnapshot } }

        assertEquals(false, viewModel.backUp("pbs", BackupMode.Stop, BackupCompression.Zstd, null), "one operation at a time")
        withTimeout(TIMEOUT_MS) { while (forms.isEmpty()) delay(10) }
        assertEquals(listOf("$VM/snapshot snapname=nightly"), forms)
    }

    @Test
    fun migrationOffersOnlyTheNodesTheServerAllows(): Unit = runBlocking {
        fixture.route("$VM/migrate", """{"data":{"allowed_nodes":["pve2"],"local_disks":[{"volid":"local:100/vm-100-disk-0.qcow2","drivename":"scsi0"}],"local_resources":[]}}""")
        start()

        viewModel.loadMigration()
        val options = withTimeout(TIMEOUT_MS) { viewModel.migration.first { it is Loadable.Loaded } }

        assertEquals(MigrationOptions(listOf("pve2"), listOf("scsi0"), emptyList()), assertIs<Loadable.Loaded<MigrationOptions>>(options).value)
    }

    @Test
    fun aContainerCanMoveToAnyOtherOnlineNode(): Unit = runBlocking {
        start(resourceId = "lxc/200")

        viewModel.loadMigration()
        val options = withTimeout(TIMEOUT_MS) { viewModel.migration.first { it is Loadable.Loaded } }

        assertEquals(listOf("pve2", "pve3"), assertIs<Loadable.Loaded<MigrationOptions>>(options).value.targets)
    }

    @Test
    fun aCloneStartsWithAFreeIdAndTheStoragesForDisks(): Unit = runBlocking {
        fixture.route("/cluster/nextid", """{"data":"105"}""")
        fixture.route("/nodes/pve1/storage", """{"data":[{"storage":"local-lvm","content":"images,rootdir"},{"storage":"local","content":"iso,backup"},{"storage":"ceph","content":"images"}]}""")
        start()

        viewModel.loadClone()
        val options = withTimeout(TIMEOUT_MS) { viewModel.clone.first { it is Loadable.Loaded } }

        assertEquals(CloneOptions(105, listOf("pve1", "pve2", "pve3"), listOf("ceph", "local-lvm")), assertIs<Loadable.Loaded<CloneOptions>>(options).value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val VM = "/nodes/pve1/qemu/100"
        const val UPID = "UPID:pve1:00001234:00005678:6712F3A1:qmsnapshot:100:root@pam:"
        const val RESOURCES = """{"data":[
            {"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"web","status":"running"},
            {"id":"lxc/200","type":"lxc","node":"pve1","vmid":200,"name":"dns","status":"stopped"},
            {"id":"node/pve1","type":"node","node":"pve1","status":"online"},
            {"id":"node/pve2","type":"node","node":"pve2","status":"online"},
            {"id":"node/pve3","type":"node","node":"pve3","status":"online"},
            {"id":"node/pve4","type":"node","node":"pve4","status":"offline"}]}"""
    }
}
