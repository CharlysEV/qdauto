package dev.qdauto.core.session

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class OutKind { CONTROL, VIDEO_CONFIG, VIDEO_KEY, VIDEO_DELTA }

/** Un mensaje completo listo para un único `write()`. */
internal class Outgoing(
    val bytes: ByteArray,
    val kind: OutKind,
    /** `CMD`, `AppID/FunctionID`, `!BIN …` o `VIDEO_*`, para la traza. */
    val label: String,
    val msgType: Int?,
    /** JSON del mensaje (solo control), para la traza. */
    val text: String? = null,
    /** Cerrar la sesión justo después de escribirlo. */
    val closeAfter: Boolean = false,
    val ptsUs: Long = -1,
    val enqueuedNanos: Long = System.nanoTime(),
) {
    val isVideo: Boolean get() = kind != OutKind.CONTROL
    val isFrame: Boolean get() = kind == OutKind.VIDEO_KEY || kind == OutKind.VIDEO_DELTA
}

internal enum class FrameOffer { ACCEPTED, DROPPED_WAITING_IDR, DROPPED_BACKLOG, CLOSED }

/**
 * Colas de salida de la sesión: control con prioridad absoluta sobre vídeo, un solo consumidor (el hilo escritor) y
 * productores que nunca se bloquean. Política de vídeo:
 * - SPS/PPS (config) e IDR nunca se descartan;
 * - si al llegar un frame hay ≥ [backlogFrames] frames o ≥ [backlogBytes] bytes de vídeo en cola, se descartan los
 *   P-frames encolados; si el que llega es un P-frame también se descarta y no se acepta ninguno más hasta el
 *   siguiente IDR (hay que pedirlo al encoder); si es un IDR, se encola (deja obsoletos los P anteriores);
 * - antes del primer frame aceptado tras [requestConfigResend] o [startStream] se reenvía SPS/PPS.
 */
internal class SendQueue(
    private val backlogFrames: Int,
    private val backlogBytes: Long,
    private val hardLimitBytes: Long,
    private val resendConfigAfterDrop: Boolean,
    private val controlCapacity: Int = 1_000,
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val control = ArrayDeque<Outgoing>()
    private val video = ArrayDeque<Outgoing>()
    private var videoBytes = 0L
    private var videoFrames = 0
    private var closed = false

    private var waitingForIdr = false
    private var pendingConfig = false

    var droppedFrames = 0L
        private set

    /** `false` si está cerrada o llena (solo pasa si el escritor lleva mucho bloqueado). */
    fun offerControl(item: Outgoing): Boolean = lock.withLock {
        if (closed || control.size >= controlCapacity) return false
        control.addLast(item)
        notEmpty.signal()
        true
    }

    /** SPS/PPS: siempre se encola. */
    fun offerConfig(item: Outgoing): Boolean = lock.withLock {
        if (closed) return false
        pendingConfig = false
        addVideo(item)
        notEmpty.signal()
        true
    }

    /**
     * Encola un frame aplicando la política de descarte. [configFactory] construye el mensaje SPS/PPS a reenviar
     * delante (o `null` si aún no hay ninguno).
     */
    fun offerFrame(item: Outgoing, configFactory: () -> Outgoing?): FrameOffer = lock.withLock {
        if (closed) return FrameOffer.CLOSED
        val isKey = item.kind == OutKind.VIDEO_KEY
        if (waitingForIdr && !isKey) {
            droppedFrames++
            return FrameOffer.DROPPED_WAITING_IDR
        }
        if (videoFrames >= backlogFrames || videoBytes >= backlogBytes) {
            droppedFrames += dropQueuedDeltas()
            if (!isKey) {
                droppedFrames++
                waitingForIdr = true
                if (resendConfigAfterDrop) pendingConfig = true
                return FrameOffer.DROPPED_BACKLOG
            }
        }
        if (isKey) waitingForIdr = false
        if (pendingConfig) {
            configFactory()?.let {
                addVideo(it)
                pendingConfig = false
            }
        }
        addVideo(item)
        if (videoBytes > hardLimitBytes) droppedFrames += dropSupersededFrames()
        notEmpty.signal()
        FrameOffer.ACCEPTED
    }

    /** `KEY_FRAME_REQ`: SPS/PPS delante del siguiente frame aceptado. */
    fun requestConfigResend() = lock.withLock { pendingConfig = true }

    /** Empieza (o se reanuda) el vídeo: se arranca en un IDR precedido de SPS/PPS. */
    fun startStream() = lock.withLock {
        waitingForIdr = true
        pendingConfig = true
    }

    val isWaitingForIdr: Boolean get() = lock.withLock { waitingForIdr }

    /** Siguiente mensaje (control primero). Bloquea; `null` cuando la cola se cierra. */
    fun take(): Outgoing? = lock.withLock {
        while (!closed && control.isEmpty() && video.isEmpty()) {
            try {
                notEmpty.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (closed) return null
        control.removeFirstOrNull() ?: video.removeFirst().also {
            videoBytes -= it.bytes.size
            if (it.isFrame) videoFrames--
        }
    }

    fun close() = lock.withLock {
        closed = true
        control.clear()
        video.clear()
        videoBytes = 0
        videoFrames = 0
        notEmpty.signalAll()
    }

    fun controlDepth(): Int = lock.withLock { control.size }
    fun videoFrameDepth(): Int = lock.withLock { videoFrames }
    fun videoByteDepth(): Long = lock.withLock { videoBytes }

    private fun addVideo(item: Outgoing) {
        video.addLast(item)
        videoBytes += item.bytes.size
        if (item.isFrame) videoFrames++
    }

    private fun dropQueuedDeltas(): Int {
        var n = 0
        val it = video.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v.kind == OutKind.VIDEO_DELTA) {
                it.remove()
                videoBytes -= v.bytes.size
                videoFrames--
                n++
            }
        }
        return n
    }

    /** Válvula de memoria: quita los frames (también IDR) anteriores al último IDR de la cola. */
    private fun dropSupersededFrames(): Int {
        val lastKey = video.indexOfLast { it.kind == OutKind.VIDEO_KEY }
        if (lastKey <= 0) return 0
        var n = 0
        var i = 0
        val it = video.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (i >= lastKey) break
            if (v.isFrame) {
                it.remove()
                videoBytes -= v.bytes.size
                videoFrames--
                n++
            }
            i++
        }
        return n
    }
}
