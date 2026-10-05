package dev.qdauto.carsim

/** Reloj de la prueba: milisegundos (monótonos) desde el arranque, para la cronología y los logs. */
class RunClock(
    val startNanos: Long = System.nanoTime(),
    val startEpochMillis: Long = System.currentTimeMillis(),
) {
    fun elapsedMs(nanos: Long = System.nanoTime()): Long = (nanos - startNanos) / 1_000_000

    fun stamp(): String = Fmt.stamp(elapsedMs())
}
