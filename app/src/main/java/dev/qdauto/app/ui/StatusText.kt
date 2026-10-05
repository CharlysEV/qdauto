package dev.qdauto.app.ui

import android.os.SystemClock
import dev.qdauto.app.link.LinkStatus
import dev.qdauto.app.touch.TouchMapping
import dev.qdauto.app.touch.TouchTracker
import dev.qdauto.app.util.Clock
import dev.qdauto.core.session.LinkState
import dev.qdauto.core.session.SessionState
import dev.qdauto.core.wire.UdpCodec
import java.util.Locale

/** Textos de estado (en español) para la actividad, la notificación y la exportación. */
object StatusText {
    fun linkTitle(s: LinkStatus): String = when {
        s.session?.state == SessionState.STREAMING && s.linkState == LinkState.CONNECTED -> "enviando vídeo"
        s.linkState == LinkState.CONNECTED -> "conectado"
        s.linkState == LinkState.CONNECTING -> "conectando"
        s.linkState == LinkState.SEARCHING -> "buscando coche"
        s.discoveryError != null -> "error de red"
        else -> "detenido"
    }

    fun notificationLine(s: LinkStatus): String {
        val st = s.session?.stats
        return when (s.linkState) {
            LinkState.CONNECTED -> "${s.session?.remote ?: "coche"} · " +
                if (st != null) fmt("%.1f fps · %.0f kbps · cola %d", st.fps, st.kbps, st.videoQueueFrames) else "sesión abierta"
            LinkState.CONNECTING -> "Esperando la conexión TCP de ${s.attemptCar ?: "?"} en el puerto ${s.attemptPort ?: "?"}"
            LinkState.SEARCHING -> s.p2p?.let { P2pText.short(it) + backoff(s) }
                ?: ("Escuchando UDP ${UdpCodec.PHONE_PORT} · " + ips(s) + backoff(s))
            LinkState.STOPPED -> s.discoveryError ?: "Detenido"
        }
    }

    fun network(s: LinkStatus): String = buildString {
        appendLine("Modo: ${s.mode.label}")
        appendLine("Enlace: ${linkTitle(s)}" + (s.discoveryError?.let { " · $it" } ?: ""))
        appendLine("IPv4: " + ips(s))
        s.p2p?.let { appendLine(P2pText.networkLine(it, SystemClock.elapsedRealtime())) }
        appendLine(s.wifi)
        val port = if (s.tcpPortSetting == 0) "aleatorio 10001-65535 (como QDLink)" else s.tcpPortSetting.toString()
        append("Puerto TCP: $port")
        if (s.attemptPort != null && s.linkState != LinkState.SEARCHING) append(" · abierto: ${s.attemptPort}")
        appendLine()
        if (s.linkState == LinkState.CONNECTING) {
            appendLine("Esperando a ${s.attemptCar} desde hace ${Clock.duration(System.currentTimeMillis() - s.attemptSinceMillis)}")
        }
        val filter = s.carFilter.ifBlank { "cualquiera" }
        val auto = when {
            // Wi-Fi Direct: tras unirse al grupo se contesta solo al anuncio que llega por él (spec 05 §8.2 E.14).
            s.p2p != null -> "tras unirse, al anuncio del coche por Wi-Fi Direct (coche: $filter)"
            s.autoConnect -> "sí (coche: $filter)"
            else -> "no"
        }
        appendLine("Conexión automática: $auto" + backoff(s) + (if (s.failures > 0) " · fallos seguidos ${s.failures}" else ""))
        if (s.bindWifiIgnored) appendLine("«Coche como punto de acceso»: ignorado en modo Wi-Fi Direct")
        if (s.boundTo != null) appendLine("Sockets vinculados a la Wi-Fi: ${s.boundTo}")
        append("Bloqueos: ${s.locks}")
    }

    fun session(s: LinkStatus): String {
        val v = s.session ?: return "Sin sesión todavía"
        return buildString {
            val age = if (v.startedAtMillis > 0) Clock.duration(System.currentTimeMillis() - v.startedAtMillis) else "—"
            appendLine("Sesión ${v.id} · ${v.state} · coche ${v.remote ?: "?"} · $age")
            val c = v.carInfo
            if (c == null) {
                appendLine("CAR_INFO: —")
            } else {
                appendLine(
                    "CAR_INFO: ${c.carWidth}×${c.carHeight} · CarType ${c.carType} · Version ${c.version} · Platform ${c.platform} " +
                        "${c.platformVersion} · CarFactory ${c.carFactory} · HUFactory ${c.huFactory} · MirrorTypeReq ${c.mirrorTypeReq} · " +
                        "legal_app_watch ${c.legalAppWatch}" + (c.stoppedAt?.let { " · QDLink pararía en $it" } ?: ""),
                )
                v.carInfoRaw?.let { appendLine("  $it") }
            }
            val a = v.videoArgs
            if (a == null) {
                appendLine("VIDEO_ARGS: —")
            } else {
                appendLine(
                    "VIDEO_ARGS: ${a.width}×${a.height} · EncodingType ${a.encodingType} · ${a.frameRate} fps · ${a.bitRate} bps · " +
                        "FrameInterval ${a.frameInterval}" + (a.stoppedAt?.let { " · QDLink pararía en $it" } ?: ""),
                )
                v.videoArgsRaw?.let { appendLine("  $it") }
            }
            v.phoneInfo?.let { p ->
                appendLine(
                    "PHONE_INFO: Phone ${p.phoneWidth}×${p.phoneHeight} · Mirror ${p.mirrorWidth}×${p.mirrorHeight} · " +
                        "InApp ${p.phoneWidthInApp}×${p.phoneHeightInApp} · Version ${p.version} · ${p.phoneBrand} ${p.phoneModel}",
                )
            }
            val h = v.videoParams
            appendLine(
                "Cabecera de vídeo: ${h.width}×${h.height} · appType ${h.appType} · ángulo ${h.angle} · orientación ${h.orientation} · " +
                    "enc ${h.encodingType} · ${h.fps} fps · ${h.bitrate} bps · GOP ${h.gop}",
            )
            val st = v.stats
            appendLine(
                fmt(
                    "Envío: %.1f fps · %.0f kbps · frames %d · IDR %d · SPS/PPS %d · cola %d (%s) · control %d · descartados %d · " +
                        "rechazados %d · peticiones IDR %d",
                    st.fps, st.kbps, st.videoFramesSent, st.keyframesSent, st.codecConfigsSent, st.videoQueueFrames,
                    bytes(st.videoQueueBytes), st.controlQueueDepth, st.videoFramesDropped, st.videoFramesRejected,
                    st.keyframeRequests,
                ),
            )
            appendLine(
                "Coche: último dato hace ${st.lastReceiveAgoMs} ms · hueco máx ${st.maxCarGapMs} ms · heartbeats ${st.carHeartbeats}" +
                    (st.lastCarHeartbeatIntervalMs?.let { " (cada $it ms)" } ?: "") + " · táctiles ${st.touchEvents} · basura ${st.garbageBytes} B",
            )
            append(
                "Tráfico: enviado ${bytes(st.bytesSent)} en ${st.messagesSent} mensajes · recibido ${bytes(st.bytesReceived)} " +
                    "en ${st.messagesReceived} mensajes",
            )
            v.closeReason?.let { append("\nCierre: $it") }
        }
    }

    fun video(s: LinkStatus): String {
        val v = s.video ?: return "Encoder: parado (arranca con VIDEO_CTRL PlayStatus 1)"
        return buildString {
            appendLine(
                "Encoder: ${v.state}" + (v.codecName?.let { " · $it" } ?: "") +
                    (v.hardware?.let { if (it) " (hardware)" else " (software)" } ?: "") + (v.step?.let { " · configuración $it" } ?: ""),
            )
            appendLine(
                "Tamaño ${v.size ?: "—"} · ${v.fps ?: "—"} fps · ${(v.bitrate ?: 0) / 1000} kbps · GOP ${v.gopSeconds ?: "—"} s · " +
                    "${v.profile ?: "—"} ${v.level ?: ""} · ${v.bitrateMode ?: "—"}",
            )
            appendLine(
                fmt(
                    "Dibujo: %s · %.1f fps · %d frames · %.1f ms/frame · tarde %d",
                    v.renderMode ?: "—", v.renderFps, v.rendered, v.avgDrawMs, v.renderLate,
                ),
            )
            append(
                fmt(
                    "Encoder: %.1f fps · %d frames · IDR %d · SPS/PPS %d · medio %s · máx %s · último IDR %s · no aceptados %d · " +
                        "IDR pedidos %d",
                    v.encodeFps, v.encoded, v.keyframes, v.configs, bytes(v.avgFrameBytes), bytes(v.maxFrameBytes),
                    bytes(v.lastKeyframeBytes), v.rejected, v.keyframeRequests,
                ),
            )
            if (v.notes.isNotEmpty()) append("\nNotas: " + v.notes.joinToString(" · "))
            if (v.failures > 0) append("\nFallos: ${v.failures}")
            v.lastError?.let { append("\nÚltimo error: $it") }
        }
    }

    fun touch(t: TouchTracker.Snapshot): String = buildString {
        val sp = t.space
        appendLine(
            "Interpretación: ${t.resolved.label}" + (if (t.mode == TouchMapping.AUTO) " (automática)" else "") +
                " · frame ${sp.frameW}×${sp.frameH} · CAR_INFO ${sp.carW}×${sp.carH} · teléfono ${sp.phoneLong}×${sp.phoneShort}",
        )
        val e = t.extent
        if (e.maxX >= e.minX) {
            appendLine(fmt("Rango visto: x %.1f…%.1f · y %.1f…%.1f · eventos %d", e.minX, e.maxX, e.minY, e.maxY, t.events))
        }
        if (t.history.isEmpty()) append("Sin toques todavía") else append(t.history.joinToString("\n"))
    }

    fun messages(s: LinkStatus): String = buildString {
        appendLine("Última tecla: ${s.lastKey ?: "—"}")
        appendLine("Último msgType 13: ${s.lastApp ?: "—"}")
        appendLine("Último no reconocido: ${s.lastUnknown ?: "—"}")
        appendLine("Último cierre: ${s.lastClose ?: "—"}")
        appendLine("Último error: ${s.lastError ?: "—"}")
        append("Eventos:")
        if (s.events.isEmpty()) append(" —")
        s.events.forEach { append("\n  ").append(it) }
    }

    /** Todo junto (exportación). */
    fun full(s: LinkStatus, touch: TouchTracker.Snapshot?): String = listOf(
        "== Red ==\n" + network(s),
        "== Wi-Fi Direct ==\n" + P2pText.full(s.p2p, SystemClock.elapsedRealtime()),
        "== Coches ==\n" + cars(s),
        "== Sesión ==\n" + session(s),
        "== Vídeo ==\n" + video(s),
        "== Táctil ==\n" + (touch?.let(::touch) ?: "—"),
        "== Mensajes ==\n" + messages(s),
    ).joinToString("\n\n")

    fun cars(s: LinkStatus): String = if (s.cars.isEmpty()) {
        "Ninguno (esperando un broadcast UDP en ${UdpCodec.PHONE_PORT})"
    } else {
        s.cars.joinToString("\n") { c -> carLine(c.name, c.uuid, c.host, c.sourcePort, c.lastSeenMillis, c.count) + "\n  ${c.rawJson}" }
    }

    fun carLine(name: String, uuid: String, host: String, port: Int, lastSeen: Long, count: Int): String =
        "'$name' · $uuid · $host:$port · ${Clock.ago(lastSeen)} (${count}×)"

    private fun ips(s: LinkStatus): String = if (s.interfaces.isEmpty()) "ninguna" else s.interfaces.joinToString(" · ")

    /** En Wi-Fi Direct la espera se aplica siempre (el ACK tras la pulsación es automático). */
    private fun backoff(s: LinkStatus): String =
        if (s.backoffMs > 0 && (s.autoConnect || s.p2p != null)) " · espera ${Clock.duration(s.backoffMs + 999)}" else ""

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> fmt("%.1f KiB", n / 1024.0)
        else -> fmt("%.1f MiB", n / (1024.0 * 1024.0))
    }

    private fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.ROOT, pattern, *args)
}
