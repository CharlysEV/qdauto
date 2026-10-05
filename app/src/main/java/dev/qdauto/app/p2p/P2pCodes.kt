package dev.qdauto.app.p2p

/** Nombres de los códigos de `WifiP2pManager` para el log y la UI (valores del SDK de android-36). */
object P2pCodes {
    /** `ActionListener.onFailure`: ERROR 0, P2P_UNSUPPORTED 1, BUSY 2, NO_SERVICE_REQUESTS 3, NO_PERMISSION 4 (API 36). */
    fun failure(code: Int): String = "$code (" + when (code) {
        0 -> "ERROR"
        1 -> "P2P_UNSUPPORTED"
        2 -> "BUSY"
        3 -> "NO_SERVICE_REQUESTS"
        4 -> "NO_PERMISSION"
        else -> "?"
    } + ")"

    fun failureEs(code: Int): String = when (code) {
        0 -> "error interno de Android"
        1 -> "Wi-Fi Direct no soportado"
        2 -> "Wi-Fi Direct ocupado"
        3 -> "sin peticiones de servicio"
        4 -> "falta el permiso"
        else -> "motivo desconocido"
    }

    /** `WifiP2pListener.onGroupCreationFailed` (API 35). */
    fun groupFailure(code: Int): String = "$code (" + when (code) {
        0 -> "CONNECTION_CANCELLED"
        1 -> "TIMED_OUT"
        2 -> "USER_REJECTED"
        3 -> "PROVISION_DISCOVERY_FAILED"
        4 -> "GROUP_REMOVED"
        5 -> "INVITATION_FAILED"
        else -> "?"
    } + ")"

    fun groupFailureEs(code: Int): String = when (code) {
        0 -> "conexión cancelada"
        1 -> "tiempo agotado"
        2 -> "rechazada (¿había que aceptar en la pantalla del coche?)"
        3 -> "falló el emparejamiento WPS (provision discovery)"
        4 -> "grupo eliminado"
        5 -> "falló la invitación a un grupo recordado"
        else -> "motivo desconocido"
    }

    /** `WIFI_P2P_STATE_CHANGED`: DISABLED 1, ENABLED 2. */
    fun p2pState(state: Int): String = "$state (" + when (state) {
        1 -> "DISABLED"
        2 -> "ENABLED"
        else -> "?"
    } + ")"

    /** `WIFI_P2P_DISCOVERY_CHANGED` y estado de escucha (API 34): STOPPED 1, STARTED 2. */
    fun discoveryState(state: Int): String = "$state (" + when (state) {
        1 -> "STOPPED"
        2 -> "STARTED"
        else -> "?"
    } + ")"

    /** `android.net.wifi.WIFI_AP_STATE_CHANGED` (no pública; QDLink: wifidirect/c.java:605-640). */
    fun apState(state: Int): String = "$state (" + when (state) {
        10 -> "DISABLING"
        11 -> "DISABLED"
        12 -> "ENABLING"
        13 -> "ENABLED"
        14 -> "FAILED"
        else -> "?"
    } + ")"

    /** Zona Wi-Fi activándose o activada: QDLink vacía la lista de peers (spec 05 §3.1). */
    fun apOn(state: Int?): Boolean = state == 12 || state == 13

    /** `WpsInfo.setup`: PBC 0, DISPLAY 1, KEYPAD 2, LABEL 3, INVALID 4. */
    fun wps(setup: Int): String = when (setup) {
        0 -> "PBC"
        1 -> "DISPLAY"
        2 -> "KEYPAD"
        3 -> "LABEL"
        4 -> "INVALID"
        else -> setup.toString()
    }
}
