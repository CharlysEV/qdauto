package dev.qdauto.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.qdauto.app.link.LinkEngine
import dev.qdauto.app.link.LinkRuntime
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.ui.StatusText

/**
 * Servicio en primer plano de tipo `connectedDevice` (interacción con un dispositivo externo por red: el coche).
 * Mantiene el [LinkEngine] y los [SystemLocks] mientras está en marcha, con o sin actividad y con la pantalla
 * apagada. Requisitos de Android 14+: FOREGROUND_SERVICE_CONNECTED_DEVICE y, como requisito previo, declarar uno de
 * CHANGE_NETWORK_STATE / CHANGE_WIFI_MULTICAST_STATE (declaramos los dos).
 */
class LinkService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locks: SystemLocks
    private var engine: LinkEngine? = null
    private var foreground = false
    private var lastText: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            refreshNotification()
            handler.postDelayed(this, NOTIFICATION_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locks = SystemLocks(this)
        AppLog.i(TAG, "servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "onStartCommand acción=${intent?.action} flags=$flags id=$startId")
        if (intent?.action == ACTION_STOP) {
            shutdown("botón Detener")
            return START_NOT_STICKY
        }
        // START o reinicio del sistema (intent nulo): primero a primer plano, siempre.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) {
            locks.acquire()
            val e = LinkEngine(applicationContext) { locks.describe() }
            engine = e
            LinkRuntime.engine = e
            LinkRuntime.serviceError = null
            e.start()
            handler.post(ticker)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        AppLog.i(TAG, "servicio destruido")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean {
        if (foreground) return true
        return try {
            startForeground(
                LinkNotifications.NOTIFICATION_ID,
                LinkNotifications.build(this, "QDAuto", "Arrancando…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foreground = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (desde segundo plano) o SecurityException (requisitos del tipo).
            AppLog.e(TAG, "no se pudo pasar a primer plano", e)
            LinkRuntime.serviceError = "No se pudo iniciar el servicio en primer plano: $e"
            false
        }
    }

    private fun shutdown(reason: String) {
        AppLog.i(TAG, "deteniendo el servicio: $reason")
        stopEngine()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun stopEngine() {
        handler.removeCallbacks(ticker)
        val e = engine ?: return
        engine = null
        if (LinkRuntime.engine === e) LinkRuntime.engine = null
        e.shutdown()
        locks.release()
    }

    private fun refreshNotification() {
        val e = engine ?: return
        val status = try {
            e.snapshot()
        } catch (ex: Exception) {
            AppLog.w(TAG, "no se pudo leer el estado", ex)
            return
        }
        val title = "QDAuto · " + StatusText.linkTitle(status)
        val text = StatusText.notificationLine(status)
        val key = "$title|$text"
        if (key == lastText) return
        lastText = key
        LinkNotifications.update(this, title, text)
    }

    companion object {
        private const val TAG = "QD/Service"
        private const val NOTIFICATION_PERIOD_MS = 2_000L
        const val ACTION_START = "dev.qdauto.app.action.START"
        const val ACTION_STOP = "dev.qdauto.app.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LinkService::class.java).setAction(ACTION_STOP))
        }
    }
}
