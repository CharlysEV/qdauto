package dev.qdauto.app.video

import android.os.Build
import dev.qdauto.app.settings.VideoSettings
import dev.qdauto.app.touch.TouchTracker
import dev.qdauto.app.util.Clock
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionState
import dev.qdauto.core.session.VideoOverrides
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Lo que la UI muestra del vídeo. */
data class VideoInfo(
    val state: String,
    val codecName: String?,
    val hardware: Boolean?,
    val step: Int?,
    val size: IntSize?,
    val fps: Int?,
    val bitrate: Int?,
    val gopSeconds: Int?,
    val profile: String?,
    val level: String?,
    val bitrateMode: String?,
    val renderMode: String?,
    val notes: List<String>,
    val rendered: Long,
    val renderFps: Double,
    val renderLate: Long,
    val avgDrawMs: Double,
    val encoded: Long,
    val encodeFps: Double,
    val keyframes: Long,
    val configs: Long,
    val rejected: Long,
    val avgFrameBytes: Long,
    val maxFrameBytes: Long,
    val lastKeyframeBytes: Long,
    val keyframeRequests: Long,
    val failures: Int,
    val lastError: String?,
)

/**
 * Arranca, reinicia y para el [VideoPipeline] de la sesión. Todo el ciclo de vida corre en un hilo propio
 * (`qd-app-video`) para no bloquear nunca el hilo de eventos de la sesión mientras se crea o libera el encoder.
 */
class VideoController(private val log: QdLog, private val touches: TouchTracker) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "qd-app-video").apply { isDaemon = true }
    }

    @Volatile
    private var pipeline: VideoPipeline? = null

    // Se escriben solo desde el hilo qd-app-video.
    private var session: PhoneSession? = null
    private var settings: VideoSettings? = null

    @Volatile
    private var failures = 0

    /** Tamaño pedido antes de ajustarlo a las capacidades del encoder (para detectar cambios reales). */
    private var requestedSize: IntSize? = null

    @Volatile
    private var state = "parado"

    @Volatile
    private var lastInfo: VideoInfo? = null

    @Volatile
    private var lastError: String? = null

    /** `VIDEO_CTRL{PlayStatus:1}`: arranca el encoder (o pide un IDR si ya está en marcha). */
    fun onPlay(s: PhoneSession, v: VideoSettings) = post("play") {
        if (session !== s) {
            stopPipeline("sesión nueva")
            session = s
            failures = 0
            lastError = null
        }
        settings = v
        val p = pipeline
        if (p != null && p.isRunning) {
            p.requestKeyframe()
        } else {
            startPipeline()
        }
    }

    /** `VIDEO_CTRL` de pausa (con la opción activada): se para el encoder; con el siguiente play se crea otro. */
    fun onPause() = post("pausa") { stopPipeline("VIDEO_CTRL de pausa") }

    /** Llegó otro `CAR_INFO`/`VIDEO_ARGS`: si cambia el tamaño se recrea el encoder; si cambia el bitrate, en caliente. */
    fun onSessionInfoChanged(s: PhoneSession) = post("CAR_INFO/VIDEO_ARGS") {
        val p = pipeline ?: return@post
        val v = settings ?: return@post
        if (s !== session) return@post
        val wanted = EncoderSpec.resolve(v, s.encoderSuggestion, s.videoArgs)
        when {
            wanted.size != requestedSize -> restart("tamaño $requestedSize → ${wanted.size}")
            wanted.bitrate != p.spec.bitrate -> p.setBitrate(wanted.bitrate)
            else -> log.i(TAG, "CAR_INFO/VIDEO_ARGS sin cambios para el encoder")
        }
    }

    fun onSettingsChanged(v: VideoSettings) = post("ajustes") {
        settings = v
        val s = session
        if (pipeline != null) {
            restart("ajustes de vídeo cambiados")
        } else if (s != null && !s.isClosed && s.state == SessionState.STREAMING) {
            // El encoder había fallado del todo: con ajustes nuevos se vuelve a intentar desde cero.
            failures = 0
            startPipeline()
        }
    }

    /** Desde cualquier hilo. */
    fun requestKeyframe(): Boolean = pipeline?.requestKeyframe() ?: false

    /** Fin de la sesión [s]: se para su encoder (si el de otra sesión ya hubiera arrancado, no se toca). */
    fun onSessionClosed(s: PhoneSession) = post("sesión cerrada") {
        if (session !== s) return@post
        stopPipeline("sesión cerrada")
        session = null
    }

    fun shutdown() {
        post("apagado") {
            stopPipeline("servicio detenido")
            session = null
        }
        exec.shutdown()
    }

    fun info(): VideoInfo? {
        val p = pipeline ?: return lastInfo?.copy(state = state, failures = failures, lastError = lastError)
        return snapshot(p, state)
    }

    // ===================================================================== interno (hilo qd-app-video)

    private fun startPipeline() {
        if (pipeline != null) return // ya en marcha (p. ej. un VIDEO_CTRL{1} mientras esperaba un reintento)
        val s = session ?: return
        val v = settings ?: return
        if (s.isClosed) return
        if (s.state != SessionState.STREAMING && !s.config.sendVideoBeforePlay) {
            log.i(TAG, "no se arranca el vídeo: la sesión está en ${s.state}")
            return
        }
        state = "arrancando"
        val spec = EncoderSpec.resolve(v, s.encoderSuggestion, s.videoArgs)
        requestedSize = spec.size
        spec.notes.forEach { log.i(TAG, it) }
        val prepared = EncoderSetup.prepare(spec, log)
        val final = prepared.spec
        // La cabecera de vídeo lleva el mismo W×H que el SPS; appType/ángulo/orientación, de los ajustes.
        s.setVideoOverrides(
            VideoOverrides(
                width = final.width,
                height = final.height,
                appType = v.appType,
                angle = v.headerAngle,
                orientation = v.headerOrientation,
            ),
        )
        touches.setFrame(final.width, final.height)
        val car = s.carInfo
        val pattern = TestPattern(
            final.width, final.height,
            car?.let { IntSize(it.carWidth, it.carHeight) },
            touches,
        ) { liveLine(s) }
        val p = VideoPipeline(final, prepared, SessionSink(s), pattern, log) { failed, error ->
            post("fallo del encoder") { onFailure(failed, error) }
        }
        try {
            p.start(firstStep = failures.coerceAtMost(EncoderSetup.STEPS - 1))
        } catch (e: Exception) {
            pattern.release()
            log.e(TAG, "no se pudo arrancar el encoder", e)
            lastError = "${Clock.now()} $e"
            lastInfo = emptyInfo(final, "error")
            retryLater()
            return
        }
        pattern.infoLines = infoLines(s, p)
        pipeline = p
        state = "en marcha"
    }

    private fun restart(reason: String) {
        log.i(TAG, "reinicio del encoder: $reason")
        stopPipeline(reason)
        startPipeline()
    }

    private fun stopPipeline(reason: String) {
        val p = pipeline ?: return
        pipeline = null
        lastInfo = snapshot(p, "parado")
        p.stop()
        state = "parado ($reason)"
        log.i(TAG, "vídeo parado: $reason")
    }

    private fun onFailure(p: VideoPipeline, error: Throwable) {
        if (pipeline !== p) return
        lastError = "${Clock.now()} $error"
        stopPipeline("fallo: $error")
        retryLater()
    }

    private fun retryLater() {
        failures++
        val s = session
        if (failures > MAX_FAILURES || s == null || s.isClosed) {
            state = "error (sin más reintentos)"
            log.e(TAG, "el vídeo falló $failures veces; no se reintenta")
            return
        }
        state = "reintentando ($failures/$MAX_FAILURES)"
        log.w(TAG, "se reintenta el encoder en 1 s con la configuración ${failures.coerceAtMost(EncoderSetup.STEPS - 1)}")
        try {
            exec.schedule({ guarded("reintento") { startPipeline() } }, 1, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun infoLines(s: PhoneSession, p: VideoPipeline): List<String> {
        val c = p.created
        val spec = p.spec
        val vp = s.videoParams
        val car = s.carInfo
        val args = s.videoArgs
        return listOf(
            "QDAuto · fase 0 · patrón de prueba",
            "Frame ${spec.size} · ${spec.fps} fps · ${spec.bitrate / 1000} kbps · GOP ${spec.gopSeconds} s",
            "${c?.profileText} ${c?.levelText} · ${c?.modeText} · ${c?.codecName}",
            if (car == null) "CAR_INFO: —" else "CAR_INFO ${car.carWidth}×${car.carHeight} · ${car.carType} · ${car.carFactory}/${car.huFactory}",
            if (args == null) "VIDEO_ARGS: —" else
                "VIDEO_ARGS ${args.width}×${args.height} enc ${args.encodingType} ${args.frameRate} fps ${args.bitRate} bps GOP ${args.frameInterval}",
            "Cabecera ${vp.width}×${vp.height} app ${vp.appType} ang ${vp.angle} or ${vp.orientation} · ${Build.MODEL}",
        )
    }

    /** Dos líneas cortas (caben en el frame aunque sea de 800 px de ancho). */
    private fun liveLine(s: PhoneSession): String {
        val st = s.stats()
        return String.format(
            Locale.ROOT,
            "Sesión %s · último dato del coche hace %d ms\nenviados %d · %.1f fps · %.0f kbps · cola %d · descartados %d · IDR %d",
            s.state, st.lastReceiveAgoMs, st.videoFramesSent, st.fps, st.kbps, st.videoQueueFrames, st.videoFramesDropped,
            st.keyframesSent,
        )
    }

    private fun snapshot(p: VideoPipeline, state: String): VideoInfo {
        val c = p.created
        val st = p.stats
        return VideoInfo(
            state = state,
            codecName = c?.codecName,
            hardware = c?.hardware,
            step = c?.step,
            size = p.spec.size,
            fps = p.spec.fps,
            bitrate = p.spec.bitrate,
            gopSeconds = p.spec.gopSeconds,
            profile = c?.profileText,
            level = c?.levelText,
            bitrateMode = c?.modeText,
            renderMode = p.renderMode,
            notes = p.spec.notes + (c?.notes ?: emptyList()),
            rendered = st.rendered.get(),
            renderFps = st.renderRate.perSecond(),
            renderLate = st.renderLate.get(),
            avgDrawMs = st.averageDrawMs(),
            encoded = st.encoded.get(),
            encodeFps = st.encodeRate.perSecond(),
            keyframes = st.keyframes.get(),
            configs = st.configs.get(),
            rejected = st.rejected.get(),
            avgFrameBytes = st.averageFrameBytes(),
            maxFrameBytes = st.maxFrameBytes.get(),
            lastKeyframeBytes = st.lastKeyframeBytes.get(),
            keyframeRequests = st.keyframeRequests.get(),
            failures = failures,
            lastError = lastError,
        )
    }

    private fun emptyInfo(spec: EncoderSpec, state: String) = VideoInfo(
        state = state, codecName = null, hardware = null, step = null, size = spec.size, fps = spec.fps,
        bitrate = spec.bitrate, gopSeconds = spec.gopSeconds, profile = null, level = null, bitrateMode = null,
        renderMode = null, notes = spec.notes, rendered = 0, renderFps = 0.0, renderLate = 0, avgDrawMs = 0.0,
        encoded = 0, encodeFps = 0.0, keyframes = 0, configs = 0, rejected = 0, avgFrameBytes = 0, maxFrameBytes = 0,
        lastKeyframeBytes = 0, keyframeRequests = 0, failures = failures, lastError = lastError,
    )

    private fun post(what: String, block: () -> Unit) {
        try {
            exec.execute { guarded(what, block) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.e(TAG, "error en '$what'", t)
            lastError = "${Clock.now()} $what: $t"
        }
    }

    /** Lleva la salida del encoder a la sesión. */
    private class SessionSink(private val s: PhoneSession) : VideoPipeline.FrameSink {
        override fun onCodecConfig(buffer: ByteBuffer): Boolean = s.sendCodecConfig(buffer)
        override fun onFrame(buffer: ByteBuffer, keyframe: Boolean, ptsUs: Long): Boolean = s.sendFrame(buffer, keyframe, ptsUs)
    }

    private companion object {
        const val TAG = "QD/VideoCtl"
        const val MAX_FAILURES = 3
    }
}
