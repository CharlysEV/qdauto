package dev.qdauto.carsim.cli

import dev.qdauto.carsim.net.BroadcastTargets
import dev.qdauto.carsim.touch.ScriptException
import dev.qdauto.carsim.touch.TouchScripts
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.math.roundToLong

/** Opciones incorrectas: se informa y se sale con código 2. */
class UsageException(message: String) : Exception(message)

/** Análisis a mano de los argumentos: `--opción valor` o `--opción=valor`. */
object ArgParser {
    fun parse(args: Array<String>): Options {
        var o = Options()
        val targets = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (arg == "-h" || arg == "/?") {
                o = o.copy(help = true)
                continue
            }
            if (!arg.startsWith("--") || arg.length == 2) throw UsageException("argumento inesperado '$arg'")
            val eq = arg.indexOf('=')
            val name = if (eq > 0) arg.substring(2, eq) else arg.substring(2)
            val inline = if (eq > 0) arg.substring(eq + 1) else null

            fun value(): String {
                if (inline != null) return inline
                val next = args.getOrNull(i)
                if (next == null || (next.startsWith("--") && next.length > 2)) throw UsageException("falta el valor de --$name")
                i++
                return next
            }

            fun flag(): Boolean {
                if (inline != null) throw UsageException("--$name no lleva valor")
                return true
            }

            when (name) {
                "help", "ayuda" -> o = o.copy(help = flag())
                "self-test", "autotest" -> o = o.copy(selfTest = flag())
                "verbose" -> o = o.copy(verbose = flag())
                "target" -> targets += parseTargets(value())
                "width" -> o = o.copy(width = parseInt(name, value(), 16, 8192))
                "height" -> o = o.copy(height = parseInt(name, value(), 16, 8192))
                "car-type" -> o = o.copy(carType = parseCarType(value()))
                "fps" -> o = o.copy(fps = parseInt(name, value(), 1, 240))
                "bitrate" -> o = o.copy(bitrate = parseBitrate(value()))
                "gop" -> o = o.copy(gop = parseInt(name, value(), 0, 3600))
                "duration" -> o = o.copy(durationMs = parseSeconds(name, value(), 0.0))
                "discovery-timeout" -> o = o.copy(discoveryTimeoutMs = parseSeconds(name, value(), 1.0))
                "out" -> o = o.copy(out = parseFile(name, value()))
                "report" -> o = o.copy(report = parseFile(name, value()))
                "touch-script" -> o = o.copy(touchScript = parseScript(value()))
                else -> throw UsageException("opción desconocida --$name")
            }
        }
        return o.copy(targets = targets)
    }

    private fun parseTargets(v: String): List<String> {
        val list = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) throw UsageException("--target vacío")
        for (t in list) {
            if (t.equals(BroadcastTargets.KEYWORD, ignoreCase = true)) continue
            try {
                InetAddress.getByName(t)
            } catch (_: UnknownHostException) {
                throw UsageException("--target '$t' no es una IP válida ni 'broadcast'")
            }
        }
        return list
    }

    private fun parseInt(name: String, v: String, min: Int, max: Int): Int {
        val n = v.trim().toIntOrNull() ?: throw UsageException("--$name espera un número entero, no '$v'")
        if (n !in min..max) throw UsageException("--$name fuera de rango ($min-$max): $n")
        return n
    }

    /** bit/s, con sufijos k y M: "4M" = 4 000 000, "2.5M", "800k". */
    internal fun parseBitrate(v: String): Int {
        val m = Regex("^(\\d+(?:[.,]\\d+)?)([kKmM]?)$").matchEntire(v.trim())
            ?: throw UsageException("--bitrate espera bit/s (p. ej. 4000000, 4M o 800k), no '$v'")
        val base = m.groupValues[1].replace(',', '.').toDouble()
        val mult = when (m.groupValues[2].lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }
        val bps = (base * mult).roundToLong()
        if (bps !in 1..2_000_000_000L) throw UsageException("--bitrate fuera de rango (1-2000M): $v")
        return bps.toInt()
    }

    private fun parseSeconds(name: String, v: String, min: Double): Long {
        val s = v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw UsageException("--$name espera segundos, no '$v'")
        if (s < min || s > 86_400.0) throw UsageException("--$name fuera de rango (${min.toLong()}-86400 s): $v")
        return (s * 1000).roundToLong()
    }

    private fun parseCarType(v: String): String {
        val t = v.trim()
        if (t.isEmpty() || t.length > 32) throw UsageException("--car-type no válido: '$v'")
        return t
    }

    private fun parseFile(name: String, v: String): File {
        if (v.isBlank()) throw UsageException("--$name espera un nombre de fichero")
        return File(v)
    }

    private fun parseScript(v: String) = try {
        TouchScripts.fromArgument(v)
    } catch (e: ScriptException) {
        throw UsageException(e.message ?: "guion táctil no válido")
    }
}
