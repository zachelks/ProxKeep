package io.github.zachelks.proxkeep.core.api.vnc

import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A remote desktop over the server's websocket relay. The websocket carries
 * the VNC protocol's bytes unchanged in binary messages; [RfbClient] speaks
 * the protocol on a thread of its own.
 */
class VncConnection private constructor(
    private val password: String?,
    private val listener: Listener,
) {
    /** Learns what happens on the desktop, on a background thread. */
    interface Listener : RfbClient.Listener {
        /**
         * The connection is over. Called exactly once, however it ended.
         *
         * @param reason why, when the server or the network gave one
         * @param problem what went wrong in the protocol, which the app words itself
         */
        fun onClosed(reason: String?, problem: RfbProblem?)
    }

    private lateinit var socket: WebSocket
    private val input = StreamQueue()
    private val finished = AtomicBoolean(false)

    /** Sends input to the desktop. Its send methods do nothing once the connection has ended. */
    val client = RfbClient(input, ::send, password, listener)

    /** Ends the connection. [Listener.onClosed] still fires. */
    fun close() {
        socket.close(NORMAL_CLOSURE, null)
        finish(null)
    }

    private fun send(bytes: ByteArray) {
        if (!finished.get()) socket.send(bytes.toByteString())
    }

    private fun finish(reason: String?, problem: RfbProblem? = null) {
        if (!finished.compareAndSet(false, true)) return
        input.close()
        listener.onClosed(reason?.takeIf { it.isNotBlank() }, problem)
    }

    private fun runProtocol() {
        var problem: RfbProblem? = null
        val reason = try {
            client.run()
            null
        } catch (e: EOFException) {
            null
        } catch (e: RfbException) {
            problem = e.problem
            e.serverReason
        } catch (e: IOException) {
            e.message
        }
        socket.close(NORMAL_CLOSURE, null)
        finish(reason, problem)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            thread(name = "vnc", isDaemon = true) { runProtocol() }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = input.offer(bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) = input.offer(text.encodeToByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            // The protocol thread reads what already arrived, then reports the end.
            input.close()
            if (reason.isNotBlank()) finish(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            input.close()
            if (reason.isNotBlank()) finish(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A response here means the server answered the upgrade request with an error.
            val reason = if (response != null) "${response.code} ${response.message}".trim() else t.message
            finish(reason)
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        internal fun connect(http: OkHttpClient, request: Request, password: String?, listener: Listener): VncConnection {
            val connection = VncConnection(password, listener)
            connection.socket = http.newWebSocket(request, connection.SocketListener())
            return connection
        }
    }
}

/** Response of `POST …/vncproxy`. */
@Serializable
internal data class VncProxyTicket(
    @Serializable(with = LenientIntSerializer::class)
    val port: Int,
    val ticket: String,
    /** A one-time VNC password, when the server was asked to generate one. */
    val password: String? = null,
)
