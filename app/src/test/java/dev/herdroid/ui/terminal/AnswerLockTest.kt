package dev.herdroid.ui.terminal

import android.app.Activity
import android.os.Looper
import android.widget.LinearLayout
import dev.herdroid.terminal.ModifierState
import dev.herdroid.terminal.SpecialKey
import dev.herdroid.terminal.TerminalModel
import dev.herdroid.terminal.TerminalView
import dev.herdroid.terminal.TerminalViewListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** When the quick answers hold their taps: [AnswerLock] on its own and wired to a [TerminalView] as the screen does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AnswerLockTest {
    private var time = 0L
    private val lock = AnswerLock { time }
    private val sent = mutableListOf<String>()
    private val send: (String) -> Boolean = { sent.add(it) }

    private fun prompt(vararg rows: String) = parsePrompt(rows.asSequence())
    private val fruits = prompt("❯ 1. [ ] Apple", "  2. [ ] Banana", "  3. [ ] Type something", "     Next", "  4. Chat about this")
    private val color = prompt("Pick a color", "❯ 1. Red", "  2. Blue", "  3. Type something.", "  4. Chat about this")
    private val next = "\u001b[B".repeat(3) + "\r"

    private val activityController = Robolectric.buildActivity(Activity::class.java)

    @After fun tearDown() {
        if (activityController.get() != null) activityController.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun keysThatDidNotGoOutHoldNothing() {
        lock.advance(fruits) { false }
        lock.advance(fruits, send)
        assertEquals(listOf(next), sent)
    }

    @Test fun theDeadlineIsAbsolute() {
        lock.advance(fruits, send)
        time = AdvancePendingMillis - 1
        lock.advance(fruits, send)
        // No timer had to run (the quick answers were hidden): the deadline alone lets taps through.
        time = AdvancePendingMillis
        lock.advance(fruits, send)
        assertEquals(listOf(next, next), sent)
    }

    @Test fun aClaudeSingleSelectAnswerHoldsUntilAnotherQuestion() {
        lock.digit(color, 2, claude = true, send)
        lock.digit(color, 1, claude = true, send)
        lock.observe(color.copy(options = color.options.map { it.copy(focused = it.number == 2) }))
        lock.digit(color, 1, claude = true, send)
        assertEquals("the same question with the focus moved still holds", listOf("2"), sent)
        lock.observe(prompt("❯ 1. Submit answers", "  2. Cancel"))
        lock.digit(color, 1, claude = true, send)
        assertEquals(listOf("2", "1"), sent)
    }

    @Test fun togglesAndOtherAgentsDigitsDoNotHold() {
        lock.digit(fruits, 2, claude = true, send)
        lock.digit(fruits, 1, claude = true, send)
        lock.digit(color, 2, claude = false, send)
        lock.digit(color, 1, claude = false, send)
        assertEquals(listOf("2", "1", "2", "1"), sent)
        assertTrue(lock.holds.isEmpty())
    }

    @Test fun otherInputHoldsUntilTheScreenChangesOrBriefly() {
        lock.inputSent(fruits)
        lock.advance(fruits, send)
        lock.observe(fruits)
        lock.advance(fruits, send)
        assertTrue("nothing while the screen is unchanged", sent.isEmpty())
        time = StaleInputMillis // Up at the top changes nothing
        lock.advance(fruits, send)
        assertEquals(listOf(next), sent)
    }

    @Test fun typingWithoutAPromptKeepsOneHold() {
        // Nothing observes or expires holds while no quick answers are shown.
        repeat(1000) {
            lock.inputSent(PromptOptions())
            time += 10
        }
        assertEquals(1, lock.holds.size)
    }

    @Test fun otherInputDoesNotReplaceANextHold() {
        lock.advance(fruits, send)
        repeat(50) {
            lock.inputSent(fruits)
            time += 10
        }
        assertEquals(2, lock.holds.size)
        time = StaleInputMillis + 600 // the last key's hold is over, the Next's isn't
        lock.observe(fruits)
        lock.advance(fruits, send)
        assertEquals("still held by the Next", listOf(next), sent)
        lock.observe(color) // the next question
        lock.digit(color, 2, claude = false, send)
        assertEquals(listOf(next, "2"), sent)
    }

    @Test fun anExtraKeyFromTheViewHoldsButTheQuickAnswersOwnKeysDoNot() {
        val activity = activityController.setup().get()
        val output = mutableListOf<String>()
        val view = TerminalView(activity, TerminalModel(), ModifierState())
        // As the terminal screen wires it: every input to the pane passes the lock first.
        view.listener = object : TerminalViewListener {
            override fun onInput(data: String) { lock.inputSent(fruits); output.add(data) }
            override fun onGridSizeChanged(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {}
            override fun onScroll(up: Boolean, lines: Int, column: Int, row: Int) {}
            override fun onTap(column: Int, row: Int) {}
            override fun onLongPress(column: Int, row: Int) {}
            override fun onFontSizeChanged(sp: Float) {}
        }
        activity.setContentView(LinearLayout(activity).apply { addView(view, LinearLayout.LayoutParams(400, 400)) })
        activityController.visible()
        view.requestFocus()

        lock.digit(fruits, 2, claude = true, view::sendRaw)
        assertTrue("its own keys aren't other input", lock.holds.isEmpty())
        view.sendSpecial(SpecialKey.UP)
        assertEquals(1, lock.holds.size)
        lock.digit(fruits, 1, claude = true, view::sendRaw)
        assertEquals(listOf("2", "\u001b[A"), output)
    }
}
