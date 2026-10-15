package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.pdm.remoteGuestAction
import io.github.zachelks.proxkeep.core.api.pdm.remoteGuestTemplate
import io.github.zachelks.proxkeep.core.api.pdm.remoteResources
import io.github.zachelks.proxkeep.core.api.pdm.remotes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatacenterManagerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PveClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PveClient(server.url("/api2/json/"), OkHttpClient(), product = Product.Pdm)
            .withCredentials(ApiTokenCredentials("admin@pdm!app", "secret"))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    @Test
    fun tokensUseTheManagersHeader(): Unit = runBlocking {
        enqueue("""{"data":[]}""")
        client.remotes()
        assertEquals("PDMAPIToken=admin@pdm!app:secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun readsRemotesAndTheirResources(): Unit = runBlocking {
        enqueue("""{"data":[{"id":"prod","type":"pve","authid":"root@pam!pdm","nodes":[{"hostname":"10.0.0.1"}]},{"id":"backup","type":"pbs"}]}""")
        enqueue(
            """{"data":[
               {"remote":"prod","resources":[
                 {"type":"pve-node","id":"remote/prod/node/pve1","node":"pve1","status":"online","uptime":100},
                 {"type":"pve-qemu","id":"remote/prod/guest/100","vmid":100,"name":"web","node":"pve1","status":"running","cpu":0.5,"mem":"1024","maxmem":2048,"template":0},
                 {"type":"pve-lxc","id":"remote/prod/guest/200","vmid":"200","name":"dns","node":"pve1","status":"stopped"},
                 {"type":"pve-storage","id":"remote/prod/storage/pve1/local","storage":"local","node":"pve1","status":"available"}]},
               {"remote":"backup","error":"connection refused","resources":[]}]}""",
        )

        val remotes = client.remotes()
        assertEquals("/api2/json/remotes", server.takeRequest().requestUrl!!.encodedPath)
        assertTrue(remotes.first().isVirtualEnvironment)
        assertFalse(remotes.last().isVirtualEnvironment)

        val resources = client.remoteResources()
        assertEquals("/api2/json/resources/list", server.takeRequest().requestUrl!!.encodedPath)
        val prod = resources.first().resources
        assertTrue(prod[0].isNode && prod[0].isOnline)
        assertEquals(ResourceKind.Qemu, prod[1].guestKind)
        assertEquals(1024L, prod[1].mem)
        assertEquals(ResourceKind.Lxc, prod[2].guestKind)
        assertEquals(200, prod[2].vmid)
        assertFalse(prod[3].isGuest)
        assertEquals("local", prod[3].label)
        assertEquals("connection refused", resources.last().error)
    }

    @Test
    fun powerActionsGoThroughTheRemote(): Unit = runBlocking {
        enqueue("""{"data":"prod!UPID:pve1:00001234:0000ABCD:6712F3A1:qmstart:100:root@pam:"}""")
        val upid = client.remoteGuestAction("prod", ResourceKind.Lxc, 200, GuestAction.Shutdown)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api2/json/pve/remotes/prod/lxc/200/shutdown", request.requestUrl!!.encodedPath)
        assertEquals("prod", Upid.parse(upid!!)?.remote)
        assertNull(Upid.parse("UPID:pdm:1:2:3:4:x::root@pam:")?.remote)
        assertEquals("/pve/remotes/{remote}/qemu/{vmid}/remote-migrate", remoteGuestTemplate(ResourceKind.Qemu, "/remote-migrate"))
    }

    @Test
    fun aRemotesTaskIsFollowedThroughTheManager(): Unit = runBlocking {
        val upid = "prod!UPID:pve1:00001234:0000ABCD:6712F3A1:qmstart:100:root@pam:"
        enqueue("""{"data":{"status":"stopped","exitstatus":"OK"}}""")
        enqueue("""{"data":[{"n":1,"t":"started"}]}""")
        assertTrue(client.taskStatus("pve1", upid).succeeded)
        assertEquals(listOf("api2", "json", "pve", "remotes", "prod", "tasks", upid, "status"), server.takeRequest().requestUrl!!.pathSegments)
        client.taskLog("pve1", upid)
        assertEquals("log", server.takeRequest().requestUrl!!.pathSegments.last())
    }

    @Test
    fun theManagersOwnTaskStartedByATokenIsReadOnItsNode(): Unit = runBlocking {
        // The `!` in the token's user ID does not name a remote.
        val upid = "UPID:pdm:00001234:0000ABCD:6712F3A1:remote-add::admin@pdm!app:"
        enqueue("""{"data":{"status":"stopped","exitstatus":"OK"}}""")
        assertTrue(client.taskStatus("localhost", upid).succeeded)
        assertEquals(listOf("api2", "json", "nodes", "localhost", "tasks", upid, "status"), server.takeRequest().requestUrl!!.pathSegments)
        assertNull(Upid.parse(upid)?.remote)
    }

    @Test
    fun theManagersTasksIncludeItsRemotes(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api2/json/nodes/localhost/tasks" -> MockResponse().setBody(
                    """{"data":[{"upid":"UPID:pdm:1:2:3:6712F3A0:refresh::root@pam:","node":"pdm","worker_type":"refresh","starttime":10}]}""",
                )
                "/api2/json/remote-tasks/list" -> MockResponse().setBody(
                    """{"data":[{"upid":"prod!UPID:pve1:1:2:3:4:qmstart:100:root@pam:","node":"pve1","worker_type":"qmstart","worker_id":"100","starttime":20}]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val tasks = client.clusterTasks()
        assertEquals(listOf("qmstart", "refresh"), tasks.map { it.type }, "newest first")
    }

    @Test
    fun anOlderManagerShowsOnlyItsOwnTasks(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api2/json/nodes/localhost/tasks" -> MockResponse().setBody("""{"data":[{"upid":"UPID:pdm:1","node":"pdm","worker_type":"refresh"}]}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"data":null,"message":"not found"}""")
            }
        }
        assertEquals(listOf("refresh"), client.clusterTasks().map { it.type })
    }
}
