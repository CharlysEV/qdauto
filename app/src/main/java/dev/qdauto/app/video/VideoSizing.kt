package dev.qdauto.app.video

import dev.qdauto.app.settings.SizeSource

data class IntSize(val width: Int, val height: Int) {
    override fun toString(): String = "${width}×$height"
}

/** Tamaño del encoder a partir de los ajustes, `CAR_INFO` y `VIDEO_ARGS`. */
object VideoSizing {
    /** QDLink usa 800×480 si `CAR_INFO` no trae tamaño (LC/a.java:919). */
    val FALLBACK = IntSize(800, 480)
    const val MIN_SIDE = 16
    const val MAX_SIDE = 4096

    class Decision(val size: IntSize, val notes: List<String>)

    /**
     * @param carInCar par(CarWidth)×par(CarHeight) tal y como lo calcula `:core` (`EncoderSuggestion`), que ya
     *   sustituye 0×0 por 800×480.
     * @param videoArgs `VIDEO_ARGS.Width×Height` (QDLink los ignora para el encoder, spec §6.9).
     */
    fun resolve(source: SizeSource, carInCar: IntSize, videoArgs: IntSize?, manual: IntSize, align16: Boolean): Decision {
        val notes = ArrayList<String>()
        var size = when (source) {
            SizeSource.CAR_INFO -> carInCar.also { notes += "tamaño de CAR_INFO $it" }
            SizeSource.VIDEO_ARGS ->
                if (videoArgs != null && videoArgs.width > 0 && videoArgs.height > 0) {
                    IntSize(even(videoArgs.width), even(videoArgs.height)).also { notes += "tamaño de VIDEO_ARGS $it" }
                } else {
                    carInCar.also { notes += "VIDEO_ARGS sin Width/Height: se usa CAR_INFO $it" }
                }
            SizeSource.MANUAL -> IntSize(even(manual.width), even(manual.height)).also { notes += "tamaño manual $it" }
        }
        if (size.width <= 0 || size.height <= 0) {
            notes += "tamaño $size no válido: se usa $FALLBACK, como QDLink"
            size = FALLBACK
        }
        if (align16) {
            val aligned = IntSize(align16(size.width), align16(size.height))
            if (aligned != size) notes += "alineado a 16: $size → $aligned"
            size = aligned
        }
        val clamped = IntSize(size.width.coerceIn(MIN_SIDE, MAX_SIDE), size.height.coerceIn(MIN_SIDE, MAX_SIDE))
        if (clamped != size) notes += "recortado a $MIN_SIDE-$MAX_SIDE: $size → $clamped"
        return Decision(clamped, notes)
    }

    /** "Redondeo a par" de QDLink: +1 si es impar. */
    fun even(v: Int): Int = if (v % 2 != 0) v + 1 else v

    /** Múltiplo de 16 más cercano (empate hacia arriba: 1080 → 1088), nunca menos de 16. */
    fun align16(v: Int): Int = maxOf(16, (v + 8) / 16 * 16)
}
