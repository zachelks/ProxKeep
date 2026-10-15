package io.github.zachelks.proxkeep.core.api.vnc

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An input stream fed with chunks of bytes from another thread, such as
 * websocket messages. Reads block until bytes arrive or the stream is
 * closed, after which reads report the end of the stream.
 */
internal class StreamQueue : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = EMPTY
    private var position = 0

    @Volatile
    private var closed = false

    fun offer(bytes: ByteArray) {
        if (!closed && bytes.isNotEmpty()) chunks.put(bytes)
    }

    override fun close() {
        closed = true
        chunks.put(EMPTY)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position >= current.size) {
            val next = try {
                chunks.poll(POLL_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", e)
            }
            if (next == null) continue
            if (next.isEmpty()) {
                // Bytes that arrived before closing are still read; then the stream ends.
                if (closed && chunks.isEmpty()) return -1
                continue
            }
            current = next
            position = 0
        }
        val count = minOf(length, current.size - position)
        System.arraycopy(current, position, buffer, offset, count)
        position += count
        return count
    }

    override fun available(): Int = current.size - position

    private companion object {
        val EMPTY = ByteArray(0)
        const val POLL_SECONDS = 30L
    }
}
