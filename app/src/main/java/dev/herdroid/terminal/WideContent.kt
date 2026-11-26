package dev.herdroid.terminal

import kotlin.math.abs
import kotlin.math.max

/**
 * Panning for a remote screen larger than the view (a watched pane keeps the size its controller
 * gave it): sideways by hand, vertically following the cursor. Offsets are in pixels from the
 * content's left or top edge.
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

    /**
     * The vertical offset for a screen taller than the view, which can't be scrolled by hand (a
     * vertical drag scrolls the scrollback): moves as little as possible from [previous] to keep the
     * focus row ([focusTop] and [focusHeight] pixels tall, usually the cursor's) in view, or shows the
     * bottom when there is no focus (the cursor is hidden). The focus row keeps [margin] pixels from
     * the view's edges where the content allows (the edge fades cover that much). Zero when it fits.
     */
    fun follow(previous: Float, contentPx: Float, viewPx: Float, focusTop: Float?, focusHeight: Float, margin: Float = 0f): Float {
        val max = maxOffset(contentPx, viewPx)
        if (max == 0f) return 0f
        if (focusTop == null) return max
        val gap = margin.coerceIn(0f, max(0f, (viewPx - focusHeight) / 2))
        var offset = previous.coerceIn(0f, max)
        if (focusTop + focusHeight + gap > offset + viewPx) offset = focusTop + focusHeight + gap - viewPx
        if (focusTop - gap < offset) offset = focusTop - gap
        return offset.coerceIn(0f, max)
    }

    /** The column under [px] (relative to the content's left padding) at [scale] and [offset]. */
    fun column(px: Float, offset: Float, cellWidth: Float, scale: Float, cols: Int): Int =
        if (cellWidth <= 0f || cols <= 0) 0
        else ((px + offset) / (cellWidth * scale)).toInt().coerceIn(0, cols - 1)

    enum class Axis { Horizontal, Vertical }
}
