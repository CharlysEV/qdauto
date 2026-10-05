package dev.qdauto.app.p2p

/** Foto inmutable del modo Wi-Fi Direct para la UI, la notificación, la exportación y el filtro del broadcast. */
data class P2pStatus(
    val supported: Boolean,
    val enabled: Boolean,
    val blocker: P2pBlocker?,
    val discovery: DiscoveryPhase,
    val searching: Boolean,
    val phase: GroupPhase,
    /** CONNECTED, INVITED y AVAILABLE, como QDLink, ordenados (el último coche primero). */
    val peers: List<PeerView>,
    /** FAILED y UNAVAILABLE: no se muestran, pero van al log. */
    val hiddenPeers: Int,
    val thisDevice: String?,
    val storedCar: StoredCar?,
    val autoConnect: Boolean,
    val autoPaused: Boolean,
    val autoRetryInMs: Long,
    val lastFailure: String?,
    val discoveryFailure: String?,
    val hotspotState: Int?,
    val timeline: String,
    val capabilities: String,
    val servicesSeen: Int,
) {
    val group: P2pGroupInfo? get() = (phase as? GroupPhase.Formed)?.group

    val target: P2pTarget?
        get() = when (val p = phase) {
            is GroupPhase.Joining -> p.target
            is GroupPhase.Formed -> p.target
            else -> null
        }

    /** Con una unión en curso o un grupo, se contesta al broadcast que llegue por la red P2P (spec 05 §8.2 E.14). */
    val handoverOpen: Boolean get() = phase is GroupPhase.Joining || phase is GroupPhase.Formed

    companion object {
        fun initial(storedCar: StoredCar?, autoConnect: Boolean) = P2pStatus(
            supported = true, enabled = false, blocker = null, discovery = DiscoveryPhase.STOPPED, searching = false,
            phase = GroupPhase.Idle, peers = emptyList(), hiddenPeers = 0, thisDevice = null, storedCar = storedCar,
            autoConnect = autoConnect, autoPaused = false, autoRetryInMs = 0, lastFailure = null, discoveryFailure = null,
            hotspotState = null,
            timeline = "", capabilities = "", servicesSeen = 0,
        )
    }
}
