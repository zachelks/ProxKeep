package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** A change to a guest's life beyond power: snapshots, backups, moves, copies and removal. */
enum class LifecycleOperation {
    TakeSnapshot,
    RollBack,
    DeleteSnapshot,
    Backup,
    Restore,
    Migrate,
    Clone,
    ConvertToTemplate,
    Delete,
}

/** How a lifecycle operation ended, for a brief message on screen. */
data class LifecycleEvent(
    val operation: LifecycleOperation,
    val guestName: String,
    val succeeded: Boolean,
    /** The server's explanation when the operation failed. */
    val detail: String? = null,
)

/** What a lifecycle operation needs to know about its guest. */
data class GuestRef(val node: String, val kind: ResourceKind, val vmid: Int)

/**
 * Runs lifecycle operations and follows each one's task to its end, like
 * [GuestActions] does for power actions. A guest runs one at a time.
 */
class GuestLifecycle(
    private val servers: ServerRepository,
    private val refresh: RefreshSignal,
    private val scope: CoroutineScope,
) {
    private val _busy = MutableStateFlow<Map<BusyGuest, LifecycleOperation>>(emptyMap())
    val busy: StateFlow<Map<BusyGuest, LifecycleOperation>> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<LifecycleEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<LifecycleEvent> = _events.asSharedFlow()

    /**
     * Starts [operation] on a guest of the signed-in server. [start] makes the
     * call and returns the task's ID, or null when the server finished at once.
     *
     * @return false when the guest is busy or incomplete, and nothing started
     */
    fun run(
        profileId: String,
        resource: ClusterResource,
        operation: LifecycleOperation,
        start: suspend (PveClient, GuestRef) -> String?,
    ): Boolean {
        val node = resource.node ?: return false
        val vmid = resource.vmid ?: return false
        val key = BusyGuest(profileId, resource.id)
        if (_busy.value.containsKey(key)) return false
        val guestName = resource.name ?: vmid.toString()
        val guest = GuestRef(node, resource.kind, vmid)

        _busy.update { it + (key to operation) }
        scope.launch {
            try {
                val outcome = servers.request { client ->
                    val upid = start(client, guest) ?: return@request null
                    // The task runs on the node in its ID, which for a migration is the source.
                    client.awaitTask(upidNode(upid) ?: node, upid, pollIntervalMs = POLL_MS)
                }
                val succeeded = outcome?.succeeded ?: true
                _events.emit(LifecycleEvent(operation, guestName, succeeded, outcome?.exitstatus.takeUnless { succeeded }))
            } catch (e: PveApiException) {
                if (!e.isAuthenticationFailure) _events.emit(LifecycleEvent(operation, guestName, false, e.statusMessage))
            } catch (e: IOException) {
                _events.emit(LifecycleEvent(operation, guestName, false, e.message))
            } finally {
                _busy.update { it - key }
            }
            refresh.request()
        }
        return true
    }

    private companion object {
        const val EVENT_BUFFER = 16
        const val POLL_MS = 2_000L

        /** The node a task runs on, from `UPID:node:...`. */
        fun upidNode(upid: String): String? = upid.split(':').getOrNull(1)?.takeIf { it.isNotEmpty() }
    }
}
