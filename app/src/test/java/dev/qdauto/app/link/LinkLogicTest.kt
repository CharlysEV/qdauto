package dev.qdauto.app.link

import dev.qdauto.app.settings.AppSettings
import dev.qdauto.app.settings.BitrateMode
import dev.qdauto.app.settings.BtReply
import dev.qdauto.app.settings.ConnectionMode
import dev.qdauto.app.settings.DiscoverySettings
import dev.qdauto.app.settings.H264Profile
import dev.qdauto.app.settings.LinkSettings
import dev.qdauto.app.settings.P2pSettings
import dev.qdauto.app.settings.SessionSettings
import dev.qdauto.app.settings.SizeSource
import dev.qdauto.app.settings.VideoSettings
import dev.qdauto.app.settings.WpsMode
import dev.qdauto.app.touch.TouchMapping
import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.session.MirrorServer
import dev.qdauto.core.session.PhoneIdentity
import dev.qdauto.core.session.WhitelistMode
import dev.qdauto.core.wire.BtAddrRequest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkLogicTest {
    @Test
    fun backoffGrowsAndResets() {
        var now = 0L
        val b = ReconnectBackoff { now }
        assertTrue(b.canAttempt())
        assertEquals(0, b.remainingMs())
        val delays = (1..7).map { b.onFailure() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
        assertFalse(b.canAttempt())
        now += 30_000
        assertTrue(b.canAttempt())
        // Una sesión con vídeo estable reinicia la cuenta y reconecta enseguida.
        assertEquals(ReconnectBackoff.QUICK_RETRY_MS, b.onSessionEnded(streamed = true, durationMs = 60_000))
        assertEquals(0, b.failureCount)
        // Una sesión que no llegó a emitir cuenta como fallo.
        assertEquals(1_000L, b.onSessionEnded(streamed = false, durationMs = 60_000))
        assertEquals(1, b.failureCount)
        // Una pausa manual no se acorta con un fin de sesión.
        now += 10_000
        b.pause(10_000)
        assertEquals(10_000L, b.onSessionEnded(streamed = true, durationMs = 60_000))
        b.reset()
        assertTrue(b.canAttempt())
    }

    @Test
    fun sessionConfigFollowsSettings() {
        val phone = PhoneIdentity(screenLongSide = 2340, screenShortSide = 1080, brand = "samsung", model = "SM-S938B", sdkInt = 36)
        val c = ConfigMapping.sessionConfig(settings(), phone)
        assertEquals(256 * 1024, c.sendBufferBytes)
        assertEquals(15_000L, c.watchdogTimeoutMs)
        assertEquals(5_000L, c.watchdogWarnMs)
        assertTrue(c.announceUnlocked)
        assertTrue(c.sendAppStatus)
        assertNull(c.btResultProvider)
        assertEquals(1, c.videoOverrides.appType)
        assertNull(c.videoOverrides.width)
        assertEquals("1.9.7", c.phone.appVersion)

        val bt = ConfigMapping.sessionConfig(settings(btReply = BtReply.R2, sendBufferKb = 0), phone)
        assertNull(bt.sendBufferBytes)
        assertEquals(2, bt.btResultProvider?.invoke(BtAddrRequest("AA:BB", 0, 0, null)))
    }

    @Test
    fun linkConfigUsesQdlinkDefaults() {
        val phone = PhoneIdentity()
        val s = settings()
        val l = ConfigMapping.linkConfig(s, ConfigMapping.sessionConfig(s, phone)) { true }
        assertEquals(MirrorServer.RANDOM_PORT, l.mirrorPort)
        assertEquals(AckPolicy.QDLINK, l.discovery.ackPolicy)
        assertEquals(20_000L, l.acceptTimeoutMs)
        val fixed = settings().copy(link = LinkSettings(tcpPort = 23456, ackRetries = 3, acceptTimeoutSec = 30, bindWifi = false))
        val lf = ConfigMapping.linkConfig(fixed, ConfigMapping.sessionConfig(fixed, phone)) { true }
        assertEquals(23456, lf.mirrorPort)
        assertEquals(3, lf.discovery.ackPolicy.retries)
    }

    @Test
    fun carFilter() {
        val car = CarAnnouncement(
            InetAddress.getByName("192.168.43.20"), 18464, "UUID-1", "C10", "{}", ByteArray(0), null, true, emptyList(), 0, 0, 1,
        )
        assertTrue(ConfigMapping.matchesFilter(car, ""))
        assertTrue(ConfigMapping.matchesFilter(car, "uuid-1"))
        assertTrue(ConfigMapping.matchesFilter(car, "c10"))
        assertTrue(ConfigMapping.matchesFilter(car, "192.168.43.20"))
        assertFalse(ConfigMapping.matchesFilter(car, "otro"))
    }

    @Test
    fun interfaceKinds() {
        assertEquals("zona Wi-Fi", NetworkWatcher.kindOf("swlan0"))
        assertEquals("zona Wi-Fi", NetworkWatcher.kindOf("ap0"))
        assertEquals("Wi-Fi", NetworkWatcher.kindOf("wlan0"))
        assertEquals("Wi-Fi / zona Wi-Fi", NetworkWatcher.kindOf("wlan1"))
        assertEquals("Wi-Fi Direct", NetworkWatcher.kindOf("p2p-wlan0-0"))
        val ifaces = listOf(
            NetworkWatcher.Iface("rmnet_data0", "10.0.0.2", 30, NetworkWatcher.kindOf("rmnet_data0")),
            NetworkWatcher.Iface("swlan0", "192.168.97.1", 24, NetworkWatcher.kindOf("swlan0")),
        )
        assertEquals(listOf("swlan0"), NetworkWatcher.relevantPart(ifaces).map { it.name })
        assertTrue(NetworkWatcher.isHotspot(ifaces[1]))
    }

    @Test
    fun p2pInterfaceDoesNotForceRebindInWifiDirectMode() {
        val wlan = NetworkWatcher.Iface("wlan0", "192.168.1.40", 24, NetworkWatcher.kindOf("wlan0"))
        val p2p = NetworkWatcher.Iface("p2p-wlan0-0", "192.168.49.23", 24, NetworkWatcher.kindOf("p2p-wlan0-0"))
        assertTrue(NetworkWatcher.isP2p(p2p))
        // Modo punto de acceso: sin cambios (la interfaz P2P cuenta, como antes).
        assertEquals(listOf("p2p-wlan0-0", "wlan0"), NetworkWatcher.relevantPart(listOf(p2p, wlan)).map { it.name })
        // Wi-Fi Direct: que aparezca o desaparezca el grupo no cambia la parte relevante → no se reabren los sockets.
        assertEquals(
            NetworkWatcher.relevantPart(listOf(wlan), ignoreP2p = true),
            NetworkWatcher.relevantPart(listOf(p2p, wlan), ignoreP2p = true),
        )
    }

    private fun settings(btReply: BtReply = BtReply.NONE, sendBufferKb: Int = 256) = AppSettings(
        connectionMode = ConnectionMode.HOTSPOT,
        p2p = P2pSettings(
            autoConnect = true, goIntent = 0, wps = WpsMode.QDLINK, wpsPin = "", joinTimeoutSec = 60,
            serviceDiscovery = true, requireQdlinkName = false, keepGroup = false,
        ),
        link = LinkSettings(tcpPort = 0, ackRetries = 0, acceptTimeoutSec = 20, bindWifi = false),
        discovery = DiscoverySettings(autoConnect = true, carFilter = ""),
        session = SessionSettings(
            sendAppStatus = true, reportUnlocked = true, heartbeat = true, heartbeatPeriodMs = 3000, watchdog = true,
            watchdogTimeoutSec = 15, replyPhoneInfo = true, replyUpdateNotify = true, replyVideoSupport = true,
            replySpeechArgs = true, replyLandMode = true, echoLegacyHeartbeat = true, whitelistMode = WhitelistMode.AUTO,
            whitelistValue = 1, requireVideoArgsForPlay = true, pauseOnVideoCtrlStop = false, sendVideoBeforePlay = false,
            replyDisconnectReq = false, closeOnDisconnectReq = false, btReply = btReply, phoneVersion = "1.9.7",
            phoneName = "", phoneUuid = "", strictParsing = true, sendBufferKb = sendBufferKb, traceVideoFrames = true,
        ),
        video = VideoSettings(
            sizeSource = SizeSource.CAR_INFO, manualWidth = 1920, manualHeight = 1080, alignTo16 = true, fps = 0,
            bitrateKbps = 0, gopSeconds = 0, profile = H264Profile.BASELINE, bitrateMode = BitrateMode.VBR,
            repeatFrameMs = 100, appType = 1, headerAngle = 90, headerOrientation = 1,
        ),
        touchMapping = TouchMapping.AUTO,
        autoStartService = false,
    )
}
