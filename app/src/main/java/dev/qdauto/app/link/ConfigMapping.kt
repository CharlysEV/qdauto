package dev.qdauto.app.link

import android.os.Build
import dev.qdauto.app.settings.AppSettings
import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.session.MirrorServer
import dev.qdauto.core.session.PhoneIdentity
import dev.qdauto.core.session.PhoneLinkConfig
import dev.qdauto.core.session.SessionConfig
import dev.qdauto.core.session.VideoOverrides
import dev.qdauto.core.wire.BtAddrRequest

/** Traduce los ajustes de la app a la configuración de `:core`. */
object ConfigMapping {
    fun phoneIdentity(screen: ScreenInfo.Size, s: AppSettings) = PhoneIdentity(
        screenLongSide = screen.longSide,
        screenShortSide = screen.shortSide,
        brand = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        appVersion = s.session.phoneVersion,
        phoneUuid = s.session.phoneUuid,
        phoneName = s.session.phoneName,
    )

    fun sessionConfig(s: AppSettings, phone: PhoneIdentity): SessionConfig {
        val ss = s.session
        val v = s.video
        val btCode = ss.btReply.code
        val watchdogMs = ss.watchdogTimeoutSec * 1_000L
        return SessionConfig(
            sendBufferBytes = if (ss.sendBufferKb > 0) ss.sendBufferKb * 1024 else null,
            sendAppStatus = ss.sendAppStatus,
            heartbeatEnabled = ss.heartbeat,
            heartbeatPeriodMs = ss.heartbeatPeriodMs.toLong(),
            watchdogEnabled = ss.watchdog,
            watchdogWarnMs = minOf(5_000L, watchdogMs),
            watchdogTimeoutMs = watchdogMs,
            replyPhoneInfo = ss.replyPhoneInfo,
            replyUpdateNotify = ss.replyUpdateNotify,
            replyVideoSupport = ss.replyVideoSupport,
            replySpeechArgs = ss.replySpeechArgs,
            replyLandMode = ss.replyLandMode,
            echoLegacyHeartbeat = ss.echoLegacyHeartbeat,
            replyDisconnectReq = ss.replyDisconnectReq,
            closeOnDisconnectReq = ss.closeOnDisconnectReq,
            btResultProvider = if (btCode == null) null else { _: BtAddrRequest -> btCode },
            whitelistMode = ss.whitelistMode,
            whitelistValue = ss.whitelistValue,
            announceUnlocked = ss.reportUnlocked,
            phone = phone,
            qdlinkStrictParsing = ss.strictParsing,
            requireVideoArgsForPlay = ss.requireVideoArgsForPlay,
            pauseOnVideoCtrlStop = ss.pauseOnVideoCtrlStop,
            sendVideoBeforePlay = ss.sendVideoBeforePlay,
            // W×H se fuerza al arrancar el encoder (mismo tamaño que el SPS).
            videoOverrides = VideoOverrides(appType = v.appType, angle = v.headerAngle, orientation = v.headerOrientation),
            traceVideoFrames = ss.traceVideoFrames,
        )
    }

    fun linkConfig(s: AppSettings, session: SessionConfig, gate: (CarAnnouncement) -> Boolean): PhoneLinkConfig {
        val l = s.link
        return PhoneLinkConfig(
            discovery = DiscoveryConfig(
                ackPolicy = if (l.ackRetries > 0) AckPolicy(retries = l.ackRetries) else AckPolicy.QDLINK,
            ),
            session = session,
            mirrorPort = if (l.tcpPort == 0) MirrorServer.RANDOM_PORT else l.tcpPort,
            acceptTimeoutMs = l.acceptTimeoutSec * 1_000L,
            // La conexión automática (y su espera exponencial) la decide [gate]; aquí solo se deja pasar.
            autoConnect = true,
            carFilter = gate,
            reconnect = true,
            retryDelayMs = 1_000,
        )
    }

    /** ¿Coincide el coche con el filtro (UUID, nombre o IP, sin distinguir mayúsculas)? Vacío = cualquiera. */
    fun matchesFilter(car: CarAnnouncement, filter: String): Boolean {
        val f = filter.trim()
        if (f.isEmpty()) return true
        return car.uuid.equals(f, ignoreCase = true) || car.name.equals(f, ignoreCase = true) || car.host == f
    }

    /** Descripción corta para el log. */
    fun describe(s: AppSettings): String {
        val l = s.link
        val port = if (l.tcpPort == 0) "aleatorio" else l.tcpPort.toString()
        return "puerto TCP $port · reenvíos ACK ${l.ackRetries} · espera ${l.acceptTimeoutSec} s · Wi-Fi vinculada ${l.bindWifi}"
    }
}
