package io.github.zachelks.proxkeep.ui.create

import io.github.zachelks.proxkeep.ui.config.DiskBus
import io.github.zachelks.proxkeep.ui.config.IpMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DraftsTest {

    private val vm = VmDraft(node = "pve1", vmid = "101", name = "web", iso = "local:iso/debian-13.1.0-amd64-netinst.iso", storage = "local-lvm")
    private val ct = CtDraft(
        node = "pve1", vmid = "201", hostname = "proxy", password = "secret1", confirmPassword = "secret1",
        template = "local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst", storage = "local-lvm",
    )

    @Test
    fun aLinuxVirtualMachineGetsSensibleDefaults() {
        assertEquals(
            mapOf(
                "vmid" to "101", "name" to "web", "ostype" to "l26",
                "ide2" to "local:iso/debian-13.1.0-amd64-netinst.iso,media=cdrom",
                "scsihw" to "virtio-scsi-single", "scsi0" to "local-lvm:32,iothread=1",
                "sockets" to "1", "cores" to "2", "cpu" to "x86-64-v2-AES", "memory" to "2048", "agent" to "1",
                "net0" to "virtio,bridge=vmbr0,firewall=1", "boot" to "order=scsi0;ide2;net0",
            ),
            vm.parameters(),
        )
    }

    @Test
    fun windowsGetsUefiATpmAndAModernMachine() {
        val windows = vm.withOs(GuestOs.Windows).copy(bus = DiskBus.Sata, bridge = null, start = true)
        assertTrue(windows.uefi)
        val parameters = windows.parameters()
        assertEquals("win11", parameters["ostype"])
        assertEquals("ovmf", parameters["bios"])
        assertEquals("q35", parameters["machine"])
        assertEquals("local-lvm:1,efitype=4m,pre-enrolled-keys=1", parameters["efidisk0"])
        assertEquals("local-lvm:1,version=v2.0", parameters["tpmstate0"])
        assertEquals("local-lvm:32", parameters["sata0"])
        assertEquals("order=sata0;ide2", parameters["boot"])
        assertEquals("1", parameters["start"])
        assertFalse("net0" in parameters)
    }

    @Test
    fun eachVirtualMachineStepChecksItsFields() {
        assertTrue(vm.isValid(VmStep.Confirm, takenIds = setOf(100)))
        assertFalse(vm.isValid(VmStep.General, takenIds = setOf(101)), "the ID is taken")
        assertFalse(vm.copy(vmid = "99").isValid(VmStep.General, emptySet()))
        assertFalse(vm.copy(name = "web_1").isValid(VmStep.General, emptySet()))
        assertTrue(vm.copy(name = "").isValid(VmStep.General, emptySet()), "a name is optional")
        assertFalse(vm.copy(storage = "").isValid(VmStep.Disk, emptySet()))
        assertFalse(vm.copy(memoryMiB = "8").isValid(VmStep.Processor, emptySet()))
        assertFalse(vm.copy(vlan = "5000").isValid(VmStep.Network, emptySet()))
        assertFalse(vm.copy(storage = "").isValid(VmStep.Confirm, emptySet()), "the last step checks them all")
    }

    @Test
    fun aContainerGetsItsTemplateRootDiskAndNetwork() {
        assertEquals(
            mapOf(
                "vmid" to "201", "hostname" to "proxy", "ostemplate" to "local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst",
                "password" to "secret1", "unprivileged" to "1", "features" to "nesting=1", "rootfs" to "local-lvm:8",
                "cores" to "1", "memory" to "512", "swap" to "512", "net0" to "name=eth0,bridge=vmbr0,ip=dhcp,firewall=1",
            ),
            ct.parameters(),
        )
        val static = ct.copy(ipMode = IpMode.Static, address = "192.0.2.20/24", gateway = "192.0.2.1", dnsServers = "192.0.2.53", sshKeys = "ssh-ed25519 AAAA me\n")
        val parameters = static.parameters()
        assertEquals("name=eth0,bridge=vmbr0,ip=192.0.2.20/24,gw=192.0.2.1,firewall=1", parameters["net0"])
        assertEquals("192.0.2.53", parameters["nameserver"])
        assertEquals("ssh-ed25519 AAAA me", parameters["ssh-public-keys"])
    }

    @Test
    fun aContainerNeedsAPasswordOrAKey() {
        assertTrue(ct.isValid(CtStep.General, emptySet()))
        assertFalse(ct.copy(confirmPassword = "secret2").isValid(CtStep.General, emptySet()))
        assertFalse(ct.copy(password = "abc", confirmPassword = "abc").isValid(CtStep.General, emptySet()))
        assertTrue(ct.copy(password = "", confirmPassword = "", sshKeys = "ssh-ed25519 AAAA").isValid(CtStep.General, emptySet()))
        assertFalse(ct.copy(password = "", confirmPassword = "").isValid(CtStep.General, emptySet()))
        assertFalse(ct.copy(hostname = "").isValid(CtStep.General, emptySet()))
        assertFalse(ct.copy(template = "").isValid(CtStep.Template, emptySet()))
        assertFalse(ct.copy(ipMode = IpMode.Static, address = "192.0.2.20").isValid(CtStep.Network, emptySet()))
    }
}
