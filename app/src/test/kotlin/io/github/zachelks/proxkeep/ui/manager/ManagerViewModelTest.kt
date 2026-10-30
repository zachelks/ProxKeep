package io.github.zachelks.proxkeep.ui.manager

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.Product
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.pdm.PdmResource
import io.github.zachelks.proxkeep.core.api.pdm.Remote
import io.github.zachelks.proxkeep.core.api.pdm.RemoteResources
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.datacenter.Collections
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterEntry
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManagerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer(product = Product.Pdm)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    private val web = PdmResource("pve-qemu", "remote/prod/guest/100", name = "web", node = "pve1", vmid = 100, status = "stopped")
    private val dns = PdmResource("pve-lxc", "remote/lab/guest/200", name = "dns", node = "lab1", vmid = 200, status = "running")
    private val node = PdmResource("pve-node", "remote/prod/node/pve1", node = "pve1", status = "online")
    private val store = PdmResource("pbs-datastore", "remote/backup/datastore/main", name = "main")

    private val views = listOf(
        RemoteView("backup", "pbs", null, listOf(store)),
        RemoteView("lab", "pve", null, listOf(dns)),
        RemoteView("prod", "pve", null, listOf(web, node)),
    )

    @Test
    fun resourcesAreFilteredAcrossRemotes() {
        assertEquals(listOf(store, dns, node, web), filterResources(views, "", ResourceFilter.All).map { it.resource })
        assertEquals(listOf(dns, web), filterResources(views, "", ResourceFilter.Guests).map { it.resource })
        assertEquals(listOf(node), filterResources(views, "", ResourceFilter.Nodes).map { it.resource })
        assertEquals(listOf(store), filterResources(views, "", ResourceFilter.Storage).map { it.resource })
        assertEquals(listOf(web), filterResources(views, " WEB ", ResourceFilter.All).map { it.resource }, "names match ignoring case")
        assertEquals(listOf(dns), filterResources(views, "200", ResourceFilter.All).map { it.resource }, "guest IDs match")
        assertEquals(listOf(node, web), filterResources(views, "prod", ResourceFilter.All).map { it.resource }, "a remote's name matches all it has")
    }

    @Test
    fun configuredRemotesNotYetReportedAreListed() {
        val merged = ManagerViewModel.merge(
            listOf(Remote("prod", "pve"), Remote("new", "pbs")),
            listOf(RemoteResources("prod", resources = listOf(web)), RemoteResources("gone", error = "unreachable")),
        )
        assertEquals(listOf("gone", "new", "prod"), merged.map { it.remote })
        assertEquals(listOf(null, "pbs", "pve"), merged.map { it.type })
        assertEquals("unreachable", merged.first().error)
        assertEquals(listOf("prod"), ManagerViewModel.merge(null, listOf(RemoteResources("prod"))).map { it.remote })
    }

    @Test
    fun aGuestActionFollowsTheRemotesTask(): Unit = runBlocking {
        val upid = "prod!UPID:pve1:00001234:0000ABCD:6712F3A1:qmstart:100:root@pam:"
        fixture.route("/remotes", """{"data":[{"id":"prod","type":"pve"}]}""")
        fixture.route(
            "/resources/list",
            """{"data":[{"remote":"prod","resources":[{"type":"pve-qemu","id":"remote/prod/guest/100","vmid":100,"name":"web","node":"pve1","status":"stopped"}]}]}""",
        )
        fixture.route("/pve/remotes/prod/qemu/100/start") { request ->
            requests += "${request.method} start"
            response("""{"data":"$upid"}""")
        }
        fixture.route("/pve/remotes/prod/tasks/$upid/status") {
            requests += "status"
            response("""{"data":{"status":"stopped","exitstatus":"OK"}}""")
        }
        val repository = fixture.signedInRepository(scope)
        val viewModel = ManagerViewModel(repository, RefreshSignal())
        try {
            scope.launch { viewModel.shown.collect {} }
            val remotes = withTimeout(TIMEOUT_MS) { viewModel.remotes.first { it is Loadable.Loaded } }
            val prod = assertIs<Loadable.Loaded<List<RemoteView>>>(remotes).value.single()
            assertEquals("pve", prod.type)
            val shown = withTimeout(TIMEOUT_MS) { viewModel.shown.filterNotNull().first() }

            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.run("prod", shown.single().resource, GuestAction.Start)
            assertEquals(R.string.pdm_action_done, message.await().id)
            assertEquals(listOf("POST start", "status"), requests)
        } finally {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun migrationsAreSchemaForms() {
        val (within, across) = migrationRoutes("prod", web, "within", "across")
        assertEquals("/pve/remotes/prod/qemu/100/migrate", within.path)
        assertEquals("/pve/remotes/{remote}/qemu/{vmid}/migrate", within.schemaPath)
        assertEquals("/pve/remotes/prod/qemu/100/remote-migrate", across.path)
        assertEquals("POST", across.method)
        assertTrue(migrationRoutes("prod", node, "within", "across").isEmpty(), "only guests migrate")
    }

    @Test
    fun everyAdministrationEntryHasItsCollection() {
        MANAGER_GROUPS.flatMap { it.entries }.filterIsInstance<DatacenterEntry.Collection>().forEach { entry ->
            val spec = assertNotNull(Collections.get(entry.specId), entry.specId)
            assertTrue('{' !in spec.list(entry.args(null)), spec.id)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
