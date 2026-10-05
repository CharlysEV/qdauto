package dev.qdauto.app.video

import dev.qdauto.app.util.RateMeter
import java.util.concurrent.atomic.AtomicLong

/** Contadores del pipeline de vídeo (los actualizan los hilos de render y de salida del encoder). */
class PipelineStats {
    val rendered = AtomicLong()
    val renderRate = RateMeter()
    val renderLate = AtomicLong()
    val drawErrors = AtomicLong()
    private val drawNanosTotal = AtomicLong()

    val encoded = AtomicLong()
    val encodeRate = RateMeter()
    val keyframes = AtomicLong()
    val configs = AtomicLong()
    /** Frames que la sesión no aceptó (cerrada, aún sin `VIDEO_CTRL{1}`, atasco o esperando un IDR). */
    val rejected = AtomicLong()
    val bytes = AtomicLong()
    val maxFrameBytes = AtomicLong()
    val lastKeyframeBytes = AtomicLong()
    val keyframeRequests = AtomicLong()

    fun onRendered(drawNanos: Long) {
        rendered.incrementAndGet()
        renderRate.mark()
        drawNanosTotal.addAndGet(drawNanos)
    }

    fun onFrame(size: Int, keyframe: Boolean, accepted: Boolean) {
        encoded.incrementAndGet()
        encodeRate.mark()
        bytes.addAndGet(size.toLong())
        maxFrameBytes.accumulateAndGet(size.toLong()) { a, b -> maxOf(a, b) }
        if (keyframe) {
            keyframes.incrementAndGet()
            lastKeyframeBytes.set(size.toLong())
        }
        if (!accepted) rejected.incrementAndGet()
    }

    /** Tiempo medio de dibujo por frame (ms). */
    fun averageDrawMs(): Double {
        val n = rendered.get()
        return if (n == 0L) 0.0 else drawNanosTotal.get() / 1e6 / n
    }

    fun averageFrameBytes(): Long {
        val n = encoded.get()
        return if (n == 0L) 0 else bytes.get() / n
    }
}
