package dev.herdroid.terminal

import android.app.Activity
import android.os.Looper
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The quick answers' keys go out exactly as given, whatever the extra-keys bar has armed. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalViewRawInputTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java)
    private val modifiers = ModifierState()
    private lateinit var view: TerminalView
    private val output = mutableListOf<String>()

    @Before fun setUp() {
        val activity = activityController.setup().get()
        view = TerminalView(activity, TerminalModel(), modifiers)
        view.listener = object : TerminalViewListener {
            override fun onInput(data: String) { output.add(data) }
            override fun onGridSizeChanged(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {}
            override fun onScroll(up: Boolean, lines: Int, column: Int, row: Int) {}
            override fun onTap(column: Int, row: Int) {}
            override fun onLongPress(column: Int, row: Int) {}
            override fun onFontSizeChanged(sp: Float) {}
        }
        activity.setContentView(LinearLayout(activity).apply { addView(view, LinearLayout.LayoutParams(400, 400)) })
        activityController.visible()
        view.requestFocus()
    }

    @After fun tearDown() { activityController.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }

    private val next = "\u001b[B\u001b[B\u001b[B\u001b[B\r"

    @Test fun stickyCtrlNeitherAppliesToNorIsUsedUpByRawKeys() {
        modifiers.ctrl = true
        view.sendRaw(next)
        view.sendRaw("3")
        assertEquals(listOf(next, "3"), output)
        assertTrue("still armed for the next real key", modifiers.ctrl)
        view.sendText("c")
        assertEquals("\u0003", output.last())
        assertFalse(modifiers.ctrl)
    }

    @Test fun lockedAltAndShiftStayAndAddNothing() {
        modifiers.alt = true; modifiers.altLocked = true
        modifiers.shift = true; modifiers.shiftLocked = true
        view.sendRaw("2")
        view.sendRaw("\r")
        assertEquals(listOf("2", "\r"), output)
        assertTrue(modifiers.alt && modifiers.altLocked && modifiers.shift && modifiers.shiftLocked)
    }

    @Test fun readOnlyViewSendsNothing() {
        assertTrue("says the keys went out", view.sendRaw("2"))
        view.inputEnabled = false
        assertFalse(view.sendRaw("1"))
        assertEquals(listOf("2"), output)
    }
}
