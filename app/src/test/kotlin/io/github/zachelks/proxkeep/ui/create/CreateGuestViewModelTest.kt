package io.github.zachelks.proxkeep.ui.create

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateGuestViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val posts = CopyOnWriteArrayList<String>()
    private lateinit var viewModel: CreateGuestViewModel
    private lateinit var lifecycle: GuestLifecycle

    private fun start(kind: ResourceKind) = runBlocking {
        fixture.route("/cluster/resources", RESOURCES)
        fixture.route("/cluster/nextid", """{"data":"102"}""")
        fixture.route("/nodes/pve1/storage", """{"data":[{"storage":"local","content":"iso,vztmpl,backup","active":1},{"storage":"local-lvm","content":"images,rootdir","active":1,"avail":107374182400}]}""")
        fixture.route("/nodes/pve2/storage", """{"data":[{"storage":"ceph","content":"images,rootdir","active":1}]}""")
        fixture.route("/nodes/pve1/storage/local/content") { request ->
            val content = request.requestUrl?.queryParameter("content")
            if (content == "iso") response("""{"data":[{"volid":"local:iso/debian-13.1.0-amd64-netinst.iso","content":"iso"}]}""")
            else response("""{"data":[{"volid":"local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst","content":"vztmpl"}]}""")
        }
        fixture.route("/nodes/pve1/network", """{"data":[{"iface":"vmbr0","type":"bridge"},{"iface":"vmbr1","type":"bridge"}]}""")
        fixture.route("/nodes/pve2/network", """{"data":[{"iface":"vmbr9","type":"bridge"}]}""")
        for (type in listOf("qemu", "lxc")) {
            fixture.route("/nodes/pve1/$type") { request ->
                posts += request.body.readUtf8()
                response("""{"data":"UPID:pve1:1:2:3:create:102:root@pam:"}""")
            }
        }
        fixture.route("/nodes/pve1/tasks/UPID:pve1:1:2:3:create:102:root@pam:/status", """{"data":{"status":"stopped","exitstatus":"OK"}}""")
        val repository = fixture.signedInRepository(scope)
        val refresh = RefreshSignal()
        lifecycle = GuestLifecycle(repository, refresh, scope)
        viewModel = CreateGuestViewModel(repository, ClusterRepository(repository, refresh, scope), lifecycle, kind)
        scope.launch { viewModel.cluster.collect {} }
        withTimeout(TIMEOUT_MS) { viewModel.node.first { it is Loadable.Loaded } }
    }

    @AfterTest
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun theWizardStartsWithAFreeIdTheFirstNodeAndItsChoices() {
        start(ResourceKind.Qemu)
        val cluster = viewModel.cluster.value!!
        assertEquals(listOf("pve1", "pve2"), cluster.nodes, "offline nodes are left out")
        assertEquals(setOf(100, 200), cluster.takenIds)
        val draft = viewModel.vm.value
        assertEquals("pve1", draft.node)
        assertEquals("local-lvm", draft.storage)
        assertEquals("vmbr0", draft.bridge)
        runBlocking { withTimeout(TIMEOUT_MS) { viewModel.vm.first { it.vmid == "102" } } }
        val choices = (viewModel.node.value as Loadable.Loaded).value
        assertEquals(listOf("local:iso/debian-13.1.0-amd64-netinst.iso"), choices.isos.map { it.volid })
        assertEquals(listOf("vmbr0", "vmbr1"), choices.bridges)
    }

    @Test
    fun anotherNodeBringsItsOwnStorageAndBridge(): Unit = runBlocking {
        start(ResourceKind.Qemu)
        viewModel.setNode("pve2")
        withTimeout(TIMEOUT_MS) { viewModel.vm.first { it.storage == "ceph" } }
        assertEquals("vmbr9", viewModel.vm.value.bridge)
    }

    @Test
    fun creatingAVirtualMachineRunsAsALifecycleOperation(): Unit = runBlocking {
        start(ResourceKind.Qemu)
        withTimeout(TIMEOUT_MS) { viewModel.vm.first { it.vmid == "102" } }
        viewModel.updateVm { it.copy(name = "web") }
        val event = async { withTimeout(TIMEOUT_MS) { lifecycle.events.first() } }

        assertTrue(viewModel.create())
        val outcome = event.await()
        assertEquals(LifecycleOperation.Create, outcome.operation)
        assertEquals("web", outcome.guestName)
        assertTrue(outcome.succeeded)
        val body = posts.single()
        assertTrue("vmid=102" in body)
        assertTrue("name=web" in body)
    }

    @Test
    fun aContainerIsCreatedFromTheFirstTemplate(): Unit = runBlocking {
        start(ResourceKind.Lxc)
        withTimeout(TIMEOUT_MS) { viewModel.ct.first { it.vmid == "102" } }
        assertEquals("local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst", viewModel.ct.value.template)
        assertFalse(viewModel.create(), "a password or key is still missing")

        viewModel.updateCt { it.copy(hostname = "proxy", password = "secret1", confirmPassword = "secret1") }
        val event = async { withTimeout(TIMEOUT_MS) { lifecycle.events.first() } }
        assertTrue(viewModel.create())
        assertTrue(event.await().succeeded)
        assertTrue("ostemplate=local%3Avztmpl%2Fdebian-13-standard_13.1-1_amd64.tar.zst" in posts.single())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val RESOURCES = """{"data":[
            {"id":"node/pve1","type":"node","node":"pve1","status":"online"},
            {"id":"node/pve2","type":"node","node":"pve2","status":"online"},
            {"id":"node/pve3","type":"node","node":"pve3","status":"offline"},
            {"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"db","status":"running"},
            {"id":"lxc/200","type":"lxc","node":"pve1","vmid":200,"name":"dns","status":"running"}]}"""
    }
}
