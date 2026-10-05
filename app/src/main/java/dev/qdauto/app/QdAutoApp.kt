package dev.qdauto.app

import android.app.Application
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.service.LinkNotifications
import dev.qdauto.app.util.Clock

class QdAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val header = "===== QDAuto Test · ${Clock.dateTime()} · ${AppInfo.describe(this)} ====="
        AppLog.init(this, header)
        CrashGuard.install()
        LinkNotifications.createChannel(this)
        AppLog.i("QD/App", "proceso iniciado; log en ${AppLog.directory()}")
    }
}
