package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientDoubleSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `GET /nodes/{node}/status`. */
@Serializable
data class NodeStatus(
    @Serializable(with = LenientDoubleSerializer::class)
    val cpu: Double? = null,
    val cpuinfo: CpuInfo? = null,
    val memory: UsedTotal? = null,
    val swap: UsedTotal? = null,
    val rootfs: UsedTotal? = null,
    @Serializable(with = LenientLongSerializer::class)
    val uptime: Long? = null,
    /** The 1, 5 and 15 minute load averages, as text. */
    val loadavg: List<String>? = null,
    val kversion: String? = null,
    val pveversion: String? = null,
    @SerialName("boot-info")
    val bootInfo: BootInfo? = null,
)

@Serializable
data class CpuInfo(
    val model: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val cpus: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val sockets: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val cores: Int? = null,
)

@Serializable
data class UsedTotal(
    @Serializable(with = LenientLongSerializer::class)
    val used: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val total: Long? = null,
)

/** @property mode `efi` or `legacy-bios` */
@Serializable
data class BootInfo(
    val mode: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val secureboot: Boolean? = null,
)

/** One row of `GET /nodes/{node}/apt/update`: a package with a newer version available. */
@Serializable
data class PackageUpdate(
    @SerialName("Package")
    val name: String,
    @SerialName("Title")
    val title: String? = null,
    @SerialName("Version")
    val version: String? = null,
    @SerialName("OldVersion")
    val oldVersion: String? = null,
    @SerialName("Origin")
    val origin: String? = null,
    @SerialName("Priority")
    val priority: String? = null,
)

/**
 * One row of `GET /nodes/{node}/services`.
 *
 * @property state `running`, `dead` or another systemd state
 * @property unitState `enabled`, `disabled`, `masked` or `not-found`
 */
@Serializable
data class NodeService(
    val service: String,
    val name: String? = null,
    val desc: String? = null,
    val state: String? = null,
    @SerialName("active-state")
    val activeState: String? = null,
    @SerialName("unit-state")
    val unitState: String? = null,
) {
    val isRunning: Boolean
        get() = state == "running"
}

/** What can be done to a node's service. */
enum class ServiceAction(internal val apiName: String) {
    Start("start"),
    Stop("stop"),
    Restart("restart"),
    Reload("reload"),
}

/** Response of `GET /nodes/{node}/dns`. */
@Serializable
data class NodeDns(
    val search: String? = null,
    val dns1: String? = null,
    val dns2: String? = null,
    val dns3: String? = null,
) {
    val servers: List<String>
        get() = listOfNotNull(dns1, dns2, dns3).filter { it.isNotBlank() }
}

/** Response of `GET /nodes/{node}/time`. */
@Serializable
data class NodeTime(
    val timezone: String? = null,
    /** Seconds since the epoch, as the node's clock reads it. */
    @Serializable(with = LenientLongSerializer::class)
    val time: Long? = null,
)

/**
 * One row of `GET /nodes/{node}/disks/list`: a physical disk.
 *
 * @property health the SMART verdict, such as `PASSED` or `OK`, or `UNKNOWN`
 * @property wearout the percentage of an SSD's life left, or `N/A`
 * @property used what uses the disk, such as `LVM`, `ZFS` or `partitions`
 */
@Serializable
data class PhysicalDisk(
    val devpath: String,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long? = null,
    val model: String? = null,
    val serial: String? = null,
    val vendor: String? = null,
    val type: String? = null,
    val health: String? = null,
    val wearout: String? = null,
    val used: String? = null,
) {
    /** Whether SMART says the disk is failing. Unknown counts as not failing. */
    val isFailing: Boolean
        get() = health != null && health != "PASSED" && health != "OK" && health != "UNKNOWN"
}

/** Response of `GET /nodes/{node}/disks/smart`. */
@Serializable
data class SmartData(
    val health: String? = null,
    /** `ata` for a table of attributes, `text` for a report such as NVMe's. */
    val type: String? = null,
    val attributes: List<SmartAttribute> = emptyList(),
    val text: String? = null,
)

@Serializable
data class SmartAttribute(
    val id: String? = null,
    val name: String,
    @Serializable(with = LenientIntSerializer::class)
    val value: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val worst: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val threshold: Int? = null,
    val raw: String? = null,
    /** `-` when the attribute has never failed. */
    val fail: String? = null,
)

/**
 * One row of `GET /nodes/{node}/certificates/info`.
 *
 * @property notafter seconds since the epoch when the certificate expires
 */
@Serializable
data class NodeCertificate(
    val filename: String,
    val subject: String? = null,
    val issuer: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val notbefore: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val notafter: Long? = null,
    val fingerprint: String? = null,
    val san: List<String> = emptyList(),
)
