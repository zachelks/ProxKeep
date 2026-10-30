package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.BackupCompression
import io.github.zachelks.proxkeep.core.api.model.BackupMode
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.Snapshot
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifecycleApiTest {

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
    fun snapshotsAreListedTakenRolledBackAndDeleted(): Unit = runBlocking {
        enqueue(
            """{"data":[{"name":"before-upgrade","description":"Before 9.2","snaptime":"1760400000","vmstate":1},
                {"name":"current","parent":"before-upgrade","running":1}]}""",
        )
        enqueue("""{"data":"UPID:pve1:1:2:3:qmsnapshot:100:root@pam:"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:qmrollback:100:root@pam:"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:qmdelsnapshot:100:root@pam:"}""")

        val snapshots = client.snapshots("pve1", ResourceKind.Qemu, 100)
        assertEquals(listOf("before-upgrade", "current"), snapshots.map { it.name })
        assertEquals(true, snapshots.first().vmstate)
        assertTrue(snapshots.last().isCurrent)
        assertEquals("/api2/json/nodes/pve1/qemu/100/snapshot", server.takeRequest().path)

        client.createSnapshot("pve1", ResourceKind.Qemu, 100, "nightly", "Before patching", includeMemory = true)
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals(mapOf("snapname" to "nightly", "description" to "Before patching", "vmstate" to "1"), form(create))
        assertEquals("csrf", create.getHeader("CSRFPreventionToken"))

        client.rollbackSnapshot("pve1", ResourceKind.Qemu, 100, "nightly")
        assertEquals("/api2/json/nodes/pve1/qemu/100/snapshot/nightly/rollback", server.takeRequest().path)

        client.deleteSnapshot("pve1", ResourceKind.Qemu, 100, "nightly")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api2/json/nodes/pve1/qemu/100/snapshot/nightly", delete.path)
        assertEquals("csrf", delete.getHeader("CSRFPreventionToken"))
    }

    @Test
    fun aContainerSnapshotNeverAsksForMemory(): Unit = runBlocking {
        enqueue("""{"data":"UPID:x"}""")

        client.createSnapshot("pve1", ResourceKind.Lxc, 200, "nightly", null, includeMemory = true)

        assertEquals(mapOf("snapname" to "nightly"), form(server.takeRequest()))
    }

    @Test
    fun snapshotNamesFollowTheServersRules() {
        assertTrue(Snapshot.isValidName("before-upgrade_2"))
        assertFalse(Snapshot.isValidName("2026"))
        assertFalse(Snapshot.isValidName("a"))
        assertFalse(Snapshot.isValidName("with space"))
        assertFalse(Snapshot.isValidName("current"))
        assertFalse(Snapshot.isValidName("ümlaut"))
    }

    @Test
    fun aBackupGoesToTheChosenStorage(): Unit = runBlocking {
        enqueue("""{"data":[{"storage":"pbs","type":"pbs","content":"backup","active":1,"avail":1000},{"storage":"nfs","content":"backup,iso","enabled":0}]}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:vzdump:100:root@pam:"}""")

        val storages = client.nodeStorage("pve1", content = "backup")
        assertEquals(setOf("backup", "iso"), storages[1].contentTypes)
        assertFalse(storages[1].isUsable)
        assertEquals("/api2/json/nodes/pve1/storage?content=backup", server.takeRequest().path)

        client.backupNow("pve1", 100, "pbs", BackupMode.Snapshot, BackupCompression.Zstd, notes = "{{guestname}} by app")
        val backup = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/vzdump", backup.path)
        assertEquals(
            mapOf("vmid" to "100", "storage" to "pbs", "mode" to "snapshot", "compress" to "zstd", "notes-template" to "{{guestname}} by app"),
            form(backup),
        )
    }

    @Test
    fun backupsAreListedAndRestoredOverTheGuest(): Unit = runBlocking {
        enqueue("""{"data":[{"volid":"pbs:backup/vm/100/2026-10-14T03:00:02Z","format":"pbs-vm","size":"2147483648","ctime":1760410802,"vmid":"100","notes":"nightly","protected":0}]}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:qmrestore:100:root@pam:"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:vzrestore:200:root@pam:"}""")

        val backups = client.storageContent("pve1", "pbs", content = "backup", vmid = 100)
        assertEquals(100, backups.single().vmid)
        assertEquals(2147483648L, backups.single().size)
        assertEquals("/api2/json/nodes/pve1/storage/pbs/content?content=backup&vmid=100", server.takeRequest().path)

        client.restore("pve1", ResourceKind.Qemu, 100, backups.single().volid, overwrite = true, start = true)
        val vm = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/qemu", vm.path)
        assertEquals(mapOf("vmid" to "100", "archive" to backups.single().volid, "force" to "1", "start" to "1"), form(vm))

        client.restore("pve1", ResourceKind.Lxc, 200, "local:backup/vzdump-lxc-200.tar.zst", overwrite = false, storage = "local-lvm")
        assertEquals(
            mapOf("vmid" to "200", "ostemplate" to "local:backup/vzdump-lxc-200.tar.zst", "restore" to "1", "storage" to "local-lvm"),
            form(server.takeRequest()),
        )
    }

    @Test
    fun migrationIsCheckedAndStarted(): Unit = runBlocking {
        enqueue("""{"data":{"running":1,"allowed_nodes":["pve2"],"not_allowed_nodes":{"pve3":{"unavailable_storages":["local-zfs"]}},"local_disks":[{"volid":"local-lvm:vm-100-disk-0","drivename":"scsi0"}],"local_resources":[]}}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:qmigrate:100:root@pam:"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:vzmigrate:200:root@pam:"}""")

        val check = client.migrationCheck("pve1", 100)
        assertEquals(listOf("pve2"), check.allowedNodes)
        assertEquals("scsi0", check.localDisks?.single()?.driveName)
        server.takeRequest()

        client.migrate("pve1", ResourceKind.Qemu, 100, "pve2", running = true, withLocalDisks = true)
        assertEquals(mapOf("target" to "pve2", "online" to "1", "with-local-disks" to "1"), form(server.takeRequest()))

        client.migrate("pve1", ResourceKind.Lxc, 200, "pve2", running = true, withLocalDisks = true)
        assertEquals(mapOf("target" to "pve2", "restart" to "1"), form(server.takeRequest()))
    }

    @Test
    fun aCloneGetsTheNextFreeId(): Unit = runBlocking {
        enqueue("""{"data":"105"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:qmclone:100:root@pam:"}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:vzclone:200:root@pam:"}""")

        val id = client.nextId()
        assertEquals(105, id)
        server.takeRequest()

        client.clone("pve1", ResourceKind.Qemu, 100, id, "web-copy", full = true, targetNode = "pve2", storage = "ceph")
        assertEquals(
            mapOf("newid" to "105", "name" to "web-copy", "full" to "1", "target" to "pve2", "storage" to "ceph"),
            form(server.takeRequest()),
        )

        client.clone("pve1", ResourceKind.Lxc, 200, 106, "dns2", full = false, targetNode = "pve1", storage = "ceph")
        assertEquals(mapOf("newid" to "106", "hostname" to "dns2", "full" to "0"), form(server.takeRequest()), "a linked clone stays on its storage")
    }

    @Test
    fun aTemplateConversionMayFinishAtOnce(): Unit = runBlocking {
        enqueue("""{"data":null}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:qmtemplate:100:root@pam:"}""")

        assertNull(client.convertToTemplate("pve1", ResourceKind.Lxc, 200))
        assertEquals("/api2/json/nodes/pve1/lxc/200/template", server.takeRequest().path)
        assertEquals("UPID:pve1:1:2:3:qmtemplate:100:root@pam:", client.convertToTemplate("pve1", ResourceKind.Qemu, 100))
    }

    @Test
    fun aDeletedGuestTakesItsDisksAndJobsWithIt(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:qmdestroy:100:root@pam:"}""")
        enqueue("""{"data":null,"message":"VM 101 is running - destroy failed"}""", code = 500)

        client.deleteGuest("pve1", ResourceKind.Qemu, 100, purge = true)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api2/json/nodes/pve1/qemu/100?purge=1&destroy-unreferenced-disks=1", request.path)

        val error = assertFailsWith<PveApiException> { client.deleteGuest("pve1", ResourceKind.Qemu, 101, purge = false) }
        assertEquals("VM 101 is running - destroy failed", error.statusMessage)
    }
}
