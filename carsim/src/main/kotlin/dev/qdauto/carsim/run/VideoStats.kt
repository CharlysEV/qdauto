package dev.qdauto.carsim.run

import dev.qdauto.core.sim.VideoFrameInfo
import dev.qdauto.core.sim.VideoKind
import dev.qdauto.core.wire.VideoParams

/** Estadísticas del vídeo recibido, calculadas al final. Tiempos en ms desde el arranque de la prueba. */
data class VideoSummary(
    val messages: Int,
    val configs: Int,
    val idr: Int,
    val p: Int,
    val other: Int,
    val payloadBytes: Long,
    /** IDR + P + otros (sin los SPS/PPS sueltos). */
    val frames: Int,
    val firstFrameMs: Long?,
    val lastFrameMs: Long?,
    val fpsAvg: Double?,
    /** Mínimo y máximo de frames en un segundo completo, contando desde el primer frame. */
    val fpsMin: Int?,
    val fpsMax: Int?,
    val fullSeconds: Int,
    val kbpsAvg: Double?,
    val kbpsMax: Double?,
    val maxGapMs: Double?,
    val maxGapAtMs: Long?,
    val gapThresholdMs: Double,
    val gapsOverThreshold: Int,
    val idrIntervalsMs: List<Long>,
    val idrIntervalsFrames: List<Int>,
    /** Desde el último IDR hasta el último frame. */
    val lastIdrToLastFrameMs: Long?,
    /** Cabeceras de 32 bytes distintas y en cuántos mensajes salió cada una. */
    val headers: Map<VideoParams, Int>,
) {
    val spanMs: Long? get() = if (firstFrameMs != null && lastFrameMs != null) lastFrameMs - firstFrameMs else null
}

/** Cuenta frames y tiempos de llegada sobre la marcha. No es thread-safe: lo protege [RunRecorder]. */
class VideoStats(nominalFps: Int) {
    private val gapThresholdNs = (maxOf(100.0, 3_000.0 / nominalFps.coerceAtLeast(1)) * 1_000_000).toLong()
    private var messages = 0
    private var configs = 0
    private var idr = 0
    private var p = 0
    private var other = 0
    private var payloadBytes = 0L
    private var frames = 0
    private var firstFrameNs = 0L
    private var lastFrameNs = 0L
    private var maxGapNs = 0L
    private var maxGapAtNs = 0L
    private var gapsOver = 0
    private val secondCounts = ArrayList<Int>()
    private val secondBytes = ArrayList<Long>()
    private val idrTimesNs = ArrayList<Long>()
    private val idrFrameIndex = ArrayList<Int>()
    private val headers = LinkedHashMap<VideoParams, Int>()
    private val window = ArrayDeque<LongArray>() // [ns, bytes, 1 si es frame]

    val messageCount: Int get() = messages
    val idrCount: Int get() = idr
    val frameCount: Int get() = frames

    fun onMessage(ns: Long, info: VideoFrameInfo) {
        messages++
        payloadBytes += info.payloadSize
        info.header?.let { headers.merge(it.params, 1, Int::plus) }
        val isFrame = info.kind != VideoKind.CONFIG
        window.addLast(longArrayOf(ns, info.payloadSize.toLong(), if (isFrame) 1 else 0))
        trim(ns)
        when (info.kind) {
            VideoKind.CONFIG -> configs++
            VideoKind.IDR -> idr++
            VideoKind.P -> p++
            VideoKind.OTHER -> other++
        }
        if (!isFrame) return
        if (frames == 0) {
            firstFrameNs = ns
        } else {
            val gap = ns - lastFrameNs
            if (gap > maxGapNs) {
                maxGapNs = gap
                maxGapAtNs = ns
            }
            if (gap > gapThresholdNs) gapsOver++
        }
        lastFrameNs = ns
        val second = ((ns - firstFrameNs) / 1_000_000_000L).toInt()
        while (secondCounts.size <= second) {
            secondCounts += 0
            secondBytes += 0L
        }
        secondCounts[second] = secondCounts[second] + 1
        secondBytes[second] = secondBytes[second] + info.payloadSize
        if (info.kind == VideoKind.IDR) {
            idrTimesNs += ns
            idrFrameIndex += frames
        }
        frames++
    }

    /** (frames/s, kbit/s) del último segundo, para la línea de estado. */
    fun liveRates(nowNs: Long): Pair<Double, Double> {
        trim(nowNs)
        return window.count { it[2] == 1L }.toDouble() to window.sumOf { it[1] } * 8 / 1000.0
    }

    /** [endNs]: fin de la observación (cuando se paró la prueba o se cerró la conexión). */
    fun summary(clockStartNs: Long, endNs: Long): VideoSummary {
        fun ms(ns: Long) = (ns - clockStartNs) / 1_000_000
        val span = if (frames > 1) (lastFrameNs - firstFrameNs) / 1e9 else 0.0
        val fullSeconds = if (frames > 0) ((endNs - firstFrameNs) / 1_000_000_000L).toInt().coerceAtLeast(0) else 0
        // Los segundos sin ningún frame (vídeo parado) cuentan como 0.
        val counts = (0 until fullSeconds).map { secondCounts.getOrElse(it) { 0 } }
        val bytes = (0 until fullSeconds).map { secondBytes.getOrElse(it) { 0L } }
        return VideoSummary(
            messages = messages,
            configs = configs,
            idr = idr,
            p = p,
            other = other,
            payloadBytes = payloadBytes,
            frames = frames,
            firstFrameMs = if (frames > 0) ms(firstFrameNs) else null,
            lastFrameMs = if (frames > 0) ms(lastFrameNs) else null,
            fpsAvg = if (span > 0) (frames - 1) / span else null,
            fpsMin = counts.minOrNull(),
            fpsMax = counts.maxOrNull(),
            fullSeconds = fullSeconds,
            // Cada frame "dura" 1/fps: bits por frame x fps (sin el sesgo de dividir por el hueco entre el primero y el último).
            kbpsAvg = if (span > 0) payloadBytes * 8 / 1000.0 / frames * ((frames - 1) / span) else null,
            kbpsMax = bytes.maxOrNull()?.let { it * 8 / 1000.0 },
            maxGapMs = if (frames > 1) maxGapNs / 1e6 else null,
            maxGapAtMs = if (frames > 1) ms(maxGapAtNs) else null,
            gapThresholdMs = gapThresholdNs / 1e6,
            gapsOverThreshold = gapsOver,
            idrIntervalsMs = idrTimesNs.zipWithNext { a, b -> (b - a) / 1_000_000 },
            idrIntervalsFrames = idrFrameIndex.zipWithNext { a, b -> b - a },
            lastIdrToLastFrameMs = idrTimesNs.lastOrNull()?.let { (lastFrameNs - it) / 1_000_000 },
            headers = LinkedHashMap(headers),
        )
    }

    private fun trim(nowNs: Long) {
        while (window.isNotEmpty() && nowNs - window.first()[0] > 1_000_000_000L) window.removeFirst()
    }
}
