package io.github.aksulightning.flyby.terminal

import android.content.*
import android.annotation.SuppressLint
import android.graphics.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import android.widget.Toast
import android.util.TypedValue
import kotlin.math.*
import io.github.aksulightning.flyby.settings.Settings
import io.github.aksulightning.flyby.settings.CursorStyle

/** Android canvas/IME adapter. ANSI interpretation and screen history stay in libvterm. */
class TerminalView(context: Context) : View(context) {
    var terminal: TerminalEmulator? = null
    var onInput: (ByteArray) -> Unit = {}
    var onResize: (Int, Int) -> Unit = { _, _ -> }
    var onModifiersConsumed: () -> Unit = {}
    var ctrlNext = false
    var altNext = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val cellWidth get() = paint.measureText("M")
    private var spacing = 1f
    private var cursorStyle = CursorStyle.UNDERLINE
    private val cellHeight get() = ceil(paint.fontSpacing * spacing)
    fun configure(settings: Settings) {
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat(), resources.displayMetrics)
        val lineSpacing = settings.lineSpacing / 100f
        val resized = paint.textSize != size || spacing != lineSpacing
        paint.textSize = size; spacing = lineSpacing; cursorStyle = settings.cursor
        keepScreenOn = settings.keepScreenOn
        if (resized && width > 0 && height > 0) onResize((height / cellHeight).toInt().coerceIn(2, 300), (width / cellWidth).toInt().coerceIn(2, 500))
        invalidate()
    }
    private var scrollback = 0
    private var scrollRemainder = 0f
    private var latest = intArrayOf()
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent): Boolean { scrollRemainder = 0f; return true }
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            performClick()
            return true
        }
        override fun onLongPress(e: android.view.MotionEvent) { copyScreen() }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val max = latest.getOrElse(4) { 0 }
            scrollRemainder -= distanceY / cellHeight
            val lines = scrollRemainder.toInt()
            scrollRemainder -= lines
            scrollback = (scrollback + lines).coerceIn(0, max)
            invalidate(); return true
        }
    })
    init {
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = "Linux terminal. Swipe to scroll history; long press to copy the visible screen."
        setBackgroundColor(Color.BLACK)
    }
    override fun performClick(): Boolean {
        super.performClick(); requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        return true
    }
    // GestureDetector calls performClick only for taps; swipes must not summon the IME.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
    override fun onCheckIsTextEditor() = true
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onResize((h / cellHeight).toInt().coerceIn(2, 300), (w / cellWidth).toInt().coerceIn(2, 500))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = terminal?.frame(scrollback) ?: return
        latest = frame
        if (frame.size < 6) return
        val rows = frame[0]; val cols = frame[1]
        // Backgrounds first: a wide glyph may cover the next cell's background.
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = 6 + (r * cols + c) * 10
            paint.color = frame[i + if (frame[i + 8] and 4 != 0) 6 else 7]
            canvas.drawRect(c * cellWidth, r * cellHeight, (c + 1) * cellWidth, (r + 1) * cellHeight, paint)
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = 6 + (r * cols + c) * 10
            val flags = frame[i + 8]
            val reverse = flags and 4 != 0
            val left = c * cellWidth; val top = r * cellHeight
            if (frame[i + 9] == 0 || frame[i] == 0) continue
            paint.color = frame[i + if (reverse) 7 else 6]
            paint.isFakeBoldText = flags and 1 != 0
            paint.isUnderlineText = flags and 2 != 0
            paint.textSkewX = if (flags and 8 != 0) -0.2f else 0f
            val chars = (0 until 6).map { frame[i + it] }.takeWhile { it != 0 && it != -1 }
                .filter { Character.isValidCodePoint(it) }.toIntArray()
            if (chars.isNotEmpty()) canvas.drawText(String(chars, 0, chars.size), left, top - paint.ascent(), paint)
        }
        paint.isFakeBoldText = false; paint.isUnderlineText = false; paint.textSkewX = 0f
        if (frame[5] != 0) {
            paint.color = 0x99ffffff.toInt()
            val left = frame[3] * cellWidth; val top = frame[2] * cellHeight
            when (cursorStyle) {
                CursorStyle.UNDERLINE -> canvas.drawRect(left, top + cellHeight - 2, left + cellWidth, top + cellHeight, paint)
                CursorStyle.BLOCK -> canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
                CursorStyle.BAR -> canvas.drawRect(left, top, left + 2, top + cellHeight, paint)
            }
        }
    }
    fun extraKey(key: Int) { emitKey(key, modifiers()); consumeModifiers() }
    private fun modifiers() = (if (ctrlNext) 4 else 0) or (if (altNext) 2 else 0)
    private fun consumeModifiers() { ctrlNext = false; altNext = false; onModifiersConsumed() }
    private fun emitKey(key: Int, modifiers: Int = 0) {
        scrollback = 0; terminal?.key(key, modifiers)?.let(onInput); invalidate()
    }
    private fun emitText(text: String, ctrl: Boolean = ctrlNext, alt: Boolean = altNext) {
        scrollback = 0
        val transformed = if (ctrl) text.map { ch ->
            when { ch == '?' -> '\u007f'; ch == ' ' -> '\u0000'; ch.uppercaseChar().code in 64..95 -> (ch.uppercaseChar().code and 31).toChar(); else -> ch }
        }.joinToString("") else text
        terminal?.text((if (alt) "\u001b" else "") + transformed)?.let(onInput)
        consumeModifiers(); invalidate()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 1
            KeyEvent.KEYCODE_TAB -> 2
            KeyEvent.KEYCODE_DEL -> 3
            KeyEvent.KEYCODE_ESCAPE -> 4
            KeyEvent.KEYCODE_DPAD_UP -> 5
            KeyEvent.KEYCODE_DPAD_DOWN -> 6
            KeyEvent.KEYCODE_DPAD_LEFT -> 7
            KeyEvent.KEYCODE_DPAD_RIGHT -> 8
            KeyEvent.KEYCODE_INSERT -> 9
            KeyEvent.KEYCODE_FORWARD_DEL -> 10
            KeyEvent.KEYCODE_MOVE_HOME -> 11
            KeyEvent.KEYCODE_MOVE_END -> 12
            KeyEvent.KEYCODE_PAGE_UP -> 13
            KeyEvent.KEYCODE_PAGE_DOWN -> 14
            else -> 0
        }
        if (key != 0) { emitKey(key, modifiers() or (if (event.isCtrlPressed) 4 else 0) or (if (event.isAltPressed) 2 else 0) or (if (event.isShiftPressed) 1 else 0)); consumeModifiers(); return true }
        val cp = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (cp > 0 && Character.isValidCodePoint(cp)) { emitText(String(Character.toChars(cp)), ctrlNext || event.isCtrlPressed, altNext || event.isAltPressed); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = ""; emitText(text?.toString().orEmpty()); return true }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString().orEmpty(); return true }
            override fun finishComposingText(): Boolean { if (composing.isNotEmpty()) { emitText(composing); composing = "" }; return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) composing = composing.dropLast(beforeLength.coerceAtMost(composing.length))
                else repeat(beforeLength.coerceIn(0, 1024)) { emitKey(3) }
                repeat(afterLength.coerceIn(0, 1024)) { emitKey(10) }; return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event) else true
            override fun performEditorAction(editorAction: Int): Boolean { emitKey(1); return true }
        }
    }
    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount > 0) terminal?.text(clip.getItemAt(0).coerceToText(context).toString().take(16384), true)?.let(onInput)
        scrollback = 0; invalidate()
    }
    fun copyScreen() {
        val f = latest; if (f.size < 6) return
        val text = (0 until f[0]).joinToString("\n") { r -> buildString {
            for (c in 0 until f[1]) {
                val i = 6 + (r * f[1] + c) * 10
                if (f[i + 9] == 0) continue
                if (f[i] == 0) append(' ') else for (n in 0 until 6) {
                    val cp = f[i + n]; if (cp == 0 || cp == -1) break
                    if (Character.isValidCodePoint(cp)) appendCodePoint(cp)
                }
            }
        }.trimEnd() }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Linux terminal", text))
        Toast.makeText(context, "Visible terminal copied", Toast.LENGTH_SHORT).show()
    }
}
