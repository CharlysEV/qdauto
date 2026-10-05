package dev.qdauto.app.log

import android.content.Context
import android.util.Log
import dev.qdauto.app.util.Clock
import dev.qdauto.core.util.Hex
import dev.qdauto.core.util.LogLevel
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.FunctionIds
import dev.qdauto.core.wire.TraceEvent
import java.io.File

/**
 * Log de la app: Logcat + un anillo en memoria (lo que muestra la UI) + el fichero de la sesión en
 * `getExternalFilesDir("logs")`. El fichero lo recibe todo (cada mensaje con su volcado hex); la UI no muestra vídeo,
 * heartbeats, WhitelistAppOn ni táctil, que tienen sus propios paneles.
 */
object AppLog {
    class Line(val seq: Long, val level: LogLevel, val text: String)

    private const val RING_CAPACITY = 3_000
    private const val MAX_HEX_BYTES = 4_096

    private val lock = Any()
    private val ring = ArrayDeque<Line>(RING_CAPACITY)
    private var nextSeq = 1L

    @Volatile
    private var writer: LogFileWriter? = null

    @Volatile
    private var dir: File? = null

    /** Para `:core` (DiscoveryListener, PhoneLink, PhoneSession, CarSim). */
    val qdLog: QdLog = QdLog { level, tag, message, error -> log(level, tag, message, error) }

    fun init(context: Context, header: String) {
        if (writer != null) return
        val d = logDir(context)
        dir = d
        writer = LogFileWriter(d, header).also { it.start() }
    }

    fun logDir(context: Context): File =
        (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).apply { mkdirs() }

    fun currentFile(): File? = writer?.currentFile

    fun directory(): File? = dir

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, tag, if (error == null) message else message + "\n" + Log.getStackTraceString(error))
        val line = "${Clock.now()} ${level.name[0]}/$tag [${Thread.currentThread().name}]: $message" +
            (error?.let { " | $it" } ?: "")
        addToRing(level, line)
        writer?.write(if (error == null) line else line + "\n" + Log.getStackTraceString(error))
    }

    /** Solo al fichero (volcados hex, trazas de vídeo). */
    fun fileOnly(text: String) {
        writer?.write(text)
    }

    /** Detalle con hora y etiqueta, solo al fichero y a Logcat (inundaría la vista: extras de broadcasts, peers…). */
    fun detail(tag: String, message: String) {
        Log.d(tag, message)
        writer?.write("${Clock.now()} D/$tag [${Thread.currentThread().name}]: $message")
    }

    /** Una línea por mensaje que entra o sale de la sesión, con el volcado hex completo en el fichero. */
    fun trace(event: TraceEvent, sessionId: Int?) {
        val arrow = if (event.direction == Direction.IN) "<-" else "->"
        val session = sessionId?.let { "S$it " } ?: ""
        val line = "${Clock.time(event.timeMillis)} T/$session$arrow ${event.kind} (${event.size} B) ${event.summary}"
        val w = writer
        if (w != null) {
            w.write(line)
            val bytes = event.bytes
            if (bytes != null && bytes.isNotEmpty()) w.write(Hex.dump(bytes, maxBytes = MAX_HEX_BYTES))
        }
        if (event.isVideo) return
        Log.i("QD/Trace", line)
        if (showInUi(event)) addToRing(LogLevel.INFO, line)
    }

    /** Líneas del anillo posteriores a [afterSeq] (como mucho [max], las más recientes). */
    fun linesAfter(afterSeq: Long, max: Int): List<Line> = synchronized(lock) {
        val newer = ring.filter { it.seq > afterSeq }
        if (newer.size > max) newer.takeLast(max) else newer
    }

    /** Vuelca el fichero a disco (export y cierre por excepción). */
    fun flush(timeoutMs: Long): Boolean = writer?.flush(timeoutMs) ?: true

    private fun showInUi(event: TraceEvent): Boolean = when (event.kind) {
        Cmd.HEARTBEAT, "TOUCH", "Mirror/${FunctionIds.WHITELIST_APP_ON}" -> false
        else -> true
    }

    private fun addToRing(level: LogLevel, text: String) {
        synchronized(lock) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(Line(nextSeq++, level, text))
        }
    }
}
