package dev.qdauto.app.link

/**
 * Lo que comparten el servicio y la actividad dentro del proceso: el motor existe mientras el servicio en primer
 * plano está en marcha, y sobrevive a la destrucción de la actividad.
 */
object LinkRuntime {
    @Volatile
    var engine: LinkEngine? = null

    /** Último problema al arrancar el servicio (p. ej. primer plano denegado), para mostrarlo en la UI. */
    @Volatile
    var serviceError: String? = null

    /** Excepción no capturada en un hilo de fondo (la app sigue viva; ver CrashGuard). */
    @Volatile
    var internalError: String? = null

    val selfTest = SelfTest()
}
