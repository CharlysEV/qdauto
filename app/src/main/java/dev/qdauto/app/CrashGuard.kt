package dev.qdauto.app

import android.os.Looper
import dev.qdauto.app.link.LinkRuntime
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.util.Clock

/**
 * Red de seguridad para el viaje de prueba: toda excepción no capturada queda en el log (y se vuelca a disco). Si
 * ocurre en un hilo de fondo de la app o de `:core` (nombres `qd-…`), la app sigue viva y se muestra el error en la UI
 * en vez de perder la sesión y el log; en el hilo principal se deja el comportamiento normal (cierre).
 */
object CrashGuard {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLog.e("QD/Crash", "excepción no capturada en el hilo ${thread.name}", error)
                AppLog.flush(2_000)
            } catch (_: Throwable) {
            }
            val background = thread !== Looper.getMainLooper().thread && thread.name.startsWith("qd-")
            if (background) {
                LinkRuntime.internalError = "${Clock.now()} hilo ${thread.name}: $error"
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
