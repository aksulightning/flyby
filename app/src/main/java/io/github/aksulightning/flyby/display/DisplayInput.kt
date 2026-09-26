package io.github.aksulightning.flyby.display

import kotlin.math.roundToInt

/** Complete HID state reports, shared with native/guest/display-input.c. */
class DisplayInput(private val send: (ByteArray) -> Unit) {
    private val keys = linkedSetOf<Int>()
    private var modifiers = 0
    private var buttons = 0
    private var x = 0
    private var y = 0
    private var batch: java.io.ByteArrayOutputStream? = null
    private fun emit(report: ByteArray) { batch?.write(report) ?: send(report) }

    private fun packet(type: Int) = ByteArray(16).also {
        it[0] = 'F'.code.toByte(); it[1] = 'I'.code.toByte(); it[2] = 1; it[3] = type.toByte()
    }
    private fun keyboard() = packet(1).also {
        it[4] = modifiers.toByte()
        keys.take(6).forEachIndexed { index, key -> it[6 + index] = key.toByte() }
    }
    private fun pointer(wheel: Int = 0) = packet(2).also {
        it[4] = buttons.toByte()
        it[5] = x.toByte(); it[6] = (x shr 8).toByte()
        it[7] = y.toByte(); it[8] = (y shr 8).toByte()
        it[9] = wheel.coerceIn(-127, 127).toByte()
    }
    fun key(code: Int, down: Boolean) {
        if (code in 0xe0..0xe7) {
            val bit = 1 shl (code - 0xe0)
            modifiers = if (down) modifiers or bit else modifiers and bit.inv()
        } else if (code in 4..0x65) {
            if (down) { if (keys.size < 6) keys.add(code) } else keys.remove(code)
        } else return
        emit(keyboard())
    }
    fun tap(code: Int) { key(code, true); key(code, false) }
    fun pointer(px: Float, py: Float, mask: Int, wheel: Int = 0) {
        x = (px.coerceIn(0f, 799f).toDouble() * 32767 / 799).roundToInt()
        y = (py.coerceIn(0f, 599f).toDouble() * 32767 / 599).roundToInt()
        buttons = mask and 7
        send(pointer(wheel))
    }
    fun reset() {
        keys.clear(); modifiers = 0; buttons = 0
        send(keyboard() + pointer())
    }
    /** US layout + right-Alt Compose, matching the bundled weston.ini. */
    fun text(text: CharSequence): Boolean {
        if (text.length > 2048) return false
        val strokes = mutableListOf<Pair<Int, Boolean>>()
        fun character(c: Char): Boolean {
            if (c in 'a'..'z') { strokes += (4 + (c - 'a')) to false; return true }
            if (c in 'A'..'Z') { strokes += (4 + (c - 'A')) to true; return true }
            val plain = "1234567890\n\u001b\b\t -=[]\\;',./"
            val codes = intArrayOf(30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,51,52,54,55,56)
            val p = plain.indexOf(c)
            if (p >= 0) { strokes += codes[p] to false; return true }
            val shifted = "!@#$%^&*()_+{}|:\"<>?"
            val shiftedCodes = intArrayOf(30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,51,52,54,55,56)
            val s = shifted.indexOf(c)
            if (s >= 0) { strokes += shiftedCodes[s] to true; return true }
            if (c == '`' || c == '~') { strokes += 53 to (c == '~'); return true }
            val compose = when (c) {
                'ä' -> "\"a"; 'Ä' -> "\"A"; 'ö' -> "\"o"; 'Ö' -> "\"O"
                'å' -> "oa"; 'Å' -> "oA"; 'é' -> "'e"; 'É' -> "'E"
                else -> return false
            }
            strokes += 0xe6 to false
            return compose.all { character(it) }
        }
        // Validate before sending to avoid partially entering unsupported text.
        if (!text.all { character(it) }) return false
        batch = java.io.ByteArrayOutputStream()
        for ((code, shift) in strokes) {
            val heldShift = modifiers and 2 != 0
            if (shift && !heldShift) key(0xe1, true)
            tap(code)
            if (shift && !heldShift) key(0xe1, false)
        }
        val reports = checkNotNull(batch).toByteArray()
        batch = null
        for (offset in reports.indices step 4096) send(reports.copyOfRange(offset, minOf(offset + 4096, reports.size)))
        return true
    }
}
