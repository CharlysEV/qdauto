package dev.qdauto.app.p2p

import dev.qdauto.app.settings.P2pSettings

/** Lo que el controlador tiene que hacer con `WifiP2pManager` (o avisar) tras un evento. */
sealed interface P2pEffect {
    data object DiscoverPeers : P2pEffect

    /** `requestPeers` y, si ningún peer está INVITED, `discoverPeers` (bucle de QDLink, wifidirect/c.java:542-566). */
    data object RefreshPeersThenDiscover : P2pEffect

    data object StopDiscovery : P2pEffect

    /** Peticiones DNS-SD y UPnP de todos los servicios y `discoverServices` (solo diagnóstico). */
    data object DiscoverServices : P2pEffect

    data object ClearServiceRequests : P2pEffect

    data class Connect(val target: P2pTarget, val plan: ConnectPlan, val auto: Boolean, val joinId: Int) : P2pEffect

    data object CancelConnect : P2pEffect

    data object RemoveGroup : P2pEffect

    data class RememberCar(val target: P2pTarget) : P2pEffect

    /** Aviso para el panel de eventos (y el log). */
    data class Note(val text: String, val warning: Boolean = false) : P2pEffect

    data class GroupFormed(val group: P2pGroupInfo) : P2pEffect

    data class GroupLost(val group: P2pGroupInfo) : P2pEffect
}

/** Un `CONNECTION_CHANGED` ya traducido: [group] no nulo = `groupFormed`; [failed] = `NetworkInfo` en FAILED. */
data class P2pConnection(val group: P2pGroupInfo?, val failed: Boolean = false)

/**
 * Decisiones del modo Wi-Fi Direct, sin Android: la máquina de estados de QDLink (spec 05 §2.6) con las mejoras de
 * §8.2: reutilizar el grupo con el mismo coche, conservarlo si no llega el TCP, avisos si no llega el anuncio y
 * conexión automática al último coche. Cada evento devuelve los efectos que hay que ejecutar.
 * Un solo hilo (el del controlador); los tiempos `now` son de un reloj monótono.
 */
class P2pMachine(settings: P2pSettings, storedCar: StoredCar?, private val timeline: P2pTimeline) {
    var settings: P2pSettings = settings

    var storedCar: StoredCar? = storedCar
        private set

    /** P2P habilitado por Android (el `C` de QDLink). */
    var enabled = false
        private set

    var blocker: P2pBlocker? = null
        private set

    /** Búsqueda pedida (el `A` de QDLink). */
    var searching = false
        private set

    var discovery = DiscoveryPhase.STOPPED
        private set

    var peers: List<P2pPeer> = emptyList()
        private set

    var phase: GroupPhase = GroupPhase.Idle
        private set

    var lastFailure: String? = null
        private set

    /** Último fallo de `discoverPeers` (se borra con el siguiente éxito), para verlo en la pantalla sin el log. */
    var discoveryFailure: String? = null
        private set

    /** «Desconectar» pausa la búsqueda y la conexión automática hasta «Buscar» o una pulsación. */
    var autoPaused = false
        private set

    var autoFailures = 0
        private set

    private var autoNotBefore = 0L
    private var servicesPending = false
    private var lastRelaunch: Long? = null
    private var requestedAt = 0L
    private var warnedLevel = 0
    private var joinSeq = 0

    private val canAct: Boolean get() = enabled && blocker == null
    private val canSearch: Boolean get() = canAct && searching

    fun autoRetryInMs(now: Long): Long = (autoNotBefore - now).coerceAtLeast(0)

    /** Al arrancar el modo se busca solo (spec 05 §8.2 B.6). */
    fun start(): List<P2pEffect> {
        searching = true
        servicesPending = settings.serviceDiscovery
        return emptyList()
    }

    fun rememberCar(car: StoredCar?) {
        storedCar = car
    }

    fun onBlocker(b: P2pBlocker?): List<P2pEffect> {
        if (b == blocker) return emptyList()
        val old = blocker
        blocker = b
        return when {
            b != null -> listOf(P2pEffect.Note(b.message, warning = true))
            old != null -> listOf(P2pEffect.Note("Resuelto: ya se puede buscar y conectar"))
            else -> emptyList()
        }
    }

    fun onP2pState(on: Boolean, now: Long): List<P2pEffect> {
        if (on == enabled) return emptyList()
        enabled = on
        discovery = DiscoveryPhase.STOPPED
        if (on) {
            // QDLink: wifidirect/c.java:837-848: al habilitarse P2P quita cualquier unión o grupo anteriores.
            phase = GroupPhase.Removing(now, null, (phase as? GroupPhase.Removing)?.next)
            return listOf(
                P2pEffect.Note("Wi-Fi Direct activado; se quitan uniones y grupos anteriores"),
                P2pEffect.CancelConnect,
                P2pEffect.RemoveGroup,
            )
        }
        // QDLink: wifidirect/c.java:856-875: todo a cero y la lista vacía.
        peers = emptyList()
        return listOf(P2pEffect.Note("Android ha desactivado Wi-Fi Direct (¿Wi-Fi apagada o zona Wi-Fi encendida?)", true)) +
            dropGroup("Wi-Fi Direct se desactivó")
    }

    /** `onChannelDisconnected`: el estado real se desconoce hasta reinicializar el canal. */
    fun onChannelLost(): List<P2pEffect> {
        enabled = false
        discovery = DiscoveryPhase.STOPPED
        peers = emptyList()
        return dropGroup("Se perdió el canal")
    }

    fun onDiscoveryChanged(running: Boolean): List<P2pEffect> {
        discovery = if (running) DiscoveryPhase.RUNNING else DiscoveryPhase.STOPPED
        return emptyList()
    }

    /**
     * Resultado de `discoverPeers`. Si falla, el bucle lo vuelve a intentar (QDLink: wifidirect/c.java:399-419); solo
     * se avisa cuando cambia el motivo, para no repetir el mismo aviso cada 3 s.
     */
    fun onDiscoverResult(ok: Boolean, reason: Int): List<P2pEffect> {
        if (ok) {
            if (discovery == DiscoveryPhase.REQUESTED) discovery = DiscoveryPhase.RUNNING
            discoveryFailure = null
            return emptyList()
        }
        discovery = DiscoveryPhase.STOPPED
        val text = "discoverPeers → FALLO ${P2pCodes.failure(reason)}: ${P2pCodes.failureEs(reason)}"
        if (text == discoveryFailure) return emptyList()
        discoveryFailure = text
        return listOf(P2pEffect.Note("La búsqueda falla ($text); se reintenta cada 3 s", warning = true))
    }

    fun onPeers(list: List<P2pPeer>, now: Long): List<P2pEffect> {
        peers = list
        return maybeAutoConnect(now)
    }

    /** Respuesta de `requestPeers` en el bucle (QDLink: wifidirect/c.java:542-566 y `U()` en :721-733). */
    fun onPeersForRelaunch(list: List<P2pPeer>, now: Long): List<P2pEffect> {
        peers = list
        val auto = maybeAutoConnect(now)
        if (auto.isNotEmpty()) return auto
        if (!canSearch || phase != GroupPhase.Idle || discovery != DiscoveryPhase.STOPPED) return emptyList()
        if (list.any { it.status == PeerStatus.INVITED }) return emptyList()
        discovery = DiscoveryPhase.REQUESTED
        requestedAt = now
        return listOf(P2pEffect.DiscoverPeers)
    }

    /** Cada segundo: plazos, relanzar la búsqueda, servicios, avisos y conexión automática. */
    fun onTick(now: Long): List<P2pEffect> {
        val out = mutableListOf<P2pEffect>()
        when (val p = phase) {
            is GroupPhase.Removing -> if (now - p.sinceMs >= REMOVE_GUARD_MS) out += finishRemoval(p, now)
            is GroupPhase.Joining -> if (now >= p.deadlineMs) {
                // QDLink: wifidirect/c.java:354-366 (vence el plazo de unión → cancelConnect).
                phase = GroupPhase.Idle
                out += P2pEffect.CancelConnect
                out += joinFailed("Tiempo agotado al unirse a «${p.target.name}» (${(p.deadlineMs - p.sinceMs) / 1000} s)", now)
            }
            is GroupPhase.Formed -> out += broadcastWarning(p, now)
            GroupPhase.Idle -> Unit
        }
        // discoverPeers aceptado pero sin DISCOVERY_CHANGED: no quedarse esperando para siempre.
        if (discovery == DiscoveryPhase.REQUESTED && now - requestedAt >= REQUEST_GUARD_MS) discovery = DiscoveryPhase.STOPPED
        // QDLink: wifidirect/c.java:563-571: cada 3 s, con la búsqueda parada y sin unión ni grupo.
        val due = lastRelaunch?.let { now - it >= RELAUNCH_MS } ?: true
        if (canSearch && phase == GroupPhase.Idle && discovery == DiscoveryPhase.STOPPED && due) {
            lastRelaunch = now
            out += P2pEffect.RefreshPeersThenDiscover
        }
        // spec 05 §8.2 B.8: una vez por búsqueda y nunca durante la unión.
        if (servicesPending && settings.serviceDiscovery && canSearch && phase == GroupPhase.Idle &&
            discovery == DiscoveryPhase.RUNNING
        ) {
            servicesPending = false
            out += P2pEffect.DiscoverServices
        }
        out += maybeAutoConnect(now)
        return out
    }

    /** «Buscar» (QDLink: f0(), wifidirect/c.java:1114-1140, también con «Actualizar»). */
    fun userSearch(now: Long): List<P2pEffect> {
        searching = true
        autoPaused = false
        autoFailures = 0
        autoNotBefore = 0
        lastFailure = null
        servicesPending = settings.serviceDiscovery
        if (!canAct) return listOf(P2pEffect.Note("No se puede buscar: ${blockedReason()}", warning = true))
        when (phase) {
            GroupPhase.Idle -> Unit
            is GroupPhase.Removing -> return listOf(P2pEffect.Note("Se buscará en cuanto se quite el grupo actual"))
            else -> return listOf(P2pEffect.Note("Ya hay una unión o un grupo; pulsa «Desconectar» para buscar otro coche"))
        }
        lastRelaunch = now
        discovery = DiscoveryPhase.REQUESTED
        requestedAt = now
        return listOf(
            P2pEffect.Note("Buscando… Toca el nombre del coche (el mismo que aparece en QDLink)"),
            P2pEffect.DiscoverPeers,
        )
    }

    /** Pulsación de «Conectar» en un peer (QDLink: wifidirect/c.java:967-1000, con las mejoras de spec 05 §8.2 C.9). */
    fun userConnect(address: String, now: Long): List<P2pEffect> {
        val peer = peers.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: return listOf(P2pEffect.Note("Ese dispositivo ya no está en la lista; pulsa «Buscar»", warning = true))
        if (!canAct) return listOf(P2pEffect.Note("No se puede conectar: ${blockedReason()}", warning = true))
        autoPaused = false
        searching = true
        val target = P2pTarget(peer.name, peer.address)
        return when (val p = phase) {
            GroupPhase.Idle -> startJoin(target, auto = false, now)
            is GroupPhase.Joining ->
                if (p.target.address.equals(target.address, ignoreCase = true)) {
                    listOf(P2pEffect.Note("Ya se está conectando con «${target.name}»"))
                } else {
                    replaceGroup(null, target, now, cancel = true)
                }
            is GroupPhase.Formed ->
                if (sameDevice(p, target)) {
                    listOf(P2pEffect.Note("Ya hay un grupo con «${target.name}»: se reutiliza (QDLink lo quitaría)"))
                } else {
                    replaceGroup(p.group, target, now, cancel = false)
                }
            is GroupPhase.Removing -> {
                phase = p.copy(next = target)
                listOf(P2pEffect.Note("Se conectará con «${target.name}» en cuanto se quite el grupo actual"))
            }
        }
    }

    /** «Desconectar» (spec 05 §8.2 F.18): búsqueda, unión, grupo y peticiones de servicio fuera. */
    fun userDisconnect(now: Long): List<P2pEffect> {
        autoPaused = true
        searching = false
        servicesPending = false
        phase = when (val p = phase) {
            is GroupPhase.Formed -> GroupPhase.Removing(now, p.group, null)
            is GroupPhase.Removing -> p.copy(next = null)
            else -> GroupPhase.Removing(now, null, null)
        }
        return listOfNotNull(
            P2pEffect.Note("Desconectado a mano: búsqueda y conexión automática en pausa hasta pulsar «Buscar»"),
            P2pEffect.StopDiscovery,
            P2pEffect.ClearServiceRequests.takeIf { settings.serviceDiscovery },
            P2pEffect.CancelConnect,
            P2pEffect.RemoveGroup,
        )
    }

    /** El motor contesta a un broadcast llegado por la red P2P (QDLink: wificonnection/a.java:508-511). */
    fun onCarAccepted(): List<P2pEffect> {
        searching = false
        return if (discovery != DiscoveryPhase.STOPPED) listOf(P2pEffect.StopDiscovery) else emptyList()
    }

    /** Terminó una sesión que llegó por Wi-Fi Direct (spec 05 §8.2 F.17). */
    fun onSessionEnded(now: Long): List<P2pEffect> {
        searching = true
        servicesPending = settings.serviceDiscovery
        val p = phase as? GroupPhase.Formed ?: return emptyList()
        if (settings.keepGroup) {
            return listOf(P2pEffect.Note("Sesión terminada; se mantiene el grupo con «${nameOf(p)}» y se espera otro anuncio"))
        }
        phase = GroupPhase.Removing(now, p.group, null)
        return listOf(
            P2pEffect.Note("Sesión terminada: se quita el grupo (como QDLink) y se vuelve a buscar"),
            P2pEffect.RemoveGroup,
        )
    }

    fun onConnectResult(joinId: Int, ok: Boolean, reason: Int, now: Long): List<P2pEffect> {
        val p = phase as? GroupPhase.Joining ?: return emptyList()
        if (p.joinId != joinId) return emptyList()
        if (ok) {
            timeline.mark(P2pTimeline.Mark.CONNECT_OK)
            return emptyList()
        }
        phase = GroupPhase.Idle
        // QDLink no reintenta (wifidirect/c.java:240-253); la conexión automática espera antes de volver a probar.
        return joinFailed(
            "connect con «${p.target.name}» → FALLO ${P2pCodes.failure(reason)}: ${P2pCodes.failureEs(reason)}", now,
        )
    }

    fun onRemoveGroupResult(ok: Boolean, now: Long): List<P2pEffect> {
        val p = phase as? GroupPhase.Removing ?: return emptyList()
        // Con éxito llegará CONNECTION_CHANGED(groupFormed=false); si falla, lo normal es que no hubiera grupo.
        return if (ok) emptyList() else finishRemoval(p, now)
    }

    fun onConnection(c: P2pConnection, now: Long): List<P2pEffect> {
        val g = c.group
        if (g != null) {
            return when (val p = phase) {
                is GroupPhase.Joining -> formed(p.target, g, now, adopted = false)
                is GroupPhase.Formed -> {
                    phase = p.copy(group = g)
                    markIp(g)
                    emptyList()
                }
                GroupPhase.Idle -> formed(guessTarget(g), g, now, adopted = true)
                is GroupPhase.Removing -> emptyList()
            }
        }
        return when (val p = phase) {
            // QDLink lo trata siempre como fallo (wifidirect/c.java:761-770); aquí solo con FAILED, porque durante la
            // negociación llegan estados intermedios. El plazo y onGroupCreationFailed cubren el resto.
            is GroupPhase.Joining -> if (c.failed) {
                phase = GroupPhase.Idle
                joinFailed("La unión con «${p.target.name}» ha fallado", now)
            } else {
                emptyList()
            }
            is GroupPhase.Formed -> {
                phase = GroupPhase.Idle
                searching = true
                servicesPending = settings.serviceDiscovery
                listOf(P2pEffect.Note("Se ha deshecho el grupo con «${nameOf(p)}»", warning = true), P2pEffect.GroupLost(p.group))
            }
            is GroupPhase.Removing -> finishRemoval(p, now)
            GroupPhase.Idle -> emptyList()
        }
    }

    /** Datos del grupo de `requestGroupInfo` o de la interfaz (IP del DHCP), con el grupo ya formado. */
    fun onGroupDetails(g: P2pGroupInfo): List<P2pEffect> {
        val p = phase as? GroupPhase.Formed ?: return emptyList()
        phase = p.copy(group = g)
        markIp(g)
        return emptyList()
    }

    /** API 35+: `WifiP2pListener.onGroupCreationFailed` (spec 05 §7.3 #8). */
    fun onGroupCreationFailed(reason: Int, now: Long): List<P2pEffect> {
        val p = phase as? GroupPhase.Joining ?: return emptyList()
        phase = GroupPhase.Idle
        return joinFailed(
            "No se formó el grupo con «${p.target.name}»: ${P2pCodes.groupFailure(reason)}, ${P2pCodes.groupFailureEs(reason)}",
            now,
        )
    }

    /** API 35+: `WifiP2pListener.onGroupNegotiationRejectedByUser`. */
    fun onNegotiationRejected(now: Long): List<P2pEffect> {
        val p = phase as? GroupPhase.Joining ?: return emptyList()
        phase = GroupPhase.Idle
        return joinFailed("Conexión con «${p.target.name}» rechazada (¿había que aceptar en la pantalla del coche?)", now)
    }

    private fun startJoin(target: P2pTarget, auto: Boolean, now: Long): List<P2pEffect> {
        val timeoutMs = settings.joinTimeoutSec * 1000L
        joinSeq++
        phase = GroupPhase.Joining(target, now, now + timeoutMs, auto, joinSeq)
        lastFailure = null
        timeline.reset(target.name)
        timeline.mark(P2pTimeline.Mark.TAP)
        val plan = ConnectPlan.of(target.address, settings.goIntent, settings.wps, settings.wpsPin)
        return listOfNotNull(
            // spec 05 §8.2 B.8: las peticiones de servicio se quitan al conectar.
            P2pEffect.ClearServiceRequests.takeIf { settings.serviceDiscovery },
            // Como LINK_HISTORY de QDLink, se guarda al pulsar (QDLink: wifilink/LinkDialog.java:270-277).
            P2pEffect.RememberCar(target),
            plan.problem?.let { P2pEffect.Note(it, warning = true) },
            P2pEffect.Note("Conectando con «${target.name}» (máx. ${timeoutMs / 1000} s)…"),
            P2pEffect.Connect(target, plan, auto, joinSeq),
        )
    }

    private fun formed(target: P2pTarget?, g: P2pGroupInfo, now: Long, adopted: Boolean): List<P2pEffect> {
        phase = GroupPhase.Formed(target, g, now)
        autoFailures = 0
        autoNotBefore = 0
        warnedLevel = 0
        lastFailure = null
        if (adopted) timeline.reset(target?.name)
        timeline.mark(P2pTimeline.Mark.GROUP)
        markIp(g)
        val how = if (adopted) "Se ha formado un grupo Wi-Fi Direct sin pulsar ningún coche" else "Conectado por Wi-Fi Direct"
        return listOfNotNull(
            P2pEffect.GroupFormed(g),
            P2pEffect.Note("$how con «${target?.name ?: g.ownerName ?: "?"}»: ${addresses(g)}. Esperando el anuncio del coche…"),
            P2pEffect.Note("El móvil ha quedado como dueño del grupo (QDLink espera lo contrario); se sigue igualmente", true)
                .takeIf { g.phoneIsOwner },
        )
    }

    private fun replaceGroup(group: P2pGroupInfo?, target: P2pTarget, now: Long, cancel: Boolean): List<P2pEffect> {
        phase = GroupPhase.Removing(now, group, target)
        return listOfNotNull(
            P2pEffect.Note("Se quita la unión o el grupo actual para conectar con «${target.name}»"),
            P2pEffect.CancelConnect.takeIf { cancel },
            P2pEffect.RemoveGroup,
        )
    }

    private fun finishRemoval(p: GroupPhase.Removing, now: Long): List<P2pEffect> {
        phase = GroupPhase.Idle
        val out = mutableListOf<P2pEffect>()
        p.group?.let { out += P2pEffect.GroupLost(it) }
        p.next?.let { out += startJoin(it, auto = false, now) }
        return out
    }

    /** Fin de la unión o del grupo por causas de la plataforma. */
    private fun dropGroup(reason: String): List<P2pEffect> {
        val p = phase
        phase = GroupPhase.Idle
        return when (p) {
            is GroupPhase.Joining -> {
                lastFailure = "$reason durante la unión con «${p.target.name}»"
                emptyList()
            }
            is GroupPhase.Formed -> listOf(P2pEffect.GroupLost(p.group))
            is GroupPhase.Removing -> listOfNotNull(p.group?.let { P2pEffect.GroupLost(it) })
            GroupPhase.Idle -> emptyList()
        }
    }

    private fun joinFailed(reason: String, now: Long): List<P2pEffect> {
        lastFailure = reason
        searching = true
        autoFailures++
        val delay = autoDelayMs(autoFailures)
        autoNotBefore = now + delay
        val retry = if (settings.autoConnect && !autoPaused && storedCar != null) {
            " · conexión automática al último coche dentro de ${delay / 1000} s"
        } else {
            ""
        }
        return listOf(P2pEffect.Note(reason + retry, warning = true))
    }

    /** spec 05 §8.2 D.13: avisos a los 10 y 30 s si no llega el `Connect_Broadcast` (la espera no tiene límite). */
    private fun broadcastWarning(p: GroupPhase.Formed, now: Long): List<P2pEffect> {
        if (timeline.has(P2pTimeline.Mark.BROADCAST)) return emptyList()
        val level = when {
            now - p.sinceMs >= 30_000 -> 2
            now - p.sinceMs >= 10_000 -> 1
            else -> 0
        }
        if (level <= warnedLevel) return emptyList()
        warnedLevel = level
        val seconds = if (level == 2) 30 else 10
        return listOf(P2pEffect.Note("No llega el anuncio del coche por Wi-Fi Direct ($seconds s)", warning = true))
    }

    private fun maybeAutoConnect(now: Long): List<P2pEffect> {
        val stored = storedCar ?: return emptyList()
        if (!settings.autoConnect || autoPaused || !canSearch || phase != GroupPhase.Idle || now < autoNotBefore) {
            return emptyList()
        }
        val available = peers.filter { it.status == PeerStatus.AVAILABLE }
        val byMac = available.firstOrNull { it.address.equals(stored.address, ignoreCase = true) }
        val peer = byMac ?: available.firstOrNull { stored.name.isNotBlank() && it.name == stored.name } ?: return emptyList()
        val how = if (byMac != null) "por su MAC" else "por su nombre; MAC nueva ${peer.address}"
        return listOf(P2pEffect.Note("Conexión automática con el último coche «${peer.name}» ($how)")) +
            startJoin(P2pTarget(peer.name, peer.address), auto = true, now)
    }

    private fun guessTarget(g: P2pGroupInfo): P2pTarget? {
        val address = (if (g.phoneIsOwner) null else g.ownerAddress) ?: return null
        val known = peers.firstOrNull { it.address.equals(address, ignoreCase = true) }
        return P2pTarget(known?.name ?: g.ownerName ?: address, address)
    }

    private fun sameDevice(p: GroupPhase.Formed, t: P2pTarget): Boolean =
        p.target?.address.equals(t.address, ignoreCase = true) || p.group.ownerAddress.equals(t.address, ignoreCase = true)

    private fun markIp(g: P2pGroupInfo) {
        if (g.phoneAddresses.isNotEmpty()) timeline.mark(P2pTimeline.Mark.IP)
    }

    private fun blockedReason(): String = blocker?.message ?: "Wi-Fi Direct está desactivado"

    private fun nameOf(p: GroupPhase.Formed): String = p.target?.name ?: p.group.ownerName ?: "?"

    companion object {
        /** QDLink: wifidirect/c.java:571 (bucle de 3 s). */
        const val RELAUNCH_MS = 3_000L
        const val REMOVE_GUARD_MS = 5_000L
        const val REQUEST_GUARD_MS = 10_000L

        /** Espera de la conexión automática tras N fallos seguidos: 2, 4, 8, 16 y 30 s. */
        fun autoDelayMs(failures: Int): Long = minOf(30_000L, 2_000L shl (failures - 1).coerceIn(0, 4))

        /** «coche 192.168.49.1, móvil 192.168.49.23/24 (p2p-wlan0-0), 5180 MHz» (spec 05 §8.5). */
        fun addresses(g: P2pGroupInfo): String {
            val phone = g.phoneAddresses.joinToString().ifEmpty { "sin IPv4 todavía" } + (g.interfaceName?.let { " ($it)" } ?: "")
            val freq = g.frequencyMhz?.let { ", $it MHz" } ?: ""
            return if (g.phoneIsOwner) {
                "móvil dueño del grupo ${g.ownerIp ?: "?"}, IPs $phone$freq"
            } else {
                "coche ${g.ownerIp ?: "?"}, móvil $phone$freq"
            }
        }
    }
}
