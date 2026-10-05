package dev.qdauto.carsim.h264

import dev.qdauto.core.h264.AnnexB
import dev.qdauto.core.h264.NalType
import dev.qdauto.core.util.Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpsParserTest {
    /** SPS de x264, High 4.0 1080p30, con dos bytes de prevención de emulación (`00 00 03`). Decodificado a mano. */
    private val x264High1080 = Hex.decode("67 64 00 28 ac d9 40 78 02 27 e5 84 00 00 03 00 04 00 00 03 00 f0 3c 60 c6 58")

    /** Baseline 3.0 640x480 sin VUI. */
    private val baseline480 = Hex.decode("67 42 00 1e 95 a8 28 0f 64")

    @Test
    fun x264HighProfile1080pWithCroppingAndVui() {
        val s = SpsParser.parse(x264High1080)
        assertEquals(100, s.profileIdc)
        assertEquals("High", s.profileName)
        assertEquals("000000", s.constraintText)
        assertEquals(40, s.levelIdc)
        assertEquals("4.0", s.levelName)
        assertEquals(0, s.spsId)
        assertEquals(1, s.chromaFormatIdc)
        assertEquals(8, s.bitDepthLuma)
        assertFalse(s.scalingMatrix)
        assertEquals(4, s.log2MaxFrameNum)
        assertEquals(0, s.picOrderCntType)
        assertEquals(4, s.maxNumRefFrames)
        assertEquals(120, s.widthInMbs)
        assertEquals(68, s.heightInMapUnits)
        assertTrue(s.frameMbsOnly)
        assertEquals(1920, s.codedWidth)
        assertEquals(1088, s.codedHeight)
        assertEquals(8, s.cropBottom)
        assertEquals(0, s.cropRight)
        assertEquals(1920, s.width)
        assertEquals(1080, s.height)
        assertTrue(s.cropped)
        val vui = assertNotNull(s.vui)
        assertNull(s.vuiError)
        assertEquals(1L, vui.numUnitsInTick)
        assertEquals(60L, vui.timeScale)
        assertEquals(false, vui.fixedFrameRate)
        assertEquals(30.0, vui.fps)
        assertEquals(2, vui.maxNumReorderFrames)
        assertEquals(4, vui.maxDecFrameBuffering)
        assertTrue(s.describe().startsWith("High (100), nivel 4.0"), s.describe())
    }

    @Test
    fun baselineWithoutVui() {
        val s = SpsParser.parse(baseline480)
        assertEquals(66, s.profileIdc)
        assertEquals("Baseline", s.profileName)
        assertEquals("3.0", s.levelName)
        assertEquals(8, s.log2MaxFrameNum)
        assertEquals(2, s.picOrderCntType)
        assertEquals(1, s.maxNumRefFrames)
        assertTrue(s.gapsInFrameNumAllowed)
        assertEquals(640, s.width)
        assertEquals(480, s.height)
        assertFalse(s.cropped)
        assertNull(s.vui)
    }

    @Test
    fun syntheticSpsRoundTrips() {
        val cases = listOf(1920 to 1080, 1280 to 720, 800 to 480, 2560 to 1440, 1366 to 768, 1560 to 720, 1920 to 720, 16 to 16, 2 to 2)
        for ((w, h) in cases) {
            for (fps in listOf(24, 30, 60)) {
                val s = SpsParser.parse(SyntheticH264.sps(w, h, fps))
                assertEquals(w to h, s.width to s.height, "SPS de ${w}x$h")
                assertEquals("Constrained Baseline", s.profileName)
                assertEquals("110000", s.constraintText)
                assertEquals(2, s.picOrderCntType)
                assertEquals(fps.toDouble(), s.vui?.fps)
                assertEquals(0, s.vui?.maxNumReorderFrames)
            }
        }
        assertEquals(40, SpsParser.parse(SyntheticH264.sps(1920, 1080, 30)).levelIdc)
        assertEquals(31, SpsParser.parse(SyntheticH264.sps(1280, 720, 30)).levelIdc)
        assertEquals(51, SpsParser.parse(SyntheticH264.sps(2560, 1440, 60)).levelIdc)
    }

    @Test
    fun syntheticPpsIsTheTypicalAndroidOne() {
        assertContentEquals(Hex.decode("68 ce 3c 80"), SyntheticH264.pps())
        val config = SyntheticH264.codecConfig(1920, 1080, 30)
        assertEquals(listOf(NalType.SPS, NalType.PPS), AnnexB.nalTypes(config))
        assertTrue(AnnexB.isCodecConfig(config))
        val frame = SyntheticH264.fakeFrame(7, keyframe = true, size = 5_000)
        assertEquals(listOf(NalType.IDR), AnnexB.nalTypes(frame))
        assertEquals(listOf(NalType.SLICE), AnnexB.nalTypes(SyntheticH264.fakeFrame(8, keyframe = false, size = 300)))
    }

    @Test
    fun rejectsBrokenSps() {
        assertFailsWith<BitstreamException> { SpsParser.parse(x264High1080, 0, 8) }
        assertFailsWith<BitstreamException> { SpsParser.parse(Hex.decode("68 ce 3c 80")) }
        assertFailsWith<BitstreamException> { SpsParser.parse(Hex.decode("e7 42 00 1e 95")) }
        assertFailsWith<IllegalArgumentException> { SyntheticH264.sps(1919, 1080, 30) }
    }
}
