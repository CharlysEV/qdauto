package dev.qdauto.app.log

import android.util.Log
import dev.qdauto.app.util.Clock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Escribe el log de la sesión en un fichero desde un hilo propio (`qd-app-logfile`), para no frenar nunca los hilos
 * del protocolo. Un fichero por arranque del proceso (`qdauto-AAAAMMDD-HHMMSS.log`), con partes nuevas cada
 * [MAX_FILE_CHARS]; se borran los más antiguos si se pasa de [MAX_FILES] o [MAX_TOTAL_BYTES].
 */
internal class LogFileWriter(private val dir: File, private val header: String) {
    private class FlushRequest(val latch: CountDownLatch)

    private val queue = LinkedBlockingQueue<Any>(QUEUE_CAPACITY)
    private val dropped = AtomicLong()
    private val baseName = "qdauto-" + Clock.stamp()
    private var out: Writer? = null
    private var written = 0L
    private var part = 1

    @Volatile
    var currentFile: File? = null
        private set

    fun start() {
        Thread({ run() }, "qd-app-logfile").apply {
            isDaemon = true
            start()
        }
    }

    fun write(line: String) {
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    /** Espera a que todo lo encolado esté en disco. */
    fun flush(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        if (!queue.offer(FlushRequest(latch))) return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun run() {
        cleanup()
        open()
        val batch = ArrayList<Any>(1024)
        while (true) {
            try {
                batch.add(queue.take())
                queue.drainTo(batch, 4096)
                for (item in batch) {
                    when (item) {
                        is String -> append(item)
                        is FlushRequest -> {
                            flushOut()
                            item.latch.countDown()
                        }
                    }
                }
                batch.clear()
                val lost = dropped.getAndSet(0)
                if (lost > 0) append("${Clock.now()} W/QD/Log: $lost líneas perdidas (cola del log llena)")
                flushOut()
            } catch (_: InterruptedException) {
                flushOut()
                return
            } catch (t: Throwable) {
                Log.e(TAG, "error en el escritor del log", t)
                batch.clear()
            }
        }
    }

    private fun append(line: String) {
        val w = out ?: return
        try {
            w.write(line)
            w.write('\n'.code)
            written += line.length + 1
            if (written > MAX_FILE_CHARS) {
                part++
                w.write("--- continúa en la parte $part ---\n")
                open()
            }
        } catch (e: IOException) {
            Log.e(TAG, "no se pudo escribir el log", e)
            open()
        }
    }

    private fun flushOut() {
        try {
            out?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "no se pudo vaciar el log", e)
        }
    }

    private fun open() {
        try {
            out?.close()
        } catch (_: IOException) {
        }
        out = null
        try {
            dir.mkdirs()
            val name = if (part == 1) "$baseName.log" else "$baseName-p$part.log"
            val f = File(dir, name)
            out = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8), 64 * 1024)
            currentFile = f
            written = f.length()
            out?.write(header)
            out?.write("\n")
        } catch (e: IOException) {
            Log.e(TAG, "no se pudo abrir el fichero de log en $dir", e)
        }
    }

    private fun cleanup() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("qdauto-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FILES || total > MAX_TOTAL_BYTES) f.delete()
        }
        // Vídeo recibido por las autopruebas: solo las últimas.
        dir.listFiles { f -> f.isFile && f.name.startsWith("autoprueba-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_SELFTEST_FILES)
            ?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "QD/Log"
        const val QUEUE_CAPACITY = 200_000
        const val MAX_FILE_CHARS = 64L * 1024 * 1024
        const val MAX_FILES = 40
        const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
        const val MAX_SELFTEST_FILES = 3
    }
}
