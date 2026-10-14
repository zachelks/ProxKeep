package io.github.zachelks.proxkeep.desktop

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import okio.ByteString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopManagerTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var repository: ServerRepository
    private lateinit var manager: DesktopManager

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        repository = fixture.signedInRepository(scope)
        manager = DesktopManager(repository, scope)
        fixture.route(PROXY, """{"data":{"port":5901,"ticket":"PVEVNC:abc"}}""")
        fixture.route(SOCKET) { MockResponse().withWebSocketUpgrade(OpenDesktop()) }
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aDesktopConnectsAndShowsItsScreen(): Unit = runBlocking {
        manager.open("pve1", 100, title = "web")
        val session = assertNotNull(manager.current.value)

        val connected = withTimeout(TIMEOUT_MS) { session.state.first { it is DesktopState.Connected } }
        withTimeout(TIMEOUT_MS) { session.frames.first { it > 0 } }

        assertEquals(2, assertIs<DesktopState.Connected>(connected).desktop.width)
        assertEquals(0xFF336699.toInt(), assertNotNull(session.framebuffer).pixel(1, 0))
        assertNotNull(session.takeDamage())
        assertNull(session.takeDamage(), "damage is taken once")
    }

    @Test
    fun aRefusedDesktopSaysWhy(): Unit = runBlocking {
        fixture.route(PROXY, """{"data":null,"message":"Permission check failed (/vms/100, VM.Console)"}""", code = 403)

        manager.open("pve1", 100, title = "web")
        val closed = withTimeout(TIMEOUT_MS) { manager.current.value!!.state.first { it is DesktopState.Closed } }

        assertEquals(DesktopState.Closed("Permission check failed (/vms/100, VM.Console)"), closed)
    }

    @Test
    fun nothingOpensWhileSignedOut(): Unit = runBlocking {
        repository.signOut()

        manager.open("pve1", 100, title = "web")

        assertNull(manager.current.value)
    }

    @Test
    fun reconnectingOpensAFreshConnection(): Unit = runBlocking {
        manager.open("pve1", 100, title = "web")
        val first = manager.current.value!!

        manager.reconnect()

        val second = assertNotNull(manager.current.value)
        assertTrue(first !== second)
        assertEquals("web", second.title)
        assertEquals(100, second.vmid)
    }

    @Test
    fun switchingServerClosesTheDesktop(): Unit = runBlocking {
        PinnedServer("second").use { second ->
            manager.open("pve1", 100, title = "web")

            repository.saveAndActivate(second.profile, ApiTokenCredentials(second.profile.principal, "secret"))

            assertNull(manager.current.value)
        }
    }

    @Test
    fun inputIsIgnoredUntilConnected() {
        val session = DesktopSession("web", "pve1", 100)

        session.sendKey(Keysyms.RETURN, down = true)
        session.sendPointer(1, 1, 0)

        assertIs<DesktopState.Connecting>(session.state.value)
    }

    /** A VNC server without a password, a 2×1 screen and one raw update. */
    private class OpenDesktop : WebSocketListener() {
        private val incoming = Buffer()
        private var step = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(Buffer().writeUtf8("RFB 003.008\n").readByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            incoming.write(bytes)
            while (true) {
                val needed = when (step) {
                    0 -> 12L
                    1 -> 1L
                    2 -> 1L
                    3 -> 20L
                    4 -> if (incoming.size >= 4) 4L + incoming.peek().apply { skip(2) }.readShort() * 4 else 4L
                    5 -> 10L
                    else -> return incoming.clear()
                }
                if (incoming.size < needed) return
                incoming.skip(needed)
                when (step) {
                    0 -> webSocket.send(Buffer().writeByte(1).writeByte(1).readByteString())
                    1 -> webSocket.send(Buffer().writeInt(0).readByteString())
                    2 -> webSocket.send(Buffer().writeShort(2).writeShort(1).write(ByteArray(16)).writeInt(3).writeUtf8("web").readByteString())
                    5 -> webSocket.send(
                        Buffer().writeByte(0).writeByte(0).writeShort(1)
                            .writeShort(0).writeShort(0).writeShort(2).writeShort(1).writeInt(0)
                            .write(byteArrayOf(0, 0, 0, 0, 0x99.toByte(), 0x66, 0x33, 0))
                            .readByteString(),
                    )
                }
                step++
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val PROXY = "/nodes/pve1/qemu/100/vncproxy"
        const val SOCKET = "/nodes/pve1/qemu/100/vncwebsocket"
    }
}
