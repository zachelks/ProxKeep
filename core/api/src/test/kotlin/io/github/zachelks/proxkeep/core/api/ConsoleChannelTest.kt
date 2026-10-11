package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.console.ConsoleChannel
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConsoleChannelTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PveClient
    private val scope = CoroutineScope(Dispatchers.Default)

    private val sessionTicket = "PVE:root@pam:6712F3A1::c2lnbmF0dXJl"
    private val consoleTicket = "PVEVNC:6712F3A1::dGVybQ+/=="

    /** Everything the client sent over the websocket, as text. */
    private val received = LinkedBlockingQueue<String>()

    /** What the client's listener was told. */
    private val events = LinkedBlockingQueue<String>()

    private val listener = object : ConsoleChannel.Listener {
        override fun onConnected() {
            events.add("connected")
        }

        override fun onOutput(data: ByteArray) {
            events.add("output:" + data.decodeToString())
        }

        override fun onClosed(reason: String?) {
            events.add("closed:$reason")
        }
    }

    /**
     * A server-side terminal that greets with [greeting] once the client has
     * introduced itself and, if [gate] is given, once the gate has opened.
     */
    private fun terminal(greeting: String, gate: CountDownLatch? = null) = object : WebSocketListener() {
        var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (text.endsWith("\n")) {
                gate?.await(5, TimeUnit.SECONDS)
                webSocket.send(greeting.encodeUtf8())
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received.add(bytes.utf8())
        }
    }

    private fun next(queue: LinkedBlockingQueue<String>): String? = queue.poll(5, TimeUnit.SECONDS)

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PveClient(server.url("/api2/json/"), OkHttpClient())
            .withCredentials(TicketCredentials("root@pam", sessionTicket, "csrf-token"))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun enqueueTermProxy() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"port":"5900","ticket":"$consoleTicket","user":"root@pam","upid":"UPID:pve1:1:2:3:vncshell::root@pam:"}}""",
            ),
        )
    }

    @Test
    fun nodeShellConnectsAndCarriesInputAndOutput(): Unit = runBlocking {
        enqueueTermProxy()
        val terminal = terminal(greeting = "OKroot@pve1:~# ")
        server.enqueue(MockResponse().withWebSocketUpgrade(terminal))

        val channel = client.openConsole(ConsoleTarget.NodeShell("pve1"), scope, listener)

        // The client introduces itself with the one-time ticket; the server's OK is not terminal output.
        assertEquals("root@pam:$consoleTicket\n", next(received))
        assertEquals("connected", next(events))
        assertEquals("output:root@pve1:~# ", next(events))

        channel.send("ls -l\n".encodeToByteArray(), 0, 6)
        assertEquals("0:6:ls -l\n", next(received))

        // The count is in bytes, not characters.
        val accented = "é".encodeToByteArray()
        channel.send(accented, 0, accented.size)
        assertEquals("0:2:é", next(received))

        channel.resize(120, 40)
        assertEquals("1:120:40:", next(received))

        terminal.socket!!.send("total 0\r\n".encodeUtf8())
        assertEquals("output:total 0\r\n", next(events))

        channel.close()
        assertEquals("closed:null", next(events))

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/api2/json/nodes/pve1/termproxy", start.path)
        assertEquals("csrf-token", start.getHeader("CSRFPreventionToken"))

        val upgrade = server.takeRequest()
        assertEquals(listOf("api2", "json", "nodes", "pve1", "vncwebsocket"), upgrade.requestUrl!!.pathSegments)
        assertEquals("5900", upgrade.requestUrl!!.queryParameter("port"))
        // The ticket's "+", "/" and "=" survive the trip through the query string.
        assertEquals(consoleTicket, upgrade.requestUrl!!.queryParameter("vncticket"))
        assertEquals("PVEAuthCookie=$sessionTicket", upgrade.getHeader("Cookie"))
        assertEquals("binary", upgrade.getHeader("Sec-WebSocket-Protocol"))
    }

    @Test
    fun guestConsolesUseTheGuestPath(): Unit = runBlocking {
        enqueueTermProxy()
        server.enqueue(MockResponse().withWebSocketUpgrade(terminal(greeting = "OK")))

        client.openConsole(ConsoleTarget.Guest("pve1", ResourceKind.Lxc, 101), scope, listener)

        assertEquals("connected", next(events))
        assertEquals("/api2/json/nodes/pve1/lxc/101/termproxy", server.takeRequest().path)
        assertEquals(
            listOf("api2", "json", "nodes", "pve1", "lxc", "101", "vncwebsocket"),
            server.takeRequest().requestUrl!!.pathSegments,
        )
    }

    @Test
    fun inputBeforeTheServerAcceptsIsNotSent(): Unit = runBlocking {
        enqueueTermProxy()
        val accept = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(terminal(greeting = "OK", gate = accept)))

        val channel = client.openConsole(ConsoleTarget.NodeShell("pve1"), scope, listener)
        assertEquals("root@pam:$consoleTicket\n", next(received))
        channel.send("early".encodeToByteArray(), 0, 5)
        channel.resize(80, 24)
        accept.countDown()

        assertEquals("connected", next(events))
        channel.send("x".encodeToByteArray(), 0, 1)
        assertEquals("0:1:x", next(received))
    }

    @Test
    fun aServerThatDoesNotSayOkIsRefused(): Unit = runBlocking {
        enqueueTermProxy()
        server.enqueue(MockResponse().withWebSocketUpgrade(terminal(greeting = "NO")))

        client.openConsole(ConsoleTarget.NodeShell("pve1"), scope, listener)

        assertEquals("closed:The server refused the console connection", next(events))
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aRejectedUpgradeReportsTheServersReason(): Unit = runBlocking {
        enqueueTermProxy()
        server.enqueue(MockResponse().setStatus("HTTP/1.1 401 permission denied - invalid PVE ticket"))

        client.openConsole(ConsoleTarget.NodeShell("pve1"), scope, listener)

        assertEquals("closed:401 permission denied - invalid PVE ticket", next(events))
    }

    @Test
    fun aRefusalToStartTheTerminalIsAnApiError(): Unit = runBlocking {
        server.enqueue(MockResponse().setStatus("HTTP/1.1 403 Permission check failed (/nodes/pve1, Sys.Console)").setBody("""{"data":null}"""))

        val error = assertFailsWith<PveApiException> {
            client.openConsole(ConsoleTarget.NodeShell("pve1"), scope, listener)
        }

        assertEquals(403, error.statusCode)
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun theServerHangingUpClosesTheChannel(): Unit = runBlocking {
        enqueueTermProxy()
        val terminal = terminal(greeting = "OK")
        server.enqueue(MockResponse().withWebSocketUpgrade(terminal))

        client.openConsole(ConsoleTarget.NodeShell("pve1"), scope, listener)
        assertEquals("connected", next(events))

        terminal.socket!!.close(1000, "logout")
        assertEquals("closed:logout", next(events))
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun inputIsFramedWithItsByteCount() {
        val data = "xxabcyy".encodeToByteArray()
        assertEquals("0:3:abc", ConsoleChannel.frameInput(data, 2, 3).utf8())
        assertEquals("0:0:", ConsoleChannel.frameInput(data, 0, 0).utf8())
    }
}
