package dev.qdauto.app.link

import dev.qdauto.app.log.AppLog
import dev.qdauto.app.util.Clock
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.KeyframeReason
import dev.qdauto.core.session.SessionListener
import dev.qdauto.core.session.SessionState
import dev.qdauto.core.util.Hex
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BinaryMessage
import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.UnknownMessage
import dev.qdauto.core.wire.VideoArgs

/**
 * Callbacks de cada sesión (hilo `qd-sN-events`): registra todo en el modelo y el log, pasa el táctil al
 * [dev.qdauto.app.touch.TouchTracker] y arranca/para el vídeo. Nunca lanza: una excepción aquí no debe afectar a la
 * sesión.
 */
internal class SessionEvents(private val engine: LinkEngine) : SessionListener {
    private val model get() = engine.model

    override fun onStateChanged(from: SessionState, to: SessionState) = guard("estado") {
        model.note("Sesión: $from → $to")
        if (to == SessionState.STREAMING) model.sessionStreamed = true
        if (to == SessionState.PAUSED) engine.video.onPause()
    }

    override fun onCarInfo(info: CarInfo, message: ControlMessage) = guard("CAR_INFO") {
        model.carInfoRaw = message.text
        engine.touches.setCar(info.carWidth, info.carHeight)
        model.note("CAR_INFO ${info.carWidth}×${info.carHeight} CarType=${info.carType} MirrorTypeReq=${info.mirrorTypeReq}")
        engine.onCarInfoReceived()
        engine.activeSession()?.let { engine.video.onSessionInfoChanged(it) }
    }

    override fun onVideoSupportRequest(videoFormat: Int, message: ControlMessage) = guard("VIDEO_SUP_REQ") {
        model.note("VIDEO_SUP_REQ VideoFormat=$videoFormat")
    }

    override fun onVideoArgs(args: VideoArgs, message: ControlMessage) = guard("VIDEO_ARGS") {
        model.videoArgsRaw = message.text
        model.note("VIDEO_ARGS ${args.width}×${args.height} ${args.frameRate} fps ${args.bitRate} bps GOP ${args.frameInterval}")
        val s = engine.activeSession() ?: return@guard
        if (s.config.sendVideoBeforePlay) engine.video.onPlay(s, engine.settings.video) else engine.video.onSessionInfoChanged(s)
    }

    override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) = guard("VIDEO_CTRL") {
        model.note("VIDEO_CTRL PlayStatus=$playStatus")
        val s = engine.activeSession() ?: return@guard
        if (play) {
            engine.video.onPlay(s, engine.settings.video)
        } else if (s.state == SessionState.PAUSED) {
            engine.video.onPause()
        }
    }

    override fun onKeyframeRequested(reason: KeyframeReason) = guard("IDR") {
        val sent = engine.video.requestKeyframe()
        if (reason != KeyframeReason.STREAM_START) model.note("Petición de IDR ($reason)" + if (sent) "" else " sin encoder")
    }

    override fun onTouch(event: TouchEvent) = guard("táctil") { engine.touches.onEvent(event) }

    override fun onKey(key: CarKey) = guard("tecla") {
        model.lastKey = "${Clock.now()} $key"
        model.note("Tecla del coche: $key")
    }

    override fun onPhoneKey(code: Int, message: ControlMessage) = guard("PHONE_KEYS") {
        if (code !in 1..3) model.lastKey = "${Clock.now()} PHONE_KEYS desconocido $code"
    }

    override fun onLandModeRequest(orientation: Int, message: ControlMessage) = guard("LAND_MODE_REQ") {
        model.note("LAND_MODE_REQ Orientation=$orientation")
    }

    override fun onBtAddr(request: BtAddrRequest, message: ControlMessage) = guard("BT_ADDR") {
        val reply = engine.settings.session.btReply
        model.note("BT_ADDR ${request.address} estado=${request.status} auto=${request.needAutoConnect} → ${reply.label}")
    }

    override fun onDisconnectRequest(message: ControlMessage) = guard("DISCONNECT_REQ") { model.note("DISCONNECT_REQ del coche") }

    override fun onGoInLinkApp(message: ControlMessage) = guard("GO_IN_LINK_APP") { model.note("GO_IN_LINK_APP del coche") }

    override fun onAppMessage(message: AppMessage) = guard("msgType 13") {
        model.lastApp = "${Clock.now()} ${message.key} ${message.text}"
    }

    override fun onBinaryMessage(message: BinaryMessage) = guard("binario") {
        model.note("msgType ${message.header.msgType}: ${message.payload.size} B")
    }

    override fun onLegacyMessage(block: BinBlock) = guard("!BIN") { model.note(block.describe()) }

    override fun onUnknownMessage(message: UnknownMessage) = guard("desconocido") {
        model.lastUnknown = "${Clock.now()} ${message.reason} (${message.totalBytes} B) ${Hex.prefix(message.bytes, maxBytes = 24)}"
        model.note("Mensaje no reconocido: ${message.reason}")
    }

    override fun onWatchdogWarning(silentMs: Long) = guard("watchdog") {
        model.note("El coche lleva $silentMs ms sin enviar nada")
    }

    override fun onTrace(event: TraceEvent) = guard("traza") {
        AppLog.trace(event, (engine.activeSession() ?: model.session)?.id)
    }

    /** El encoder se para en `PhoneLinkListener.onSessionEnded`, que llega justo después con la sesión. */
    override fun onClosed(reason: CloseReason) = guard("cierre") {
        model.lastClose = "${Clock.now()} $reason"
        model.note("Sesión cerrada: $reason")
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            AppLog.e(TAG, "error procesando '$what'", t)
        }
    }

    private companion object {
        const val TAG = "QD/Events"
    }
}
