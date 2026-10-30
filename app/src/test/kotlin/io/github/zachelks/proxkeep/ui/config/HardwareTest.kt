package io.github.zachelks.proxkeep.ui.config

import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareTest {

    private val vm = GuestConfig(
        mapOf(
            "sockets" to "2", "cores" to "4", "cpu" to "x86-64-v2-AES,flags=+aes", "memory" to "4096", "balloon" to "1024",
            "scsi0" to "local-lvm:vm-100-disk-0,iothread=1,size=32G", "efidisk0" to "local-lvm:vm-100-disk-1,size=4M",
            "ide2" to "local:iso/debian-13.1.0-amd64-netinst.iso,media=cdrom,size=754M", "sata0" to "none,media=cdrom",
            "unused0" to "local-lvm:vm-100-disk-2", "net0" to "virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1,tag=20",
        ),
        digest = "abc",
    )

    private val container = GuestConfig(
        mapOf(
            "cores" to "2", "cpulimit" to "1.5", "memory" to "1024", "swap" to "512",
            "rootfs" to "local-lvm:vm-200-disk-0,size=8G", "mp0" to "local-lvm:vm-200-disk-1,mp=/srv/data,size=16G",
            "net0" to "name=eth0,bridge=vmbr0,hwaddr=BC:24:11:00:00:01,ip=dhcp,ip6=auto,type=veth",
        ),
    )

    @Test
    fun readsAVirtualMachinesHardware() {
        val hardware = Hardware.from(ResourceKind.Qemu, vm)
        assertEquals(8, hardware.totalCores)
        assertEquals("x86-64-v2-AES", hardware.cpuType)
        assertEquals(4096, hardware.memoryMiB)
        assertEquals(1024, hardware.balloonMiB)
        assertEquals(listOf("scsi0", "efidisk0", "unused0"), hardware.disks.map { it.key })
        val disk = hardware.disks.first()
        assertEquals("local-lvm", disk.storage)
        assertEquals(32L shl 30, disk.sizeBytes)
        assertTrue(disk.canResize)
        assertTrue(hardware.disks[1].isFirmwareState)
        assertFalse(hardware.disks[1].canResize)
        assertTrue(hardware.disks[2].isUnused)
        assertEquals(listOf("sata0", "ide2").sorted(), hardware.cdroms.map { it.key }.sorted())
        assertTrue(hardware.cdroms.first { it.key == "sata0" }.isEmptyDrive)
        val nic = hardware.nics.single()
        assertEquals("virtio", nic.model)
        assertEquals("BC:24:11:2A:3B:4C", nic.mac)
        assertEquals(20, nic.tag)
        assertTrue(nic.firewall)
    }

    @Test
    fun readsAContainersHardware() {
        val hardware = Hardware.from(ResourceKind.Lxc, container)
        assertEquals(2, hardware.cores)
        assertEquals("1.5", hardware.cpuLimit)
        assertEquals(512, hardware.swapMiB)
        assertEquals(listOf("rootfs", "mp0"), hardware.disks.map { it.key })
        assertTrue(hardware.disks[0].isRoot)
        assertEquals("/srv/data", hardware.disks[1].mountPath)
        assertEquals(16L shl 30, hardware.disks[1].sizeBytes)
        val nic = hardware.nics.single()
        assertEquals("eth0", nic.name)
        assertEquals("dhcp", nic.ip)
        assertEquals("auto", nic.ip6)
    }

    @Test
    fun newerMemorySettingsAreReadToo() {
        assertEquals(8192, Hardware.from(ResourceKind.Qemu, GuestConfig(mapOf("memory" to "current=8192"))).memoryMiB)
        assertEquals(Hardware.DEFAULT_MEMORY_MIB, Hardware.from(ResourceKind.Qemu, GuestConfig(emptyMap())).memoryMiB)
    }

    @Test
    fun processorChangesKeepOtherCpuFlags() {
        assertEquals(
            mapOf("cores" to "2", "sockets" to "1", "cpu" to "host,flags=+aes"),
            HardwareChanges.processor(ResourceKind.Qemu, 1, 2, "host", null, vm),
        )
        assertEquals(mapOf("cores" to "4", "cpulimit" to "0"), HardwareChanges.processor(ResourceKind.Lxc, 1, 4, null, "", container))
    }

    @Test
    fun memoryChangesKeepOtherMemoryKeys() {
        val newer = GuestConfig(mapOf("memory" to "current=4096,hotplug=1"))
        assertEquals(mapOf("memory" to "8192,hotplug=1", "balloon" to "0"), HardwareChanges.memory(ResourceKind.Qemu, 8192, 0, null, newer))
        assertEquals(mapOf("memory" to "2048", "swap" to "0"), HardwareChanges.memory(ResourceKind.Lxc, 2048, null, 0, container))
    }

    @Test
    fun newDevicesTakeTheFirstFreeSlot() {
        assertEquals("scsi1", HardwareChanges.nextKey("scsi", DiskBus.Scsi.slots, vm))
        assertEquals("ide0", HardwareChanges.nextKey("ide", DiskBus.Ide.slots, vm))
        assertNull(HardwareChanges.nextKey("ide", 3, GuestConfig(mapOf("ide0" to "x", "ide1" to "x", "ide2" to "x"))))
        assertEquals("local-lvm:32", HardwareChanges.newDisk("local-lvm", 32))
        assertEquals("local-lvm:8,mp=/srv/logs", HardwareChanges.newMountPoint("local-lvm", 8, " /srv/logs "))
        assertEquals("none,media=cdrom", HardwareChanges.cdrom(null))
        assertEquals("local:iso/x.iso,media=cdrom", HardwareChanges.cdrom("local:iso/x.iso"))
    }

    @Test
    fun networkChangesKeepTheMacAddress() {
        assertEquals(
            "virtio=BC:24:11:2A:3B:4C,bridge=vmbr1",
            HardwareChanges.qemuNic(vm["net0"], "virtio", "vmbr1", null, firewall = false),
        )
        assertEquals("e1000=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1,tag=20", HardwareChanges.qemuNic(vm["net0"], "e1000", "vmbr0", 20, true))
        assertEquals("virtio,bridge=vmbr0,firewall=1", HardwareChanges.qemuNic(null, "virtio", "vmbr0", null, true))
    }

    @Test
    fun containerNetworkChangesDropAGatewayWithoutAStaticAddress() {
        val static = HardwareChanges.lxcNic(container["net0"], NicInfo("net0", name = "eth0", bridge = "vmbr0", ip = "192.0.2.20/24", gateway = "192.0.2.1", ip6 = "auto"))
        assertEquals("name=eth0,bridge=vmbr0,hwaddr=BC:24:11:00:00:01,ip=192.0.2.20/24,ip6=auto,type=veth,gw=192.0.2.1", static)
        val dhcp = HardwareChanges.lxcNic(null, NicInfo("net1", name = "eth1", bridge = "vmbr0", ip = "dhcp", gateway = "192.0.2.1"))
        assertEquals("name=eth1,bridge=vmbr0,ip=dhcp", dhcp)
    }

    @Test
    fun sizesAreReadInBinaryUnits() {
        assertEquals(512L shl 20, parseSize("512M"))
        assertEquals(1536L shl 20, parseSize("1.5G"))
        assertEquals(4096, parseSize("4096"))
        assertNull(parseSize("big"))
    }
}
