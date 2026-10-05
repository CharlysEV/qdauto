package dev.qdauto.carsim.touch

import dev.qdauto.carsim.Fmt
import dev.qdauto.core.wire.FunctionIds
import java.io.File
import java.io.IOException
import java.text.Normalizer

/** Error en un guion táctil. */
class ScriptException(message: String) : Exception(message)

/** Coordenada del guion: píxeles del coche o porcentaje de su ancho/alto. */
data class Coord(val value: Float, val percent: Boolean) {
    fun resolve(size: Int): Float = if (percent) value * size / 100f else value

    override fun toString(): String = Fmt.num(value) + if (percent) "%" else ""
}

/** Un paso del guion. [text] es la forma canónica (en español) para logs e informe. */
sealed class ScriptStep {
    abstract val text: String
}

data class WaitStep(val ms: Long) : ScriptStep() {
    override val text: String get() = "espera $ms"
}

data class TapStep(val x: Coord, val y: Coord, val holdMs: Long) : ScriptStep() {
    override val text: String get() = "toque $x $y $holdMs"
}

data class DragStep(val x0: Coord, val y0: Coord, val x1: Coord, val y1: Coord, val durationMs: Long, val steps: Int) : ScriptStep() {
    override val text: String get() = "arrastre $x0 $y0 $x1 $y1 $durationMs $steps"
}

/** Dos dedos a la vez: se ponen, se mantienen [holdMs] y se levantan. */
data class TwoFingerStep(val x0: Coord, val y0: Coord, val x1: Coord, val y1: Coord, val holdMs: Long) : ScriptStep() {
    override val text: String get() = "dos $x0 $y0 $x1 $y1 $holdMs"
}

data class PinchStep(val cx: Coord, val cy: Coord, val d0: Coord, val d1: Coord, val durationMs: Long, val steps: Int) : ScriptStep() {
    override val text: String get() = "pellizco $cx $cy $d0 $d1 $durationMs $steps"
}

/** `PHONE_KEYS` (1 Inicio, 2 Atrás, 3 Recientes). */
data class KeyStep(val code: Int, val name: String) : ScriptStep() {
    override val text: String get() = "tecla $name"
}

/** msgType 13 `Music/<FunctionID>`. */
data class MusicStep(val functionId: String, val name: String) : ScriptStep() {
    override val text: String get() = "musica $name"
}

/** `KEY_FRAME_REQ`. */
data object KeyframeStep : ScriptStep() {
    override val text: String get() = "keyframe"
}

class TouchScript(val steps: List<ScriptStep>, val origin: String) {
    val isEmpty: Boolean get() = steps.isEmpty()

    override fun toString(): String = if (isEmpty) "ninguno" else "$origin (${steps.size} pasos)"
}

/** Guiones por defecto y lectura de `--touch-script`. */
object TouchScripts {
    /** Tras 3 s de vídeo: toques en 25 %/25 %, 75 %/75 % y el centro, un arrastre de lado a lado y dos dedos. */
    const val DEFAULT_TEXT =
        "espera 3000; toque 25% 25%; espera 700; toque 75% 75%; espera 700; toque 50% 50%; espera 700; " +
            "arrastre 10% 50% 90% 50% 800 20; espera 700; dos 35% 50% 65% 50% 600"

    /** El autotest añade teclas y un KEY_FRAME_REQ para comprobar que llegan al teléfono. */
    const val SELF_TEST_EXTRA = "espera 300; tecla atras; musica siguiente; keyframe; espera 500"

    val DEFAULT: TouchScript = parse(DEFAULT_TEXT, "por defecto")
    val SELF_TEST: TouchScript = parse("$DEFAULT_TEXT; $SELF_TEST_EXTRA", "autotest")
    val NONE = TouchScript(emptyList(), "ninguno")

    /** `none`, `default`, un fichero o el guion escrito en la propia opción. */
    fun fromArgument(arg: String): TouchScript {
        when (normalize(arg.trim())) {
            "none", "ninguno", "no" -> return NONE
            "default", "defecto" -> return DEFAULT
        }
        val file = File(arg)
        if (file.isFile) {
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                throw ScriptException("no se pudo leer ${file.path}: ${e.message}")
            }
            return parse(text, "fichero ${file.path}")
        }
        return parse(arg, "línea de comandos")
    }

    /** Órdenes separadas por líneas o por ';'; '#' empieza un comentario. */
    fun parse(text: String, origin: String): TouchScript {
        val steps = ArrayList<ScriptStep>()
        text.lines().forEachIndexed { index, line ->
            for (part in line.substringBefore('#').split(';')) {
                val tokens = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                try {
                    steps += parseStep(tokens)
                } catch (e: ScriptException) {
                    throw ScriptException("guion táctil, línea ${index + 1}, '${part.trim()}': ${e.message}")
                }
            }
        }
        return TouchScript(steps, origin)
    }

    private fun parseStep(tokens: List<String>): ScriptStep {
        val args = tokens.drop(1)
        fun coord(i: Int): Coord = parseCoord(args.getOrNull(i) ?: throw ScriptException("faltan coordenadas"))
        fun ms(i: Int, default: Long): Long = args.getOrNull(i)?.let { parseLong(it, "milisegundos", 0, 600_000) } ?: default
        fun steps(i: Int, default: Int): Int = args.getOrNull(i)?.let { parseLong(it, "pasos", 1, 1_000).toInt() } ?: default
        fun maxArgs(n: Int) {
            if (args.size > n) throw ScriptException("sobran argumentos (como mucho $n)")
        }
        return when (normalize(tokens[0])) {
            "espera", "wait" -> {
                maxArgs(1)
                WaitStep(parseLong(args.getOrNull(0) ?: throw ScriptException("falta la espera en ms"), "milisegundos", 0, 600_000))
            }
            "toque", "tap" -> {
                maxArgs(3)
                TapStep(coord(0), coord(1), ms(2, 50))
            }
            "arrastre", "drag" -> {
                maxArgs(6)
                DragStep(coord(0), coord(1), coord(2), coord(3), ms(4, 300), steps(5, 10))
            }
            "dos", "two" -> {
                maxArgs(5)
                TwoFingerStep(coord(0), coord(1), coord(2), coord(3), ms(4, 500))
            }
            "pellizco", "pinch" -> {
                maxArgs(6)
                PinchStep(coord(0), coord(1), coord(2), coord(3), ms(4, 300), steps(5, 10))
            }
            "tecla", "key" -> {
                maxArgs(1)
                parseKey(args.getOrNull(0) ?: throw ScriptException("falta la tecla"))
            }
            "musica", "music" -> {
                maxArgs(1)
                parseMusic(args.getOrNull(0) ?: throw ScriptException("falta la acción"))
            }
            "keyframe", "idr" -> {
                maxArgs(0)
                KeyframeStep
            }
            else -> throw ScriptException("orden desconocida '${tokens[0]}'")
        }
    }

    private fun parseKey(arg: String): KeyStep = when (normalize(arg)) {
        "inicio", "home" -> KeyStep(1, "inicio")
        "atras", "back" -> KeyStep(2, "atras")
        "recientes", "recents" -> KeyStep(3, "recientes")
        else -> KeyStep(parseLong(arg, "código de tecla", 0, 1_000_000).toInt(), arg)
    }

    private fun parseMusic(arg: String): MusicStep = when (normalize(arg)) {
        "play" -> MusicStep(FunctionIds.PLAY_CONTROL_PLAY, "play")
        "pausa", "pause" -> MusicStep(FunctionIds.PLAY_CONTROL_PAUSE, "pausa")
        "playpausa", "playpause" -> MusicStep(FunctionIds.PLAY_CONTROL, "playpausa")
        "siguiente", "next" -> MusicStep(FunctionIds.NEXT, "siguiente")
        "anterior", "prev" -> MusicStep(FunctionIds.PREV, "anterior")
        "silencio", "mute" -> MusicStep(FunctionIds.MUTE_CONTROL, "silencio")
        else -> throw ScriptException("acción de música desconocida '$arg' (play, pausa, playpausa, siguiente, anterior, silencio)")
    }

    private fun parseCoord(s: String): Coord {
        val percent = s.endsWith("%")
        val number = s.removeSuffix("%").replace(',', '.')
        val v = number.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw ScriptException("coordenada no válida '$s'")
        return Coord(v, percent)
    }

    private fun parseLong(s: String, what: String, min: Long, max: Long): Long {
        val v = s.toLongOrNull() ?: throw ScriptException("$what no válidos: '$s'")
        if (v !in min..max) throw ScriptException("$what fuera de rango ($min-$max): $v")
        return v
    }

    /** Minúsculas y sin tildes ("Atrás" = "atras"). */
    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
