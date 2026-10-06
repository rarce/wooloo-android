package dev.herdroid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    @Test
    fun `herdr style full frame paints cells cursor and hyperlinks`() {
        val emu = TerminalEmulator(10, 3)
        // Shape taken from herdr's render_ansi tests.
        emu.feed(
            "\u001b[?2026h\u001b[?25l\u001b]8;;\u001b\\\u001b[2J" +
                "\u001b[1;1H\u001b[0;39;49ma\u001b]8;;https://example.com\u001b\\b\u001b]8;;\u001b\\c" +
                "\u001b[0;31;49md\u001b[0;39;49me\u001b[0m" +
                "\u001b[2;3H\u001b[6 q\u001b[?25h\u001b[?2026l"
        )
        assertEquals("abcde", emu.rowText(0))
        assertEquals("https://example.com", emu.linkAt(1, 0))
        assertNull(emu.linkAt(2, 0))
        assertEquals(Color.indexed(1), emu.fgAt(3, 0))
        assertEquals(Color.DEFAULT, emu.fgAt(4, 0))
        assertEquals(2, emu.cursorX)
        assertEquals(1, emu.cursorY)
        assertTrue(emu.cursorVisible)
        assertEquals(CursorShape.BAR, emu.cursorShape)
    }

    @Test
    fun `incremental patch only touches addressed cells`() {
        val emu = TerminalEmulator(6, 2)
        emu.feed("\u001b[1;1Hhello\u001b[2;1Hworld")
        emu.feed("\u001b[?2026h\u001b[?25l\u001b[1;2H\u001b[0;1;39;49mE\u001b[0m\u001b[?2026l")
        assertEquals("hEllo", emu.rowText(0))
        assertEquals("world", emu.rowText(1))
        assertEquals(Attr.BOLD, emu.attrsAt(1, 0))
        assertFalse(emu.cursorVisible)
    }

    @Test
    fun `truecolor indexed and colon underline styles parse`() {
        val emu = TerminalEmulator(4, 1)
        emu.feed("\u001b[0;4:3;38;2;10;20;30;48;5;200mX")
        assertEquals(Color.rgb(10, 20, 30), emu.fgAt(0, 0))
        assertEquals(Color.indexed(200), emu.bgAt(0, 0))
        assertTrue(emu.attrsAt(0, 0) and Attr.UNDERLINE != 0)
        emu.feed("\u001b[1;2H\u001b[38:2::1:2:3mY")
        assertEquals(Color.rgb(1, 2, 3), emu.fgAt(1, 0))
    }

    @Test
    fun `wide glyphs occupy two cells and utf8 can split across frames`() {
        val emu = TerminalEmulator(6, 1)
        val bytes = "\u001b[1;1H漢x".toByteArray(Charsets.UTF_8)
        emu.feed(bytes, 0, 7) // split inside the 3-byte CJK sequence
        emu.feed(bytes, 7, bytes.size - 7)
        assertEquals("漢", emu.textAt(0, 0))
        assertEquals("", emu.textAt(1, 0))
        assertEquals("x", emu.textAt(2, 0))
        assertEquals("漢x", emu.rowText(0))
    }

    @Test
    fun `overwriting half of a wide glyph clears the other half`() {
        val emu = TerminalEmulator(4, 1)
        emu.feed("\u001b[1;1H漢\u001b[1;2Hz")
        assertEquals(" ", emu.textAt(0, 0))
        assertEquals("z", emu.textAt(1, 0))
    }

    @Test
    fun `combining marks and zwj emoji stay in one cell`() {
        val emu = TerminalEmulator(6, 1)
        emu.feed("é!")
        assertEquals("é", emu.textAt(0, 0))
        assertEquals("!", emu.textAt(1, 0))
    }

    @Test
    fun `line feed at bottom scrolls and erase line clears`() {
        val emu = TerminalEmulator(5, 2)
        emu.feed("one\r\ntwo\r\nthree")
        assertEquals("two", emu.rowText(0))
        assertEquals("three", emu.rowText(1))
        emu.feed("\u001b[1;1H\u001b[K")
        assertEquals("", emu.rowText(0))
    }

    @Test
    fun `autowrap defers until the next printable`() {
        val emu = TerminalEmulator(3, 2)
        emu.feed("abc")
        assertEquals(2, emu.cursorX)
        assertEquals(0, emu.cursorY)
        emu.feed("d")
        assertEquals("abc", emu.rowText(0))
        assertEquals("d", emu.rowText(1))
    }

    @Test
    fun `resize preserves overlapping content`() {
        val emu = TerminalEmulator(4, 2)
        emu.feed("abcd\u001b[2;1Hefgh")
        emu.resize(2, 3)
        assertEquals("ab", emu.rowText(0))
        assertEquals("ef", emu.rowText(1))
        assertEquals("", emu.rowText(2))
    }

    @Test
    fun `inverse attribute and reset`() {
        val emu = TerminalEmulator(3, 1)
        emu.feed("\u001b[0;7;39;49mA\u001b[0mB")
        assertEquals(Attr.INVERSE, emu.attrsAt(0, 0))
        assertEquals(0, emu.attrsAt(1, 0))
    }

    @Test
    fun `unknown sequences and dcs strings are ignored`() {
        val emu = TerminalEmulator(5, 1)
        emu.feed("\u001bP1;2|junk\u001b\\\u001b[?1049h\u001b[>4;2mok")
        assertEquals("ok", emu.rowText(0))
    }
}
