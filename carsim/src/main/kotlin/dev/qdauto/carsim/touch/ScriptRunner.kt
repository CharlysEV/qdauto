package dev.qdauto.carsim.touch

import dev.qdauto.carsim.Console
import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.RunClock
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchPointer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Resultado de un paso del guion. */
data class StepResult(val tMs: Long, val step: ScriptStep, val ok: Boolean, val detail: String)

/**
 * Ejecuta un [TouchScript] contra el [CarSim] en su propio hilo (`carsim-guion`). Empieza con el primer frame de
 * vídeo o, si no llega, tras una espera máxima. Se para en el primer paso que no se pueda enviar.
 */
class ScriptRunner(
    private val sim: CarSim,
    val script: TouchScript,
    private val width: Int,
    private val height: Int,
    private val clock: RunClock,
) {
    private val results = CopyOnWriteArrayList<StepResult>()
    private val done = CountDownLatch(1)
    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    @Volatile
    var startedAtMs: Long? = null
        private set

    /** Empezó sin haber recibido vídeo (se cumplió la espera máxima). */
    @Volatile
    var startedWithoutVideo = false
        private set

    val isDone: Boolean get() = done.count == 0L
    val completed: Boolean get() = isDone && results.size == script.steps.size && results.all { it.ok }

    fun results(): List<StepResult> = results.toList()

    fun start(videoArrived: () -> Boolean, maxWaitMs: Long): ScriptRunner {
        thread = Thread({ run(videoArrived, maxWaitMs) }, "carsim-guion").apply {
            isDaemon = true
            start()
        }
        return this
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
    }

    fun await(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun run(videoArrived: () -> Boolean, maxWaitMs: Long) {
        try {
            val deadline = System.nanoTime() + maxWaitMs * 1_000_000
            while (!stopped && !videoArrived() && System.nanoTime() < deadline) pause(50)
            if (stopped) return
            startedWithoutVideo = !videoArrived()
            startedAtMs = clock.elapsedMs()
            val why = if (startedWithoutVideo) "sin vídeo tras ${Fmt.duration(maxWaitMs)}" else "con el primer frame de vídeo"
            Console.line("${clock.stamp()}  guion táctil ${script.origin}: empieza $why (${script.steps.size} pasos)")
            for (step in script.steps) {
                if (stopped) break
                val t = clock.elapsedMs()
                val (ok, detail) = execute(step)
                if (!ok && stopped) break // parado desde fuera: el paso no falló, se cortó
                results += StepResult(t, step, ok, detail)
                if (step !is WaitStep || !ok) {
                    val shown = if (detail.isEmpty()) step.text else "${step.text} -> $detail"
                    Console.line("${Fmt.stamp(t)}  guion: $shown${if (ok) "" else "  (FALLO)"}")
                }
                if (!ok) break
            }
            if (!stopped) Console.line("${clock.stamp()}  guion táctil terminado: ${sim.sentTouches().size} mensajes táctiles enviados")
        } catch (_: InterruptedException) {
            // parado desde fuera
        } finally {
            done.countDown()
        }
    }

    private fun execute(step: ScriptStep): Pair<Boolean, String> = when (step) {
        is WaitStep -> pause(step.ms).let { finished -> finished to (if (finished) "" else "interrumpida") }
        is TapStep -> {
            val x = step.x.resolve(width)
            val y = step.y.resolve(height)
            sim.tap(x, y, holdMs = step.holdMs) to point(x, y)
        }
        is DragStep -> {
            val x0 = step.x0.resolve(width)
            val y0 = step.y0.resolve(height)
            val x1 = step.x1.resolve(width)
            val y1 = step.y1.resolve(height)
            sim.drag(x0, y0, x1, y1, step.steps, step.durationMs) to "${point(x0, y0)} a ${point(x1, y1)}"
        }
        is TwoFingerStep -> {
            val x0 = step.x0.resolve(width)
            val y0 = step.y0.resolve(height)
            val x1 = step.x1.resolve(width)
            val y1 = step.y1.resolve(height)
            twoFingers(x0, y0, x1, y1, step.holdMs) to "${point(x0, y0)} y ${point(x1, y1)}"
        }
        is PinchStep -> {
            val cx = step.cx.resolve(width)
            val cy = step.cy.resolve(height)
            val d0 = step.d0.resolve(width)
            val d1 = step.d1.resolve(width)
            sim.pinch(cx, cy, d0, d1, step.steps, step.durationMs) to "centro ${point(cx, cy)}, ${Fmt.num(d0)} a ${Fmt.num(d1)} px"
        }
        is KeyStep -> sim.sendPhoneKey(step.code) to "PHONE_KEYS ${step.code}"
        is MusicStep -> sim.sendMusicKey(step.functionId) to "Music/${step.functionId}"
        KeyframeStep -> sim.requestKeyframe() to "KEY_FRAME_REQ"
    }

    /**
     * Secuencia de `MotionEvent` de Android, la misma que usa `CarSim.pinch`: DOWN del dedo 0, POINTER_DOWN del 1,
     * POINTER_UP y UP. Los códigos 5/6 son una suposición (spec §9.2 [INFERENCIA]); QDLink solo distingue 0 y 1.
     */
    private fun twoFingers(x0: Float, y0: Float, x1: Float, y1: Float, holdMs: Long): Boolean {
        if (!sim.sendTouch(TouchCodec.ACTION_DOWN, listOf(TouchPointer.down(0, x0, y0)))) return false
        if (!sim.sendTouch(CarSim.ACTION_POINTER_DOWN, listOf(TouchPointer.move(0, x0, y0), TouchPointer.down(1, x1, y1)))) return false
        pause(holdMs)
        if (!sim.sendTouch(CarSim.ACTION_POINTER_UP, listOf(TouchPointer.move(0, x0, y0), TouchPointer.up(1, x1, y1)))) return false
        return sim.sendTouch(TouchCodec.ACTION_UP, listOf(TouchPointer.up(0, x0, y0)))
    }

    /** Espera en trozos para poder parar a tiempo. `false` si se paró antes. */
    private fun pause(ms: Long): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (!stopped) {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return true
            try {
                Thread.sleep(minOf(left, 50))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun point(x: Float, y: Float) = "(${Fmt.num(x)}, ${Fmt.num(y)})"
}
