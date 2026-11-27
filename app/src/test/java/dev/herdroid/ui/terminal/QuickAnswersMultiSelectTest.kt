package dev.herdroid.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAnswersMultiSelectTest {
    @get:Rule val compose = createComposeRule()

    private fun prompt(vararg rows: String) = parsePrompt(rows.asSequence())

    private val fruits = arrayOf(
        "Which fruits do you like?",
        "❯ 1. [ ] Apple",
        "  2. [✔] Banana",
        "  3. [ ] Cherry",
        "  4. [ ] Type something",
        "     Next",
        "──────────",
        "  5. Chat about this",
    )

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private fun state(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    @Test fun checkboxItemsAreTogglesThatSendTheirDigitAndShowTheNextFrame() {
        val keys = mutableListOf<String>()
        var current by mutableStateOf(prompt(*fruits))
        compose.setContent { WoolooMobileTheme { QuickAnswers(current.options, current.advance) { keys.add(it) } } }

        compose.onNodeWithText("Banana").performScrollTo()
            .assert(role(Role.Checkbox)).assert(state(ToggleableState.On))
        compose.onNodeWithText("Apple").performScrollTo()
            .assert(role(Role.Checkbox)).assert(state(ToggleableState.Off)).assertIsSelected()
            .performClick()
        // No optimistic flip: Apple stays unchecked until the screen says otherwise.
        compose.onNodeWithText("Apple").assert(state(ToggleableState.Off))
        assertEquals(listOf("1"), keys)

        current = prompt(*fruits.map { it.replace("[ ] Apple", "[✔] Apple") }.toTypedArray())
        compose.onNodeWithText("Apple").assert(state(ToggleableState.On))
        compose.onNodeWithText("Type something").performScrollTo()
            .assert(role(Role.Checkbox)).assert(state(ToggleableState.Off))
    }

    @Test fun nextMovesTheFocusOntoTheRowAndPressesEnter() {
        val keys = mutableListOf<String>()
        compose.setContent {
            WoolooMobileTheme { prompt(*fruits).let { QuickAnswers(it.options, it.advance) { k -> keys.add(k) } } }
        }
        // Chat about this stays a plain button with its digit.
        compose.onNodeWithText("Chat about this").performScrollTo()
            .assert(role(Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .performClick()
        compose.onNodeWithText("Next").performScrollTo()
            .assert(role(Role.Button)).assertIsNotSelected()
            .assert(SemanticsMatcher("click label") { it.config[SemanticsActions.OnClick].label == "Next question" })
            .performClick()
        assertEquals(listOf("5", "\u001b[B".repeat(4) + "\r"), keys)
    }

    @Test fun aFocusedSubmitRowIsHighlightedAndOnlyPressesEnter() {
        val keys = mutableListOf<String>()
        val focused = prompt("  1. [✔] Hammer", "  2. [ ] Saw", "  3. [ ] Type something", "❯    Submit", "  4. Chat about this")
        compose.setContent { WoolooMobileTheme { QuickAnswers(focused.options, focused.advance) { keys.add(it) } } }
        compose.onNodeWithText("Hammer").assertIsNotSelected()
        compose.onNodeWithText("Submit").performScrollTo().assertIsSelected()
            .assert(SemanticsMatcher("click label") { it.config[SemanticsActions.OnClick].label == "Submit" })
            .performClick()
        assertEquals(listOf("\r"), keys)
    }

    @Test fun singleSelectPromptsHaveNoTogglesOrAdvanceButton() {
        val keys = mutableListOf<String>()
        val color = prompt("❯ 1. Red", "  2. Blue", "  3. Type something.", "  4. Chat about this")
        compose.setContent { WoolooMobileTheme { QuickAnswers(color.options, color.advance) { keys.add(it) } } }
        compose.onAllNodes(role(Role.Checkbox)).assertCountEquals(0)
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithText("Blue").assert(role(Role.Button)).performClick()
        assertEquals(listOf("2"), keys)
    }

    private val next = "\u001b[B".repeat(4) + "\r"
    private val fruitsFocusOnNext = fruits.map {
        it.replace("❯ 1. [ ] Apple", "  1. [ ] Apple").replace("     Next", "❯    Next")
    }.toTypedArray()

    @Test fun afterNextEveryButtonWaitsWhileTheQuestionIsTheSame() {
        val keys = mutableListOf<String>()
        var current by mutableStateOf(prompt(*fruits))
        compose.setContent { WoolooMobileTheme { QuickAnswers(current.options, current.advance) { keys.add(it) } } }
        compose.onNodeWithText("Next").performScrollTo().performClick()
        // The intermediate frame: the focus is on Next, the question hasn't changed yet.
        current = prompt(*fruitsFocusOnNext)
        compose.onNodeWithText("Next").performScrollTo().assertIsNotEnabled().performClick()
        // A toggle or digit now would land on the next question.
        compose.onNodeWithText("Cherry").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("Chat about this").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals("arrows or digits sent now would answer the next question", listOf(next), keys)
    }

    @Test fun tapsBeforeTheNextCompositionAreHeldToo() {
        val keys = mutableListOf<String>()
        compose.setContent { WoolooMobileTheme { prompt(*fruits).let { QuickAnswers(it.options, it.advance) { k -> keys.add(k) } } } }
        val nextButton = compose.onNodeWithText("Next").performScrollTo()
        val cherry = compose.onNodeWithText("Cherry").performScrollTo()
        // Fetch both nodes first: a query waits for idle, which would recompose in between.
        val nextClick = nextButton.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cherryClick = cherry.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            nextClick()
            nextClick()
            cherryClick()
        }
        assertEquals("one Next, and no toggle that would land on the next question", listOf(next), keys)
    }

    @Test fun theNextQuestionTakesTapsAgain() {
        val keys = mutableListOf<String>()
        var current by mutableStateOf(prompt(*fruits))
        compose.setContent { WoolooMobileTheme { QuickAnswers(current.options, current.advance) { keys.add(it) } } }
        compose.onNodeWithText("Next").performScrollTo().performClick()
        current = prompt("Pick a color", "❯ 1. Red", "  2. Blue", "  3. Type something.", "  4. Chat about this")
        compose.onNodeWithText("Blue").assertIsEnabled().performClick()
        assertEquals(listOf(next, "2"), keys)
    }

    @Test fun aLostNextStopsWaitingAfterATimeout() {
        val keys = mutableListOf<String>()
        compose.setContent { WoolooMobileTheme { prompt(*fruits).let { QuickAnswers(it.options, it.advance) { k -> keys.add(k) } } } }
        val nextButton = compose.onNodeWithText("Next").performScrollTo()
        // The same screen keeps coming, as if the keys were lost; only the clock moves.
        compose.mainClock.autoAdvance = false
        // A gesture needs frames the paused clock doesn't give; the click action runs right away.
        nextButton.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(next), keys)
        compose.waitForIdle() // applies the click's state change; the clock stays put
        compose.mainClock.advanceTimeBy(AdvancePendingMillis / 2)
        compose.onNodeWithText("Apple").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(AdvancePendingMillis / 2 + 100)
        compose.onNodeWithText("Apple").assertIsEnabled()
        nextButton.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(next, next), keys)
    }

    @Test fun withTheFocusInTheFreeTextFieldDigitsMoveUpFirst() {
        val keys = mutableListOf<String>()
        val field = prompt(*fruits.map {
            it.replace("❯ 1. [ ] Apple", "  1. [ ] Apple").replace("  4. [ ] Type something", "❯ 4. [ ] Type something")
        }.toTypedArray())
        compose.setContent { WoolooMobileTheme { QuickAnswers(field.options, field.advance) { keys.add(it) } } }
        compose.onNodeWithText("Apple").performScrollTo().performClick()
        compose.onNodeWithText("Type something").performScrollTo().performClick()
        compose.onNodeWithText("Chat about this").performScrollTo().performClick()
        assertEquals(listOf("1", "4", "5").map { "\u001b[A$it" }, keys)
    }

    @Test fun comingBackToAPromptAlreadyAdvancedFromAllowsNextAgain() {
        val keys = mutableListOf<String>()
        val first = prompt(*fruits)
        var current by mutableStateOf(first)
        compose.setContent { WoolooMobileTheme { QuickAnswers(current.options, current.advance) { keys.add(it) } } }
        compose.onNodeWithText("Next").performScrollTo().performClick()
        // The next question, then back (←) to the same screen as before.
        current = prompt("❯ 1. Red", "  2. Blue", "  3. Type something.", "  4. Chat about this")
        compose.waitForIdle()
        current = first
        compose.onNodeWithText("Next").performScrollTo().performClick()
        assertEquals(List(2) { "\u001b[B".repeat(4) + "\r" }, keys)
    }
}
