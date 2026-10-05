package dev.qdauto.carsim.h264

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BitsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun expGolombRoundTrips() {
        val w = BitWriter()
        val unsigned = (0..300).toList() + listOf(1_000, 65_535, 1 shl 20, Int.MAX_VALUE - 1)
        val signed = (-150..150).toList() + listOf(-100_000, 100_000)
        unsigned.forEach(w::ue)
        signed.forEach(w::se)
        w.u(32, 0xDEADBEEFL)
        w.trailingBits()
        val r = BitReader(w.toByteArray())
        unsigned.forEach { assertEquals(it, r.ue()) }
        signed.forEach { assertEquals(it, r.se()) }
        assertEquals(0xDEADBEEFL, r.u32())
        assertEquals(1, r.u(1)) // rbsp_stop_one_bit
    }

    @Test
    fun knownCodes() {
        // ue: 0 = 1, 1 = 010, 2 = 011, 3 = 00100; se: 1 = 010, -1 = 011.
        val r = BitReader(bytes(0b1_010_011_0, 0b0100_010_0, 0b11_000000))
        assertEquals(0, r.ue())
        assertEquals(1, r.ue())
        assertEquals(2, r.ue())
        assertEquals(3, r.ue())
        assertEquals(1, r.se())
        assertEquals(-1, r.se())
    }

    @Test
    fun readerFailsAtEndOfData() {
        val r = BitReader(bytes(0x00))
        assertFailsWith<BitstreamException> { r.ue() }
        assertFailsWith<BitstreamException> { BitReader(bytes(0xFF)).u(9) }
    }

    @Test
    fun emulationPreventionBytes() {
        val raw = bytes(0, 0, 0, 0, 1, 2, 3, 4, 0, 0)
        val escaped = Rbsp.escape(raw)
        assertContentEquals(bytes(0, 0, 3, 0, 0, 3, 1, 2, 3, 4, 0, 0), escaped)
        assertContentEquals(raw, Rbsp.unescape(escaped))
        val rnd = Random(42)
        repeat(200) {
            val data = ByteArray(rnd.nextInt(0, 64)) { (if (rnd.nextInt(3) == 0) rnd.nextInt(0, 4) else 0).toByte() }
            val e = Rbsp.escape(data)
            // 00 00 00, 00 00 01 y 00 00 02 no pueden aparecer dentro de una NAL (§7.4.1).
            for (i in 2 until e.size) {
                if (e[i - 2] == 0.toByte() && e[i - 1] == 0.toByte()) check(e[i] > 2) { "secuencia prohibida en ${e.toList()}" }
            }
            assertContentEquals(data, Rbsp.unescape(e))
        }
    }
}
