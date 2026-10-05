package dev.qdauto.app.link

/**
 * Espera entre intentos automáticos de conexión: exponencial (1, 2, 4, 8, 16, 30 s) tras cada fallo y casi
 * inmediata tras una sesión que llegó a emitir vídeo un rato. Thread-safe.
 */
class ReconnectBackoff(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var failures = 0
    private var notBefore = Long.MIN_VALUE

    @Synchronized
    fun canAttempt(): Boolean = nowMs() >= notBefore

    @Synchronized
    fun remainingMs(): Long = if (notBefore == Long.MIN_VALUE) 0 else (notBefore - nowMs()).coerceAtLeast(0)

    @get:Synchronized
    val failureCount: Int get() = failures

    /** Intento fallido (el coche no conectó o la sesión murió pronto). Devuelve la espera aplicada. */
    @Synchronized
    fun onFailure(): Long {
        failures++
        return delayAtLeast(delayFor(failures))
    }

    /** Fin de sesión: si llegó a emitir vídeo al menos [STABLE_MS], reconexión rápida; si no, cuenta como fallo. */
    @Synchronized
    fun onSessionEnded(streamed: Boolean, durationMs: Long): Long {
        if (streamed && durationMs >= STABLE_MS) {
            failures = 0
            return delayAtLeast(QUICK_RETRY_MS)
        }
        return onFailure()
    }

    /** Aplica una espera sin acortar otra más larga que ya hubiera (p. ej. una [pause] manual). */
    private fun delayAtLeast(delay: Long): Long {
        notBefore = maxOf(notBefore, nowMs() + delay)
        return remainingMs()
    }

    /** No reconectar solos durante [ms] (p. ej. tras pulsar "Desconectar"). */
    @Synchronized
    fun pause(ms: Long) {
        notBefore = maxOf(notBefore, nowMs() + ms)
    }

    @Synchronized
    fun reset() {
        failures = 0
        notBefore = Long.MIN_VALUE
    }

    companion object {
        const val QUICK_RETRY_MS = 1_000L
        const val STABLE_MS = 10_000L
        const val MAX_DELAY_MS = 30_000L

        fun delayFor(failures: Int): Long = minOf(MAX_DELAY_MS, 1_000L shl (failures - 1).coerceIn(0, 5))
    }
}
