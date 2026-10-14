package io.github.zachelks.proxkeep.core.api.vnc

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** What the server said about its screen when the connection opened. */
data class DesktopInfo(val name: String, val width: Int, val height: Int)

/**
 * A VNC (RFB protocol) client, reading from [input] and writing through
 * [output]. It handles protocol versions 3.3 to 3.8, no authentication or
 * VNC password authentication, and the Raw, CopyRect and ZRLE encodings,
 * with server-side resizes and a client-drawn pointer.
 *
 * [run] does the handshake and then reads server messages until the
 * connection ends; call it on a thread of its own. The send methods may be
 * called from any thread.
 *
 * @param password the VNC password; only its first 8 characters count
 */
class RfbClient(
    input: InputStream,
    private val output: Output,
    private val password: String?,
    private val listener: Listener,
) {
    /** Where the client's bytes go. Must accept writes from several threads. */
    fun interface Output {
        @Throws(IOException::class)
        fun write(bytes: ByteArray)
    }

    /** Learns what happens on the connection, on the connection's thread. */
    interface Listener {
        fun onConnected(desktop: DesktopInfo)

        /** The server changed the screen size. The framebuffer has the new size, and is blank. */
        fun onResized(width: Int, height: Int) = Unit

        /** Pixels in [region] changed. */
        fun onUpdated(region: Region) = Unit

        /** The pointer's shape changed. */
        fun onCursor(cursor: Cursor) = Unit

        fun onBell() = Unit

        /** The guest's clipboard changed. */
        fun onClipboard(text: String) = Unit
    }

    private val input = DataInputStream(input)
    private val zrle = ZrleDecoder()
    private var minorVersion = 8

    /** Set once [run] has finished the handshake. */
    @Volatile
    var framebuffer: Framebuffer = Framebuffer(0, 0)
        private set

    /**
     * Connects and reads until the connection ends.
     *
     * @throws RfbException when the server refuses or breaks the protocol
     * @throws IOException when the connection fails
     */
    fun run() {
        handshake()
        while (true) readServerMessage()
    }

    // --- Handshake ------------------------------------------------------------

    private fun handshake() {
        val version = String(readBytes(VERSION_LENGTH), Charsets.US_ASCII)
        val match = VERSION_PATTERN.matchEntire(version) ?: throw RfbException("Not a VNC server")
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        if (major != 3 || minor < 3) throw RfbException("Unsupported VNC version $major.$minor")
        minorVersion = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        output.write("RFB 003.00$minorVersion\n".toByteArray(Charsets.US_ASCII))

        val security = chooseSecurity()
        if (security == SECURITY_VNC) {
            val challenge = readBytes(CHALLENGE_SIZE)
            output.write(vncAuthResponse(challenge, password.orEmpty()))
        }
        // Version 3.8 always reports the outcome; older versions only after a password.
        if (minorVersion >= 8 || security == SECURITY_VNC) {
            if (input.readInt() != 0) {
                val reason = if (minorVersion >= 8) readString() else ""
                throw RfbException(reason.ifBlank { "The server refused the password" })
            }
        }

        output.write(byteArrayOf(1)) // Share the desktop with other viewers.
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        readBytes(PIXEL_FORMAT_LENGTH) // The server's own format; the client asks for its own below.
        val name = readString()
        framebuffer = Framebuffer(width, height)

        output.write(setPixelFormat())
        output.write(setEncodings())
        listener.onConnected(DesktopInfo(name, width, height))
        requestUpdate(incremental = false)
    }

    private fun chooseSecurity(): Int {
        if (minorVersion == 3) {
            val type = input.readInt()
            if (type == SECURITY_INVALID) throw RfbException(readString())
            return type
        }
        val count = input.readUnsignedByte()
        if (count == 0) throw RfbException(readString())
        val offered = readBytes(count).map { it.toInt() and 0xFF }
        val chosen = when {
            password != null && SECURITY_VNC in offered -> SECURITY_VNC
            SECURITY_NONE in offered -> SECURITY_NONE
            SECURITY_VNC in offered -> SECURITY_VNC
            else -> throw RfbException("The server offers no sign-in this app supports")
        }
        output.write(byteArrayOf(chosen.toByte()))
        return chosen
    }

    // --- Server messages ------------------------------------------------------

    private fun readServerMessage() {
        when (val type = input.readUnsignedByte()) {
            MSG_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
            MSG_COLOUR_MAP -> {
                input.readUnsignedByte()
                input.readUnsignedShort()
                val count = input.readUnsignedShort()
                readBytes(count * 6)
            }
            MSG_BELL -> listener.onBell()
            MSG_CUT_TEXT -> {
                readBytes(3)
                val length = input.readInt()
                if (length < 0 || length > MAX_CUT_TEXT) throw RfbException("The clipboard text is too long")
                listener.onClipboard(String(readBytes(length), Charsets.ISO_8859_1))
            }
            else -> throw RfbException("Unknown server message $type")
        }
    }

    private fun readFramebufferUpdate() {
        input.readUnsignedByte()
        val count = input.readUnsignedShort()
        var damage: Region? = null
        for (i in 0 until count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENCODING_RAW -> {
                    checkBounds(x, y, w, h)
                    val pixels = IntArray(w * h)
                    val row = ByteArray(w * 4)
                    for (r in 0 until h) {
                        input.readFully(row)
                        for (c in 0 until w) pixels[r * w + c] = pixel(row, c * 4)
                    }
                    framebuffer.put(x, y, w, h, pixels)
                }
                ENCODING_COPY_RECT -> {
                    val srcX = input.readUnsignedShort()
                    val srcY = input.readUnsignedShort()
                    checkBounds(x, y, w, h)
                    checkBounds(srcX, srcY, w, h)
                    framebuffer.copy(srcX, srcY, x, y, w, h)
                }
                ENCODING_ZRLE -> {
                    val length = input.readInt()
                    if (length < 0 || length > MAX_COMPRESSED) throw RfbException("A compressed rectangle is too large")
                    val data = readBytes(length)
                    checkBounds(x, y, w, h)
                    zrle.decode(data, x, y, w, h, framebuffer)
                }
                ENCODING_DESKTOP_SIZE -> {
                    framebuffer.resize(w, h)
                    listener.onResized(w, h)
                    damage = Region(0, 0, w, h)
                    continue
                }
                ENCODING_CURSOR -> {
                    readCursor(x, y, w, h)
                    continue
                }
                else -> throw RfbException("The server used encoding $encoding, which was not asked for")
            }
            val region = Region(x, y, w, h)
            damage = damage?.union(region) ?: region
        }
        damage?.let(listener::onUpdated)
        requestUpdate(incremental = true)
    }

    private fun readCursor(hotX: Int, hotY: Int, w: Int, h: Int) {
        val colors = readBytes(w * h * 4)
        val maskRow = (w + 7) / 8
        val mask = readBytes(maskRow * h)
        val pixels = IntArray(w * h) { i ->
            val row = i / w
            val col = i % w
            val visible = (mask[row * maskRow + col / 8].toInt() shr (7 - col % 8)) and 1 == 1
            if (visible) pixel(colors, i * 4) else 0
        }
        listener.onCursor(Cursor(w, h, hotX, hotY, pixels))
    }

    private fun checkBounds(x: Int, y: Int, w: Int, h: Int) {
        val fb = framebuffer
        if (x + w > fb.width || y + h > fb.height) throw RfbException("The server drew outside the screen")
    }

    // --- Client messages ------------------------------------------------------

    /** Presses or releases a key, given as an X11 keysym. */
    fun sendKey(keysym: Int, down: Boolean) {
        output.write(
            ByteBuffer.allocate(8).put(MSG_KEY_EVENT).put(if (down) 1 else 0).putShort(0).putInt(keysym).array(),
        )
    }

    /**
     * Moves the pointer to [x], [y] with [buttons] held: bit 0 is the left
     * button, bit 1 the middle, bit 2 the right, and bits 3 and 4 a wheel
     * step up and down.
     */
    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val fb = framebuffer
        val px = x.coerceIn(0, (fb.width - 1).coerceAtLeast(0))
        val py = y.coerceIn(0, (fb.height - 1).coerceAtLeast(0))
        output.write(
            ByteBuffer.allocate(6).put(MSG_POINTER_EVENT).put(buttons.toByte()).putShort(px.toShort()).putShort(py.toShort()).array(),
        )
    }

    /** Sends text to the guest's clipboard. VNC carries Latin-1 only; other characters become `?`. */
    fun sendClipboard(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        output.write(ByteBuffer.allocate(8 + bytes.size).put(MSG_CLIENT_CUT_TEXT).put(ByteArray(3)).putInt(bytes.size).put(bytes).array())
    }

    private fun requestUpdate(incremental: Boolean) {
        val fb = framebuffer
        output.write(
            ByteBuffer.allocate(10)
                .put(MSG_UPDATE_REQUEST)
                .put(if (incremental) 1 else 0)
                .putShort(0)
                .putShort(0)
                .putShort(fb.width.toShort())
                .putShort(fb.height.toShort())
                .array(),
        )
    }

    private fun setPixelFormat(): ByteArray = ByteBuffer.allocate(20)
        .put(MSG_SET_PIXEL_FORMAT)
        .put(ByteArray(3))
        .put(32) // bits per pixel
        .put(24) // depth
        .put(0) // little-endian
        .put(1) // true color
        .putShort(255).putShort(255).putShort(255)
        .put(16).put(8).put(0)
        .put(ByteArray(3))
        .array()

    private fun setEncodings(): ByteArray {
        val encodings = intArrayOf(ENCODING_ZRLE, ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE, ENCODING_CURSOR)
        val buffer = ByteBuffer.allocate(4 + encodings.size * 4).put(MSG_SET_ENCODINGS).put(0).putShort(encodings.size.toShort())
        encodings.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    // --- Plumbing -------------------------------------------------------------

    private fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        try {
            input.readFully(bytes)
        } catch (e: EOFException) {
            throw EOFException("The connection closed")
        }
        return bytes
    }

    private fun readString(): String {
        val length = input.readInt()
        if (length < 0 || length > MAX_STRING) throw RfbException("The server sent a string that is too long")
        return String(readBytes(length), Charsets.UTF_8)
    }

    /** A 32-bit little-endian pixel in the format the client asked for. */
    private fun pixel(bytes: ByteArray, offset: Int): Int {
        val blue = bytes[offset].toInt() and 0xFF
        val green = bytes[offset + 1].toInt() and 0xFF
        val red = bytes[offset + 2].toInt() and 0xFF
        return OPAQUE or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        val VERSION_PATTERN = Regex("RFB (\\d{3})\\.(\\d{3})\n")
        const val VERSION_LENGTH = 12
        const val PIXEL_FORMAT_LENGTH = 16

        const val SECURITY_INVALID = 0
        const val SECURITY_NONE = 1
        const val SECURITY_VNC = 2

        const val MSG_FRAMEBUFFER_UPDATE = 0
        const val MSG_COLOUR_MAP = 1
        const val MSG_BELL = 2
        const val MSG_CUT_TEXT = 3

        const val MSG_SET_PIXEL_FORMAT: Byte = 0
        const val MSG_SET_ENCODINGS: Byte = 2
        const val MSG_UPDATE_REQUEST: Byte = 3
        const val MSG_KEY_EVENT: Byte = 4
        const val MSG_POINTER_EVENT: Byte = 5
        const val MSG_CLIENT_CUT_TEXT: Byte = 6

        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_ZRLE = 16
        const val ENCODING_DESKTOP_SIZE = -223
        const val ENCODING_CURSOR = -239

        const val MAX_STRING = 64 * 1024
        const val MAX_CUT_TEXT = 1024 * 1024
        const val MAX_COMPRESSED = 64 * 1024 * 1024
    }
}
