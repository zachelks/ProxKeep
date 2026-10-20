package io.github.zachelks.proxkeep.ui.backupjobs

import io.github.zachelks.proxkeep.core.api.model.BackupJob
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.GuestLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupJobsTest {

    private val resources = listOf(
        ClusterResource(id = "node/pve1", type = "node", node = "pve1", status = "online"),
        ClusterResource(id = "node/pve2", type = "node", node = "pve2", status = "online"),
        ClusterResource(id = "node/pve3", type = "node", node = "pve3", status = "offline"),
        ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100),
        ClusterResource(id = "lxc/101", type = "lxc", node = "pve2", vmid = 101),
        ClusterResource(id = "qemu/102", type = "qemu", node = "pve1", vmid = 102),
        ClusterResource(id = "qemu/103", type = "qemu", node = "pve3", vmid = 103),
    )

    @Test
    fun aJobForNamedGuestsRunsOnEachOfTheirNodes() {
        val job = BackupJob("backup-a", mapOf("vmid" to "102,100,101,103", "storage" to "pbs"))
        assertEquals(
            mapOf(
                "pve1" to mapOf("vmid" to "100,102", "storage" to "pbs"),
                "pve2" to mapOf("vmid" to "101", "storage" to "pbs"),
            ),
            BackupJobsViewModel.runPlan(job, resources),
            "an offline node's guests are left out",
        )
    }

    @Test
    fun aJobForAllGuestsRunsOnEveryOnlineNodeOrItsOwn() {
        val all = BackupJob("backup-b", mapOf("all" to "1", "storage" to "nfs"))
        assertEquals(setOf("pve1", "pve2"), BackupJobsViewModel.runPlan(all, resources).keys)
        val pinned = BackupJob("backup-c", mapOf("all" to "1", "storage" to "nfs", "node" to "pve2"))
        assertEquals(mapOf("pve2" to mapOf("all" to "1", "storage" to "nfs")), BackupJobsViewModel.runPlan(pinned, resources))
        val offline = BackupJob("backup-d", mapOf("all" to "1", "node" to "pve3"))
        assertEquals(emptyMap(), BackupJobsViewModel.runPlan(offline, resources))
    }

    @Test
    fun aBackupsKindComesFromItsName() {
        assertEquals(ResourceKind.Qemu, GuestLifecycle.backupKind("local:backup/vzdump-qemu-100-2026_10_14-03_00_02.vma.zst"))
        assertEquals(ResourceKind.Lxc, GuestLifecycle.backupKind("nfs:backup/vzdump-lxc-200-2026_10_14-03_00_02.tar.zst"))
        assertEquals(ResourceKind.Qemu, GuestLifecycle.backupKind("pbs:backup/vm/100/2026-10-14T03:00:00Z"))
        assertEquals(ResourceKind.Lxc, GuestLifecycle.backupKind("pbs:backup/ct/200/2026-10-14T03:00:00Z"))
        assertEquals(ResourceKind.Lxc, GuestLifecycle.backupKind("pbs:something", format = "pbs-ct"))
        assertNull(GuestLifecycle.backupKind("local:iso/debian.iso"))
    }
}
