package io.github.zachelks.proxkeep.core.api.vnc

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes ZRLE rectangles: zlib-compressed 64×64 tiles, each raw, one
 * color, palette-packed or run-length encoded.
 *
 * The zlib stream runs across every rectangle of the connection, so one
 * decoder belongs to one connection. Pixels are 3-byte compressed pixels,
 * which is what the 32-bit, depth-24 format the client asks for produces.
 */
internal class ZrleDecoder {
    private val inflater = Inflater()
    private val tile = IntArray(TILE_SIZE * TILE_SIZE)

    /** Decodes one rectangle's [compressed] data into [framebuffer]. */
    fun decode(compressed: ByteArray, x: Int, y: Int, width: Int, height: Int, framebuffer: Framebuffer) {
        val data = Reader(inflate(compressed, maxDecodedSize(width, height)))
        var tileY = 0
        while (tileY < height) {
            val th = minOf(TILE_SIZE, height - tileY)
            var tileX = 0
            while (tileX < width) {
                val tw = minOf(TILE_SIZE, width - tileX)
                decodeTile(data, tw, th)
                framebuffer.put(x + tileX, y + tileY, tw, th, tile)
                tileX += TILE_SIZE
            }
            tileY += TILE_SIZE
        }
    }

    /**
     * Inflates one rectangle's data, which can never be longer than [limit];
     * more means the data is damaged or hostile.
     */
    private fun inflate(compressed: ByteArray, limit: Long): ByteArray {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(minOf(compressed.size.toLong() * 4, limit).toInt())
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = inflater.inflate(buffer)
                if (n > 0) out.write(buffer, 0, n)
                if (out.size() > limit) throw RfbException("A compressed rectangle holds more than its pixels")
                // ZRLE never uses a preset dictionary; asking for one would otherwise loop forever.
                if (inflater.needsDictionary()) throw RfbException("The server sent damaged compressed data")
                if (n == 0 && (inflater.needsInput() || inflater.finished())) break
            }
        } catch (e: DataFormatException) {
            throw RfbException("The server sent damaged compressed data", e)
        }
        return out.toByteArray()
    }

    private fun decodeTile(data: Reader, tw: Int, th: Int) {
        val count = tw * th
        when (val type = data.u8()) {
            RAW -> for (i in 0 until count) tile[i] = data.cpixel()
            SOLID -> tile.fill(data.cpixel(), 0, count)
            in 2..16 -> {
                val palette = IntArray(type) { data.cpixel() }
                val bits = when {
                    type == 2 -> 1
                    type <= 4 -> 2
                    else -> 4
                }
                val mask = (1 shl bits) - 1
                for (row in 0 until th) {
                    var byte = 0
                    var left = 0
                    for (col in 0 until tw) {
                        if (left == 0) {
                            byte = data.u8()
                            left = 8
                        }
                        left -= bits
                        tile[row * tw + col] = palette[(byte shr left) and mask]
                    }
                }
            }
            PLAIN_RLE -> {
                var i = 0
                while (i < count) {
                    val color = data.cpixel()
                    val run = data.runLength()
                    checkRun(i, run, count)
                    tile.fill(color, i, i + run)
                    i += run
                }
            }
            in 130..255 -> {
                val palette = IntArray(type - 128) { data.cpixel() }
                var i = 0
                while (i < count) {
                    val index = data.u8()
                    val color = palette.getOrNull(index and 0x7F) ?: throw RfbException("A tile refers to a color it lacks")
                    val run = if (index and 0x80 != 0) data.runLength() else 1
                    checkRun(i, run, count)
                    tile.fill(color, i, i + run)
                    i += run
                }
            }
            else -> throw RfbException("Unknown ZRLE tile type $type")
        }
    }

    /**
     * The most a [width] by [height] rectangle can take once inflated. A
     * pixel takes at most 4 bytes, in a plain run-length tile with runs of
     * one; each tile adds its type byte and at most a 127-color palette.
     */
    private fun maxDecodedSize(width: Int, height: Int): Long {
        val tiles = ((width + TILE_SIZE - 1) / TILE_SIZE).toLong() * ((height + TILE_SIZE - 1) / TILE_SIZE)
        return width.toLong() * height * MAX_BYTES_PER_PIXEL + tiles * MAX_TILE_OVERHEAD
    }

    private fun checkRun(at: Int, run: Int, count: Int) {
        if (at + run > count) throw RfbException("A run goes past the end of its tile")
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun u8(): Int {
            if (position >= bytes.size) throw RfbException("Compressed data ended inside a tile")
            return bytes[position++].toInt() and 0xFF
        }

        /** A compressed pixel: blue, green, red, as the pixel format puts them. */
        fun cpixel(): Int {
            val blue = u8()
            val green = u8()
            val red = u8()
            return OPAQUE or (red shl 16) or (green shl 8) or blue
        }

        fun runLength(): Int {
            var length = 1
            do {
                val b = u8()
                length += b
            } while (b == 255)
            return length
        }
    }

    private companion object {
        const val TILE_SIZE = 64
        const val BUFFER_SIZE = 64 * 1024
        const val RAW = 0
        const val SOLID = 1
        const val PLAIN_RLE = 128
        const val MAX_BYTES_PER_PIXEL = 4
        const val MAX_TILE_OVERHEAD = 1 + 127 * 3
    }
}

internal const val OPAQUE = 0xFF000000.toInt()
