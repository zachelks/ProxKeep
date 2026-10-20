package io.github.zachelks.proxkeep.desktop

import io.github.zachelks.proxkeep.core.api.PveApiException
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
 * Owns the open remote desktop, at most one at a time. It belongs to the
 * app, not to a screen, so the desktop stays connected while its screen
 * comes and goes.
 */
class DesktopManager(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<DesktopSession?>(null)
    val current: StateFlow<DesktopSession?> = _current.asStateFlow()

    init {
        scope.launch {
            // A desktop belongs to the server it was opened on.
            repository.activeProfileId.drop(1).collect { close() }
        }
    }

    /** Opens a virtual machine's display, replacing any desktop already open. */
    fun open(node: String, vmid: Int, title: String) {
        if (repository.session.value !is Session.Active) return
        close()
        val session = DesktopSession(title, node, vmid)
        _current.value = session
        scope.launch {
            try {
                session.attach(repository.request { it.openDesktop(node, vmid, session) })
            } catch (e: PveApiException) {
                session.fail(e.statusMessage)
            } catch (e: IOException) {
                session.fail(e.message)
            }
        }
    }

    /** Opens a fresh connection to the same display. */
    fun reconnect() {
        val session = _current.value ?: return
        open(session.node, session.vmid, session.title)
    }

    fun close() {
        _current.value?.end()
        _current.value = null
    }
}
