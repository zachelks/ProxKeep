package io.github.zachelks.proxkeep.core.api.vnc

import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** A remote desktop through the server's websocket relay, end to end. */
class VncConnectionTest {

    private val server = MockWebServer().apply { start() }
    private val client = PveClient(server.url("/api2/json/"), OkHttpClient())
        .withCredentials(ApiTokenCredentials("root@pam!app", "secret"))
    private val events = LinkedBlockingQueue<String>()

    private val listener = object : VncConnection.Listener {
        override fun onConnected(desktop: DesktopInfo) { events += "connected ${desktop.name} ${desktop.width}x${desktop.height}" }
        override fun onUpdated(region: Region) { events += "updated $region" }
        override fun onClosed(reason: String?) { events += "closed $reason" }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun nextEvent(): String = assertNotNull(events.poll(10, TimeUnit.SECONDS), "no event")

    @Test
    fun aDesktopOpensWithTheGeneratedPassword(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"port":"5901","ticket":"PVEVNC:6712F3A1::dGlja2V0","password":"genpass1","user":"root@pam!app"}}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(ScriptedDesktop(expectedPassword = "genpass1")))

        val connection = client.openDesktop("pve1", 100, listener)

        assertEquals("connected web 2x1", nextEvent())
        assertEquals("updated Region(x=0, y=0, width=2, height=1)", nextEvent())
        assertEquals(0xFF336699.toInt(), connection.client.framebuffer.pixel(1, 0))

        val proxy = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/qemu/100/vncproxy", proxy.path)
        assertEquals("websocket=1&generate-password=1", proxy.body.readUtf8())
        val socket = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/qemu/100/vncwebsocket", socket.requestUrl?.encodedPath)
        assertEquals("5901", socket.requestUrl?.queryParameter("port"))
        assertEquals("PVEVNC:6712F3A1::dGlja2V0", socket.requestUrl?.queryParameter("vncticket"))
        assertEquals("PVEAPIToken=root@pam!app=secret", socket.getHeader("Authorization"))

        connection.close()
        assertEquals("closed null", nextEvent())
        assertEquals(null, events.poll(500, TimeUnit.MILLISECONDS), "closed is reported once")
    }

    @Test
    fun withoutAGeneratedPasswordTheTicketIsThePassword(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"port":5901,"ticket":"PVEVNC:abc"}}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(ScriptedDesktop(expectedPassword = "PVEVNC:abc")))

        client.openDesktop("pve1", 100, listener)

        assertEquals("connected web 2x1", nextEvent())
    }

    @Test
    fun aWrongPasswordEndsTheConnectionWithTheReason(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"port":5901,"ticket":"PVEVNC:abc","password":"other"}}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(ScriptedDesktop(expectedPassword = "genpass1")))

        client.openDesktop("pve1", 100, listener)

        assertEquals("closed Authentication failed", nextEvent())
    }

    @Test
    fun aRefusedProxyIsReported(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"data":null,"message":"Permission check failed (/vms/100, VM.Console)"}"""))

        val error = assertFailsWith<PveApiException> { client.openDesktop("pve1", 100, listener) }

        assertEquals(403, error.statusCode)
    }

    /**
     * A VNC server behind the websocket, as QEMU's is: version 3.8, VNC
     * password authentication, a 2×1 screen, and one raw update.
     */
    private class ScriptedDesktop(private val expectedPassword: String) : WebSocketListener() {
        private val incoming = Buffer()
        private var step = 0
        private val challenge = ByteArray(16) { (it * 3).toByte() }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("RFB 003.008\n".toByteArray().toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            incoming.write(bytes)
            while (true) {
                if (!advance(webSocket)) break
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        private fun advance(socket: WebSocket): Boolean = when (step) {
            0 -> take(12) {
                socket.send(byteArrayOf(1, 2).toByteString())
            }
            1 -> take(1) {
                socket.send(challenge.toByteString())
            }
            2 -> take(16) { response ->
                if (response.contentEquals(vncAuthResponse(challenge, expectedPassword))) {
                    socket.send(Buffer().writeInt(0).readByteString())
                } else {
                    socket.send(Buffer().writeInt(1).writeInt(21).writeUtf8("Authentication failed").readByteString())
                }
            }
            3 -> take(1) {
                socket.send(Buffer().writeShort(2).writeShort(1).write(ByteArray(16)).writeInt(3).writeUtf8("web").readByteString())
            }
            4 -> take(20) {}
            5 -> takeEncodings()
            6 -> take(10) {
                val update = Buffer()
                    .writeByte(0).writeByte(0).writeShort(1)
                    .writeShort(0).writeShort(0).writeShort(2).writeShort(1).writeInt(0)
                    .write(byteArrayOf(0, 0, 0, 0))
                    .write(byteArrayOf(0x99.toByte(), 0x66, 0x33, 0))
                socket.send(update.readByteString())
            }
            else -> {
                incoming.clear()
                false
            }
        }

        private fun take(count: Long, then: (ByteArray) -> Unit): Boolean {
            if (incoming.size < count) return false
            val bytes = incoming.readByteArray(count)
            step++
            then(bytes)
            return true
        }

        private fun takeEncodings(): Boolean {
            if (incoming.size < 4) return false
            val count = incoming.peek().apply { skip(2) }.readShort().toInt()
            return take(4L + count * 4) {}
        }
    }
}
