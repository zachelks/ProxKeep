package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.pbs.BackupGroup
import io.github.zachelks.proxkeep.core.api.pbs.BackupSnapshot
import io.github.zachelks.proxkeep.core.api.pbs.PruneOptions
import io.github.zachelks.proxkeep.core.api.pbs.backupGroups
import io.github.zachelks.proxkeep.core.api.pbs.backupServerStatus
import io.github.zachelks.proxkeep.core.api.pbs.backupSnapshots
import io.github.zachelks.proxkeep.core.api.pbs.datastoreUsage
import io.github.zachelks.proxkeep.core.api.pbs.deleteSnapshot
import io.github.zachelks.proxkeep.core.api.pbs.namespaces
import io.github.zachelks.proxkeep.core.api.pbs.pruneGroup
import io.github.zachelks.proxkeep.core.api.pbs.setProtected
import io.github.zachelks.proxkeep.core.api.pbs.startGarbageCollection
import io.github.zachelks.proxkeep.core.api.pbs.verify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupServerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PveClient

    private val ticket = "PBS:root@pam:6712F3A1::c2lnbmF0dXJl"

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PveClient(server.url("/api2/json/"), OkHttpClient(), product = Product.Pbs)
            .withCredentials(TicketCredentials("root@pam", ticket, "csrf"))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }

    @Test
    fun ticketsGoInTheBackupServerCookie(): Unit = runBlocking {
        enqueue("""{"data":{"version":"4.2","release":"1","repoid":"abc"}}""")
        client.version()
        assertEquals("PBSAuthCookie=$ticket", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun tokensUseTheBackupServerHeader(): Unit = runBlocking {
        enqueue("""{"data":{"version":"4.2"}}""")
        client.withCredentials(ApiTokenCredentials("root@pam!app", "secret")).version()
        assertEquals("PBSAPIToken=root@pam!app:secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun theProductKeepsItsRulesAcrossCredentials() {
        assertEquals(Product.Pbs, client.withCredentials(null).product)
        assertEquals("PVEAPIToken=a@pve!t=s", Product.Pve.tokenAuthorization("a@pve!t", "s"))
        assertEquals("PDMAPIToken=a@pdm!t:s", Product.Pdm.tokenAuthorization("a@pdm!t", "s"))
        assertEquals(Product.Pbs, Product.forPort(8007))
        assertEquals(Product.Pve, Product.forPort(443))
    }

    @Test
    fun signInReadsTheTicketFromTheBackupServerCookie(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "PBSAuthCookie=${ticket.replace(":", "%3A")}; Path=/; Secure")
                .setBody("""{"data":{"username":"root@pam","CSRFPreventionToken":"csrf"}}"""),
        )
        val result = PveClient(server.url("/api2/json/"), OkHttpClient(), product = Product.Pbs).login("root", "pam", "password")
        val success = assertIs<LoginResult.Success>(result)
        assertEquals(ticket, (success.credentials as TicketCredentials).ticket)
    }

    @Test
    fun tasksOfABackupServerAreItsOwn(): Unit = runBlocking {
        enqueue(
            """{"data":[{"upid":"UPID:pbs:00001234:0000ABCD:00000001:6712F3A1:garbage_collection:store1:root@pam:",
               "node":"pbs","worker_type":"garbage_collection","worker_id":"store1","user":"root@pam",
               "starttime":1729295265,"endtime":1729295300,"status":"OK"}]}""",
        )
        val tasks = client.clusterTasks()
        val request = server.takeRequest()
        assertEquals("/api2/json/nodes/localhost/tasks", request.requestUrl!!.encodedPath)
        assertEquals("garbage_collection", tasks.single().type)
        assertEquals("store1", tasks.single().id)
        assertTrue(tasks.single().succeeded)
    }

    @Test
    fun readsDatastoreUsage(): Unit = runBlocking {
        enqueue(
            """{"data":[{"store":"store1","total":1000,"used":250,"avail":750,"estimated-full-date":1767225600,
               "gc-status":{"upid":"UPID:x","disk-bytes":100,"index-data-bytes":400,"pending-bytes":20,"removed-bytes":5}},
               {"store":"usb","error":"datastore is not mounted"}]}""",
        )
        val usage = client.datastoreUsage()
        assertEquals("/api2/json/status/datastore-usage", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(250L, usage[0].used)
        assertEquals(1767225600L, usage[0].estimatedFullDate)
        assertEquals(4.0, usage[0].gcStatus!!.deduplicationFactor)
        assertEquals(20L, usage[0].gcStatus!!.pendingBytes)
        assertEquals("datastore is not mounted", usage[1].error)
        assertNull(usage[1].total)
    }

    @Test
    fun readsTheServerStatus(): Unit = runBlocking {
        enqueue("""{"data":{"cpu":0.25,"memory":{"total":8000,"used":2000,"free":6000},"root":{"total":100,"used":40,"avail":60},"uptime":3600}}""")
        val status = client.backupServerStatus()
        assertEquals("/api2/json/nodes/localhost/status", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(0.25, status.cpu)
        assertEquals(2000L, status.memory!!.used)
        assertEquals(40L, status.root!!.used)
    }

    @Test
    fun browsesGroupsAndSnapshotsInANamespace(): Unit = runBlocking {
        enqueue("""{"data":[{"ns":""},{"ns":"prod","comment":"Production"}]}""")
        enqueue("""{"data":[{"backup-type":"vm","backup-id":"100","last-backup":1729295265,"backup-count":3,"owner":"root@pam"}]}""")
        enqueue(
            """{"data":[{"backup-type":"vm","backup-id":"100","backup-time":1729295265,"size":4096,"protected":true,
               "verification":{"state":"ok","upid":"UPID:x"},
               "files":[{"filename":"drive-scsi0.img.fidx","size":4096,"crypt-mode":"encrypt"}]}]}""",
        )

        assertEquals(listOf("", "prod"), client.namespaces("store1").map { it.ns })
        assertEquals("/api2/json/admin/datastore/store1/namespace", server.takeRequest().requestUrl!!.encodedPath)

        val group = client.backupGroups("store1", "prod").single()
        val groupsRequest = server.takeRequest().requestUrl!!
        assertEquals("prod", groupsRequest.queryParameter("ns"))
        assertEquals("vm/100", group.key)
        assertEquals(3L, group.count)

        val snapshot = client.backupSnapshots("store1", "prod", group).single()
        val snapshotsRequest = server.takeRequest().requestUrl!!
        assertEquals("/api2/json/admin/datastore/store1/snapshots", snapshotsRequest.encodedPath)
        assertEquals("vm", snapshotsRequest.queryParameter("backup-type"))
        assertEquals("100", snapshotsRequest.queryParameter("backup-id"))
        assertTrue(snapshot.protected)
        assertTrue(snapshot.verification!!.passed)
        assertTrue(snapshot.encrypted)
    }

    @Test
    fun theRootNamespaceIsLeftOut(): Unit = runBlocking {
        enqueue("""{"data":[]}""")
        client.backupGroups("store1")
        assertNull(server.takeRequest().requestUrl!!.queryParameter("ns"))
    }

    @Test
    fun maintenanceStartsTasks(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pbs:1:2:3:4:garbage_collection:store1:root@pam:"}""")
        enqueue("""{"data":"UPID:pbs:1:2:3:4:verify:store1:root@pam:"}""")

        assertEquals("UPID:pbs:1:2:3:4:garbage_collection:store1:root@pam:", client.startGarbageCollection("store1"))
        val gc = server.takeRequest()
        assertEquals("POST", gc.method)
        assertEquals("/api2/json/admin/datastore/store1/gc", gc.requestUrl!!.encodedPath)
        assertEquals("csrf", gc.getHeader("CSRFPreventionToken"))

        client.verify("store1", "prod", skipVerified = true)
        assertEquals(mapOf("ns" to "prod", "ignore-verified" to "1"), form(server.takeRequest()))
    }

    @Test
    fun aPruneIsPreviewedWithADryRun(): Unit = runBlocking {
        enqueue(
            """{"data":[{"backup-type":"vm","backup-id":"100","backup-time":1729295265,"keep":true},
               {"backup-type":"vm","backup-id":"100","backup-time":1729208865,"keep":false,"protected":false}]}""",
        )
        val results = client.pruneGroup("store1", "", BackupGroup("vm", "100"), PruneOptions(last = 1, daily = 7), dryRun = true)
        val request = server.takeRequest()
        assertEquals("/api2/json/admin/datastore/store1/prune", request.requestUrl!!.encodedPath)
        assertEquals(
            mapOf("backup-type" to "vm", "backup-id" to "100", "keep-last" to "1", "keep-daily" to "7", "dry-run" to "1"),
            form(request),
        )
        assertEquals(listOf(true, false), results.map { it.keep })
    }

    @Test
    fun snapshotsAreNamedByTypeIdAndTime(): Unit = runBlocking {
        val snapshot = BackupSnapshot("ct", "101", time = 1729295265)
        enqueue("""{"data":null}""")
        enqueue("""{"data":null}""")

        client.setProtected("store1", "", snapshot, protected = true)
        val protect = server.takeRequest()
        assertEquals("PUT", protect.method)
        assertEquals(
            mapOf("backup-type" to "ct", "backup-id" to "101", "backup-time" to "1729295265", "protected" to "1"),
            form(protect),
        )

        client.deleteSnapshot("store1", "", snapshot)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("1729295265", delete.requestUrl!!.queryParameter("backup-time"))
    }

    @Test
    fun emptyPruneOptionsKeepEverything() {
        assertTrue(PruneOptions().isEmpty)
        assertFalse(PruneOptions(weekly = 4).isEmpty)
        assertEquals(mapOf("keep-weekly" to "4", "keep-yearly" to "1"), PruneOptions(weekly = 4, yearly = 1).toParameters())
    }
}
