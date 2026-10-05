package dev.qdauto.carsim.cli

import dev.qdauto.carsim.touch.TouchScript
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.VideoArgsValues
import java.io.File

/** Opciones de la línea de comandos. Los valores por defecto del coche salen de `:core` (suposición del C10). */
data class Options(
    /** Destinos de `--target` tal y como se escribieron ('broadcast', IP o nombre); vacío = 'broadcast'. */
    val targets: List<String> = emptyList(),
    val width: Int = DEFAULT_CAR.carWidth,
    val height: Int = DEFAULT_CAR.carHeight,
    val carType: String = DEFAULT_CAR.carType,
    val fps: Int = DEFAULT_VIDEO.frameRate,
    val bitrate: Int = DEFAULT_VIDEO.bitRate,
    val gop: Int = DEFAULT_VIDEO.frameInterval,
    /** Vídeo a observar tras el handshake; `null` = el del modo ([DEFAULT_DURATION_MS] o [SELF_TEST_DURATION_MS]). 0 = hasta Ctrl+C. */
    val durationMs: Long? = null,
    val discoveryTimeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS,
    val out: File? = null,
    val report: File? = null,
    /** `null` = el guion por defecto del modo. */
    val touchScript: TouchScript? = null,
    val verbose: Boolean = false,
    val selfTest: Boolean = false,
    val help: Boolean = false,
) {
    companion object {
        val DEFAULT_CAR = CarInfoValues()
        val DEFAULT_VIDEO = VideoArgsValues()
        const val DEFAULT_DURATION_MS = 30_000L
        const val SELF_TEST_DURATION_MS = 8_000L
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 120_000L
    }
}
