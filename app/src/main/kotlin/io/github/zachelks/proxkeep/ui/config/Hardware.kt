package io.github.zachelks.proxkeep.ui.config

import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.schema.ParamSchema
import io.github.zachelks.proxkeep.core.api.schema.ParamType
import io.github.zachelks.proxkeep.core.api.schema.PropertyString

/**
 * The property strings the hardware screens edit. The server's schema is
 * used when it is known; these cover what the screens need without it.
 * Keys they do not list are kept as written.
 */
object HardwareFormats {
    val NIC_MODELS = listOf(
        "virtio", "e1000", "e1000e", "rtl8139", "vmxnet3", "e1000-82540em", "e1000-82544gc", "e1000-82545em",
        "i82551", "i82557b", "i82559er", "ne2k_isa", "ne2k_pci", "pcnet",
    )

    val qemuNet = ParamSchema(
        "net[n]", ParamType.String,
        properties = listOf(
            ParamSchema("model", ParamType.String, enum = NIC_MODELS, defaultKey = true, optional = false),
            ParamSchema("macaddr", ParamType.String),
            ParamSchema("bridge", ParamType.String),
            ParamSchema("tag", ParamType.Integer, minimum = 1.0, maximum = 4094.0),
            ParamSchema("firewall", ParamType.Boolean),
        ) + NIC_MODELS.map { ParamSchema(it, ParamType.Other, alias = "macaddr", keyAlias = "model") },
    )

    val lxcNet = ParamSchema(
        "net[n]", ParamType.String,
        properties = listOf(
            ParamSchema("name", ParamType.String, optional = false),
            ParamSchema("bridge", ParamType.String),
            ParamSchema("hwaddr", ParamType.String),
            ParamSchema("ip", ParamType.String),
            ParamSchema("gw", ParamType.String),
            ParamSchema("ip6", ParamType.String),
            ParamSchema("gw6", ParamType.String),
            ParamSchema("tag", ParamType.Integer, minimum = 1.0, maximum = 4094.0),
            ParamSchema("firewall", ParamType.Boolean),
        ),
    )

    val qemuDisk = ParamSchema(
        "disk", ParamType.String,
        properties = listOf(
            ParamSchema("file", ParamType.String, defaultKey = true, optional = false),
            ParamSchema("size", ParamType.String),
            ParamSchema("media", ParamType.String),
        ),
    )

    val lxcVolume = ParamSchema(
        "volume", ParamType.String,
        properties = listOf(
            ParamSchema("volume", ParamType.String, defaultKey = true, optional = false),
            ParamSchema("mp", ParamType.String),
            ParamSchema("size", ParamType.String),
        ),
    )

    val cpu = ParamSchema("cpu", ParamType.String, properties = listOf(ParamSchema("cputype", ParamType.String, defaultKey = true)))

    val memory = ParamSchema("memory", ParamType.String, properties = listOf(ParamSchema("current", ParamType.Integer, defaultKey = true)))

    /** CPU types worth offering first. Any other type the server knows can be typed. */
    val CPU_TYPES = listOf("host", "x86-64-v2-AES", "x86-64-v3", "x86-64-v4", "kvm64", "qemu64", "max")
}

/** A disk bus and how many devices it takes, numbered from 0. */
enum class DiskBus(val key: String, val slots: Int) {
    Scsi("scsi", 31),
    Virtio("virtio", 16),
    Sata("sata", 6),
    Ide("ide", 4),
}

/**
 * One disk, CD drive or container volume.
 *
 * @property volume the volume ID, such as `local-lvm:vm-100-disk-0`, or `none` for an empty CD drive
 * @property mountPath where a container's mount point appears inside it
 */
data class DiskInfo(
    val key: String,
    val volume: String,
    val sizeBytes: Long?,
    val isCdrom: Boolean,
    val mountPath: String? = null,
) {
    val storage: String?
        get() = volume.substringBefore(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    val isUnused: Boolean
        get() = key.startsWith("unused")

    /** EFI variables and TPM state are firmware storage, not disks to grow or detach. */
    val isFirmwareState: Boolean
        get() = key.startsWith("efidisk") || key.startsWith("tpmstate")

    val isRoot: Boolean
        get() = key == "rootfs"

    val canResize: Boolean
        get() = !isCdrom && !isUnused && !isFirmwareState && sizeBytes != null

    val isEmptyDrive: Boolean
        get() = isCdrom && (volume == "none" || volume.isEmpty())
}

/**
 * One network device.
 *
 * @property name a container's interface name inside it, such as `eth0`
 * @property ip a container's IPv4 setting: `dhcp`, `manual` or an address with a prefix
 */
data class NicInfo(
    val key: String,
    val model: String? = null,
    val mac: String? = null,
    val bridge: String? = null,
    val tag: Int? = null,
    val firewall: Boolean = false,
    val name: String? = null,
    val ip: String? = null,
    val gateway: String? = null,
    val ip6: String? = null,
    val gateway6: String? = null,
)

/** What a guest's hardware tab shows, read from its configuration. */
data class Hardware(
    val kind: ResourceKind,
    val sockets: Int,
    val cores: Int,
    val cpuType: String?,
    val cpuLimit: String?,
    val memoryMiB: Long,
    val balloonMiB: Long?,
    val swapMiB: Long?,
    val disks: List<DiskInfo>,
    val cdroms: List<DiskInfo>,
    val nics: List<NicInfo>,
) {
    val totalCores: Int
        get() = sockets * cores

    companion object {
        fun from(kind: ResourceKind, config: GuestConfig): Hardware {
            val volumes = config.disks.map { device ->
                if (kind == ResourceKind.Lxc && (device.bus == "rootfs" || device.bus == "mp")) {
                    val parsed = PropertyString.parse(device.value, HardwareFormats.lxcVolume)
                    DiskInfo(device.key, parsed["volume"].orEmpty(), parsed["size"]?.let(::parseSize), isCdrom = false, mountPath = parsed["mp"])
                } else if (device.bus == "unused") {
                    DiskInfo(device.key, device.value, null, isCdrom = false)
                } else {
                    val parsed = PropertyString.parse(device.value, HardwareFormats.qemuDisk)
                    DiskInfo(device.key, parsed["file"].orEmpty(), parsed["size"]?.let(::parseSize), isCdrom = parsed["media"] == "cdrom")
                }
            }
            val nics = config.networks.map { device ->
                if (kind == ResourceKind.Lxc) {
                    val parsed = PropertyString.parse(device.value, HardwareFormats.lxcNet)
                    NicInfo(
                        key = device.key, mac = parsed["hwaddr"], bridge = parsed["bridge"], tag = parsed["tag"]?.toIntOrNull(),
                        firewall = parsed.flag("firewall") == true, name = parsed["name"], ip = parsed["ip"], gateway = parsed["gw"],
                        ip6 = parsed["ip6"], gateway6 = parsed["gw6"],
                    )
                } else {
                    val parsed = PropertyString.parse(device.value, HardwareFormats.qemuNet)
                    NicInfo(
                        key = device.key, model = parsed["model"], mac = parsed["macaddr"], bridge = parsed["bridge"],
                        tag = parsed["tag"]?.toIntOrNull(), firewall = parsed.flag("firewall") == true,
                    )
                }
            }
            val memory = config["memory"]?.let { PropertyString.parse(it, HardwareFormats.memory)["current"]?.toLongOrNull() }
            return Hardware(
                kind = kind,
                sockets = config.long("sockets")?.toInt() ?: 1,
                cores = config.long("cores")?.toInt() ?: 1,
                cpuType = config["cpu"]?.let { PropertyString.parse(it, HardwareFormats.cpu)["cputype"] },
                cpuLimit = config["cpulimit"],
                memoryMiB = memory ?: DEFAULT_MEMORY_MIB,
                balloonMiB = config.long("balloon"),
                swapMiB = config.long("swap"),
                disks = volumes.filterNot { it.isCdrom },
                cdroms = volumes.filter { it.isCdrom },
                nics = nics,
            )
        }

        /** Proxmox's default when a guest's configuration does not name its memory. */
        const val DEFAULT_MEMORY_MIB = 512L
    }
}

/** Configuration changes for the hardware dialogs to send. */
object HardwareChanges {

    fun processor(kind: ResourceKind, sockets: Int, cores: Int, cpuType: String?, cpuLimit: String?, config: GuestConfig): Map<String, String> =
        buildMap {
            put("cores", cores.toString())
            if (kind == ResourceKind.Qemu) {
                put("sockets", sockets.toString())
                if (!cpuType.isNullOrBlank()) {
                    val current = config["cpu"]?.let { PropertyString.parse(it, HardwareFormats.cpu) } ?: PropertyString.Empty
                    put("cpu", current.with("cputype", cpuType.trim()).encode(HardwareFormats.cpu))
                }
            } else if (cpuLimit != null) {
                put("cpulimit", cpuLimit.ifBlank { "0" })
            }
        }

    /** A virtual machine's memory keeps any other keys its property string has. */
    fun memory(kind: ResourceKind, memoryMiB: Long, balloonMiB: Long?, swapMiB: Long?, config: GuestConfig): Map<String, String> =
        buildMap {
            if (kind == ResourceKind.Qemu) {
                val current = config["memory"]?.let { PropertyString.parse(it, HardwareFormats.memory) } ?: PropertyString.Empty
                put("memory", current.with("current", memoryMiB.toString()).encode(HardwareFormats.memory))
                balloonMiB?.let { put("balloon", it.toString()) }
            } else {
                put("memory", memoryMiB.toString())
                swapMiB?.let { put("swap", it.toString()) }
            }
        }

    /** The first free key on [bus], such as `scsi1`, or null when the bus is full. */
    fun nextKey(bus: String, slots: Int, config: GuestConfig): String? =
        (0 until slots).map { "$bus$it" }.firstOrNull { config[it] == null }

    /** A new disk: the server allocates [sizeGiB] on [storage]. */
    fun newDisk(storage: String, sizeGiB: Int): String = "$storage:$sizeGiB"

    /** A new container mount point. */
    fun newMountPoint(storage: String, sizeGiB: Int, path: String): String = "$storage:$sizeGiB,mp=${path.trim()}"

    /** A CD drive holding [iso], such as `local:iso/debian-13.iso`, or empty when it is null. */
    fun cdrom(iso: String?): String = "${iso ?: "none"},media=cdrom"

    fun qemuNic(existing: String?, model: String, bridge: String, tag: Int?, firewall: Boolean): String {
        val current = existing?.let { PropertyString.parse(it, HardwareFormats.qemuNet) } ?: PropertyString.Empty
        return current
            .with("model", model)
            .with("bridge", bridge)
            .with("tag", tag?.toString())
            .with("firewall", if (firewall) "1" else null)
            .encode(HardwareFormats.qemuNet)
    }

    fun lxcNic(existing: String?, nic: NicInfo): String {
        val current = existing?.let { PropertyString.parse(it, HardwareFormats.lxcNet) } ?: PropertyString.Empty
        return current
            .with("name", nic.name)
            .with("bridge", nic.bridge)
            .with("ip", nic.ip)
            .with("gw", nic.gateway.takeIf { nic.ip != null && nic.ip != "dhcp" && nic.ip != "manual" })
            .with("ip6", nic.ip6)
            .with("gw6", nic.gateway6.takeIf { nic.ip6 != null && nic.ip6 != "dhcp" && nic.ip6 != "auto" && nic.ip6 != "manual" })
            .with("tag", nic.tag?.toString())
            .with("firewall", if (nic.firewall) "1" else null)
            .encode(HardwareFormats.lxcNet)
    }
}

/** Reads a Proxmox size such as `32G` or `512M` as bytes. A plain number counts as bytes. */
fun parseSize(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val unit = trimmed.last().uppercaseChar()
    val factor = when (unit) {
        'K' -> 1L shl 10
        'M' -> 1L shl 20
        'G' -> 1L shl 30
        'T' -> 1L shl 40
        else -> 1L
    }
    val number = if (factor == 1L) trimmed else trimmed.dropLast(1)
    return number.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * factor).toLong() }
}
