package dev.qdauto.carsim.selftest

import dev.qdauto.carsim.h264.SyntheticH264
import dev.qdauto.core.session.EncoderSuggestion
import dev.qdauto.core.session.PhoneSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Encoder" del teléfono simulado (`autotest-encoder`): con el tamaño, fps, bitrate y GOP que sugiere la sesión
 * (inCar y `VIDEO_ARGS`), manda SPS/PPS válidos y luego frames falsos a ritmo fijo, con un IDR cada FrameInterval
 * segundos y otro cada vez que la sesión lo pide (inicio, `KEY_FRAME_REQ`, atasco), como haría `MediaCodec`.
 */
class FakeEncoder {
    private val idrRequested = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val framesAccepted = AtomicInteger()
    val framesRejected = AtomicInteger()
    val keyframes = AtomicInteger()

    @Volatile
    var suggestion: EncoderSuggestion? = null
        private set

    @Volatile
    var frameBytes: Pair<Int, Int>? = null
        private set

    fun start(session: PhoneSession) {
        if (!started.compareAndSet(false, true)) return
        running = true
        thread = Thread({ loop(session) }, "autotest-encoder").apply {
            isDaemon = true
            start()
        }
    }

    fun requestKeyframe() = idrRequested.set(true)

    fun stop() {
        running = false
        thread?.interrupt()
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    val isAlive: Boolean get() = thread?.isAlive == true

    private fun loop(session: PhoneSession) {
        val s = session.encoderSuggestion
        suggestion = s
        val fps = s.frameRate.coerceIn(1, 240)
        val gopFrames = (s.iFrameIntervalSec * fps).coerceAtLeast(1)
        // IDR = 4 P: un GOP de N frames pesa N + 3 P, así que se reparte el bitrate pedido entre N + 3.
        val pSize = (s.bitRate.toLong() / 8 / fps * gopFrames / (gopFrames + 3)).toInt().coerceIn(200, 256 * 1024)
        val idrSize = (pSize * 4).coerceAtMost(1024 * 1024)
        frameBytes = pSize to idrSize
        session.sendCodecConfig(SyntheticH264.codecConfig(s.width, s.height, fps))
        val periodNanos = 1_000_000_000L / fps
        var next = System.nanoTime()
        var index = 0
        var sinceIdr = gopFrames
        while (running && !session.isClosed) {
            val key = idrRequested.getAndSet(false) || sinceIdr >= gopFrames
            val frame = SyntheticH264.fakeFrame(index, key, if (key) idrSize else pSize)
            if (session.sendFrame(frame, key, index * 1_000_000L / fps)) {
                framesAccepted.incrementAndGet()
                if (key) keyframes.incrementAndGet()
            } else {
                framesRejected.incrementAndGet()
            }
            sinceIdr = if (key) 1 else sinceIdr + 1
            index++
            next += periodNanos
            val wait = next - System.nanoTime()
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    break
                }
            } else {
                next = System.nanoTime() // con retraso: no se acumulan frames atrasados
            }
        }
    }
}
