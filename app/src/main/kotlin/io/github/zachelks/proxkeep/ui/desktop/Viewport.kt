package io.github.zachelks.proxkeep.ui.desktop

/**
 * How the remote screen sits on the device's screen: a point on the remote
 * screen is drawn at `point * scale + offset`.
 */
data class Viewport(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {

    fun toDesktopX(screenX: Float): Float = (screenX - offsetX) / scale

    fun toDesktopY(screenY: Float): Float = (screenY - offsetY) / scale

    fun toScreenX(desktopX: Float): Float = desktopX * scale + offsetX

    fun toScreenY(desktopY: Float): Float = desktopY * scale + offsetY

    /** Scales by [factor] around a point of the device's screen, which stays where it is. */
    fun zoomed(factor: Float, focusX: Float, focusY: Float, minScale: Float, maxScale: Float): Viewport {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        val applied = next / scale
        return Viewport(next, focusX - (focusX - offsetX) * applied, focusY - (focusY - offsetY) * applied)
    }

    fun panned(dx: Float, dy: Float): Viewport = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /**
     * Keeps the remote screen on the device's screen: centered along a side
     * where it is smaller than the view, and with no empty margin along a
     * side where it is larger.
     */
    fun clamped(desktopWidth: Int, desktopHeight: Int, viewWidth: Float, viewHeight: Float): Viewport =
        Viewport(
            scale,
            clampOffset(offsetX, desktopWidth * scale, viewWidth),
            clampOffset(offsetY, desktopHeight * scale, viewHeight),
        )

    /** Pans as little as needed for a point of the remote screen to be at least [margin] inside the view. */
    fun revealing(desktopX: Float, desktopY: Float, viewWidth: Float, viewHeight: Float, margin: Float): Viewport {
        val x = toScreenX(desktopX)
        val y = toScreenY(desktopY)
        val dx = when {
            x < margin -> margin - x
            x > viewWidth - margin -> viewWidth - margin - x
            else -> 0f
        }
        val dy = when {
            y < margin -> margin - y
            y > viewHeight - margin -> viewHeight - margin - y
            else -> 0f
        }
        return panned(dx, dy)
    }

    companion object {
        /** The whole remote screen, as large as fits, centered. */
        fun fit(desktopWidth: Int, desktopHeight: Int, viewWidth: Float, viewHeight: Float): Viewport {
            if (desktopWidth <= 0 || desktopHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return Viewport()
            val scale = minOf(viewWidth / desktopWidth, viewHeight / desktopHeight)
            return Viewport(scale, (viewWidth - desktopWidth * scale) / 2, (viewHeight - desktopHeight * scale) / 2)
        }

        /** One remote pixel per device pixel, keeping the remote point under the view's center where it is. */
        fun actualSize(current: Viewport, viewWidth: Float, viewHeight: Float): Viewport =
            current.zoomed(1f / current.scale, viewWidth / 2, viewHeight / 2, 1f, 1f)

        private fun clampOffset(offset: Float, content: Float, view: Float): Float =
            if (content <= view) (view - content) / 2 else offset.coerceIn(view - content, 0f)
    }
}
