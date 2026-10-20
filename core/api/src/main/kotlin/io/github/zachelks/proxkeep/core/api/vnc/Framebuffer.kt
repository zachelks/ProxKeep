package io.github.zachelks.proxkeep.core.api.vnc

/**
 * The remote screen as ARGB pixels, written by the connection's thread and
 * read by whoever draws it. Every access holds the framebuffer's lock.
 */
class Framebuffer(width: Int, height: Int) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    /** Row-major, `0xAARRGGBB`. Read or write it only inside [withLock]. */
    var pixels: IntArray = IntArray(width * height)
        private set

    private val lock = Any()

    fun <T> withLock(block: Framebuffer.() -> T): T = synchronized(lock) { block() }

    /** Changes the size, clearing every pixel. */
    internal fun resize(newWidth: Int, newHeight: Int) = withLock {
        width = newWidth
        height = newHeight
        pixels = IntArray(newWidth * newHeight)
    }

    internal fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) = withLock {
        for (row in y until y + h) {
            val start = row * width + x
            pixels.fill(color, start, start + w)
        }
    }

    /** Copies a rectangle within the screen. Overlapping source and destination are handled. */
    internal fun copy(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) = withLock {
        val rows = if (srcY < y) (h - 1 downTo 0) else (0 until h)
        for (row in rows) {
            System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (y + row) * width + x, w)
        }
    }

    /** Writes [source], [w] pixels wide, to the rectangle at [x], [y]. */
    internal fun put(x: Int, y: Int, w: Int, h: Int, source: IntArray, sourceOffset: Int = 0) = withLock {
        for (row in 0 until h) {
            System.arraycopy(source, sourceOffset + row * w, pixels, (y + row) * width + x, w)
        }
    }

    fun pixel(x: Int, y: Int): Int = withLock { pixels[y * width + x] }
}

/** A rectangle of the remote screen. */
data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    /** The smallest rectangle covering this one and [other]. */
    fun union(other: Region): Region {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        return Region(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
    }
}

/**
 * The pointer's shape, drawn by the app so it moves without waiting for the
 * server.
 *
 * @property pixels `0xAARRGGBB`, transparent where the pointer has no pixel
 */
class Cursor(val width: Int, val height: Int, val hotspotX: Int, val hotspotY: Int, val pixels: IntArray)
