package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientDoubleSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import kotlinx.serialization.Serializable

/**
 * Response of `GET /nodes/{node}/{qemu|lxc}/{vmid}/status/current`.
 *
 * Carries what the cluster-wide resource list does not: whether a running
 * virtual machine is paused, and whether the guest is locked by an operation
 * such as a backup or migration.
 */
@Serializable
data class GuestStatus(
    val status: String,
    /** The virtual machine monitor's own state, for example `running` or `paused`. Absent for containers. */
    val qmpstatus: String? = null,
    val name: String? = null,
    /** Name of the operation holding a lock on the guest, for example `backup`. */
    val lock: String? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val cpu: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val cpus: Double? = null,
    @Serializable(with = LenientLongSerializer::class)
    val mem: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val maxmem: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val swap: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val maxswap: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val uptime: Long? = null,
) {
    val isRunning: Boolean
        get() = status == "running"

    val isPaused: Boolean
        get() = isRunning && qmpstatus == "paused"
}
