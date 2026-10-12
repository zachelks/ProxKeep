package io.github.zachelks.proxkeep.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** How often live data is read again while it is on screen. */
const val LIVE_REFRESH_MS = 10_000L

/**
 * Paces every loader of live data. Each one reloads on its own timer, and all
 * of them reload at once when someone asks, for example after the refresh
 * button or a power action.
 */
class RefreshSignal {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Asks every active loader to reload now. */
    fun request() {
        requests.tryEmit(Unit)
    }

    /**
     * Emits at once, then every [intervalMs] and whenever a reload is
     * requested. A request that arrives while the collector is still busy
     * with the previous tick is kept, not lost.
     */
    fun ticks(intervalMs: Long = LIVE_REFRESH_MS): Flow<Unit> = channelFlow {
        launch { requests.collect { send(Unit) } }
        while (true) {
            send(Unit)
            delay(intervalMs)
        }
    }.conflate()
}
