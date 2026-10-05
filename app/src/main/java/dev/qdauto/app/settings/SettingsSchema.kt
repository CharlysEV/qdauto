package dev.qdauto.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.qdauto.app.touch.TouchMapping
import dev.qdauto.core.session.WhitelistMode

/** Un ajuste guardado en `SharedPreferences`, con su etiqueta (en español) y su valor por defecto. */
sealed class Field<T : Any>(val key: String, val label: String, val help: String?, val default: T) {
    abstract fun read(p: SharedPreferences): T

    /** Un valor guardado con otro tipo (versión anterior de la app) vuelve al valor por defecto. */
    protected fun orDefault(block: () -> T?): T = try {
        block() ?: default
    } catch (_: ClassCastException) {
        default
    }
}

class BoolField(key: String, label: String, default: Boolean, help: String? = null) :
    Field<Boolean>(key, label, help, default) {
    override fun read(p: SharedPreferences): Boolean = orDefault { p.getBoolean(key, default) }
    fun put(e: SharedPreferences.Editor, value: Boolean) {
        e.putBoolean(key, value)
    }
}

class IntField(
    key: String,
    label: String,
    default: Int,
    val min: Int,
    val max: Int,
    help: String? = null,
    /** Admite 0 aunque quede fuera de [min]..[max] (0 = automático). */
    val zeroAllowed: Boolean = false,
) : Field<Int>(key, label, help, default) {
    override fun read(p: SharedPreferences): Int = orDefault { p.getInt(key, default) }.takeIf(::isValid) ?: default
    fun isValid(v: Int): Boolean = (zeroAllowed && v == 0) || v in min..max
    fun errorFor(v: Int?): String? = when {
        v == null -> "Número no válido"
        !isValid(v) -> "Valores: " + (if (zeroAllowed) "0 o " else "") + "$min–$max"
        else -> null
    }

    fun put(e: SharedPreferences.Editor, value: Int) {
        e.putInt(key, value)
    }
}

class TextField(key: String, label: String, default: String, help: String? = null, val maxLength: Int = 64) :
    Field<String>(key, label, help, default) {
    override fun read(p: SharedPreferences): String = orDefault { p.getString(key, default) }.trim().take(maxLength)
    fun put(e: SharedPreferences.Editor, value: String) {
        e.putString(key, value.trim().take(maxLength))
    }
}

/** Lista cerrada de opciones (enum), guardada por nombre. */
class ChoiceField<E : Enum<E>>(
    key: String,
    label: String,
    default: E,
    val options: List<E>,
    help: String? = null,
    val labelOf: (E) -> String,
) : Field<E>(key, label, help, default) {
    override fun read(p: SharedPreferences): E {
        val name = orDefaultName(p)
        return options.firstOrNull { it.name == name } ?: default
    }

    private fun orDefaultName(p: SharedPreferences): String? = try {
        p.getString(key, default.name)
    } catch (_: ClassCastException) {
        default.name
    }

    fun labels(): List<String> = options.map(labelOf)
    fun indexIn(p: SharedPreferences): Int = options.indexOf(read(p)).coerceAtLeast(0)
    fun defaultIndex(): Int = options.indexOf(default).coerceAtLeast(0)
    fun putIndex(e: SharedPreferences.Editor, index: Int) {
        e.putString(key, options[index.coerceIn(0, options.size - 1)].name)
    }
}

class Section(val title: String, val note: String?, val fields: List<Field<*>>)

/** Todos los ajustes de la app, su presentación y su lectura como [AppSettings]. */
object Settings {
    // ---- Conexión ----
    val connectionMode = ChoiceField(
        "connection_mode", "Modo de conexión", ConnectionMode.HOTSPOT, ConnectionMode.entries,
        "Punto de acceso: el coche se une a la zona Wi-Fi del móvil. Wi-Fi Direct: el móvil se une al coche (hace " +
            "falta el permiso «Dispositivos cercanos», la Wi-Fi activada y la zona Wi-Fi apagada). QDLink tiene los " +
            "dos; cuál usa el C10 se sabrá en el coche.",
    ) { it.label }
    val autoConnect = BoolField(
        "auto_connect", "Conexión automática al primer coche", true,
        "Con el servicio en marcha, contesta al primer broadcast UDP sin tocar el móvil.",
    )
    val carFilter = TextField(
        "car_filter", "Filtro de coche para la conexión automática", "",
        "UUID, nombre o IP del coche. Vacío = cualquiera.",
    )
    val tcpPort = IntField(
        "tcp_port", "Puerto TCP del móvil", 0, 1024, 65535,
        "0 = aleatorio en 10001-65535 en cada intento, como QDLink (ACK idéntico de 174 B y sin confundir una " +
            "conexión tardía de un intento anterior). Un número fijo facilita las capturas.",
        zeroAllowed = true,
    )
    val ackRetries = IntField(
        "ack_retries", "Reenvíos del ACK UDP", 0, 0, 20,
        "0 = un solo ACK, como QDLink. Con N > 0 se repite el mismo ACK cada 2 s desde los 3 s si el coche no conecta.",
    )
    val acceptTimeoutSec = IntField(
        "accept_timeout_s", "Espera de la conexión TCP del coche (s)", 20, 5, 120, "QDLink: 20 s.",
    )
    val bindWifi = BoolField(
        "bind_wifi", "Coche como punto de acceso (móvil conectado a su Wi-Fi)", false,
        "Vincula los sockets a esa Wi-Fi aunque no tenga Internet. Déjalo desactivado si el móvil comparte su " +
            "conexión (zona Wi-Fi). En modo Wi-Fi Direct se ignora.",
    )

    // ---- Wi-Fi Direct ----
    val p2pAutoConnect = BoolField(
        "p2p_auto_connect", "Conectar solo al último coche", true,
        "En cuanto aparece en la búsqueda (por su MAC, o por su nombre si la MAC cambió). Si falla, lo reintenta " +
            "con esperas de 2 a 30 s. «Desconectar» lo pausa hasta pulsar «Buscar». QDLink no lo hace.",
    )
    val p2pGoIntent = IntField(
        "p2p_go_intent", "groupOwnerIntent del móvil", 0, -1, 15,
        "0 = como QDLink (el coche queda como dueño del grupo). 15 = el móvil quiere ser el dueño. " +
            "-1 = no tocarlo (automático).",
    )
    val p2pWps = ChoiceField(
        "p2p_wps", "WPS al conectar", WpsMode.QDLINK, WpsMode.entries, "QDLink no lo toca: PBC, sin PIN.",
    ) { it.label }
    val p2pWpsPin = TextField(
        "p2p_wps_pin", "PIN WPS (solo con «PIN que muestra el coche»)", "", maxLength = 8,
    )
    val p2pJoinTimeoutSec = IntField(
        "p2p_join_timeout_s", "Plazo para unirse al grupo (s)", 60, 30, 120, "QDLink: 60 s; al vencer, cancelConnect.",
    )
    val p2pServiceDiscovery = BoolField(
        "p2p_services", "Buscar servicios QDLink (diagnóstico)", true,
        "Una vez por búsqueda pide los servicios DNS-SD y UPnP de los dispositivos cercanos y marca los que " +
            "anuncian QDLink_DnsSd_Instance o QDLink_UPnP_Device. No se anuncia nada: QDLink tampoco lo hace " +
            "desde el móvil (ese código suyo no se alcanza).",
    )
    val p2pRequireName = BoolField(
        "p2p_require_name", "Exigir nombre como QDLink", false,
        "Contestar solo al broadcast cuyo DeviceName esté contenido en el nombre Wi-Fi Direct del coche pulsado. " +
            "Desactivado: basta con que llegue por la red Wi-Fi Direct (la regla se registra igualmente).",
    )
    val p2pKeepGroup = BoolField(
        "p2p_keep_group", "Mantener el grupo tras la sesión", false, "QDLink lo quita y vuelve a buscar.",
    )

    // ---- Sesión ----
    val sendAppStatus = BoolField("send_app_status", "Enviar !BIN AppStatus al conectar", true, "QDLink lo envía siempre.")
    val reportUnlocked = BoolField(
        "report_unlocked", "Enviar LOCK_SCREEN_STATUS 3 (desbloqueado)", true,
        "Tras VIDEO_SUP_RSP. Nunca se informa de pantalla apagada ni bloqueada.",
    )
    val heartbeat = BoolField("heartbeat", "Enviar HEARTBEAT", true)
    val heartbeatPeriodMs = IntField("heartbeat_ms", "Periodo del HEARTBEAT (ms)", 3000, 500, 10000, "QDLink: 3000.")
    val watchdog = BoolField("watchdog", "Cortar si el coche deja de hablar", true)
    val watchdogTimeoutSec = IntField(
        "watchdog_s", "Silencio del coche antes de cortar (s)", 15, 5, 120,
        "QDLink corta a los 5-10 s; aquí se avisa a los 5 s.",
    )
    val replyPhoneInfo = BoolField("reply_phone_info", "CAR_INFO → PHONE_INFO", true)
    val replyUpdateNotify = BoolField("reply_update_notify", "… y después UPDATE_NOTIFY 5", true)
    val replyVideoSupport = BoolField("reply_video_support", "VIDEO_SUP_REQ → VIDEO_SUP_RSP", true)
    val replySpeechArgs = BoolField("reply_speech_args", "VIDEO_ARGS → SPEECH_ARGS", true)
    val replyLandMode = BoolField("reply_land_mode", "LAND_MODE_REQ → LAND_MODE_RSP", true)
    val echoLegacyHeartbeat = BoolField("echo_legacy_heartbeat", "Devolver el heartbeat !BIN del coche", true)
    val whitelistMode = ChoiceField(
        "whitelist_mode", "Envío de WhitelistAppOn", WhitelistMode.AUTO, WhitelistMode.entries,
        "AUTO = solo si CAR_INFO trae legal_app_watch = 1, como QDLink.",
    ) {
        when (it) {
            WhitelistMode.AUTO -> "AUTO (como QDLink)"
            WhitelistMode.ALWAYS -> "Siempre"
            WhitelistMode.NEVER -> "Nunca"
        }
    }
    val whitelistValue = IntField("whitelist_value", "Valor de WhitelistAppOn", 1, 0, 1)
    val requireVideoArgsForPlay = BoolField(
        "require_video_args", "Exigir VIDEO_ARGS antes de VIDEO_CTRL 1", true, "Como QDLink.",
    )
    val pauseOnVideoCtrlStop = BoolField(
        "pause_on_stop", "Pausar el vídeo con VIDEO_CTRL ≠ 1", false, "QDLink lo ignora y sigue emitiendo.",
    )
    val sendVideoBeforePlay = BoolField("video_before_play", "Enviar vídeo antes de VIDEO_CTRL 1 (experimento)", false)
    val replyDisconnectReq = BoolField("reply_disconnect", "Responder DISCONNECT_RSP a DISCONNECT_REQ", false, "QDLink no responde.")
    val closeOnDisconnectReq = BoolField("close_on_disconnect", "Cerrar la sesión con DISCONNECT_REQ", false)
    val btReply = ChoiceField(
        "bt_reply", "Respuesta a BT_ADDR", BtReply.NONE, BtReply.entries,
        "Sin permisos Bluetooth la app no puede comprobar la conexión real.",
    ) { it.label }
    val phoneVersion = TextField("phone_version", "PHONE_INFO.Version", "1.9.7", "La de QDLink 1.9.7.", maxLength = 32)
    val phoneName = TextField("phone_name", "PHONE_INFO.PhoneName", "", "QDLink lo manda vacío.")
    val phoneUuid = TextField("phone_uuid", "PHONE_INFO.PhoneUUID", "", "QDLink lo manda vacío.")
    val strictParsing = BoolField(
        "strict_parsing", "Leer CAR_INFO/VIDEO_ARGS como QDLink", true,
        "La primera clave que falta deja las siguientes a 0.",
    )
    val sendBufferKb = IntField(
        "send_buffer_kb", "SO_SNDBUF (KiB)", 256, 16, 8192,
        "0 = el del sistema. QDLink pide 4096; con menos, la congestión se nota antes y se descartan P-frames.",
        zeroAllowed = true,
    )
    val traceVideoFrames = BoolField("trace_video", "Registrar cada frame de vídeo en el log", true)

    // ---- Vídeo ----
    val sizeSource = ChoiceField(
        "size_source", "Tamaño del encoder", SizeSource.CAR_INFO, SizeSource.entries,
    ) { it.label }
    val manualWidth = IntField("manual_width", "Ancho manual", 1920, 16, 4096)
    val manualHeight = IntField("manual_height", "Alto manual", 1080, 16, 4096)
    val alignTo16 = BoolField(
        "align16", "Alinear el tamaño a múltiplos de 16", false,
        "Redondea al múltiplo de 16 más cercano (1080 → 1088) y la cabecera de vídeo lleva el mismo tamaño. " +
            "QDLink no alinea (envía 1080 y el SPS recorta), por eso va desactivado.",
    )
    val fps = IntField("fps", "Fotogramas por segundo", 0, 1, 120, "0 = los de VIDEO_ARGS (24 si no llegan).", zeroAllowed = true)
    val bitrateKbps = IntField(
        "bitrate_kbps", "Bitrate (kbit/s)", 0, 100, 200000, "0 = el de VIDEO_ARGS (2765 si no llega).", zeroAllowed = true,
    )
    val gopSeconds = IntField(
        "gop_s", "Intervalo entre IDR (s)", 0, 1, 60, "0 = el FrameInterval de VIDEO_ARGS (4 si no llega).", zeroAllowed = true,
    )
    val profile = ChoiceField("profile", "Perfil H.264", H264Profile.BASELINE, H264Profile.entries) { it.label }
    val bitrateMode = ChoiceField("bitrate_mode", "Modo de bitrate", BitrateMode.VBR, BitrateMode.entries) { it.label }
    val repeatFrameMs = IntField(
        "repeat_ms", "Repetir el último frame tras (ms)", 100, 10, 2000,
        "KEY_REPEAT_PREVIOUS_FRAME_AFTER: mantiene el ritmo si el dibujo se para. 0 = no.",
        zeroAllowed = true,
    )
    val appType = IntField("app_type", "appType de la cabecera de vídeo", 1, 1, 2, "1 = UI propia (in-app, como QDLink); 2 = espejo.")
    val headerAngle = IntField("header_angle", "Ángulo de la cabecera", 90, 0, 359, "In-app de QDLink: 90.")
    val headerOrientation = IntField(
        "header_orientation", "Orientación de la cabecera", 1, 0, 255, "In-app de QDLink: 1 (255 = FF).",
    )

    // ---- Táctil y app ----
    val touchMapping = ChoiceField(
        "touch_mapping", "Interpretación de las coordenadas del coche", TouchMapping.AUTO, TouchMapping.entries,
        "Automático: el espacio más pequeño que contiene todos los toques de la sesión.",
    ) { it.label }
    val autoStartService = BoolField("auto_start", "Iniciar el servicio al abrir la app", false)

    val sections: List<Section> = listOf(
        Section(
            "Conexión", "El puerto, los reenvíos y la Wi-Fi se aplican cuando no hay sesión.",
            listOf(connectionMode, autoConnect, carFilter, tcpPort, ackRetries, acceptTimeoutSec, bindWifi),
        ),
        Section(
            "Wi-Fi Direct", "Solo en modo Wi-Fi Direct. Se aplican al momento; los de la conexión, en la siguiente unión.",
            listOf(
                p2pAutoConnect, p2pGoIntent, p2pWps, p2pWpsPin, p2pJoinTimeoutSec, p2pServiceDiscovery, p2pRequireName,
                p2pKeepGroup,
            ),
        ),
        Section(
            "Sesión", "Se aplica en la siguiente conexión.",
            listOf(
                sendAppStatus, reportUnlocked, heartbeat, heartbeatPeriodMs, watchdog, watchdogTimeoutSec,
                replyPhoneInfo, replyUpdateNotify, replyVideoSupport, replySpeechArgs, replyLandMode, echoLegacyHeartbeat,
                whitelistMode, whitelistValue, requireVideoArgsForPlay, pauseOnVideoCtrlStop, sendVideoBeforePlay,
                replyDisconnectReq, closeOnDisconnectReq, btReply, phoneVersion, phoneName, phoneUuid, strictParsing,
                sendBufferKb, traceVideoFrames,
            ),
        ),
        Section(
            "Vídeo", "Si hay vídeo en marcha, el encoder se reinicia al guardar.",
            listOf(
                sizeSource, manualWidth, manualHeight, alignTo16, fps, bitrateKbps, gopSeconds, profile, bitrateMode,
                repeatFrameMs, appType, headerAngle, headerOrientation,
            ),
        ),
        Section("Táctil y app", null, listOf(touchMapping, autoStartService)),
    )

    fun load(p: SharedPreferences): AppSettings = AppSettings(
        connectionMode = connectionMode.read(p),
        p2p = P2pSettings(
            autoConnect = p2pAutoConnect.read(p),
            goIntent = p2pGoIntent.read(p),
            wps = p2pWps.read(p),
            wpsPin = p2pWpsPin.read(p),
            joinTimeoutSec = p2pJoinTimeoutSec.read(p),
            serviceDiscovery = p2pServiceDiscovery.read(p),
            requireQdlinkName = p2pRequireName.read(p),
            keepGroup = p2pKeepGroup.read(p),
        ),
        link = LinkSettings(
            tcpPort = tcpPort.read(p),
            ackRetries = ackRetries.read(p),
            acceptTimeoutSec = acceptTimeoutSec.read(p),
            bindWifi = bindWifi.read(p),
        ),
        discovery = DiscoverySettings(autoConnect = autoConnect.read(p), carFilter = carFilter.read(p)),
        session = SessionSettings(
            sendAppStatus = sendAppStatus.read(p),
            reportUnlocked = reportUnlocked.read(p),
            heartbeat = heartbeat.read(p),
            heartbeatPeriodMs = heartbeatPeriodMs.read(p),
            watchdog = watchdog.read(p),
            watchdogTimeoutSec = watchdogTimeoutSec.read(p),
            replyPhoneInfo = replyPhoneInfo.read(p),
            replyUpdateNotify = replyUpdateNotify.read(p),
            replyVideoSupport = replyVideoSupport.read(p),
            replySpeechArgs = replySpeechArgs.read(p),
            replyLandMode = replyLandMode.read(p),
            echoLegacyHeartbeat = echoLegacyHeartbeat.read(p),
            whitelistMode = whitelistMode.read(p),
            whitelistValue = whitelistValue.read(p),
            requireVideoArgsForPlay = requireVideoArgsForPlay.read(p),
            pauseOnVideoCtrlStop = pauseOnVideoCtrlStop.read(p),
            sendVideoBeforePlay = sendVideoBeforePlay.read(p),
            replyDisconnectReq = replyDisconnectReq.read(p),
            closeOnDisconnectReq = closeOnDisconnectReq.read(p),
            btReply = btReply.read(p),
            phoneVersion = phoneVersion.read(p),
            phoneName = phoneName.read(p),
            phoneUuid = phoneUuid.read(p),
            strictParsing = strictParsing.read(p),
            sendBufferKb = sendBufferKb.read(p),
            traceVideoFrames = traceVideoFrames.read(p),
        ),
        video = VideoSettings(
            sizeSource = sizeSource.read(p),
            manualWidth = manualWidth.read(p),
            manualHeight = manualHeight.read(p),
            alignTo16 = alignTo16.read(p),
            fps = fps.read(p),
            bitrateKbps = bitrateKbps.read(p),
            gopSeconds = gopSeconds.read(p),
            profile = profile.read(p),
            bitrateMode = bitrateMode.read(p),
            repeatFrameMs = repeatFrameMs.read(p),
            appType = appType.read(p),
            headerAngle = headerAngle.read(p),
            headerOrientation = headerOrientation.read(p),
        ),
        touchMapping = touchMapping.read(p),
        autoStartService = autoStartService.read(p),
    )

    /** `clave = valor` de todos los ajustes, para el log y la exportación. */
    fun dump(p: SharedPreferences): String = sections.joinToString("\n") { section ->
        section.fields.joinToString("\n", prefix = "[${section.title}]\n") { f -> "${f.key} = ${f.read(p)}" }
    }
}

/** Acceso a los ajustes guardados. */
class SettingsStore(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings = Settings.load(prefs)

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit { putBoolean(Settings.autoConnect.key, enabled) }
    }

    fun setP2pAutoConnect(enabled: Boolean) {
        prefs.edit { putBoolean(Settings.p2pAutoConnect.key, enabled) }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        prefs.edit { putString(Settings.connectionMode.key, mode.name) }
    }

    fun dump(): String = Settings.dump(prefs)

    private companion object {
        const val PREFS = "qdauto_settings"
    }
}
