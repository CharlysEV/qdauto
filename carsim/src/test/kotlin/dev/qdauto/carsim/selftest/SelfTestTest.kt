package dev.qdauto.carsim.selftest

import dev.qdauto.carsim.ExitCodes
import dev.qdauto.carsim.cli.Options
import dev.qdauto.carsim.report.CheckStatus
import dev.qdauto.carsim.touch.TouchScripts
import dev.qdauto.core.json.JsonParser
import dev.qdauto.core.session.VideoOverrides
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El autotest de verdad (coche simulado contra PhoneSession en 127.0.0.1), en versión corta. */
class SelfTestTest {
    private val script = TouchScripts.parse(
        "espera 100; toque 10% 10%; dos 20% 20% 40% 40% 100; arrastre 0 0 100% 100% 100 5; pellizco 50% 50% 100 300 100 3; " +
            "tecla atras; musica siguiente; keyframe; espera 300",
        "test",
    )

    @Test
    fun selfTestPasses() {
        val report = File.createTempFile("carsim-informe", ".json").apply { deleteOnExit() }
        val result = SelfTest(Options(durationMs = 1_500, touchScript = script, report = report)).run()
        val status = result.checks.associate { it.id to it.status }
        assertTrue(result.passed, result.checks.filter { it.status == CheckStatus.FAIL }.toString())
        assertEquals(ExitCodes.OK, result.exitCode)
        val mustPass = listOf(
            "descubrimiento", "ack_formato", "tcp", "appstatus", "handshake", "orden", "phone_info", "video_recibido",
            "video_valido", "cabecera_tamano", "cabecera_eco", "sps", "sps_vs_cabecera", "huecos", "tactil", "inesperados",
            "sesion", "autotest_tactil", "autotest_teclas", "autotest_keyframe", "autotest_hilos",
        )
        for (id in mustPass) assertEquals(CheckStatus.PASS, status[id], "comprobación $id")
        val json = JsonParser.parseObject(report.readText())
        assertEquals("PASS", json.string("resultado"))
        assertTrue((json.array("comprobaciones")?.size ?: 0) >= mustPass.size)
        report.delete()
    }

    @Test
    fun wrongHeaderSizeIsDetected() {
        val result = SelfTest(Options(durationMs = 1_000, touchScript = TouchScripts.NONE), VideoOverrides(width = 1000)).run()
        val status = result.checks.associate { it.id to it.status }
        assertEquals(ExitCodes.CHECKS_FAILED, result.exitCode)
        assertEquals(CheckStatus.FAIL, status["cabecera_tamano"])
        assertEquals(CheckStatus.FAIL, status["sps_vs_cabecera"])
        assertEquals(CheckStatus.FAIL, status["video_valido"])
        assertEquals(CheckStatus.PASS, status["sps"])
        assertEquals(CheckStatus.SKIP, status["tactil"])
    }
}
