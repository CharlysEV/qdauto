package dev.qdauto.app.settings

import dev.qdauto.app.touch.TouchMapping
import dev.qdauto.core.session.WhitelistMode

/** De dónde sale el tamaño del encoder. */
enum class SizeSource(val label: String) {
    CAR_INFO("CAR_INFO: par(CarWidth)×par(CarHeight), como QDLink"),
    VIDEO_ARGS("VIDEO_ARGS: Width×Height"),
    MANUAL("Manual (ancho×alto de abajo)"),
}

enum class H264Profile(val label: String) {
    BASELINE("Baseline (como QDLink)"),
    MAIN("Main"),
    HIGH("High"),
}

enum class BitrateMode(val label: String) {
    VBR("VBR (como QDLink)"),
    CBR("CBR"),
    CQ("CQ (calidad constante)"),
    CBR_FD("CBR_FD (Android 12+)"),
}

/** Respuesta a `BT_ADDR` (spec §6.8). Sin permisos Bluetooth no se puede consultar el estado real. */
enum class BtReply(val code: Int?, val label: String) {
    NONE(null, "No responder"),
    R0(0, "BT_RESULT 0"),
    R1(1, "BT_RESULT 1"),
    R2(2, "BT_RESULT 2 (conectado)"),
}

/** Cómo se forma la red con el coche: los dos modos de QDLink («Conexión de punto de acceso» y «Wi-Fi Direct»). */
enum class ConnectionMode(val label: String) {
    /** El coche se une a la zona Wi-Fi del móvil. */
    HOTSPOT("Punto de acceso del móvil"),

    /** El móvil se une al coche por Wi-Fi Direct (spec 05). Por encima, el mismo UDP/TCP. */
    WIFI_DIRECT("Wi-Fi Direct"),
}

/** WPS de `connect()` en Wi-Fi Direct. QDLink no toca `wps`: PBC del constructor, sin PIN (spec 05 §5.1). */
enum class WpsMode(val label: String) {
    QDLINK("Sin tocar: PBC, como QDLink"),
    PBC("PBC explícito"),
    DISPLAY("PIN que muestra el móvil (se teclea en el coche)"),
    KEYPAD("PIN que muestra el coche (escríbelo abajo)"),
}

/** Ajustes del modo Wi-Fi Direct. Se aplican en caliente; los de `connect()`, en la siguiente unión. */
data class P2pSettings(
    /** Unirse al último coche en cuanto aparezca (la autoconexión de QDLink es código muerto, spec 05 §6). */
    val autoConnect: Boolean,
    /** `groupOwnerIntent` de `connect()`: 0 como QDLink; -1 = no tocarlo (automático). */
    val goIntent: Int,
    val wps: WpsMode,
    val wpsPin: String,
    val joinTimeoutSec: Int,
    /** Pedir los servicios DNS-SD/UPnP de los peers (solo diagnóstico; nunca se anuncia nada). */
    val serviceDiscovery: Boolean,
    /** Exigir la regla de QDLink: el nombre P2P pulsado contiene el `DeviceName` del broadcast. */
    val requireQdlinkName: Boolean,
    /** No quitar el grupo al terminar la sesión (QDLink lo quita). */
    val keepGroup: Boolean,
)

/** Ajustes que obligan a reabrir el enlace (sockets UDP/TCP); se aplican cuando no hay sesión. */
data class LinkSettings(
    /** 0 = aleatorio en 10001-65535, como QDLink. */
    val tcpPort: Int,
    val ackRetries: Int,
    val acceptTimeoutSec: Int,
    /**
     * Coche como punto de acceso: vincular los sockets a la Wi-Fi a la que está conectado el móvil. En modo Wi-Fi
     * Direct se ignora: con el proceso vinculado, el ACK no llegaría a la subred P2P (spec 05 §7.3 #6).
     */
    val bindWifi: Boolean,
)

/** Se aplican en caliente. */
data class DiscoverySettings(
    val autoConnect: Boolean,
    /** UUID, nombre o IP del coche; vacío = cualquiera. */
    val carFilter: String,
)

/** Se convierten en `SessionConfig`; se aplican en la siguiente conexión. */
data class SessionSettings(
    val sendAppStatus: Boolean,
    val reportUnlocked: Boolean,
    val heartbeat: Boolean,
    val heartbeatPeriodMs: Int,
    val watchdog: Boolean,
    val watchdogTimeoutSec: Int,
    val replyPhoneInfo: Boolean,
    val replyUpdateNotify: Boolean,
    val replyVideoSupport: Boolean,
    val replySpeechArgs: Boolean,
    val replyLandMode: Boolean,
    val echoLegacyHeartbeat: Boolean,
    val whitelistMode: WhitelistMode,
    val whitelistValue: Int,
    val requireVideoArgsForPlay: Boolean,
    val pauseOnVideoCtrlStop: Boolean,
    val sendVideoBeforePlay: Boolean,
    val replyDisconnectReq: Boolean,
    val closeOnDisconnectReq: Boolean,
    val btReply: BtReply,
    val phoneVersion: String,
    val phoneName: String,
    val phoneUuid: String,
    val strictParsing: Boolean,
    /** `SO_SNDBUF` en KiB; 0 = no tocarlo. */
    val sendBufferKb: Int,
    val traceVideoFrames: Boolean,
)

/** Se aplican al arrancar el encoder (si hay vídeo en marcha, se reinicia). */
data class VideoSettings(
    val sizeSource: SizeSource,
    val manualWidth: Int,
    val manualHeight: Int,
    val alignTo16: Boolean,
    /** 0 = el de `VIDEO_ARGS` (24 si no llega, como QDLink). */
    val fps: Int,
    /** 0 = el de `VIDEO_ARGS` (2 764 800 bps si no llega). */
    val bitrateKbps: Int,
    /** 0 = el de `VIDEO_ARGS` (4 s si no llega). */
    val gopSeconds: Int,
    val profile: H264Profile,
    val bitrateMode: BitrateMode,
    /** `KEY_REPEAT_PREVIOUS_FRAME_AFTER`; 0 = no. */
    val repeatFrameMs: Int,
    val appType: Int,
    val headerAngle: Int,
    val headerOrientation: Int,
)

data class AppSettings(
    val connectionMode: ConnectionMode,
    val p2p: P2pSettings,
    val link: LinkSettings,
    val discovery: DiscoverySettings,
    val session: SessionSettings,
    val video: VideoSettings,
    val touchMapping: TouchMapping,
    val autoStartService: Boolean,
)
