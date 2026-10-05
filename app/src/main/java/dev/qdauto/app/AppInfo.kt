package dev.qdauto.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Versión de la app y del teléfono, para la cabecera del log y la exportación. */
object AppInfo {
    fun describe(context: Context): String {
        val version = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: Exception) {
            "?"
        }
        return "QDAuto Test $version · ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT}) · ${Build.FINGERPRINT}"
    }

    /** Requiere `<queries>` en el manifiesto para ver el paquete (Android 11+). */
    fun isInstalled(context: Context, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
