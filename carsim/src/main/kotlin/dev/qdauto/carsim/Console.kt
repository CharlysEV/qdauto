package dev.qdauto.carsim

import dev.qdauto.core.util.LogLevel
import dev.qdauto.core.util.QdLog
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Salida de la CLI. Un único hilo escribe en la consola, así que los hilos del simulador nunca se quedan bloqueados
 * escribiendo (en Windows basta con seleccionar texto en la consola para congelar la salida, y eso falsearía las
 * medidas de vídeo). En una consola interactiva la línea de estado se reescribe en su sitio y el resto de líneas
 * se escriben por encima de ella.
 */
object Console {
    /** Salida a una consola de verdad. Desde JDK 22 `System.console()` existe aunque se redirija: se mira `isTerminal()`. */
    val interactive: Boolean = isTerminal()

    private sealed interface Item
    private class Line(val text: String, val error: Boolean) : Item
    private class Status(val text: String?) : Item
    private class Flush(val done: CountDownLatch) : Item

    private const val MAX_PENDING = 20_000
    private val queue = LinkedBlockingQueue<Item>()
    private val dropped = AtomicInteger()

    init {
        Thread(::printLoop, "carsim-consola").apply {
            isDaemon = true
            start()
        }
    }

    /** Si la salida no es una consola (Git Bash, fichero) y nadie ha elegido codificación, se escribe en UTF-8. */
    fun install() {
        if (!interactive && System.getProperty("stdout.encoding") == System.getProperty("native.encoding")) {
            System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
            System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
        }
    }

    private fun isTerminal(): Boolean {
        val console = System.console() ?: return false
        return try {
            java.io.Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
        } catch (_: ReflectiveOperationException) {
            true // JDK 21 o anterior: si hay Console, es una terminal
        }
    }

    fun line(text: String) = offer(Line(text, false))

    fun error(text: String) = offer(Line(text, true))

    /** Línea de estado: en consola se reescribe en su sitio; si no, se escribe como una línea más. */
    fun status(text: String) = if (interactive) offer(Status(text)) else line(text)

    fun clearStatus() {
        if (interactive) offer(Status(null))
    }

    /** Espera a que se haya escrito todo lo pendiente. */
    fun flush(timeoutMs: Long = 5_000) {
        val done = CountDownLatch(1)
        queue.offer(Flush(done))
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * `offer` y no `put`: la cola no tiene límite, así que nunca espera, y `put` lanza `InterruptedException` si el
     * hilo que escribe tiene la marca de interrupción (p. ej. el guion táctil tras pararlo, o los hilos de `CarSim`).
     */
    private fun offer(item: Item) {
        if (item is Line && !item.error && queue.size >= MAX_PENDING) {
            dropped.incrementAndGet()
            return
        }
        queue.offer(item)
    }

    private fun printLoop() {
        var status: String? = null
        var shown = 0
        while (true) {
            when (val item = queue.take()) {
                is Line -> {
                    shown = erase(shown)
                    val skipped = dropped.getAndSet(0)
                    if (skipped > 0) System.out.println("($skipped líneas omitidas: la consola no daba abasto)")
                    if (item.error) {
                        System.out.flush()
                        System.err.println(item.text)
                        System.err.flush()
                    } else {
                        System.out.println(item.text)
                    }
                    status?.let { shown = draw(it) }
                }
                is Status -> {
                    shown = erase(shown)
                    status = item.text
                    status?.let { shown = draw(it) }
                }
                is Flush -> {
                    System.out.flush()
                    System.err.flush()
                    item.done.countDown()
                }
            }
        }
    }

    private fun erase(shown: Int): Int {
        if (shown > 0) System.out.print("\r" + " ".repeat(shown) + "\r")
        return 0
    }

    private fun draw(text: String): Int {
        System.out.print("\r" + text)
        System.out.flush()
        return text.length
    }
}

/**
 * Logs de `:core` por la consola, con la marca de tiempo de la prueba. Sin `--verbose` solo salen avisos y errores,
 * y los avisos de vídeo de cada frame se cortan tras los primeros (el informe final los resume todos).
 */
class ConsoleLog(
    private val clock: RunClock,
    private val verbose: Boolean,
    private val prefix: String = "",
) : QdLog {
    private val videoWarnings = AtomicInteger()

    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (!verbose && level < LogLevel.WARN) return
        if (!verbose && message.startsWith("vídeo #")) {
            val n = videoWarnings.incrementAndGet()
            if (n > MAX_VIDEO_WARNINGS) {
                if (n == MAX_VIDEO_WARNINGS + 1) Console.line("${clock.stamp()}  (hay más errores de vídeo: se resumen en el informe final)")
                return
            }
        }
        val text = buildString {
            append(clock.stamp()).append("  ").append(prefix).append(level.name[0]).append('/').append(tag).append(": ").append(message)
            if (error != null) append('\n').append(error.stackTraceToString().trimEnd())
        }
        Console.line(text)
    }

    private companion object {
        const val MAX_VIDEO_WARNINGS = 5
    }
}
