package dev.qdauto.app.p2p

/** Estado de un peer (`WifiP2pDevice.status`; SDK: CONNECTED 0, INVITED 1, FAILED 2, AVAILABLE 3, UNAVAILABLE 4). */
enum class PeerStatus(val code: Int, val label: String) {
    CONNECTED(0, "conectado"),
    INVITED(1, "invitado"),
    FAILED(2, "fallido"),
    AVAILABLE(3, "disponible"),
    UNAVAILABLE(4, "no disponible"),
    UNKNOWN(-1, "desconocido");

    /** QDLink solo lista CONNECTED, INVITED y AVAILABLE (QDLink: wifidirect/c.java:735-755). */
    val listed: Boolean get() = this == CONNECTED || this == INVITED || this == AVAILABLE

    companion object {
        fun of(code: Int): PeerStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Un dispositivo Wi-Fi Direct cercano, copiado de `WifiP2pDevice` (sin tipos de Android: lógica y tests). */
data class P2pPeer(
    val address: String,
    val name: String,
    val status: PeerStatus,
    val isGroupOwner: Boolean,
    val primaryType: String? = null,
    val secondaryType: String? = null,
    /** Métodos WPS que anuncia, p. ej. "PBC/PIN-pantalla". */
    val wps: String = "",
    val serviceDiscoveryCapable: Boolean = false,
    val wfd: String? = null,
    /** `WifiP2pDevice.toString()` en una línea, para el log. */
    val raw: String = "",
)

/** El coche elegido: pulsado en la lista o por la conexión automática. */
data class P2pTarget(val name: String, val address: String)

/** Último coche usado: nombre y MAC P2P, como `LINK_HISTORY` de QDLink (spec 05 §6). */
data class StoredCar(val name: String, val address: String, val savedAtMillis: Long)

data class Ipv4Net(val address: String, val prefix: Int) {
    override fun toString(): String = "$address/$prefix"
}

/** Grupo formado: `WifiP2pInfo` + `WifiP2pGroup` + las IPv4 de la interfaz P2P del móvil. */
data class P2pGroupInfo(
    val networkName: String?,
    val interfaceName: String?,
    val frequencyMhz: Int?,
    val netId: Int?,
    val phoneIsOwner: Boolean,
    val ownerName: String?,
    val ownerAddress: String?,
    /** `WifiP2pInfo.groupOwnerAddress` (con un GO Android, 192.168.49.1). */
    val ownerIp: String?,
    val clients: List<String>,
    val phoneAddresses: List<Ipv4Net>,
)

enum class DiscoveryPhase(val label: String) {
    STOPPED("parada"),
    REQUESTED("pedida"),
    RUNNING("en marcha"),
}

/** Fase del grupo: el `E` de QDLink («mGroupFormedStatus», spec 05 §2.3) más la retirada de un grupo. */
sealed interface GroupPhase {
    data object Idle : GroupPhase

    data class Joining(
        val target: P2pTarget,
        val sinceMs: Long,
        val deadlineMs: Long,
        val auto: Boolean,
        val joinId: Int,
    ) : GroupPhase

    data class Formed(val target: P2pTarget?, val group: P2pGroupInfo, val sinceMs: Long) : GroupPhase

    /** `removeGroup` pedido: [group] es el que se quita (si había) y [next], el coche con el que unirse después. */
    data class Removing(val sinceMs: Long, val group: P2pGroupInfo?, val next: P2pTarget?) : GroupPhase
}

/** Lo que impide buscar o conectar (comprobaciones previas, spec 05 §8.2 A.1). */
enum class P2pBlocker(val message: String) {
    UNSUPPORTED("Este móvil no tiene Wi-Fi Direct."),
    CHANNEL_LOST("Se perdió el canal con el servicio Wi-Fi Direct de Android. Detén e inicia el servicio."),
    NO_NEARBY_PERMISSION(
        "Falta el permiso «Dispositivos cercanos»: sin él, Android no deja buscar ni conectar por Wi-Fi Direct.",
    ),
    NO_LOCATION_PERMISSION("Falta el permiso de ubicación precisa: en Android 10-12, Wi-Fi Direct lo exige."),
    LOCATION_OFF("La ubicación del móvil está desactivada: en Android 10-12, Wi-Fi Direct no encuentra nada sin ella."),
    WIFI_OFF("Activa la Wi-Fi del móvil (no hace falta conectarse a ninguna red)."),
    HOTSPOT_ON("Apaga la zona Wi-Fi del móvil para usar Wi-Fi Direct: con ella encendida, Android no lo permite."),
}
