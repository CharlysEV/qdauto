package dev.qdauto.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Vigila las IPv4 de todas las interfaces (también las de la zona Wi-Fi, como `swlan0`/`ap0`/`wlan1`, que
 * ConnectivityManager no expone) cada 3 s y con los avisos de Wi-Fi. Opcionalmente vincula el proceso a la red Wi-Fi
 * a la que está conectado el móvil (coche como punto de acceso sin Internet), para que el ACK UDP no salga por datos
 * móviles.
 */
class NetworkWatcher(
    context: Context,
    private val log: QdLog,
    private val onChange: (Change) -> Unit,
) {
    data class Iface(val name: String, val address: String, val prefix: Int, val kind: String) {
        override fun toString(): String = "$name $address/$prefix" + if (kind.isNotEmpty()) " ($kind)" else ""
    }

    /** [relevant]: cambiaron las interfaces Wi-Fi/zona/P2P/USB. [bindingChanged]: cambió la red vinculada. */
    class Change(val interfaces: List<Iface>, val relevant: Boolean, val bindingChanged: Boolean)

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var scheduler: ScheduledExecutorService? = null
    private var observer: ConnectivityManager.NetworkCallback? = null
    private var wifiRequest: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var interfaces: List<Iface> = emptyList()
        private set

    /** Enlace de la Wi-Fi cliente (frecuencia, velocidad y RSSI), si el móvil está conectado a alguna. */
    @Volatile
    private var stationLink: String? = null

    /** Descripción de la red a la que está vinculado el proceso, o `null`. */
    @Volatile
    var boundTo: String? = null
        private set

    @Volatile
    private var boundNetwork: Network? = null

    @Synchronized
    fun start(bindWifi: Boolean) {
        if (scheduler != null) return
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qd-app-net").apply { isDaemon = true } }
        scheduler = s
        poll(false) // primera lectura síncrona: quien abre los sockets ya ve las IPs
        s.scheduleWithFixedDelay({ poll(false) }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = pollSoon(false)
            override fun onLost(network: Network) {
                stationLink = null
                pollSoon(false)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = pollSoon(false)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiInfo ?: return
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                stationLink = "${info.frequency} MHz, ${info.linkSpeed} Mbit/s, RSSI ${info.rssi} dBm" +
                    if (validated) ", con Internet" else ", sin Internet validada"
            }
        }
        try {
            cm?.registerNetworkCallback(wifiRequest(), cb)
            observer = cb
        } catch (e: Exception) {
            log.w(TAG, "no se pudo registrar el aviso de cambios de Wi-Fi", e)
        }
        setBindWifi(bindWifi)
    }

    /** Estado de la Wi-Fi cliente para la UI y el log (`isWifiEnabled` requiere ACCESS_WIFI_STATE). */
    fun wifiSummary(): String {
        val enabled = try {
            wifi?.isWifiEnabled
        } catch (_: SecurityException) {
            null
        }
        val state = when (enabled) {
            true -> "activada"
            false -> "desactivada"
            null -> "?"
        }
        return "Wi-Fi cliente $state" + (stationLink?.let { " · conectada: $it" } ?: "")
    }

    @Synchronized
    fun stop() {
        setBindWifi(false)
        observer?.let { cb ->
            try {
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        observer = null
        scheduler?.shutdownNow()
        scheduler = null
    }

    /**
     * Activa o desactiva la vinculación a la Wi-Fi. Se pide la red con `requestNetwork` (CHANGE_NETWORK_STATE) para que
     * Android la mantenga aunque no tenga Internet, y se vincula el proceso con `bindProcessToNetwork`.
     */
    @Synchronized
    fun setBindWifi(enabled: Boolean) {
        val manager = cm ?: return
        if (enabled && wifiRequest == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = bind(network)
                override fun onLost(network: Network) {
                    if (boundNetwork == network) bind(null)
                }
            }
            try {
                manager.requestNetwork(wifiRequest(), cb)
                wifiRequest = cb
                log.i(TAG, "pidiendo la red Wi-Fi para vincular los sockets")
            } catch (e: Exception) {
                log.w(TAG, "requestNetwork(Wi-Fi) falló", e)
            }
        } else if (!enabled && wifiRequest != null) {
            try {
                manager.unregisterNetworkCallback(wifiRequest!!)
            } catch (_: Exception) {
            }
            wifiRequest = null
            bind(null)
        }
    }

    private fun bind(network: Network?) {
        val manager = cm ?: return
        val ok = try {
            manager.bindProcessToNetwork(network)
        } catch (e: Exception) {
            log.w(TAG, "bindProcessToNetwork($network) falló", e)
            false
        }
        val description = network?.let { n ->
            val lp = try {
                manager.getLinkProperties(n)
            } catch (_: Exception) {
                null
            }
            "$n ${lp?.interfaceName ?: "?"} ${lp?.linkAddresses?.joinToString() ?: ""}".trim()
        }
        boundNetwork = if (ok) network else null
        boundTo = if (ok) description else null
        log.i(TAG, if (network == null) "proceso desvinculado de la Wi-Fi" else "proceso vinculado a la Wi-Fi: $description (ok=$ok)")
        pollSoon(true)
    }

    private fun pollSoon(bindingChanged: Boolean) {
        try {
            scheduler?.execute { poll(bindingChanged) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun poll(bindingChanged: Boolean) {
        try {
            val now = scan()
            val old = interfaces
            if (now == old && !bindingChanged) return
            interfaces = now
            val relevant = relevantPart(now) != relevantPart(old)
            log.i(TAG, "IPv4: " + (if (now.isEmpty()) "ninguna" else now.joinToString(" · ")) + " · " + wifiSummary())
            onChange(Change(now, relevant, bindingChanged))
        } catch (e: Exception) {
            log.w(TAG, "no se pudieron leer las interfaces", e)
        }
    }

    private fun scan(): List<Iface> {
        val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        return list.flatMap { ni ->
            val up = runCatching { ni.isUp }.getOrDefault(true)
            val loopback = runCatching { ni.isLoopback }.getOrDefault(false)
            if (!up || loopback) {
                emptyList()
            } else {
                ni.interfaceAddresses.filter { it.address is Inet4Address }.map { ia ->
                    Iface(ni.name, ia.address.hostAddress ?: "?", ia.networkPrefixLength.toInt(), kindOf(ni.name))
                }
            }
        }.sortedWith(compareBy({ it.name }, { it.address }))
    }

    companion object {
        /**
         * Interfaces por las que puede llegar el coche (todo menos datos móviles y VPN). Con [ignoreP2p] tampoco las
         * `p2p*`: en modo Wi-Fi Direct el grupo aparece y desaparece con cada conexión y el UDP ya escucha en 0.0.0.0,
         * así que no hay que reabrir los sockets por ellas (spec 05 §8.2 D.13).
         */
        fun relevantPart(list: List<Iface>, ignoreP2p: Boolean = false): List<Iface> =
            list.filter { it.kind != MOBILE && it.kind != VPN && !(ignoreP2p && it.kind == P2P) }

        fun isHotspot(iface: Iface): Boolean = iface.kind == HOTSPOT

        fun isP2p(iface: Iface): Boolean = iface.kind == P2P

        private const val TAG = "QD/Net"
        private const val POLL_SECONDS = 3L
        private const val MOBILE = "datos móviles"
        private const val VPN = "VPN"
        private const val HOTSPOT = "zona Wi-Fi"
        private const val P2P = "Wi-Fi Direct"

        /** Wi-Fi con o sin Internet (el coche como punto de acceso no tiene). */
        private fun wifiRequest(): NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        /** Tipo probable de interfaz por su nombre (no hay API pública para la zona Wi-Fi). */
        fun kindOf(name: String): String = when {
            name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> HOTSPOT
            name == "wlan0" -> "Wi-Fi"
            name.startsWith("wlan") -> "Wi-Fi / zona Wi-Fi"
            name.startsWith("p2p") -> P2P
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("seth") -> MOBILE
            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "USB"
            name.startsWith("bt-pan") -> "Bluetooth"
            name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec") -> VPN
            else -> ""
        }
    }
}
