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

    @Test fun functionAndNavigationKeysFollowXtermSequences() {
        val expected = mapOf(
            SpecialKey.DOWN to "[B", SpecialKey.HOME to "[H", SpecialKey.END to "[F",
            SpecialKey.INSERT to "[2~", SpecialKey.DELETE to "[3~", SpecialKey.PAGE_DOWN to "[6~",
            SpecialKey.F2 to "OQ", SpecialKey.F3 to "OR", SpecialKey.F4 to "OS",
            SpecialKey.F6 to "[17~", SpecialKey.F7 to "[18~", SpecialKey.F8 to "[19~",
            SpecialKey.F9 to "[20~", SpecialKey.F10 to "[21~", SpecialKey.F11 to "[23~", SpecialKey.F12 to "[24~")
        expected.forEach { (key, suffix) -> assertEquals("\u001b$suffix", KeyEncoder.special(key)) }
        assertEquals("\u001b[1;8Q", KeyEncoder.special(SpecialKey.F2, ctrl = true, alt = true, shift = true))
        assertEquals("\u001b[3;5~", KeyEncoder.special(SpecialKey.DELETE, ctrl = true))
        assertEquals("\r", KeyEncoder.special(SpecialKey.ENTER))
        assertEquals("\u001b\r", KeyEncoder.special(SpecialKey.ENTER, alt = true))
        assertEquals("\t", KeyEncoder.special(SpecialKey.TAB))
        assertEquals("\u001b\t", KeyEncoder.special(SpecialKey.TAB, alt = true))
        assertEquals("\u0008", KeyEncoder.special(SpecialKey.BACKSPACE, ctrl = true))
        assertEquals("\u001b\u007f", KeyEncoder.special(SpecialKey.BACKSPACE, alt = true))
        assertEquals("\u001b", KeyEncoder.special(SpecialKey.ESCAPE))
    }

    @Test fun controlPunctuationAndUnicodePreserveTerminalSemantics() {
        val groups = listOf(" 2@" to 0, "[3" to 27, "\\4" to 28, "]5" to 29, "^6" to 30, "_7/-" to 31, "8?" to 127)
        groups.forEach { (chars, code) -> chars.forEach { assertEquals(code.toChar(), KeyEncoder.ctrlChar(it)) } }
        assertEquals("ñ\r", KeyEncoder.text("ñ\n", ctrl = true, alt = false))
        assertEquals("\u001b\r", KeyEncoder.text("\n", ctrl = false, alt = true))
    }
}
