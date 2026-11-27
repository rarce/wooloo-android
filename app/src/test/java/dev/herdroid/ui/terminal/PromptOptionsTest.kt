package dev.herdroid.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptOptionsTest {
    private fun parse(vararg rows: String) = parsePromptOptions(rows.asSequence())

    @Test fun readsThreeOptionsAndTheFocusedOne() {
        val options = parse(
            "Do you want to make this edit to Foo.kt?",
            "❯ 1. Yes",
            "  2. Yes, allow all edits during this session (shift+tab)",
            "  3. No, and tell Claude what to do differently (esc)",
        )
        assertEquals(
            listOf(
                PromptOption(1, "Yes", focused = true),
                PromptOption(2, "Yes, allow all edits during this session (shift+tab)"),
                PromptOption(3, "No, and tell Claude what to do differently (esc)"),
            ),
            options,
        )
    }

    @Test fun readsMoreThanThreeOptionsUpToSubmit() {
        val options = parse(
            "☐ Database",
            "Which database should we use?",
            "",
            "❯ 1. Postgres",
            "  2. MySQL",
            "  3. SQLite",
            "  4. DuckDB",
            "  5. Type something.",
            "  6. Submit",
            "",
            "Enter to select · ↑/↓ to navigate · Esc to cancel",
        )
        assertEquals((1..6).toList(), options.map { it.number })
        assertEquals("Type something.", options[4].label)
        assertEquals("Submit", options[5].label)
        assertEquals(listOf(1), options.filter { it.focused }.map { it.number })
    }

    @Test fun trimsBoxBordersAndAcceptsParenthesisNumbers() {
        val options = parse(
            "╭──────────────────────────────╮",
            "│ Allow this command?          │",
            "│   1) Yes                     │",
            "│ › 2) No                      │",
            "╰──────────────────────────────╯",
        )
        assertEquals(listOf(PromptOption(1, "Yes"), PromptOption(2, "No", focused = true)), options)
    }

    @Test fun skipsDescriptionAndWrappedLinesBetweenItems() {
        val options = parse(
            "❯ 1. Postgres",
            "     Relational database with strong consistency",
            "     and a rich extension ecosystem.",
            "  2. Redis",
            "     In-memory key-value store",
            "  3. Type something.",
        )
        assertEquals(listOf("Postgres", "Redis", "Type something."), options.map { it.label })
    }

    @Test fun ignoresNumberedOutputWithoutAFocusMarker() {
        assertTrue(parse("Steps:", "1. Install", "2. Configure", "3. Run").isEmpty())
        assertTrue(parse("❯ 1. Only one option").isEmpty())
        assertTrue(parse("❯ 1. Yes", "> 2. No").isEmpty())
        assertTrue(parse("❯ 1. Yes", "  3. No").isEmpty())
    }

    @Test fun takesTheLastPromptOnScreen() {
        val options = parse(
            "❯ 1. Old yes",
            "  2. Old no",
            "Some output",
            "  1. Approve",
            "  2. Reject",
            "❯ 3. Defer",
        )
        assertEquals(listOf("Approve", "Reject", "Defer"), options.map { it.label })
        assertEquals(3, options.single { it.focused }.number)
    }

    @Test fun aLaterNumberedListRetiresAnEarlierPrompt() {
        // Claude's input echoed "> 1. do X" and its plan list; the prompt below has no marker yet.
        assertTrue(parse("> 1. do X", "  2. do Y", "Do you want to proceed?", "  1. Yes", "  2. No").isEmpty())
        assertTrue(parse("❯ 1. Yes", "  2. No", "Next steps:", "1. Install", "2. Run").isEmpty())
        assertTrue(parse("❯ 1. Yes", "  2. No", "❯ 1. Again", "> 2. Twice").isEmpty())
        assertTrue(parse("❯ 1. Yes", "  2. No", "  1. One", "  3. Three").isEmpty())
        // A later prompt whose first item isn't recognized still retires the earlier one.
        assertTrue(parse("> 1. do X", "  2. do Y", "● 1. Allow once", "  2. Allow always").isEmpty())
    }

    @Test fun aLoneLaterItemKeepsThePrompt() {
        assertEquals(listOf("Yes", "No"), parse("❯ 1. Yes", "  2. No", "Note:", "1. see above").map { it.label })
    }

    @Test fun dropsMultiSelectCheckboxes() {
        val options = parse("❯ 1. [ ] Lint", "  2. [✔] Tests", "  3. [x] Build", "  4. Submit")
        assertEquals(listOf("Lint", "Tests", "Build", "Submit"), options.map { it.label })
    }

    @Test fun aNumberedListFarAboveTheBottomIsHistory() {
        // A quoted plan higher up, then output and a y/N question that isn't a numbered prompt.
        val rows = listOf("> 1. Add the migration", "  2. Run the tests") +
            (1..8).map { "output line $it" } + listOf("Proceed? (y/N)")
        assertTrue(parse(*rows.toTypedArray()).isEmpty())
    }

    @Test fun aClaudeMenuWithItsHintLinesBelowStillParses() {
        val options = parse(
            "Do you want to proceed?",
            "❯ 1. Yes",
            "  2. Yes, and don't ask again for this command",
            "     Applies to this project",
            "  3. No, and tell Claude what to do differently (esc)",
            "",
            "────────────────────────────────",
            "Enter to select · ↑/↓ to navigate · Esc to cancel",
            "",
            "",
        )
        assertEquals(listOf(1, 2, 3), options.map { it.number })
    }

    @Test fun aPromptWhoseFirstItemsScrolledOffStillParses() {
        val options = parse("  4. Redis", "❯ 5. SQLite", "  6. Submit", "", "Enter to select")
        assertEquals(listOf(4, 5, 6), options.map { it.number })
        assertEquals(5, options.single { it.focused }.number)
    }

    @Test fun aStrayItemAfterAnotherRunIsStillBroken() {
        assertTrue(parse("❯ 1. Yes", "  2. No", "  3. Maybe", "Some output", "  3. stray").isEmpty())
        // Only the first item on screen may start above 1.
        assertTrue(parse("Some output", "  1. One", "  2. Two", "❯ 4. Four", "  5. Five").isEmpty())
    }

    @Test fun aDeeperIndentedNumberedDescriptionIsNotAnItem() {
        val options = parse(
            "❯ 1. Apply the plan",
            "     2. Then run migrations",
            "  2. Edit the plan",
            "  3. Cancel",
        )
        assertEquals(listOf("Apply the plan", "Edit the plan", "Cancel"), options.map { it.label })
    }

    @Test fun aScrolledOffDescriptionAtTheTopDoesNotStartTheRun() {
        val options = parse("     2. Then run migrations", "  3. Redis", "❯ 4. SQLite", "  5. Submit")
        assertEquals(listOf(3, 4, 5), options.map { it.number })
    }

    @Test fun aPhoneWidthQuestionWithWrappedRowsBelowStillParses() {
        val options = parse(
            "Which database should we use?",
            "❯ 1. Postgres",
            "     Relational database with",
            "     strong consistency",
            "  2. Redis",
            "     In-memory key-value store",
            "  3. Type something.",
            "     Describe the database",
            "     you want if none of",
            "     these fit",
            "",
            "────────────────────────────",
            "  Chat about this",
            "",
            "Enter to select · ↑/↓ to",
            "navigate · Esc to cancel",
        )
        assertEquals(listOf(1, 2, 3), options.map { it.number })
    }

    @Test fun keepsBracketsInsideALabel() {
        assertEquals("Mark [x] as done", parse("❯ 1. Mark [x] as done", "  2. Skip")[0].label)
    }

    @Test fun stopsAtNine() {
        val rows = (1..10).map { if (it == 1) "❯ $it. Option $it" else "  $it. Option $it" }
        assertEquals((1..9).toList(), parse(*rows.toTypedArray()).map { it.number })
    }

    private fun prompt(vararg rows: String) = parsePrompt(rows.asSequence())

    @Test fun checkboxItemsKeepTheirStateAndLoseThePrefix() {
        val options = parse("❯ 1. [ ] Apple", "  2. [✔] Banana", "  3. [x] Cherry", "  4. Chat about this")
        assertEquals(listOf("Apple", "Banana", "Cherry", "Chat about this"), options.map { it.label })
        assertEquals(listOf(false, true, true, null), options.map { it.checked })
    }

    @Test fun bracketsInsideALabelAreNotACheckbox() {
        val options = parse("❯ 1. Mark [x] as done", "  2. Leave it")
        assertEquals(listOf(PromptOption(1, "Mark [x] as done", focused = true), PromptOption(2, "Leave it")), options)
    }

    @Test fun nextOrSubmitRightUnderACheckboxIsTheAdvanceRow() {
        val next = prompt("❯ 1. [ ] Apple", "  2. [ ] Banana", "     Next", "  3. Chat about this")
        assertEquals(AdvanceRow("Next"), next.advance)
        assertEquals(listOf(1, 2, 3), next.options.map { it.number })
        val focused = prompt("  1. [ ] Apple", "  2. [✔] Banana", "❯    Submit", "  3. Chat about this")
        assertEquals(AdvanceRow("Submit", focused = true), focused.advance)
        assertEquals("the row holds the one focus", emptyList<Int>(), focused.options.filter { it.focused }.map { it.number })
    }

    @Test fun nextOrSubmitElsewhereIsOrdinaryText() {
        assertNull("not under a checkbox", prompt("❯ 1. Yes", "  2. No", "     Submit", "  3. Later").advance)
        assertNull("not right under it", prompt("❯ 1. [ ] Apple", "  2. [ ] Banana", "", "     Next").advance)
        assertNull("not exactly Next", prompt("❯ 1. [ ] Apple", "  2. [ ] Banana", "     Next step").advance)
        // Without a focus on an item or the row, there is no prompt at all.
        assertEquals(PromptOptions(), prompt("  1. [ ] Apple", "  2. [ ] Banana", "     Next"))
        assertEquals("two focus markers", PromptOptions(), prompt("❯ 1. [ ] Apple", "  2. [ ] Banana", "❯    Next"))
    }
}
