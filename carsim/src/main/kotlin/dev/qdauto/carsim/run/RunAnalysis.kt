package dev.qdauto.carsim.run

import dev.qdauto.carsim.cli.Options
import dev.qdauto.carsim.report.Check
import dev.qdauto.carsim.report.CheckStatus
import dev.qdauto.carsim.touch.StepResult
import dev.qdauto.carsim.touch.TouchScript
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimReport
import dev.qdauto.core.sim.SentTouch
import java.io.File

/** Por qué terminó la prueba. */
enum class StopReason(val text: String) {
    DURATION("duración alcanzada"),
    USER("parada a mano (Ctrl+C)"),
    PHONE_CLOSED("la conexión se cerró antes de tiempo"),
    NO_PHONE("no se llegó a conectar con el teléfono"),
}

/** Cómo fue el guion táctil. */
data class ScriptOutcome(
    val script: TouchScript,
    val startedAtMs: Long?,
    val startedWithoutVideo: Boolean,
    val completed: Boolean,
    val results: List<StepResult>,
)

/** Todo lo que se sabe al terminar, con el simulador ya cerrado. */
class RunAnalysis(
    val options: Options,
    val settings: RunSettings,
    val simConfig: CarSimConfig,
    val stopReason: StopReason,
    val endMs: Long,
    val report: CarSimReport,
    val fanoutBroadcasts: Int,
    val recorder: RecorderSnapshot,
    val video: VideoSummary,
    val sps: List<SpsResult>,
    val script: ScriptOutcome,
    val sentTouches: List<SentTouch>,
    /** Grabación del vídeo si se pidió con `--out` (la temporal se borra). */
    val savedVideo: File?,
) {
    val connected: Boolean get() = report.connectedTo != null
    val totalBroadcasts: Int get() = report.broadcastsSent + fanoutBroadcasts
}

/** Lo que añade el autotest (o cualquier otro modo) al final de la prueba. */
interface RunExtension {
    /** Se llama con el simulador ya cerrado y antes de imprimir el informe. */
    fun finish(analysis: RunAnalysis): ExtensionResult
}

/** Comprobaciones, líneas para el informe de texto y datos para el JSON de una [RunExtension]. */
data class ExtensionResult(
    val title: String,
    val checks: List<Check>,
    val lines: List<String>,
    val json: Map<String, Any?>,
)

/** Resultado de una prueba completa. */
data class RunResult(val checks: List<Check>, val exitCode: Int) {
    val passed: Boolean get() = checks.none { it.status == CheckStatus.FAIL }
}
