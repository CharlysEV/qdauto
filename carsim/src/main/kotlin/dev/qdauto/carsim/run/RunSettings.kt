package dev.qdauto.carsim.run

import dev.qdauto.carsim.cli.Options
import dev.qdauto.carsim.net.BroadcastTarget
import dev.qdauto.carsim.net.BroadcastTargets
import dev.qdauto.carsim.touch.TouchScript
import dev.qdauto.carsim.touch.TouchScripts
import dev.qdauto.core.wire.UdpCodec

enum class RunMode(val label: String) {
    CAR("coche"),
    SELF_TEST("autotest"),
}

/** Lo que cambia entre una prueba contra el móvil real y el autotest en local. */
data class RunSettings(
    val mode: RunMode,
    /** El primero lo manda `CarSim` desde UDP 18464; el resto, [dev.qdauto.carsim.net.BroadcastFanout]. */
    val targets: List<BroadcastTarget>,
    val phonePort: Int = UdpCodec.PHONE_PORT,
    val carPort: Int = UdpCodec.CAR_PORT,
    val broadcastIntervalMs: Long = 1_000,
    val heartbeatPeriodMs: Long = 2_000,
    /** Vídeo a observar tras el handshake; 0 = hasta Ctrl+C. */
    val durationMs: Long,
    val discoveryTimeoutMs: Long,
    val script: TouchScript,
    /** Comprobar antes de empezar que nadie tiene ya el puerto UDP del coche. */
    val checkPortFree: Boolean,
) {
    companion object {
        /** Espera máxima del primer frame antes de lanzar el guion táctil sin vídeo. */
        const val SCRIPT_VIDEO_WAIT_MS = 5_000L

        fun forCar(o: Options) = RunSettings(
            mode = RunMode.CAR,
            targets = BroadcastTargets.resolve(o.targets),
            durationMs = o.durationMs ?: Options.DEFAULT_DURATION_MS,
            discoveryTimeoutMs = o.discoveryTimeoutMs,
            script = o.touchScript ?: TouchScripts.DEFAULT,
            checkPortFree = true,
        )
    }
}
