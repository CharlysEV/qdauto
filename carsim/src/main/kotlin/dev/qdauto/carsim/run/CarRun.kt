package dev.qdauto.carsim.run

import dev.qdauto.carsim.Console
import dev.qdauto.carsim.ConsoleLog
import dev.qdauto.carsim.ExitCodes
import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.RunClock
import dev.qdauto.carsim.SetupException
import dev.qdauto.carsim.cli.Options
import dev.qdauto.carsim.decode.DecodeOutcome
import dev.qdauto.carsim.decode.Ffmpeg
import dev.qdauto.carsim.net.BroadcastFanout
import dev.qdauto.carsim.report.CheckStatus
import dev.qdauto.carsim.report.Checks
import dev.qdauto.carsim.report.JsonReport
import dev.qdauto.carsim.report.TextReport
import dev.qdauto.carsim.touch.ScriptRunner
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.VideoArgsValues
import dev.qdauto.core.wire.UdpCodec
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Una prueba completa con el coche simulado: descubrimiento, handshake, vídeo durante la duración pedida, guion
 * táctil, informe y comprobaciones. Ctrl+C para la prueba y saca el informe igualmente.
 */
class CarRun(
    private val options: Options,
    private val settings: RunSettings,
    private val clock: RunClock = RunClock(),
    private val extension: RunExtension? = null,
) {
    private val log = ConsoleLog(clock, options.verbose)
    private val recorder = RunRecorder(clock, options.verbose, options.fps)
    private val stopSignal = CountDownLatch(1)
    private val finished = AtomicBoolean(false)
    private val reportDone = CountDownLatch(1)

    @Volatile
    private var exitCode = ExitCodes.CHECKS_FAILED

    @Volatile
    private var scriptRunner: ScriptRunner? = null

    @Volatile
    private var stopNanos = 0L

    fun run(): RunResult {
        val recording = Recording.prepare(options.out)
        options.report?.let(::ensureParentDir)
        if (settings.checkPortFree) ensureUdpPortFree(settings.carPort)
        val simConfig = simConfig(recording.file)
        val sim = try {
            CarSim(simConfig, recorder, log)
        } catch (e: IOException) {
            recording.finish()
            throw SetupException("no se pudo crear ${recording.file.path}: ${e.message}", e)
        }
        val hook = Thread(::onShutdown, "carsim-ctrl-c")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            return execute(sim, simConfig, recording)
        } finally {
            finished.set(true)
            reportDone.countDown()
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // ya se está apagando la JVM (Ctrl+C): el gancho termina el proceso
            }
        }
    }

    private fun execute(sim: CarSim, simConfig: CarSimConfig, recording: Recording): RunResult {
        printHeader(simConfig, recording)
        recorder.note("inicio (${settings.mode.label}): broadcast a ${settings.targets.joinToString { it.address.hostAddress }}")
        var fanout: BroadcastFanout? = null
        var progress: ProgressTicker? = null
        val stopReason = try {
            sim.start()
            fanout = startFanout(sim, simConfig)
            progress = ProgressTicker(clock, sim, recorder, fanout, recording.file).start()
            drive(sim)
        } finally {
            if (stopNanos == 0L) stopNanos = System.nanoTime()
            fanout?.close()
            scriptRunner?.let {
                it.stop()
                it.await(2_000)
            }
            sim.close()
            sim.awaitTermination(5_000)
            progress?.stop()
        }

        val snapshot = recorder.snapshot()
        val closedNanos = snapshot.closedAtMs?.let { clock.startNanos + it * 1_000_000 } ?: stopNanos
        val analysis = RunAnalysis(
            options = options,
            settings = settings,
            simConfig = simConfig,
            stopReason = stopReason,
            endMs = clock.elapsedMs(stopNanos),
            report = sim.report(),
            fanoutBroadcasts = fanout?.sent ?: 0,
            recorder = snapshot,
            video = recorder.videoSummary(minOf(stopNanos, closedNanos)),
            sps = SpsReader.readAll(recording.file, snapshot.spsSegments),
            script = scriptOutcome(),
            sentTouches = sim.sentTouches(),
            savedVideo = recording.saved,
            decode = decode(recording.file, sim.report().videoMessages),
        )
        val extra = extension?.finish(analysis)
        val checks = Checks.evaluate(analysis) + extra?.checks.orEmpty()
        exitCode = if (checks.any { it.status == CheckStatus.FAIL }) ExitCodes.CHECKS_FAILED else ExitCodes.OK
        Console.flush()
        TextReport.print(analysis, extra, checks)
        options.report?.let { file ->
            try {
                JsonReport.write(file, analysis, extra, checks, clock)
                Console.line("Informe JSON guardado en ${file.path}")
            } catch (e: IOException) {
                Console.error("No se pudo guardar el informe JSON en ${file.path}: ${e.message}")
            }
        }
        recording.finish()
        Console.flush()
        return RunResult(checks, exitCode)
    }

    /** Espera al vídeo y luego a que se cumpla la duración (y acabe el guion), a que se cierre la conexión o a Ctrl+C. */
    private fun drive(sim: CarSim): StopReason {
        while (true) {
            when (sim.currentState) {
                CarSimState.STREAMING -> break
                CarSimState.CLOSED -> return stop(if (recorder.connectedAtMs == null) StopReason.NO_PHONE else StopReason.PHONE_CLOSED)
                else -> if (waitForStop()) return stop(StopReason.USER)
            }
        }
        val streamingNanos = System.nanoTime()
        if (!settings.script.isEmpty) {
            scriptRunner = ScriptRunner(sim, settings.script, options.width, options.height, clock)
                .start({ recorder.firstVideoAtMs != null }, RunSettings.SCRIPT_VIDEO_WAIT_MS)
        }
        val what = if (settings.durationMs > 0) "durante ${Fmt.duration(settings.durationMs)}" else "hasta Ctrl+C"
        Console.line("${clock.stamp()}  observando el vídeo $what...")
        var waitingForScript = false
        while (true) {
            if (sim.currentState == CarSimState.CLOSED) return stop(StopReason.PHONE_CLOSED)
            val elapsed = (System.nanoTime() - streamingNanos) / 1_000_000
            if (settings.durationMs > 0 && elapsed >= settings.durationMs) {
                val script = scriptRunner
                if (script == null || script.isDone || elapsed >= settings.durationMs + MAX_SCRIPT_OVERRUN_MS) return stop(StopReason.DURATION)
                if (!waitingForScript) {
                    waitingForScript = true
                    Console.line("${clock.stamp()}  duración cumplida: se espera a que acabe el guion táctil")
                }
            }
            if (waitForStop()) return stop(StopReason.USER)
        }
    }

    private fun stop(reason: StopReason): StopReason {
        stopNanos = System.nanoTime()
        recorder.note("fin de la prueba: ${reason.text}", print = true)
        return reason
    }

    private fun waitForStop(): Boolean = stopSignal.await(POLL_MS, TimeUnit.MILLISECONDS)

    /** Ctrl+C: para la prueba, deja que el hilo principal saque el informe y termina con su código. */
    private fun onShutdown() {
        if (finished.get()) return
        Console.line("")
        Console.line("Ctrl+C: parando la prueba y preparando el informe...")
        stopSignal.countDown()
        reportDone.await(20, TimeUnit.SECONDS)
        Console.flush()
        Runtime.getRuntime().halt(exitCode)
    }

    private fun scriptOutcome(): ScriptOutcome {
        val r = scriptRunner
        return ScriptOutcome(
            script = settings.script,
            startedAtMs = r?.startedAtMs,
            startedWithoutVideo = r?.startedWithoutVideo ?: false,
            completed = r?.completed ?: settings.script.isEmpty,
            results = r?.results().orEmpty(),
        )
    }

    private fun simConfig(recording: File) = CarSimConfig(
        broadcastAddress = settings.targets.first().address,
        phoneDiscoveryPort = settings.phonePort,
        carAckPort = settings.carPort,
        broadcastIntervalMs = settings.broadcastIntervalMs,
        discoveryTimeoutMs = settings.discoveryTimeoutMs,
        carInfo = CarInfoValues(carType = options.carType, carWidth = options.width, carHeight = options.height),
        videoArgs = VideoArgsValues(frameRate = options.fps, bitRate = options.bitrate, frameInterval = options.gop),
        heartbeatPeriodMs = settings.heartbeatPeriodMs,
        recordVideoTo = recording,
        receiverLimitBytes = options.receiverLimitBytes,
        receiverHangMs = options.receiverHangMs,
    )

    /** `--decode`: la grabación por ffmpeg (con el simulador ya cerrado, así que el fichero está completo). */
    private fun decode(recording: File, videoMessages: Int): DecodeOutcome {
        if (!options.decode) return DecodeOutcome(false, null, null, "no se pidió --decode")
        if (settings.mode == RunMode.SELF_TEST) return DecodeOutcome(true, null, null, "los frames del autotest son falsos y no se pueden decodificar")
        val ffmpeg = Ffmpeg.locate(options.ffmpeg)
            ?: return DecodeOutcome(true, null, null, options.ffmpeg?.let { "no existe ${it.path}" } ?: "ffmpeg no encontrado (ni en ${Ffmpeg.DEFAULT_PATH.path} ni en el PATH)")
        if (videoMessages == 0) return DecodeOutcome(true, ffmpeg, null, "no hubo vídeo")
        Console.line("${clock.stamp()}  decodificando el vídeo recibido con ${ffmpeg.path}...")
        return DecodeOutcome(true, ffmpeg, Ffmpeg.decode(ffmpeg, recording), null)
    }

    private fun startFanout(sim: CarSim, config: CarSimConfig): BroadcastFanout? {
        val extra = settings.targets.drop(1)
        if (extra.isEmpty()) return null
        val payload = UdpCodec.buildConnectBroadcast(config.deviceUuid, config.deviceName, config.broadcastExtra)
        return try {
            BroadcastFanout(
                targets = extra.map { InetSocketAddress(it.address, settings.phonePort) },
                payload = payload,
                intervalMs = settings.broadcastIntervalMs,
                active = { sim.currentState == CarSimState.IDLE || sim.currentState == CarSimState.DISCOVERING },
                onError = { Console.line("${clock.stamp()}  aviso: $it") },
            ).start()
        } catch (e: IOException) {
            Console.line("Aviso: ${e.message}; solo se usará ${settings.targets.first()}")
            null
        }
    }

    private fun printHeader(config: CarSimConfig, recording: Recording) {
        val c = config.carInfo
        val v = config.videoArgs
        Console.line("carsim (${settings.mode.label}): coche QDLink simulado (Leapmotor C10)")
        val limit = if (config.receiverLimitBytes > 0) "se cuelga con mensajes de vídeo de más de ${config.receiverLimitBytes / 1024} KiB (${Fmt.duration(config.receiverHangMs)} sin leer)" else "sin límite de mensaje"
        val spsCheck = if (options.spsCheck) "SPS/PPS repetidos" else "sin comprobar SPS/PPS repetidos"
        val decode = if (options.decode) " | ffmpeg al final" else ""
        Console.line("  Manías C10  $limit | $spsCheck$decode")
        Console.line("  CAR_INFO    CarType ${c.carType} | ${Fmt.size(c.carWidth, c.carHeight)} | CarFactory ${c.carFactory} | HUFactory ${c.huFactory}")
        Console.line(
            "  VIDEO_ARGS  ${Fmt.size(v.width ?: c.carWidth, v.height ?: c.carHeight)} | EncodingType ${v.encodingType} | " +
                "${v.frameRate} fps | ${v.bitRate} bit/s | FrameInterval ${v.frameInterval}",
        )
        Console.line("  Broadcast   cada ${config.broadcastIntervalMs} ms al UDP ${settings.phonePort} de:")
        settings.targets.forEachIndexed { i, t ->
            Console.line("                $t" + if (i == 0) " [sale del UDP ${settings.carPort}]" else "")
        }
        val firewall = if (settings.mode == RunMode.CAR) " (el cortafuegos de Windows tiene que dejarlo entrar a java.exe)" else ""
        Console.line("  ACK         en el UDP ${settings.carPort}$firewall")
        val duration = if (settings.durationMs > 0) "${Fmt.duration(settings.durationMs)} de vídeo tras el handshake" else "hasta Ctrl+C"
        val video = recording.saved?.let { " | vídeo en ${it.path}" } ?: ""
        Console.line("  Prueba      $duration | guion táctil: ${settings.script}$video")
        Console.line("Esperando al teléfono (hasta ${Fmt.duration(settings.discoveryTimeoutMs)}; Ctrl+C para parar)...")
    }

    private fun ensureUdpPortFree(port: Int) {
        try {
            DatagramSocket(null as SocketAddress?).use { s ->
                s.reuseAddress = false
                s.bind(InetSocketAddress(port))
            }
        } catch (e: SocketException) {
            throw SetupException("el puerto UDP $port ya está en uso (¿hay otro carsim abierto?): ${e.message}", e)
        }
    }

    private companion object {
        const val POLL_MS = 100L

        /** Si el guion sigue cuando acaba la duración, se le espera como mucho esto. */
        const val MAX_SCRIPT_OVERRUN_MS = 60_000L
    }
}

/** Dónde graba `CarSim` el vídeo: el fichero de `--out` o uno temporal (hace falta para leer los SPS). */
class Recording private constructor(val file: File, private val temporary: Boolean) {
    val saved: File? get() = if (temporary) null else file

    fun finish() {
        if (temporary) file.delete()
    }

    companion object {
        fun prepare(out: File?): Recording {
            if (out == null) {
                return try {
                    Recording(File.createTempFile("carsim-", ".h264").apply { deleteOnExit() }, true)
                } catch (e: IOException) {
                    throw SetupException("no se pudo crear el fichero temporal para el vídeo: ${e.message}", e)
                }
            }
            ensureParentDir(out)
            return Recording(out, false)
        }
    }
}

internal fun ensureParentDir(file: File) {
    val dir = file.absoluteFile.parentFile ?: return
    if (!dir.isDirectory && !dir.mkdirs()) throw SetupException("no se pudo crear la carpeta ${dir.path}")
}
