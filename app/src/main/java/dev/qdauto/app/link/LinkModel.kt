package dev.qdauto.app.link

import dev.qdauto.app.p2p.P2pStatus
import dev.qdauto.app.settings.ConnectionMode
import dev.qdauto.app.util.Clock
import dev.qdauto.app.video.VideoInfo
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.session.LinkState
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionState
import dev.qdauto.core.session.SessionStats
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.PhoneInfo
import dev.qdauto.core.wire.VideoArgs
import dev.qdauto.core.wire.VideoParams

/** Un coche descubierto, para la lista de la UI. */
data class CarRow(
    val key: String,
    val name: String,
    val uuid: String,
    val host: String,
    val sourcePort: Int,
    val lastSeenMillis: Long,
    val count: Int,
    val qdlinkCompatible: Boolean,
    val rawJson: String,
)

/** La sesión actual o la última. */
data class SessionView(
    val id: Int,
    val state: SessionState,
    val remote: String?,
    val startedAtMillis: Long,
    val carInfo: CarInfo?,
    val carInfoRaw: String?,
    val videoArgs: VideoArgs?,
    val videoArgsRaw: String?,
    val phoneInfo: PhoneInfo?,
    val videoParams: VideoParams,
    val stats: SessionStats,
    val closeReason: String?,
)

/** Foto de todo el estado para la UI, la notificación y la exportación. */
data class LinkStatus(
    val mode: ConnectionMode,
    /** Estado de Wi-Fi Direct; `null` en modo punto de acceso. */
    val p2p: P2pStatus?,
    /** «Coche como punto de acceso» activado, pero ignorado por estar en modo Wi-Fi Direct. */
    val bindWifiIgnored: Boolean,
    val linkState: LinkState,
    val discoveryError: String?,
    val autoConnect: Boolean,
    val carFilter: String,
    val backoffMs: Long,
    val failures: Int,
    val interfaces: List<NetworkWatcher.Iface>,
    val wifi: String,
    val boundTo: String?,
    val locks: String,
    val tcpPortSetting: Int,
    val attemptPort: Int?,
    val attemptCar: String?,
    val attemptSinceMillis: Long,
    val cars: List<CarRow>,
    val session: SessionView?,
    val video: VideoInfo?,
    val lastClose: String?,
    val lastError: String?,
    val lastKey: String?,
    val lastApp: String?,
    val lastUnknown: String?,
    val events: List<String>,
)

/** Estado mutable del enlace. Lo escriben los callbacks de `:core` (varios hilos) y lo lee la UI. */
class LinkModel {
    private val lock = Any()
    private val cars = LinkedHashMap<String, CarAnnouncement>()
    private val events = ArrayDeque<String>()

    @Volatile var linkState: LinkState = LinkState.STOPPED
    @Volatile var discoveryError: String? = null
    @Volatile var attemptCar: CarAnnouncement? = null
    @Volatile var attemptPort: Int? = null
    @Volatile var attemptSinceMillis: Long = 0
    @Volatile var session: PhoneSession? = null
    @Volatile var sessionStartedMillis: Long = 0
    @Volatile var sessionStreamed: Boolean = false
    @Volatile var carInfoRaw: String? = null
    @Volatile var videoArgsRaw: String? = null
    @Volatile var lastClose: String? = null
    @Volatile var lastError: String? = null
    @Volatile var lastKey: String? = null
    @Volatile var lastApp: String? = null
    @Volatile var lastUnknown: String? = null

    fun upsertCar(car: CarAnnouncement) = synchronized(lock) { cars[car.key] = car }

    fun car(key: String): CarAnnouncement? = synchronized(lock) { cars[key] }

    fun cars(): List<CarAnnouncement> = synchronized(lock) { cars.values.sortedByDescending { it.lastSeenMillis } }

    /** Evento para el panel "Eventos" (y el log, que lo escribe quien llama). */
    fun note(text: String) {
        val line = "${Clock.now()} $text"
        synchronized(lock) {
            events.addFirst(line)
            while (events.size > MAX_EVENTS) events.removeLast()
        }
    }

    fun events(): List<String> = synchronized(lock) { events.toList() }

    /**
     * Empieza un intento (antes del ACK). Lo propio de cada sesión se limpia aquí y no en [onSessionStarted]: los
     * primeros mensajes del coche pueden procesarse antes de que `PhoneLink` avise del inicio de la sesión.
     */
    fun onAttempt(car: CarAnnouncement, port: Int) {
        attemptCar = car
        attemptPort = port
        attemptSinceMillis = System.currentTimeMillis()
        sessionStreamed = false
        carInfoRaw = null
        videoArgsRaw = null
        lastKey = null
        lastApp = null
        lastUnknown = null
    }

    fun onSessionStarted(s: PhoneSession) {
        session = s
        sessionStartedMillis = System.currentTimeMillis()
        note("Sesión ${s.id} con ${s.remoteAddress} en el puerto ${attemptPort ?: "?"}")
    }

    fun sessionView(): SessionView? {
        val s = session ?: return null
        return SessionView(
            id = s.id,
            state = s.state,
            remote = s.remoteAddress?.let { "${it.address?.hostAddress}:${it.port}" },
            startedAtMillis = sessionStartedMillis,
            carInfo = s.carInfo,
            carInfoRaw = carInfoRaw,
            videoArgs = s.videoArgs,
            videoArgsRaw = videoArgsRaw,
            phoneInfo = s.lastPhoneInfo,
            videoParams = s.videoParams,
            stats = s.stats(),
            closeReason = s.closeReason?.toString(),
        )
    }

    fun carRows(): List<CarRow> = cars().map { c ->
        CarRow(c.key, c.name, c.uuid, c.host, c.sourcePort, c.lastSeenMillis, c.count, c.qdlinkCompatible, c.rawJson)
    }

    private companion object {
        const val MAX_EVENTS = 40
    }
}
