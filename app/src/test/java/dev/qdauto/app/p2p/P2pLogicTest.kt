package dev.qdauto.app.p2p

import dev.qdauto.app.settings.WpsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2pLogicTest {
    private val car = P2pPeer("aa:bb:cc:dd:ee:01", "Leapmotor C10-4F2A", PeerStatus.AVAILABLE, isGroupOwner = true, primaryType = "7-0050F204-1")
    private val phone = P2pPeer("aa:bb:cc:dd:ee:02", "Galaxy de Ana", PeerStatus.AVAILABLE, isGroupOwner = false, primaryType = "10-0050F204-5")
    private val printer = P2pPeer("aa:bb:cc:dd:ee:03", "DIRECT-HP OfficeJet", PeerStatus.AVAILABLE, isGroupOwner = true)

    // ---------------------------------------------------------------- clasificación de peers

    @Test
    fun qdlinkServicesMarkTheCarLikeQdlinksOwnFilters() {
        val dnsSd = SeenService(SeenService.Kind.DNSSD, "«QDLink_DnsSd_Instance» tipo _ssplink._tcp.local.", 0)
        val v = PeerClassifier.classify(phone, listOf(dnsSd), emptyList(), null)
        assertTrue(v.looksLikeQdlinkCar)
        assertTrue(v.verdict.contains("QDLink_DnsSd_Instance"))
        val upnp = SeenService(SeenService.Kind.UPNP, "uuid:1234 | uuid:1234::upnp:rootdevice | uuid:1234::QDLink_UPnP_Device", 0)
        assertTrue(PeerClassifier.classify(printer, listOf(upnp), emptyList(), null).looksLikeQdlinkCar)
        // Otro servicio, o el tipo sin la instancia (QDLink solo mira la instancia): no.
        val other = SeenService(SeenService.Kind.DNSSD, "«Impresora» tipo _ipp._tcp.local.", 0)
        val typeOnly = SeenService(SeenService.Kind.DNSSD, "«X» tipo _ssplink._tcp.local.", 0)
        assertFalse(PeerClassifier.classify(printer, listOf(other, typeOnly), emptyList(), null).looksLikeQdlinkCar)
    }

    @Test
    fun broadcastNameRuleAndHints() {
        // Regla de QDLink: el nombre P2P contiene el DeviceName del broadcast. Un DeviceName vacío no cuenta.
        val v = PeerClassifier.classify(car, emptyList(), listOf("", "C10-4F2A"), null)
        assertEquals(listOf("C10-4F2A"), v.broadcastNames)
        assertTrue(v.looksLikeQdlinkCar)
        assertFalse(PeerClassifier.classify(phone, emptyList(), listOf(""), null).looksLikeQdlinkCar)
        val hint = PeerClassifier.classify(car, emptyList(), emptyList(), null)
        assertTrue(hint.nameHint)
        assertFalse(hint.looksLikeQdlinkCar)
        assertFalse(PeerClassifier.classify(phone, emptyList(), emptyList(), null).nameHint)
    }

    @Test
    fun lastCarFirstThenQdlinkLikeThenByName() {
        val stored = StoredCar("Galaxy de Ana", "AA:BB:CC:DD:EE:02", 0)
        assertTrue(PeerClassifier.isLastCar(phone, stored), "la MAC se compara sin mayúsculas")
        assertTrue(PeerClassifier.isLastCar(phone.copy(address = "aa:bb:cc:dd:ee:77"), stored), "o por nombre si cambió la MAC")
        assertFalse(PeerClassifier.isLastCar(phone, null))
        val upnp = SeenService(SeenService.Kind.UPNP, "uuid:1::QDLink_UPnP_Device", 0)
        val views = listOf(
            PeerClassifier.classify(printer.copy(name = "Zeta"), emptyList(), emptyList(), stored),
            PeerClassifier.classify(printer, listOf(upnp), emptyList(), stored),
            PeerClassifier.classify(phone, emptyList(), emptyList(), stored),
            PeerClassifier.classify(printer.copy(name = "Alfa", address = "aa:bb:cc:dd:ee:05"), emptyList(), emptyList(), stored),
        )
        assertEquals(
            listOf("Galaxy de Ana", "DIRECT-HP OfficeJet", "Alfa", "Zeta"),
            PeerClassifier.sort(views).map { it.peer.name },
        )
    }

    @Test
    fun deviceTypesAndListedStatusesLikeQdlink() {
        assertEquals("teléfono (10-0050F204-5)", PeerClassifier.deviceType("10-0050F204-5"))
        assertEquals("pantalla (7-0050F204-1)", PeerClassifier.deviceType("7-0050F204-1"))
        assertEquals("tipo ?", PeerClassifier.deviceType(null))
        assertEquals("? (x)", PeerClassifier.deviceType("x"))
        // QDLink: wifidirect/c.java:735-755.
        assertEquals(
            listOf(PeerStatus.CONNECTED, PeerStatus.INVITED, PeerStatus.AVAILABLE),
            PeerStatus.entries.filter { it.listed },
        )
        assertEquals(PeerStatus.UNAVAILABLE, PeerStatus.of(4))
        assertEquals(PeerStatus.UNKNOWN, PeerStatus.of(9))
    }

    // ---------------------------------------------------------------- WifiP2pConfig

    @Test
    fun connectPlanDefaultsAreQdlinks() {
        val q = ConnectPlan.of("aa:bb", 0, WpsMode.QDLINK, "1234")
        assertEquals(ConnectPlan("aa:bb", 0, null, null), q)
        assertNull(q.problem)
        assertEquals(15, ConnectPlan.of("aa:bb", 15, WpsMode.QDLINK, "").groupOwnerIntent)
        assertNull(ConnectPlan.of("aa:bb", -1, WpsMode.QDLINK, "").groupOwnerIntent, "-1: no se toca (automático)")
        assertNull(ConnectPlan.of("aa:bb", 16, WpsMode.QDLINK, "").groupOwnerIntent)
        assertEquals(ConnectPlan.WPS_PBC, ConnectPlan.of("aa:bb", 0, WpsMode.PBC, "").wpsSetup)
        val display = ConnectPlan.of("aa:bb", 0, WpsMode.DISPLAY, "1234")
        assertEquals(ConnectPlan.WPS_DISPLAY, display.wpsSetup)
        assertNull(display.wpsPin, "el PIN solo se usa con KEYPAD")
        val keypad = ConnectPlan.of("aa:bb", 0, WpsMode.KEYPAD, " 12345670 ")
        assertEquals(ConnectPlan.WPS_KEYPAD, keypad.wpsSetup)
        assertEquals("12345670", keypad.wpsPin)
        assertNotNull(ConnectPlan.of("aa:bb", 0, WpsMode.KEYPAD, "  ").problem)
        assertTrue(q.describe().contains("groupOwnerIntent=0") && q.describe().contains("sin tocar (PBC)"))
    }

    // ---------------------------------------------------------------- traspaso a UDP/TCP

    @Test
    fun qdlinkNameRuleIsContainment() {
        // QDLink: wificonnection/a.java:505, uuidName.contains(DeviceName).
        assertTrue(P2pHandover.qdlinkRule("Leapmotor C10-4F2A", "C10-4F2A"))
        assertFalse(P2pHandover.qdlinkRule("C10", "Leapmotor C10"), "el sentido importa")
        assertTrue(P2pHandover.qdlinkRule(null, ""), "DeviceName vacío: siempre")
        assertFalse(P2pHandover.qdlinkRule(null, "C10"), "sin pulsación solo casa el vacío")
    }

    @Test
    fun broadcastMustComeFromTheP2pNetwork() {
        val nets = listOf(Ipv4Net("192.168.49.23", 24))
        assertTrue(P2pHandover.fromGroup("192.168.49.1", "192.168.49.1", emptyList()), "la IP del GO")
        assertTrue(P2pHandover.fromGroup("192.168.49.77", null, nets), "la subred de la interfaz P2P")
        assertFalse(P2pHandover.fromGroup("192.168.1.10", "192.168.49.1", nets), "la Wi-Fi de casa, no")
        assertFalse(P2pHandover.fromGroup("no-ip", "192.168.49.1", nets))

        val ok = P2pHandover.evaluate("192.168.49.1", "otro nombre", true, "192.168.49.1", nets, "Leapmotor C10", requireName = false)
        assertTrue(ok.accept && ok.fromGroup && !ok.qdlinkRule)
        assertTrue(ok.reason.contains("regla QDLink") && ok.reason.contains("no"))
        val strict = P2pHandover.evaluate("192.168.49.1", "otro nombre", true, "192.168.49.1", nets, "Leapmotor C10", requireName = true)
        assertFalse(strict.accept)
        assertTrue(P2pHandover.evaluate("192.168.49.1", "C10", true, "192.168.49.1", nets, "Leapmotor C10", requireName = true).accept)
        assertFalse(P2pHandover.evaluate("192.168.49.1", "C10", false, "192.168.49.1", nets, "Leapmotor C10", false).accept, "sin grupo ni unión")
        assertFalse(P2pHandover.evaluate("192.168.1.50", "C10", true, "192.168.49.1", nets, "Leapmotor C10", false).accept)
    }

    @Test
    fun ipv4Arithmetic() {
        assertEquals(0xC0A83101.toInt(), P2pHandover.parse("192.168.49.1"))
        assertNull(P2pHandover.parse("192.168.49"))
        assertNull(P2pHandover.parse("192.168.49.256"))
        val a = P2pHandover.parse("10.1.2.3")!!
        assertTrue(P2pHandover.inSubnet(a, P2pHandover.parse("10.200.0.0")!!, 8))
        assertFalse(P2pHandover.inSubnet(a, P2pHandover.parse("10.1.2.4")!!, 32))
        assertTrue(P2pHandover.inSubnet(a, 0, 0))
        assertFalse(P2pHandover.inSubnet(a, a, 33))
    }

    // ---------------------------------------------------------------- permisos y comprobaciones previas

    @Test
    fun permissionsPerAndroidVersion() {
        assertEquals(listOf(P2pPermissions.NEARBY_WIFI_DEVICES), P2pPermissions.requested(36))
        assertEquals(listOf(P2pPermissions.NEARBY_WIFI_DEVICES), P2pPermissions.requested(33))
        assertEquals(listOf(P2pPermissions.ACCESS_FINE_LOCATION, P2pPermissions.ACCESS_COARSE_LOCATION), P2pPermissions.requested(31))
        assertEquals(listOf(P2pPermissions.ACCESS_FINE_LOCATION), P2pPermissions.requested(29))
        assertEquals(P2pPermissions.NEARBY_WIFI_DEVICES, P2pPermissions.essential(36))
        assertEquals(P2pPermissions.ACCESS_FINE_LOCATION, P2pPermissions.essential(32))
        assertFalse(P2pPermissions.locationServicesNeeded(33))
        assertTrue(P2pPermissions.locationServicesNeeded(32))
    }

    @Test
    fun preflightReportsTheFirstThingToFix() {
        val ok = PreflightInput(supported = true, channelLost = false, sdkInt = 36, permissionGranted = true, locationOn = false, wifiOn = true, hotspotOn = false)
        assertNull(P2pPreflight.blocker(ok), "en 13+ la ubicación apagada da igual (neverForLocation)")
        assertEquals(P2pBlocker.UNSUPPORTED, P2pPreflight.blocker(ok.copy(supported = false, permissionGranted = false)))
        assertEquals(P2pBlocker.CHANNEL_LOST, P2pPreflight.blocker(ok.copy(channelLost = true)))
        assertEquals(P2pBlocker.NO_NEARBY_PERMISSION, P2pPreflight.blocker(ok.copy(permissionGranted = false, wifiOn = false)))
        assertEquals(P2pBlocker.NO_LOCATION_PERMISSION, P2pPreflight.blocker(ok.copy(sdkInt = 30, permissionGranted = false)))
        assertEquals(P2pBlocker.LOCATION_OFF, P2pPreflight.blocker(ok.copy(sdkInt = 30)))
        assertEquals(P2pBlocker.WIFI_OFF, P2pPreflight.blocker(ok.copy(wifiOn = false, hotspotOn = true)))
        assertNull(P2pPreflight.blocker(ok.copy(wifiOn = null)), "si no se puede leer, no se bloquea")
        assertEquals(P2pBlocker.HOTSPOT_ON, P2pPreflight.blocker(ok.copy(hotspotOn = true)))
    }

    // ---------------------------------------------------------------- tiempos y códigos

    @Test
    fun timelineSummaryLikeTheSpec() {
        var now = 0L
        val t = P2pTimeline { now }
        t.reset("Leapmotor C10")
        t.mark(P2pTimeline.Mark.TAP)
        now = 3_200
        t.mark(P2pTimeline.Mark.GROUP)
        assertEquals(
            "P2P: coche «Leapmotor C10» (coche GO, 5180 MHz), unión 3,2 s, sin broadcast por P2P, sin TCP",
            t.summary(group(phoneIsOwner = false)),
        )
        now = 4_300
        assertTrue(t.mark(P2pTimeline.Mark.BROADCAST))
        now = 9_999
        assertFalse(t.mark(P2pTimeline.Mark.BROADCAST), "solo el primero")
        now = 4_350
        t.mark(P2pTimeline.Mark.ACK)
        t.clear(P2pTimeline.Mark.ACK)
        now = 30_000
        t.mark(P2pTimeline.Mark.ACK)
        now = 30_400
        t.mark(P2pTimeline.Mark.ACCEPT)
        assertEquals(
            "P2P: coche «Leapmotor C10» (móvil GO, 5180 MHz), unión 3,2 s, primer broadcast a 1,1 s, TCP a 0,4 s",
            t.summary(group(phoneIsOwner = true)),
        )
        assertTrue(t.describe().startsWith("pulsación "))
        assertTrue(t.describe().contains("grupo formado +3,2 s"))
        t.reset(null)
        assertEquals("", t.describe())
    }

    @Test
    fun reasonCodeNames() {
        assertEquals("2 (BUSY)", P2pCodes.failure(2))
        assertEquals("4 (NO_PERMISSION)", P2pCodes.failure(4))
        assertEquals("0 (ERROR)", P2pCodes.failure(0))
        assertEquals("2 (USER_REJECTED)", P2pCodes.groupFailure(2))
        assertEquals("5 (INVITATION_FAILED)", P2pCodes.groupFailure(5))
        assertEquals("2 (ENABLED)", P2pCodes.p2pState(2))
        assertEquals("1 (STOPPED)", P2pCodes.discoveryState(1))
        assertTrue(P2pCodes.apOn(13) && P2pCodes.apOn(12))
        assertFalse(P2pCodes.apOn(11) || P2pCodes.apOn(null))
        assertEquals("KEYPAD", P2pCodes.wps(2))
    }

    @Test
    fun addressesTextLikeTheSpecMessages() {
        assertEquals("coche 192.168.49.1, móvil 192.168.49.23/24 (p2p-wlan0-0), 5180 MHz", P2pMachine.addresses(group(phoneIsOwner = false)))
        assertTrue(P2pMachine.addresses(group(phoneIsOwner = true)).startsWith("móvil dueño del grupo"))
    }

    private fun group(phoneIsOwner: Boolean) = P2pGroupInfo(
        networkName = "DIRECT-xy", interfaceName = "p2p-wlan0-0", frequencyMhz = 5180, netId = 3, phoneIsOwner = phoneIsOwner,
        ownerName = "C10", ownerAddress = "aa:bb:cc:dd:ee:01", ownerIp = "192.168.49.1", clients = emptyList(),
        phoneAddresses = listOf(Ipv4Net("192.168.49.23", 24)),
    )
}
