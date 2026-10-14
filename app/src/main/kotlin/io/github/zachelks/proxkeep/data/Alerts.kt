package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.serialization.Serializable

/** Something on a server worth a notification. */
sealed interface Alert {
    val profileId: String
    val serverName: String

    /**
     * A task that ended in an error, such as a failed backup.
     *
     * @property guest what the task acted on, usually a guest ID
     */
    data class TaskFailed(
        override val profileId: String,
        override val serverName: String,
        val upid: String,
        val node: String,
        val type: String,
        val guest: String?,
        val status: String,
    ) : Alert

    data class NodeOffline(override val profileId: String, override val serverName: String, val node: String) : Alert

    data class NodeOnline(override val profileId: String, override val serverName: String, val node: String) : Alert
}

/**
 * What the last check of one server saw, so the next one reports only what is new.
 *
 * @property lastTaskEnd the end time, in seconds since the epoch, of the newest finished task seen
 */
@Serializable
data class ServerAlertState(
    val lastTaskEnd: Long = 0,
    val offlineNodes: Set<String> = emptySet(),
)

/** Keeps [ServerAlertState] between checks, by profile ID. */
interface AlertStore {
    fun read(): Map<String, ServerAlertState>

    fun write(state: Map<String, ServerAlertState>)
}

/** Which alerts the person wants. */
data class AlertKinds(val failedTasks: Boolean, val offlineNodes: Boolean)

/**
 * Compares a server's tasks and nodes with what the last check saw.
 *
 * The first check of a server only records what it sees, so a newly
 * watched server does not report its whole history.
 */
object AlertChecker {

    suspend fun check(
        profile: ServerProfile,
        client: PveClient,
        previous: ServerAlertState?,
        kinds: AlertKinds,
    ): Pair<List<Alert>, ServerAlertState> {
        val tasks = if (kinds.failedTasks) client.clusterTasks() else emptyList()
        val nodes = if (kinds.offlineNodes) client.clusterResources().filter { it.kind == ResourceKind.Node } else emptyList()

        val finished = tasks.filter { !it.isRunning && it.endtime != null }
        val newestEnd = finished.maxOfOrNull { it.endtime!! } ?: previous?.lastTaskEnd ?: 0
        val offline = nodes.filter { it.status != "online" }.mapNotNull { it.node }.toSet()
        val next = ServerAlertState(
            lastTaskEnd = maxOf(newestEnd, previous?.lastTaskEnd ?: 0),
            offlineNodes = if (kinds.offlineNodes) offline else previous?.offlineNodes.orEmpty(),
        )
        if (previous == null) return emptyList<Alert>() to next

        val alerts = mutableListOf<Alert>()
        if (kinds.failedTasks) {
            finished.filter { it.failed && (it.endtime ?: 0) > previous.lastTaskEnd }
                .sortedBy { it.endtime }
                .forEach { task ->
                    alerts += Alert.TaskFailed(profile.id, profile.name, task.upid, task.node, task.type, task.id, task.status.orEmpty())
                }
        }
        if (kinds.offlineNodes) {
            (offline - previous.offlineNodes).sorted().forEach { alerts += Alert.NodeOffline(profile.id, profile.name, it) }
            val known = nodes.mapNotNull { it.node }.toSet()
            (previous.offlineNodes - offline).filter { it in known }.sorted().forEach { alerts += Alert.NodeOnline(profile.id, profile.name, it) }
        }
        return alerts to next
    }
}
