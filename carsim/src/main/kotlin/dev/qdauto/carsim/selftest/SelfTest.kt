package dev.qdauto.carsim.selftest

import dev.qdauto.carsim.Console
import dev.qdauto.carsim.ConsoleLog
import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.RunClock
import dev.qdauto.carsim.SetupException
import dev.qdauto.carsim.cli.Options
import dev.qdauto.carsim.net.BroadcastTarget
import dev.qdauto.carsim.report.Check
import dev.qdauto.carsim.report.JsonReport
import dev.qdauto.carsim.run.CarRun
import dev.qdauto.carsim.run.ExtensionResult
import dev.qdauto.carsim.run.RunAnalysis
import dev.qdauto.carsim.run.RunExtension
import dev.qdauto.carsim.run.RunMode
import dev.qdauto.carsim.run.RunResult
import dev.qdauto.carsim.run.RunSettings
import dev.qdauto.carsim.touch.KeyStep
import dev.qdauto.carsim.touch.KeyframeStep
import dev.qdauto.carsim.touch.MusicStep
import dev.qdauto.carsim.touch.TouchScripts
import dev.qdauto.core.session.KeyframeReason
import dev.qdauto.core.session.VideoOverrides
import dev.qdauto.core.sim.SentTouch
import dev.qdauto.core.wire.CarKey
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * `--self-test`: el coche simulado contra un teléfono simulado ([LoopbackPhone], la `PhoneSession` de `:core` con
 * frames falsos) en 127.0.0.1 y con puertos libres. Pasa por exactamente el mismo camino que una prueba real
 * (comprobaciones e informe incluidos) y añade las del lado del teléfono: el táctil y las teclas llegan idénticos,
 * `KEY_FRAME_REQ` pide un IDR y todos los hilos terminan.
 */
class SelfTest(
    private val options: Options,
    /** Solo para tests: fuerza la cabecera de vídeo del teléfono para comprobar que los fallos se detectan. */
    private val videoOverrides: VideoOverrides = VideoOverrides(),
    /** Solo para tests: IDR enormes o SPS/PPS repetidos, para comprobar que las manías del C10 se detectan. */
    private val encoderTweaks: EncoderTweaks = EncoderTweaks(),
) {
    fun run(): RunResult {
        if (options.targets.isNotEmpty()) Console.line("Aviso: --target no se usa en el autotest (todo va por 127.0.0.1).")
        val clock = RunClock()
        val phonePort = freeUdpPort()
        var carPort = freeUdpPort()
        while (carPort == phonePort) carPort = freeUdpPort()
        val settings = RunSettings(
            mode = RunMode.SELF_TEST,
            targets = listOf(BroadcastTarget(InetAddress.getLoopbackAddress(), "teléfono simulado")),
            phonePort = phonePort,
            carPort = carPort,
            broadcastIntervalMs = 200,
            heartbeatPeriodMs = 1_000,
            durationMs = options.durationMs ?: Options.SELF_TEST_DURATION_MS,
            discoveryTimeoutMs = 15_000,
            script = options.touchScript ?: TouchScripts.SELF_TEST,
            checkPortFree = false,
        )
        val phone = LoopbackPhone(phonePort, carPort, ConsoleLog(clock, options.verbose, "[teléfono] "), videoOverrides, encoderTweaks)
        try {
            phone.start()
        } catch (e: IOException) {
            throw SetupException("no se pudo abrir el UDP del teléfono simulado: ${e.message}", e)
        }
        try {
            return CarRun(options, settings, clock, PhoneSide(phone)).run()
        } finally {
            phone.close()
        }
    }

    private fun freeUdpPort(): Int = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Comprobaciones del lado del teléfono. */
    private class PhoneSide(private val phone: LoopbackPhone) : RunExtension {
        override fun finish(analysis: RunAnalysis): ExtensionResult {
            // El coche ya cerró el TCP: la sesión termina por EOF tras entregar todos sus eventos, en orden.
            phone.awaitSessionClosed(5_000)
            phone.close()
            val terminated = phone.awaitTermination(5_000)
            val session = phone.session
            val stats = session?.stats()
            val checks = listOf(
                touches(analysis),
                keys(analysis),
                keyframe(analysis),
                threads(terminated),
            )
            val s = phone.encoder.suggestion
            val sizes = phone.encoder.frameBytes
            val lines = buildList {
                add("sesión: ${session?.toString() ?: "no se creó"}; cierre: ${phone.closeReason ?: "-"}")
                if (s != null) {
                    add("encoder simulado: ${s.width}x${s.height}, ${s.frameRate} fps, ${s.bitRate} bit/s, IDR cada ${s.iFrameIntervalSec} s" +
                        (sizes?.let { (p, i) -> "; P de $p B, IDR de $i B" } ?: ""))
                }
                if (stats != null) {
                    add(
                        "vídeo enviado: ${stats.videoFramesSent} frames (IDR ${stats.keyframesSent}, SPS/PPS ${stats.codecConfigsSent}), " +
                            "descartados ${stats.videoFramesDropped}, rechazados ${stats.videoFramesRejected}, peticiones de IDR ${stats.keyframeRequests}",
                    )
                    add(
                        "del coche: ${Fmt.count(stats.carHeartbeats, "heartbeat", "heartbeats")}, hueco máximo ${stats.maxCarGapMs} ms, " +
                            Fmt.count(stats.touchEvents, "táctil", "táctiles"),
                    )
                }
                add("táctiles recibidos: ${phone.touches.size}; teclas: ${phone.keys}; peticiones de IDR: ${phone.keyframeReasons}")
                if (phone.unknown.isNotEmpty()) add("mensajes del coche no reconocidos: ${phone.unknown.take(5)}")
            }
            val json = linkedMapOf<String, Any?>(
                "cierre" to phone.closeReason?.toString(),
                "framesAceptados" to phone.encoder.framesAccepted.get(),
                "framesRechazados" to phone.encoder.framesRejected.get(),
                "idrGenerados" to phone.encoder.keyframes.get(),
                "estadisticas" to stats?.let {
                    linkedMapOf(
                        "videoFramesSent" to it.videoFramesSent,
                        "keyframesSent" to it.keyframesSent,
                        "codecConfigsSent" to it.codecConfigsSent,
                        "videoFramesDropped" to it.videoFramesDropped,
                        "videoFramesRejected" to it.videoFramesRejected,
                        "keyframeRequests" to it.keyframeRequests,
                        "carHeartbeats" to it.carHeartbeats,
                        "maxCarGapMs" to it.maxCarGapMs,
                        "touchEvents" to it.touchEvents,
                    )
                },
                "tactilesRecibidos" to phone.touches.map { JsonReport.touchJson(SentTouch(it.action, it.pointers)) },
                "teclas" to phone.keys.map { it.name },
                "peticionesIdr" to phone.keyframeReasons.map { it.name },
                "noReconocidos" to phone.unknown.map { it.toString() },
            )
            return ExtensionResult("Teléfono simulado (PhoneSession de :core)", checks, lines, json)
        }

        private fun touches(a: RunAnalysis): Check {
            val id = "autotest_tactil"
            val title = "El teléfono recibe exactamente los táctiles enviados (acción, dedos y x/y bit a bit)"
            val sent = a.sentTouches
            if (sent.isEmpty()) return Check.skip(id, title, "no se mandó ningún táctil")
            val got = phone.touches.map { SentTouch(it.action, it.pointers) }
            if (got == sent) return Check.pass(id, title, "${sent.size} mensajes, ${sent.sumOf { it.pointers.size }} dedos")
            val k = sent.indices.firstOrNull { it >= got.size || got[it] != sent[it] } ?: sent.size
            val detail = "enviados ${sent.size}, recibidos ${got.size}" +
                if (k < sent.size) "; el #$k se envió como ${sent[k]} y llegó ${got.getOrNull(k)}" else ""
            return Check.fail(id, title, detail)
        }

        private fun keys(a: RunAnalysis): Check {
            val id = "autotest_teclas"
            val title = "El teléfono recibe las teclas del guion (PHONE_KEYS y Music)"
            val expected = a.script.results.filter { it.ok }.mapNotNull {
                when (val step = it.step) {
                    is KeyStep -> CarKey.fromPhoneKeys(step.code)
                    is MusicStep -> CarKey.fromMusicFunction(step.functionId)
                    else -> null
                }
            }
            if (expected.isEmpty()) return Check.skip(id, title, "el guion no manda teclas")
            val got = phone.keys.toList()
            return Check.of(got == expected, id, title, if (got == expected) "$got" else "se esperaba $expected y llegó $got")
        }

        private fun keyframe(a: RunAnalysis): Check {
            val id = "autotest_keyframe"
            val title = "KEY_FRAME_REQ: el teléfono reenvía SPS/PPS y pide un IDR al encoder"
            if (a.script.results.none { it.ok && it.step == KeyframeStep }) return Check.skip(id, title, "el guion no manda KEY_FRAME_REQ")
            val asked = KeyframeReason.CAR_REQUEST in phone.keyframeReasons
            val configs = a.report.codecConfigMessages
            return Check.of(asked && configs >= 2, id, title, "IDR pedido: ${if (asked) "sí" else "no"}; mensajes SPS/PPS recibidos: $configs")
        }

        private fun threads(terminated: Boolean): Check {
            val id = "autotest_hilos"
            val title = "Al terminar no queda ningún hilo vivo del coche ni del teléfono"
            val prefixes = listOf("qd-", "carsim-main", "carsim-reader", "carsim-timer", "autotest-")
            var alive = liveThreads(prefixes)
            val deadline = System.currentTimeMillis() + 2_000
            while (alive.isNotEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                alive = liveThreads(prefixes)
            }
            return Check.of(terminated && alive.isEmpty(), id, title, if (alive.isEmpty()) "ninguno" else "siguen vivos: $alive")
        }

        private fun liveThreads(prefixes: List<String>): List<String> =
            Thread.getAllStackTraces().keys.filter { t -> t.isAlive && prefixes.any { t.name.startsWith(it) } }.map { it.name }
    }
}
