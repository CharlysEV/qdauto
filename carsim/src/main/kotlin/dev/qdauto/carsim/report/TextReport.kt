package dev.qdauto.carsim.report

import dev.qdauto.carsim.Console
import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.run.Dims
import dev.qdauto.carsim.run.ExtensionResult
import dev.qdauto.carsim.run.PhoneMessage
import dev.qdauto.carsim.run.RunAnalysis
import dev.qdauto.carsim.run.RunMode
import dev.qdauto.carsim.run.SpsResult
import dev.qdauto.carsim.touch.WaitStep
import dev.qdauto.core.util.Hex
import kotlin.math.roundToInt

/** Informe final legible en la consola. */
object TextReport {
    private const val RULE = "=============================================================================="
    private const val INDENT = "            "

    fun print(a: RunAnalysis, extra: ExtensionResult?, checks: List<Check>) = lines(a, extra, checks).forEach(Console::line)

    fun lines(a: RunAnalysis, extra: ExtensionResult?, checks: List<Check>): List<String> = buildList {
        val failed = checks.filter { it.status == CheckStatus.FAIL }
        val verdict = if (failed.isEmpty()) "PASS" else "FAIL"
        add("")
        add(RULE)
        add("INFORME DE CARSIM (${a.settings.mode.label}): $verdict")
        add(RULE)
        summary(a)
        timeline(a)
        phoneMessages(a)
        video(a)
        sps(a)
        script(a)
        if (extra != null) {
            section(extra.title)
            extra.lines.forEach { add("   $it") }
        }
        problems(a)
        section("Comprobaciones")
        val width = checks.maxOfOrNull { it.id.length } ?: 10
        for (c in checks) {
            add("   ${c.status.name.padEnd(4)}  ${c.id.padEnd(width)}  ${c.title}")
            add("   ${" ".repeat(4)}  ${" ".repeat(width)}  ${c.detail}")
        }
        add(RULE)
        val skipped = checks.count { it.status == CheckStatus.SKIP }
        val tail = if (skipped > 0) "; $skipped sin evaluar" else ""
        if (failed.isEmpty()) {
            add("RESULTADO: PASS (${checks.size - skipped} de ${checks.size} comprobaciones correctas$tail)")
        } else {
            add("RESULTADO: FAIL (${failed.size} de ${checks.size} fallan: ${failed.joinToString(", ") { it.id }}$tail)")
        }
        add(RULE)
        if (a.report.ackFrom == null && a.settings.mode == RunMode.CAR) hints()
    }

    private fun MutableList<String>.section(title: String) {
        add("")
        add("-- $title --")
    }

    private fun MutableList<String>.summary(a: RunAnalysis) {
        val c = a.simConfig.carInfo
        val v = a.simConfig.videoArgs
        add("Coche: CarType ${c.carType}, ${Fmt.size(c.carWidth, c.carHeight)} | VIDEO_ARGS ${v.frameRate} fps, ${v.bitRate} bit/s, FrameInterval ${v.frameInterval}")
        add("Teléfono: ${a.report.connectedTo ?: Fmt.address(a.report.ackFrom).takeIf { a.report.ackFrom != null } ?: "no contestó"}")
        add("Duración: ${Fmt.secs(a.endMs)} | fin: ${a.stopReason.text} | cierre del TCP: ${a.report.closeReason ?: "-"}")
    }

    private fun MutableList<String>.timeline(a: RunAnalysis) {
        section("Cronología")
        var previous = 0L
        for (e in a.recorder.timeline) {
            add("${Fmt.stamp(e.tMs)}  (+${(e.tMs - previous).toString().padStart(5)} ms)  ${Fmt.clip(e.text, 200)}")
            previous = e.tMs
        }
    }

    /** Cada JSON distinto una vez, en orden de llegada, con cuántas veces llegó y cada cuánto. */
    private fun MutableList<String>.phoneMessages(a: RunAnalysis) {
        section("Mensajes del teléfono al coche (cada JSON distinto, por orden de llegada)")
        if (a.recorder.appStatuses.isEmpty() && a.recorder.phoneMessages.isEmpty()) {
            add("   (ninguno)")
            return
        }
        for (s in a.recorder.appStatuses) {
            val verdict = if (s.errors.isEmpty()) "idéntico al de QDLink" else "${s.errors.size} diferencias con QDLink"
            add("${Fmt.stamp(s.tMs)}  !BIN AppStatus (512 B, SDK ${s.sdkInt}): $verdict")
        }
        val groups = LinkedHashMap<Pair<String, String>, MutableList<PhoneMessage>>()
        for (m in a.recorder.phoneMessages) groups.getOrPut(m.kind to m.json) { ArrayList() } += m
        for ((key, list) in groups) {
            val (kind, json) = key
            val type = if (list[0].msgType == 13) " (msgType 13)" else ""
            val intervals = list.zipWithNext { x, y -> y.tMs - x.tMs }
            val repeat = when {
                list.size == 1 -> ""
                else -> " x${list.size}, cada ${intervals.min()}-${intervals.max()} ms"
            }
            add("${Fmt.stamp(list[0].tMs)}  $kind$type$repeat")
            add("$INDENT$json")
        }
    }

    private fun MutableList<String>.video(a: RunAnalysis) {
        section("Vídeo")
        val v = a.video
        if (v.messages == 0) {
            add("   (no llegó vídeo)")
            return
        }
        val va = a.simConfig.videoArgs
        add("   mensajes: ${v.messages} (SPS/PPS ${v.configs}, IDR ${v.idr}, P ${v.p}, otros ${v.other}), ${Fmt.bytes(v.payloadBytes)} de H.264")
        v.fpsAvg?.let { add("   fps: medio ${Fmt.dec(it)} (pedido ${va.frameRate}), por segundo ${v.fpsMin ?: "-"}-${v.fpsMax ?: "-"} en ${v.fullSeconds} s completos") }
        v.kbpsAvg?.let {
            val max = v.kbpsMax?.let { m -> ", máximo ${m.roundToInt()} kbit/s en un segundo" } ?: ""
            add("   bitrate: medio ${it.roundToInt()} kbit/s$max (pedido ${va.bitRate / 1000})")
        }
        v.maxGapMs?.let {
            add("   huecos: máximo ${Fmt.dec(it, 0)} ms a los ${Fmt.secs(v.maxGapAtMs ?: 0L)}; ${v.gapsOverThreshold} de más de ${Fmt.dec(v.gapThresholdMs, 0)} ms")
        }
        if (v.idrIntervalsMs.isNotEmpty()) {
            add(
                "   IDR: ${v.idr}, cada ${v.idrIntervalsMs.min()}-${v.idrIntervalsMs.max()} ms (media ${Fmt.dec(v.idrIntervalsMs.average(), 0)}), " +
                    "cada ${v.idrIntervalsFrames.min()}-${v.idrIntervalsFrames.max()} frames",
            )
        } else {
            add("   IDR: ${v.idr}")
        }
        add("   cabeceras de 32 B:")
        for ((p, n) in v.headers) {
            add(
                "      ${Fmt.size(p.width, p.height)} enc ${p.encodingType} fps ${p.fps} bitrate ${p.bitrate} GOP ${p.gop} " +
                    "appType ${p.appType} ángulo ${p.angle} orientación ${p.orientation}: $n mensajes",
            )
        }
        add("   errores de validación: ${a.report.videoErrorCount}")
        Fmt.groupedErrors(a.report.videoErrors).take(15).forEach { add("      ! $it") }
        if (a.report.videoErrorCount > a.report.videoErrors.size) add("      (se agrupan los ${a.report.videoErrors.size} primeros)")
        a.savedVideo?.let { add("   vídeo guardado en ${it.path} (ffplay -f h264 \"${it.path}\")") }
    }

    private fun MutableList<String>.sps(a: RunAnalysis) {
        section("SPS")
        if (a.sps.isEmpty()) {
            add("   (ningún mensaje de vídeo traía SPS)")
            return
        }
        // Un bloque por SPS distinto; los segmentos con el mismo SPS se juntan.
        val distinct = LinkedHashMap<String, MutableList<SpsResult>>()
        for (r in a.sps) distinct.getOrPut(r.nal?.let { Hex.encode(it) } ?: "error:${r.error}") { ArrayList() } += r
        for ((_, results) in distinct) {
            val r = results[0]
            val where = "mensaje #${r.segment.messageIndex} a los ${Fmt.secs(r.segment.tMs, 3)}" +
                if (results.size > 1) " (y ${Fmt.count(results.size - 1, "vez", "veces")} más)" else ""
            add("   $where: ${r.info?.describe() ?: r.error}")
            r.nal?.let { add("      hex: ${Fmt.clip(Hex.encode(it), 180)}") }
            val headers = LinkedHashMap<Dims, Int>()
            results.forEach { s -> s.segment.headerSizes.forEach { (d, n) -> headers.merge(d, n, Int::plus) } }
            val info = r.info
            if (headers.isNotEmpty()) {
                val list = headers.entries.joinToString(", ") { (d, n) ->
                    val mark = if (info == null) "" else if (d == Dims(info.width, info.height)) " coincide" else " NO COINCIDE"
                    "$d$mark ($n mensajes)"
                }
                add("      cabeceras de 32 B con este SPS: $list")
            }
        }
        val before = a.recorder.headersBeforeFirstSps
        if (before.isNotEmpty()) add("   antes del primer SPS: ${before.entries.joinToString { (d, n) -> "$d ($n mensajes)" }}")
    }

    private fun MutableList<String>.script(a: RunAnalysis) {
        val s = a.script
        section("Guion táctil (${s.script})")
        if (s.script.isEmpty) {
            add("   (sin guion)")
            return
        }
        if (s.startedAtMs == null) {
            add("   no llegó a empezar")
            return
        }
        for (r in s.results) {
            if (r.step is WaitStep && r.ok) continue
            val detail = if (r.detail.isEmpty()) "" else " -> ${r.detail}"
            add("${Fmt.stamp(r.tMs)}  ${if (r.ok) "OK  " else "FALLO"}  ${r.step.text}$detail")
        }
        val pending = s.script.steps.size - s.results.size
        if (pending > 0) add("   ($pending pasos sin ejecutar)")
        add("   mensajes táctiles enviados: ${a.sentTouches.size}")
    }

    private fun MutableList<String>.problems(a: RunAnalysis) {
        val r = a.report
        if (r.unexpected.isNotEmpty()) {
            section("Inesperado (${r.unexpected.size})")
            r.unexpected.take(20).forEach { add("   ! ${Fmt.clip(it, 300)}") }
        }
        if (r.handshakeErrors.isNotEmpty()) {
            section("Errores del handshake")
            r.handshakeErrors.forEach { add("   ! $it") }
        }
    }

    private fun MutableList<String>.hints() {
        add("")
        add("Si no llega el ACK:")
        add("  - la app QDAuto tiene que estar abierta en el móvil, y QDLink cerrado del todo (forzar detención);")
        add("  - el móvil y el PC tienen que estar en la misma Wi-Fi, sin aislamiento de clientes en el router;")
        add("  - el cortafuegos de Windows tiene que dejar entrar UDP 18464 a java.exe (ver carsim/README.md);")
        add("  - prueba con --target <IP del móvil> para mandarle el broadcast directamente.")
    }
}
