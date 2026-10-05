package dev.qdauto.app.p2p

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi

/**
 * `WifiP2pListener` (Android 15+), solo para diagnóstico: es la mejor pista de por qué falla la unión, por ejemplo si el
 * coche pide aceptar y nadie acepta (spec 05 §7.3 #8). Los avisos llegan al hilo de [handler].
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
internal class P2pListenerApi35(
    private val manager: WifiP2pManager,
    private val handler: Handler,
    private val sink: Sink,
) {
    interface Sink {
        fun onListenerEvent(text: String)
        fun onGroupCreationFailed(reason: Int)
        fun onNegotiationRejected()
    }

    private val listener = object : WifiP2pManager.WifiP2pListener {
        override fun onP2pStateChanged(state: Int) = sink.onListenerEvent("onP2pStateChanged ${P2pCodes.p2pState(state)}")

        override fun onDiscoveryStateChanged(state: Int) =
            sink.onListenerEvent("onDiscoveryStateChanged ${P2pCodes.discoveryState(state)}")

        override fun onListenStateChanged(state: Int) =
            sink.onListenerEvent("onListenStateChanged ${P2pCodes.discoveryState(state)}")

        override fun onDeviceConfigurationChanged(device: WifiP2pDevice?) =
            sink.onListenerEvent("onDeviceConfigurationChanged ${P2pDescribe.device(device)}")

        override fun onPeerListChanged(peers: WifiP2pDeviceList) =
            sink.onListenerEvent("onPeerListChanged: ${peers.deviceList.size} peers")

        override fun onGroupCreating() = sink.onListenerEvent("onGroupCreating")

        override fun onGroupCreated(info: WifiP2pInfo, group: WifiP2pGroup) =
            sink.onListenerEvent("onGroupCreated ${P2pDescribe.info(info)} · ${P2pDescribe.group(group)}")

        override fun onGroupCreationFailed(reason: Int) {
            sink.onListenerEvent("onGroupCreationFailed ${P2pCodes.groupFailure(reason)}")
            sink.onGroupCreationFailed(reason)
        }

        override fun onGroupNegotiationRejectedByUser() {
            sink.onListenerEvent("onGroupNegotiationRejectedByUser")
            sink.onNegotiationRejected()
        }

        override fun onGroupRemoved() = sink.onListenerEvent("onGroupRemoved")

        override fun onPeerClientJoined(info: WifiP2pInfo, group: WifiP2pGroup) =
            sink.onListenerEvent("onPeerClientJoined ${P2pDescribe.info(info)} · ${P2pDescribe.group(group)}")

        override fun onPeerClientDisconnected(info: WifiP2pInfo, group: WifiP2pGroup) =
            sink.onListenerEvent("onPeerClientDisconnected ${P2pDescribe.info(info)} · ${P2pDescribe.group(group)}")

        override fun onFrequencyChanged(info: WifiP2pInfo, group: WifiP2pGroup) =
            sink.onListenerEvent("onFrequencyChanged ${P2pDescribe.info(info)} · ${P2pDescribe.group(group)}")
    }

    /** Devuelve el error, o `null` si quedó registrado (sin «Dispositivos cercanos» lanza SecurityException). */
    fun register(): String? = try {
        manager.registerWifiP2pListener({ handler.post(it) }, listener)
        null
    } catch (e: SecurityException) {
        e.toString()
    } catch (e: Exception) {
        e.toString()
    }

    fun unregister() {
        try {
            manager.unregisterWifiP2pListener(listener)
        } catch (_: Exception) {
        }
    }
}
