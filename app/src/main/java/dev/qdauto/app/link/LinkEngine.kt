package dev.qdauto.app.link

import android.content.Context
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.settings.AppSettings
import dev.qdauto.app.settings.ConnectionMode
import dev.qdauto.app.settings.SettingsStore
import dev.qdauto.app.touch.TouchTracker
import dev.qdauto.app.util.Clock
import dev.qdauto.app.video.VideoController
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.LinkState
import dev.qdauto.core.session.PhoneLink
import dev.qdauto.core.session.PhoneLinkListener
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.util.Hex
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.UdpCodec
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Motor del servicio: un [PhoneLink] de `:core` (UDP 18463 → ACK → TCP → [PhoneSession]) con reconexión automática
 * y espera exponencial, el vídeo de prueba, el táctil y el estado para la UI. En modo Wi-Fi Direct, además, el
 * controlador P2P ([P2pBridge]) forma la red con el coche; por encima, el mismo flujo. Las operaciones de ciclo de vida
 * van por un hilo propio (`qd-app-engine`); los callbacks de `:core` solo actualizan el modelo y delegan.
 */
class LinkEngine(context: Context, private val locksDescription: () -> String) {
    private val appContext = context.applicationContext
    private val log = AppLog.qdLog
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "qd-app-engine").apply { isDaemon = true }
    }
    private val store = SettingsStore(appContext)
    private val backoff = ReconnectBackoff()
    private val screen = ScreenInfo.read(appContext)
    private val sessionEvents = SessionEvents(this)
    private val linkEvents = LinkEvents()
    private val network = NetworkWatcher(appContext, log) { change -> onNetworkChanged(change) }

    val model = LinkModel()
    val touches = TouchTracker()
    val video = VideoController(log, touches)
    private val p2p = P2pBridge(appContext, model, { network.interfaces }) {
        post("grupo Wi-Fi Direct perdido") { link?.disconnect() }
    }

    @Volatile
    var settings: AppSettings = store.load()
        private set

    @Volatile
    private var link: PhoneLink? = null

    @Volatile
    private var running = false

    @Volatile
    private var pendingRebind = false

    /** Interfaces útiles cuando se abrieron los sockets (para saber si un cambio de red obliga a reabrirlos). */
    private var openedWith: List<NetworkWatcher.Iface> = emptyList()

    fun start() = post("arranque") {
        if (running) return@post
        running = true
        val s = settings
        touches.mode = s.touchMapping
        touches.setPhone(screen.longSide, screen.shortSide)
        log.i(TAG, "pantalla: ${screen.description}")
        log.i(TAG, "ajustes:\n${store.dump()}")
        log.i(TAG, "modo de conexión: ${s.connectionMode.label}")
        network.start(bindWifi(s))
        openLink()
        if (s.connectionMode == ConnectionMode.WIFI_DIRECT) p2p.start(s.p2p, s.link.bindWifi)
    }

    /** Cierra todo. No bloquea: la parada se completa en el hilo del motor. */
    fun shutdown() {
        post("parada") {
            running = false
            p2p.stop()
            closeLink()
            network.stop()
            video.shutdown()
            model.linkState = LinkState.STOPPED
            log.i(TAG, "motor parado")
        }
        exec.shutdown()
    }

    /** Conexión manual con un coche de la lista (aunque la automática esté desactivada). */
    fun connect(key: String) = post("conectar") {
        val l = link
        val car = model.car(key)
        when {
            l == null -> model.note("El servicio no está escuchando")
            car == null -> model.note("Coche desconocido: $key")
            else -> {
                backoff.reset()
                model.note("Conexión manual con ${car.name} (${car.host})")
                if (!l.connect(car)) model.note("No se puede conectar ahora: ya hay un intento o una sesión en curso")
            }
        }
    }

    /** Corta la sesión o el intento; la conexión automática espera [MANUAL_PAUSE_MS]. */
    fun disconnect() = post("desconectar") {
        backoff.pause(MANUAL_PAUSE_MS)
        model.note("Desconexión manual; la conexión automática espera ${MANUAL_PAUSE_MS / 1000} s")
        link?.disconnect()
    }

    fun updateSettings(new: AppSettings) = post("ajustes") {
        val old = settings
        settings = new
        touches.mode = new.touchMapping
        log.i(TAG, "ajustes aplicados:\n${store.dump()}")
        if (old.discovery != new.discovery) backoff.reset()
        if (bindWifi(old) != bindWifi(new)) network.setBindWifi(bindWifi(new))
        if (old.connectionMode != new.connectionMode) {
            onModeChanged(new)
        } else if (old.p2p != new.p2p) {
            p2p.updateSettings(new.p2p)
        }
        if (old.link != new.link || old.session != new.session) {
            pendingRebind = true
            rebindIfIdle()
            if (pendingRebind) model.note("Los ajustes de conexión y sesión se aplicarán al terminar la sesión actual")
        }
        if (old.video != new.video) video.onSettingsChanged(new.video)
    }

    fun requestKeyframe() = post("IDR manual") {
        model.note(if (video.requestKeyframe()) "IDR pedido al encoder" else "No hay encoder en marcha")
    }

    // ---- Wi-Fi Direct (botones de la sección de la UI) ----

    fun p2pSearch() = post("Wi-Fi Direct: buscar") {
        if (!p2p.search()) model.note("Wi-Fi Direct no está activo: elige el modo «Wi-Fi Direct»")
    }

    fun p2pConnect(address: String) = post("Wi-Fi Direct: conectar") {
        if (!p2p.connect(address)) model.note("Wi-Fi Direct no está activo: elige el modo «Wi-Fi Direct»")
    }

    /** Quita la unión o el grupo; si había una sesión por P2P, también la corta (y pausa la reconexión). */
    fun p2pDisconnect() = post("Wi-Fi Direct: desconectar") {
        if (p2p.disconnect()) {
            backoff.pause(MANUAL_PAUSE_MS)
            link?.disconnect()
        }
    }

    fun p2pForgetCar() = post("Wi-Fi Direct: olvidar coche") {
        p2p.forgetCar()
        model.note("Wi-Fi Direct: último coche olvidado")
    }

    /** Tras conceder un permiso o volver de los ajustes del sistema. */
    fun p2pRecheck() = post("Wi-Fi Direct: comprobar") { p2p.recheck() }

    fun sendUnlocked() = sessionAction("LOCK_SCREEN_STATUS 3") { it.sendLockScreenStatus(3) }
    fun sendCarAppBackground() = sessionAction("CAR_APP_BACKGROUND") { it.sendCarAppBackground() }
    fun resendPhoneInfo() = sessionAction("PHONE_INFO") { it.resendPhoneInfo() }

    /** Clave del coche con ese UUID (para la autoprueba), o `null`. */
    fun findCarByUuid(uuid: String): String? = model.cars().firstOrNull { it.uuid == uuid }?.key

    /** Sesión activa (también antes de que `PhoneLink` avise de su inicio). */
    internal fun activeSession(): PhoneSession? = link?.currentSession

    /** Hito del modo Wi-Fi Direct (primer `CAR_INFO`), desde los eventos de la sesión. */
    internal fun onCarInfoReceived() = p2p.onCarInfo()

    fun snapshot(): LinkStatus {
        val s = settings
        val l = link
        return LinkStatus(
            mode = s.connectionMode,
            p2p = p2p.status,
            bindWifiIgnored = s.link.bindWifi && s.connectionMode == ConnectionMode.WIFI_DIRECT,
            linkState = if (l == null) LinkState.STOPPED else model.linkState,
            discoveryError = model.discoveryError,
            autoConnect = s.discovery.autoConnect,
            carFilter = s.discovery.carFilter,
            backoffMs = backoff.remainingMs(),
            failures = backoff.failureCount,
            interfaces = network.interfaces,
            wifi = network.wifiSummary(),
            boundTo = network.boundTo,
            locks = locksDescription(),
            tcpPortSetting = s.link.tcpPort,
            attemptPort = model.attemptPort,
            attemptCar = model.attemptCar?.let { "${it.name} (${it.host})" },
            attemptSinceMillis = model.attemptSinceMillis,
            cars = model.carRows(),
            session = model.sessionView(),
            video = video.info(),
            lastClose = model.lastClose,
            lastError = model.lastError,
            lastKey = model.lastKey,
            lastApp = model.lastApp,
            lastUnknown = model.lastUnknown,
            events = model.events(),
        )
    }

    // ===================================================================== interno (hilo qd-app-engine)

    private fun openLink() {
        if (!running || link != null) return
        val s = settings
        val phone = ConfigMapping.phoneIdentity(screen, s)
        val sessionConfig = ConfigMapping.sessionConfig(s, phone)
        val l = PhoneLink(ConfigMapping.linkConfig(s, sessionConfig, ::autoGate), linkEvents, sessionEvents, log)
        try {
            l.start()
        } catch (e: Exception) {
            l.close()
            val msg = "No se pudo abrir UDP ${l.config.discovery.port}: ${e.message}"
            model.discoveryError = msg
            model.note("$msg; se reintenta en 5 s")
            log.e(TAG, msg, e)
            schedule(5_000, "reintento UDP") { openLink() }
            return
        }
        link = l
        pendingRebind = false
        openedWith = relevantInterfaces(network.interfaces)
        model.discoveryError = null
        log.i(TAG, "escuchando: ${ConfigMapping.describe(s)}; $phone")
    }

    private fun closeLink() {
        val l = link ?: return
        link = null
        l.close()
        if (!l.awaitTermination(3_000)) log.w(TAG, "el enlace anterior no terminó en 3 s")
    }

    /** Reabre los sockets si hace falta y no hay intento ni sesión; si los hay, se hará al volver a buscar. */
    private fun rebindIfIdle() {
        if (!running || !pendingRebind) return
        val l = link
        if (l != null && l.state != LinkState.SEARCHING) {
            log.i(TAG, "reapertura pendiente: hay un intento o una sesión en curso")
            return
        }
        model.note("Reabriendo los sockets (ajustes o red cambiados)")
        closeLink()
        openLink()
    }

    private fun onNetworkChanged(change: NetworkWatcher.Change) = post("red") {
        if (!running) return@post
        val relevant = relevantInterfaces(change.interfaces)
        if (change.bindingChanged || (link != null && relevant != openedWith)) {
            model.note("Red: " + change.interfaces.joinToString(" · ").ifEmpty { "sin IPv4" })
            pendingRebind = true
            rebindIfIdle()
        }
    }

    /** En modo Wi-Fi Direct, la interfaz `p2p*` va y viene con cada grupo y no obliga a reabrir los sockets. */
    private fun relevantInterfaces(list: List<NetworkWatcher.Iface>) =
        NetworkWatcher.relevantPart(list, ignoreP2p = settings.connectionMode == ConnectionMode.WIFI_DIRECT)

    /** Vincular el proceso a la Wi-Fi impide llegar a la subred P2P (spec 05 §7.3 #6): solo en modo punto de acceso. */
    private fun bindWifi(s: AppSettings) = s.link.bindWifi && s.connectionMode == ConnectionMode.HOTSPOT

    private fun onModeChanged(s: AppSettings) {
        model.note("Modo de conexión: ${s.connectionMode.label}")
        log.i(TAG, "modo de conexión: ${s.connectionMode.label}")
        openedWith = relevantInterfaces(network.interfaces)
        if (!running) return
        if (s.connectionMode == ConnectionMode.WIFI_DIRECT) {
            p2p.start(s.p2p, s.link.bindWifi)
        } else if (p2p.stop()) {
            model.note("Wi-Fi Direct parado con una sesión por P2P abierta: se corta")
            link?.disconnect()
        }
    }

    /** Filtro de la conexión automática (lo llama `PhoneLink` con cada broadcast, en su hilo). */
    private fun autoGate(car: CarAnnouncement): Boolean {
        if (!running) return false
        val s = settings
        // Wi-Fi Direct: tras la pulsación el ACK es automático, como en QDLink (spec 05 §8.2 E.14).
        if (s.connectionMode == ConnectionMode.WIFI_DIRECT) return p2p.gate(car, s, backoff::canAttempt)
        val d = s.discovery
        return d.autoConnect && ConfigMapping.matchesFilter(car, d.carFilter) && backoff.canAttempt()
    }

    private fun sessionAction(label: String, action: (PhoneSession) -> Boolean) = post(label) {
        val s = activeSession()
        model.note(
            when {
                s == null -> "$label: no hay sesión"
                action(s) -> "$label encolado"
                else -> "$label: no se pudo encolar"
            },
        )
    }

    private fun post(what: String, block: () -> Unit) {
        try {
            exec.execute { guard(what, block) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun schedule(delayMs: Long, what: String, block: () -> Unit) {
        try {
            exec.schedule({ guard(what, block) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.e(TAG, "error en '$what'", t)
            model.lastError = "${Clock.now()} $what: $t"
        }
    }

    /** Eventos de `PhoneLink` (hilos de descubrimiento, aceptación y eventos de sesión): nunca lanzan. */
    private inner class LinkEvents : PhoneLinkListener {
        override fun onLinkStateChanged(state: LinkState) = guard("estado del enlace") {
            model.linkState = state
            if (state == LinkState.SEARCHING && pendingRebind) post("reapertura") { rebindIfIdle() }
        }

        override fun onCarFound(car: CarAnnouncement) = guard("coche encontrado") {
            model.upsertCar(car)
            model.note("Coche encontrado: '${car.name}' uuid=${car.uuid} ${car.host}:${car.sourcePort} qdlink=${car.qdlinkCompatible}")
            if (car.warnings.isNotEmpty()) model.note("Avisos del broadcast: ${car.warnings}")
            AppLog.fileOnly("Connect_Broadcast de ${car.host}:${car.sourcePort} (${car.rawBytes.size} B):\n${Hex.dump(car.rawBytes)}")
        }

        override fun onCarSeen(car: CarAnnouncement) = guard("coche visto") { model.upsertCar(car) }

        override fun onConnecting(car: CarAnnouncement, mirrorPort: Int) = guard("conectando") {
            touches.reset()
            model.onAttempt(car, mirrorPort)
            model.note("ACK a ${car.host}:${UdpCodec.CAR_PORT} con MirrorPort $mirrorPort; esperando su conexión TCP")
            p2p.onConnecting(car)
        }

        override fun onAcceptTimeout(car: CarAnnouncement) = guard("timeout") {
            val delay = backoff.onFailure()
            model.note("${car.name} no conectó por TCP en ${settings.link.acceptTimeoutSec} s; siguiente intento automático en ${delay / 1000} s")
            p2p.onAcceptTimeout(car)
        }

        override fun onSessionStarted(session: PhoneSession) = guard("sesión iniciada") {
            model.onSessionStarted(session)
            p2p.onSessionStarted()
        }

        override fun onSessionEnded(session: PhoneSession, reason: CloseReason) = guard("sesión terminada") {
            val duration = System.currentTimeMillis() - model.sessionStartedMillis
            val delay = backoff.onSessionEnded(model.sessionStreamed, duration)
            model.note("Fin de la sesión ${session.id} tras ${Clock.duration(duration)}; reconexión automática en ${delay / 1000} s")
            video.onSessionClosed(session)
            p2p.onSessionEnded()
        }

        override fun onExtraConnection(from: InetSocketAddress?) = guard("conexión extra") {
            model.note("Conexión TCP extra de $from con la sesión activa (se cierra)")
        }

        override fun onError(message: String, error: Throwable?) = guard("error del enlace") {
            model.lastError = "${Clock.now()} $message"
            model.note("Error: $message")
        }
    }

    private companion object {
        const val TAG = "QD/Engine"
        const val MANUAL_PAUSE_MS = 10_000L
    }
}
