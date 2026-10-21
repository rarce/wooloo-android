package dev.herdroid.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.herdroid.R
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.graphics.Rect
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Sticky modifier state shared with the extra-keys bar. */
class ModifierState {
    var ctrl = false
    var alt = false
    var shift = false
    var ctrlLocked = false
    var shiftLocked = false
    var altLocked = false
    var onChange: (() -> Unit)? = null

    /** Returns the active modifiers and releases one-shot ones. */
    fun consume(): Triple<Boolean, Boolean, Boolean> {
        val result = Triple(ctrl, alt, shift)
        var changed = false
        if (ctrl && !ctrlLocked) { ctrl = false; changed = true }
        if (alt && !altLocked) { alt = false; changed = true }
        if (shift && !shiftLocked) { shift = false; changed = true }
        if (changed) onChange?.invoke()
        return result
    }
}

interface TerminalViewListener {
    /** Bytes for the PTY, already encoded. */
    fun onInput(data: String)
    fun onGridSizeChanged(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)
    fun onScroll(up: Boolean, lines: Int, column: Int, row: Int)
    fun onTap(column: Int, row: Int)
    fun onLongPress(column: Int, row: Int)
    fun onReadScreen() {}
    fun onFontSizeChanged(sp: Float)
}

/**
 * Renders a [TerminalModel] grid and turns touch + IME input into terminal input.
 *
 * Design notes for phones:
 * - Soft keyboards are driven through a custom [InputConnection] that diffs composing text,
 *   so Gboard/SwiftKey word composition becomes correct keystrokes and backspaces.
 * - Vertical drags scroll herdr's scrollback (with fling), pinch changes font size,
 *   taps become mouse clicks for TUIs that enable mouse reporting.
 * - If the remote screen is wider than the view (e.g. read-only observe), it is scaled to fit.
 */
@SuppressLint("ViewConstructor")
class TerminalView(
    context: Context,
    val model: TerminalModel,
    val modifiers: ModifierState,
) : View(context) {

    var listener: TerminalViewListener? = null
    var theme: TerminalTheme = TerminalTheme.Default
        set(value) { field = value; invalidate() }

    var inputEnabled = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) { hardwareModifiers = 0; resetInputContext(force = true) }
            // The IME is still bound to the null connection of the disabled pane: rebind it,
            // or the keyboard (and its dictation key) stays inert until focus moves.
            else if (hasFocus()) restartInput()
            restoreInputFocus()
        }

    // Enabled only by the resumed, live pane, with no dialog or composer open.
    var retainInputFocus = false
        set(value) { field = value; restoreInputFocus() }
    private val restoreFocus = Runnable {
        if (retainInputFocus && inputEnabled && isAttachedToWindow && hasWindowFocus() && !hasFocus()) {
            requestFocus()
        }
    }

    fun restoreInputFocus() {
        removeCallbacks(restoreFocus)
        if (retainInputFocus && inputEnabled) post(restoreFocus)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) { hardwareModifiers = 0; restoreInputFocus() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) restoreInputFocus() else hardwareModifiers = 0
    }

    private var hardwareModifiers = 0
    private var inputConnection: TerminalInputConnection? = null

    // The IME's view of the current line since the last reset, and what the remote already got.
    // It outlives a connection the framework recreates, so the IME keeps the word it is editing.
    private val imeText: Editable = Editable.Factory.getInstance().newEditable("")
    private var sentText = ""
    private var sentCursor = 0

    @androidx.annotation.VisibleForTesting
    internal var inputRestarts = 0
        private set

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply { strokeWidth = 2f }

    private var fontSizeSp = 13f
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var baseline = 0f
    private var gridCols = 0
    private var gridRows = 0

    private val charBuffer = CharArray(512)
    private val tmpRect = RectF()

    private var cursorBlinkOn = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            postDelayed(this, 530)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isFocusableInTouchMode = true
        setFontSize(fontSizeSp, notify = false)
        model.listener = { postInvalidateOnAnimation() }
    }

    fun setFontSize(sp: Float, notify: Boolean = true) {
        fontSizeSp = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics
        )
        val fm = textPaint.fontMetrics
        cellWidth = textPaint.measureText("M")
        cellHeight = (fm.descent - fm.ascent + fm.leading).let { kotlin.math.ceil(it) }
        baseline = -fm.ascent
        recomputeGrid()
        invalidate()
        if (notify) listener?.onFontSizeChanged(fontSizeSp)
    }

    val fontSize: Float get() = fontSizeSp

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGrid()
        restoreInputFocus()
    }

    private fun recomputeGrid() {
        if (width == 0 || height == 0 || cellWidth == 0f) return
        val cols = max(MIN_COLS, floor((width - paddingLeft - paddingRight) / cellWidth).toInt())
        val rows = max(MIN_ROWS, floor((height - paddingTop - paddingBottom) / cellHeight).toInt())
        if (cols != gridCols || rows != gridRows) {
            gridCols = cols
            gridRows = rows
            listener?.onGridSizeChanged(cols, rows, cellWidth.roundToInt(), cellHeight.roundToInt())
        }
    }

    fun currentGrid(): Pair<Int, Int> = gridCols to gridRows

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blinkRunnable, 530)
        restoreInputFocus()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        removeCallbacks(restoreFocus)
        inputConnection?.closeConnection()
        hardwareModifiers = 0
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        // No per-frame announcements: services read the current screen when they query/focus it.
        info.text = model.screenText().trimEnd().take(16_384).ifBlank { context.getString(R.string.terminal_empty) }
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            R.id.read_terminal_text, context.getString(R.string.read_terminal_text),
        ))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.read_terminal_text && listener != null) {
            listener?.onReadScreen()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    // ---- Rendering --------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        synchronized(model.lock) {
            val emu = model.emulator
            val contentWidth = emu.cols * cellWidth
            val available = (width - paddingLeft - paddingRight).toFloat()
            val scale = if (contentWidth > available && contentWidth > 0) available / contentWidth else 1f
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            if (scale != 1f) canvas.scale(scale, scale)
            for (y in 0 until emu.rows) drawRow(canvas, emu, y)
            drawCursor(canvas, emu)
            canvas.restore()
        }
    }

    private fun drawRow(canvas: Canvas, emu: TerminalEmulator, y: Int) {
        val top = y * cellHeight
        // Backgrounds, merged into runs.
        var x = 0
        while (x < emu.cols) {
            val attrs = emu.attrsAt(x, y)
            val bg = cellBackground(emu, x, y, attrs)
            var end = x + 1
            while (end < emu.cols && cellBackground(emu, end, y, emu.attrsAt(end, y)) == bg) end++
            if (bg != theme.background) {
                bgPaint.color = bg
                canvas.drawRect(x * cellWidth, top, end * cellWidth, top + cellHeight, bgPaint)
            }
            x = end
        }
        // Glyphs: batch ASCII runs with identical style, draw everything else per cell.
        x = 0
        while (x < emu.cols) {
            val cell = emu.textAt(x, y)
            if (cell.isEmpty() || cell == " ") { x++; continue }
            val attrs = emu.attrsAt(x, y)
            val fg = cellForeground(emu, x, y, attrs)
            applyStyle(attrs, fg)
            if (cell.length == 1 && cell[0].code < 0x80) {
                var len = 0
                var end = x
                while (end < emu.cols && len < charBuffer.size) {
                    val t = emu.textAt(end, y)
                    if (t.length != 1 || t[0].code >= 0x80 || emu.attrsAt(end, y) != attrs ||
                        cellForeground(emu, end, y, emu.attrsAt(end, y)) != fg
                    ) break
                    charBuffer[len++] = t[0]
                    end++
                }
                if (attrs and Attr.HIDDEN == 0) {
                    canvas.drawText(charBuffer, 0, len, x * cellWidth, top + baseline, textPaint)
                }
                drawDecorations(canvas, attrs, x, end, top, fg)
                x = end
            } else {
                val wide = x + 1 < emu.cols && emu.textAt(x + 1, y).isEmpty()
                val span = if (wide) 2 else 1
                if (attrs and Attr.HIDDEN == 0) {
                    val glyphWidth = textPaint.measureText(cell)
                    val target = cellWidth * span
                    if (glyphWidth > target * 1.05f && !isBoxDrawing(cell)) {
                        // Squeeze oversized fallback glyphs into their cells.
                        canvas.save()
                        canvas.scale(target / glyphWidth, 1f, x * cellWidth, 0f)
                        canvas.drawText(cell, x * cellWidth, top + baseline, textPaint)
                        canvas.restore()
                    } else {
                        canvas.drawText(cell, x * cellWidth, top + baseline, textPaint)
                    }
                }
                drawDecorations(canvas, attrs, x, x + span, top, fg)
                x += span
            }
        }
    }

    private fun isBoxDrawing(cell: String) = cell[0].code in 0x2500..0x259F

    private fun drawDecorations(canvas: Canvas, attrs: Int, from: Int, to: Int, top: Float, fg: Int) {
        if (attrs and (Attr.UNDERLINE or Attr.STRIKE) == 0) return
        linePaint.color = fg
        if (attrs and Attr.UNDERLINE != 0) {
            val uy = top + cellHeight - 2f
            canvas.drawLine(from * cellWidth, uy, to * cellWidth, uy, linePaint)
        }
        if (attrs and Attr.STRIKE != 0) {
            val sy = top + cellHeight / 2f
            canvas.drawLine(from * cellWidth, sy, to * cellWidth, sy, linePaint)
        }
    }

    private fun applyStyle(attrs: Int, fg: Int) {
        textPaint.color = fg
        textPaint.isFakeBoldText = attrs and Attr.BOLD != 0
        textPaint.textSkewX = if (attrs and Attr.ITALIC != 0) -0.2f else 0f
        textPaint.alpha = if (attrs and Attr.DIM != 0) 0x99 else 0xFF
    }

    private fun cellForeground(emu: TerminalEmulator, x: Int, y: Int, attrs: Int): Int {
        val bold = attrs and Attr.BOLD != 0
        return if (attrs and Attr.INVERSE != 0) {
            theme.resolve(emu.bgAt(x, y), isForeground = false, bold = false)
        } else {
            theme.resolve(emu.fgAt(x, y), isForeground = true, bold = bold)
        }
    }

    private fun cellBackground(emu: TerminalEmulator, x: Int, y: Int, attrs: Int): Int =
        if (attrs and Attr.INVERSE != 0) {
            theme.resolve(emu.fgAt(x, y), isForeground = true, bold = false)
        } else {
            theme.resolve(emu.bgAt(x, y), isForeground = false, bold = false)
        }

    private fun drawCursor(canvas: Canvas, emu: TerminalEmulator) {
        if (!emu.cursorVisible) return
        val cx = emu.cursorX * cellWidth
        val cy = emu.cursorY * cellHeight
        cursorPaint.color = theme.cursor
        val focused = hasFocus()
        when (emu.cursorShape) {
            CursorShape.BAR -> if (cursorBlinkOn || !focused) {
                canvas.drawRect(cx, cy, cx + max(2f, cellWidth / 7f), cy + cellHeight, cursorPaint)
            }
            CursorShape.UNDERLINE -> if (cursorBlinkOn || !focused) {
                canvas.drawRect(cx, cy + cellHeight - max(2f, cellHeight / 9f), cx + cellWidth, cy + cellHeight, cursorPaint)
            }
            CursorShape.BLOCK -> {
                tmpRect.set(cx, cy, cx + cellWidth, cy + cellHeight)
                if (focused) {
                    cursorPaint.style = Paint.Style.FILL
                    cursorPaint.alpha = 0xB0
                    canvas.drawRect(tmpRect, cursorPaint)
                    val glyph = emu.textAt(emu.cursorX, emu.cursorY)
                    if (glyph.isNotBlank()) {
                        textPaint.color = theme.background
                        textPaint.isFakeBoldText = false
                        textPaint.textSkewX = 0f
                        canvas.drawText(glyph, cx, cy + baseline, textPaint)
                    }
                } else {
                    cursorPaint.style = Paint.Style.STROKE
                    cursorPaint.strokeWidth = 2f
                    canvas.drawRect(tmpRect, cursorPaint)
                    cursorPaint.style = Paint.Style.FILL
                }
                cursorPaint.alpha = 0xFF
            }
        }
    }

    // ---- Touch ------------------------------------------------------------------------

    private var scrollAccumulator = 0f
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setFontSize(fontSizeSp * detector.scaleFactor, notify = false)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                listener?.onFontSizeChanged(fontSizeSp)
            }
        })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollAccumulator = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (col, row) = cellAt(e.x, e.y)
            requestFocus()
            showKeyboard()
            listener?.onTap(col, row)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val (col, row) = cellAt(e.x, e.y)
            listener?.onLongPress(col, row)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            scrollBy(dy, e2.x, e2.y)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || abs(vy) < abs(vx)) return false
            lastFlingY = 0
            scroller.fling(0, 0, 0, -vy.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
            postOnAnimation(flingRunnable)
            return true
        }
    })

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val y = scroller.currY
                scrollBy((y - lastFlingY).toFloat(), width / 2f, height / 2f)
                lastFlingY = y
                postOnAnimation(this)
            }
        }
    }

    private fun scrollBy(dy: Float, x: Float, y: Float) {
        if (cellHeight <= 0f) return
        scrollAccumulator += dy
        val lines = (scrollAccumulator / cellHeight).toInt()
        if (lines != 0) {
            scrollAccumulator -= lines * cellHeight
            val (col, row) = cellAt(x, y)
            // Dragging content down (negative dy) reveals older output: scroll up.
            listener?.onScroll(up = lines < 0, lines = abs(lines), column = col, row = row)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scaling = false
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    fun cellAt(px: Float, py: Float): Pair<Int, Int> {
        val emu = model.emulator
        val contentWidth = emu.cols * cellWidth
        val available = (width - paddingLeft - paddingRight).toFloat()
        val scale = if (contentWidth > available && contentWidth > 0) available / contentWidth else 1f
        val col = ((px - paddingLeft) / (cellWidth * scale)).toInt().coerceIn(0, max(0, emu.cols - 1))
        val row = ((py - paddingTop) / (cellHeight * scale)).toInt().coerceIn(0, max(0, emu.rows - 1))
        return col to row
    }

    // ---- Keyboard ---------------------------------------------------------------------

    fun showKeyboard() {
        if (!inputEnabled) return
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = inputEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputEnabled) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        inputConnection?.closeConnection()
        // A new IME session starts without a composition, but with the text it can still edit.
        BaseInputConnection.removeComposingSpans(imeText)
        outAttrs.initialSelStart = Selection.getSelectionStart(imeText).coerceAtLeast(0)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(imeText).coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) outAttrs.setInitialSurroundingText(imeText)
        return TerminalInputConnection().also { inputConnection = it }
    }

    private fun restartInput() {
        inputRestarts++
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
    }

    private val controlActive: Boolean
        get() = modifiers.ctrl || modifiers.alt ||
            hardwareModifiers and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0

    private fun emitText(text: String) {
        if (!inputEnabled || text.isEmpty()) return
        val (ctrl, alt, shift) = modifiers.consume()
        listener?.onInput(KeyEncoder.text(if (shift) text.uppercase() else text,
            ctrl || hardwareModifiers and KeyEvent.META_CTRL_ON != 0,
            alt || hardwareModifiers and KeyEvent.META_ALT_ON != 0))
    }

    /**
     * Bar text joins the IME's context like typed text, so the word being edited survives it.
     * With Ctrl/Alt or a line break it is a command instead, and the context starts over.
     */
    fun sendText(text: String) {
        if (!inputEnabled) return
        val connection = inputConnection
        if (connection != null && !controlActive && text.none { it == '\n' || it == '\r' }) {
            connection.insertText(text)
        } else {
            resetInputContext()
            emitText(text)
        }
        restoreInputFocus()
    }

    fun prepareModifierInput() {
        resetInputContext()
        restoreInputFocus()
    }

    fun sendPaste(text: String) {
        if (!inputEnabled || text.isEmpty()) return
        val connection = inputConnection
        val sameLine = text.none { it == '\n' || it == '\r' }
        if (connection == null || !sameLine || !connection.canAdopt()) resetInputContext()
        listener?.onInput("\u001b[200~$text\u001b[201~")
        if (connection != null && sameLine && connection.canAdopt()) connection.adopt(text)
        restoreInputFocus()
    }

    fun sendSpecial(key: SpecialKey, shift: Boolean = false) {
        if (!inputEnabled) return
        resetInputContext()
        val (ctrl, alt, stickyShift) = modifiers.consume()
        listener?.onInput(KeyEncoder.special(key,
            ctrl || hardwareModifiers and KeyEvent.META_CTRL_ON != 0,
            alt || hardwareModifiers and KeyEvent.META_ALT_ON != 0,
            shift || stickyShift || hardwareModifiers and KeyEvent.META_SHIFT_ON != 0))
        restoreInputFocus()
    }

    /** Cursor movement, Enter and shortcuts change the remote line in ways the IME can't follow. */
    private fun resetInputContext(force: Boolean = false) {
        if (!force && imeText.isEmpty()) return
        imeText.clear()
        BaseInputConnection.removeComposingSpans(imeText)
        Selection.setSelection(imeText, 0)
        sentText = ""
        sentCursor = 0
        val connection = inputConnection ?: return
        connection.closeConnection()
        inputConnection = null
        restartInput()
    }

    private fun emitSpecial(key: SpecialKey, count: Int = 1) {
        repeat(count.coerceAtLeast(0)) { listener?.onInput(KeyEncoder.special(key)) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val modifierMask = modifierMask(keyCode)
        if (modifierMask != 0) {
            hardwareModifiers = hardwareModifiers or modifierMask
            return true
        }
        val special = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SpecialKey.ENTER
            KeyEvent.KEYCODE_DEL -> SpecialKey.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> SpecialKey.DELETE
            KeyEvent.KEYCODE_TAB -> SpecialKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> SpecialKey.ESCAPE
            KeyEvent.KEYCODE_DPAD_UP -> SpecialKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SpecialKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> SpecialKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpecialKey.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> SpecialKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> SpecialKey.END
            KeyEvent.KEYCODE_PAGE_UP -> SpecialKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> SpecialKey.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> SpecialKey.INSERT
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                SpecialKey.entries[SpecialKey.F1.ordinal + (keyCode - KeyEvent.KEYCODE_F1)]
            else -> null
        }
        val ctrl = event.isCtrlPressed || modifiers.ctrl || hardwareModifiers and KeyEvent.META_CTRL_ON != 0
        val alt = event.isAltPressed || modifiers.alt || hardwareModifiers and KeyEvent.META_ALT_ON != 0
        if (special != null) {
            resetInputContext()
            val (_, _, shift) = modifiers.consume()
            listener?.onInput(KeyEncoder.special(special, ctrl, alt, event.isShiftPressed || shift || hardwareModifiers and KeyEvent.META_SHIFT_ON != 0))
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode != 0) {
            resetInputContext()
            val (_, _, shift) = modifiers.consume()
            val text = String(Character.toChars(unicode))
            listener?.onInput(KeyEncoder.text(if (shift) text.uppercase() else text, ctrl, alt))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun modifierMask(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
        else -> 0
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mask = modifierMask(keyCode)
        if (mask == 0) return super.onKeyUp(keyCode, event)
        hardwareModifiers = hardwareModifiers and mask.inv()
        return true
    }

    /** Editable context lets IMEs revise dictation and query the text/cursor coherently. */
    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, true) {
        override fun getEditable(): Editable = imeText

        private var closed = false
        private var extractedToken: Int? = null

        override fun closeConnection() {
            closed = true
            super.closeConnection()
        }

        private fun edit(operation: () -> Boolean): Boolean {
            if (closed || !inputEnabled) return false
            val controlInput = controlActive
            val result = operation()
            synchronizeText()
            if (controlInput || editable.any { it == '\n' || it == '\r' }) resetInputContext()
            return result
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int) =
            edit { super.commitText(text, newCursorPosition) }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
            edit { super.setComposingText(text, newCursorPosition) }

        override fun finishComposingText(): Boolean {
            if (closed) return false
            return super.finishComposingText().also { notifySelection() }
        }

        override fun setSelection(start: Int, end: Int) = edit { super.setSelection(start, end) }

        // Re-composing a committed word (backspace into it) must reach the IME's selection state.
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            if (closed || !inputEnabled) return false
            return super.setComposingRegion(start, end).also { notifySelection() }
        }

        /** Bar text, inserted where the IME's cursor is, as if typed. */
        fun insertText(text: String) {
            if (closed) return
            super.finishComposingText()
            commitText(text, 1)
        }

        /** Whether text the remote already received at its cursor can join this context. */
        fun canAdopt() = !closed &&
            Selection.getSelectionStart(editable) == sentCursor && Selection.getSelectionEnd(editable) == sentCursor

        fun adopt(text: String) {
            removeComposingSpans(editable)
            editable.insert(sentCursor, text)
            sentText = editable.toString()
            sentCursor += text.length
            Selection.setSelection(editable, sentCursor)
            notifySelection()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (closed || !inputEnabled || beforeLength < 0 || afterLength < 0) return false
            if (editable.isEmpty()) {
                // An empty local context does not mean the remote prompt is empty.
                emitSpecial(SpecialKey.BACKSPACE, max(1, beforeLength))
                emitSpecial(SpecialKey.DELETE, afterLength)
                return true
            }
            return edit { super.deleteSurroundingText(beforeLength, afterLength) }
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (closed || !inputEnabled || beforeLength < 0 || afterLength < 0) return false
            if (editable.isEmpty()) return deleteSurroundingText(beforeLength, afterLength)
            return edit { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (closed || !inputEnabled) return false
            // IMEs send some edits as key events (Gboard: backspace with no known text, digits).
            // Plain ones stay inside the context instead of restarting the IME and losing the word.
            if (event.action == KeyEvent.ACTION_DOWN && !controlActive && !event.isCtrlPressed && !event.isAltPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> return deleteKey(forward = false)
                    KeyEvent.KEYCODE_FORWARD_DEL -> return deleteKey(forward = true)
                }
                val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
                if (unicode != 0 && unicode != '\n'.code && unicode != '\t'.code) {
                    return commitText(String(Character.toChars(unicode)), 1)
                }
            }
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                else -> false
            }
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            if (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) extractedToken = request?.token
            return ExtractedText().apply {
                text = editable.toString()
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = Selection.getSelectionStart(editable).coerceAtLeast(0)
                selectionEnd = Selection.getSelectionEnd(editable).coerceAtLeast(0)
            }
        }

        private fun deleteKey(forward: Boolean): Boolean {
            val start = Selection.getSelectionStart(editable).coerceAtLeast(0)
            val end = Selection.getSelectionEnd(editable).coerceAtLeast(0)
            return when {
                start != end -> commitText("", 1)
                // The context only begins at the last reset; the remote line goes on beyond it.
                !forward && start == 0 -> { emitSpecial(SpecialKey.BACKSPACE); true }
                forward && end == editable.length -> { emitSpecial(SpecialKey.DELETE); true }
                forward -> deleteSurroundingTextInCodePoints(0, 1)
                else -> deleteSurroundingTextInCodePoints(1, 0)
            }
        }

        private fun moveCursor(target: Int) {
            if (target > sentCursor) emitSpecial(SpecialKey.RIGHT, sentText.codePointCount(sentCursor, target))
            if (target < sentCursor) emitSpecial(SpecialKey.LEFT, sentText.codePointCount(target, sentCursor))
            sentCursor = target
        }

        private fun synchronizeText() {
            val next = editable.toString()
            if (next != sentText) {
                var common = sentText.commonPrefixWith(next).length
                // A UTF-16 prefix can split two different emoji at their shared high surrogate.
                if (common > 0 && common < sentText.length && sentText[common - 1].isHighSurrogate()) common--
                moveCursor(sentText.length)
                emitSpecial(SpecialKey.BACKSPACE, sentText.codePointCount(common, sentText.length))
                emitText(next.substring(common))
                sentText = next
                sentCursor = next.length
            }
            moveCursor(Selection.getSelectionEnd(editable).coerceIn(0, next.length))
            notifySelection()
        }

        private fun notifySelection() {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.updateSelection(this@TerminalView, Selection.getSelectionStart(editable),
                Selection.getSelectionEnd(editable), getComposingSpanStart(editable), getComposingSpanEnd(editable))
            extractedToken?.let { imm.updateExtractedText(this@TerminalView, it, getExtractedText(null, 0)) }
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            if (closed || !inputEnabled) return false
            sendSpecial(SpecialKey.ENTER)
            return true
        }
    }

    companion object {
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 28f
        private const val MIN_COLS = 20
        private const val MIN_ROWS = 6
    }
}
