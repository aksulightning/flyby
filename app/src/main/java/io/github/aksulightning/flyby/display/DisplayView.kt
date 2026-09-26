package io.github.aksulightning.flyby.display

import android.content.Context
import android.graphics.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/** View owns only presentation state; VM lifetime belongs to VmService. */
class DisplayView(context: Context) : View(context) {
    var input: DisplayInput? = null
    var onUnsupportedText: () -> Unit = {}
    private val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private var ready = false
    private var pointerX = 0f
    private var pointerY = 0f
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = "Wayland desktop, 800 by 600" }
    fun update(pixels: IntArray) { bitmap.setPixels(pixels, 0, 800, 0, 0, 800, 600); ready = true; invalidate() }
    fun clear() { ready = false; invalidate() }
    fun keyboard() { requestFocus(); context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = minOf(w / 800f, h / 600f)
        val left = (w - 800 * scale) / 2f; val top = (h - 600 * scale) / 2f
        bounds.set(left, top, left + 800 * scale, top + 600 * scale)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (ready) canvas.drawBitmap(bitmap, null, bounds, paint)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun position(event: MotionEvent) {
        pointerX = ((event.x - bounds.left) * 800 / bounds.width()).coerceIn(0f, 799f)
        pointerY = ((event.y - bounds.top) * 600 / bounds.height()).coerceIn(0f, 599f)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ready || bounds.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !bounds.contains(event.x, event.y)) return false
        requestFocus()
        position(event)
        val up = event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
        input?.pointer(pointerX, pointerY, if (up) 0 else if (mouse) event.buttonState and 7 else 1)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!ready || bounds.isEmpty || !bounds.contains(event.x, event.y)) return super.onGenericMotionEvent(event)
        if (event.actionMasked in listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)) {
            position(event)
            input?.pointer(pointerX, pointerY, event.buttonState and 7, event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) input?.reset()
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            // Hold composing text in the IME; send it only once committed.
            private var composing: CharSequence = ""
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text ?: ""; return true }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = ""
                if (input?.text(text ?: "") == false) onUnsupportedText()
                return true
            }
            override fun finishComposingText(): Boolean {
                val pending = composing; composing = ""
                if (pending.isNotEmpty() && input?.text(pending) == false) onUnsupportedText()
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) composing = composing.dropLast(beforeLength.coerceAtLeast(0))
                else repeat(beforeLength.coerceIn(0, 128)) { input?.tap(42) }
                repeat(afterLength.coerceIn(0, 128)) { input?.tap(76) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent) = this@DisplayView.dispatchKeyEvent(event)
        }
    }
    private fun hid(key: Int): Int = when (key) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 4 + key - KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 30 + key - KeyEvent.KEYCODE_1
        KeyEvent.KEYCODE_0 -> 39
        KeyEvent.KEYCODE_ENTER -> 40; KeyEvent.KEYCODE_ESCAPE -> 41
        KeyEvent.KEYCODE_DEL -> 42; KeyEvent.KEYCODE_TAB -> 43; KeyEvent.KEYCODE_SPACE -> 44
        KeyEvent.KEYCODE_MINUS -> 45; KeyEvent.KEYCODE_EQUALS -> 46
        KeyEvent.KEYCODE_LEFT_BRACKET -> 47; KeyEvent.KEYCODE_RIGHT_BRACKET -> 48
        KeyEvent.KEYCODE_BACKSLASH -> 49; KeyEvent.KEYCODE_SEMICOLON -> 51
        KeyEvent.KEYCODE_APOSTROPHE -> 52; KeyEvent.KEYCODE_GRAVE -> 53
        KeyEvent.KEYCODE_COMMA -> 54; KeyEvent.KEYCODE_PERIOD -> 55; KeyEvent.KEYCODE_SLASH -> 56
        KeyEvent.KEYCODE_CAPS_LOCK -> 57
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 58 + key - KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_MOVE_HOME -> 74; KeyEvent.KEYCODE_PAGE_UP -> 75
        KeyEvent.KEYCODE_FORWARD_DEL -> 76; KeyEvent.KEYCODE_MOVE_END -> 77; KeyEvent.KEYCODE_PAGE_DOWN -> 78
        KeyEvent.KEYCODE_DPAD_RIGHT -> 79; KeyEvent.KEYCODE_DPAD_LEFT -> 80
        KeyEvent.KEYCODE_DPAD_DOWN -> 81; KeyEvent.KEYCODE_DPAD_UP -> 82
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xe0; KeyEvent.KEYCODE_SHIFT_LEFT -> 0xe1
        KeyEvent.KEYCODE_ALT_LEFT -> 0xe2; KeyEvent.KEYCODE_META_LEFT -> 0xe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xe4; KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xe5
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xe6; KeyEvent.KEYCODE_META_RIGHT -> 0xe7
        else -> 0
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val code = hid(keyCode)
        if (code == 0) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) input?.key(code, true)
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val code = hid(keyCode)
        if (code == 0) return super.onKeyUp(keyCode, event)
        input?.key(code, false); return true
    }
}
