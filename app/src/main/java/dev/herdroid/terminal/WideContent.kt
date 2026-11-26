package dev.herdroid.terminal

import kotlin.math.abs
import kotlin.math.max

/**
 * Horizontal panning for a remote screen wider than the view (a watched pane keeps the size its
 * controller gave it). Offsets are in pixels from the left edge of the content.
 */
internal object WideContent {
    /** How far the content can move left: zero when it fits. */
    fun maxOffset(contentPx: Float, viewPx: Float): Float = max(0f, contentPx - viewPx)

    fun clamp(offset: Float, contentPx: Float, viewPx: Float): Float = offset.coerceIn(0f, maxOffset(contentPx, viewPx))

    /**
     * Which way a one-finger drag goes, decided once from its first movement past [slop]:
     * mostly sideways pans (only when there is something to the side), anything else scrolls
     * the scrollback as before. Null until the finger has moved far enough to tell.
     */
    fun axis(totalDx: Float, totalDy: Float, slop: Float, canPan: Boolean): Axis? = when {
        abs(totalDx) < slop && abs(totalDy) < slop -> null
        canPan && abs(totalDx) > abs(totalDy) -> Axis.Horizontal
        else -> Axis.Vertical
    }

    /** The position bar's thumb as fractions of the view width: start and length. Null when it fits. */
    fun thumb(offset: Float, contentPx: Float, viewPx: Float): Pair<Float, Float>? {
        if (contentPx <= viewPx || contentPx <= 0f) return null
        val length = viewPx / contentPx
        return clamp(offset, contentPx, viewPx) / contentPx to length
    }

    /** The column under [px] (relative to the content's left padding) at [scale] and [offset]. */
    fun column(px: Float, offset: Float, cellWidth: Float, scale: Float, cols: Int): Int =
        if (cellWidth <= 0f || cols <= 0) 0
        else ((px + offset) / (cellWidth * scale)).toInt().coerceIn(0, cols - 1)

    enum class Axis { Horizontal, Vertical }
}
