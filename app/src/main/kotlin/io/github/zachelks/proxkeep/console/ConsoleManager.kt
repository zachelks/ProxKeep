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
 * Owns the open console. It belongs to the app, not to a screen, so a
 * console keeps running while the screen showing it comes and goes.
 */
class ConsoleManager(
    private val context: Context,
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<ConsoleSession?>(null)
    val current: StateFlow<ConsoleSession?> = _current.asStateFlow()

    init {
        scope.launch {
            // A console belongs to the server it was opened on. Switching
            // server or signing out ends it.
            repository.activeProfileId
                .drop(1)
                .collect { close() }
        }
    }

    /** Opens a console, replacing any that is already open. */
    fun open(target: ConsoleTarget, title: String) {
        if (repository.session.value !is Session.Active) return
        close()
        val session = ConsoleSession(context, title, target)
        _current.value = session
        scope.launch {
            try {
                session.attach(repository.request { it.openConsole(target, scope, session.channelListener) })
            } catch (e: PveApiException) {
                session.fail(e.statusMessage)
            } catch (e: IOException) {
                session.fail(e.message)
            }
        }
    }

    /** Opens a fresh console on the same target as the current one. */
    fun reconnect() {
        val session = _current.value ?: return
        open(session.target, session.title)
    }

    fun close() {
        _current.value?.end()
        _current.value = null
    }
}
