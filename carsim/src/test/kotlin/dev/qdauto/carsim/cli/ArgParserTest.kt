package dev.qdauto.carsim.cli

import dev.qdauto.carsim.touch.TapStep
import dev.qdauto.carsim.touch.TouchScripts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgParserTest {
    private fun parse(vararg args: String) = ArgParser.parse(arrayOf(*args))

    @Test
    fun defaultsComeFromCore() {
        val o = parse()
        assertEquals(Options(), o)
        assertEquals(1920, o.width)
        assertEquals(1080, o.height)
        assertEquals("2D4", o.carType)
        assertEquals(30, o.fps)
        assertEquals(4_000_000, o.bitrate)
        assertEquals(1, o.gop)
        assertNull(o.durationMs)
        assertNull(o.touchScript)
        assertTrue(o.targets.isEmpty())
    }

    @Test
    fun allOptions() {
        val o = parse(
            "--target", "192.168.1.50", "--target=broadcast,10.0.0.255", "--width", "2560", "--height=1440",
            "--car-type", "2D5", "--fps", "60", "--bitrate", "8M", "--gop", "2", "--duration", "12,5",
            "--discovery-timeout", "30", "--out", "x.h264", "--report", "r.json", "--verbose", "--self-test",
            "--touch-script", "toque 10% 20%",
        )
        assertEquals(listOf("192.168.1.50", "broadcast", "10.0.0.255"), o.targets)
        assertEquals(2560 to 1440, o.width to o.height)
        assertEquals("2D5", o.carType)
        assertEquals(60, o.fps)
        assertEquals(8_000_000, o.bitrate)
        assertEquals(2, o.gop)
        assertEquals(12_500L, o.durationMs)
        assertEquals(30_000L, o.discoveryTimeoutMs)
        assertEquals(File("x.h264"), o.out)
        assertEquals(File("r.json"), o.report)
        assertTrue(o.verbose)
        assertTrue(o.selfTest)
        assertIs<TapStep>(o.touchScript?.steps?.single())
    }

    @Test
    fun bitrateSuffixes() {
        assertEquals(4_000_000, ArgParser.parseBitrate("4000000"))
        assertEquals(4_000_000, ArgParser.parseBitrate("4M"))
        assertEquals(2_500_000, ArgParser.parseBitrate("2.5m"))
        assertEquals(800_000, ArgParser.parseBitrate("800k"))
        assertFailsWith<UsageException> { ArgParser.parseBitrate("4 Mbps") }
        assertFailsWith<UsageException> { ArgParser.parseBitrate("0") }
        assertFailsWith<UsageException> { ArgParser.parseBitrate("3000M") }
    }

    @Test
    fun touchScriptKeywords() {
        assertTrue(parse("--touch-script", "none").touchScript!!.isEmpty)
        assertEquals(TouchScripts.DEFAULT.steps, parse("--touch-script", "default").touchScript!!.steps)
    }

    @Test
    fun helpAndDurationZero() {
        assertTrue(parse("--help").help)
        assertTrue(parse("-h").help)
        assertEquals(0L, parse("--duration", "0").durationMs)
    }

    @Test
    fun errorsAreUsageExceptions() {
        val bad = listOf(
            arrayOf("--nope"),
            arrayOf("posicional"),
            arrayOf("--width"),
            arrayOf("--width", "--fps", "30"),
            arrayOf("--width", "abc"),
            arrayOf("--width", "10"),
            arrayOf("--fps", "0"),
            arrayOf("--verbose=1"),
            arrayOf("--duration", "-1"),
            arrayOf("--touch-script", "toque 10%"),
            arrayOf("--touch-script", "salta 3"),
            arrayOf("--car-type", " "),
        )
        for (args in bad) {
            assertFailsWith<UsageException>(args.joinToString(" ")) { ArgParser.parse(args) }
        }
        val e = assertFailsWith<UsageException> { parse("--touch-script", "espera 10; toque 10%") }
        assertTrue(e.message!!.contains("línea 1"), e.message)
    }
}
