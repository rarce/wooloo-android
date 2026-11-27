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
 * question "Tools" (Hammer, Saw, Drill), whose advance row says Submit instead of Next. The 5x
 * frames were streamed by `terminal session control`, each after one input record.
 */
/** The prompt on screen after every record of fixture [name], applied as [TerminalController] does. */
internal fun claudeMultiSelectFrame(name: String): PromptOptions {
    val text = checkNotNull(ClaudeMultiSelectFixturesTest::class.java.getResource("/prompts/claude-multiselect/$name.ndjson")).readText()
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

class ClaudeMultiSelectFixturesTest {
    private fun frame(name: String) = claudeMultiSelectFrame(name)

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

    @Test fun aDigitTypedWithTheFocusOnTheFreeTextFieldGoesIntoIt() {
        // Focus on "4. [ ] Type something": digits must first move the focus up out of the field.
        val field = frame("50-focus-type")
        assertEquals(listOf(4), field.focus())
        assertEquals(up + "2", field.digitInput(2, claude = true))
        assertEquals(up + "4", field.digitInput(4, claude = true))
        assertEquals(up + "5", field.digitInput(5, claude = true))
        // What a bare 2 did there: typed into the field (which checks itself), Banana untouched.
        val typed = frame("51-digit2-on-type")
        assertEquals("2", typed.options[3].label)
        assertEquals(listOf(false, false, false, true, null), typed.checked())
        assertEquals(listOf(4), typed.focus())
        assertEquals(up + "2", typed.digitInput(2, claude = true))
    }

    @Test fun upThenTheDigitTogglesAndKeepsTheTypedText() {
        val apple = frame("52-up-then-digit1-from-type")
        assertEquals(listOf(true, false, false, true, null), apple.checked())
        assertEquals("2", apple.options[3].label)
        assertEquals(listOf(3), apple.focus())
        assertEquals("1", apple.digitInput(1, claude = true))
        val field = frame("58-up-then-digit4-from-type")
        assertEquals(listOf(true, false, false, false, null), field.checked())
        assertEquals(listOf(3), field.focus())
    }

    @Test fun digitsElsewhereAreJustTheDigit() {
        assertEquals("2", frame("01-initial").digitInput(2, claude = true))
        assertEquals("5", frame("08-focus-next").digitInput(5, claude = true))
        assertEquals("5", frame("40-focus-chat").digitInput(5, claude = true))
        // Single-select prompts are unchanged here.
        assertEquals("1", frame("09-q2-after-next").digitInput(1, claude = true))
    }

    @Test fun otherAgentsGetTheBareDigit() {
        assertEquals("2", frame("50-focus-type").digitInput(2, claude = false))
    }

    @Test fun composerChipsSendWhatTheQuickAnswersSend() {
        val field = frame("50-focus-type")
        assertEquals((1..5).map { up + it }, composerAnswers(field, claude = true).map { it.input })
        assertEquals("1 Apple", composerAnswers(field, claude = true).first().label)
        assertEquals((1..5).map { "$it" }, composerAnswers(field, claude = false).map { it.input })
        assertEquals((1..5).map { "$it" }, composerAnswers(frame("01-initial"), claude = true).map { it.input })
    }
}
