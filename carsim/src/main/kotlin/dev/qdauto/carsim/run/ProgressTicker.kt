package dev.qdauto.carsim.run

import dev.qdauto.carsim.Console
import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.RunClock
import dev.qdauto.carsim.net.BroadcastFanout
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimState
import java.io.File
import kotlin.math.roundToInt

/**
 * Progreso en directo (`carsim-progreso`): cada segundo en una consola interactiva (la línea de estado se reescribe
 * en su sitio) y cada 5 s si la salida va a un fichero. Además, en cuanto la grabación lo permite, lee y muestra
 * el primer SPS.
 */
class ProgressTicker(
    private val clock: RunClock,
    private val sim: CarSim,
    private val recorder: RunRecorder,
    private val fanout: BroadcastFanout?,
    private val recording: File,
) {
    private val thread = Thread(::loop, "carsim-progreso").apply { isDaemon = true }

    @Volatile
    private var stopped = false
    private var spsShown = false

    fun start(): ProgressTicker {
        thread.start()
        return this
    }

    fun stop() {
        stopped = true
        thread.interrupt()
        thread.join(1_000)
        Console.clearStatus()
    }

    private fun loop() {
        var tick = 0
        while (!stopped) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                break
            }
            if (stopped) break
            tick++
            showSpsOnce()
            val state = sim.currentState
            if (state == CarSimState.CLOSED) continue
            if (Console.interactive || tick % 5 == 0) Console.status(line(state))
        }
    }

    private fun line(state: CarSimState): String {
        val t = "[${Fmt.secs(clock.elapsedMs())}]"
        val r = sim.report()
        return when (state) {
            CarSimState.IDLE, CarSimState.DISCOVERING ->
                "$t BUSCANDO: ${r.broadcastsSent + (fanout?.sent ?: 0)} broadcasts enviados, sin ACK"
            CarSimState.CONNECTING -> "$t CONECTANDO por TCP a ${Fmt.address(r.ackFrom)}"
            CarSimState.HANDSHAKE -> "$t HANDSHAKE: ${recorder.phoneMessageCount()} mensajes del teléfono"
            CarSimState.STREAMING -> {
                val v = recorder.liveVideo()
                val errors = r.videoErrorCount + r.unexpected.size + r.handshakeErrors.size
                "$t VIDEO ${Fmt.dec(v.fps)} fps ${v.kbps.roundToInt()} kbit/s | frames ${v.frames} IDR ${v.idr} | errores $errors | toques ${r.touchesSent}"
            }
            CarSimState.CLOSED -> "$t CERRADO"
        }
    }

    private fun showSpsOnce() {
        if (spsShown) return
        val segment = recorder.firstSpsSegment() ?: return
        // La grabación va por un BufferedOutputStream: hasta que no se vuelca, el SPS no está en el fichero.
        if (recording.length() < segment.fileOffset + segment.length) return
        spsShown = true
        val result = SpsReader.read(recording, segment)
        val text = result.info?.describe() ?: result.error
        Console.line("${Fmt.stamp(clock.elapsedMs())}  SPS: $text")
    }
}
