package dev.qdauto.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dev.qdauto.app.log.AppLog

/**
 * Bloqueos que mantienen el enlace vivo con la pantalla apagada mientras corre el servicio:
 * - `MulticastLock`: que el filtro de paquetes de la Wi-Fi no tire los broadcast UDP del coche
 *   (CHANGE_WIFI_MULTICAST_STATE);
 * - `WifiLock` de baja latencia: sin ahorro de energía en la Wi-Fi cliente (WAKE_LOCK). Android solo lo aplica con la
 *   pantalla encendida y la app en primer plano; en modo zona Wi-Fi el móvil es el punto de acceso y no influye;
 * - `WakeLock` parcial: la CPU sigue con la pantalla apagada (WAKE_LOCK).
 */
class SystemLocks(context: Context) {
    private val appContext = context.applicationContext
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire() {
        val wm = appContext.getSystemService(WifiManager::class.java)
        val pm = appContext.getSystemService(PowerManager::class.java)
        if (multicast == null) {
            multicast = attempt("MulticastLock") {
                wm?.createMulticastLock(TAG_LOCK)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
        if (wifi == null) {
            wifi = attempt("WifiLock") {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG_LOCK)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
        if (wake == null) {
            wake = attempt("WakeLock") {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_LOCK)?.apply {
                    setReferenceCounted(false)
                    // Dura lo que el servicio (se libera en release()); el límite es solo una red de seguridad.
                    acquire(MAX_WAKE_MS)
                }
            }
        }
        AppLog.i(TAG, "bloqueos: ${describe()}")
    }

    @Synchronized
    fun release() {
        multicast?.let { l -> attempt("liberar MulticastLock") { if (l.isHeld) l.release() } }
        wifi?.let { l -> attempt("liberar WifiLock") { if (l.isHeld) l.release() } }
        wake?.let { l -> attempt("liberar WakeLock") { if (l.isHeld) l.release() } }
        multicast = null
        wifi = null
        wake = null
        AppLog.i(TAG, "bloqueos liberados")
    }

    @Synchronized
    fun describe(): String =
        "multicast=${held(multicast?.isHeld)} wifi=${held(wifi?.isHeld)} cpu=${held(wake?.isHeld)}"

    private fun held(v: Boolean?): String = when (v) {
        true -> "sí"
        false -> "no"
        null -> "—"
    }

    private fun <T> attempt(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        AppLog.e(TAG, "$what falló", e)
        null
    }

    private companion object {
        const val TAG = "QD/Locks"
        const val TAG_LOCK = "qdauto:link"
        const val MAX_WAKE_MS = 10L * 60 * 60 * 1000
    }
}
