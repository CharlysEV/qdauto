package dev.qdauto.app.touch

/**
 * Cómo leer los `x,y` float del táctil del coche. Se desconoce el espacio de coordenadas (spec §9.3, §11.9):
 * QDLink in-app (appType 1) espera px del frame; en espejo (appType 2), px de la pantalla del teléfono.
 */
enum class TouchMapping(val label: String, val short: String) {
    AUTO("Automático (heurística)", "auto"),
    FRAME_PX("Píxeles del frame de vídeo", "px frame"),
    NORMALIZED("Normalizado 0-1", "0-1"),
    CAR_PX("Píxeles de CAR_INFO", "px CAR_INFO"),
    PHONE_PX("Píxeles del teléfono en horizontal", "px teléfono"),
}

/** Tamaños con los que se interpretan las coordenadas. [carW]/[carH] = 0 si aún no hay `CAR_INFO`. */
data class TouchSpace(
    val frameW: Int,
    val frameH: Int,
    val carW: Int,
    val carH: Int,
    val phoneLong: Int,
    val phoneShort: Int,
)

/** Punto ya convertido a píxeles del frame. */
data class FramePoint(val x: Float, val y: Float, val inside: Boolean)

/** Valores extremos vistos en la sesión: deciden la interpretación automática. */
data class TouchExtent(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float, val sawFraction: Boolean) {
    fun include(x: Float, y: Float): TouchExtent = TouchExtent(
        minOf(minX, x), minOf(minY, y), maxOf(maxX, x), maxOf(maxY, y),
        sawFraction || x != x.toInt().toFloat() || y != y.toInt().toFloat(),
    )

    companion object {
        val EMPTY = TouchExtent(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, false)
    }
}

object TouchMapper {
    /**
     * Interpretación automática: el espacio más pequeño que contiene todos los toques vistos en la sesión, para que
     * todos los gestos se lean igual. 0-1 solo si todos caben en [0, 1] y alguno tiene decimales.
     */
    fun guess(extent: TouchExtent, space: TouchSpace): TouchMapping {
        if (extent.maxX < extent.minX) return TouchMapping.FRAME_PX // aún no hay toques
        if (extent.minX < 0f || extent.minY < 0f) return TouchMapping.FRAME_PX
        val x = extent.maxX
        val y = extent.maxY
        return when {
            x <= 1f && y <= 1f && extent.sawFraction -> TouchMapping.NORMALIZED
            fits(x, y, space.frameW, space.frameH) -> TouchMapping.FRAME_PX
            space.carW > 0 && space.carH > 0 && fits(x, y, space.carW, space.carH) -> TouchMapping.CAR_PX
            fits(x, y, space.phoneLong, space.phoneShort) -> TouchMapping.PHONE_PX
            else -> TouchMapping.FRAME_PX
        }
    }

    fun resolve(mode: TouchMapping, extent: TouchExtent, space: TouchSpace): TouchMapping =
        if (mode == TouchMapping.AUTO) guess(extent, space) else mode

    /** Convierte un punto crudo a px del frame según [mapping] (que no debe ser [TouchMapping.AUTO]). */
    fun toFrame(x: Float, y: Float, mapping: TouchMapping, space: TouchSpace): FramePoint {
        val w = space.frameW.toFloat()
        val h = space.frameH.toFloat()
        val fx: Float
        val fy: Float
        when (mapping) {
            TouchMapping.AUTO, TouchMapping.FRAME_PX -> {
                fx = x
                fy = y
            }
            TouchMapping.NORMALIZED -> {
                fx = x * w
                fy = y * h
            }
            TouchMapping.CAR_PX -> {
                fx = if (space.carW > 0) x * w / space.carW else x
                fy = if (space.carH > 0) y * h / space.carH else y
            }
            TouchMapping.PHONE_PX -> {
                fx = if (space.phoneLong > 0) x * w / space.phoneLong else x
                fy = if (space.phoneShort > 0) y * h / space.phoneShort else y
            }
        }
        val inside = fx.isFinite() && fy.isFinite() && fx >= 0f && fy >= 0f && fx <= w && fy <= h
        return FramePoint(fx, fy, inside)
    }

    private fun fits(x: Float, y: Float, w: Int, h: Int): Boolean = w > 0 && h > 0 && x <= w && y <= h
}
