package dev.qdauto.carsim.report

import dev.qdauto.carsim.Fmt
import dev.qdauto.carsim.RunClock
import dev.qdauto.carsim.run.ExtensionResult
import dev.qdauto.carsim.run.RunAnalysis
import dev.qdauto.core.json.Json
import dev.qdauto.core.json.JsonArray
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.json.JsonValue
import dev.qdauto.core.json.JsonWriter
import dev.qdauto.core.sim.SentTouch
import dev.qdauto.core.util.Hex
import java.io.File
import java.time.Instant

/** Informe completo en JSON (`--report`): todo lo del informe de texto sin recortar. */
object JsonReport {
    fun write(file: File, a: RunAnalysis, extra: ExtensionResult?, checks: List<Check>, clock: RunClock) {
        file.writeText(PrettyJson.write(Json.toValue(build(a, extra, checks, clock))) + "\n", Charsets.UTF_8)
    }

    fun build(a: RunAnalysis, extra: ExtensionResult?, checks: List<Check>, clock: RunClock): Map<String, Any?> {
        val r = a.report
        val rec = a.recorder
        val v = a.video
        val va = a.simConfig.videoArgs
        return linkedMapOf(
            "herramienta" to "carsim",
            "modo" to a.settings.mode.label,
            "resultado" to if (checks.any { it.status == CheckStatus.FAIL }) "FAIL" else "PASS",
            "inicio" to Instant.ofEpochMilli(clock.startEpochMillis).toString(),
            "duracionMs" to a.endMs,
            "fin" to a.stopReason.text,
            "comprobaciones" to checks.map {
                linkedMapOf("id" to it.id, "titulo" to it.title, "estado" to it.status.name, "detalle" to it.detail)
            },
            "configuracion" to linkedMapOf(
                "destinos" to a.settings.targets.map { linkedMapOf("ip" to it.address.hostAddress, "descripcion" to it.label) },
                "puertoUdpTelefono" to a.settings.phonePort,
                "puertoUdpCoche" to a.settings.carPort,
                "carInfo" to a.simConfig.carInfo.toPara(),
                "videoArgs" to linkedMapOf(
                    "Width" to (va.width ?: a.simConfig.carInfo.carWidth),
                    "Height" to (va.height ?: a.simConfig.carInfo.carHeight),
                    "EncodingType" to va.encodingType,
                    "FrameRate" to va.frameRate,
                    "BitRate" to va.bitRate,
                    "FrameInterval" to va.frameInterval,
                ),
                "duracionMs" to a.settings.durationMs,
                "guion" to a.settings.script.steps.map { it.text },
                "video" to a.savedVideo?.path,
            ),
            "cronologia" to rec.timeline.map { linkedMapOf("tMs" to it.tMs, "evento" to it.text) },
            "ack" to linkedMapOf(
                "tMs" to rec.ackAtMs,
                "de" to r.ackFrom?.let { Fmt.address(it) },
                "json" to r.ackJson,
                "mirrorPort" to r.mirrorPort,
                "broadcastsEnviados" to a.totalBroadcasts,
            ),
            "conexion" to linkedMapOf("con" to r.connectedTo, "tMs" to rec.connectedAtMs, "cierre" to r.closeReason, "cierreMs" to rec.closedAtMs),
            "appStatus" to rec.appStatuses.map { linkedMapOf("tMs" to it.tMs, "sdk" to it.sdkInt, "errores" to it.errors) },
            "mensajesTelefono" to rec.phoneMessages.map { linkedMapOf("tMs" to it.tMs, "msgType" to it.msgType, "tipo" to it.kind, "json" to it.json) },
            "conteoMensajes" to r.phoneMessageCounts,
            "ordenMensajes" to r.phoneMessageOrder,
            "heartbeats" to linkedMapOf(
                "n" to rec.heartbeatTimesMs.size,
                "tMs" to rec.heartbeatTimesMs,
                "intervalosMs" to rec.heartbeatTimesMs.zipWithNext { x, y -> y - x },
            ),
            "video" to linkedMapOf(
                "mensajes" to v.messages,
                "spsPps" to v.configs,
                "idr" to v.idr,
                "p" to v.p,
                "otros" to v.other,
                "bytes" to v.payloadBytes,
                "primerTipo" to r.firstVideoKind?.name,
                "primerFrameMs" to v.firstFrameMs,
                "ultimoFrameMs" to v.lastFrameMs,
                "fpsMedio" to v.fpsAvg,
                "fpsMinPorSegundo" to v.fpsMin,
                "fpsMaxPorSegundo" to v.fpsMax,
                "segundosCompletos" to v.fullSeconds,
                "kbpsMedio" to v.kbpsAvg,
                "kbpsMaxPorSegundo" to v.kbpsMax,
                "huecoMaxMs" to v.maxGapMs,
                "huecoMaxEnMs" to v.maxGapAtMs,
                "umbralHuecoMs" to v.gapThresholdMs,
                "huecosSobreUmbral" to v.gapsOverThreshold,
                "intervalosIdrMs" to v.idrIntervalsMs,
                "intervalosIdrFrames" to v.idrIntervalsFrames,
                "cabeceras" to v.headers.map { (p, n) ->
                    linkedMapOf(
                        "width" to p.width, "height" to p.height, "angle" to p.angle, "orientation" to p.orientation,
                        "encodingType" to p.encodingType, "fps" to p.fps, "bitrate" to p.bitrate, "gop" to p.gop,
                        "appType" to p.appType, "mensajes" to n,
                    )
                },
                "valido" to r.videoValid,
                "erroresTotal" to r.videoErrorCount,
                "errores" to r.videoErrors,
                "mensajeMaxBytes" to r.videoMaxMessageBytes,
                "mensajesGrandes" to r.videoLargeMessages,
                "umbralGrandeBytes" to a.simConfig.largeMessageBytes,
                "limiteCocheBytes" to a.simConfig.receiverLimitBytes,
                "cuelgue" to r.receiverHang?.let {
                    linkedMapOf("tMs" to rec.hangAtMs, "mensaje" to it.messageIndex, "bytes" to it.messageBytes, "limiteBytes" to it.limitBytes, "sinLeerMs" to it.hangMs)
                },
                "spsPps" to r.codecConfigs.map {
                    linkedMapOf(
                        "mensaje" to it.messageIndex, "primero" to it.first, "desdeAnteriorMs" to it.sinceLastMs, "pedido" to it.requested,
                        "identico" to it.sameAsPrevious, "seguidoDe" to it.followedBy?.name, "veredicto" to it.verdict.name,
                    )
                },
            ),
            "decodificacion" to a.decode.let { d ->
                linkedMapOf(
                    "pedida" to d.requested,
                    "ffmpeg" to d.ffmpeg?.path,
                    "omitida" to d.skipReason,
                    "frames" to d.result?.frames,
                    "codigo" to d.result?.exitCode,
                    "ms" to d.result?.elapsedMs,
                    "fallo" to d.result?.failure,
                    "errores" to d.result?.errorLines,
                )
            },
            "sps" to a.sps.map { s ->
                val info = s.info
                linkedMapOf(
                    "mensaje" to s.segment.messageIndex,
                    "tMs" to s.segment.tMs,
                    "hex" to s.nal?.let { Hex.encode(it, separator = "") },
                    "error" to s.error,
                    "descripcion" to info?.describe(),
                    "profileIdc" to info?.profileIdc,
                    "perfil" to info?.profileName,
                    "constraintSet" to info?.constraintText,
                    "levelIdc" to info?.levelIdc,
                    "nivel" to info?.levelName,
                    "chroma" to info?.chromaName,
                    "bitDepth" to info?.bitDepthLuma,
                    "codificado" to info?.let { Fmt.size(it.codedWidth, it.codedHeight) },
                    "recorte" to info?.let { linkedMapOf("izq" to it.cropLeft, "der" to it.cropRight, "arriba" to it.cropTop, "abajo" to it.cropBottom) },
                    "width" to info?.width,
                    "height" to info?.height,
                    "frameMbsOnly" to info?.frameMbsOnly,
                    "pocType" to info?.picOrderCntType,
                    "refFrames" to info?.maxNumRefFrames,
                    "vuiFps" to info?.vui?.fps,
                    "reorden" to info?.vui?.maxNumReorderFrames,
                    "cabeceras" to s.segment.headerSizes.map { (d, n) -> linkedMapOf("width" to d.width, "height" to d.height, "mensajes" to n) },
                )
            },
            "tactil" to linkedMapOf(
                "guion" to a.script.script.origin,
                "inicioMs" to a.script.startedAtMs,
                "sinVideo" to a.script.startedWithoutVideo,
                "completo" to a.script.completed,
                "pasos" to a.script.results.map { linkedMapOf("tMs" to it.tMs, "paso" to it.step.text, "ok" to it.ok, "detalle" to it.detail) },
                "mensajes" to a.sentTouches.map(::touchJson),
            ),
            "inesperados" to r.unexpected,
            "erroresHandshake" to r.handshakeErrors,
            "extra" to extra?.let { linkedMapOf("titulo" to it.title) + it.json },
        )
    }

    fun touchJson(t: SentTouch): Map<String, Any?> = linkedMapOf(
        "action" to t.action,
        "dedos" to t.pointers.map { p -> linkedMapOf("id" to p.id, "accion" to p.action, "x" to finite(p.x), "y" to finite(p.y)) },
    )

    /** JSON no admite NaN ni infinitos: esos van como texto. */
    private fun finite(f: Float): Any = if (f.isFinite()) f else f.toString()
}

/** JSON con sangría de dos espacios; los arrays de valores simples van en una línea. */
object PrettyJson {
    fun write(v: JsonValue): String = StringBuilder().also { append(it, v, 0) }.toString()

    private fun append(sb: StringBuilder, v: JsonValue, depth: Int) {
        when (v) {
            is JsonObject -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var i = 0
                for ((key, value) in v) {
                    indent(sb, depth + 1)
                    JsonWriter.quote(sb, key)
                    sb.append(": ")
                    append(sb, value, depth + 1)
                    if (++i < v.size) sb.append(',')
                    sb.append('\n')
                }
                indent(sb, depth)
                sb.append('}')
            }
            is JsonArray -> {
                if (v.isEmpty() || v.none { it is JsonObject || it is JsonArray }) {
                    JsonWriter.append(sb, v)
                    return
                }
                sb.append("[\n")
                v.forEachIndexed { i, item ->
                    indent(sb, depth + 1)
                    append(sb, item, depth + 1)
                    if (i < v.size - 1) sb.append(',')
                    sb.append('\n')
                }
                indent(sb, depth)
                sb.append(']')
            }
            else -> JsonWriter.append(sb, v)
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        repeat(depth) { sb.append("  ") }
    }
}
