package io.github.zachelks.proxkeep.ui.guests

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.GuestActions
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerAccessRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class GuestsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()
    private lateinit var actions: GuestActions
    private lateinit var consoles: ConsoleManager
    private lateinit var viewModel: GuestsViewModel

    private fun start() = runBlocking {
        val repository = fixture.signedInRepository(scope)
        actions = GuestActions(repository, refresh, scope)
        consoles = ConsoleManager(ApplicationProvider.getApplicationContext<Context>(), repository, scope)
        viewModel = GuestsViewModel(
            repository,
            ClusterRepository(repository, refresh, scope),
            ServerAccessRepository(repository, scope),
            actions,
            consoles,
            refresh,
        )
        scope.launch { viewModel.cluster.collect {} }
        scope.launch { viewModel.detail.collect {} }
        scope.launch { viewModel.busyGuests.collect {} }
        withTimeout(TIMEOUT_MS) { viewModel.cluster.first { it is ClusterState.Loaded } }
    }

    private fun serve(config: String = """{"agent":"1","serial0":"socket"}""") {
        fixture.route("/cluster/resources", RESOURCES)
        fixture.route("/version", """{"data":{"version":"9.2.1"}}""")
        fixture.route("/access/permissions", """{"data":{"/":{"VM.Audit":1}}}""")
        fixture.route("$VM/status/current", """{"data":{"status":"running","qmpstatus":"running","lock":"backup"}}""")
        fixture.route("$VM/config", """{"data":$config}""")
        fixture.route("$VM/agent/get-osinfo", """{"data":{"result":{"pretty-name":"Debian GNU/Linux 13 (trixie)"}}}""")
        fixture.route("$CT/status/current", """{"data":{"status":"stopped"}}""")
        fixture.route("$CT/config", """{"data":{"ostype":"debian","hostname":"dns"}}""")
    }

    @AfterTest
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        if (::consoles.isInitialized) consoles.close()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun theSelectedGuestsDetailIsRead(): Unit = runBlocking {
        serve()
        start()

        viewModel.select("qemu/100")
        val detail = withTimeout(TIMEOUT_MS) { viewModel.detail.first { it?.osInfo != null } }!!

        assertEquals("qemu/100", detail.resourceId)
        assertEquals("backup", detail.status?.lock)
        assertEquals("socket", detail.config?.get("serial0"))
        assertEquals("Debian GNU/Linux 13 (trixie)", detail.osInfo?.displayName)
    }

    @Test
    fun theGuestAgentIsAskedOnlyOnce(): Unit = runBlocking {
        serve()
        start()
        viewModel.select("qemu/100")
        withTimeout(TIMEOUT_MS) { viewModel.detail.first { it?.osInfo != null } }

        refresh.request()
        withTimeout(TIMEOUT_MS) { while (fixture.requestCount("$VM/status/current") < 2) delay(10) }
        delay(100)

        assertEquals(1, fixture.requestCount("$VM/agent/get-osinfo"))
    }

    @Test
    fun theGuestAgentIsNotAskedWhenSwitchedOff(): Unit = runBlocking {
        serve(config = """{"agent":"0"}""")
        start()

        viewModel.select("qemu/100")
        val detail = withTimeout(TIMEOUT_MS) { viewModel.detail.first { it?.config != null } }!!

        assertNull(detail.osInfo)
        assertEquals(0, fixture.requestCount("$VM/agent/get-osinfo"))
    }

    @Test
    fun aContainerIsReadWithoutTheGuestAgent(): Unit = runBlocking {
        serve()
        start()

        viewModel.select("lxc/200")
        val detail = withTimeout(TIMEOUT_MS) { viewModel.detail.first { it?.config != null } }!!

        assertEquals("dns", detail.config?.get("hostname"))
        assertEquals(0, fixture.requestedPaths.count { it.endsWith("get-osinfo") })
    }

    @Test
    fun aFailedRefreshKeepsTheDetailAndSaysWhy(): Unit = runBlocking {
        serve()
        start()
        viewModel.select("qemu/100")
        val loaded = withTimeout(TIMEOUT_MS) { viewModel.detail.first { it?.osInfo != null } }!!

        fixture.route("$VM/status/current", """{"data":null}""", code = 500)
        refresh.request()
        val stale = withTimeout(TIMEOUT_MS) { viewModel.detail.first { it?.error != null } }!!

        assertEquals(loaded.status, stale.status)
        assertEquals(loaded.config, stale.config)
        assertEquals(R.string.error_server_response, stale.error?.id)
    }

    @Test
    fun aGuestThatIsNoLongerListedIsNotRead(): Unit = runBlocking {
        serve()
        start()

        viewModel.select("qemu/999")
        delay(200)

        assertEquals(GuestDetail("qemu/999"), viewModel.detail.value)
        assertEquals(0, fixture.requestedPaths.count { "/999/" in it })
    }

    @Test
    fun busyGuestsAreOnlyTheSignedInServers(): Unit = runBlocking {
        serve()
        fixture.route("$VM/status/start", """{"data":"$UPID"}""")
        fixture.route("/nodes/pve1/tasks/$UPID/status", """{"data":{"status":"running"}}""")
        start()

        actions.run("other-server", guest(id = "qemu/300", vmid = 300), GuestAction.Start)
        viewModel.runAction(guest(), GuestAction.Start)
        val busy = withTimeout(TIMEOUT_MS) { viewModel.busyGuests.first { it.isNotEmpty() } }

        assertEquals(mapOf("qemu/100" to GuestAction.Start), busy)
    }

    @Test
    fun aConsoleOpensForTheGuest(): Unit = runBlocking {
        serve()
        start()

        viewModel.openConsole(guest())

        assertEquals("web", consoles.current.value?.title)
        assertEquals(ConsoleTarget.Guest("pve1", ResourceKind.Qemu, 100), consoles.current.value?.target)
    }

    private fun guest(id: String = "qemu/100", vmid: Int = 100) =
        ClusterResource(id = id, type = "qemu", node = "pve1", vmid = vmid, name = "web", status = "running")

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val VM = "/nodes/pve1/qemu/100"
        const val CT = "/nodes/pve1/lxc/200"
        const val UPID = "UPID:pve1:00001234:00005678:6712F3A1:qmstart:100:root@pam:"
        const val RESOURCES = """{"data":[
            {"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"web","status":"running"},
            {"id":"lxc/200","type":"lxc","node":"pve1","vmid":200,"name":"dns","status":"stopped"},
            {"id":"node/pve1","type":"node","node":"pve1","status":"online"}]}"""
    }
}
