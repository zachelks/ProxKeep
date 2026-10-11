package io.github.zachelks.proxkeep.core.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the QEMU guest agent inside a virtual machine reports about its
 * operating system, from `GET /nodes/{node}/qemu/{vmid}/agent/get-osinfo`.
 *
 * This is the only place the server learns which operating system a guest
 * really runs. The `ostype` configuration key is a category picked by
 * whoever created the guest, not something detected.
 */
@Serializable
data class GuestOsInfo(
    /** Short machine-readable ID, for example `ubuntu` or `mswindows`. */
    val id: String? = null,
    val name: String? = null,
    /** The full product name, for example `Ubuntu 24.04.1 LTS`. */
    @SerialName("pretty-name")
    val prettyName: String? = null,
    val version: String? = null,
    @SerialName("kernel-release")
    val kernelRelease: String? = null,
) {
    /** The best available name, or null if the agent reported none. */
    val displayName: String?
        get() = prettyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(name, version).joinToString(" ").takeIf { it.isNotBlank() }
}

/** Guest agent calls wrap their answer in a `result` field. */
@Serializable
internal data class AgentResult<T>(val result: T? = null)
