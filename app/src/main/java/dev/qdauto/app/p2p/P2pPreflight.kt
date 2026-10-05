package dev.qdauto.app.p2p

/** Entradas de las comprobaciones previas del modo Wi-Fi Direct (spec 05 §8.2 A.1). */
data class PreflightInput(
    val supported: Boolean,
    val channelLost: Boolean,
    val sdkInt: Int,
    val permissionGranted: Boolean,
    val locationOn: Boolean,
    /** `null` si no se pudo leer. */
    val wifiOn: Boolean?,
    val hotspotOn: Boolean,
)

object P2pPreflight {
    /** El primer impedimento, en el orden en que conviene resolverlos, o `null` si se puede buscar y conectar. */
    fun blocker(i: PreflightInput): P2pBlocker? = when {
        !i.supported -> P2pBlocker.UNSUPPORTED
        i.channelLost -> P2pBlocker.CHANNEL_LOST
        !i.permissionGranted ->
            if (i.sdkInt >= 33) P2pBlocker.NO_NEARBY_PERMISSION else P2pBlocker.NO_LOCATION_PERMISSION
        P2pPermissions.locationServicesNeeded(i.sdkInt) && !i.locationOn -> P2pBlocker.LOCATION_OFF
        i.wifiOn == false -> P2pBlocker.WIFI_OFF
        // spec 05 §7.3 #1: con la zona Wi-Fi encendida no hay Wi-Fi Direct (Samsung lo dice en su pantalla).
        i.hotspotOn -> P2pBlocker.HOTSPOT_ON
        else -> null
    }
}
