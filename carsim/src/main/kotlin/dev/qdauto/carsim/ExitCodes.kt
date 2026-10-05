package dev.qdauto.carsim

/** Códigos de salida de la CLI. */
object ExitCodes {
    /** Todas las comprobaciones PASS (o sin evaluar). */
    const val OK = 0

    /** Alguna comprobación FAIL. */
    const val CHECKS_FAILED = 1

    /** Opciones incorrectas. */
    const val USAGE = 2

    /** No se pudo preparar la prueba (puerto ocupado, fichero que no se puede crear...). */
    const val SETUP = 3
}

/** Fallo al preparar la prueba: se informa y se sale con [ExitCodes.SETUP]. */
class SetupException(message: String, cause: Throwable? = null) : Exception(message, cause)
