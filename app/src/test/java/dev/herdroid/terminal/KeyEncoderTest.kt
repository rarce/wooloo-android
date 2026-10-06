package dev.herdroid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyEncoderTest {
    @Test
    fun arrowsAndModifiers() {
        assertEquals("\u001b[A", KeyEncoder.special(SpecialKey.UP))
        assertEquals("\u001b[1;5C", KeyEncoder.special(SpecialKey.RIGHT, ctrl = true))
        assertEquals("\u001b[1;3D", KeyEncoder.special(SpecialKey.LEFT, alt = true))
        assertEquals("\u001b[Z", KeyEncoder.special(SpecialKey.TAB, shift = true))
        assertEquals("\u001b[5~", KeyEncoder.special(SpecialKey.PAGE_UP))
        assertEquals("\u001bOP", KeyEncoder.special(SpecialKey.F1))
        assertEquals("\u001b[15~", KeyEncoder.special(SpecialKey.F5))
        assertEquals("\u007f", KeyEncoder.special(SpecialKey.BACKSPACE))
    }

    @Test
    fun stickyModifiersOnText() {
        assertEquals("\u0003", KeyEncoder.text("c", ctrl = true, alt = false))
        assertEquals("\u0003", KeyEncoder.text("C", ctrl = true, alt = false))
        assertEquals("\u001bx", KeyEncoder.text("x", ctrl = false, alt = true))
        assertEquals("\u001b\u0001", KeyEncoder.text("a", ctrl = true, alt = true))
        assertEquals("ls\r", KeyEncoder.text("ls\n", ctrl = false, alt = false))
    }
}
