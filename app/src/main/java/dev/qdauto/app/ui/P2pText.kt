package dev.qdauto.app.ui

import dev.qdauto.app.p2p.GroupPhase
import dev.qdauto.app.p2p.P2pBlocker
import dev.qdauto.app.p2p.P2pCodes
import dev.qdauto.app.p2p.P2pGroupInfo
import dev.qdauto.app.p2p.P2pStatus
import dev.qdauto.app.p2p.PeerClassifier
import dev.qdauto.app.p2p.PeerView
import dev.qdauto.app.p2p.StoredCar
import dev.qdauto.app.util.Clock

/** Textos del modo Wi-Fi Direct (en español). `now` es `SystemClock.elapsedRealtime()`, el reloj de las fases. */
object P2pText {
    fun state(p: P2pStatus, now: Long): String = buildString {
        val p2p = when {
            !p.supported -> "no disponible en este móvil"
            p.enabled -> "activado"
            else -> "desactivado por Android (o esperando a que se active)"
        }
        appendLine("Wi-Fi Direct $p2p · búsqueda ${p.discovery.label}" + if (p.searching) "" else " (sin pedir)")
        p.discoveryFailure?.let { appendLine("La búsqueda falla: $it") }
        appendLine("Fase: ${phase(p.phase, now)}")
        p.thisDevice?.let { appendLine("Este móvil: $it, el nombre que verá el coche") }
        appendLine("Último coche: ${storedCar(p.storedCar)}")
        append(
            "Conexión automática al último coche: " + when {
                !p.autoConnect -> "no"
                p.autoPaused -> "en pausa (pulsa «Buscar»)"
                p.autoRetryInMs > 0 -> "sí, siguiente intento en ${Clock.duration(p.autoRetryInMs + 999)}"
                else -> "sí"
            },
        )
        p.lastFailure?.let { append("\nÚltimo fallo: $it") }
        if (p.timeline.isNotEmpty()) append("\nTiempos: ${p.timeline}")
    }

    fun phase(ph: GroupPhase, now: Long): String = when (ph) {
        GroupPhase.Idle -> "sin grupo"
        is GroupPhase.Joining -> "uniéndose a «${ph.target.name}» (${ph.target.address}) desde hace " +
            "${Clock.duration(now - ph.sinceMs)}, máx. ${(ph.deadlineMs - ph.sinceMs) / 1000} s" + if (ph.auto) " · automática" else ""
        is GroupPhase.Formed -> "en grupo con «${ph.target?.name ?: ph.group.ownerName ?: "?"}» desde hace ${Clock.duration(now - ph.sinceMs)}"
        is GroupPhase.Removing -> "quitando el grupo" + (ph.next?.let { " para conectar con «${it.name}»" } ?: "")
    }

    fun storedCar(car: StoredCar?): String =
        car?.let { "«${it.name}» ${it.address} (${Clock.ago(it.savedAtMillis)})" } ?: "ninguno"

    fun group(g: P2pGroupInfo?): String {
        if (g == null) return "Grupo: ninguno"
        val owner = if (g.phoneIsOwner) "ESTE MÓVIL (QDLink espera que lo sea el coche)" else "«${g.ownerName ?: "?"}» ${g.ownerAddress.orEmpty()}"
        return "Grupo «${g.networkName ?: "?"}» · ${g.frequencyMhz?.let { "$it MHz" } ?: "? MHz"} · netId ${g.netId ?: "?"}\n" +
            "Dueño (GO): $owner · IP ${g.ownerIp ?: "?"}\n" +
            "Móvil: ${g.phoneAddresses.joinToString().ifEmpty { "sin IPv4 todavía" }} en ${g.interfaceName ?: "?"}\n" +
            "Clientes: ${g.clients.joinToString().ifEmpty { "—" }}"
    }

    /** Una línea para el panel de red: interfaz, IPs y dueño del grupo. */
    fun networkLine(p: P2pStatus, now: Long): String {
        val g = p.group ?: return "Wi-Fi Direct: ${phase(p.phase, now)}" + (p.blocker?.let { " · ${it.message}" } ?: "")
        val role = if (g.phoneIsOwner) "móvil GO ${g.ownerIp ?: "?"}" else "coche GO ${g.ownerIp ?: "?"}"
        return "Wi-Fi Direct: grupo con «${(p.phase as? GroupPhase.Formed)?.target?.name ?: g.ownerName ?: "?"}» · $role · móvil " +
            g.phoneAddresses.joinToString().ifEmpty { "sin IPv4" } + " en ${g.interfaceName ?: "?"}" +
            (g.frequencyMhz?.let { " · $it MHz" } ?: "")
    }

    fun peerLine(v: PeerView): String = buildString {
        val p = v.peer
        append("«${p.name.ifBlank { "(sin nombre)" }}» ${p.address}")
        if (v.isLastCar) append(" · ÚLTIMO COCHE")
        append("\n  ${p.status.label}")
        if (p.isGroupOwner) append(" · es dueño de un grupo (GO)")
        append(" · ${PeerClassifier.deviceType(p.primaryType)}")
        if (p.wps.isNotEmpty()) append(" · WPS ${p.wps}")
        if (v.services.isNotEmpty()) {
            append("\n  servicios: " + v.services.joinToString(" | ") { "${it.kind.label} ${it.text}" })
        }
        append("\n  ${v.verdict}")
    }

    /** Para la notificación (con el enlace buscando). */
    fun short(p: P2pStatus): String = when (val ph = p.phase) {
        is GroupPhase.Joining -> "Wi-Fi Direct: uniéndose a «${ph.target.name}»"
        is GroupPhase.Formed -> "Wi-Fi Direct: en grupo con «${ph.target?.name ?: ph.group.ownerName ?: "?"}»; esperando el anuncio"
        is GroupPhase.Removing -> "Wi-Fi Direct: quitando el grupo"
        GroupPhase.Idle -> p.blocker?.let { "Wi-Fi Direct en pausa: ${it.message}" }
            ?: "Wi-Fi Direct: buscando (${p.peers.size} dispositivos cerca)"
    }

    /** Qué hacer con cada impedimento (lo que dice el botón de arreglo). */
    fun hint(b: P2pBlocker): String = when (b) {
        P2pBlocker.NO_NEARBY_PERMISSION ->
            "Pulsa «Permiso…» y elige «Permitir». Si Android ya no pregunta, se abren los ajustes de la app: " +
                "Permisos ▸ Dispositivos cercanos ▸ Permitir."
        P2pBlocker.NO_LOCATION_PERMISSION -> "Pulsa «Permiso…» y elige la ubicación precisa."
        P2pBlocker.LOCATION_OFF -> "Pulsa «Ubicación…» y actívala."
        P2pBlocker.WIFI_OFF -> "Pulsa «Wi-Fi…» y actívala."
        P2pBlocker.HOTSPOT_ON -> "Pulsa «Zona Wi-Fi…» y apágala (Conexiones ▸ Zona Wi-Fi y anclaje a red)."
        P2pBlocker.UNSUPPORTED, P2pBlocker.CHANNEL_LOST -> ""
    }

    /** Todo (exportación). */
    fun full(p: P2pStatus?, now: Long): String {
        if (p == null) return "No se usa (modo punto de acceso)"
        return buildString {
            p.blocker?.let { appendLine("IMPIDE BUSCAR: ${it.message}") }
            appendLine(state(p, now))
            appendLine(group(p.group))
            appendLine("Capacidades: ${p.capabilities}")
            appendLine("Zona Wi-Fi: ${p.hotspotState?.let(P2pCodes::apState) ?: "sin aviso"} · servicios vistos: ${p.servicesSeen}")
            append("Dispositivos (${p.peers.size}; FAILED/UNAVAILABLE ocultos: ${p.hiddenPeers}):")
            if (p.peers.isEmpty()) append(" ninguno")
            p.peers.forEach { append("\n").append(peerLine(it)) }
        }
    }
}
