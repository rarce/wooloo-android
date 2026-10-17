package dev.herdroid.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalThemeTest {
    @Test fun paletteResolvesAnsiCubeGrayscaleRgbAndBoldWithoutChangingBackground() {
        val theme = TerminalTheme.Default
        assertEquals(theme.foreground, theme.resolve(Color.DEFAULT, true, false))
        assertEquals(theme.background, theme.resolve(Color.DEFAULT, false, true))
        assertEquals(theme.ansi[1], theme.resolve(Color.indexed(1), true, false))
        assertEquals(theme.ansi[9], theme.resolve(Color.indexed(1), true, true))
        assertEquals(theme.ansi[1], theme.resolve(Color.indexed(1), false, true))
        assertEquals(0xff000000.toInt(), theme.resolve(Color.indexed(16), true, false))
        assertEquals(0xffffffff.toInt(), theme.resolve(Color.indexed(231), true, false))
        assertEquals(0xff080808.toInt(), theme.resolve(Color.indexed(232), true, false))
        assertEquals(0xffeeeeee.toInt(), theme.resolve(Color.indexed(255), true, false))
        assertEquals(0xff123456.toInt(), theme.resolve(Color.rgb(0x12, 0x34, 0x56), true, false))
        assertEquals(theme, theme.copy(ansi = theme.ansi.copyOf()))
        assertEquals(theme.hashCode(), theme.copy().hashCode())
        assertNotEquals(theme, theme.copy(background = 0))
        assertNotEquals(theme, "other")
    }
}
