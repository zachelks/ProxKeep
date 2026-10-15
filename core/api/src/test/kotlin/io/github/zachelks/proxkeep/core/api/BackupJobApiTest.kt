package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupJobApiTest {

    private val server = MockWebServer().apply { start() }
    private val client = PveClient(server.url("/api2/json/"), OkHttpClient())
        .withCredentials(TicketCredentials("root@pam", "PVE:root@pam:X::Y", "csrf"))

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun form(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
    }

    @Test
    fun jobsAreReadWithTheirSettings(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                {"id":"backup-b","type":"vzdump","schedule":"sat 02:00","storage":"pbs","vmid":"100,101","mode":"snapshot","enabled":0,"next-run":1760900000,"prune-backups":"keep-daily=7,keep-weekly=4"},
                {"id":"backup-a","type":"vzdump","schedule":"daily","storage":"nfs","all":1,"exclude":"200","comment":"Nightly","node":"pve1","repeat-missed":1}]}""",
            ),
        )
        val jobs = client.backupJobs()
        assertEquals(listOf("backup-a", "backup-b"), jobs.map { it.id })
        val nightly = jobs[0]
        assertTrue(nightly.all)
        assertTrue(nightly.enabled)
        assertEquals(listOf(200), nightly.excluded)
        assertEquals("Nightly", nightly.comment)
        assertEquals(mapOf("storage" to "nfs", "all" to "1", "exclude" to "200"), nightly.runParameters())

        val weekly = jobs[1]
        assertFalse(weekly.enabled)
        assertEquals(listOf(100, 101), weekly.guests)
        assertEquals(
            mapOf("storage" to "pbs", "vmid" to "100,101", "mode" to "snapshot", "prune-backups" to "keep-daily=7,keep-weekly=4"),
            weekly.runParameters(),
        )
    }

    @Test
    fun aJobRunsAsABackupOnANode(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":"UPID:pve1:1:2:3:vzdump::root@pam:"}"""))
        client.runBackup("pve1", mapOf("storage" to "nfs", "all" to "1"))
        val request = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/vzdump", request.path)
        assertEquals(mapOf("storage" to "nfs", "all" to "1"), form(request.body.readUtf8()))
    }

    @Test
    fun aRestoredCopyCanGetNewAddresses(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":"UPID:pve1:1:2:3:qmrestore:150:root@pam:"}"""))
        client.restore("pve1", ResourceKind.Qemu, 150, "pbs:backup/vm/100/2026-10-14T03:00:00Z", overwrite = false, storage = "local-lvm", unique = true)
        assertEquals(
            mapOf("vmid" to "150", "archive" to "pbs:backup/vm/100/2026-10-14T03:00:00Z", "storage" to "local-lvm", "unique" to "1"),
            form(server.takeRequest().body.readUtf8()),
        )
    }
}
