package dev.qdauto.carsim.run

import dev.qdauto.carsim.Console
import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.RunClock
import dev.qdauto.core.sim.CarSimListener
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.ReceiverHang
import dev.qdauto.core.sim.VideoFrameInfo
import dev.qdauto.core.sim.VideoKind
import dev.qdauto.core.util.BE
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BroadcastAck
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.TraceEvent
import java.net.InetSocketAddress

/** Un hito de la prueba (la primera vez que pasa cada cosa). */
data class TimelineEvent(val tMs: Long, val text: String)

/** Mensaje de control (msgType 0) o de apps (msgType 13) del teléfono, con su JSON tal cual llegó. */
data class PhoneMessage(val tMs: Long, val msgType: Int, val kind: String, val json: String)

/** AppStatus `!BIN` recibido y diferencias con el de QDLink. */
data class AppStatusSeen(val tMs: Long, val sdkInt: Int, val errors: List<String>)

/** Foto inmutable de todo lo registrado. */
data class RecorderSnapshot(
    val timeline: List<TimelineEvent>,
    val phoneMessages: List<PhoneMessage>,
    val heartbeatTimesMs: List<Long>,
    val appStatuses: List<AppStatusSeen>,
    /** Primera vez que se mandó cada CMD del coche. */
    val firstOut: Map<String, Long>,
    /** Primera vez que llegó cada tipo de mensaje del teléfono. */
    val firstIn: Map<String, Long>,
    val ackAtMs: Long?,
    val connectedAtMs: Long?,
    val streamingAtMs: Long?,
    val firstVideoAtMs: Long?,
    val closedAtMs: Long?,
    val closeReason: String?,
    /** Cuándo se colgó el coche simulado por un mensaje de vídeo demasiado grande. */
    val hangAtMs: Long?,
    val spsSegments: List<SpsSegment>,
    val headersBeforeFirstSps: Map<Dims, Int>,
)

/** Estado del vídeo para la línea de progreso. */
data class LiveVideo(val fps: Double, val kbps: Double, val frames: Int, val idr: Int)

/**
 * Escucha al [dev.qdauto.core.sim.CarSim] (desde sus hilos internos) y lo apunta todo: hitos con hora, mensajes
 * del teléfono, heartbeats, AppStatus, estadísticas de vídeo y dónde están los SPS en la grabación. Además cuenta
 * en directo lo importante. Todos los métodos son thread-safe y rápidos (la consola es asíncrona).
 */
class RunRecorder(
    private val clock: RunClock,
    private val verbose: Boolean,
    nominalFps: Int,
) : CarSimListener {
    private val lock = Any()
    private val timeline = ArrayList<TimelineEvent>()
    private val timelineKeys = HashSet<String>()
    private val phoneMessages = ArrayList<PhoneMessage>()
    private val printedMessages = HashSet<String>()
    private val heartbeats = ArrayList<Long>()
    private val appStatuses = ArrayList<AppStatusSeen>()
    private val firstOut = LinkedHashMap<String, Long>()
    private val firstIn = LinkedHashMap<String, Long>()
    private val video = VideoStats(nominalFps)
    private val sps = SpsTracker()

    @Volatile
    var ackAtMs: Long? = null
        private set

    @Volatile
    var connectedAtMs: Long? = null
        private set

    @Volatile
    var streamingAtMs: Long? = null
        private set

    @Volatile
    var firstVideoAtMs: Long? = null
        private set

    @Volatile
    private var closedAtMs: Long? = null

    @Volatile
    private var closeReason: String? = null

    @Volatile
    private var hangAtMs: Long? = null

    /** Apunta un hito suelto (inicio, fin...). */
    fun note(text: String, print: Boolean = false) {
        val t = clock.elapsedMs()
        synchronized(lock) { timeline += TimelineEvent(t, text) }
        if (print) say(t, text)
    }

    // ===================================================================== CarSimListener

    override fun onStateChanged(state: CarSimState) {
        val t = clock.elapsedMs()
        when (state) {
            CarSimState.HANDSHAKE -> {
                connectedAtMs = t
                first(t, "tcp", "TCP conectado: empieza el handshake", print = true)
            }
            CarSimState.STREAMING -> {
                streamingAtMs = t
                first(t, "streaming", "VIDEO_CTRL{PlayStatus:1} enviado: el coche espera vídeo", print = true)
            }
            else -> Unit
        }
    }

    override fun onAck(ack: BroadcastAck, from: InetSocketAddress) {
        val t = clock.elapsedMs()
        ackAtMs = t
        first(t, "ack", "Broadcast_ACK de ${from.address.hostAddress}:${from.port} con MirrorPort ${ack.mirrorPort}", print = true)
    }

    override fun onAppStatus(block: BinBlock, errors: List<String>) {
        val t = clock.elapsedMs()
        val sdk = BE.getInt(block.bytes, 76)
        synchronized(lock) {
            appStatuses += AppStatusSeen(t, sdk, errors)
            firstIn.putIfAbsent(APP_STATUS, t)
        }
        val verdict = if (errors.isEmpty()) "idéntico al de QDLink" else "${errors.size} diferencias con QDLink"
        first(t, "in:$APP_STATUS", "<- !BIN AppStatus (512 B, SDK $sdk): $verdict", print = !verbose)
    }

    override fun onPhoneControl(message: ControlMessage) = onPhoneJson(MsgType.CONTROL, message.cmd.ifEmpty { "CONTROL?" }, message.text)

    override fun onPhoneApp(message: AppMessage) = onPhoneJson(MsgType.APP, message.key, message.text)

    override fun onVideoFrame(info: VideoFrameInfo) {
        val ns = System.nanoTime()
        val t = clock.elapsedMs(ns)
        synchronized(lock) {
            video.onMessage(ns, info)
            sps.onMessage(t, info)
        }
        if (firstVideoAtMs == null) firstVideoAtMs = t
        val size = info.header?.params?.let { " ${Fmt.size(it.width, it.height)}" } ?: ""
        when (info.kind) {
            VideoKind.CONFIG -> first(t, "video:config", "<- primer SPS/PPS (${info.payloadSize} B$size)", print = !verbose)
            VideoKind.IDR -> first(t, "video:idr", "<- primer IDR (${info.payloadSize} B$size)", print = !verbose)
            VideoKind.P -> first(t, "video:p", "<- primer P-frame (${info.payloadSize} B)", print = !verbose)
            VideoKind.OTHER -> first(t, "video:other", "<- primer mensaje de vídeo sin SPS/PPS ni slices", print = !verbose)
        }
    }

    override fun onReceiverHang(hang: ReceiverHang) {
        val t = clock.elapsedMs()
        if (hangAtMs == null) hangAtMs = t
        first(t, "hang", "!! el coche se cuelga como el C10: ${hang.describe()}; deja de leer ${hang.hangMs} ms y cierra", print = true)
    }

    override fun onTrace(event: TraceEvent) {
        val t = clock.elapsedMs()
        if (verbose) say(t, event.toString())
        if (event.direction == Direction.OUT && event.kind in HANDSHAKE_OUT) {
            val isFirst = synchronized(lock) { firstOut.putIfAbsent(event.kind, t) == null }
            if (isFirst) first(t, "out:${event.kind}", "-> ${event.kind} ${event.summary}", print = !verbose)
        }
    }

    override fun onClosed(reason: String) {
        val t = clock.elapsedMs()
        closedAtMs = t
        closeReason = reason
        note(if (connectedAtMs != null) "conexión cerrada: $reason" else "simulador parado: $reason", print = true)
    }

    // ===================================================================== consultas

    fun liveVideo(): LiveVideo = synchronized(lock) {
        val (fps, kbps) = video.liveRates(System.nanoTime())
        LiveVideo(fps, kbps, video.frameCount, video.idrCount)
    }

    fun phoneMessageCount(): Int = synchronized(lock) { phoneMessages.size }

    fun firstSpsSegment(): SpsSegment? = synchronized(lock) { sps.first() }

    fun videoSummary(endNs: Long): VideoSummary = synchronized(lock) { video.summary(clock.startNanos, endNs) }

    fun snapshot(): RecorderSnapshot = synchronized(lock) {
        RecorderSnapshot(
            timeline = timeline.sortedBy { it.tMs },
            phoneMessages = phoneMessages.toList(),
            heartbeatTimesMs = heartbeats.toList(),
            appStatuses = appStatuses.toList(),
            firstOut = LinkedHashMap(firstOut),
            firstIn = LinkedHashMap(firstIn),
            ackAtMs = ackAtMs,
            connectedAtMs = connectedAtMs,
            streamingAtMs = streamingAtMs,
            firstVideoAtMs = firstVideoAtMs,
            closedAtMs = closedAtMs,
            closeReason = closeReason,
            hangAtMs = hangAtMs,
            spsSegments = sps.segments(),
            headersBeforeFirstSps = sps.headersBeforeFirstSps(),
        )
    }

    // ===================================================================== privado

    private fun onPhoneJson(msgType: Int, kind: String, json: String) {
        val t = clock.elapsedMs()
        val newText = synchronized(lock) {
            if (phoneMessages.size < MAX_MESSAGES) phoneMessages += PhoneMessage(t, msgType, kind, json)
            if (kind == Cmd.HEARTBEAT) heartbeats += t
            firstIn.putIfAbsent(kind, t)
            printedMessages.size < MAX_PRINTED && printedMessages.add("$kind|$json")
        }
        first(t, "in:$kind", "<- $kind", print = false)
        // En directo: cada JSON distinto una vez (los heartbeats y WhitelistAppOn se repiten sin cambios).
        if (!verbose && newText) say(t, "<- $kind ${Fmt.clip(json, 300)}")
    }

    private fun first(t: Long, key: String, text: String, print: Boolean) {
        val isNew = synchronized(lock) {
            timelineKeys.add(key).also { if (it) timeline += TimelineEvent(t, text) }
        }
        if (isNew && print) say(t, text)
    }

    private fun say(t: Long, text: String) = Console.line("${Fmt.stamp(t)}  $text")

    companion object {
        const val APP_STATUS = "!BIN AppStatus"

        /** Mensajes del coche que forman el handshake (spec §7.1) o lo alteran. */
        val HANDSHAKE_OUT = setOf(Cmd.CAR_INFO, Cmd.VIDEO_SUP_REQ, Cmd.VIDEO_ARGS, Cmd.VIDEO_CTRL, Cmd.KEY_FRAME_REQ, Cmd.DISCONNECT_REQ)
        private const val MAX_MESSAGES = 50_000
        private const val MAX_PRINTED = 200
    }
}
