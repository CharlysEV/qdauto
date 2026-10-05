package dev.qdauto.app.link

import dev.qdauto.app.log.AppLog
import dev.qdauto.app.util.Clock
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimListener
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.VideoArgsValues
import dev.qdauto.core.sim.VideoFrameInfo
import dev.qdauto.core.wire.FunctionIds
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Autoprueba en el propio móvil, antes del viaje: un [CarSim] de `:core` hace de coche por 127.0.0.1 contra el servicio
 * en marcha (broadcast → ACK → TCP → handshake → vídeo real de MediaCodec → táctil y teclas) y valida el vídeo que
 * recibe. El H.264 recibido se guarda junto a los logs (`ffplay -f h264 autoprueba-….h264`).
 */
class SelfTest {
    @Volatile
    var status: String = "Sin ejecutar"
        private set

    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun start(engine: LinkEngine): Boolean {
        if (!running.compareAndSet(false, true)) return false
        status = "${Clock.now()} arrancando el coche simulado…"
        Thread({
            try {
                run(engine)
            } catch (t: Throwable) {
                AppLog.e(TAG, "la autoprueba falló", t)
                status = "${Clock.now()} ERROR: $t"
            } finally {
                running.set(false)
            }
        }, "qd-app-selftest").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun run(engine: LinkEngine) {
        val dir = AppLog.directory()
        val file = dir?.let { File(it, "autoprueba-${Clock.stamp()}.h264") }
        val videoErrors = AtomicInteger()
        val sim = CarSim(
            CarSimConfig(
                broadcastAddress = InetAddress.getLoopbackAddress(),
                deviceUuid = SIM_UUID,
                deviceName = "C10-SIM",
                discoveryTimeoutMs = 30_000,
                carInfo = CarInfoValues(carWidth = 1280, carHeight = 720),
                videoArgs = VideoArgsValues(frameRate = 30, bitRate = 4_000_000, frameInterval = 1),
                recordVideoTo = file,
                // W×H y appType dependen de los ajustes (alineación, tamaño manual…): se informan, no se validan.
                expectedWidth = 0,
                expectedHeight = 0,
                expectedAppType = null,
            ),
            object : CarSimListener {
                override fun onStateChanged(state: CarSimState) {
                    status = "${Clock.now()} coche simulado: $state"
                }

                override fun onVideoFrame(info: VideoFrameInfo) {
                    if (info.errors.isNotEmpty() && videoErrors.incrementAndGet() <= 5) {
                        AppLog.w(TAG, "vídeo #${info.index} ${info.kind}: ${info.errors}")
                    }
                }
            },
            AppLog.qdLog,
        ).start()
        try {
            // Con la conexión automática desactivada (o un filtro), se conecta a mano en cuanto aparece.
            val deadline = System.currentTimeMillis() + 10_000
            var key: String? = null
            while (key == null && System.currentTimeMillis() < deadline && sim.currentState != CarSimState.CLOSED) {
                key = engine.findCarByUuid(SIM_UUID)
                if (key == null) Thread.sleep(200)
            }
            if (key == null) {
                status = "${Clock.now()} FALLO: el servicio no vio el broadcast del coche simulado (¿servicio parado?)"
                return
            }
            if (!reached(sim, CarSimState.CONNECTING, 1_500)) engine.connect(key)
            if (!sim.awaitState(CarSimState.STREAMING, 30_000)) {
                status = "${Clock.now()} FALLO: no se llegó a STREAMING\n${sim.report().summary()}"
                return
            }
            status = "${Clock.now()} vídeo pedido; esperando frames…"
            val gotVideo = sim.awaitVideoMessages(60, 15_000)
            status = "${Clock.now()} enviando táctil y teclas…"
            sim.tap(640f, 360f, holdMs = 300)
            Thread.sleep(400)
            sim.drag(100f, 600f, 1180f, 120f, steps = 40, durationMs = 2_000)
            Thread.sleep(400)
            sim.pinch(640f, 360f, startDistance = 150f, endDistance = 600f, steps = 20, durationMs = 1_000)
            sim.requestKeyframe()
            sim.sendPhoneKey(2)
            sim.sendMusicKey(FunctionIds.NEXT)
            Thread.sleep(3_000)
            val r = sim.report()
            val verdict = if (gotVideo && r.videoValid && r.handshakeErrors.isEmpty()) "OK" else "CON ERRORES"
            status = "${Clock.now()} $verdict\n${r.summary().trim()}" + (file?.let { "\nH.264 recibido: ${it.path}" } ?: "")
            AppLog.i(TAG, "autoprueba $verdict:\n${r.summary()}")
        } finally {
            sim.close()
            sim.awaitTermination(5_000)
        }
    }

    /** ¿Ha llegado el simulador a [state] o más allá (sin cerrarse) en [timeoutMs]? */
    private fun reached(sim: CarSim, state: CarSimState, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val s = sim.currentState
            if (s != CarSimState.CLOSED && s.ordinal >= state.ordinal) return true
            if (s == CarSimState.CLOSED || System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TAG = "QD/SelfTest"
        const val SIM_UUID = "QDAUTO-SIM-0001"
    }
}
