package io.github.zachelks.proxkeep.core.api.pdm

import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.encodePathValue
import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientDoubleSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A Proxmox VE cluster or Backup Server that a Datacenter Manager manages,
 * from `GET /remotes`.
 *
 * @property type `pve` or `pbs`
 */
@Serializable
data class Remote(
    val id: String,
    val type: String,
    val authid: String? = null,
    @SerialName("web-url")
    val webUrl: String? = null,
) {
    val isVirtualEnvironment: Boolean
        get() = type == "pve"
}

/**
 * One resource of a remote, from `GET /resources/list`.
 *
 * @property type what it is: `pve-node`, `pve-qemu`, `pve-lxc`, `pve-storage`, `pbs-node` or `pbs-datastore`
 * @property id unique across remotes, such as `remote/prod/guest/100`
 */
@Serializable
data class PdmResource(
    val type: String,
    val id: String,
    val name: String? = null,
    val node: String? = null,
    val storage: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val vmid: Int? = null,
    val status: String? = null,
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
    @Serializable(with = LenientBooleanSerializer::class)
    val template: Boolean = false,
) {
    /** The guest's kind, or null for anything else. */
    val guestKind: ResourceKind?
        get() = when (type) {
            "pve-qemu" -> ResourceKind.Qemu
            "pve-lxc" -> ResourceKind.Lxc
            else -> null
        }

    val isGuest: Boolean
        get() = guestKind != null && vmid != null

    val isNode: Boolean
        get() = type == "pve-node" || type == "pbs-node"

    /** A name to show: the guest's or storage's name, else the node's, else the ID. */
    val label: String
        get() = name ?: storage ?: node ?: id

    /** Nodes report no status when they are reachable on some versions; uptime then says they run. */
    val isOnline: Boolean
        get() = status == "online" || status == "running" || (status == null && (uptime ?: 0) > 0)
}

/**
 * One remote's resources. When the manager cannot reach it, [error] says
 * why and [resources] holds what it last saw, if anything.
 */
@Serializable
data class RemoteResources(
    val remote: String,
    val error: String? = null,
    val resources: List<PdmResource> = emptyList(),
)

suspend fun PveClient.remotes(): List<Remote> =
    PveJson.decodeFromJsonElement(ListSerializer(Remote.serializer()), read("/remotes"))

/** Every remote's resources, as the manager last saw them. */
suspend fun PveClient.remoteResources(): List<RemoteResources> =
    PveJson.decodeFromJsonElement(ListSerializer(RemoteResources.serializer()), read("/resources/list"))

private fun ResourceKind.segment(): String = if (this == ResourceKind.Lxc) "lxc" else "qemu"

/** The path of a guest on a Proxmox VE remote, as the manager relays it. */
fun remoteGuestPath(remote: String, kind: ResourceKind, vmid: Int): String =
    "/pve/remotes/${encodePathValue(remote)}/${kind.segment()}/$vmid"

/** The schema template of [remoteGuestPath] followed by [rest], such as `/remote-migrate`. */
fun remoteGuestTemplate(kind: ResourceKind, rest: String): String = "/pve/remotes/{remote}/${kind.segment()}/{vmid}$rest"

/**
 * Starts, shuts down or stops a guest on a remote. Returns the remote
 * task's ID, which names the remote before a `!`.
 */
suspend fun PveClient.remoteGuestAction(remote: String, kind: ResourceKind, vmid: Int, action: GuestAction): String? {
    val verb = when (action) {
        GuestAction.Start -> "start"
        GuestAction.Shutdown -> "shutdown"
        GuestAction.Stop -> "stop"
        else -> throw IllegalArgumentException("A Datacenter Manager does not relay $action")
    }
    return write("POST", "${remoteGuestPath(remote, kind, vmid)}/$verb", emptyMap())
}
