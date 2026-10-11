package io.github.zachelks.proxkeep.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import io.github.zachelks.proxkeep.core.api.console.ConsoleChannel
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Where a console is in its life. */
sealed interface ConsoleState {
    data object Connecting : ConsoleState

    data object Connected : ConsoleState

    /** @property reason why the connection ended, when the server or network said */
    data class Closed(val reason: String?) : ConsoleState
}

/**
 * One open console: the terminal emulator, the connection feeding it, and
 * the glue between the two.
 *
 * A session lives outside any screen, so it survives the device being
 * rotated and the app being left and reopened. The screen showing it sets
 * [view] while it is on screen.
 */
class ConsoleSession(
    context: Context,
    val title: String,
    val target: ConsoleTarget,
) : TerminalSessionClient {

    val id: String = UUID.randomUUID().toString()

    private val appContext = context.applicationContext
    private val mainThread = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ConsoleState>(ConsoleState.Connecting)
    val state: StateFlow<ConsoleState> = _state.asStateFlow()

    @Volatile
    private var channel: ConsoleChannel? = null
    private var ended = false
    private var columns = 0
    private var rows = 0

    /** The view drawing this session, if it is on screen. Main thread only. */
    var view: TerminalView? = null

    /** The terminal itself. Input typed into it goes to the server; output from the server is fed into it. */
    val terminal: TerminalSession = TerminalSession(
        object : TerminalSession.Transport {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                channel?.send(data, offset, count)
            }

            override fun resize(columns: Int, rows: Int) {
                this@ConsoleSession.columns = columns
                this@ConsoleSession.rows = rows
                channel?.resize(columns, rows)
            }

            override fun close() {
                channel?.close()
            }
        },
        SCROLLBACK_ROWS,
        this,
    )

    /** Receives the connection's events, on a network thread. */
    val channelListener = object : ConsoleChannel.Listener {
        override fun onConnected() {
            mainThread.post {
                _state.value = ConsoleState.Connected
                // The view may have been laid out before the server was ready to hear its size.
                if (columns > 0 && rows > 0) channel?.resize(columns, rows)
            }
        }

        override fun onOutput(data: ByteArray) {
            terminal.onRemoteOutput(data, 0, data.size)
        }

        override fun onClosed(reason: String?) {
            mainThread.post { markClosed(reason) }
        }
    }

    /** Hands the session its connection once it has been started. Main thread only. */
    fun attach(channel: ConsoleChannel) {
        this.channel = channel
        // The person may have closed the console while it was still starting.
        if (ended) channel.close()
    }

    /** Records that the console could not be started at all. Main thread only. */
    fun fail(reason: String?) {
        markClosed(reason)
    }

    /** Ends the session for good. Main thread only. */
    fun end() {
        if (ended) return
        ended = true
        view = null
        terminal.finishIfRunning()
    }

    /** Types the clipboard's text into the terminal. */
    fun paste() {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString().orEmpty()
        if (text.isNotEmpty()) terminal.emulator?.paste(text)
    }

    private fun markClosed(reason: String?) {
        if (_state.value is ConsoleState.Closed) return
        _state.value = ConsoleState.Closed(reason)
        terminal.onRemoteClosed(reason ?: CLOSED_MESSAGE)
    }

    // --- TerminalSessionClient -------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(title, text.orEmpty()))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        view?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    private companion object {
        const val SCROLLBACK_ROWS = 5000

        /** Shown in the terminal itself, which has no access to the app's translations. */
        const val CLOSED_MESSAGE = "Connection closed"
    }
}
