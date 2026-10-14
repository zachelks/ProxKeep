package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.ServiceAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeApiTest {

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
    fun theNodesStatusIsRead(): Unit = runBlocking {
        enqueue(
            """{"data":{"cpu":0.042,"cpuinfo":{"model":"AMD EPYC 7302P","cpus":32,"sockets":1},"memory":{"used":"8589934592","total":68719476736},
                "uptime":93784,"loadavg":["0.42","0.38","0.31"],"pveversion":"pve-manager/9.0.3/abc","kversion":"Linux 6.14.8-2-pve",
                "boot-info":{"mode":"efi","secureboot":0}}}""",
        )
        val status = client.nodeStatus("pve1")
        assertEquals("/api2/json/nodes/pve1/status", server.takeRequest().path)
        assertEquals(32, status.cpuinfo?.cpus)
        assertEquals(8589934592, status.memory?.used)
        assertEquals(listOf("0.42", "0.38", "0.31"), status.loadavg)
        assertEquals("efi", status.bootInfo?.mode)
        assertEquals(false, status.bootInfo?.secureboot)
    }

    @Test
    fun aNodeRebootsOrShutsDown(): Unit = runBlocking {
        enqueue("""{"data":null}""")
        enqueue("""{"data":null}""")
        client.nodePower("pve1", reboot = true)
        assertEquals(mapOf("command" to "reboot"), form(server.takeRequest()))
        client.nodePower("pve1", reboot = false)
        assertEquals(mapOf("command" to "shutdown"), form(server.takeRequest()))
    }

    @Test
    fun updatesAreListedAndRefreshed(): Unit = runBlocking {
        enqueue("""{"data":[{"Package":"zfsutils-linux","OldVersion":"2.3.1","Version":"2.3.2","Title":"ZFS tools"},{"Package":"pve-manager","OldVersion":"9.0.2","Version":"9.0.3"}]}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:aptupdate::root@pam:"}""")
        val updates = client.packageUpdates("pve1")
        assertEquals(listOf("pve-manager", "zfsutils-linux"), updates.map { it.name })
        assertEquals("9.0.2", updates.first().oldVersion)
        assertEquals("/api2/json/nodes/pve1/apt/update", server.takeRequest().path)
        assertEquals("UPID:pve1:1:2:3:aptupdate::root@pam:", client.refreshPackageLists("pve1"))
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun servicesAreListedAndControlled(): Unit = runBlocking {
        enqueue("""{"data":[{"service":"pveproxy","name":"pveproxy","desc":"PVE API Proxy Server","state":"running","unit-state":"enabled"},{"service":"chrony","state":"dead","unit-state":"enabled"}]}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:srvrestart:pveproxy:root@pam:"}""")
        val services = client.services("pve1")
        assertEquals(listOf("chrony", "pveproxy"), services.map { it.service })
        assertTrue(services[1].isRunning)
        assertFalse(services[0].isRunning)
        server.takeRequest()
        client.serviceAction("pve1", "pveproxy", ServiceAction.Restart)
        assertEquals("/api2/json/nodes/pve1/services/pveproxy/restart", server.takeRequest().path)
    }

    @Test
    fun dnsKeepsAtMostThreeServersAndRemovesTheRest(): Unit = runBlocking {
        enqueue("""{"data":null}""")
        client.setNodeDns("pve1", "example.com", listOf("192.0.2.53"))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(mapOf("search" to "example.com", "dns1" to "192.0.2.53", "delete" to "dns2,dns3"), form(request))
    }

    @Test
    fun networkChangesAreAppliedOrReverted(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:srvreload:networking:root@pam:"}""")
        enqueue("""{"data":null}""")
        client.applyNetwork("pve1")
        assertEquals("PUT", server.takeRequest().method)
        client.revertNetwork("pve1")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun disksSmartCertificatesAndTheJournalAreRead(): Unit = runBlocking {
        enqueue("""{"data":[{"devpath":"/dev/sdb","size":1000204886016,"health":"FAILED","type":"hdd"},{"devpath":"/dev/nvme0n1","health":"PASSED","wearout":"97","type":"nvme"}]}""")
        enqueue("""{"data":{"health":"PASSED","type":"ata","attributes":[{"id":"  5","name":"Reallocated_Sector_Ct","value":100,"worst":100,"threshold":10,"raw":"0","fail":"-"}]}}""")
        enqueue("""{"data":[{"filename":"pveproxy-ssl.pem","subject":"/CN=pve1.example.com","notafter":1792000000,"san":["pve1.example.com"]}]}""")
        enqueue("""{"data":["Oct 14 08:00:01 pve1 systemd[1]: Started", "Oct 14 08:00:02 pve1 pveproxy[1]: worker started"]}""")

        val disks = client.physicalDisks("pve1")
        assertEquals(listOf("/dev/nvme0n1", "/dev/sdb"), disks.map { it.devpath })
        assertTrue(disks[1].isFailing)
        assertFalse(disks[0].isFailing)
        server.takeRequest()

        val smart = client.smart("pve1", "/dev/sda")
        assertEquals("/api2/json/nodes/pve1/disks/smart?disk=%2Fdev%2Fsda", server.takeRequest().path)
        assertEquals("Reallocated_Sector_Ct", smart.attributes.single().name)

        assertEquals(listOf("pve1.example.com"), client.certificates("pve1").single().san)
        server.takeRequest()
        assertEquals(2, client.journal("pve1", 100).size)
        assertEquals("/api2/json/nodes/pve1/journal?lastentries=100", server.takeRequest().path)
    }

    @Test
    fun anyPathCanBeReadAndWritten(): Unit = runBlocking {
        enqueue("""{"data":{"keyboard":"en-us","digest":"abc"}}""")
        enqueue("""{"data":null}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:x::root@pam:"}""")
        val options = client.read("/cluster/options") as JsonObject
        assertEquals("en-us", options["keyboard"]?.jsonPrimitive?.content)
        assertEquals("/api2/json/cluster/options", server.takeRequest().path)

        assertNull(client.write("PUT", "/cluster/options", mapOf("keyboard" to "de")))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("csrf", put.getHeader("CSRFPreventionToken"))
        assertEquals(mapOf("keyboard" to "de"), form(put))

        assertEquals("UPID:pve1:1:2:3:x::root@pam:", client.write("DELETE", "/nodes/pve1/network/vmbr9", emptyMap()))
        assertEquals("/api2/json/nodes/pve1/network/vmbr9", server.takeRequest().path)
        assertFailsWith<IllegalArgumentException> { client.write("PATCH", "/x", emptyMap()) }
    }

    @Test
    fun aPathValueWithASlashStaysOneSegment(): Unit = runBlocking {
        enqueue("""{"data":null}""")
        client.write("DELETE", "/cluster/firewall/ipset/office/${encodePathValue("10.0.0.0/8")}", emptyMap())
        assertEquals("/api2/json/cluster/firewall/ipset/office/10.0.0.0%2F8", server.takeRequest().path)
        assertEquals("a%25b%2Fc", encodePathValue("a%b/c"))
    }

    @Test
    fun aWriteCanReturnItsData(): Unit = runBlocking {
        enqueue("""{"data":{"full-tokenid":"ops@pve!ci","value":"secret-uuid","info":{"privsep":1}}}""")
        val data = client.writeForData("POST", "/access/users/ops@pve/token/ci", mapOf("privsep" to "1")) as JsonObject
        assertEquals("secret-uuid", data["value"]?.jsonPrimitive?.content)
        assertEquals("/api2/json/access/users/ops@pve/token/ci", server.takeRequest().path)
    }
}
