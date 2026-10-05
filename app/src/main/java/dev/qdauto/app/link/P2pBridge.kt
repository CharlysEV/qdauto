package dev.qdauto.app.link

import android.content.Context
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.p2p.P2pCarStore
import dev.qdauto.app.p2p.P2pGroupInfo
import dev.qdauto.app.p2p.P2pHandover
import dev.qdauto.app.p2p.P2pNet
import dev.qdauto.app.p2p.P2pStatus
import dev.qdauto.app.p2p.P2pTimeline
import dev.qdauto.app.p2p.WifiDirectController
import dev.qdauto.app.settings.AppSettings
import dev.qdauto.app.settings.P2pSettings
import dev.qdauto.core.discovery.CarAnnouncement

/**
 * Une el modo Wi-Fi Direct con el enlace de `:core`. La capa P2P solo forma la red (spec 05 §8.1): aquí se decide qué
 * `Connect_Broadcast` se contesta (§8.2 E.14), se avisa al controlador de los hitos de la sesión (§8.2 E.15-F.17) y se
 * registra cada datagrama con la regla de QDLink (§8.4 #7). Lo llaman el hilo del motor y los de `PhoneLink`.
 */
internal class P2pBridge(
    private val context: Context,
    private val model: LinkModel,
    private val interfaces: () -> List<NetworkWatcher.Iface>,
    private val cutSession: () -> Unit,
) {
    @Volatile
    private var controller: WifiDirectController? = null

    /** IP del coche contestado por la red P2P en el intento o la sesión en curso; `null` si no hay. */
    @Volatile
    private var carHost: String? = null

    val status: P2pStatus? get() = controller?.status

    fun start(settings: P2pSettings, bindWifiRequested: Boolean) {
        if (controller != null) return
        val c = WifiDirectController(context, settings, interfaces, { model.cars().map { it.name } }, Events())
        controller = c
        c.start()
        if (bindWifiRequested) {
            model.note("Wi-Fi Direct: se ignora «Coche como punto de acceso»; con el proceso vinculado a esa Wi-Fi no se llegaría al coche")
        }
    }

    /** Para el modo. Devuelve `true` si había un intento o una sesión por P2P (el motor la corta). */
    fun stop(): Boolean {
        val c = controller ?: return false
        controller = null
        val hadSession = carHost != null
        carHost = null
        c.close()
        return hadSession
    }

    fun updateSettings(s: P2pSettings) {
        controller?.updateSettings(s)
    }

    /** Devuelve `false` si el modo no está activo. */
    fun search(): Boolean {
        val c = controller ?: return false
        c.search()
        return true
    }

    fun connect(address: String): Boolean {
        val c = controller ?: return false
        c.connect(address)
        return true
    }

    /** «Desconectar» de Wi-Fi Direct. Devuelve `true` si había una sesión o un intento por P2P que cortar. */
    fun disconnect(): Boolean {
        val c = controller ?: return false
        c.disconnect()
        return carHost != null
    }

    fun forgetCar() {
        val c = controller
        if (c != null) c.forgetCar() else P2pCarStore(context).clear()
    }

    fun recheck() {
        controller?.recheck()
    }

    /** Filtro de la conexión automática en modo Wi-Fi Direct (lo llama `PhoneLink` con cada broadcast, en su hilo). */
    fun gate(car: CarAnnouncement, s: AppSettings, canAttempt: () -> Boolean): Boolean {
        val c = controller ?: return false
        val st = c.status
        val group = st.group
        val d = P2pHandover.evaluate(
            source = car.host,
            deviceName = car.name,
            handoverOpen = st.handoverOpen,
            ownerIp = group?.ownerIp,
            phoneNets = phoneNets(group, st.handoverOpen),
            targetName = st.target?.name,
            requireName = s.p2p.requireQdlinkName,
        )
        if (d.fromGroup && group != null && c.timeline.mark(P2pTimeline.Mark.BROADCAST)) {
            model.note("Wi-Fi Direct: primer anuncio del coche por la red P2P: «${car.name}» desde ${car.host}")
        }
        val filterOk = ConfigMapping.matchesFilter(car, s.discovery.carFilter)
        val retryOk = canAttempt()
        val extra = when {
            !d.accept -> ""
            !filterOk -> " · pero no pasa el filtro de coche"
            !retryOk -> " · pero hay que esperar al siguiente reintento"
            else -> ""
        }
        AppLog.detail(TAG, "UDP ${car.host}:${car.sourcePort} «${car.name}» uuid=${car.uuid}: ${d.reason}$extra")
        return d.accept && filterOk && retryOk
    }

    /** `PhoneLink` abre un intento (ACK): si el coche llegó por P2P, se para la búsqueda (QDLink: wificonnection/a.java:508-511). */
    fun onConnecting(car: CarAnnouncement) {
        val c = controller ?: return
        val st = c.status
        val group = st.group
        // El mismo criterio que gate(): también un intento que empiece mientras llega el aviso del grupo formado.
        val viaP2p = st.handoverOpen && P2pHandover.fromGroup(car.host, group?.ownerIp, phoneNets(group, true))
        carHost = if (viaP2p) car.host else null
        if (!viaP2p) return
        c.timeline.mark(P2pTimeline.Mark.ACK)
        c.onCarAccepted()
    }

    /** spec 05 §8.2 E.16: sin TCP se conserva el grupo y se contesta al siguiente broadcast (QDLink se queda colgado). */
    fun onAcceptTimeout(car: CarAnnouncement) {
        if (car.host != carHost) return
        carHost = null
        controller?.timeline?.clear(P2pTimeline.Mark.ACK)
        model.note("Wi-Fi Direct: no llegó el TCP; se mantiene el grupo y se contestará al siguiente anuncio")
    }

    fun onSessionStarted() {
        if (carHost != null) controller?.timeline?.mark(P2pTimeline.Mark.ACCEPT)
    }

    fun onCarInfo() {
        if (carHost != null) controller?.timeline?.mark(P2pTimeline.Mark.CAR_INFO)
    }

    fun onSessionEnded() {
        if (carHost == null) return
        carHost = null
        val c = controller ?: return
        val summary = c.timeline.summary(c.status.group)
        AppLog.i(TAG, summary)
        model.note(summary)
        c.onSessionEnded()
    }

    /** Las IPv4 de la interfaz P2P; si aún no se conocen, se leen en el momento. */
    private fun phoneNets(group: P2pGroupInfo?, open: Boolean) =
        group?.phoneAddresses.orEmpty().ifEmpty { if (open) P2pNet.addresses(group?.interfaceName) else emptyList() }

    private inner class Events : WifiDirectController.Listener {
        override fun onNote(text: String, warning: Boolean) = model.note("Wi-Fi Direct: $text")

        override fun onGroupFormed(group: P2pGroupInfo) = Unit

        /** Sin grupo no hay camino al coche: se corta ya la sesión en vez de esperar al watchdog. */
        override fun onGroupLost(group: P2pGroupInfo) {
            if (carHost == null) return
            model.note("Wi-Fi Direct: se perdió el grupo con la sesión abierta; se corta la sesión")
            cutSession()
        }
    }

    private companion object {
        const val TAG = "QD/P2P"
    }
}
