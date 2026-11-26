package dev.herdroid.ui.terminal

import androidx.compose.runtime.Immutable

/** One numbered choice of the agent's active prompt; [number] is the key that selects it. */
@Immutable
data class PromptOption(val number: Int, val label: String, val focused: Boolean = false)

/** Shown for a blocked agent whose prompt couldn't be read from the screen. */
internal val FallbackAnswers = listOf(PromptOption(1, "", focused = true), PromptOption(2, ""), PromptOption(3, ""))

// "❯ 1. Yes", "  2) No": an optional focus marker, a single-key number and a label.
private val promptItem = Regex("""(?:([❯›>])\s*)?([1-9])[.)]\s+(\S.*?)""")
private val submitLabel = Regex("""Submit (?:answers|responses)""", RegexOption.IGNORE_CASE)

/**
 * The options of the active numbered prompt in [rows] (top to bottom): the last run of consecutive
 * items 1, 2, 3… with exactly one focus marker. Lines between items (descriptions, wrapped labels)
 * are allowed; box borders are ignored. A numbered list without a focus marker is ordinary output.
 */
internal fun parsePromptOptions(rows: Sequence<String>): List<PromptOption> {
    var last = emptyList<PromptOption>()
    var run = ArrayList<PromptOption>()
    fun close() {
        if (run.size >= 2 && run.count { it.focused } == 1) last = run
        run = ArrayList()
    }
    for (line in rows) {
        val match = promptItem.matchEntire(line.trim().trim('│').trim()) ?: continue
        val (marker, digit, label) = match.destructured
        val option = PromptOption(digit.toInt(), label, focused = marker.isNotEmpty())
        when (option.number) {
            run.size + 1 -> run.add(option)
            1 -> { close(); run.add(option) }
            else -> close()
        }
    }
    close()
    return last
}

/** Claude Code's final review of a multi-question form: "1. Submit answers", "2. Cancel". */
internal fun isSubmitMenu(options: List<PromptOption>): Boolean =
    options.size == 2 && submitLabel.matches(options[0].label) && options[1].label.equals("Cancel", ignoreCase = true)

internal fun hasSubmitMenu(rows: Sequence<String>): Boolean = isSubmitMenu(parsePromptOptions(rows))
