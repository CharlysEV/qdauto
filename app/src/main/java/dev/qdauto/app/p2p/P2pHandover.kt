package dev.qdauto.app.p2p

/** Qué se hace con un `Connect_Broadcast` en modo Wi-Fi Direct, y por qué (para el log de cada datagrama). */
data class HandoverDecision(val accept: Boolean, val fromGroup: Boolean, val qdlinkRule: Boolean, val reason: String)

/**
 * Traspaso del grupo P2P al UDP/TCP de siempre (spec 05 §2.7 y §8.2 E). QDLink solo mira el nombre; aquí basta con que
 * el broadcast llegue por la red Wi-Fi Direct (IP del GO o subred de la interfaz P2P) y la regla de QDLink se registra,
 * o se exige con el ajuste «Exigir nombre como QDLink».
 */
object P2pHandover {
    /**
     * La regla de QDLink: el nombre P2P pulsado contiene el `DeviceName` del broadcast (QDLink: wificonnection/a.java:505).
     * Con `DeviceName` vacío se cumple siempre; sin pulsación (`uuidName` = "") solo con `DeviceName` vacío.
     */
    fun qdlinkRule(targetName: String?, deviceName: String): Boolean = (targetName ?: "").contains(deviceName)

    /** ¿Viene [source] de la red Wi-Fi Direct: la IP del GO o la subred de la interfaz P2P del móvil? */
    fun fromGroup(source: String, ownerIp: String?, phoneNets: List<Ipv4Net>): Boolean {
        val src = parse(source) ?: return false
        if (ownerIp != null && parse(ownerIp) == src) return true
        return phoneNets.any { net -> parse(net.address)?.let { inSubnet(src, it, net.prefix) } == true }
    }

    fun evaluate(
        source: String,
        deviceName: String,
        handoverOpen: Boolean,
        ownerIp: String?,
        phoneNets: List<Ipv4Net>,
        targetName: String?,
        requireName: Boolean,
    ): HandoverDecision {
        val fromGroup = fromGroup(source, ownerIp, phoneNets)
        val rule = qdlinkRule(targetName, deviceName)
        val (accept, why) = when {
            !handoverOpen -> false to "no hay unión ni grupo Wi-Fi Direct"
            !fromGroup -> false to "no llega por la red Wi-Fi Direct"
            requireName && !rule -> false to "se exige el nombre como QDLink"
            else -> true to "llega por la red Wi-Fi Direct"
        }
        val reason = "subred P2P: ${yes(fromGroup)} · regla QDLink («${targetName.orEmpty()}» contiene «$deviceName»): " +
            "${yes(rule)} → ${if (accept) "se contesta" else "se ignora"} ($why)"
        return HandoverDecision(accept, fromGroup, rule, reason)
    }

    /** IPv4 en notación decimal con puntos a entero, o `null`. */
    fun parse(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (p in parts) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or n
        }
        return value
    }

    fun inSubnet(address: Int, network: Int, prefix: Int): Boolean {
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (address and mask) == (network and mask)
    }

    private fun yes(b: Boolean) = if (b) "sí" else "no"
}
