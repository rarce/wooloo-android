package dev.herdroid.terminal

enum class SpecialKey {
    ENTER, TAB, BACKSPACE, ESCAPE, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN,
    INSERT, DELETE, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Encodes keys as the byte sequences an xterm-compatible PTY application expects. */
object KeyEncoder {
    private const val ESC = "\u001b"

    fun special(key: SpecialKey, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun csiLetter(letter: Char) = if (mod == 1) "$ESC[$letter" else "$ESC[1;$mod$letter"
        fun csiTilde(n: Int) = if (mod == 1) "$ESC[$n~" else "$ESC[$n;$mod~"
        fun ss3(letter: Char) = if (mod == 1) "${ESC}O$letter" else "$ESC[1;$mod$letter"
        return when (key) {
            SpecialKey.ENTER -> if (alt) "$ESC\r" else "\r"
            SpecialKey.TAB -> if (shift) "$ESC[Z" else if (alt) "$ESC\t" else "\t"
            SpecialKey.BACKSPACE -> when {
                ctrl -> "\u0008"
                alt -> "$ESC\u007f"
                else -> "\u007f"
            }
            SpecialKey.ESCAPE -> ESC
            SpecialKey.UP -> csiLetter('A')
            SpecialKey.DOWN -> csiLetter('B')
            SpecialKey.RIGHT -> csiLetter('C')
            SpecialKey.LEFT -> csiLetter('D')
            SpecialKey.HOME -> csiLetter('H')
            SpecialKey.END -> csiLetter('F')
            SpecialKey.INSERT -> csiTilde(2)
            SpecialKey.DELETE -> csiTilde(3)
            SpecialKey.PAGE_UP -> csiTilde(5)
            SpecialKey.PAGE_DOWN -> csiTilde(6)
            SpecialKey.F1 -> ss3('P')
            SpecialKey.F2 -> ss3('Q')
            SpecialKey.F3 -> ss3('R')
            SpecialKey.F4 -> ss3('S')
            SpecialKey.F5 -> csiTilde(15)
            SpecialKey.F6 -> csiTilde(17)
            SpecialKey.F7 -> csiTilde(18)
            SpecialKey.F8 -> csiTilde(19)
            SpecialKey.F9 -> csiTilde(20)
            SpecialKey.F10 -> csiTilde(21)
            SpecialKey.F11 -> csiTilde(23)
            SpecialKey.F12 -> csiTilde(24)
        }
    }

    /** Applies sticky Ctrl/Alt modifiers from the extra-keys bar to typed text. */
    fun text(text: String, ctrl: Boolean, alt: Boolean): String {
        if (!ctrl && !alt) return text.replace('\n', '\r')
        val out = StringBuilder()
        text.forEach { ch ->
            var c = if (ch == '\n') '\r' else ch
            var s = c.toString()
            if (ctrl) s = ctrlChar(c)?.toString() ?: s
            if (alt) s = ESC + s
            out.append(s)
        }
        return out.toString()
    }

    fun ctrlChar(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        in 'A'..'Z' -> (c - 'A' + 1).toChar()
        ' ', '2', '@' -> 0.toChar()
        '[', '3' -> 27.toChar()
        '\\', '4' -> 28.toChar()
        ']', '5' -> 29.toChar()
        '^', '6' -> 30.toChar()
        '_', '7', '/', '-' -> 31.toChar()
        '8', '?' -> 127.toChar()
        else -> null
    }
}
