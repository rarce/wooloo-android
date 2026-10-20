package dev.herdroid.demo

import dev.herdroid.terminal.CharWidth

/** A run of text in one SGR style (`"1"`, `"38;5;114"`; empty is the default style). */
internal data class Span(val text: String, val style: String = "")

internal typealias Line = List<Span>

internal object Style {
    const val DIM = "38;5;245"
    const val BOLD = "1"
    const val GREEN = "38;5;114"
    const val RED = "38;5;203"
    const val BLUE = "38;5;75"
    const val ORANGE = "38;5;208"
    const val YELLOW = "38;5;179"
    const val PURPLE = "38;5;141"
}

internal fun line(vararg spans: Span): Line = spans.toList()
internal fun text(value: String, style: String = ""): Line = listOf(Span(value, style))

/** What one pane shows: rows of styled cells and where the cursor is. */
internal class DemoScreen(val rows: List<Line>, val cursorRow: Int, val cursorColumn: Int, val cursorVisible: Boolean = true) {
    /** A full repaint, encoded the way herdr's renderer sends frames. */
    fun ansi(): ByteArray = buildString {
        append("\u001b[?2026h\u001b[?25l\u001b[0m\u001b[2J")
        rows.forEachIndexed { index, row ->
            append("\u001b[").append(index + 1).append(";1H")
            row.forEach { span ->
                if (span.style.isEmpty()) append(span.text)
                else append("\u001b[0;").append(span.style).append('m').append(span.text).append("\u001b[0m")
            }
        }
        append("\u001b[").append(cursorRow + 1).append(';').append(cursorColumn + 1).append('H')
        if (cursorVisible) append("\u001b[?25h")
        append("\u001b[?2026l")
    }.toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Wraps [line] to rows of at most [columns] cells, breaking at a space when one is
         * close enough to the edge.
         */
        fun wrap(line: Line, columns: Int): List<Line> {
            val width = columns.coerceAtLeast(1)
            val cells = line.flatMap { span ->
                val result = ArrayList<Pair<String, String>>()
                var i = 0
                while (i < span.text.length) {
                    val cp = span.text.codePointAt(i)
                    val char = String(Character.toChars(cp))
                    i += char.length
                    if (CharWidth.of(cp) > 0) result += char to span.style
                }
                result
            }
            if (cells.isEmpty()) return listOf(emptyList())
            val rows = ArrayList<Line>()
            var start = 0
            while (start < cells.size) {
                var end = minOf(cells.size, start + width)
                if (end < cells.size) {
                    val space = (end downTo start + width / 2).firstOrNull { cells.getOrNull(it)?.first == " " }
                    if (space != null && space > start) end = space
                }
                rows += group(cells.subList(start, end))
                start = end
                // A wrapped row starts at the next word, not with the space that ended the last one.
                while (start < cells.size && cells[start].first == " ") start++
            }
            return rows
        }

        fun width(line: Line): Int = line.sumOf { span -> span.text.codePoints().map { CharWidth.of(it) }.sum() }

        private fun group(cells: List<Pair<String, String>>): Line {
            val spans = ArrayList<Span>()
            val text = StringBuilder()
            var style: String? = null
            for ((char, cellStyle) in cells) {
                if (style != null && cellStyle != style) {
                    spans += Span(text.toString(), style)
                    text.clear()
                }
                style = cellStyle
                text.append(char)
            }
            if (style != null) spans += Span(text.toString(), style)
            return spans
        }
    }
}
