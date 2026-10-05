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
            "video_valido", "cabecera_tamano", "cabecera_eco", "sps", "sps_vs_cabecera", "huecos", "tamano_mensaje",
            "sps_repetido", "tactil", "inesperados", "sesion", "autotest_tactil", "autotest_teclas", "autotest_keyframe",
            "autotest_hilos",
        )
        for (id in mustPass) assertEquals(CheckStatus.PASS, status[id], "comprobación $id: ${result.checks.first { it.id == id }.detail}")
        // Sin --decode no se ejecuta ffmpeg.
        assertEquals(CheckStatus.SKIP, status["decodifica"])
        val json = JsonParser.parseObject(report.readText())
        assertEquals("PASS", json.string("resultado"))
        assertTrue((json.array("comprobaciones")?.size ?: 0) >= mustPass.size)
        val video = json.obj("video")!!
        assertTrue(video.int("mensajeMaxBytes")!! in 1_000..480 * 1024)
        assertEquals(0, video.int("mensajesGrandes"))
        assertEquals(2, video.array("spsPps")?.size, "SPS/PPS inicial y el del KEY_FRAME_REQ")
        report.delete()
    }

    /** Manía 1 del C10: un IDR de más de 512 KiB cuelga al coche (deja de leer y cierra a los --hang segundos). */
    @Test
    fun oversizedVideoMessageHangsTheCarAndFails() {
        val result = SelfTest(
            Options(durationMs = 4_000, touchScript = TouchScripts.NONE, receiverHangMs = 1_000),
            encoderTweaks = EncoderTweaks(idrBytes = 600 * 1024),
        ).run()
        val status = result.checks.associate { it.id to it.status }
        val detail = result.checks.associate { it.id to it.detail }
        assertEquals(ExitCodes.CHECKS_FAILED, result.exitCode)
        assertEquals(CheckStatus.FAIL, status["tamano_mensaje"], detail["tamano_mensaje"])
        assertTrue(detail["tamano_mensaje"]!!.contains("se colgó"), detail["tamano_mensaje"])
        assertTrue(detail["tamano_mensaje"]!!.contains("1000 ms sin leer"), detail["tamano_mensaje"])
        assertEquals(CheckStatus.FAIL, status["sesion"], detail["sesion"])
        assertTrue(detail["sesion"]!!.contains("receptor colgado"), detail["sesion"])
    }

    /** Con --limit 0 el coche no se cuelga, pero la comprobación sigue avisando de que el C10 lo haría. */
    @Test
    fun withoutTheLimitBigMessagesStillFailTheCheck() {
        val result = SelfTest(
            Options(durationMs = 1_500, touchScript = TouchScripts.NONE, receiverLimitBytes = 0),
            encoderTweaks = EncoderTweaks(idrBytes = 600 * 1024),
        ).run()
        val status = result.checks.associate { it.id to it.status }
        val detail = result.checks.associate { it.id to it.detail }
        assertEquals(CheckStatus.FAIL, status["tamano_mensaje"], detail["tamano_mensaje"])
        assertTrue(detail["tamano_mensaje"]!!.contains("sin emular el cuelgue"), detail["tamano_mensaje"])
        assertEquals(CheckStatus.PASS, status["sesion"], detail["sesion"])
    }

    /** Entre 480 y 512 KiB el coche aguanta, pero el teléfono tendría que haber recortado: WARN. */
    @Test
    fun messagesOver480KiBAreAWarning() {
        val result = SelfTest(
            Options(durationMs = 1_500, touchScript = TouchScripts.NONE),
            encoderTweaks = EncoderTweaks(idrBytes = 500 * 1024),
        ).run()
        val status = result.checks.associate { it.id to it.status }
        val detail = result.checks.associate { it.id to it.detail }
        assertEquals(ExitCodes.OK, result.exitCode)
        assertEquals(CheckStatus.WARN, status["tamano_mensaje"], detail["tamano_mensaje"])
        assertTrue(detail["tamano_mensaje"]!!.contains("de más de 480 KiB"), detail["tamano_mensaje"])
    }

    /** Manía 2 del C10: SPS/PPS repetidos sin IDR detrás reinician el decodificador a ciegas. */
    @Test
    fun repeatedCodecConfigWithoutIdrFails() {
        val result = SelfTest(
            Options(durationMs = 2_000, touchScript = TouchScripts.NONE),
            encoderTweaks = EncoderTweaks(repeatConfigEveryFrames = 10),
        ).run()
        val status = result.checks.associate { it.id to it.status }
        val detail = result.checks.associate { it.id to it.detail }
        assertEquals(ExitCodes.CHECKS_FAILED, result.exitCode)
        assertEquals(CheckStatus.FAIL, status["sps_repetido"], detail["sps_repetido"])
        assertTrue(detail["sps_repetido"]!!.contains("sin IDR detrás"), detail["sps_repetido"])
        // Y con la comprobación desactivada no se evalúa.
        val off = SelfTest(
            Options(durationMs = 1_500, touchScript = TouchScripts.NONE, spsCheck = false),
            encoderTweaks = EncoderTweaks(repeatConfigEveryFrames = 10),
        ).run()
        assertEquals(CheckStatus.SKIP, off.checks.first { it.id == "sps_repetido" }.status)
        assertEquals(ExitCodes.OK, off.exitCode)
    }

    /** `--decode` en el autotest se salta: los frames son falsos. */
    @Test
    fun decodeIsSkippedInSelfTest() {
        val result = SelfTest(Options(durationMs = 1_000, touchScript = TouchScripts.NONE, decode = true)).run()
        val check = result.checks.first { it.id == "decodifica" }
        assertEquals(CheckStatus.SKIP, check.status)
        assertTrue(check.detail.contains("autotest"), check.detail)
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
