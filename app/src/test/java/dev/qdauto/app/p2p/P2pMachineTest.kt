package dev.qdauto.app.p2p

import dev.qdauto.app.settings.P2pSettings
import dev.qdauto.app.settings.WpsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2pMachineTest {
    private val settings = P2pSettings(
        autoConnect = true, goIntent = 0, wps = WpsMode.QDLINK, wpsPin = "", joinTimeoutSec = 60,
        serviceDiscovery = true, requireQdlinkName = false, keepGroup = false,
    )
    private var wall = 1_000_000L
    private val timeline = P2pTimeline { wall }

    private val car = P2pPeer("aa:bb:cc:dd:ee:01", "Leapmotor C10", PeerStatus.AVAILABLE, isGroupOwner = true)
    private val tv = P2pPeer("aa:bb:cc:dd:ee:02", "[TV] Salón", PeerStatus.AVAILABLE, isGroupOwner = false)

    private fun machine(s: P2pSettings = settings, stored: StoredCar? = null) = P2pMachine(s, stored, timeline)

    private fun group(owner: P2pPeer = car, phoneIsOwner: Boolean = false) = P2pGroupInfo(
        networkName = "DIRECT-xy-C10", interfaceName = "p2p-wlan0-0", frequencyMhz = 5180, netId = 7,
        phoneIsOwner = phoneIsOwner, ownerName = owner.name, ownerAddress = owner.address, ownerIp = "192.168.49.1",
        clients = emptyList(), phoneAddresses = listOf(Ipv4Net("192.168.49.23", 24)),
    )

    /** Arranca y deja la máquina lista para buscar: P2P activado y la limpieza inicial sin grupo que quitar. */
    private fun ready(m: P2pMachine) {
        m.start()
        m.onP2pState(true, 0)
        m.onRemoveGroupResult(false, 0)
    }

    /** Lo que hace el controlador con `RememberCar`. */
    private fun remember(m: P2pMachine, effects: List<P2pEffect>) {
        effects.filterIsInstance<P2pEffect.RememberCar>().forEach { m.rememberCar(StoredCar(it.target.name, it.target.address, 0)) }
    }

    private fun List<P2pEffect>.connect(): P2pEffect.Connect = filterIsInstance<P2pEffect.Connect>().single()

    private fun List<P2pEffect>.hasConnect() = any { it is P2pEffect.Connect }

    private fun List<P2pEffect>.relaunches() = contains(P2pEffect.RefreshPeersThenDiscover)

    @Test
    fun enablingP2pCleansUpLikeQdlinkThenSearches() {
        val m = machine()
        m.start()
        assertTrue(m.onTick(0).isEmpty(), "sin P2P habilitado no se hace nada")
        val on = m.onP2pState(true, 0)
        assertTrue(P2pEffect.CancelConnect in on && P2pEffect.RemoveGroup in on)
        assertIs<GroupPhase.Removing>(m.phase)
        assertFalse(m.onTick(1_000).relaunches(), "no se busca mientras se quita el grupo anterior")
        assertTrue(m.onRemoveGroupResult(false, 1_500).isEmpty())
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.onTick(2_000).relaunches())
        assertEquals(listOf(P2pEffect.DiscoverPeers), m.onPeersForRelaunch(listOf(tv), 2_100))
        assertEquals(DiscoveryPhase.REQUESTED, m.discovery)
        m.onDiscoverResult(ok = true, reason = 0)
        assertEquals(DiscoveryPhase.RUNNING, m.discovery)
        // Con la búsqueda en marcha, una sola búsqueda de servicios (diagnóstico).
        assertTrue(P2pEffect.DiscoverServices in m.onTick(3_000))
        assertFalse(P2pEffect.DiscoverServices in m.onTick(4_000))
    }

    @Test
    fun discoveryFailuresAreShownOnceAndRetried() {
        val m = machine()
        ready(m)
        m.userSearch(0)
        val first = m.onDiscoverResult(ok = false, reason = 0)
        assertIs<P2pEffect.Note>(first.single())
        assertEquals(DiscoveryPhase.STOPPED, m.discovery)
        assertTrue(m.discoveryFailure.orEmpty().contains("0 (ERROR)"))
        assertTrue(m.onTick(3_000).relaunches(), "el bucle lo reintenta")
        assertTrue(m.onDiscoverResult(ok = false, reason = 0).isEmpty(), "el mismo motivo no se repite")
        assertTrue(m.onDiscoverResult(ok = false, reason = 2).isNotEmpty(), "otro motivo sí")
        m.onDiscoverResult(ok = true, reason = 0)
        assertNull(m.discoveryFailure)
    }

    @Test
    fun removalWithoutConfirmationIsGuarded() {
        val m = machine()
        m.start()
        m.onP2pState(true, 0)
        m.onRemoveGroupResult(true, 100) // éxito, pero nunca llega CONNECTION_CHANGED
        assertIs<GroupPhase.Removing>(m.phase)
        m.onTick(P2pMachine.REMOVE_GUARD_MS - 1)
        assertIs<GroupPhase.Removing>(m.phase)
        m.onTick(P2pMachine.REMOVE_GUARD_MS)
        assertEquals(GroupPhase.Idle, m.phase)
    }

    @Test
    fun discoveryIsRelaunchedEveryThreeSecondsOnlyWhenStoppedAndIdle() {
        val m = machine()
        ready(m)
        assertTrue(m.onTick(0).relaunches())
        assertFalse(m.onTick(2_000).relaunches())
        assertTrue(m.onTick(3_000).relaunches())
        // Un peer INVITED impide relanzar (QDLink: U(), wifidirect/c.java:721-733).
        assertTrue(m.onPeersForRelaunch(listOf(car.copy(status = PeerStatus.INVITED)), 3_100).isEmpty())
        assertEquals(DiscoveryPhase.STOPPED, m.discovery)
        // Con la búsqueda en marcha no se relanza.
        m.onDiscoveryChanged(true)
        assertFalse(m.onTick(10_000).relaunches())
        // Ni durante la unión.
        m.onDiscoveryChanged(false)
        m.onPeers(listOf(car), 10_000)
        m.userConnect(car.address, 10_000)
        assertFalse(m.onTick(20_000).relaunches())
        // Ni con el grupo formado.
        m.onConnection(P2pConnection(group()), 21_000)
        assertFalse(m.onTick(30_000).relaunches())
        // Un discoverPeers sin respuesta no bloquea el bucle para siempre.
        val n = machine()
        ready(n)
        n.userSearch(0)
        assertEquals(DiscoveryPhase.REQUESTED, n.discovery)
        n.onTick(P2pMachine.REQUEST_GUARD_MS)
        assertEquals(DiscoveryPhase.STOPPED, n.discovery)
    }

    @Test
    fun tapConnectsWithQdlinkConfigAndTimesOut() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car, tv), 0)
        val fx = m.userConnect(car.address, 1_000)
        val c = fx.connect()
        assertEquals(car.address, c.plan.deviceAddress)
        assertEquals(0, c.plan.groupOwnerIntent, "QDLink: groupOwnerIntent 0")
        assertNull(c.plan.wpsSetup, "WPS sin tocar: PBC del constructor")
        assertNull(c.plan.wpsPin)
        assertFalse(c.auto)
        assertTrue(P2pEffect.ClearServiceRequests in fx)
        assertEquals(P2pTarget(car.name, car.address), fx.filterIsInstance<P2pEffect.RememberCar>().single().target)
        assertTrue(timeline.has(P2pTimeline.Mark.TAP))
        assertTrue(m.onConnectResult(c.joinId, ok = true, reason = 0, now = 2_000).isEmpty())
        assertTrue(timeline.has(P2pTimeline.Mark.CONNECT_OK))
        assertIs<GroupPhase.Joining>(m.phase)
        assertFalse(P2pEffect.CancelConnect in m.onTick(60_999))
        val timeout = m.onTick(61_000)
        assertTrue(P2pEffect.CancelConnect in timeout, "QDLink: plazo de 60 s y cancelConnect")
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.lastFailure.orEmpty().contains("Tiempo agotado"))
        assertTrue(timeout.relaunches(), "vuelve a buscar")
    }

    @Test
    fun joinFailuresCarryTheirReasonAndStaleResultsAreIgnored() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car), 0)
        val first = m.userConnect(car.address, 0).connect()
        // Estado intermedio durante la negociación: se sigue esperando.
        assertTrue(m.onConnection(P2pConnection(null, failed = false), 500).isEmpty())
        assertIs<GroupPhase.Joining>(m.phase)
        m.onConnectResult(first.joinId, ok = false, reason = 2, now = 1_000)
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.lastFailure.orEmpty().contains("2 (BUSY)"))

        val second = m.userConnect(car.address, 2_000).connect()
        assertTrue(m.onConnectResult(first.joinId, ok = false, reason = 0, now = 2_100).isEmpty(), "resultado de la unión anterior")
        assertIs<GroupPhase.Joining>(m.phase)
        m.onGroupCreationFailed(2, 3_000)
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.lastFailure.orEmpty().contains("USER_REJECTED"))
        assertTrue(m.onConnectResult(second.joinId, ok = true, reason = 0, now = 3_100).isEmpty())
        assertEquals(GroupPhase.Idle, m.phase)

        m.userConnect(car.address, 4_000)
        m.onConnection(P2pConnection(null, failed = true), 4_500)
        assertEquals(GroupPhase.Idle, m.phase, "NetworkInfo FAILED durante la unión = fallo")

        m.userConnect(car.address, 5_000)
        m.onNegotiationRejected(5_500)
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.lastFailure.orEmpty().contains("rechazada"))
    }

    @Test
    fun groupIsReusedForTheSameCarAndReplacedForAnother() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car, tv), 0)
        m.userConnect(car.address, 0)
        val formed = m.onConnection(P2pConnection(group()), 3_200)
        assertTrue(formed.any { it is P2pEffect.GroupFormed })
        assertTrue(formed.none { it is P2pEffect.Note && it.warning }, "el coche es GO: sin aviso")
        assertIs<GroupPhase.Formed>(m.phase)
        assertTrue(timeline.has(P2pTimeline.Mark.GROUP) && timeline.has(P2pTimeline.Mark.IP))
        val same = m.userConnect(car.address, 5_000)
        assertTrue(same.none { it == P2pEffect.RemoveGroup || it is P2pEffect.Connect }, "mismo coche: se reutiliza")
        val swap = m.userConnect(tv.address, 6_000)
        assertTrue(P2pEffect.RemoveGroup in swap)
        assertFalse(swap.hasConnect(), "primero se quita el grupo")
        val after = m.onConnection(P2pConnection(null), 6_500)
        assertTrue(after.any { it is P2pEffect.GroupLost })
        assertEquals(tv.address, after.connect().target.address)
        assertIs<GroupPhase.Joining>(m.phase)
    }

    @Test
    fun switchingTargetDuringAJoinCancelsItFirst() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car, tv), 0)
        m.userConnect(car.address, 0)
        assertTrue(m.userConnect(car.address, 100).none { it is P2pEffect.Connect }, "el mismo: se sigue esperando")
        val swap = m.userConnect(tv.address, 200)
        assertTrue(P2pEffect.CancelConnect in swap && P2pEffect.RemoveGroup in swap)
        assertEquals(tv.address, m.onRemoveGroupResult(false, 300).connect().target.address)
    }

    @Test
    fun phoneAsGroupOwnerIsWarnedAndUnknownGroupsAreAdopted() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car), 0)
        m.userConnect(car.address, 0)
        val fx = m.onConnection(P2pConnection(group(phoneIsOwner = true)), 1_000)
        assertTrue(fx.any { it is P2pEffect.Note && it.warning && it.text.contains("dueño del grupo") })

        // Un grupo que aparece sin pulsación (p. ej. invitación aceptada en el sistema) se adopta.
        val n = machine()
        ready(n)
        n.onPeers(listOf(car), 0)
        val adopted = n.onConnection(P2pConnection(group()), 1_000)
        assertTrue(adopted.any { it is P2pEffect.GroupFormed })
        assertEquals(car.address, (n.phase as GroupPhase.Formed).target?.address)
    }

    @Test
    fun acceptedBroadcastStopsDiscoveryAndSessionEndRemovesGroupUnlessKept() {
        for (keep in listOf(false, true)) {
            val m = machine(settings.copy(keepGroup = keep))
            ready(m)
            m.onPeers(listOf(car), 0)
            m.userConnect(car.address, 0)
            m.onConnection(P2pConnection(group()), 1_000)
            m.onDiscoveryChanged(true)
            assertEquals(listOf(P2pEffect.StopDiscovery), m.onCarAccepted(), "QDLink: stopPeerDiscovery al aceptar")
            assertFalse(m.searching)
            val end = m.onSessionEnded(60_000)
            assertEquals(!keep, P2pEffect.RemoveGroup in end)
            if (keep) assertIs<GroupPhase.Formed>(m.phase) else assertIs<GroupPhase.Removing>(m.phase)
            assertTrue(m.searching)
        }
    }

    @Test
    fun lostGroupGoesBackToSearch() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car), 0)
        m.userConnect(car.address, 0)
        m.onConnection(P2pConnection(group()), 1_000)
        m.onCarAccepted()
        val lost = m.onConnection(P2pConnection(null), 30_000)
        assertTrue(lost.any { it is P2pEffect.GroupLost })
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.searching)
        assertTrue(m.onTick(31_000).relaunches())
    }

    @Test
    fun autoConnectsToTheLastCarWithBackoff() {
        val m = machine(stored = StoredCar(car.name, car.address, 0))
        ready(m)
        val c = m.onPeers(listOf(tv, car.copy(address = car.address.uppercase())), 0).connect()
        assertTrue(c.auto)
        assertEquals(car.name, c.target.name)
        m.onConnectResult(c.joinId, ok = false, reason = 0, now = 1_000)
        assertEquals(1, m.autoFailures)
        assertEquals(2_000L, m.autoRetryInMs(1_000))
        assertFalse(m.onPeers(listOf(car), 2_000).hasConnect(), "espera de 2 s tras el primer fallo")
        assertTrue(m.onTick(3_000).hasConnect())
        // Al formarse el grupo se pone a cero la cuenta.
        m.onConnection(P2pConnection(group()), 4_000)
        assertEquals(0, m.autoFailures)
    }

    @Test
    fun autoConnectFindsTheCarByNameWhenItsMacChanged() {
        val m = machine(stored = StoredCar(car.name, "aa:bb:cc:dd:ee:99", 0))
        ready(m)
        val fx = m.onPeers(listOf(tv, car), 0)
        assertEquals(car.address, fx.connect().target.address)
        remember(m, fx)
        assertEquals(car.address, m.storedCar?.address, "se guarda la MAC nueva")
        // Ni INVITED ni sin la opción.
        val n = machine(settings.copy(autoConnect = false), StoredCar(car.name, car.address, 0))
        ready(n)
        assertFalse(n.onPeers(listOf(car), 0).hasConnect())
        val o = machine(stored = StoredCar(car.name, car.address, 0))
        ready(o)
        assertFalse(o.onPeers(listOf(car.copy(status = PeerStatus.INVITED)), 0).hasConnect())
    }

    @Test
    fun manualDisconnectPausesSearchAndAutoConnectUntilSearchIsPressed() {
        val m = machine(stored = StoredCar(car.name, car.address, 0))
        ready(m)
        m.onPeers(listOf(car), 0)
        m.onConnection(P2pConnection(group()), 1_000)
        val off = m.userDisconnect(2_000)
        assertTrue(listOf(P2pEffect.StopDiscovery, P2pEffect.CancelConnect, P2pEffect.RemoveGroup, P2pEffect.ClearServiceRequests).all { it in off })
        assertTrue(m.onConnection(P2pConnection(null), 2_500).any { it is P2pEffect.GroupLost })
        assertTrue(m.autoPaused)
        assertFalse(m.onPeers(listOf(car), 3_000).hasConnect())
        assertTrue(m.onTick(10_000).none { it is P2pEffect.Connect || it == P2pEffect.RefreshPeersThenDiscover })
        val search = m.userSearch(11_000)
        assertTrue(P2pEffect.DiscoverPeers in search)
        assertTrue(m.onPeers(listOf(car), 12_000).hasConnect())
    }

    @Test
    fun searchPressedWhileRemovingResumesWhenTheGroupIsGone() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car), 0)
        m.userConnect(car.address, 0)
        m.onConnection(P2pConnection(group()), 1_000)
        m.userDisconnect(2_000)
        val search = m.userSearch(2_100)
        assertFalse(P2pEffect.DiscoverPeers in search)
        assertTrue(search.filterIsInstance<P2pEffect.Note>().single().text.contains("en cuanto se quite"))
        m.onConnection(P2pConnection(null), 2_500)
        assertTrue(m.onTick(3_000).relaunches())
    }

    @Test
    fun p2pDisabledResetsEverythingAndReenablingCleansUpAgain() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car), 0)
        m.userConnect(car.address, 0)
        m.onConnection(P2pConnection(group()), 1_000)
        val off = m.onP2pState(false, 2_000)
        assertTrue(off.any { it is P2pEffect.GroupLost })
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.peers.isEmpty())
        assertFalse(m.enabled)
        assertFalse(m.onTick(10_000).relaunches())
        val on = m.onP2pState(true, 11_000)
        assertTrue(P2pEffect.CancelConnect in on && P2pEffect.RemoveGroup in on)
        // Canal perdido durante la unión: se sale de ella con el motivo.
        m.onRemoveGroupResult(false, 11_100)
        m.onPeers(listOf(car), 11_200)
        m.userConnect(car.address, 11_300)
        m.onChannelLost()
        assertEquals(GroupPhase.Idle, m.phase)
        assertTrue(m.lastFailure.orEmpty().contains("canal"))
    }

    @Test
    fun blockersAreReportedOnceAndPauseSearchAndConnect() {
        val m = machine()
        ready(m)
        val note = m.onBlocker(P2pBlocker.HOTSPOT_ON)
        assertIs<P2pEffect.Note>(note.single())
        assertTrue(m.onBlocker(P2pBlocker.HOTSPOT_ON).isEmpty())
        assertFalse(m.onTick(10_000).relaunches())
        m.onPeers(listOf(car), 10_000)
        assertFalse(m.userConnect(car.address, 10_000).hasConnect())
        assertTrue(m.onBlocker(null).isNotEmpty())
        assertTrue(m.onTick(20_000).relaunches())
    }

    @Test
    fun warnsAtTenAndThirtySecondsWithoutTheCarsBroadcast() {
        val m = machine()
        ready(m)
        m.onPeers(listOf(car), 0)
        m.userConnect(car.address, 0)
        m.onConnection(P2pConnection(group()), 1_000)
        fun warnings(now: Long) = m.onTick(now).filterIsInstance<P2pEffect.Note>().filter { it.warning }.map { it.text }
        assertTrue(warnings(10_999).isEmpty())
        assertTrue(warnings(11_000).single().contains("(10 s)"))
        assertTrue(warnings(12_000).isEmpty())
        assertTrue(warnings(31_000).single().contains("(30 s)"))
        // Con el anuncio ya recibido no hay avisos.
        val n = machine()
        ready(n)
        n.onPeers(listOf(car), 0)
        n.userConnect(car.address, 0)
        n.onConnection(P2pConnection(group()), 1_000)
        timeline.mark(P2pTimeline.Mark.BROADCAST)
        assertTrue(n.onTick(40_000).none { it is P2pEffect.Note && it.warning })
    }

    @Test
    fun autoDelayGrowsUpToThirtySeconds() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map { P2pMachine.autoDelayMs(it) })
    }
}
