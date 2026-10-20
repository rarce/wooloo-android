package dev.herdroid.terminal

import android.app.Activity
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalViewInputTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java)
    private lateinit var view: TerminalView
    private lateinit var otherEditor: EditText
    private val output = mutableListOf<String>()
    private var reads = 0

    @Before fun setUp() {
        val activity = activityController.setup().get()
        view = TerminalView(activity, TerminalModel(), ModifierState())
        view.listener = object : TerminalViewListener {
            override fun onReadScreen() { reads++ }
            override fun onInput(data: String) { output.add(data) }
            override fun onGridSizeChanged(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {}
            override fun onScroll(up: Boolean, lines: Int, column: Int, row: Int) {}
            override fun onTap(column: Int, row: Int) {}
            override fun onLongPress(column: Int, row: Int) {}
            override fun onFontSizeChanged(sp: Float) {}
        }
        otherEditor = EditText(activity)
        activity.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(400, 400))
            addView(otherEditor)
        })
        activityController.visible()
        view.requestFocus()
    }

    @After fun tearDown() { activityController.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun accessibleScreenContainsOutputAndOffersReadActionWithoutSendingInput() {
        view.model.applyFrame(80, 24, true, "review output\r\n$ ".toByteArray())
        val node = view.createAccessibilityNodeInfo()
        assertTrue(node.text.toString().contains("review output"))
        assertTrue(node.actionList.any { it.id == dev.herdroid.R.id.read_terminal_text })
        assertTrue(view.performAccessibilityAction(dev.herdroid.R.id.read_terminal_text, null))
        assertEquals(1, reads)
        assertTrue(output.isEmpty())
        view.model.applyFrame(80, 24, true, "updated output".toByteArray())
        assertTrue(view.createAccessibilityNodeInfo().text.toString().contains("updated output"))
    }

    @Test fun editorAllowsDictationAndExposesTextAndCursor() {
        val info = EditorInfo()
        val connection = requireNotNull(view.onCreateInputConnection(info))
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertTrue(connection.commitText("hola mundo", 1))
        assertEquals("hola mundo", connection.getTextBeforeCursor(100, 0).toString())
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals("hola mundo", extracted.text.toString())
        assertEquals(10, extracted.selectionEnd)
    }

    @Test fun partialDictationRevisionsAndFinalCommitDoNotDuplicateWords() {
        val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        connection.setComposingText("hola", 1)
        connection.setComposingText("hola mundo", 1)
        connection.setComposingText("hola móvil", 1)
        connection.commitText("hola móvil", 1)
        assertEquals(listOf("hola", " mundo") + List(4) { "\u007f" } + "óvil", output)
        assertEquals("hola móvil", connection.getTextBeforeCursor(100, 0).toString())
    }

    @Test fun emojiRevisionDoesNotSendAnOrphanSurrogate() {
        val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        connection.setComposingText("😀", 1)
        connection.commitText("😁", 1)
        assertEquals(listOf("😀", "\u007f", "😁"), output)
        connection.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("", connection.getTextBeforeCursor(100, 0).toString())
        assertEquals("\u007f", output.last())
    }

    @Test fun imeCanMoveCursorAndReplaceASelection() {
        val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        connection.commitText("abcd", 1)
        connection.setSelection(1, 3)
        connection.commitText("X", 1)
        assertEquals("aXd", connection.getExtractedText(ExtractedTextRequest(), 0).text.toString())
        assertEquals(listOf("abcd", "\u001b[D", "\u001b[C") + List(3) { "\u007f" } + listOf("Xd", "\u001b[D"), output)
    }

    @Test fun navigationEndsCompositionAndRejectsLateImeUpdates() {
        val old = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        old.setComposingText("hola", 1)
        view.sendSpecial(SpecialKey.LEFT)
        assertFalse(old.commitText("late dictation", 1))
        val next = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        next.commitText("X", 1)
        assertEquals(listOf("hola", "\u001b[D", "X"), output)
    }

    @Test fun ctrlShortcutAndEnterDiscardContextWithoutErasingRemoteText() {
        val initial = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        initial.commitText("hello", 1)
        view.prepareModifierInput()
        view.modifiers.ctrl = true
        val shortcut = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        shortcut.commitText("c", 1)
        assertFalse(shortcut.commitText("late", 1))
        val next = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        next.commitText("next\n", 1)
        assertFalse(next.commitText("late", 1))
        assertEquals(listOf("hello", "\u0003", "next\r"), output)
    }

    @Test fun shiftLeftIsOneShotAndShiftLockSurvivesRepeats() {
        view.modifiers.shift = true
        view.sendSpecial(SpecialKey.LEFT)
        view.sendSpecial(SpecialKey.LEFT)
        view.modifiers.shift = true
        view.modifiers.shiftLocked = true
        repeat(2) { view.sendSpecial(SpecialKey.LEFT) }
        assertEquals(listOf("\u001b[1;2D", "\u001b[D", "\u001b[1;2D", "\u001b[1;2D"), output)
        assertTrue(view.modifiers.shift)
    }

    @Test fun hardwareShiftCombinesWithBarCtrlAltAndReleases() {
        view.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        view.modifiers.ctrl = true
        view.modifiers.alt = true
        view.sendSpecial(SpecialKey.LEFT)
        view.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        view.sendSpecial(SpecialKey.LEFT)
        assertEquals(listOf("\u001b[1;8D", "\u001b[D"), output)
    }

    @Test fun stickyShiftAlsoAppliesToHardwareArrows() {
        view.modifiers.shift = true
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf("\u001b[1;2D"), output)
        assertFalse(view.modifiers.shift)
    }

    @Test fun backspaceWithEmptyImeContextStillReachesRemotePrompt() {
        val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        connection.deleteSurroundingText(0, 0)
        connection.deleteSurroundingText(2, 1)
        assertEquals(listOf("\u007f", "\u007f", "\u007f", "\u001b[3~"), output)
    }

    @Test fun readOnlyViewRejectsImeAndBarInput() {
        val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        view.inputEnabled = false
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertFalse(connection.commitText("blocked", 1))
        view.sendSpecial(SpecialKey.ENTER)
        view.sendText("blocked")
        view.sendPaste("blocked")
        assertTrue(output.isEmpty())
    }

    @Test fun activePaneRecoversLostFocusButAllowsAnotherEditorWhileSuspended() {
        activityController.windowFocusChanged(true)
        view.retainInputFocus = true
        otherEditor.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.hasFocus())
        view.retainInputFocus = false
        otherEditor.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(otherEditor.hasFocus())
        view.retainInputFocus = true
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.hasFocus())
        val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }
}
