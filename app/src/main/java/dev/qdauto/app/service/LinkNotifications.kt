package dev.qdauto.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import dev.qdauto.app.R
import dev.qdauto.app.ui.MainActivity

/** Canal y notificación del servicio en primer plano. */
object LinkNotifications {
    const val CHANNEL_ID = "qdauto_link"
    const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Enlace con el coche", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Estado del servicio QDAuto (descubrimiento, sesión y vídeo)"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun build(context: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, LinkService::class.java).setAction(LinkService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_stop), "Detener", stop).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun update(context: Context, title: String, text: String) {
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(context, title, text))
        } catch (_: SecurityException) {
            // Sin POST_NOTIFICATIONS: el servicio sigue funcionando sin notificación visible.
        }
    }
}
