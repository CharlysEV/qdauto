package dev.qdauto.carsim.decode

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Resultado de pasar la grabación Annex-B por ffmpeg (`-f h264 -i - -f null -`). */
data class DecodeResult(
    val ffmpeg: File,
    /** Frames que ffmpeg llegó a decodificar (`frame=` del progreso), si lo dijo. */
    val frames: Int?,
    /** Líneas de error del decodificador (stderr con `-loglevel error`). */
    val errorLines: List<String>,
    val exitCode: Int?,
    val elapsedMs: Long,
    /** ffmpeg no se pudo ejecutar o no terminó a tiempo. */
    val failure: String?,
) {
    val ok: Boolean get() = failure == null && exitCode == 0 && errorLines.isEmpty()
}

/** Qué pasó con `--decode`: si se pidió, con qué ffmpeg y el resultado (o por qué no se evaluó). */
data class DecodeOutcome(
    val requested: Boolean,
    val ffmpeg: File?,
    val result: DecodeResult?,
    /** Motivo del SKIP (sin `--decode`, sin ffmpeg, sin vídeo, autotest). */
    val skipReason: String?,
)

/**
 * Comprobación visual "de verdad": el H.264 recibido se decodifica con ffmpeg y cualquier línea de error del
 * decodificador cuenta como fallo. ffmpeg se busca en `--ffmpeg`, en [DEFAULT_PATH] y en el `PATH`.
 */
object Ffmpeg {
    /** `QDAUTO_FFMPEG` o `tools/ffmpeg/...` relativo al directorio de trabajo. */
    val DEFAULT_PATH = File(System.getenv("QDAUTO_FFMPEG") ?: "tools/ffmpeg/ffmpeg-master-latest-win64-gpl/bin/ffmpeg.exe")

    /** Dónde está ffmpeg, o `null`. Con [explicit] solo vale ese (si no existe, `null`). */
    fun locate(explicit: File? = null, path: String? = System.getenv("PATH"), default: File = DEFAULT_PATH): File? {
        if (explicit != null) return explicit.takeIf { it.isFile }
        if (default.isFile) return default
        val names = if (File.separatorChar == '\\') listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg")
        for (dir in path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
            for (name in names) {
                val f = File(dir, name)
                if (f.isFile) return f
            }
        }
        return null
    }

    /** Manda [h264] por la entrada estándar de ffmpeg y recoge sus errores y el número de frames. */
    fun decode(ffmpeg: File, h264: File, timeoutMs: Long = 300_000): DecodeResult {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val progress = try {
            File.createTempFile("carsim-ffmpeg-", ".txt")
        } catch (e: IOException) {
            return DecodeResult(ffmpeg, null, emptyList(), null, elapsed(), "no se pudo crear el fichero de progreso: ${e.message}")
        }
        val command = listOf(
            ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostats", "-progress", progress.path,
            "-f", "h264", "-i", "-", "-f", "null", "-",
        )
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (e: IOException) {
            progress.delete()
            return DecodeResult(ffmpeg, null, emptyList(), null, elapsed(), "no se pudo ejecutar ${ffmpeg.path}: ${e.message}")
        }
        val stderr = StringBuilder()
        val stderrThread = Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { stderr.appendLine(it) }
            } catch (_: IOException) {
            }
        }, "carsim-ffmpeg-stderr").apply { isDaemon = true; start() }
        val stdoutThread = Thread({
            try {
                process.inputStream.use { it.readBytes() }
            } catch (_: IOException) {
            }
        }, "carsim-ffmpeg-stdout").apply { isDaemon = true; start() }
        var feedError: String? = null
        try {
            process.outputStream.use { out ->
                h264.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
        } catch (e: IOException) {
            // ffmpeg cierra su entrada si aborta: los errores salen por stderr.
            feedError = e.message
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            stderrThread.join(1_000)
            progress.delete()
            return DecodeResult(ffmpeg, null, errorLines(stderr.toString()), null, elapsed(), "ffmpeg no terminó en ${timeoutMs / 1000} s")
        }
        stderrThread.join(5_000)
        stdoutThread.join(5_000)
        val frames = try {
            parseFrames(progress.readText())
        } catch (_: IOException) {
            null
        } finally {
            progress.delete()
        }
        val errors = errorLines(stderr.toString())
        val failure = if (feedError != null && errors.isEmpty() && process.exitValue() != 0) "ffmpeg cerró la entrada: $feedError" else null
        return DecodeResult(ffmpeg, frames, errors, process.exitValue(), elapsed(), failure)
    }

    /** Líneas no vacías de stderr (con `-loglevel error` todas son errores). */
    fun errorLines(stderr: String): List<String> = stderr.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    /** Último `frame=N` del fichero de `-progress`. */
    fun parseFrames(progress: String): Int? =
        progress.lines().lastOrNull { it.startsWith("frame=") }?.substringAfter('=')?.trim()?.toIntOrNull()
}
