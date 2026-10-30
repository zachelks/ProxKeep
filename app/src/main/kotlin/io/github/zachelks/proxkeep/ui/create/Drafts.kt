package io.github.zachelks.proxkeep.ui.create

import io.github.zachelks.proxkeep.ui.config.DiskBus
import io.github.zachelks.proxkeep.ui.config.HardwareChanges
import io.github.zachelks.proxkeep.ui.config.IpMode
import io.github.zachelks.proxkeep.ui.config.NicInfo

/** The steps of the new virtual machine wizard, in order. */
enum class VmStep { General, System, Disk, Processor, Network, Confirm }

/** The steps of the new container wizard, in order. */
enum class CtStep { General, Template, Disk, Processor, Network, Confirm }

/** What kind of operating system a new virtual machine runs, which sets its defaults. */
enum class GuestOs(val ostype: String) {
    Linux("l26"),
    Windows("win11"),
    Other("other"),
}

/**
 * A new virtual machine as the wizard fills it in.
 *
 * @property iso the installation image, or null for an empty CD drive
 * @property uefi whether to boot with UEFI firmware, which needs an EFI disk
 * @property bridge null for no network device
 */
data class VmDraft(
    val node: String = "",
    val vmid: String = "",
    val name: String = "",
    val os: GuestOs = GuestOs.Linux,
    val iso: String? = null,
    val uefi: Boolean = false,
    val agent: Boolean = true,
    val bus: DiskBus = DiskBus.Scsi,
    val storage: String = "",
    val diskGiB: String = "32",
    val sockets: String = "1",
    val cores: String = "2",
    val cpuType: String = DEFAULT_CPU,
    val memoryMiB: String = "2048",
    val bridge: String? = "vmbr0",
    val vlan: String = "",
    val firewall: Boolean = true,
    val start: Boolean = false,
) {
    /** Windows 11 requires UEFI and a TPM, so choosing Windows turns them on. */
    fun withOs(os: GuestOs): VmDraft = copy(os = os, uefi = if (os == GuestOs.Windows) true else uefi)

    fun isValid(step: VmStep, takenIds: Set<Int>): Boolean = when (step) {
        VmStep.General -> node.isNotEmpty() && isFreeId(vmid, takenIds) && isValidGuestName(name)
        VmStep.System -> true
        VmStep.Disk -> storage.isNotEmpty() && inRange(diskGiB, 1, MAX_DISK_GIB)
        VmStep.Processor -> inRange(sockets, 1, MAX_SOCKETS) && inRange(cores, 1, MAX_CORES) && cpuType.isNotBlank() &&
            inRange(memoryMiB, MIN_MEMORY_MIB, MAX_MEMORY_MIB)
        VmStep.Network -> bridge == null || (bridge.isNotBlank() && (vlan.isEmpty() || inRange(vlan, 1, MAX_VLAN)))
        VmStep.Confirm -> VmStep.entries.filter { it != VmStep.Confirm }.all { isValid(it, takenIds) }
    }

    /** The parameters of `POST /nodes/{node}/qemu`. */
    fun parameters(): Map<String, String> = buildMap {
        put("vmid", vmid)
        if (name.isNotBlank()) put("name", name.trim())
        put("ostype", os.ostype)
        put("ide2", HardwareChanges.cdrom(iso))
        if (uefi) {
            put("bios", "ovmf")
            put("efidisk0", "$storage:1,efitype=4m,pre-enrolled-keys=1")
        }
        if (uefi || os == GuestOs.Windows) put("machine", "q35")
        if (os == GuestOs.Windows) put("tpmstate0", "$storage:1,version=v2.0")
        put("scsihw", "virtio-scsi-single")
        val disk = "${bus.key}0"
        put(disk, HardwareChanges.newDisk(storage, diskGiB.toInt()) + if (bus == DiskBus.Scsi || bus == DiskBus.Virtio) ",iothread=1" else "")
        put("sockets", sockets)
        put("cores", cores)
        put("cpu", cpuType.trim())
        put("memory", memoryMiB)
        if (agent) put("agent", "1")
        bridge?.let { put("net0", HardwareChanges.qemuNic(null, "virtio", it.trim(), vlan.toIntOrNull(), firewall)) }
        put("boot", "order=" + listOfNotNull(disk, "ide2", "net0".takeIf { bridge != null }).joinToString(";"))
        if (start) put("start", "1")
    }

    companion object {
        const val DEFAULT_CPU = "x86-64-v2-AES"
    }
}

/**
 * A new container as the wizard fills it in.
 *
 * @property template the operating system template's volume ID, such as `local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst`
 */
data class CtDraft(
    val node: String = "",
    val vmid: String = "",
    val hostname: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val sshKeys: String = "",
    val unprivileged: Boolean = true,
    val nesting: Boolean = true,
    val template: String = "",
    val storage: String = "",
    val diskGiB: String = "8",
    val cores: String = "1",
    val memoryMiB: String = "512",
    val swapMiB: String = "512",
    val bridge: String = "vmbr0",
    val vlan: String = "",
    val firewall: Boolean = true,
    val ipMode: IpMode = IpMode.Dhcp,
    val address: String = "",
    val gateway: String = "",
    val dnsServers: String = "",
    val searchDomain: String = "",
    val start: Boolean = false,
) {
    /** A password or a key is needed to sign in to the container. */
    val hasCredentials: Boolean
        get() = (password.length >= MIN_PASSWORD && password == confirmPassword) || (password.isEmpty() && sshKeys.isNotBlank())

    fun isValid(step: CtStep, takenIds: Set<Int>): Boolean = when (step) {
        CtStep.General -> node.isNotEmpty() && isFreeId(vmid, takenIds) && hostname.isNotBlank() && isValidGuestName(hostname) && hasCredentials
        CtStep.Template -> template.isNotEmpty()
        CtStep.Disk -> storage.isNotEmpty() && inRange(diskGiB, 1, MAX_DISK_GIB)
        CtStep.Processor -> inRange(cores, 1, MAX_CORES) && inRange(memoryMiB, MIN_MEMORY_MIB, MAX_MEMORY_MIB) && inRange(swapMiB, 0, MAX_MEMORY_MIB)
        CtStep.Network -> bridge.isNotBlank() && (vlan.isEmpty() || inRange(vlan, 1, MAX_VLAN)) &&
            (ipMode != IpMode.Static || '/' in address)
        CtStep.Confirm -> CtStep.entries.filter { it != CtStep.Confirm }.all { isValid(it, takenIds) }
    }

    /** The parameters of `POST /nodes/{node}/lxc`. */
    fun parameters(): Map<String, String> = buildMap {
        put("vmid", vmid)
        put("hostname", hostname.trim())
        put("ostemplate", template)
        if (password.isNotEmpty()) put("password", password)
        if (sshKeys.isNotBlank()) put("ssh-public-keys", sshKeys.trim())
        put("unprivileged", if (unprivileged) "1" else "0")
        if (nesting) put("features", "nesting=1")
        put("rootfs", HardwareChanges.newDisk(storage, diskGiB.toInt()))
        put("cores", cores)
        put("memory", memoryMiB)
        put("swap", swapMiB)
        val nic = NicInfo(
            key = "net0",
            name = "eth0",
            bridge = bridge.trim(),
            tag = vlan.toIntOrNull(),
            firewall = firewall,
            ip = ipMode.value(address.trim()),
            gateway = gateway.trim().ifEmpty { null },
        )
        put("net0", HardwareChanges.lxcNic(null, nic))
        if (dnsServers.isNotBlank()) put("nameserver", dnsServers.trim())
        if (searchDomain.isNotBlank()) put("searchdomain", searchDomain.trim())
        if (start) put("start", "1")
    }
}

/** Whether [text] is a guest ID from 100 up that no guest has. */
fun isFreeId(text: String, taken: Set<Int>): Boolean = text.toIntOrNull()?.let { it in MIN_GUEST_ID..MAX_GUEST_ID && it !in taken } == true

/** Names and host names are DNS names: letters, digits, `-` and `.`, not starting or ending with `-`. Empty is allowed for a name. */
fun isValidGuestName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return true
    return trimmed.length <= MAX_NAME && trimmed.split('.').all { label ->
        label.isNotEmpty() && label.first() != '-' && label.last() != '-' && label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}

private fun inRange(text: String, min: Long, max: Long): Boolean = text.toLongOrNull()?.let { it in min..max } == true

private const val MIN_GUEST_ID = 100
private const val MAX_GUEST_ID = 999_999_999
private const val MAX_NAME = 63
private const val MIN_PASSWORD = 5
private const val MAX_SOCKETS = 4L
private const val MAX_CORES = 8192L
private const val MIN_MEMORY_MIB = 16L
private const val MAX_MEMORY_MIB = 64L * 1024 * 1024
private const val MAX_DISK_GIB = 128L * 1024
private const val MAX_VLAN = 4094L
