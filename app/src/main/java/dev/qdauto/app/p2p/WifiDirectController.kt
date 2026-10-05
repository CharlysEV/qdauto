package dev.qdauto.app.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.IntentCompat
import dev.qdauto.app.link.NetworkWatcher
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.settings.P2pSettings

/**
 * Wi-Fi Direct del lado del teléfono, como QDLink (spec 05): canal de `WifiP2pManager`, receptor de todos los
 * broadcasts P2P, búsqueda de peers (y de servicios, solo diagnóstico), `connect()` con la configuración de QDLink,
 * retirada del grupo y conexión automática al último coche. Las decisiones las toma [P2pMachine]; aquí se ejecutan y
 * se registra todo con la etiqueta `QD/P2P`. Todo corre en el hilo `qd-app-p2p`; [status] es una foto para otros hilos.
 */
class WifiDirectController(
    context: Context,
    settings: P2pSettings,
    private val interfaces: () -> List<NetworkWatcher.Iface>,
    private val udpNames: () -> Collection<String>,
    private val listener: Listener,
) {
    /** Avisos para el motor (llegan en el hilo `qd-app-p2p`; no bloquear). */
    interface Listener {
        fun onNote(text: String, warning: Boolean)
        fun onGroupFormed(group: P2pGroupInfo)
        fun onGroupLost(group: P2pGroupInfo)
    }

    private val appContext = context.applicationContext
    private val thread = HandlerThread("qd-app-p2p").apply { start() }
    private val handler = Handler(thread.looper)
    private val store = P2pCarStore(appContext)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    /** Hitos y tiempos; también los marca el motor desde sus hilos. */
    val timeline = P2pTimeline()

    private val machine = P2pMachine(settings, store.load(), timeline)
    private val services = HashMap<String, MutableList<SeenService>>()
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    /** Cambia con cada canal: los resultados de un canal anterior solo se registran. */
    private var generation = 0
    private var receiverRegistered = false
    private var api35: P2pListenerApi35? = null
    private var reinitAttempts = 0
    private var channelLost = false
    private var supported = true
    private var apState: Int? = null
    private var thisDevice: String? = null
    private var lastPeersLog: String? = null
    private val lastFailures = HashMap<String, Int>()
    private var lastPermission: Boolean? = null
    private var capabilities = ""
    private var started = false
    private var closed = false

    @Volatile
    var status: P2pStatus = P2pStatus.initial(machine.storedCar, settings.autoConnect)
        private set

    fun start() = post("arranque") { doStart() }

    /** Para todo (búsqueda, unión, grupo, receptor y canal) y termina el hilo. No bloquea. */
    fun close() {
        post("parada") { teardown() }
        thread.quitSafely()
    }

    fun updateSettings(s: P2pSettings) = post("ajustes") {
        machine.settings = s
        log("ajustes Wi-Fi Direct: $s")
    }

    fun search() = post("buscar") { run(machine.userSearch(now())) }

    fun connect(address: String) = post("conectar") { run(machine.userConnect(address, now())) }

    fun disconnect() = post("desconectar") { run(machine.userDisconnect(now())) }

    fun forgetCar() = post("olvidar coche") {
        store.clear()
        machine.rememberCar(null)
        log("último coche olvidado")
        publish()
    }

    /** Volver a mirar permisos, Wi-Fi, zona Wi-Fi… (p. ej. tras conceder un permiso). */
    fun recheck() = post("comprobar") {
        preflight()
        publish()
    }

    fun onCarAccepted() = post("broadcast aceptado") { run(machine.onCarAccepted()) }

    fun onSessionEnded() = post("fin de sesión") { run(machine.onSessionEnded(now())) }

    // ===================================================================== hilo qd-app-p2p

    private fun doStart() {
        if (started || closed) return
        started = true
        val m = appContext.getSystemService(WifiP2pManager::class.java)
        supported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && m != null
        manager = m
        capabilities = describeCapabilities()
        log("arranque del modo Wi-Fi Direct · $capabilities")
        machine.storedCar?.let { log("último coche: «${it.name}» (${it.address})") }
        if (supported) {
            // Como QDLink (wifidirect/c.java:1084-1099): canal y después el receptor; STATE_CHANGED llega al registrarlo.
            if (initChannel()) {
                registerReceiver()
                registerApi35()
                requestP2pState()
            }
        } else {
            warn("Wi-Fi Direct no disponible: FEATURE_WIFI_DIRECT=${appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)}, WifiP2pManager=$m")
        }
        run(machine.start())
        preflight()
        publish()
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            guard("tick") {
                preflight()
                refreshPhoneAddresses()
                run(machine.onTick(now()))
                publish()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun initChannel(): Boolean {
        val m = manager ?: return false
        generation++
        val ch = try {
            // Callbacks en nuestro hilo (QDLink usa el principal); ChannelListener para reinicializar (spec 05 §8.2 A.2).
            m.initialize(appContext, thread.looper, channelListener)
        } catch (e: Exception) {
            warn("initialize lanzó $e")
            null
        }
        channel = ch
        if (ch == null) {
            warn("initialize devolvió null: Wi-Fi Direct no disponible")
            supported = false
            return false
        }
        log("canal Wi-Fi Direct inicializado (generación $generation)")
        return true
    }

    private val channelListener = WifiP2pManager.ChannelListener {
        handler.post { guard("canal perdido") { onChannelDisconnected() } }
    }

    private fun onChannelDisconnected() {
        if (closed) return
        warn("onChannelDisconnected: se perdió el canal con el servicio Wi-Fi Direct")
        channel = null
        generation++
        run(machine.onChannelLost())
        if (reinitAttempts >= MAX_REINIT) {
            channelLost = true
            preflight()
            publish()
            return
        }
        reinitAttempts++
        val delay = REINIT_DELAY_MS * reinitAttempts
        log("se reinicializa el canal dentro de $delay ms (intento $reinitAttempts de $MAX_REINIT)")
        handler.postDelayed({
            guard("reinicializar canal") {
                if (!closed && channel == null && initChannel()) requestP2pState()
            }
        }, delay)
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
        }
        // Context y no ContextCompat: por debajo de 33, ContextCompat exige un permiso que el manifiesto quita (spec 05 §8.2 A.3).
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        try {
            appContext.registerReceiver(receiver, filter, null, handler, flags)
            receiverRegistered = true
        } catch (e: Exception) {
            warn("registerReceiver lanzó $e")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            guard("broadcast ${intent.action}") { onBroadcast(intent) }
        }
    }

    private fun registerApi35() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || api35 != null || closed) return
        val m = manager ?: return
        val l = P2pListenerApi35(m, handler, Api35Sink())
        val error = l.register()
        if (error == null) {
            api35 = l
            log("WifiP2pListener registrado (API 35+)")
        } else {
            warn("registerWifiP2pListener: $error (se reintenta al conceder el permiso)")
        }
    }

    private inner class Api35Sink : P2pListenerApi35.Sink {
        override fun onListenerEvent(text: String) = detail("WifiP2pListener.$text")

        override fun onGroupCreationFailed(reason: Int) = guard("onGroupCreationFailed") {
            run(machine.onGroupCreationFailed(reason, now()))
        }

        override fun onNegotiationRejected() = guard("onGroupNegotiationRejectedByUser") {
            run(machine.onNegotiationRejected(now()))
        }
    }

    private fun onBroadcast(intent: Intent) {
        val action = intent.action ?: return
        val short = action.substringAfterLast('.')
        if (action != WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) detail("broadcast $short: ${P2pDescribe.extras(intent)}")
        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                onP2pState(state, "STATE_CHANGED")
            }
            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                log("DISCOVERY_CHANGED ${P2pCodes.discoveryState(state)}")
                run(machine.onDiscoveryChanged(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED))
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                val list = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_P2P_DEVICE_LIST, WifiP2pDeviceList::class.java)
                val peers = P2pDescribe.peers(list)
                logPeers("PEERS_CHANGED", peers)
                // QDLink: con la zona Wi-Fi encendida la lista se vacía (wifidirect/c.java:605-640).
                run(machine.onPeers(if (P2pCodes.apOn(apState)) emptyList() else peers, now()))
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionBroadcast(intent)
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                thisDevice = device?.let { "«${it.deviceName}» (${it.deviceAddress}, anonimizada)" }
                log("THIS_DEVICE_CHANGED: este móvil ${P2pDescribe.device(device)}")
            }
            ACTION_WIFI_AP_STATE_CHANGED -> {
                val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
                apState = state
                log("zona Wi-Fi ${P2pCodes.apState(state)}")
                if (P2pCodes.apOn(state)) run(machine.onPeers(emptyList(), now()))
                preflight()
            }
        }
        publish()
    }

    private fun onP2pState(state: Int, source: String) {
        log("$source: Wi-Fi Direct ${P2pCodes.p2pState(state)}")
        val on = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
        if (on) reinitAttempts = 0
        val wasOn = machine.enabled
        run(machine.onP2pState(on, now()))
        if (on && !wasOn) requestDeviceInfo()
    }

    private fun onConnectionBroadcast(intent: Intent) {
        val info = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
        val net = P2pDescribe.networkInfo(intent)
        val group = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
        log("CONNECTION_CHANGED: ${P2pDescribe.info(info)} · ${P2pDescribe.network(net)} · ${P2pDescribe.group(group)}")
        detail("CONNECTION_CHANGED WifiP2pGroup.toString(): ${group?.let { P2pDescribe.oneLine(it.toString()) }}")
        val formed = info?.groupFormed == true
        val g = if (formed) P2pDescribe.groupInfo(info, group) else null
        if (g != null) log("grupo: ${P2pMachine.addresses(g)} · red «${g.networkName}» · netId ${g.netId} · clientes ${g.clients}")
        run(machine.onConnection(P2pConnection(g, P2pDescribe.isFailed(net)), now()))
        // spec 05 §8.2 D.12: cruzar el broadcast con requestConnectionInfo y requestGroupInfo.
        if (formed) queryGroup()
    }

    @SuppressLint("MissingPermission") // permiso comprobado justo antes
    private fun queryGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!P2pPermissions.granted(appContext)) return
        val gen = generation
        attempt("requestConnectionInfo") {
            m.requestConnectionInfo(ch) { info -> guard("requestConnectionInfo") { log("requestConnectionInfo: ${P2pDescribe.info(info)}") } }
        }
        attempt("requestGroupInfo") {
            m.requestGroupInfo(ch) { group ->
                guard("requestGroupInfo") {
                    log("requestGroupInfo: ${P2pDescribe.group(group)}")
                    val formed = machine.phase as? GroupPhase.Formed
                    if (gen == generation && group != null && formed != null) {
                        // requestGroupInfo no trae la IP del GO: se conserva la del broadcast (y las IPs ya vistas).
                        val fresh = P2pDescribe.groupInfo(null, group)
                        val g = fresh.copy(
                            ownerIp = formed.group.ownerIp,
                            phoneAddresses = fresh.phoneAddresses.ifEmpty { formed.group.phoneAddresses },
                        )
                        run(machine.onGroupDetails(g))
                        publish()
                    }
                }
            }
        }
    }

    /** Como cliente, la IPv4 llega por DHCP: se vuelve a mirar cada segundo hasta tenerla (spec 05 §8.4 #6). */
    private fun refreshPhoneAddresses() {
        val formed = machine.phase as? GroupPhase.Formed ?: return
        if (formed.group.phoneAddresses.isNotEmpty()) return
        val nets = P2pNet.addresses(formed.group.interfaceName)
        if (nets.isEmpty()) return
        log("IP Wi-Fi Direct del móvil: ${nets.joinToString()} (${formed.group.interfaceName ?: "p2p*"})")
        run(machine.onGroupDetails(formed.group.copy(phoneAddresses = nets)))
    }

    private fun requestP2pState() {
        val m = manager ?: return
        val ch = channel ?: return
        val gen = generation
        attempt("requestP2pState") {
            m.requestP2pState(ch) { state -> guard("requestP2pState") { if (gen == generation) onP2pState(state, "requestP2pState") } }
        }
    }

    /** Nombre P2P de este móvil: lo que verá el coche (spec 05 §4). */
    @SuppressLint("MissingPermission") // permiso comprobado justo antes
    private fun requestDeviceInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!P2pPermissions.granted(appContext)) return
        attempt("requestDeviceInfo") {
            m.requestDeviceInfo(ch) { device ->
                guard("requestDeviceInfo") {
                    if (device != null) thisDevice = "«${device.deviceName}» (${device.deviceAddress})"
                    log("requestDeviceInfo: este móvil ${P2pDescribe.device(device)}")
                    publish()
                }
            }
        }
    }

    private fun preflight() {
        val wifiOn = try {
            wifi?.isWifiEnabled
        } catch (_: Exception) {
            null
        }
        val hotspot = P2pCodes.apOn(apState) || interfaces().any { NetworkWatcher.isHotspot(it) }
        val permission = P2pPermissions.granted(appContext)
        val sdk = Build.VERSION.SDK_INT
        val input = PreflightInput(
            supported = supported,
            channelLost = channelLost,
            sdkInt = sdk,
            permissionGranted = permission,
            locationOn = !P2pPermissions.locationServicesNeeded(sdk) || P2pPermissions.locationEnabled(appContext),
            wifiOn = wifiOn,
            hotspotOn = hotspot,
        )
        run(machine.onBlocker(P2pPreflight.blocker(input)))
        if (permission && lastPermission == false) {
            log("permiso concedido: ${P2pPermissions.describe(appContext)}")
            registerApi35()
            if (machine.enabled) requestDeviceInfo()
        }
        lastPermission = permission
    }

    // ===================================================================== efectos

    private fun run(effects: List<P2pEffect>) {
        for (e in effects) {
            try {
                execute(e)
            } catch (t: Throwable) {
                AppLog.e(TAG, "error ejecutando $e", t)
            }
        }
    }

    private fun execute(e: P2pEffect) {
        when (e) {
            is P2pEffect.Note -> {
                if (e.warning) warn(e.text) else log(e.text)
                listener.onNote(e.text, e.warning)
            }
            is P2pEffect.RememberCar -> {
                val car = store.save(e.target)
                machine.rememberCar(car)
                log("último coche guardado: «${car.name}» (${car.address})")
            }
            is P2pEffect.GroupFormed -> listener.onGroupFormed(e.group)
            is P2pEffect.GroupLost -> {
                if (!timeline.has(P2pTimeline.Mark.ACCEPT)) log(timeline.summary(e.group))
                listener.onGroupLost(e.group)
            }
            else -> call(e)
        }
    }

    /**
     * Llamadas a `WifiP2pManager`: cada una con su resultado en el log («discoverPeers → FALLO 2 (BUSY)»). Las que
     * exigen «Dispositivos cercanos» (o ubicación en 10-12) solo las pide la máquina sin impedimentos, es decir, con
     * el permiso concedido ([P2pPreflight]); si aun así faltara, la SecurityException se registra abajo.
     */
    @SuppressLint("MissingPermission")
    private fun call(e: P2pEffect) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            warn("${nameOf(e)}: sin canal Wi-Fi Direct; no se hace")
            failed(e, WifiP2pManager.ERROR)
            return
        }
        val gen = generation
        try {
            when (e) {
                P2pEffect.DiscoverPeers -> m.discoverPeers(ch, action("discoverPeers", gen) { ok, reason ->
                    run(machine.onDiscoverResult(ok, reason))
                })
                P2pEffect.RefreshPeersThenDiscover -> m.requestPeers(ch) { list ->
                    guard("requestPeers") {
                        if (gen != generation) return@guard
                        val peers = P2pDescribe.peers(list)
                        logPeers("requestPeers", peers)
                        run(machine.onPeersForRelaunch(if (P2pCodes.apOn(apState)) emptyList() else peers, now()))
                        publish()
                    }
                }
                P2pEffect.StopDiscovery -> m.stopPeerDiscovery(ch, action("stopPeerDiscovery", gen))
                P2pEffect.DiscoverServices -> discoverServices(m, ch, gen)
                P2pEffect.ClearServiceRequests -> m.clearServiceRequests(ch, action("clearServiceRequests", gen))
                is P2pEffect.Connect -> connect(m, ch, e, gen)
                P2pEffect.CancelConnect -> m.cancelConnect(ch, action("cancelConnect", gen, quiet = true))
                P2pEffect.RemoveGroup -> m.removeGroup(ch, action("removeGroup", gen, quiet = true) { ok, _ ->
                    run(machine.onRemoveGroupResult(ok, now()))
                })
                else -> Unit
            }
        } catch (ex: SecurityException) {
            AppLog.e(TAG, "${nameOf(e)}: falta el permiso ($ex)", ex)
            failed(e, NO_PERMISSION)
        } catch (ex: Exception) {
            // IllegalArgumentException con el canal cerrado…
            AppLog.e(TAG, "${nameOf(e)} lanzó $ex", ex)
            failed(e, WifiP2pManager.ERROR)
        }
    }

    /** Una llamada que no llegó a hacerse cuenta como fallida, para que la máquina no se quede esperando. */
    private fun failed(e: P2pEffect, reason: Int) {
        when (e) {
            P2pEffect.DiscoverPeers -> run(machine.onDiscoverResult(false, reason))
            is P2pEffect.Connect -> run(machine.onConnectResult(e.joinId, false, reason, now()))
            P2pEffect.RemoveGroup -> run(machine.onRemoveGroupResult(false, now()))
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission") // solo desde call(), con el permiso comprobado
    private fun connect(m: WifiP2pManager, ch: WifiP2pManager.Channel, e: P2pEffect.Connect, gen: Int) {
        // Constructor simple, como QDLink (wifidirect/c.java:988-997): solo deviceAddress y groupOwnerIntent.
        val config = WifiP2pConfig()
        config.deviceAddress = e.plan.deviceAddress
        e.plan.groupOwnerIntent?.let { config.groupOwnerIntent = it }
        if (e.plan.wpsSetup != null || e.plan.wpsPin != null) {
            val wps = config.wps ?: WpsInfo().also { config.wps = it }
            e.plan.wpsSetup?.let { wps.setup = it }
            e.plan.wpsPin?.let { wps.pin = it }
        }
        log("connect con «${e.target.name}»${if (e.auto) " (automática)" else ""}: ${e.plan.describe()} · " +
            "WifiP2pConfig{${P2pDescribe.oneLine(config.toString())}}")
        m.connect(ch, config, action("connect", gen) { ok, reason ->
            run(machine.onConnectResult(e.joinId, ok, reason, now()))
        })
    }

    /** Solo diagnóstico (spec 05 §8.2 B.8): se piden todos los DNS-SD y UPnP; nunca se anuncia nada. */
    @SuppressLint("MissingPermission") // solo desde call(), con el permiso comprobado
    private fun discoverServices(m: WifiP2pManager, ch: WifiP2pManager.Channel, gen: Int) {
        m.setDnsSdResponseListeners(
            ch,
            { instance, type, device -> guard("DNS-SD") { onService(device, SeenService.Kind.DNSSD, "«$instance» tipo $type") } },
            { domain, txt, device -> guard("TXT") { onService(device, SeenService.Kind.TXT, "$domain $txt") } },
        )
        m.setUpnpServiceResponseListener(ch) { usns, device ->
            guard("UPnP") { onService(device, SeenService.Kind.UPNP, usns.orEmpty().joinToString(" | ")) }
        }
        // Se limpian antes para no acumular peticiones repetidas entre búsquedas.
        m.clearServiceRequests(ch, action("clearServiceRequests", gen))
        m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), action("addServiceRequest(DNS-SD)", gen))
        m.addServiceRequest(ch, WifiP2pUpnpServiceRequest.newInstance(), action("addServiceRequest(UPnP)", gen))
        m.discoverServices(ch, action("discoverServices", gen))
    }

    private fun onService(device: WifiP2pDevice?, kind: SeenService.Kind, text: String) {
        val address = device?.deviceAddress.orEmpty()
        log("servicio ${kind.label} de «${device?.deviceName}» ($address): $text")
        if (address.isEmpty()) return
        val list = services.getOrPut(address.lowercase()) { mutableListOf() }
        list.removeAll { it.kind == kind && it.text == text }
        list += SeenService(kind, text, System.currentTimeMillis())
        while (list.size > MAX_SERVICES_PER_PEER) list.removeAt(0)
        publish()
    }

    /** [quiet]: un fallo es lo normal si no había nada que cancelar o quitar (p. ej. la limpieza al activarse P2P). */
    private fun action(name: String, gen: Int, quiet: Boolean = false, then: ((Boolean, Int) -> Unit)? = null) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = result(name, gen, true, 0, quiet, then)
            override fun onFailure(reason: Int) = result(name, gen, false, reason, quiet, then)
        }

    private fun result(name: String, gen: Int, ok: Boolean, reason: Int, quiet: Boolean, then: ((Boolean, Int) -> Unit)?) =
        guard(name) {
            val text = if (ok) "$name → OK" else "$name → FALLO ${P2pCodes.failure(reason)}"
            // El mismo fallo repetido (p. ej. discoverPeers cada 3 s) va solo al fichero.
            val repeated = !ok && lastFailures.put(name, reason) == reason
            if (ok) lastFailures.remove(name)
            when {
                gen != generation -> {
                    log("$text (canal anterior: se ignora)")
                    return@guard
                }
                ok -> log(text)
                repeated -> detail("$text (otra vez)")
                quiet -> log("$text (normal si no había nada que cancelar o quitar)")
                else -> warn(text)
            }
            then?.invoke(ok, reason)
            publish()
        }

    // ===================================================================== estado y log

    private fun publish() {
        val now = now()
        val names = try {
            udpNames()
        } catch (_: Exception) {
            emptyList()
        }
        val stored = machine.storedCar
        val views = PeerClassifier.sort(
            machine.peers.map { PeerClassifier.classify(it, services[it.address.lowercase()].orEmpty(), names, stored) },
        )
        status = P2pStatus(
            supported = supported,
            enabled = machine.enabled,
            blocker = machine.blocker,
            discovery = machine.discovery,
            searching = machine.searching,
            phase = machine.phase,
            peers = views.filter { it.peer.status.listed },
            hiddenPeers = views.count { !it.peer.status.listed },
            thisDevice = thisDevice,
            storedCar = stored,
            autoConnect = machine.settings.autoConnect,
            autoPaused = machine.autoPaused,
            autoRetryInMs = machine.autoRetryInMs(now),
            lastFailure = machine.lastFailure,
            discoveryFailure = machine.discoveryFailure,
            hotspotState = apState,
            timeline = timeline.describe(),
            capabilities = capabilities,
            servicesSeen = services.values.sumOf { it.size },
        )
    }

    /** spec 05 §8.4 #3: la lista completa solo si cambia. */
    private fun logPeers(source: String, peers: List<P2pPeer>) {
        val text = peers.joinToString("\n") { "  ${it.raw}" }
        if (text == lastPeersLog) {
            detail("$source: sin cambios (${peers.size} peers)")
            return
        }
        lastPeersLog = text
        log("$source: ${peers.size} peers" + peers.joinToString("") { "\n  «${it.name}» ${it.address} ${it.status.label}" })
        detail("$source, detalle:\n$text")
    }

    private fun describeCapabilities(): String {
        val m = manager
        val pm = appContext.packageManager
        val parts = mutableListOf(
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
            "FEATURE_WIFI_DIRECT ${yes(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))}",
        )
        if (m != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                parts += "quitar clientes ${yes(m.isGroupClientRemovalSupported)}"
                parts += "búsqueda por canal ${yes(m.isChannelConstrainedDiscoverySupported)}"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) parts += "Wi-Fi Direct R2 ${yes(m.isWiFiDirectR2Supported)}"
        }
        val wifiOn = try {
            wifi?.isWifiEnabled
        } catch (_: Exception) {
            null
        }
        parts += "Wi-Fi ${when (wifiOn) { true -> "activada"; false -> "DESACTIVADA"; null -> "?" }}"
        parts += "interfaces: " + interfaces().joinToString(" · ").ifEmpty { "ninguna con IPv4" }
        parts += P2pPermissions.describe(appContext)
        return parts.joinToString(" · ")
    }

    private fun teardown() {
        if (closed) return
        closed = true
        handler.removeCallbacks(tick)
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            // spec 05 §8.2 F.18 y QDLink i0() (wifidirect/c.java:1164-1193).
            attempt("stopPeerDiscovery") { m.stopPeerDiscovery(ch, null) }
            attempt("cancelConnect") { m.cancelConnect(ch, null) }
            attempt("removeGroup") { m.removeGroup(ch, null) }
            attempt("clearServiceRequests") { m.clearServiceRequests(ch, null) }
        }
        (machine.phase as? GroupPhase.Formed)?.let { if (!timeline.has(P2pTimeline.Mark.ACCEPT)) log(timeline.summary(it.group)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) api35?.unregister()
        api35 = null
        if (receiverRegistered) attempt("unregisterReceiver") { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        attempt("channel.close") { ch?.close() }
        channel = null
        log("modo Wi-Fi Direct parado: búsqueda, unión y grupo quitados; canal cerrado")
    }

    private fun post(what: String, block: () -> Unit) {
        if (!handler.post { if (!closed) guard(what, block) }) {
            AppLog.w(TAG, "'$what' descartado: el hilo Wi-Fi Direct ya terminó")
        }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            AppLog.e(TAG, "error en '$what'", t)
        }
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            warn("$what lanzó $e")
        }
    }

    private fun nameOf(e: P2pEffect): String = e.toString().substringBefore('(')

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun log(text: String) = AppLog.i(TAG, text)

    private fun warn(text: String) = AppLog.w(TAG, text)

    private fun detail(text: String) = AppLog.detail(TAG, text)

    private fun yes(b: Boolean) = if (b) "sí" else "no"

    private companion object {
        const val TAG = "QD/P2P"
        const val TICK_MS = 1_000L
        const val MAX_REINIT = 3
        const val REINIT_DELAY_MS = 2_000L
        const val MAX_SERVICES_PER_PEER = 20

        /** SDK: `WifiP2pManager.NO_PERMISSION` (la constante es de API 36). */
        const val NO_PERMISSION = 4

        /** No pública en el SDK; la registra QDLink (wifidirect/c.java:662). */
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
    }
}
