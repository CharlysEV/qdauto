package dev.qdauto.app.p2p

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/**
 * Permisos de Wi-Fi Direct (spec 05 §7.2). Android 13+: `NEARBY_WIFI_DEVICES`, declarado con `neverForLocation`, así
 * que no hace falta ubicación ni tenerla encendida. Android 10-12: ubicación precisa y la ubicación activada.
 */
object P2pPermissions {
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    /** Lo que se pide. En Android 12 la precisa sola se ignora: hay que pedirla junto con la aproximada. */
    fun requested(sdkInt: Int): List<String> = when {
        sdkInt >= 33 -> listOf(NEARBY_WIFI_DEVICES)
        sdkInt >= 31 -> listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
        else -> listOf(ACCESS_FINE_LOCATION)
    }

    /** El que exigen de verdad `discoverPeers`, `connect`, `requestPeers`… */
    fun essential(sdkInt: Int): String = if (sdkInt >= 33) NEARBY_WIFI_DEVICES else ACCESS_FINE_LOCATION

    fun locationServicesNeeded(sdkInt: Int): Boolean = sdkInt < 33

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(essential(Build.VERSION.SDK_INT)) == PackageManager.PERMISSION_GRANTED

    /** Permisos que hay que pedir (vacío si ya está concedido el esencial). */
    fun missing(context: Context): List<String> = if (granted(context)) emptyList() else requested(Build.VERSION.SDK_INT)

    fun locationEnabled(context: Context): Boolean = try {
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } catch (_: Exception) {
        false
    }

    /** Para el log de capacidades. */
    fun describe(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        val perms = requested(sdk).joinToString(", ") { p ->
            val state = if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "concedido" else "NO"
            "${p.substringAfterLast('.')} $state"
        }
        val location = if (locationServicesNeeded(sdk)) {
            " · ubicación ${if (locationEnabled(context)) "activada" else "DESACTIVADA"}"
        } else {
            " · ubicación no necesaria (neverForLocation)"
        }
        return "permisos: $perms$location"
    }
}
