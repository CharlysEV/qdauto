package dev.qdauto.carsim.h264

import java.io.ByteArrayOutputStream

/** Bitstream H.264 mal formado o más corto de lo que dicen sus campos. */
class BitstreamException(message: String) : Exception(message)

/** Paso entre NAL y RBSP: bytes de prevención de emulación `00 00 03` (H.264 §7.4.1). */
object Rbsp {
    /** Quita el `03` de cada `00 00 03`. */
    fun unescape(src: ByteArray, off: Int = 0, len: Int = src.size - off): ByteArray {
        val out = ByteArrayOutputStream(len)
        var zeros = 0
        for (i in off until off + len) {
            val b = src[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Inserta un `03` tras cada `00 00` seguido de un byte <= 3. */
    fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + rbsp.size / 64 + 4)
        var zeros = 0
        for (x in rbsp) {
            val b = x.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}

/** Lector de bits MSB primero con Exp-Golomb (H.264 §9.1). Lanza [BitstreamException] si se acaban los datos. */
class BitReader(private val data: ByteArray) {
    private var bit = 0L

    val bitsLeft: Long get() = data.size * 8L - bit

    /** Entero sin signo de [n] bits (0-31). */
    fun u(n: Int): Int {
        require(n in 0..31) { "u($n) fuera de rango" }
        if (bitsLeft < n) throw BitstreamException("faltan bits: se piden $n y quedan $bitsLeft")
        var v = 0
        repeat(n) { v = (v shl 1) or nextBit() }
        return v
    }

    fun u32(): Long = (u(16).toLong() shl 16) or u(16).toLong()

    fun flag(): Boolean = u(1) == 1

    /** ue(v). */
    fun ue(): Int {
        var zeros = 0
        while (u(1) == 0) {
            if (++zeros > 31) throw BitstreamException("código Exp-Golomb con más de 31 ceros")
        }
        if (zeros == 0) return 0
        val v = (1L shl zeros) - 1 + u(zeros)
        if (v > Int.MAX_VALUE) throw BitstreamException("código Exp-Golomb fuera de rango")
        return v.toInt()
    }

    /** se(v): 1, -1, 2, -2... */
    fun se(): Int {
        val k = ue().toLong()
        return (if (k % 2 == 1L) (k + 1) / 2 else -(k / 2)).toInt()
    }

    private fun nextBit(): Int {
        val b = (data[(bit ushr 3).toInt()].toInt() ushr (7 - (bit and 7).toInt())) and 1
        bit++
        return b
    }
}

/** Escritor de bits MSB primero con Exp-Golomb, para generar SPS/PPS de prueba. */
class BitWriter {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var count = 0

    fun u(n: Int, value: Long) {
        for (i in n - 1 downTo 0) bit(((value ushr i) and 1L).toInt())
    }

    fun u(n: Int, value: Int) = u(n, value.toLong())

    fun flag(b: Boolean) = bit(if (b) 1 else 0)

    fun ue(value: Int) {
        require(value >= 0) { "ue($value) negativo" }
        val v = value.toLong() + 1
        val len = 64 - java.lang.Long.numberOfLeadingZeros(v)
        u(len - 1, 0L)
        u(len, v)
    }

    fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

    /** rbsp_trailing_bits(): un 1 y ceros hasta el byte. */
    fun trailingBits() {
        bit(1)
        while (count != 0) bit(0)
    }

    fun toByteArray(): ByteArray {
        check(count == 0) { "faltan bits hasta completar el byte" }
        return out.toByteArray()
    }

    private fun bit(b: Int) {
        current = (current shl 1) or b
        if (++count == 8) {
            out.write(current)
            current = 0
            count = 0
        }
    }
}
