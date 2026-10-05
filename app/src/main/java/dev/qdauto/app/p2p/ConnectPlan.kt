package dev.qdauto.app.p2p

import dev.qdauto.app.settings.WpsMode

/**
 * Campos de `WifiP2pConfig` para `connect()`. `null` = no tocar el campo y dejar el valor del constructor simple, como
 * QDLink (spec 05 §5.1): `groupOwnerIntent` -1 (automático), WPS PBC sin PIN y grupo persistente.
 */
data class ConnectPlan(val deviceAddress: String, val groupOwnerIntent: Int?, val wpsSetup: Int?, val wpsPin: String?) {
    /** Una configuración que fallará seguro. */
    val problem: String?
        get() = if (wpsSetup == WPS_KEYPAD && wpsPin == null) "WPS «PIN que muestra el coche» sin PIN en los ajustes" else null

    fun describe(): String = "deviceAddress=$deviceAddress · groupOwnerIntent=" +
        (groupOwnerIntent?.toString() ?: "sin tocar (-1, automático)") +
        " · wps=" + (wpsSetup?.let(P2pCodes::wps) ?: "sin tocar (PBC)") + (wpsPin?.let { " PIN $it" } ?: "")

    companion object {
        // SDK: WpsInfo.PBC 0, DISPLAY 1, KEYPAD 2.
        const val WPS_PBC = 0
        const val WPS_DISPLAY = 1
        const val WPS_KEYPAD = 2

        fun of(deviceAddress: String, goIntent: Int, wps: WpsMode, pin: String): ConnectPlan = ConnectPlan(
            deviceAddress = deviceAddress,
            // QDLink: wifidirect/c.java:990-997 (rol 0 y modo 0 → 0). Fuera de 0..15 no se toca: automático.
            groupOwnerIntent = goIntent.takeIf { it in 0..15 },
            wpsSetup = when (wps) {
                WpsMode.QDLINK -> null
                WpsMode.PBC -> WPS_PBC
                WpsMode.DISPLAY -> WPS_DISPLAY
                WpsMode.KEYPAD -> WPS_KEYPAD
            },
            wpsPin = pin.trim().takeIf { wps == WpsMode.KEYPAD && it.isNotEmpty() },
        )
    }
}
