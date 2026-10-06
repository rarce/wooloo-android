package dev.herdroid.terminal

/**
 * A screen-grid terminal emulator sized for herdr's server-rendered pane frames.
 *
 * herdr does the heavy lifting (it runs the real VT engine for each pane) and streams compact
 * ANSI repaints: synchronized-output brackets, CUP positioning, full SGR state per run,
 * `2J` on full redraws, cursor visibility/shape and OSC 8 hyperlinks. This class decodes that
 * stream (plus common extras for robustness) into a grid the renderer can draw.
 *
 * Not thread-safe by itself; [TerminalModel] guards it with a lock.
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24) {

    var cols: Int = cols.coerceAtLeast(1); private set
    var rows: Int = rows.coerceAtLeast(1); private set

    // Grid storage as parallel arrays (row-major).
    private var text: Array<String> = Array(this.cols * this.rows) { " " }
    private var fgs = IntArray(this.cols * this.rows)
    private var bgs = IntArray(this.cols * this.rows)
    private var attrs = IntArray(this.cols * this.rows)
    private var linkIds = IntArray(this.cols * this.rows)

    /** OSC 8 targets; index+1 is stored per cell (0 means no link). */
    private val links = ArrayList<String>()
    private val linkIndex = HashMap<String, Int>()

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    var cursorShape = CursorShape.BLOCK; private set

    /** Bumped on every mutation so renderers can skip identical frames. */
    var revision: Long = 0; private set

    // Current pen.
    private var penFg = Color.DEFAULT
    private var penBg = Color.DEFAULT
    private var penAttrs = 0
    private var penLink = 0
    private var pendingWrap = false
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private var savedX = 0
    private var savedY = 0

    // Parser state.
    private var state = State.GROUND
    private val params = StringBuilder()
    private var intermediate: Char? = null
    private var privateMarker: Char? = null
    private val oscBuffer = StringBuilder()
    private var utf8Remaining = 0
    private var utf8Codepoint = 0
    private var lastPrintedIndex = -1
    private var lastPrintedCp = 0

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return
        val newText = Array(c * r) { " " }
        val newFg = IntArray(c * r)
        val newBg = IntArray(c * r)
        val newAttrs = IntArray(c * r)
        val newLinks = IntArray(c * r)
        for (y in 0 until minOf(rows, r)) {
            for (x in 0 until minOf(cols, c)) {
                val from = y * cols + x
                val to = y * c + x
                newText[to] = text[from]
                newFg[to] = fgs[from]
                newBg[to] = bgs[from]
                newAttrs[to] = attrs[from]
                newLinks[to] = linkIds[from]
            }
        }
        cols = c; rows = r
        text = newText; fgs = newFg; bgs = newBg; attrs = newAttrs; linkIds = newLinks
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
        revision++
    }

    fun reset() {
        clearRegion(0, cols * rows)
        links.clear(); linkIndex.clear()
        cursorX = 0; cursorY = 0
        penFg = Color.DEFAULT; penBg = Color.DEFAULT; penAttrs = 0; penLink = 0
        state = State.GROUND
        revision++
    }

    // ---- Cell accessors for renderers -------------------------------------------------

    fun textAt(x: Int, y: Int): String = text[y * cols + x]
    fun fgAt(x: Int, y: Int): Int = fgs[y * cols + x]
    fun bgAt(x: Int, y: Int): Int = bgs[y * cols + x]
    fun attrsAt(x: Int, y: Int): Int = attrs[y * cols + x]
    fun linkAt(x: Int, y: Int): String? {
        if (x !in 0 until cols || y !in 0 until rows) return null
        val id = linkIds[y * cols + x]
        return if (id == 0) null else links.getOrNull(id - 1)
    }

    fun rowText(y: Int): String = buildString {
        for (x in 0 until cols) {
            val cell = text[y * cols + x]
            if (cell.isNotEmpty()) append(cell)
        }
    }.trimEnd()

    fun screenText(): String = (0 until rows).joinToString("\n") { rowText(it) }.trimEnd()

    // ---- Input ------------------------------------------------------------------------

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (utf8Remaining > 0) {
                if (b and 0xC0 == 0x80) {
                    utf8Codepoint = (utf8Codepoint shl 6) or (b and 0x3F)
                    utf8Remaining--
                    if (utf8Remaining == 0) onCodepoint(utf8Codepoint)
                    continue
                }
                // Invalid continuation: emit replacement and reprocess this byte.
                utf8Remaining = 0
                onCodepoint(0xFFFD)
            }
            when {
                b < 0x80 -> onCodepoint(b)
                b and 0xE0 == 0xC0 -> { utf8Codepoint = b and 0x1F; utf8Remaining = 1 }
                b and 0xF0 == 0xE0 -> { utf8Codepoint = b and 0x0F; utf8Remaining = 2 }
                b and 0xF8 == 0xF0 -> { utf8Codepoint = b and 0x07; utf8Remaining = 3 }
                else -> onCodepoint(0xFFFD)
            }
        }
        revision++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun onCodepoint(cp: Int) {
        when (state) {
            State.GROUND -> ground(cp)
            State.ESCAPE -> escape(cp)
            State.CSI -> csi(cp)
            State.OSC -> osc(cp)
            State.OSC_ESC -> {
                if (cp == '\\'.code) { finishOsc(); state = State.GROUND } else {
                    oscBuffer.clear(); state = State.GROUND; onCodepoint(cp)
                }
            }
            State.STRING -> if (cp == 0x1B) state = State.STRING_ESC else if (cp == 0x07) state = State.GROUND
            State.STRING_ESC -> state = if (cp == '\\'.code) State.GROUND else State.STRING
            State.CHARSET -> state = State.GROUND
        }
    }

    private fun ground(cp: Int) {
        when (cp) {
            0x1B -> { state = State.ESCAPE }
            0x0D -> { cursorX = 0; pendingWrap = false }
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x08 -> { if (cursorX > 0) cursorX--; pendingWrap = false }
            0x09 -> { cursorX = minOf(cols - 1, (cursorX / 8 + 1) * 8); pendingWrap = false }
            0x07, 0x00, 0x0E, 0x0F -> Unit
            else -> if (cp >= 0x20 && cp != 0x7F) print(cp)
        }
    }

    private fun escape(cp: Int) {
        state = State.GROUND
        when (cp.toChar()) {
            '[' -> { params.clear(); intermediate = null; privateMarker = null; state = State.CSI }
            ']' -> { oscBuffer.clear(); state = State.OSC }
            'P', 'X', '^', '_' -> state = State.STRING
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> { savedX = cursorX; savedY = cursorY }
            '8' -> { cursorX = savedX; cursorY = savedY; pendingWrap = false }
            'D' -> lineFeed()
            'E' -> { cursorX = 0; lineFeed() }
            'M' -> reverseIndex()
            'c' -> reset()
            else -> Unit
        }
    }

    private fun csi(cp: Int) {
        val c = cp.toChar()
        when {
            c in '0'..'9' || c == ';' || c == ':' -> params.append(c)
            c in "?<=>" && params.isEmpty() -> privateMarker = c
            cp in 0x20..0x2F -> intermediate = c
            cp in 0x40..0x7E -> { state = State.GROUND; dispatchCsi(c) }
            cp == 0x1B -> state = State.ESCAPE
            else -> Unit
        }
    }

    private fun osc(cp: Int) {
        when (cp) {
            0x07 -> { finishOsc(); state = State.GROUND }
            0x1B -> state = State.OSC_ESC
            else -> if (oscBuffer.length < 4096) oscBuffer.appendCodePoint(cp)
        }
    }

    private fun finishOsc() {
        val data = oscBuffer.toString()
        oscBuffer.clear()
        // OSC 8 ; params ; URI
        if (data.startsWith("8;")) {
            val uri = data.substringAfter(';').substringAfter(';', "")
            penLink = if (uri.isEmpty()) 0 else linkIndex.getOrPut(uri) {
                links.add(uri); links.size
            }
        }
    }

    // ---- Printing ---------------------------------------------------------------------

    private fun print(cp: Int) {
        val width = CharWidth.of(cp)
        if (width == 0) {
            // Combining mark / ZWJ / variation selector: attach to the previous glyph.
            if (lastPrintedIndex in text.indices) {
                text[lastPrintedIndex] = text[lastPrintedIndex] + String(Character.toChars(cp))
            }
            lastPrintedCp = cp
            return
        }
        if (lastPrintedCp == 0x200D && lastPrintedIndex in text.indices) {
            // Emoji ZWJ sequence continues in the same cell.
            text[lastPrintedIndex] = text[lastPrintedIndex] + String(Character.toChars(cp))
            lastPrintedCp = cp
            return
        }
        if (pendingWrap) {
            cursorX = 0
            lineFeed()
            pendingWrap = false
        }
        if (width == 2 && cursorX == cols - 1) {
            // A wide glyph doesn't fit in the last column: wrap first.
            writeCell(cursorX, cursorY, " ")
            cursorX = 0
            lineFeed()
        }
        val index = cursorY * cols + cursorX
        writeCell(cursorX, cursorY, String(Character.toChars(cp)))
        lastPrintedIndex = index
        lastPrintedCp = cp
        if (width == 2 && cursorX + 1 < cols) {
            writeCell(cursorX + 1, cursorY, "", continuation = true)
        }
        val next = cursorX + width
        if (next >= cols) {
            cursorX = cols - 1
            pendingWrap = true
        } else {
            cursorX = next
        }
    }

    private fun writeCell(x: Int, y: Int, value: String, continuation: Boolean = false) {
        val i = y * cols + x
        if (!continuation) {
            val old = text[i]
            // Overwriting the right half of a wide glyph orphans its left half.
            if (old.isEmpty() && x > 0) text[i - 1] = " "
            // Overwriting a wide glyph orphans its continuation cell.
            if (old.isNotEmpty() && CharWidth.of(old.codePointAtOrZero()) == 2 &&
                x + 1 < cols && text[i + 1].isEmpty()
            ) text[i + 1] = " "
        }
        text[i] = value
        fgs[i] = penFg
        bgs[i] = penBg
        attrs[i] = penAttrs
        linkIds[i] = penLink
    }

    private fun String.codePointAtOrZero(): Int = if (isEmpty()) 0 else codePointAt(0)

    private fun lineFeed() {
        pendingWrap = false
        if (cursorY == scrollBottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == scrollTop) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(n: Int) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (y in scrollTop..scrollBottom - count) copyRow(y + count, y)
        for (y in scrollBottom - count + 1..scrollBottom) clearRow(y)
    }

    private fun scrollDown(n: Int) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (y in scrollBottom downTo scrollTop + count) copyRow(y - count, y)
        for (y in scrollTop until scrollTop + count) clearRow(y)
    }

    private fun copyRow(from: Int, to: Int) {
        System.arraycopy(text, from * cols, text, to * cols, cols)
        System.arraycopy(fgs, from * cols, fgs, to * cols, cols)
        System.arraycopy(bgs, from * cols, bgs, to * cols, cols)
        System.arraycopy(attrs, from * cols, attrs, to * cols, cols)
        System.arraycopy(linkIds, from * cols, linkIds, to * cols, cols)
    }

    private fun clearRow(y: Int) = clearRegion(y * cols, (y + 1) * cols)

    /** Clears [start, end) using the current background, as terminals do for erase ops. */
    private fun clearRegion(start: Int, end: Int) {
        for (i in start until end) {
            text[i] = " "
            fgs[i] = Color.DEFAULT
            bgs[i] = penBg
            attrs[i] = 0
            linkIds[i] = 0
        }
    }

    // ---- CSI dispatch -----------------------------------------------------------------

    private fun dispatchCsi(final: Char) {
        val raw = params.toString()
        val p = parseParams(raw)
        fun arg(i: Int, default: Int) = p.getOrNull(i)?.firstOrNull()?.takeIf { it > 0 } ?: default

        if (privateMarker == '?') {
            if (final == 'h' || final == 'l') {
                val on = final == 'h'
                for (group in p) when (group.firstOrNull()) {
                    25 -> cursorVisible = on
                    else -> Unit // 2026 sync output, 1049 alt screen, mouse modes: not needed here.
                }
            }
            return
        }
        if (privateMarker != null) return

        if (intermediate == ' ' && final == 'q') {
            cursorShape = CursorShape.fromDecscusr(arg(0, 0))
            return
        }
        if (intermediate != null) return

        when (final) {
            'H', 'f' -> moveTo(arg(1, 1) - 1, arg(0, 1) - 1)
            'A' -> moveTo(cursorX, cursorY - arg(0, 1))
            'B' -> moveTo(cursorX, cursorY + arg(0, 1))
            'C' -> moveTo(cursorX + arg(0, 1), cursorY)
            'D' -> moveTo(cursorX - arg(0, 1), cursorY)
            'E' -> moveTo(0, cursorY + arg(0, 1))
            'F' -> moveTo(0, cursorY - arg(0, 1))
            'G', '`' -> moveTo(arg(0, 1) - 1, cursorY)
            'd' -> moveTo(cursorX, arg(0, 1) - 1)
            'J' -> eraseDisplay(p.getOrNull(0)?.firstOrNull() ?: 0)
            'K' -> eraseLine(p.getOrNull(0)?.firstOrNull() ?: 0)
            'X' -> {
                val start = cursorY * cols + cursorX
                clearRegion(start, minOf(start + arg(0, 1), (cursorY + 1) * cols))
            }
            'm' -> sgr(p)
            'r' -> {
                val top = arg(0, 1) - 1
                val bottom = arg(1, rows) - 1
                if (top < bottom && bottom < rows) {
                    scrollTop = top; scrollBottom = bottom
                    moveTo(0, 0)
                }
            }
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            's' -> { savedX = cursorX; savedY = cursorY }
            'u' -> moveTo(savedX, savedY)
            else -> Unit
        }
    }

    private fun moveTo(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        pendingWrap = false
        lastPrintedIndex = -1
        lastPrintedCp = 0
    }

    private fun eraseDisplay(mode: Int) {
        val here = cursorY * cols + cursorX
        when (mode) {
            0 -> clearRegion(here, cols * rows)
            1 -> clearRegion(0, here + 1)
            2, 3 -> clearRegion(0, cols * rows)
        }
    }

    private fun eraseLine(mode: Int) {
        val start = cursorY * cols
        when (mode) {
            0 -> clearRegion(start + cursorX, start + cols)
            1 -> clearRegion(start, start + cursorX + 1)
            2 -> clearRegion(start, start + cols)
        }
    }

    /** Parses `1;38:2::10:20:30;4:3` into groups of colon-separated sub-parameters. */
    private fun parseParams(raw: String): List<IntArray> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { group ->
            group.split(':').map { it.toIntOrNull() ?: 0 }.toIntArray()
        }
    }

    private fun sgr(p: List<IntArray>) {
        if (p.isEmpty()) { resetPen(); return }
        var i = 0
        while (i < p.size) {
            val group = p[i]
            val code = group.firstOrNull() ?: 0
            when (code) {
                0 -> resetPen()
                1 -> penAttrs = penAttrs or Attr.BOLD
                2 -> penAttrs = penAttrs or Attr.DIM
                3 -> penAttrs = penAttrs or Attr.ITALIC
                4 -> {
                    val style = group.getOrNull(1) ?: 1
                    penAttrs = if (style == 0) penAttrs and Attr.UNDERLINE.inv()
                    else penAttrs or Attr.UNDERLINE
                }
                5, 6 -> penAttrs = penAttrs or Attr.BLINK
                7 -> penAttrs = penAttrs or Attr.INVERSE
                8 -> penAttrs = penAttrs or Attr.HIDDEN
                9 -> penAttrs = penAttrs or Attr.STRIKE
                21 -> penAttrs = penAttrs or Attr.UNDERLINE
                22 -> penAttrs = penAttrs and (Attr.BOLD or Attr.DIM).inv()
                23 -> penAttrs = penAttrs and Attr.ITALIC.inv()
                24 -> penAttrs = penAttrs and Attr.UNDERLINE.inv()
                25 -> penAttrs = penAttrs and Attr.BLINK.inv()
                27 -> penAttrs = penAttrs and Attr.INVERSE.inv()
                28 -> penAttrs = penAttrs and Attr.HIDDEN.inv()
                29 -> penAttrs = penAttrs and Attr.STRIKE.inv()
                in 30..37 -> penFg = Color.indexed(code - 30)
                39 -> penFg = Color.DEFAULT
                in 40..47 -> penBg = Color.indexed(code - 40)
                49 -> penBg = Color.DEFAULT
                in 90..97 -> penFg = Color.indexed(code - 90 + 8)
                in 100..107 -> penBg = Color.indexed(code - 100 + 8)
                38, 48, 58 -> {
                    val (color, consumed) = extendedColor(p, i)
                    if (code == 38) penFg = color else if (code == 48) penBg = color
                    i += consumed
                }
                else -> Unit
            }
            i++
        }
    }

    /** Returns the color and how many extra `;` groups were consumed. */
    private fun extendedColor(p: List<IntArray>, i: Int): Pair<Int, Int> {
        val group = p[i]
        if (group.size > 1) {
            // Colon form: 38:5:n or 38:2:[colorspace]:r:g:b
            return when (group[1]) {
                5 -> Color.indexed(group.getOrElse(2) { 0 }) to 0
                2 -> {
                    val rgb = if (group.size >= 6) group.copyOfRange(3, 6) else group.copyOfRange(2, minOf(5, group.size))
                    Color.rgb(rgb.getOrElse(0) { 0 }, rgb.getOrElse(1) { 0 }, rgb.getOrElse(2) { 0 }) to 0
                }
                else -> Color.DEFAULT to 0
            }
        }
        return when (p.getOrNull(i + 1)?.firstOrNull()) {
            5 -> Color.indexed(p.getOrNull(i + 2)?.firstOrNull() ?: 0) to 2
            2 -> Color.rgb(
                p.getOrNull(i + 2)?.firstOrNull() ?: 0,
                p.getOrNull(i + 3)?.firstOrNull() ?: 0,
                p.getOrNull(i + 4)?.firstOrNull() ?: 0,
            ) to 4
            else -> Color.DEFAULT to 0
        }
    }

    private fun resetPen() {
        penFg = Color.DEFAULT
        penBg = Color.DEFAULT
        penAttrs = 0
    }

    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESC, STRING, STRING_ESC, CHARSET }
}

enum class CursorShape {
    BLOCK, UNDERLINE, BAR;

    companion object {
        fun fromDecscusr(n: Int) = when (n) {
            3, 4 -> UNDERLINE
            5, 6 -> BAR
            else -> BLOCK
        }
    }
}

object Attr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128
}

/**
 * Packed color: 0 = terminal default, 0x01_0000nn = palette index, 0x02_rrggbb = truecolor.
 */
object Color {
    const val DEFAULT = 0
    private const val INDEXED = 0x01000000
    private const val RGB = 0x02000000
    private const val TAG_MASK = 0x7F000000.toInt()

    fun indexed(i: Int) = INDEXED or (i and 0xFF)
    fun rgb(r: Int, g: Int, b: Int) =
        RGB or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isDefault(c: Int) = c == DEFAULT
    fun isIndexed(c: Int) = c and TAG_MASK == INDEXED
    fun isRgb(c: Int) = c and TAG_MASK == RGB
    fun index(c: Int) = c and 0xFF
    fun rgbValue(c: Int) = c and 0xFFFFFF
}
