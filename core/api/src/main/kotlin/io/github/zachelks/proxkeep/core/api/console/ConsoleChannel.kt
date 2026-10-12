package io.github.zachelks.proxkeep.core.api.console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live connection to a text console on the server.
 *
 * The server relays a websocket to a terminal process. The conversation, as
 * spoken by the server's own web console, is:
 *
 * 1. The client sends `user:ticket\n`.
 * 2. The server answers with the two bytes `OK`. Everything after them, and
 *    every later message, is terminal output.
 * 3. The client sends typed bytes as `0:<byte count>:<bytes>`, a new size as
 *    `1:<columns>:<rows>:`, and `2` every 30 seconds to keep the connection
 *    open.
 */
class ConsoleChannel private constructor(
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val ticket: TermProxyTicket,
) {
    /** Receives the channel's events. Called on a network thread. */
    interface Listener {
        /** The server accepted the connection. Input is carried from now on. */
        fun onConnected()

        /** Bytes the terminal produced. */
        fun onOutput(data: ByteArray)

        /**
         * The connection is over. Called exactly once, however it ended.
         *
         * @param reason why, when the server or the network gave one
         */
        fun onClosed(reason: String?)
    }

    private lateinit var socket: WebSocket

    @Volatile
    private var connected = false
    private val finished = AtomicBoolean(false)
    private var keepAlive: Job? = null

    /** Bytes received before the server's `OK` has been seen in full. */
    private val greeting = Buffer()

    /** Sends typed bytes to the terminal. Ignored until connected. */
    fun send(data: ByteArray, offset: Int, count: Int) {
        if (!connected) return
        var position = offset
        val end = offset + count
        while (position < end) {
            val length = minOf(MAX_INPUT_FRAME, end - position)
            socket.send(frameInput(data, position, length))
            position += length
        }
    }

    /** Tells the terminal its size in character cells. Ignored until connected. */
    fun resize(columns: Int, rows: Int) {
        if (connected) socket.send("1:$columns:$rows:")
    }

    /** Ends the connection. The listener's [Listener.onClosed] still fires. */
    fun close() {
        socket.close(NORMAL_CLOSURE, null)
        finish(null)
    }

    private fun finish(reason: String?) {
        if (!finished.compareAndSet(false, true)) return
        connected = false
        keepAlive?.cancel()
        listener.onClosed(reason?.takeIf { it.isNotBlank() })
    }

    private fun receive(webSocket: WebSocket, data: ByteString) {
        if (connected) {
            listener.onOutput(data.toByteArray())
            return
        }
        greeting.write(data)
        if (greeting.size < GREETING.size) return
        if (greeting.readByteString(GREETING.size.toLong()) != GREETING) {
            webSocket.close(NORMAL_CLOSURE, null)
            finish("The server refused the console connection")
            return
        }
        connected = true
        keepAlive = scope.launch {
            while (true) {
                delay(KEEP_ALIVE_MS)
                webSocket.send("2")
            }
        }
        listener.onConnected()
        if (greeting.size > 0) listener.onOutput(greeting.readByteArray())
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("${ticket.user}:${ticket.ticket}\n")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(webSocket, bytes)

        override fun onMessage(webSocket: WebSocket, text: String) =
            receive(webSocket, text.encodeToByteArray().toByteString())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            finish(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A response here means the server answered the upgrade request with an error.
            val reason = if (response != null) "${response.code} ${response.message}".trim() else t.message
            finish(reason)
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val KEEP_ALIVE_MS = 30_000L
        private const val MAX_INPUT_FRAME = 4096
        private val GREETING = "OK".encodeToByteArray().toByteString()

        /** Frames typed bytes the way the server's terminal process expects them. */
        fun frameInput(data: ByteArray, offset: Int, count: Int): ByteString =
            Buffer().writeUtf8("0:$count:").write(data, offset, count).readByteString()

        internal fun connect(
            http: OkHttpClient,
            request: Request,
            ticket: TermProxyTicket,
            scope: CoroutineScope,
            listener: Listener,
        ): ConsoleChannel {
            val channel = ConsoleChannel(scope, listener, ticket)
            // An idle shell sends nothing for as long as nobody types, so the
            // connection must not be given up for being quiet.
            val patient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            channel.socket = patient.newWebSocket(request, channel.SocketListener())
            return channel
        }
    }
}
