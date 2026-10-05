package dev.qdauto.carsim.report

import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.run.Dims
import dev.qdauto.carsim.run.RunAnalysis
import dev.qdauto.carsim.run.RunRecorder
import dev.qdauto.carsim.run.StopReason
import dev.qdauto.core.session.MirrorGeometry
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.UdpCodec
import dev.qdauto.core.wire.VideoParams

enum class CheckStatus { PASS, FAIL, SKIP }

/** Una comprobación del informe. SKIP = no se puede evaluar (p. ej. no hubo vídeo); no cuenta como fallo. */
data class Check(val id: String, val title: String, val status: CheckStatus, val detail: String) {
    companion object {
        fun pass(id: String, title: String, detail: String) = Check(id, title, CheckStatus.PASS, detail)
        fun fail(id: String, title: String, detail: String) = Check(id, title, CheckStatus.FAIL, detail)
        fun skip(id: String, title: String, detail: String) = Check(id, title, CheckStatus.SKIP, detail)
        fun of(ok: Boolean, id: String, title: String, detail: String) = Check(id, title, if (ok) CheckStatus.PASS else CheckStatus.FAIL, detail)
    }
}

/** Comprobaciones de una prueba contra el teléfono (spec §7.1 y §8). */
object Checks {
    /** Heartbeat del teléfono: el watchdog de QDLink corta a los 5 s sin datos (LC/a.java:689-703); el del coche, ni idea. */
    private const val MAX_HEARTBEAT_GAP_MS = 5_000L
    private const val MAX_FRAME_GAP_MS = 1_000.0

    /** Orden en que el teléfono tiene que responder (spec §7.1). */
    val HANDSHAKE_ORDER = listOf(RunRecorder.APP_STATUS, Cmd.PHONE_INFO, Cmd.UPDATE_NOTIFY, Cmd.VIDEO_SUP_RSP, Cmd.SPEECH_ARGS)

    fun evaluate(a: RunAnalysis): List<Check> = listOf(
        discovery(a), ackFormat(a), tcp(a), appStatus(a), handshake(a), order(a), phoneInfo(a), heartbeat(a),
        videoReceived(a), videoValid(a), headerSize(a), headerEcho(a), sps(a), spsVsHeader(a),
        fps(a), gaps(a), idr(a), touch(a), unexpected(a), session(a),
    )

    private fun discovery(a: RunAnalysis): Check {
        val id = "descubrimiento"
        val title = "El teléfono contesta al Connect_Broadcast con un Broadcast_ACK"
        val r = a.report
        val at = a.recorder.ackAtMs
        return if (r.ackFrom != null && at != null) {
            Check.pass(id, title, "ACK de ${Fmt.address(r.ackFrom)} a los ${Fmt.secs(at)} (${Fmt.count(a.totalBroadcasts, "broadcast enviado", "broadcasts enviados")})")
        } else {
            Check.fail(id, title, "sin ACK tras ${Fmt.count(a.totalBroadcasts, "broadcast", "broadcasts")} (${r.closeReason ?: a.stopReason.text})")
        }
    }

    private fun ackFormat(a: RunAnalysis): Check {
        val id = "ack_formato"
        val title = "Broadcast_ACK idéntico al de QDLink (174 B con un MirrorPort de 5 cifras)"
        val r = a.report
        val json = r.ackJson ?: return Check.skip(id, title, "no hubo ACK")
        val port = r.mirrorPort ?: return Check.fail(id, title, "ACK sin MirrorPort: $json")
        val expected = UdpCodec.broadcastAckJson(port)
        val range = if (port in 10_001..65_535) "" else " (QDLink lo elige en 10001-65535)"
        return Check.of(
            json == expected, id, title,
            if (json == expected) "MirrorPort $port$range" else "llegó $json; QDLink manda $expected",
        )
    }

    private fun tcp(a: RunAnalysis): Check {
        val id = "tcp"
        val title = "El coche se conecta por TCP al MirrorPort"
        val r = a.report
        if (r.ackFrom == null) return Check.skip(id, title, "no hubo ACK")
        return if (a.connected) {
            Check.pass(id, title, "conectado a ${r.connectedTo}")
        } else {
            Check.fail(id, title, "no se pudo conectar a ${Fmt.address(r.ackFrom)} puerto ${r.mirrorPort}: ${r.closeReason}")
        }
    }

    private fun appStatus(a: RunAnalysis): Check {
        val id = "appstatus"
        val title = "AppStatus !BIN de 512 B al conectar, idéntico al de QDLink"
        if (!a.connected) return Check.skip(id, title, "no hubo conexión")
        val seen = a.recorder.appStatuses
        val errors = seen.flatMap { it.errors }
        return when {
            seen.isEmpty() -> Check.fail(id, title, "no llegó ningún AppStatus")
            errors.isNotEmpty() -> Check.fail(id, title, "${errors.size} diferencias: ${errors.take(3).joinToString("; ")}")
            seen.size > 1 -> Check.fail(id, title, "llegaron ${seen.size} (QDLink manda uno por sesión)")
            else -> Check.pass(id, title, "a los ${Fmt.secs(seen[0].tMs)}, versionAndroid (SDK) ${seen[0].sdkInt}")
        }
    }

    private fun handshake(a: RunAnalysis): Check {
        val id = "handshake"
        val title = "Respuestas del handshake: PHONE_INFO, UPDATE_NOTIFY, VIDEO_SUP_RSP y SPEECH_ARGS"
        if (!a.connected) return Check.skip(id, title, "no hubo conexión")
        val counts = a.report.phoneMessageCounts
        val missing = listOf(Cmd.PHONE_INFO, Cmd.UPDATE_NOTIFY, Cmd.VIDEO_SUP_RSP, Cmd.SPEECH_ARGS).filter { (counts[it] ?: 0) == 0 }
        val times = listOf(
            Cmd.CAR_INFO to Cmd.PHONE_INFO,
            Cmd.VIDEO_SUP_REQ to Cmd.VIDEO_SUP_RSP,
            Cmd.VIDEO_ARGS to Cmd.SPEECH_ARGS,
        ).mapNotNull { (out, reply) ->
            val sent = a.recorder.firstOut[out] ?: return@mapNotNull null
            val got = a.recorder.firstIn[reply] ?: return@mapNotNull null
            "$reply en ${got - sent} ms"
        }
        val problems = missing.map { "falta $it" } + a.report.handshakeErrors
        return if (problems.isEmpty()) {
            Check.pass(id, title, times.joinToString(", "))
        } else {
            Check.fail(id, title, (problems + times).joinToString("; "))
        }
    }

    private fun order(a: RunAnalysis): Check {
        val id = "orden"
        val title = "Orden: AppStatus, PHONE_INFO, UPDATE_NOTIFY, VIDEO_SUP_RSP, SPEECH_ARGS"
        if (!a.connected) return Check.skip(id, title, "no hubo conexión")
        val seen = a.report.phoneMessageOrder.filter { it in HANDSHAKE_ORDER }.take(HANDSHAKE_ORDER.size)
        return Check.of(seen == HANDSHAKE_ORDER, id, title, if (seen == HANDSHAKE_ORDER) "correcto" else "llegó: ${seen.joinToString(", ")}")
    }

    /** `PHONE_INFO` como lo construye QDLink (LC/a.java:914-959, spec §6.4 y §8.6). */
    private fun phoneInfo(a: RunAnalysis): Check {
        val id = "phone_info"
        val title = "PHONE_INFO coherente con CAR_INFO (eco *InApp y Mirror* según la geometría de QDLink)"
        val info = a.report.lastPhoneInfo ?: return Check.skip(id, title, "no llegó PHONE_INFO")
        val car = a.simConfig.carInfo
        val problems = ArrayList<String>()
        fun expect(key: String, value: Int) {
            val got = info.int(key)
            if (got != value) problems += "$key=$got (se esperaba $value)"
        }
        expect("PhoneWidthInApp", car.carWidth)
        expect("PhoneHeightInApp", car.carHeight)
        expect("MirrorWidthInApp", car.carWidth)
        expect("MirrorHeightInApp", car.carHeight)
        expect("MirrorTypeSupport", car.mirrorTypeReq)
        val pw = info.int("PhoneWidth") ?: 0
        val ph = info.int("PhoneHeight") ?: 0
        if (pw <= 0 || ph <= 0 || pw < ph) {
            problems += "PhoneWidth x PhoneHeight = ${pw}x$ph (tiene que ser lado largo x lado corto)"
        } else {
            val g = MirrorGeometry.forCarInfo(pw, ph, car.carWidth, car.carHeight)
            expect("MirrorWidth", g.mirrorWidth)
            expect("MirrorHeight", g.mirrorHeight)
        }
        if (info.string("Version").isNullOrEmpty()) problems += "Version vacío"
        val summary = "Phone ${pw}x$ph, Mirror ${info.int("MirrorWidth")}x${info.int("MirrorHeight")}, " +
            "InApp ${info.int("MirrorWidthInApp")}x${info.int("MirrorHeightInApp")}, Version ${info.string("Version")}, " +
            "${info.string("PhoneBrand")} ${info.string("PhoneModel")}"
        return Check.of(problems.isEmpty(), id, title, if (problems.isEmpty()) summary else problems.joinToString("; "))
    }

    private fun heartbeat(a: RunAnalysis): Check {
        val id = "heartbeat"
        val title = "HEARTBEAT del teléfono periódico (QDLink: a 1 s y luego cada 3 s), sin huecos > 5 s"
        val connectedAt = a.recorder.connectedAtMs ?: return Check.skip(id, title, "no hubo conexión")
        val end = a.recorder.closedAtMs ?: a.endMs
        if (end - connectedAt < 5_000) return Check.skip(id, title, "la sesión duró menos de 5 s")
        val times = a.recorder.heartbeatTimesMs
        if (times.isEmpty()) return Check.fail(id, title, "ningún HEARTBEAT en ${Fmt.secs(end - connectedAt)}")
        val gaps = listOf(times.first() - connectedAt) + times.zipWithNext { x, y -> y - x } + listOf(end - times.last())
        val intervals = times.zipWithNext { x, y -> y - x }
        val detail = "${times.size} heartbeats, el primero a los ${times.first() - connectedAt} ms de conectar" +
            if (intervals.isEmpty()) "" else ", cada ${intervals.min()}-${intervals.max()} ms"
        val worst = gaps.max()
        return Check.of(worst <= MAX_HEARTBEAT_GAP_MS, id, title, if (worst <= MAX_HEARTBEAT_GAP_MS) detail else "$detail; hueco de $worst ms")
    }

    private fun videoReceived(a: RunAnalysis): Check {
        val id = "video_recibido"
        val title = "Llega vídeo tras VIDEO_CTRL{PlayStatus:1}"
        val streaming = a.recorder.streamingAtMs ?: return Check.skip(id, title, "el handshake no llegó a VIDEO_CTRL")
        val first = a.recorder.firstVideoAtMs
        return if (first != null) {
            Check.pass(id, title, "primer mensaje de vídeo a los ${first - streaming} ms de VIDEO_CTRL")
        } else {
            Check.fail(id, title, "ningún mensaje de vídeo en ${Fmt.secs(a.endMs - streaming)}")
        }
    }

    private fun videoValid(a: RunAnalysis): Check {
        val id = "video_valido"
        val title = "Vídeo válido: cabeceras 16+32 B, Annex-B, SPS/PPS primero y IDR antes de cualquier P"
        val r = a.report
        if (r.videoMessages == 0) return Check.skip(id, title, "no hubo vídeo")
        return if (r.videoValid) {
            Check.pass(id, title, "${r.videoMessages} mensajes sin errores, el primero ${r.firstVideoKind}")
        } else {
            val why = when {
                r.idrFrames == 0 -> "ningún IDR; "
                else -> ""
            }
            Check.fail(id, title, "$why${r.videoErrorCount} errores: ${Fmt.groupedErrors(r.videoErrors).take(3).joinToString("; ")}")
        }
    }

    /** In-app: W x H = par(CarWidth) x par(CarHeight); espejo: MirrorWidth x MirrorHeight (spec §8.7). */
    private fun headerSize(a: RunAnalysis): Check {
        val id = "cabecera_tamano"
        val title = "Cabecera de 32 B: ancho x alto = par(CarWidth) x par(CarHeight) en modo in-app"
        val headers = a.video.headers
        if (headers.isEmpty()) return Check.skip(id, title, "no hubo vídeo")
        val car = a.simConfig.carInfo
        val inApp = Dims(MirrorGeometry.even(car.carWidth), MirrorGeometry.even(car.carHeight))
        val mirror = a.report.lastPhoneInfo?.let { Dims(it.int("MirrorWidth") ?: 0, it.int("MirrorHeight") ?: 0) }
        val bad = headers.filter { (p, _) ->
            val expected = if (p.appType == VideoParams.APP_TYPE_MIRROR) mirror else inApp
            Dims(p.width, p.height) != expected
        }
        val sizes = headers.entries.groupBy { Dims(it.key.width, it.key.height) }.map { (d, e) -> "$d (${e.sumOf { it.value }} mensajes)" }
        return if (bad.isEmpty()) {
            Check.pass(id, title, sizes.joinToString(", "))
        } else {
            val list = bad.entries.joinToString("; ") { (p, n) ->
                val expected = if (p.appType == VideoParams.APP_TYPE_MIRROR) "MirrorWidth x MirrorHeight $mirror" else "$inApp"
                "${p.width}x${p.height} appType ${p.appType} en $n mensajes, se esperaba $expected"
            }
            Check.fail(id, title, list)
        }
    }

    /** Eco crudo de `VIDEO_ARGS` y modo in-app de QDLink (LC/a.java:1453-1481, spec §8.1 y §8.7). */
    private fun headerEcho(a: RunAnalysis): Check {
        val id = "cabecera_eco"
        val title = "Cabecera de 32 B: eco de VIDEO_ARGS y modo in-app (appType 1, ángulo 90, orientación 1)"
        val headers = a.video.headers.keys
        if (headers.isEmpty()) return Check.skip(id, title, "no hubo vídeo")
        val va = a.simConfig.videoArgs
        val problems = LinkedHashSet<String>()
        for (p in headers) {
            if (p.encodingType != va.encodingType) problems += "encodingType ${p.encodingType} (VIDEO_ARGS ${va.encodingType})"
            if (p.fps != va.frameRate) problems += "fps ${p.fps} (VIDEO_ARGS ${va.frameRate})"
            if (p.bitrate != va.bitRate) problems += "bitrate ${p.bitrate} (VIDEO_ARGS ${va.bitRate})"
            if (p.gop != va.frameInterval) problems += "GOP ${p.gop} (VIDEO_ARGS ${va.frameInterval})"
            if (p.appType != VideoParams.APP_TYPE_IN_APP) problems += "appType ${p.appType}"
            if (p.angle != 90) problems += "ángulo ${p.angle}"
            if (p.orientation != 1) problems += "orientación ${p.orientation}"
        }
        val first = headers.first()
        val ok = "enc ${first.encodingType}, ${first.fps} fps, ${first.bitrate} bit/s, GOP ${first.gop}, appType ${first.appType}, " +
            "ángulo ${first.angle}, orientación ${first.orientation}"
        return Check.of(problems.isEmpty(), id, title, if (problems.isEmpty()) ok else problems.joinToString("; "))
    }

    private fun sps(a: RunAnalysis): Check {
        val id = "sps"
        val title = "SPS legible"
        if (a.report.videoMessages == 0) return Check.skip(id, title, "no hubo vídeo")
        if (a.sps.isEmpty()) return Check.fail(id, title, "ningún mensaje de vídeo traía SPS")
        val errors = a.sps.mapNotNull { it.error }
        val first = a.sps.firstNotNullOfOrNull { it.info }
            ?: return Check.fail(id, title, errors.distinct().take(3).joinToString("; "))
        val distinct = a.sps.mapNotNull { it.nal?.contentHashCode() }.distinct().size
        val detail = first.describe() + " (${Fmt.count(a.sps.size, "SPS recibido", "SPS recibidos")}, ${Fmt.count(distinct, "distinto", "distintos")})"
        return Check.of(errors.isEmpty(), id, title, if (errors.isEmpty()) detail else "$detail; errores: ${errors.distinct().take(3).joinToString("; ")}")
    }

    private fun spsVsHeader(a: RunAnalysis): Check {
        val id = "sps_vs_cabecera"
        val title = "Tamaño del SPS (con recorte) = ancho x alto de la cabecera de 32 B"
        val parsed = a.sps.filter { it.info != null }
        if (parsed.isEmpty()) return Check.skip(id, title, "no hay ningún SPS legible")
        val problems = LinkedHashSet<String>()
        for (r in parsed) {
            val info = r.info!!
            val spsDims = Dims(info.width, info.height)
            for ((dims, n) in r.segment.headerSizes) {
                if (dims != spsDims) problems += "SPS del mensaje #${r.segment.messageIndex} es $spsDims y la cabecera dice $dims en $n mensajes"
            }
        }
        val before = a.recorder.headersBeforeFirstSps.values.sum()
        if (before > 0) problems += "$before mensajes de vídeo antes del primer SPS"
        val sizes = parsed.mapNotNull { it.info }.map { Dims(it.width, it.height) }.distinct()
        return Check.of(problems.isEmpty(), id, title, if (problems.isEmpty()) "${sizes.joinToString(", ")} en ambos" else problems.take(5).joinToString("; "))
    }

    private fun fps(a: RunAnalysis): Check {
        val id = "fps"
        val title = "fps medio entre el 50 % y el 150 % del pedido en VIDEO_ARGS"
        val v = a.video
        val avg = v.fpsAvg
        if (avg == null || v.fullSeconds < 2) return Check.skip(id, title, "menos de 2 s de vídeo")
        val wanted = a.simConfig.videoArgs.frameRate
        val ok = avg >= wanted * 0.5 && avg <= wanted * 1.5
        return Check.of(ok, id, title, "medio ${Fmt.dec(avg)}, por segundo ${v.fpsMin}-${v.fpsMax} (pedido $wanted)")
    }

    private fun gaps(a: RunAnalysis): Check {
        val id = "huecos"
        val title = "Sin cortes de vídeo de más de 1 s"
        val v = a.video
        val max = v.maxGapMs ?: return Check.skip(id, title, "menos de 2 frames")
        val detail = "hueco máximo ${Fmt.dec(max, 0)} ms a los ${Fmt.secs(v.maxGapAtMs ?: 0L)}; " +
            "${v.gapsOverThreshold} huecos de más de ${Fmt.dec(v.gapThresholdMs, 0)} ms"
        return Check.of(max <= MAX_FRAME_GAP_MS, id, title, detail)
    }

    /** `KEY_I_FRAME_INTERVAL` = `VIDEO_ARGS.FrameInterval` en segundos (QDLink: SC/managers/a.java:584). */
    private fun idr(a: RunAnalysis): Check {
        val gop = a.simConfig.videoArgs.frameInterval
        val id = "idr"
        val title = "IDR periódicos: como mucho cada 2 x FrameInterval s"
        val v = a.video
        if (gop <= 0) return Check.skip(id, title, "FrameInterval = 0")
        if (v.frames == 0) return Check.skip(id, title, "no hubo vídeo")
        val span = v.spanMs ?: 0L
        if (span < 2_000L * gop + 1_000) return Check.skip(id, title, "vídeo demasiado corto (${Fmt.secs(span)}) para FrameInterval $gop s")
        if (v.idr == 0) return Check.fail(id, title, "ningún IDR")
        val limit = 2_000L * gop + 500
        val worst = (v.idrIntervalsMs + listOfNotNull(v.lastIdrToLastFrameMs)).maxOrNull() ?: 0L
        val detail = if (v.idrIntervalsMs.isEmpty()) {
            "un solo IDR"
        } else {
            "cada ${v.idrIntervalsMs.min()}-${v.idrIntervalsMs.max()} ms (media ${Fmt.dec(v.idrIntervalsMs.average(), 0)}), " +
                "${v.idrIntervalsFrames.min()}-${v.idrIntervalsFrames.max()} frames"
        }
        return Check.of(worst <= limit, id, title, if (worst <= limit) detail else "$detail; $worst ms sin IDR (límite $limit)")
    }

    private fun touch(a: RunAnalysis): Check {
        val id = "tactil"
        val title = "Guion táctil enviado completo"
        val s = a.script
        if (s.script.isEmpty) return Check.skip(id, title, "sin guion")
        if (a.recorder.streamingAtMs == null) return Check.skip(id, title, "el handshake no llegó a VIDEO_CTRL")
        if (s.startedAtMs == null) return Check.fail(id, title, "no llegó a empezar (${a.stopReason.text})")
        val failed = s.results.firstOrNull { !it.ok }
        val detail = "${s.results.size}/${s.script.steps.size} pasos, ${a.sentTouches.size} mensajes táctiles" +
            if (s.startedWithoutVideo) " (empezó sin vídeo)" else ""
        return when {
            failed != null -> Check.fail(id, title, "$detail; falló '${failed.step.text}' ${failed.detail}")
            !s.completed -> Check.fail(id, title, "$detail; no terminó (${a.stopReason.text})")
            else -> Check.pass(id, title, detail)
        }
    }

    private fun unexpected(a: RunAnalysis): Check {
        val id = "inesperados"
        val title = "Nada inesperado del teléfono (bytes basura, msgType raros, !BIN extra)"
        if (!a.connected) return Check.skip(id, title, "no hubo conexión")
        val list = a.report.unexpected
        return Check.of(list.isEmpty(), id, title, if (list.isEmpty()) "nada" else "${list.size}: ${list.take(3).joinToString("; ")}")
    }

    private fun session(a: RunAnalysis): Check {
        val id = "sesion"
        val title = "La sesión aguanta hasta el final de la prueba"
        if (!a.connected) return Check.skip(id, title, "no hubo conexión")
        return when (a.stopReason) {
            StopReason.DURATION, StopReason.USER -> Check.pass(id, title, "${a.stopReason.text} a los ${Fmt.secs(a.endMs)}")
            else -> Check.fail(id, title, "a los ${Fmt.secs(a.recorder.closedAtMs ?: a.endMs)}: ${a.recorder.closeReason ?: a.stopReason.text}")
        }
    }
}
