package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.GuestStatus
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestModelsTest {

    private fun config(json: String): GuestConfig =
        GuestConfig.fromJson(PveJson.parseToJsonElement(json) as JsonObject)

    private val vmConfig = config(
        """{"name":"web","memory":"4096","cores":4,"sockets":1,"ostype":"l26","onboot":1,
            "agent":"enabled=1,fstrim_cloned_disks=1","scsihw":"virtio-scsi-single",
            "scsi1":"local-lvm:vm-100-disk-1,size=100G","scsi0":"local-lvm:vm-100-disk-0,size=32G",
            "ide2":"local:iso/debian.iso,media=cdrom,size=650M","efidisk0":"local-lvm:vm-100-disk-2,size=4M",
            "net1":"virtio=BC:24:11:00:00:02,bridge=vmbr1","net0":"virtio=BC:24:11:00:00:01,bridge=vmbr0",
            "unused0":"local-lvm:vm-100-disk-9","description":"Front end\n","digest":"abc123",
            "meta":{"nested":true}}""",
    )

    @Test
    fun configKeepsPlainValuesAndDropsTheRest() {
        assertEquals("web", vmConfig["name"])
        assertEquals(4096L, vmConfig.long("memory"))
        assertEquals(4L, vmConfig.long("cores"))
        assertEquals(true, vmConfig.flag("onboot"))
        assertEquals("Front end\n", vmConfig.description)
        assertNull(vmConfig["digest"])
        assertNull(vmConfig["meta"])
        assertNull(vmConfig["missing"])
    }

    @Test
    fun disksAreOrderedByBusThenIndex() {
        assertEquals(
            listOf("scsi0", "scsi1", "ide2", "efidisk0", "unused0"),
            vmConfig.disks.map { it.key },
        )
        // The controller type shares the "scsi" prefix but is not a device.
        assertFalse(vmConfig.disks.any { it.key == "scsihw" })
        assertTrue(vmConfig.disks.single { it.key == "ide2" }.isCdrom)
        assertFalse(vmConfig.disks.first().isCdrom)
    }

    @Test
    fun networksAreOrderedByIndex() {
        assertEquals(listOf("net0", "net1"), vmConfig.networks.map { it.key })
        assertEquals(1, vmConfig.networks.last().index)
    }

    @Test
    fun agentSettingIsReadInBothForms() {
        assertTrue(vmConfig.agentEnabled)
        assertTrue(config("""{"agent":"1"}""").agentEnabled)
        assertFalse(config("""{"agent":"0"}""").agentEnabled)
        assertFalse(config("""{"agent":"enabled=0"}""").agentEnabled)
        assertFalse(config("""{}""").agentEnabled)
    }

    @Test
    fun containerConfigListsRootDiskFirst() {
        val container = config(
            """{"hostname":"dns","memory":512,"swap":512,"cores":1,"unprivileged":1,
                "mp0":"tank:subvol-101-disk-1,mp=/data,size=8G","rootfs":"local-lvm:vm-101-disk-0,size=8G",
                "net0":"name=eth0,bridge=vmbr0,ip=dhcp","description":"  "}""",
        )
        assertEquals(listOf("rootfs", "mp0"), container.disks.map { it.key })
        assertNull(container.disks.first().index)
        assertNull(container.description)
    }

    @Test
    fun actionsDependOnStateAndGuestType() {
        fun actions(kind: ResourceKind, running: Boolean, paused: Boolean = false, template: Boolean = false) =
            GuestAction.available(kind, running, paused, template)

        assertEquals(listOf(GuestAction.Start), actions(ResourceKind.Qemu, running = false))
        assertEquals(
            listOf(GuestAction.Shutdown, GuestAction.Reboot, GuestAction.Pause, GuestAction.Stop, GuestAction.Reset),
            actions(ResourceKind.Qemu, running = true),
        )
        assertEquals(listOf(GuestAction.Resume, GuestAction.Stop), actions(ResourceKind.Qemu, running = true, paused = true))
        assertEquals(
            listOf(GuestAction.Shutdown, GuestAction.Reboot, GuestAction.Stop),
            actions(ResourceKind.Lxc, running = true),
        )
        assertEquals(listOf(GuestAction.Start), actions(ResourceKind.Lxc, running = false))
        assertEquals(emptyList(), actions(ResourceKind.Qemu, running = false, template = true))
        assertEquals(emptyList(), actions(ResourceKind.Node, running = true))
    }

    @Test
    fun onlyStopAndResetAreForceful() {
        assertEquals(setOf(GuestAction.Stop, GuestAction.Reset), GuestAction.entries.filter { it.isForceful }.toSet())
    }

    @Test
    fun guestStatusKnowsPausedFromRunning() {
        val paused = PveJson.decodeFromString<GuestStatus>(
            """{"status":"running","qmpstatus":"paused","cpus":2,"mem":"1024","lock":"backup","ha":{"managed":0}}""",
        )
        assertTrue(paused.isRunning)
        assertTrue(paused.isPaused)
        assertEquals("backup", paused.lock)
        assertEquals(1024L, paused.mem)

        val stopped = PveJson.decodeFromString<GuestStatus>("""{"status":"stopped","qmpstatus":"stopped"}""")
        assertFalse(stopped.isRunning)
        assertFalse(stopped.isPaused)
    }

    @Test
    fun taskOutcomesAreClassified() {
        fun summary(extra: String) = PveJson.decodeFromString<TaskSummary>(
            """{"upid":"UPID:pve1:1:2:3:qmstart:100:root@pam:","node":"pve1","type":"qmstart","id":"100","starttime":"1700000000"$extra}""",
        )
        val running = summary("")
        assertTrue(running.isRunning)
        assertFalse(running.failed)
        assertEquals(1_700_000_000L, running.starttime)

        assertTrue(summary(""","endtime":1700000005,"status":"OK"""").succeeded)
        assertTrue(summary(""","endtime":1700000005,"status":"WARNINGS: 2"""").hasWarnings)
        val failed = summary(""","endtime":1700000005,"status":"start failed: QEMU exited with code 1"""")
        assertTrue(failed.failed)
        assertFalse(failed.succeeded)

        assertTrue(TaskStatus("stopped", "OK").succeeded)
        assertTrue(TaskStatus("stopped", "WARNINGS: 1").succeeded)
        assertFalse(TaskStatus("stopped", "command failed").succeeded)
        assertFalse(TaskStatus("running").succeeded)
    }
}
