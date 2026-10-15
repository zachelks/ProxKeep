package io.github.zachelks.proxkeep.desktop

import io.github.zachelks.proxkeep.core.api.vnc.Cursor
import io.github.zachelks.proxkeep.core.api.vnc.DesktopInfo
import io.github.zachelks.proxkeep.core.api.vnc.Framebuffer
import io.github.zachelks.proxkeep.core.api.vnc.Region
import io.github.zachelks.proxkeep.core.api.vnc.RfbProblem
import io.github.zachelks.proxkeep.core.api.vnc.VncConnection
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Where a remote desktop is in its life. */
sealed interface DesktopState {
    data object Connecting : DesktopState

    data class Connected(val desktop: DesktopInfo) : DesktopState

    /** @property reason why the connection ended, when the server or network said */
    /**
     * @property reason why, in the server's or network's words, when they gave one
     * @property problem what went wrong in the protocol, which the app words itself
     */
    data class Closed(val reason: String?, val problem: RfbProblem? = null) : DesktopState
}

/** Where keys and pointer moves for a desktop go. */
interface DesktopInput {
    /** Presses or releases a key, given as an X11 keysym. */
    fun sendKey(keysym: Int, down: Boolean)

    /** Moves the pointer, with the buttons in [buttons] held. */
    fun sendPointer(x: Int, y: Int, buttons: Int)
}

/** Something that happened on the desktop worth telling the person about. */
sealed interface DesktopEvent {
    /** The guest put [text] on its clipboard. */
    data class Clipboard(val text: String) : DesktopEvent

    data object Bell : DesktopEvent
}

/**
 * One virtual machine's display: the connection to it, the screen it
 * shows, and the pointer. It lives outside any screen, so it survives the
 * device being rotated.
 *
 * @property vmid the guest the display belongs to
 */
class DesktopSession(val title: String, val node: String, val vmid: Int) : VncConnection.Listener, DesktopInput {

    val id: String = UUID.randomUUID().toString()

    private val _state = MutableStateFlow<DesktopState>(DesktopState.Connecting)
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    /** Counts screen changes, so whoever draws the screen knows to look at [takeDamage]. */
    private val _frames = MutableStateFlow(0L)
    val frames: StateFlow<Long> = _frames.asStateFlow()

    private val _cursor = MutableStateFlow<Cursor?>(null)
    val cursor: StateFlow<Cursor?> = _cursor.asStateFlow()

    private val _events = MutableSharedFlow<DesktopEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DesktopEvent> = _events.asSharedFlow()

    private val damage = AtomicReference<Region?>(null)

    @Volatile
    private var connection: VncConnection? = null

    @Volatile
    private var ended = false

    /** The remote screen, or null before the connection has opened. */
    val framebuffer: Framebuffer?
        get() = connection?.client?.framebuffer?.takeIf { _state.value is DesktopState.Connected }

    /** Hands the session its connection once it has been started. */
    fun attach(connection: VncConnection) {
        this.connection = connection
        // The person may have closed the desktop while it was still starting.
        if (ended) connection.close()
    }

    /** Records that the desktop could not be opened at all. */
    fun fail(reason: String?, problem: RfbProblem? = null) {
        if (_state.value !is DesktopState.Closed) _state.value = DesktopState.Closed(reason, problem)
    }

    /** Ends the session for good. */
    fun end() {
        if (ended) return
        ended = true
        connection?.close()
    }

    /** The part of the screen that changed since the last call, or null if nothing did. */
    fun takeDamage(): Region? = damage.getAndSet(null)

    override fun sendKey(keysym: Int, down: Boolean) {
        if (_state.value is DesktopState.Connected) connection?.client?.sendKey(keysym, down)
    }

    override fun sendPointer(x: Int, y: Int, buttons: Int) {
        if (_state.value is DesktopState.Connected) connection?.client?.sendPointer(x, y, buttons)
    }

    fun sendClipboard(text: String) {
        if (_state.value is DesktopState.Connected) connection?.client?.sendClipboard(text)
    }

    // --- VncConnection.Listener, on the connection's thread -----------------

    override fun onConnected(desktop: DesktopInfo) {
        _state.value = DesktopState.Connected(desktop)
    }

    override fun onResized(width: Int, height: Int) {
        val now = _state.value
        if (now is DesktopState.Connected) _state.value = DesktopState.Connected(now.desktop.copy(width = width, height = height))
        markDamaged(Region(0, 0, width, height))
    }

    override fun onUpdated(region: Region) = markDamaged(region)

    override fun onCursor(cursor: Cursor) {
        _cursor.value = cursor
    }

    override fun onBell() {
        _events.tryEmit(DesktopEvent.Bell)
    }

    override fun onClipboard(text: String) {
        _events.tryEmit(DesktopEvent.Clipboard(text))
    }

    override fun onClosed(reason: String?, problem: RfbProblem?) {
        fail(reason, problem)
    }

    private fun markDamaged(region: Region) {
        damage.getAndUpdate { it?.union(region) ?: region }
        _frames.value = _frames.value + 1
    }
}
