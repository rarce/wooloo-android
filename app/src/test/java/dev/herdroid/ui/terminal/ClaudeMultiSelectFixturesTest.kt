package dev.herdroid.ui.terminal

import dev.herdroid.herdr.herdrJson
import dev.herdroid.terminal.TerminalModel
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Real frames of Claude Code's AskUserQuestion with a multiSelect question, captured with
 * `herdr terminal session observe` at the pane's size (131x50; only the cwd redacted). Q1 "Fruits" (multiSelect:
 * Apple, Banana, Cherry), Q2 "Color" (single: Red, Blue); a second form has one multiSelect
 * question "Tools" (Hammer, Saw, Drill), whose advance row says Submit instead of Next.
 */
class ClaudeMultiSelectFixturesTest {
    /** The screen after every record of [name], applied as [TerminalController] does. */
    private fun frame(name: String): PromptOptions {
        val text = checkNotNull(javaClass.getResource("/prompts/claude-multiselect/$name.ndjson")).readText()
        val model = TerminalModel()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val record = herdrJson.parseToJsonElement(line).jsonObject
            model.applyFrame(
                record["width"]!!.jsonPrimitive.int, record["height"]!!.jsonPrimitive.int,
                record["full"]!!.jsonPrimitive.boolean, Base64.getDecoder().decode(record["bytes"]!!.jsonPrimitive.content),
            )
        }
        return parsePrompt((0 until model.emulator.rows).asSequence().map(model::rowText))
    }

    private fun PromptOptions.checked() = options.map { it.checked }
    private fun PromptOptions.focus() = options.filter { it.focused }.map { it.number }

    private val down = "\u001b[B"
    private val up = "\u001b[A"

    @Test fun uncheckedQuestionHasCheckboxItemsAndANextRow() {
        val prompt = frame("01-initial")
        assertEquals(listOf("Apple", "Banana", "Cherry", "Type something", "Chat about this"), prompt.options.map { it.label })
        assertEquals((1..5).toList(), prompt.options.map { it.number })
        assertEquals(listOf(false, false, false, false, null), prompt.checked())
        assertEquals(listOf(1), prompt.focus())
        assertEquals(AdvanceRow("Next"), prompt.advance)
        assertEquals(down.repeat(4) + "\r", prompt.advanceInput)
    }

    @Test fun aDigitTogglesThatItemAndLeavesTheFocus() {
        val prompt = frame("02-after-digit2")
        assertEquals(listOf(false, true, false, false, null), prompt.checked())
        assertEquals(listOf(1), prompt.focus())
    }

    @Test fun partlyCheckedQuestions() {
        assertEquals(listOf(false, false, true, false, null), frame("04-partly-checked").checked())
        // Space, then Enter, toggle the focused Apple.
        assertEquals(listOf(true, false, true, false, null), frame("05-after-space").checked())
        assertEquals(listOf(false, false, true, false, null), frame("06-after-enter-on-focused").checked())
        // "Type something" is a checkbox too.
        assertEquals(listOf(false, false, true, true, null), frame("07-after-digit4").checked())
    }

    @Test fun focusOnTheNextRowKeepsThePrompt() {
        for (name in listOf("08-focus-next", "41-up-from-chat")) {
            val prompt = frame(name)
            assertEquals(name, 5, prompt.options.size)
            assertEquals(name, emptyList<Int>(), prompt.focus())
            assertEquals(name, AdvanceRow("Next", focused = true), prompt.advance)
            assertEquals(name, "\r", prompt.advanceInput)
        }
        assertEquals(listOf(false, false, true, false, null), frame("41-up-from-chat").checked())
    }

    @Test fun focusBelowTheNextRowGoesUpToIt() {
        val prompt = frame("40-focus-chat")
        assertEquals(listOf(5), prompt.focus())
        assertEquals(up + "\r", prompt.advanceInput)
    }

    @Test fun singleSelectQuestionAndReviewAreUnchanged() {
        val color = frame("09-q2-after-next")
        assertEquals(listOf("Red", "Blue", "Type something.", "Chat about this"), color.options.map { it.label })
        assertEquals(listOf(null, null, null, null), color.checked())
        assertNull(color.advance)
        assertNull(color.advanceInput)
        for (name in listOf("12-review", "23-single-after-submit")) {
            val review = frame(name)
            assertTrue(name, isSubmitMenu(review.options))
            assertNull(name, review.advance)
        }
    }

    @Test fun aLoneMultiSelectQuestionSubmits() {
        val initial = frame("20-single-initial")
        assertEquals(listOf("Hammer", "Saw", "Drill", "Type something", "Chat about this"), initial.options.map { it.label })
        assertEquals(AdvanceRow("Submit"), initial.advance)
        assertEquals(listOf(true, false, true, false, null), frame("21-single-partly").checked())
        val focused = frame("22-single-focus-submit")
        assertEquals(AdvanceRow("Submit", focused = true), focused.advance)
        assertEquals("\r", focused.advanceInput)
        assertFalse(isSubmitMenu(focused.options))
    }
}
