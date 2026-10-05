package dev.qdauto.app.util

/** Eventos por segundo en una ventana deslizante de 1 s. Thread-safe. */
class RateMeter(private val windowNanos: Long = 1_000_000_000L) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun mark(nowNanos: Long = System.nanoTime()) {
        times.addLast(nowNanos)
        trim(nowNanos)
    }

    @Synchronized
    fun perSecond(nowNanos: Long = System.nanoTime()): Double {
        trim(nowNanos)
        return times.size * 1e9 / windowNanos
    }

    private fun trim(nowNanos: Long) {
        while (times.isNotEmpty() && nowNanos - times.first() > windowNanos) times.removeFirst()
    }
}
