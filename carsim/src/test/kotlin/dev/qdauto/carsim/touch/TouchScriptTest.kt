package dev.qdauto.carsim.touch

import dev.qdauto.core.wire.FunctionIds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TouchScriptTest {
    private fun pct(v: Float) = Coord(v, percent = true)

    @Test
    fun defaultScript() {
        val s = TouchScripts.DEFAULT.steps
        assertEquals(10, s.size)
        assertEquals(WaitStep(3_000), s[0])
        assertEquals(TapStep(pct(25f), pct(25f), 50), s[1])
        assertEquals(TapStep(pct(75f), pct(75f), 50), s[3])
        assertEquals(TapStep(pct(50f), pct(50f), 50), s[5])
        assertEquals(DragStep(pct(10f), pct(50f), pct(90f), pct(50f), 800, 20), s[7])
        assertEquals(TwoFingerStep(pct(35f), pct(50f), pct(65f), pct(50f), 600), s[9])
        val extra = TouchScripts.SELF_TEST.steps.drop(s.size)
        assertEquals(listOf(WaitStep(300), KeyStep(2, "atras"), MusicStep(FunctionIds.NEXT, "siguiente"), KeyframeStep, WaitStep(500)), extra)
    }

    @Test
    fun coordinates() {
        assertEquals(480f, pct(25f).resolve(1920))
        assertEquals(270f, pct(25f).resolve(1080))
        assertEquals(100.5f, Coord(100.5f, false).resolve(1920))
        assertEquals("25%", pct(25f).toString())
    }

    @Test
    fun linesCommentsAliasesAndAccents() {
        val s = TouchScripts.parse(
            """
            # comentario
            wait 100; tap 10 20 300
            drag 0 0 100% 100%   # fin de línea
            two 1 2 3 4
            pinch 50% 50% 100 400 250 5
            tecla Atrás; key home; tecla 7
            Música pausa; music next; idr
            """.trimIndent(),
            "test",
        ).steps
        assertEquals(
            listOf(
                WaitStep(100),
                TapStep(Coord(10f, false), Coord(20f, false), 300),
                DragStep(Coord(0f, false), Coord(0f, false), pct(100f), pct(100f), 300, 10),
                TwoFingerStep(Coord(1f, false), Coord(2f, false), Coord(3f, false), Coord(4f, false), 500),
                PinchStep(pct(50f), pct(50f), Coord(100f, false), Coord(400f, false), 250, 5),
                KeyStep(2, "atras"),
                KeyStep(1, "inicio"),
                KeyStep(7, "7"),
                MusicStep(FunctionIds.PLAY_CONTROL_PAUSE, "pausa"),
                MusicStep(FunctionIds.NEXT, "siguiente"),
                KeyframeStep,
            ),
            s,
        )
    }

    @Test
    fun errorsSayWhere() {
        val cases = listOf("toque 10%", "salta 3", "espera -5", "espera", "musica fuerte", "toque 1 2 3 4", "toque a b", "keyframe 1")
        for (text in cases) {
            val e = assertFailsWith<ScriptException>(text) { TouchScripts.parse("espera 1\n$text", "test") }
            assertTrue(e.message!!.contains("línea 2"), e.message)
        }
    }

    @Test
    fun fromArgument() {
        assertSame(TouchScripts.NONE, TouchScripts.fromArgument("none"))
        assertSame(TouchScripts.NONE, TouchScripts.fromArgument("Ninguno"))
        assertSame(TouchScripts.DEFAULT, TouchScripts.fromArgument("default"))
        val file = File.createTempFile("guion", ".txt").apply { deleteOnExit() }
        file.writeText("espera 10\ntoque 1 1\n")
        val fromFile = TouchScripts.fromArgument(file.path)
        assertEquals(2, fromFile.steps.size)
        assertTrue(fromFile.origin.startsWith("fichero"))
        assertEquals(1, TouchScripts.fromArgument("toque 5 5").steps.size)
        file.delete()
    }
}
