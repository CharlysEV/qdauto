package dev.qdauto.app.p2p

import dev.qdauto.app.util.Clock
import java.util.EnumMap
import java.util.Locale

/**
 * Hitos de una conexión Wi-Fi Direct con su hora (spec 05 §8.4 #8-9). Los marcan varios hilos (controlador P2P,
 * descubrimiento UDP, aceptación TCP y eventos de sesión): thread-safe. Cada hito cuenta solo la primera vez.
 */
class P2pTimeline(private val nowMs: () -> Long = System::currentTimeMillis) {
    enum class Mark(val label: String) {
        TAP("pulsación"),
        CONNECT_OK("connect OK"),
        GROUP("grupo formado"),
        IP("IP P2P"),
        BROADCAST("primer broadcast por P2P"),
        ACK("ACK"),
        ACCEPT("TCP aceptado"),
        CAR_INFO("primer CAR_INFO"),
    }

    private val marks = EnumMap<Mark, Long>(Mark::class.java)
    private var target: String? = null

    @Synchronized
    fun reset(targetName: String?) {
        marks.clear()
        target = targetName
    }

    /** Marca [m] si aún no lo estaba; devuelve `true` la primera vez. */
    @Synchronized
    fun mark(m: Mark): Boolean {
        if (marks.containsKey(m)) return false
        marks[m] = nowMs()
        return true
    }

    @Synchronized
    fun has(m: Mark): Boolean = marks.containsKey(m)

    /** Para volver a medir un hito (p. ej. el ACK del siguiente intento tras un TCP que no llegó). */
    @Synchronized
    fun clear(m: Mark) {
        marks.remove(m)
    }

    /** "pulsación 12:00:01.123 · connect OK +0,1 s · grupo formado +3,2 s · …" (cada salto, desde el hito anterior). */
    @Synchronized
    fun describe(): String {
        val parts = ArrayList<String>(marks.size)
        var previous: Long? = null
        for ((m, t) in marks) {
            val p = previous
            parts += if (p == null) "${m.label} ${Clock.time(t)}" else "${m.label} +${seconds(t - p)}"
            previous = t
        }
        return parts.joinToString(" · ")
    }

    /** «P2P: coche «X» (coche GO, 5180 MHz), unión 3,2 s, primer broadcast a 1,1 s, TCP a 0,4 s». */
    @Synchronized
    fun summary(group: P2pGroupInfo?): String {
        val role = when {
            group == null -> "sin grupo"
            group.phoneIsOwner -> "móvil GO"
            else -> "coche GO"
        }
        val freq = group?.frequencyMhz?.let { ", $it MHz" } ?: ""
        return "P2P: coche «${target ?: "?"}» ($role$freq), " +
            listOf(
                span(Mark.TAP, Mark.GROUP)?.let { "unión $it" } ?: "sin grupo",
                span(Mark.GROUP, Mark.BROADCAST)?.let { "primer broadcast a $it" } ?: "sin broadcast por P2P",
                span(Mark.ACK, Mark.ACCEPT)?.let { "TCP a $it" } ?: "sin TCP",
            ).joinToString(", ")
    }

    private fun span(from: Mark, to: Mark): String? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return seconds(b - a)
    }

    companion object {
        /** "3,2 s" (coma decimal, como el resumen de la spec). */
        fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms.coerceAtLeast(0) / 1000.0).replace('.', ',')
    }
}
