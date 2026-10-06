package dev.herdroid.terminal

/** ARGB palette used by the renderer. Defaults to a calm GitHub-dark-like scheme. */
data class TerminalTheme(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val ansi: IntArray,
) {
    private val cube: IntArray by lazy {
        IntArray(256) { i ->
            when {
                i < 16 -> ansi[i]
                i < 232 -> {
                    val n = i - 16
                    val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                    argb(steps[n / 36], steps[(n / 6) % 6], steps[n % 6])
                }
                else -> {
                    val v = 8 + (i - 232) * 10
                    argb(v, v, v)
                }
            }
        }
    }

    fun resolve(color: Int, isForeground: Boolean, bold: Boolean): Int = when {
        Color.isDefault(color) -> if (isForeground) foreground else background
        Color.isIndexed(color) -> {
            val index = Color.index(color)
            // Classic "bold brightens" behavior for the 8 base colors.
            cube[if (bold && isForeground && index < 8) index + 8 else index]
        }
        Color.isRgb(color) -> 0xFF000000.toInt() or Color.rgbValue(color)
        else -> if (isForeground) foreground else background
    }

    override fun equals(other: Any?) = other is TerminalTheme && other.ansi.contentEquals(ansi) &&
        other.background == background && other.foreground == foreground
    override fun hashCode() = ansi.contentHashCode() * 31 + background

    companion object {
        fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        val Default = TerminalTheme(
            background = 0xFF0F171B.toInt(),
            foreground = 0xFFE9EDE8.toInt(),
            cursor = 0xFFE9EDE8.toInt(),
            selection = 0x6667B0F0,
            ansi = intArrayOf(
                0xFF484F58.toInt(), 0xFFFF7B72.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
                0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
                0xFF6E7681.toInt(), 0xFFFFA198.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
                0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFFFFFFF.toInt(),
            ),
        )
    }
}
