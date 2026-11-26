package dev.herdroid.terminal

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewPanTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java)
    private lateinit var view: TerminalView
    private val scrolls = mutableListOf<Boolean>()

    @Before fun setUp() {
        val activity = activityController.setup().get()
        view = TerminalView(activity, TerminalModel(), ModifierState())
        view.listener = object : TerminalViewListener {
            override fun onInput(data: String) {}
            override fun onGridSizeChanged(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {}
            override fun onScroll(up: Boolean, lines: Int, column: Int, row: Int) { scrolls += up }
            override fun onTap(column: Int, row: Int) {}
            override fun onLongPress(column: Int, row: Int) {}
            override fun onFontSizeChanged(sp: Float) {}
        }
        view.panWide = true
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(400, 400)) })
        activityController.visible()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun tearDown() { activityController.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val t = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        for (i in 1..10) {
            val x = fromX + (toX - fromX) * i / 10
            val y = fromY + (toY - fromY) * i / 10
            view.onTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        view.onTouchEvent(MotionEvent.obtain(t, t + 400, MotionEvent.ACTION_UP, toX, toY, 0))
    }

    @Test fun sidewaysDragPansAWideScreenAndVerticalDragStillScrolls() {
        view.model.applyFrame(400, 10, true, "wide".toByteArray())
        val before = view.cellAt(100f, 50f).first
        drag(300f, 100f, 50f, 105f)
        val after = view.cellAt(100f, 50f).first
        assertTrue("panned from $before to $after", after > before)
        assertEquals(emptyList<Boolean>(), scrolls)

        drag(200f, 50f, 205f, 350f)
        assertTrue(scrolls.isNotEmpty())
        assertEquals(after, view.cellAt(100f, 50f).first)
    }

    @Test fun fitScreenShowsTheWholeScreenAndANarrowScreenNeverPans() {
        view.model.applyFrame(400, 10, true, "wide".toByteArray())
        view.fitScreen = true
        assertEquals(399, view.cellAt(399f, 50f).first)

        view.fitScreen = false
        view.model.applyFrame(20, 10, true, "narrow".toByteArray())
        drag(300f, 100f, 50f, 105f)
        assertEquals(5, view.cellAt(5 * cellWidthOf(view) + 1f, 50f).first)
    }

    @Test fun inControlAWideScreenScalesToFitAndNeverPans() {
        view.panWide = false
        view.model.applyFrame(400, 10, true, "wide".toByteArray())
        assertEquals(399, view.cellAt(399f, 50f).first)
        drag(300f, 100f, 50f, 105f)
        assertEquals(399, view.cellAt(399f, 50f).first)
        assertEquals(0, view.cellAt(0f, 50f).first)
    }

    @Test fun aTallScreenKeepsTheCursorRowInView() {
        view.model.applyFrame(20, 200, true, "top".toByteArray())
        assertEquals("cursor on the first row", 0, view.cellAt(5f, 1f).second)

        view.model.applyFrame(20, 200, true, ("\r\n".repeat(199) + "end").toByteArray())
        assertEquals("cursor on the last row", 199, view.cellAt(5f, 399f).second)
        assertTrue(view.cellAt(5f, 1f).second > 0)

        view.model.applyFrame(20, 200, true, "\u001b[H\u001b[?25l".toByteArray())
        assertEquals("hidden cursor shows the bottom", 199, view.cellAt(5f, 399f).second)
    }

    @Test fun fitToScreenFitsATallScreenWhole() {
        view.model.applyFrame(20, 200, true, ("\r\n".repeat(199)).toByteArray())
        view.fitScreen = true
        assertEquals(0, view.cellAt(5f, 1f).second)
        assertEquals(199, view.cellAt(5f, 399f).second)
    }

    @Test fun inControlATallScreenIsDrawnFromItsTop() {
        view.panWide = false
        view.model.applyFrame(20, 200, true, ("\r\n".repeat(199)).toByteArray())
        assertEquals(0, view.cellAt(5f, 1f).second)
    }

    private fun cellWidthOf(v: TerminalView): Float {
        // Column 1 starts one cell in: find it by probing.
        var x = 0f
        while (v.cellAt(x, 0f).first == 0) x += 1f
        return x - v.paddingLeft
    }
}
