package dev.qdauto.app.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Marcas de tiempo para el log y la UI (`DateTimeFormatter` es thread-safe). */
object Clock {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun now(): String = LocalTime.now().format(TIME)

    fun time(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(TIME)

    /** `20261004-143012`, para nombres de fichero. */
    fun stamp(): String = LocalDateTime.now().format(STAMP)

    fun dateTime(): String = LocalDateTime.now().format(DATE_TIME)

    /** "3 s", "2 min 05 s", "1 h 02 min". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "%d min %02d s".format(Locale.ROOT, s / 60, s % 60)
            else -> "%d h %02d min".format(Locale.ROOT, s / 3600, (s % 3600) / 60)
        }
    }

    /** "hace 3 s". */
    fun ago(epochMillis: Long): String = "hace " + duration(System.currentTimeMillis() - epochMillis)
}
