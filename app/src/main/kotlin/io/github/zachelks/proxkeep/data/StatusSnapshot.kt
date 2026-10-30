package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.serialization.Serializable

/**
 * The signed-in server at a glance, for the home screen widget and the
 * Quick Settings tile, which show it without opening the app.
 *
 * @property updatedAt milliseconds since the epoch when it was read
 */
@Serializable
data class StatusSnapshot(
    val profileId: String,
    val serverName: String,
    val nodesOnline: Int,
    val nodesTotal: Int,
    val guestsRunning: Int,
    val guestsTotal: Int,
    val updatedAt: Long,
) {
    val allNodesOnline: Boolean
        get() = nodesOnline == nodesTotal

    companion object {
        fun of(profile: ServerProfile, resources: List<ClusterResource>, now: Long): StatusSnapshot {
            val nodes = resources.filter { it.kind == ResourceKind.Node }
            val guests = resources.filter { (it.kind == ResourceKind.Qemu || it.kind == ResourceKind.Lxc) && it.template != true }
            return StatusSnapshot(
                profileId = profile.id,
                serverName = profile.name,
                nodesOnline = nodes.count { it.status == "online" },
                nodesTotal = nodes.size,
                guestsRunning = guests.count { it.status == "running" },
                guestsTotal = guests.size,
                updatedAt = now,
            )
        }
    }
}

/** Keeps the latest [StatusSnapshot] where the widget and tile can read it. */
interface StatusStore {
    fun read(): StatusSnapshot?

    fun write(snapshot: StatusSnapshot?)
}
