package dev.qdauto.app.p2p

/** Un servicio visto en la búsqueda de servicios (solo diagnóstico, spec 05 §8.2 B.8). */
data class SeenService(val kind: Kind, val text: String, val atMillis: Long) {
    enum class Kind(val label: String) {
        DNSSD("DNS-SD"),
        TXT("TXT"),
        UPNP("UPnP"),
    }
}

/** Un peer con lo que se sabe de él, para la lista de la UI. */
data class PeerView(
    val peer: P2pPeer,
    val isLastCar: Boolean,
    /** Servicios que pasan el filtro de la librería P2P de QDLink. */
    val qdlinkServices: List<String>,
    /** `DeviceName` de broadcasts UDP contenidos en su nombre P2P (la regla de QDLink). */
    val broadcastNames: List<String>,
    val nameHint: Boolean,
    val services: List<SeenService>,
) {
    val looksLikeQdlinkCar: Boolean get() = qdlinkServices.isNotEmpty() || broadcastNames.isNotEmpty()

    val verdict: String
        get() = when {
            qdlinkServices.isNotEmpty() -> "parece un coche QDLink: anuncia ${qdlinkServices.joinToString()}"
            broadcastNames.isNotEmpty() ->
                "parece un coche QDLink: su nombre contiene el DeviceName «${broadcastNames.first()}» de un broadcast"
            nameHint -> "quizá un coche (por el nombre)"
            else -> "sin pistas (QDLink tampoco filtra: se elige por el nombre)"
        }
}

/**
 * Marca los peers que parecen un coche QDLink. En la capa P2P QDLink no decide nada: el usuario pulsa un nombre
 * (spec 05 §3.4). Sus únicos criterios son los filtros de servicio de su librería P2P (código que el móvil no alcanza,
 * pero es lo que anunciaría un coche con esa librería) y la regla del broadcast UDP. El nombre es solo una pista.
 */
object PeerClassifier {
    /** QDLink: wifidirect/c.java:98; filtro `instanceName.contains(…)` en :465-499. */
    const val DNSSD_INSTANCE = "QDLink_DnsSd_Instance"

    /** QDLink: wifidirect/c.java:937 (tipo que anunciaría el coche; el filtro de QDLink no lo mira). */
    const val DNSSD_TYPE = "_ssplink._tcp"

    /** QDLink: wifidirect/c.java:101; filtro `contains(…)` en :158-189. */
    const val UPNP_DEVICE = "QDLink_UPnP_Device"

    private val NAME_HINTS = listOf("leapmotor", "leap", "c10", "qdlink", "qdrive", "neusoft")

    fun classify(peer: P2pPeer, services: List<SeenService>, udpNames: Collection<String>, stored: StoredCar?): PeerView {
        val qdlink = services.filter { s ->
            (s.kind == SeenService.Kind.DNSSD && s.text.contains(DNSSD_INSTANCE)) ||
                (s.kind == SeenService.Kind.UPNP && s.text.contains(UPNP_DEVICE))
        }.map { "${it.kind.label} ${it.text}" }.distinct()
        // QDLink: wificonnection/a.java:505 (nombre pulsado ⊇ DeviceName). Un DeviceName vacío casaría con todos.
        val names = udpNames.filter { it.isNotBlank() && peer.name.contains(it) }.distinct()
        val lower = peer.name.lowercase()
        return PeerView(peer, isLastCar(peer, stored), qdlink, names, NAME_HINTS.any { lower.contains(it) }, services)
    }

    fun isLastCar(peer: P2pPeer, stored: StoredCar?): Boolean = stored != null &&
        (peer.address.equals(stored.address, ignoreCase = true) || (stored.name.isNotBlank() && peer.name == stored.name))

    /** El último coche primero (spec 05 §8.2 B.7), después los que parecen QDLink y el resto por nombre. */
    fun sort(views: List<PeerView>): List<PeerView> = views.sortedWith(
        compareBy<PeerView>({ !it.isLastCar }, { !it.looksLikeQdlinkCar }, { !it.nameHint })
            .thenBy { it.peer.name.lowercase() }
            .thenBy { it.peer.address },
    )

    /** Tipo WPS «categoría-OUI-subcategoría» (p. ej. 10-0050F204-5, un teléfono) con el nombre de la categoría. */
    fun deviceType(primary: String?): String {
        if (primary.isNullOrBlank()) return "tipo ?"
        val name = when (primary.substringBefore('-').toIntOrNull()) {
            1 -> "ordenador"
            2 -> "dispositivo de entrada"
            3 -> "impresora o escáner"
            4 -> "cámara"
            5 -> "almacenamiento"
            6 -> "equipo de red"
            7 -> "pantalla"
            8 -> "multimedia"
            9 -> "consola"
            10 -> "teléfono"
            11 -> "audio"
            12 -> "base"
            255 -> "otros"
            else -> "?"
        }
        return "$name ($primary)"
    }
}
