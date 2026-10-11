package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientDoubleSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import kotlinx.serialization.Serializable

/** What a [ClusterResource] row describes. */
enum class ResourceKind {
    Node,
    Qemu,
    Lxc,
    Storage,
    Pool,
    Sdn,
    Unknown;

    companion object {
        fun fromApi(type: String): ResourceKind = when (type) {
            "node" -> Node
            "qemu" -> Qemu
            "lxc" -> Lxc
            "storage" -> Storage
            "pool" -> Pool
            "sdn" -> Sdn
            else -> Unknown
        }
    }
}

/**
 * One row of `GET /cluster/resources`, the call that feeds the overview and
 * the guest, node and storage lists.
 *
 * Which fields are present depends on [type], so everything beyond [id] and
 * [type] is optional. Sizes are in bytes and [uptime] is in seconds.
 */
@Serializable
data class ClusterResource(
    val id: String,
    val type: String,
    val node: String? = null,
    val status: String? = null,
    val name: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val vmid: Int? = null,
    /** CPU use as a fraction of [maxcpu], from 0.0 to 1.0. */
    @Serializable(with = LenientDoubleSerializer::class)
    val cpu: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val maxcpu: Double? = null,
    @Serializable(with = LenientLongSerializer::class)
    val mem: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val maxmem: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val disk: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val maxdisk: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val uptime: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val netin: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val netout: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val diskread: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val diskwrite: Long? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val template: Boolean? = null,
    val hastate: String? = null,
    val pool: String? = null,
    val tags: String? = null,
    val lock: String? = null,
    val storage: String? = null,
    val plugintype: String? = null,
    val content: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val shared: Boolean? = null,
) {
    val kind: ResourceKind
        get() = ResourceKind.fromApi(type)

    val isGuest: Boolean
        get() = kind == ResourceKind.Qemu || kind == ResourceKind.Lxc

    val isTemplate: Boolean
        get() = template == true

    /** [tags] split into individual tags. Servers separate them with `;`, `,` or spaces. */
    val tagList: List<String>
        get() = tags?.split(';', ',', ' ')?.filter { it.isNotBlank() }.orEmpty()
}
