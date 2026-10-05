package dev.qdauto.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Process
import android.view.Surface
import dev.qdauto.core.h264.AnnexB
import dev.qdauto.core.h264.NalType
import dev.qdauto.core.util.Hex
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Un encoder H.264 en marcha: el hilo `qd-app-render` dibuja el [TestPattern] en la Surface de entrada a [EncoderSpec.fps]
 * con `lockHardwareCanvas()` y el hilo `qd-app-drain` saca los buffers de salida hacia la sesión (SPS/PPS con
 * `BUFFER_FLAG_CODEC_CONFIG` → [FrameSink.onCodecConfig]; el resto → [FrameSink.onFrame]). Un pipeline sirve para un
 * solo arranque: para cambiar de tamaño se crea otro.
 */
class VideoPipeline(
    val spec: EncoderSpec,
    private val prepared: EncoderSetup.Prepared,
    private val sink: FrameSink,
    val pattern: TestPattern,
    private val log: QdLog,
    private val onFatal: (VideoPipeline, Throwable) -> Unit,
) {
    interface FrameSink {
        fun onCodecConfig(buffer: ByteBuffer): Boolean
        fun onFrame(buffer: ByteBuffer, keyframe: Boolean, ptsUs: Long): Boolean
    }

    val stats = PipelineStats()

    @Volatile
    var created: EncoderSetup.Created? = null
        private set

    @Volatile
    var renderMode: String = "—"
        private set

    /** Acepta peticiones (IDR, bitrate) y avisa de fallos. */
    @Volatile
    private var running = false

    /** Se separan para parar primero el dibujo y seguir vaciando el encoder hasta que el render haya terminado. */
    @Volatile
    private var rendering = false

    @Volatile
    private var draining = false
    private val fatalReported = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var drainThread: Thread? = null

    val isRunning: Boolean get() = running

    /** Crea, configura y arranca el encoder (cascada desde [firstStep]) y los dos hilos. Lanza si no hay encoder. */
    fun start(firstStep: Int) {
        val c = EncoderSetup.create(prepared, firstStep, log)
        created = c
        running = true
        rendering = true
        draining = true
        drainThread = Thread({ drainLoop(c.codec) }, "qd-app-drain").apply {
            isDaemon = true
            start()
        }
        renderThread = Thread({ renderLoop(c.surface) }, "qd-app-render").apply {
            isDaemon = true
            start()
        }
        log.i(TAG, "encoder ${c.codecName} en marcha: ${spec.size} ${spec.fps} fps ${spec.bitrate} bps GOP ${spec.gopSeconds} s (paso ${c.step})")
    }

    /** Pide un IDR al encoder (`PARAMETER_KEY_REQUEST_SYNC_FRAME`). */
    fun requestKeyframe(): Boolean {
        val c = created ?: return false
        if (!running) return false
        return try {
            c.codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            stats.keyframeRequests.incrementAndGet()
            true
        } catch (e: IllegalStateException) {
            log.w(TAG, "no se pudo pedir un IDR: $e")
            false
        }
    }

    /** Cambia el bitrate en caliente (`PARAMETER_KEY_VIDEO_BITRATE`). */
    fun setBitrate(bitrate: Int): Boolean {
        val c = created ?: return false
        if (!running) return false
        return try {
            c.codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
            log.i(TAG, "bitrate en caliente → $bitrate bps")
            true
        } catch (e: IllegalStateException) {
            log.w(TAG, "no se pudo cambiar el bitrate: $e")
            false
        }
    }

    /** Para los hilos y libera el encoder, la Surface y el patrón. Idempotente; bloquea como mucho unos segundos. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running = false
        rendering = false
        renderThread?.let { t ->
            LockSupport.unpark(t)
            t.join(JOIN_TIMEOUT_MS)
            if (t.isAlive) log.w(TAG, "el hilo de render no terminó a tiempo")
        }
        // El encoder se vacía hasta aquí: así un render esperando un buffer de entrada no se queda bloqueado.
        draining = false
        drainThread?.let { t ->
            t.join(JOIN_TIMEOUT_MS)
            if (t.isAlive) log.w(TAG, "el hilo de salida del encoder no terminó a tiempo")
        }
        val c = created
        if (c != null) {
            try {
                c.codec.stop()
            } catch (e: Exception) {
                log.w(TAG, "codec.stop(): $e")
            }
            try {
                c.codec.release()
            } catch (e: Exception) {
                log.w(TAG, "codec.release(): $e")
            }
            try {
                c.surface.release()
            } catch (_: Exception) {
            }
        }
        pattern.release()
        log.i(TAG, "encoder liberado (${stats.encoded.get()} frames, ${stats.keyframes.get()} IDR)")
    }

    // ===================================================================== hilo de render

    private fun renderLoop(surface: Surface) {
        setPriority()
        val frameNanos = 1_000_000_000L / spec.fps
        val start = System.nanoTime()
        var next = start
        var frame = 0L
        var hardware = true
        renderMode = "GPU (lockHardwareCanvas)"
        while (rendering) {
            val canvas = try {
                if (hardware) surface.lockHardwareCanvas() else surface.lockCanvas(null)
            } catch (e: Exception) {
                if (!rendering) break
                if (hardware) {
                    log.w(TAG, "lockHardwareCanvas() falló; se prueba lockCanvas()", e)
                    hardware = false
                    renderMode = "CPU (lockCanvas)"
                    continue
                }
                reportFatal(IllegalStateException("no se puede dibujar en la Surface del encoder", e))
                break
            }
            val t0 = System.nanoTime()
            try {
                pattern.draw(canvas, frame, t0 - start)
            } catch (e: Exception) {
                if (stats.drawErrors.getAndIncrement() == 0L) log.e(TAG, "error dibujando el patrón", e)
            }
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                if (rendering) reportFatal(e)
                break
            }
            stats.onRendered(System.nanoTime() - t0)
            frame++
            next += frameNanos
            val now = System.nanoTime()
            if (now - next > 2 * frameNanos) {
                // Vamos tarde: se reprograma en vez de dibujar ráfagas.
                stats.renderLate.incrementAndGet()
                next = now
            }
            val wait = next - now
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    // ===================================================================== hilo de salida

    private fun drainLoop(codec: MediaCodec) {
        setPriority()
        val info = MediaCodec.BufferInfo()
        var configSent = false
        var csd: ByteArray? = null
        try {
            while (draining) {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    csd = csdOf(format)
                    log.i(TAG, "formato de salida del encoder: $format")
                    continue
                }
                if (index < 0) continue // INFO_TRY_AGAIN_LATER
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            configSent = true
                            stats.configs.incrementAndGet()
                            logConfig(buffer, "buffer CODEC_CONFIG")
                            sink.onCodecConfig(buffer)
                        } else {
                            val fromFormat = csd
                            if (!configSent && fromFormat != null) {
                                // Algunos encoders solo dan SPS/PPS en el formato de salida (csd-0/csd-1).
                                configSent = true
                                stats.configs.incrementAndGet()
                                val b = ByteBuffer.wrap(fromFormat)
                                logConfig(b, "csd-0/csd-1 del formato")
                                sink.onCodecConfig(b)
                            }
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val accepted = sink.onFrame(buffer, key, info.presentationTimeUs)
                            stats.onFrame(info.size, key, accepted)
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
                if (eos) break
            }
        } catch (e: Exception) {
            if (draining && running) reportFatal(e)
        }
    }

    private fun logConfig(buffer: ByteBuffer, origin: String) {
        val dup = buffer.duplicate()
        val bytes = ByteArray(dup.remaining()).also { dup.get(it) }
        val types = AnnexB.nalTypes(bytes).joinToString("+") { NalType.name(it) }
        val sps = AnnexB.nalUnits(bytes).firstOrNull { it.type == NalType.SPS && it.length >= 4 }
        val profile = sps?.let {
            val profileIdc = bytes[it.offset + 1].toInt() and 0xFF
            val constraints = bytes[it.offset + 2].toInt() and 0xFF
            val levelIdc = bytes[it.offset + 3].toInt() and 0xFF
            " profile_idc=$profileIdc constraints=0x${Integer.toHexString(constraints)} level_idc=$levelIdc"
        } ?: ""
        log.i(TAG, "SPS/PPS ($origin, ${bytes.size} B, $types$profile): ${Hex.encode(bytes)}")
    }

    private fun csdOf(format: MediaFormat): ByteArray? {
        val sps = format.getByteBuffer("csd-0") ?: return null
        val pps = format.getByteBuffer("csd-1")
        fun bytesOf(b: ByteBuffer): ByteArray {
            val d = b.duplicate()
            d.rewind()
            return ByteArray(d.remaining()).also { d.get(it) }
        }
        return bytesOf(sps) + (pps?.let(::bytesOf) ?: ByteArray(0))
    }

    private fun reportFatal(e: Throwable) {
        if (fatalReported.compareAndSet(false, true)) {
            log.e(TAG, "fallo del pipeline de vídeo", e)
            onFatal(this, e)
        }
    }

    private fun setPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "QD/Video"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
