package io.github.zachelks.proxkeep.console

import android.content.Context
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns the open consoles. They belong to the app, not to a screen, so each
 * keeps running while the screen showing it comes and goes, and while
 * another console is on screen.
 *
 * Call from the main thread.
 */
class ConsoleManager(
    private val context: Context,
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
) {
    private val _sessions = MutableStateFlow<List<ConsoleSession>>(emptyList())

    /** Every open console, in the order they were opened. */
    val sessions: StateFlow<List<ConsoleSession>> = _sessions.asStateFlow()

    private val _current = MutableStateFlow<ConsoleSession?>(null)

    /** The console on screen, or that was on screen last. */
    val current: StateFlow<ConsoleSession?> = _current.asStateFlow()

    init {
        scope.launch {
            // Consoles belong to the server they were opened on. Switching
            // server or signing out ends them.
            repository.activeProfileId
                .drop(1)
                .collect { closeAll() }
        }
    }

    /**
     * Puts a console for [target] on screen: the one already open, if it is
     * still connected, so opening a shell twice does not start a second one
     * on the server. Otherwise a new console starts. Consoles already open
     * keep running.
     */
    fun open(target: ConsoleTarget, title: String) {
        if (repository.session.value !is Session.Active) return
        _sessions.value.firstOrNull { it.target == target && it.state.value !is ConsoleState.Closed }?.let { open ->
            _current.value = open
            return
        }
        val session = start(target, title)
        _sessions.value = _sessions.value + session
        _current.value = session
    }

    /** Puts [session] on screen. */
    fun select(session: ConsoleSession) {
        if (session in _sessions.value) _current.value = session
    }

    /** Replaces [session] with a fresh console on the same target, in the same place. */
    fun reconnect(session: ConsoleSession? = _current.value) {
        if (session == null || session !in _sessions.value) return
        if (repository.session.value !is Session.Active) return
        session.end()
        val fresh = start(session.target, session.title)
        _sessions.value = _sessions.value.map { if (it === session) fresh else it }
        _current.value = fresh
    }

    /** Ends [session]. The console beside it, if any, comes on screen. */
    fun close(session: ConsoleSession? = _current.value) {
        if (session == null) return
        val before = _sessions.value
        val index = before.indexOf(session)
        if (index < 0) return
        session.end()
        val after = before - session
        _sessions.value = after
        if (_current.value === session) _current.value = after.getOrNull(index) ?: after.lastOrNull()
    }

    /** Repaints every open console in the current [TerminalPalette], dropping colors programs set themselves. */
    fun refreshColors() {
        _sessions.value.forEach { session ->
            session.terminal.emulator?.mColors?.reset()
            session.view?.invalidate()
        }
    }

    fun closeAll() {
        _sessions.value.forEach { it.end() }
        _sessions.value = emptyList()
        _current.value = null
    }

    private fun start(target: ConsoleTarget, title: String): ConsoleSession {
        val session = ConsoleSession(context, title, target)
        scope.launch {
            try {
                session.attach(repository.request { it.openConsole(target, scope, session.channelListener) })
            } catch (e: PveApiException) {
                session.fail(e.statusMessage)
            } catch (e: IOException) {
                session.fail(e.message)
            }
        }
        return session
    }
}
