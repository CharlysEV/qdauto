package dev.qdauto.app.p2p

import android.content.Context
import androidx.core.content.edit

/** Último coche Wi-Fi Direct (nombre y MAC P2P), para la conexión automática; sobrevive a reinicios. */
class P2pCarStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): StoredCar? {
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return StoredCar(prefs.getString(KEY_NAME, null).orEmpty(), address, prefs.getLong(KEY_TIME, 0))
    }

    fun save(target: P2pTarget): StoredCar {
        val car = StoredCar(target.name, target.address, System.currentTimeMillis())
        prefs.edit {
            putString(KEY_NAME, car.name)
            putString(KEY_ADDRESS, car.address)
            putLong(KEY_TIME, car.savedAtMillis)
        }
        return car
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val PREFS = "qdauto_p2p"
        const val KEY_NAME = "last_car_name"
        const val KEY_ADDRESS = "last_car_address"
        const val KEY_TIME = "last_car_time"
    }
}
