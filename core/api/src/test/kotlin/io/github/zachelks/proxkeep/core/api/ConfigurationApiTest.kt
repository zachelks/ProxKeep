package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.schema.ApiSchema
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationApiTest {

    private val server = MockWebServer().apply { start() }
    private val client = PveClient(server.url("/api2/json/"), OkHttpClient())
        .withCredentials(TicketCredentials("root@pam", "PVE:root@pam:X::Y", "csrf"))

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }

    @Test
    fun theConfigurationCarriesItsDigest(): Unit = runBlocking {
        enqueue("""{"data":{"cores":2,"memory":"4096","digest":"abc123","net0":"virtio=BC:24:11:2A:3B:4C,bridge=vmbr0"}}""")
        val config = client.guestConfig("pve1", ResourceKind.Qemu, 100)
        assertEquals("abc123", config.digest)
        assertEquals("2", config["cores"])
        assertNull(config["digest"])
    }

    @Test
    fun virtualMachinesChangeAsATask(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:qmconfig:100:root@pam:"}""")
        val upid = client.updateConfig("pve1", ResourceKind.Qemu, 100, mapOf("cores" to "4"), delete = listOf("net1", "ide2"), digest = "abc123")
        assertEquals("UPID:pve1:1:2:3:qmconfig:100:root@pam:", upid)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api2/json/nodes/pve1/qemu/100/config", request.path)
        assertEquals("csrf", request.getHeader("CSRFPreventionToken"))
        assertEquals(mapOf("cores" to "4", "delete" to "net1,ide2", "digest" to "abc123"), form(request))
    }

    @Test
    fun containersChangeAtOnce(): Unit = runBlocking {
        enqueue("""{"data":null}""")
        assertNull(client.updateConfig("pve1", ResourceKind.Lxc, 200, mapOf("hostname" to "web")))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api2/json/nodes/pve1/lxc/200/config", request.path)
        assertEquals(mapOf("hostname" to "web"), form(request))
    }

    @Test
    fun aStaleDigestIsRefused(): Unit = runBlocking {
        enqueue("""{"data":null,"message":"detected modified configuration - file changed by other user? Try again."}""", code = 500)
        val error = assertFailsWith<PveApiException> {
            client.updateConfig("pve1", ResourceKind.Lxc, 200, mapOf("hostname" to "web"), digest = "old")
        }
        assertTrue("modified configuration" in error.statusMessage)
    }

    @Test
    fun pendingChangesAreRead(): Unit = runBlocking {
        enqueue(
            """{"data":[{"key":"cores","value":2,"pending":4},{"key":"memory","value":"4096"},
                {"key":"net1","value":"virtio,bridge=vmbr1","delete":1},{"key":"name","pending":"web"}]}""",
        )
        val pending = client.pendingConfig("pve1", ResourceKind.Qemu, 100)
        assertEquals("/api2/json/nodes/pve1/qemu/100/pending", server.takeRequest().path)
        assertEquals(listOf("cores", "net1", "name"), pending.filter { it.isPending }.map { it.key })
        assertEquals("4", pending[0].pending)
        assertTrue(pending[2].deleting)
        assertNull(pending[3].value)
        assertFalse(pending[1].isPending)
    }

    @Test
    fun disksGrow(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:resize:100:root@pam:"}""")
        client.resizeDisk("pve1", ResourceKind.Qemu, 100, "scsi0", "+8G")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api2/json/nodes/pve1/qemu/100/resize", request.path)
        assertEquals(mapOf("disk" to "scsi0", "size" to "+8G"), form(request))
    }

    @Test
    fun bridgesAreListed(): Unit = runBlocking {
        enqueue("""{"data":[{"iface":"vmbr1","type":"bridge","active":1},{"iface":"vmbr0","type":"bridge","active":1,"cidr":"192.0.2.10/24"}]}""")
        val bridges = client.nodeNetworks("pve1", type = "any_bridge")
        assertEquals("/api2/json/nodes/pve1/network?type=any_bridge", server.takeRequest().path)
        assertEquals(listOf("vmbr0", "vmbr1"), bridges.map { it.iface })
        assertTrue(bridges.all { it.isBridge })
    }

    @Test
    fun guestsAreCreated(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:qmcreate:101:root@pam:"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:vzcreate:201:root@pam:"}""")
        client.createVm("pve1", mapOf("vmid" to "101", "cores" to "2"))
        val vm = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/qemu", vm.path)
        assertEquals(mapOf("vmid" to "101", "cores" to "2"), form(vm))
        client.createContainer("pve1", mapOf("vmid" to "201", "ostemplate" to "local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst"))
        assertEquals("/api2/json/nodes/pve1/lxc", server.takeRequest().path)
    }

    @Test
    fun theSchemaComesFromTheApiViewer(): Unit = runBlocking {
        enqueue(javaClass.getResource("/apidata-excerpt.js")!!.readText())
        val schema = ApiSchema.parse(client.apiSchemaText())
        val request = server.takeRequest()
        assertEquals("/pve-docs/api-viewer/apidata.js", request.path)
        assertNotNull(schema.method("/nodes/{node}/qemu/{vmid}/config", "POST"))
    }

    @Test
    fun aServerWithoutTheViewerSaysSo(): Unit = runBlocking {
        enqueue("", code = 404)
        assertEquals(404, assertFailsWith<SchemaUnavailableException> { client.apiSchemaText() }.statusCode)
        // Not a sign that the session expired.
        enqueue("", code = 401)
        assertEquals(401, assertFailsWith<SchemaUnavailableException> { client.apiSchemaText() }.statusCode)
    }
}
