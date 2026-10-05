package dev.qdauto.carsim

import java.util.Locale

/**
 * Formato de la salida en español: coma decimal y sin separador de miles. Solo ASCII y letras latinas
 * (la consola de Windows suele estar en cp850, que no tiene "→", "…" ni "×").
 */
object Fmt {
    private val ES: Locale = Locale.forLanguageTag("es-ES")

    fun dec(value: Double, digits: Int = 1): String = String.format(ES, "%.${digits}f", value)

    /** Marca de tiempo de la prueba, alineada: "   4,512 s". */
    fun stamp(ms: Long): String = String.format(ES, "%8.3f s", ms / 1000.0)

    /** "12,3 s". */
    fun secs(ms: Long, digits: Int = 1): String = dec(ms / 1000.0, digits) + " s"

    /** Duración configurada: "30 s", "1,2 s". */
    fun duration(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else secs(ms, if (ms % 100 == 0L) 1 else 3)

    fun size(width: Int, height: Int): String = "${width}x$height"

    /** "14,3 MB", "512,0 KB", "300 B". */
    fun bytes(n: Long): String = when {
        n >= 1_000_000 -> dec(n / 1_000_000.0) + " MB"
        n >= 1_000 -> dec(n / 1_000.0) + " KB"
        else -> "$n B"
    }

    /** Número sin decimales si es entero ("480"), con uno si no ("100,5"). */
    fun num(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else dec(v.toDouble(), 2).trimEnd('0')

    fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "..."

    /** "1 broadcast enviado" / "3 broadcasts enviados". */
    fun count(n: Number, one: String, many: String): String = "$n ${if (n.toLong() == 1L) one else many}"

    /**
     * Errores de vídeo de `CarSimReport` ("#12: ancho 1000 != 1920") agrupados por texto, en orden de aparición:
     * "ancho 1000 != 1920 (35 mensajes, el primero #0)".
     */
    fun groupedErrors(errors: List<String>): List<String> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (e in errors) {
            val text = e.substringAfter(": ", e)
            groups.getOrPut(text) { ArrayList() } += e.substringBefore(": ", "")
        }
        return groups.map { (text, where) ->
            if (where.size == 1) "$text (mensaje ${where[0]})" else "$text (${where.size} mensajes, el primero ${where[0]})"
        }
    }

    /** "/192.168.1.50:18463" (InetSocketAddress.toString) sin la barra inicial. */
    fun address(s: String?): String = s?.removePrefix("/") ?: "-"
}
