package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.awaitIfTask
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
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

/** The outcome of a power action, for a brief message on screen. */
data class ActionEvent(
    val action: GuestAction,
    val guestName: String,
    val succeeded: Boolean,
    /** The server's explanation when the action failed. */
    val detail: String? = null,
)

/** A guest with a power action in progress. */
data class BusyGuest(val profileId: String, val resourceId: String)

/**
 * Runs power actions and follows each one's task to its end. It belongs to
 * the app, not to a screen, so leaving the screen does not lose the outcome.
 */
class GuestActions(
    private val servers: ServerRepository,
    private val refresh: RefreshSignal,
    private val scope: CoroutineScope,
) {
    private val _busy = MutableStateFlow<Map<BusyGuest, GuestAction>>(emptyMap())

    /** Guests with an action in progress. Keyed by server too, since guest IDs repeat across servers. */
    val busy: StateFlow<Map<BusyGuest, GuestAction>> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<ActionEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<ActionEvent> = _events.asSharedFlow()

    /** Starts [action] on a guest of the signed-in server. A guest runs one action at a time. */
    fun run(profileId: String, resource: ClusterResource, action: GuestAction) {
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        val key = BusyGuest(profileId, resource.id)
        if (_busy.value.containsKey(key)) return
        val guestName = resource.name ?: vmid.toString()

        _busy.update { it + (key to action) }
        scope.launch {
            try {
                val result = servers.request { client -> client.awaitIfTask(client.guestAction(node, resource.kind, vmid, action)) }
                val succeeded = result?.succeeded ?: true
                _events.emit(ActionEvent(action, guestName, succeeded, result?.exitstatus.takeUnless { succeeded }))
            } catch (e: PveApiException) {
                if (!e.isAuthenticationFailure) {
                    _events.emit(ActionEvent(action, guestName, succeeded = false, detail = e.statusMessage))
                }
            } catch (e: IOException) {
                _events.emit(ActionEvent(action, guestName, succeeded = false, detail = e.message))
            } finally {
                _busy.update { it - key }
            }
            refresh.request()
        }
    }

    private companion object {
        const val EVENT_BUFFER = 16
    }
}
