package dev.herdroid.ui.terminal

import androidx.compose.runtime.Immutable
import dev.herdroid.terminal.KeyEncoder
import dev.herdroid.terminal.SpecialKey

/**
 * One numbered choice of the agent's active prompt; [number] is the key that selects it. [checked]
 * is the state of a multi-select checkbox item ("[ ]", "[✔]"), and null for an ordinary item.
 */
@Immutable
data class PromptOption(val number: Int, val label: String, val focused: Boolean = false, val checked: Boolean? = null)

/**
 * Claude Code's unnumbered "Next" or "Submit" row right under a multi-select question's checkboxes:
 * no digit reaches it, only moving the focus onto it and pressing Enter.
 */
@Immutable
data class AdvanceRow(val label: String, val focused: Boolean = false)

/** The active prompt: its numbered [options] and, for a multi-select question, its [advance] row. */
@Immutable
data class PromptOptions(val options: List<PromptOption> = emptyList(), val advance: AdvanceRow? = null) {
    /**
     * What activates [advance] from the current focus: Enter when it has the focus, otherwise the
     * arrows that move the focus onto it first (one step per item: checkbox items above it, items
     * such as "Chat about this" below it). Null without an advance row.
     */
    val advanceInput: String? get() {
        val row = advance ?: return null
        val enter = KeyEncoder.special(SpecialKey.ENTER)
        if (row.focused) return enter
        val last = options.lastOrNull { it.checked != null }?.number ?: return null
        val focused = options.firstOrNull { it.focused }?.number ?: return null
        val (key, steps) = if (focused <= last) SpecialKey.DOWN to last - focused + 1 else SpecialKey.UP to focused - last
        return KeyEncoder.special(key).repeat(steps) + enter
    }
}

/** Shown for a blocked agent whose prompt couldn't be read from the screen. */
internal val FallbackAnswers = listOf(PromptOption(1, "", focused = true), PromptOption(2, ""), PromptOption(3, ""))

// "❯ 1. Yes", "  2) No": an optional focus marker, a single-key number and a label.
private val promptItem = Regex("""(?:([❯›>])\s*)?([1-9])[.)]\s+(\S.*?)""")
// Claude Code's multi-select items: "[ ] Option", "[✔] Option".
private val checkbox = Regex("""^\[([ ✔✓xX×])]\s+""")
// Its "Next"/"Submit" row under the checkboxes, focusable like an item but without a number.
private val advanceItem = Regex("""(?:([❯›>])\s*)?(Next|Submit)""")
private val submitLabel = Regex("""Submit (?:answers|responses)""", RegexOption.IGNORE_CASE)

/**
 * The most non-blank rows below a prompt's last item, not counting rows indented under it: Claude's
 * separator, "Chat about this" and a footer wrapped at phone width fit; a numbered list further up
 * is history.
 */
private const val MAX_ROWS_BELOW_PROMPT = 8

/** A focus marker and its space ("❯ "): how far it can push an item's number right. */
private const val MARKER_WIDTH = 2

/**
 * The options of the active numbered prompt in [rows] (top to bottom): the last run of consecutive
 * items with exactly one focus marker, ending near the bottom of the screen. Runs start at 1, except
 * the first item on screen, whose prompt may have scrolled off the top. Lines between items
 * (descriptions, wrapped labels, numbered lines indented deeper than the run's items) are allowed;
 * box borders are ignored. A numbered list without a focus marker is ordinary output, and any
 * numbered list below a prompt means that prompt is no longer the active one.
 */
internal fun parsePromptOptions(rows: Sequence<String>): List<PromptOption> = parsePrompt(rows).options

/**
 * [parsePromptOptions] plus a multi-select question's [AdvanceRow]: the "Next"/"Submit" row right
 * after a checkbox item, which can hold the run's one focus marker instead of an item.
 */
internal fun parsePrompt(rows: Sequence<String>): PromptOptions {
    val lines = rows.map(::unboxed).toList()
    var last = PromptOptions()
    var lastRow = -1
    var lastIndent = 0
    var run = ArrayList<PromptOption>()
    var runAdvance: AdvanceRow? = null
    var runIndent = 0
    var runEnd = -1
    var runFromTop = false
    var seenItem = false
    fun close(broken: Boolean = false) {
        // An out-of-sequence item means the screen moved on from any earlier prompt.
        if (broken || run.size >= 2) {
            val focus = run.count { it.focused } + if (runAdvance?.focused == true) 1 else 0
            last = if (!broken && focus == 1) PromptOptions(run, runAdvance) else PromptOptions()
            lastRow = runEnd
            lastIndent = runIndent
        }
        run = ArrayList()
        runAdvance = null
    }
    fun start(option: PromptOption, indent: Int, fromTop: Boolean) {
        run = arrayListOf(option)
        runAdvance = null
        runIndent = indent
        runFromTop = fromTop
    }
    for ((row, body) in lines.withIndex()) {
        val trimmed = body.trimStart()
        val match = promptItem.matchEntire(trimmed)
        if (match == null) {
            // Only right under a checkbox item, and only once: elsewhere "Submit" is ordinary text.
            val advance = advanceItem.matchEntire(trimmed)
            if (advance != null && runAdvance == null && run.lastOrNull()?.checked != null && runEnd == row - 1) {
                runAdvance = AdvanceRow(advance.groupValues[2], focused = advance.groupValues[1].isNotEmpty())
            }
            continue
        }
        val indent = body.length - trimmed.length + match.groups[2]!!.range.first
        // "     2. Then run migrations" under item 1 is its description, not item 2. A focus marker
        // written before the digit ("│ ❯ 2." under "│ 1.") shifts it by up to its own width.
        if (run.isNotEmpty() && indent > runIndent + MARKER_WIDTH) continue
        val (marker, digit, label) = match.destructured
        val box = checkbox.find(label)
        val option = PromptOption(
            digit.toInt(), if (box == null) label else label.substring(box.range.last + 1),
            focused = marker.isNotEmpty(), checked = box?.let { it.groupValues[1] != " " },
        )
        val next = (run.lastOrNull()?.number ?: 0) + 1
        when {
            // The top line was a scrolled-off item's description: the prompt's items start here.
            runFromTop && run.size == 1 && indent < runIndent - MARKER_WIDTH -> start(option, indent, fromTop = true)
            // A checkbox under the advance row would put that row between items: not Claude's layout.
            option.number == next && run.isNotEmpty() -> { run.add(option); if (option.checked != null) runAdvance = null }
            option.number == 1 || !seenItem -> { close(); start(option, indent, fromTop = !seenItem) }
            else -> close(broken = true)
        }
        if (run.lastOrNull() === option) runEnd = row
        seenItem = true
    }
    close()
    if (last.options.isEmpty()) return last
    // Rows indented under the last item (its description, wrapped lines) belong to the prompt.
    val below = (lastRow + 1 until lines.size).count { row ->
        val line = lines[row]
        line.isNotBlank() && line.length - line.trimStart().length <= lastIndent
    }
    return if (below <= MAX_ROWS_BELOW_PROMPT) last else PromptOptions()
}

/** [line] without trailing spaces and box borders; a leading border counts as indentation. */
private fun unboxed(line: String): String {
    val text = line.trimEnd().trimEnd('│').trimEnd()
    val lead = text.length - text.trimStart().length
    return if (text.startsWith("│", lead)) text.replaceRange(lead, lead + 1, " ") else text
}

/** Claude Code's final review of a multi-question form: "1. Submit answers", "2. Cancel". */
internal fun isSubmitMenu(options: List<PromptOption>): Boolean =
    options.size == 2 && submitLabel.matches(options[0].label) && options[1].label.equals("Cancel", ignoreCase = true)

internal fun hasSubmitMenu(rows: Sequence<String>): Boolean = isSubmitMenu(parsePromptOptions(rows))
