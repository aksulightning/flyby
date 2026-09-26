package io.github.aksulightning.flyby.display

import org.junit.Assert.*
import org.junit.Test

class DisplayInputTest {
    @Test fun ctrlAndKeyReleasePreserveOtherHeldKeys() {
        val packets = mutableListOf<ByteArray>()
        val input = DisplayInput { packets += it }
        input.key(0xe0, true); input.key(6, true); input.key(6, false)
        assertEquals(1, packets.last()[4].toInt())
        assertEquals(0, packets.last()[6].toInt())
        input.reset()
        assertEquals(32, packets.last().size)
        assertEquals(0, packets.last()[4].toInt())
        assertEquals(0, packets.last()[20].toInt())
    }
    @Test fun pointerEdgesAndWheelMatchGuestHidDescriptor() {
        var packet = byteArrayOf()
        val input = DisplayInput { packet = it }
        input.pointer(900f, -20f, 2, -1)
        assertEquals(2, packet[3].toInt())
        assertEquals(2, packet[4].toInt())
        assertEquals(32767, (packet[5].toInt() and 255) or ((packet[6].toInt() and 255) shl 8))
        assertEquals(0, packet[7].toInt())
        assertEquals(-1, packet[9].toInt())
    }
    @Test fun textIsBatchedAndUnsupportedTextIsNotPartiallySent() {
        val packets = mutableListOf<ByteArray>()
        val input = DisplayInput { packets += it }
        assertFalse(input.text("hello\u2603"))
        assertTrue(packets.isEmpty())
        assertTrue(input.text("Aa09!? äöå\n"))
        assertTrue(packets.all { it.size <= 4096 && it.size % 16 == 0 })
        val all = packets.flatMap { it.toList() }.chunked(16)
        assertTrue(all.all { it[0] == 'F'.code.toByte() && it[3] == 1.toByte() })
        assertEquals(2, all[0][4].toInt()) // Shift for uppercase A.
        assertEquals(4, all[1][6].toInt())
        assertEquals(0, all.last()[4].toInt())
        assertEquals(0, all.last()[6].toInt())
    }
}
