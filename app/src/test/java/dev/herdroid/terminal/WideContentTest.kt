package dev.herdroid.terminal

import dev.herdroid.terminal.WideContent.Axis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WideContentTest {
    @Test fun offsetsStayWithinTheHiddenWidth() {
        assertEquals(0f, WideContent.maxOffset(300f, 400f))
        assertEquals(600f, WideContent.maxOffset(1000f, 400f))
        assertEquals(0f, WideContent.clamp(-50f, 1000f, 400f))
        assertEquals(600f, WideContent.clamp(900f, 1000f, 400f))
        assertEquals(250f, WideContent.clamp(250f, 1000f, 400f))
        assertEquals(0f, WideContent.clamp(250f, 300f, 400f))
    }

    @Test fun aTallScreenFollowsTheCursorOrShowsTheBottom() {
        // Fits: no offset.
        assertEquals(0f, WideContent.follow(50f, 300f, 400f, focusTop = 280f, focusHeight = 20f))
        // Cursor already in view: stay put.
        assertEquals(100f, WideContent.follow(100f, 1000f, 400f, focusTop = 300f, focusHeight = 20f))
        // Cursor below: move just enough to show its whole row.
        assertEquals(380f, WideContent.follow(100f, 1000f, 400f, focusTop = 760f, focusHeight = 20f))
        // Cursor above: move up to it.
        assertEquals(40f, WideContent.follow(300f, 1000f, 400f, focusTop = 40f, focusHeight = 20f))
        // No cursor: the bottom.
        assertEquals(600f, WideContent.follow(0f, 1000f, 400f, focusTop = null, focusHeight = 20f))
    }

    @Test fun aDragLocksToTheAxisOfItsFirstMovement() {
        assertNull(WideContent.axis(3f, 2f, slop = 8f, canPan = true))
        assertEquals(Axis.Horizontal, WideContent.axis(20f, 5f, slop = 8f, canPan = true))
        assertEquals(Axis.Vertical, WideContent.axis(5f, -20f, slop = 8f, canPan = true))
        // Nothing to the side: every drag keeps scrolling the scrollback.
        assertEquals(Axis.Vertical, WideContent.axis(20f, 5f, slop = 8f, canPan = false))
    }

    @Test fun thumbShowsWhichPartIsVisible() {
        assertNull(WideContent.thumb(0f, 400f, 400f))
        assertEquals(0f to 0.4f, WideContent.thumb(0f, 1000f, 400f))
        assertEquals(0.6f to 0.4f, WideContent.thumb(600f, 1000f, 400f))
        assertEquals(0.6f to 0.4f, WideContent.thumb(5000f, 1000f, 400f))
    }

    @Test fun tapsMapThroughTheOffset() {
        assertEquals(0, WideContent.column(5f, 0f, 10f, 1f, 200))
        assertEquals(60, WideContent.column(5f, 600f, 10f, 1f, 200))
        assertEquals(199, WideContent.column(5000f, 600f, 10f, 1f, 200))
        assertEquals(20, WideContent.column(100f, 0f, 10f, 0.5f, 200))
    }
}
