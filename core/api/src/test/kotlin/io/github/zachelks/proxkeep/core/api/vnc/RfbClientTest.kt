package io.github.zachelks.proxkeep.core.api.vnc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The RFB client against a scripted server, over in-memory pipes. */
class RfbClientTest {

    private val toClient = PipedOutputStream()
    private val clientInput = PipedInputStream(toClient, 1 shl 20)
    private val server = DataOutputStream(toClient)
    private val sent = LinkedBlockingQueue<ByteArray>()
    private val events = CopyOnWriteArrayList<Any>()
    private var failure: Throwable? = null
    private lateinit var worker: Thread

    private val listener = object : RfbClient.Listener {
        override fun onConnected(desktop: DesktopInfo) { events += desktop }
        override fun onResized(width: Int, height: Int) { events += "resized ${width}x$height" }
        override fun onUpdated(region: Region) { events += region }
        override fun onCursor(cursor: Cursor) { events += cursor }
        override fun onBell() { events += "bell" }
        override fun onClipboard(text: String) { events += "clipboard $text" }
    }

    private fun start(password: String? = null): RfbClient {
        val client = RfbClient(clientInput, { sent.put(it) }, password, listener)
        worker = thread(name = "rfb-test") {
            try {
                client.run()
            } catch (e: Throwable) {
                failure = e
            }
        }
        return client
    }

    @AfterTest
    fun tearDown() {
        runCatching { toClient.close() }
        worker.join(5_000)
    }

    private fun next(): ByteArray = assertNotNull(sent.poll(5, TimeUnit.SECONDS), "the client sent nothing")

    private fun finish(): Throwable? {
        toClient.close()
        worker.join(5_000)
        return failure
    }

    /** Version 3.8, the given security, and a [width] by [height] screen called "web". */
    private fun handshake(width: Int = 4, height: Int = 2, password: String? = null): RfbClient {
        val client = start(password)
        server.write("RFB 003.008\n".toByteArray())
        assertEquals("RFB 003.008\n", String(next()))
        if (password == null) {
            server.write(byteArrayOf(1, 1))
            assertContentEquals(byteArrayOf(1), next())
        } else {
            server.write(byteArrayOf(2, 1, 2))
            assertContentEquals(byteArrayOf(2), next())
            val challenge = ByteArray(16) { it.toByte() }
            server.write(challenge)
            assertContentEquals(expectedAuth(challenge, password), next())
        }
        server.writeInt(0)
        assertContentEquals(byteArrayOf(1), next(), "shares the desktop")
        server.writeShort(width)
        server.writeShort(height)
        server.write(ByteArray(16))
        server.writeInt(3)
        server.write("web".toByteArray())
        server.flush()
        val pixelFormat = next()
        assertEquals(20, pixelFormat.size)
        assertEquals(0, pixelFormat[0].toInt(), "SetPixelFormat")
        assertEquals(32, pixelFormat[4].toInt(), "32 bits per pixel")
        val encodings = next()
        assertEquals(2, encodings[0].toInt(), "SetEncodings")
        assertUpdateRequest(next(), incremental = false, width, height)
        return client
    }

    private fun assertUpdateRequest(message: ByteArray, incremental: Boolean, width: Int, height: Int) {
        assertEquals(3, message[0].toInt())
        assertEquals(if (incremental) 1 else 0, message[1].toInt())
        assertEquals(width, ((message[6].toInt() and 0xFF) shl 8) or (message[7].toInt() and 0xFF))
        assertEquals(height, ((message[8].toInt() and 0xFF) shl 8) or (message[9].toInt() and 0xFF))
    }

    private fun rectangle(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        server.writeShort(x)
        server.writeShort(y)
        server.writeShort(w)
        server.writeShort(h)
        server.writeInt(encoding)
    }

    private fun update(rectangles: Int, body: () -> Unit) {
        server.write(byteArrayOf(0, 0))
        server.writeShort(rectangles)
        body()
        server.flush()
    }

    private fun pixel(rgb: Int) = byteArrayOf((rgb and 0xFF).toByte(), (rgb shr 8 and 0xFF).toByte(), (rgb shr 16 and 0xFF).toByte(), 0)

    @Test
    fun aPasswordProtectedServerIsJoined() {
        handshake(password = "pveticket-longer-than-8")

        assertEquals(DesktopInfo("web", 4, 2), events.single())
    }

    @Test
    fun aRefusedPasswordSaysWhy() {
        start("wrong")
        server.write("RFB 003.008\n".toByteArray())
        server.write(byteArrayOf(1, 2))
        server.write(ByteArray(16))
        server.writeInt(1)
        server.writeInt(21)
        server.write("Authentication failed".toByteArray())
        server.flush()
        worker.join(5_000)

        val error = assertIs<RfbException>(failure)
        assertEquals("Authentication failed", error.message)
    }

    @Test
    fun anOldServerChoosesTheSecurityItself() {
        start()
        server.write("RFB 003.003\n".toByteArray())
        assertEquals("RFB 003.003\n", String(next()))
        server.writeInt(1)
        server.writeShort(1)
        server.writeShort(1)
        server.write(ByteArray(16))
        server.writeInt(0)
        server.flush()

        assertContentEquals(byteArrayOf(1), next(), "no security result for 3.3 without a password")
    }

    @Test
    fun aServerWithoutSupportedSecurityIsRefused() {
        start("pw")
        server.write("RFB 003.008\n".toByteArray())
        server.write(byteArrayOf(1, 19))
        server.flush()
        worker.join(5_000)

        assertIs<RfbException>(failure)
    }

    @Test
    fun somethingElseIsNotAVncServer() {
        start()
        server.write("HTTP/1.1 400".toByteArray())
        server.flush()
        worker.join(5_000)

        assertEquals("Not a VNC server", failure?.message)
    }

    @Test
    fun rawRectanglesAreDrawnAndTheNextUpdateIsAskedFor() {
        val client = handshake()

        update(1) {
            rectangle(1, 0, 2, 2, 0)
            listOf(0xFF0000, 0x00FF00, 0x0000FF, 0x123456).forEach { server.write(pixel(it)) }
        }

        assertUpdateRequest(next(), incremental = true, 4, 2)
        assertEquals(Region(1, 0, 2, 2), events.last())
        val fb = client.framebuffer
        assertEquals(0xFFFF0000.toInt(), fb.pixel(1, 0))
        assertEquals(0xFF00FF00.toInt(), fb.pixel(2, 0))
        assertEquals(0xFF123456.toInt(), fb.pixel(2, 1))
        assertEquals(0, fb.pixel(0, 0), "untouched")
    }

    @Test
    fun copyRectMovesPixelsEvenWhenTheyOverlap() {
        val client = handshake(width = 4, height = 1)
        update(1) {
            rectangle(0, 0, 4, 1, 0)
            listOf(1, 2, 3, 4).forEach { server.write(pixel(it)) }
        }
        next()

        update(1) {
            rectangle(1, 0, 3, 1, 1)
            server.writeShort(0)
            server.writeShort(0)
        }
        next()

        assertEquals(listOf(1, 1, 2, 3), (0 until 4).map { client.framebuffer.pixel(it, 0) and 0xFFFFFF })
    }

    @Test
    fun zrleTilesOfEveryKindAreDecoded() {
        val client = handshake(width = 70, height = 3)
        val encoder = ZrleTestEncoder()

        // One rectangle, two tiles: a 64-wide tile and a 6-wide one.
        update(1) {
            rectangle(0, 0, 70, 3, 16)
            encoder.writeRect(server) {
                // Tile 1: plain RLE, 100 red then the rest green.
                write(128)
                cpixel(0xFF0000)
                run(100)
                cpixel(0x00FF00)
                run(64 * 3 - 100)
                // Tile 2: a two-color packed palette, alternating per pixel.
                write(2)
                cpixel(0x000000)
                cpixel(0xFFFFFF)
                repeat(3) { write(0b01010100) }
            }
        }
        next()

        val fb = client.framebuffer
        assertEquals(0xFF0000, fb.pixel(0, 0) and 0xFFFFFF)
        assertEquals(0xFF0000, fb.pixel(35, 1) and 0xFFFFFF, "pixel 99 of the tile")
        assertEquals(0x00FF00, fb.pixel(36, 1) and 0xFFFFFF, "pixel 100 of the tile")
        assertEquals(listOf(0, 0xFFFFFF, 0, 0xFFFFFF, 0, 0xFFFFFF), (64 until 70).map { fb.pixel(it, 2) and 0xFFFFFF })

        // A second rectangle continues the same compressed stream.
        update(1) {
            rectangle(0, 0, 3, 1, 16)
            encoder.writeRect(server) {
                write(1)
                cpixel(0x0000FF)
            }
        }
        next()
        assertEquals(0x0000FF, fb.pixel(2, 0) and 0xFFFFFF)

        update(1) {
            rectangle(0, 1, 4, 1, 16)
            encoder.writeRect(server) {
                // Palette RLE: index 1 for three pixels, then index 0 once.
                write(130)
                cpixel(0x111111)
                cpixel(0x222222)
                write(0x81)
                run(3)
                write(0x00)
            }
        }
        next()
        assertEquals(listOf(0x222222, 0x222222, 0x222222, 0x111111), (0 until 4).map { fb.pixel(it, 1) and 0xFFFFFF })

        update(1) {
            rectangle(0, 2, 2, 1, 16)
            encoder.writeRect(server) {
                write(0)
                cpixel(0xABCDEF)
                cpixel(0x010203)
            }
        }
        next()
        assertEquals(listOf(0xABCDEF, 0x010203), (0 until 2).map { fb.pixel(it, 2) and 0xFFFFFF })
    }

    @Test
    fun theServerCanResizeTheScreen() {
        val client = handshake()

        update(1) { rectangle(0, 0, 8, 6, -223) }

        assertUpdateRequest(next(), incremental = true, 8, 6)
        assertTrue("resized 8x6" in events)
        assertEquals(8, client.framebuffer.width)
    }

    @Test
    fun thePointerShapeIsDrawnByTheClient() {
        handshake()

        update(1) {
            rectangle(1, 0, 2, 1, -239)
            server.write(pixel(0xFF0000))
            server.write(pixel(0x00FF00))
            server.write(byteArrayOf(0b10000000.toByte()))
        }
        next()

        val cursor = events.filterIsInstance<Cursor>().single()
        assertEquals(1, cursor.hotspotX)
        assertEquals(listOf(0xFFFF0000.toInt(), 0), cursor.pixels.toList(), "masked pixels are transparent")
    }

    @Test
    fun theBellAndTheGuestsClipboardArePassedOn() {
        handshake()

        server.write(2)
        server.write(byteArrayOf(3, 0, 0, 0))
        server.writeInt(5)
        server.write("héllo".toByteArray(Charsets.ISO_8859_1))
        server.flush()

        assertTrue(finish() is EOFException)
        assertEquals(listOf("bell", "clipboard héllo"), events.drop(1))
    }

    @Test
    fun inputIsSentInTheProtocolsLayout() {
        val client = handshake()

        client.sendKey(0xff0d, down = true)
        client.sendPointer(x = 9, y = -1, buttons = 0b100)
        client.sendClipboard("hi")

        assertContentEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0x0d), next())
        assertContentEquals(byteArrayOf(5, 4, 0, 3, 0, 0), next(), "clamped to the screen")
        assertContentEquals(byteArrayOf(6, 0, 0, 0, 0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte()), next())
    }

    @Test
    fun aRectangleOutsideTheScreenIsRefused() {
        handshake()

        update(1) {
            rectangle(3, 0, 2, 1, 0)
            server.write(ByteArray(8))
        }
        worker.join(5_000)

        assertIs<RfbException>(failure)
    }

    /** The VNC challenge answer, worked out independently of the client. */
    private fun expectedAuth(challenge: ByteArray, password: String): ByteArray {
        val key = ByteArray(8)
        password.toByteArray(Charsets.ISO_8859_1).take(8).forEachIndexed { i, b ->
            key[i] = (Integer.reverse(b.toInt() and 0xFF) ushr 24).toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }
}

/** Writes ZRLE rectangles the way a server does, with one zlib stream for the connection. */
private class ZrleTestEncoder {
    private val deflater = Deflater()

    class Tiles {
        val bytes = ByteArrayOutputStream()
        fun write(value: Int) = bytes.write(value)
        fun cpixel(rgb: Int) {
            bytes.write(rgb and 0xFF)
            bytes.write(rgb shr 8 and 0xFF)
            bytes.write(rgb shr 16 and 0xFF)
        }
        fun run(length: Int) {
            var left = length - 1
            while (left >= 255) {
                bytes.write(255)
                left -= 255
            }
            bytes.write(left)
        }
    }

    fun writeRect(out: DataOutputStream, tiles: Tiles.() -> Unit) {
        val raw = Tiles().apply(tiles).bytes.toByteArray()
        deflater.setInput(raw)
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
            compressed.write(buffer, 0, n)
            if (n < buffer.size) break
        }
        out.writeInt(compressed.size())
        out.write(compressed.toByteArray())
    }
}
