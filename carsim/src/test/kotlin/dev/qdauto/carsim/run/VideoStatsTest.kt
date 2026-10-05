package dev.qdauto.carsim.run

import dev.qdauto.core.h264.NalType
import dev.qdauto.core.sim.VideoFrameInfo
import dev.qdauto.core.sim.VideoKind
import dev.qdauto.core.wire.VideoExtHeader
import dev.qdauto.core.wire.VideoParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VideoStatsTest {
    private val params = VideoParams(1920, 1080, fps = 30, bitrate = 4_000_000, gop = 1, encodingType = 3)
    private val header = VideoExtHeader(32, 1, 0, params, 0)
    private val ms = 1_000_000L

    private fun info(index: Int, kind: VideoKind, size: Int): VideoFrameInfo {
        val nals = when (kind) {
            VideoKind.CONFIG -> listOf(NalType.SPS, NalType.PPS)
            VideoKind.IDR -> listOf(NalType.IDR)
            VideoKind.P -> listOf(NalType.SLICE)
            VideoKind.OTHER -> listOf(NalType.SEI)
        }
        return VideoFrameInfo(index, kind, header, size, nals, emptyList())
    }

    @Test
    fun fpsGapsAndIdrIntervals() {
        val stats = VideoStats(nominalFps = 30)
        val sps = SpsTracker()
        val t0 = 5_000 * ms
        var index = 0
        stats.onMessage(t0, info(index++, VideoKind.CONFIG, 30).also { sps.onMessage(0, it) })
        // 90 frames a 25 fps (40 ms), IDR cada 25, y un corte de 600 ms tras el frame 50.
        var t = t0
        for (f in 0 until 90) {
            t += if (f == 51) 600 * ms else 40 * ms
            val kind = if (f % 25 == 0) VideoKind.IDR else VideoKind.P
            val i = info(index++, kind, 1_000)
            stats.onMessage(t, i)
            sps.onMessage(0, i)
        }
        val s = stats.summary(clockStartNs = 0, endNs = t + 100 * ms)
        assertEquals(91, s.messages)
        assertEquals(1, s.configs)
        assertEquals(4, s.idr)
        assertEquals(86, s.p)
        assertEquals(90, s.frames)
        val span = (t - (t0 + 40 * ms)) / 1e9
        assertEquals(89 / span, assertNotNull(s.fpsAvg), 1e-9)
        assertEquals(600.0, s.maxGapMs)
        assertEquals(1, s.gapsOverThreshold) // umbral: máx(100, 3 * 33,3) = 100 ms
        // IDR en los frames 0, 25, 50 y 75; el corte cae entre el 50 y el 75.
        assertEquals(listOf(1_000L, 1_000L, 1_560L), s.idrIntervalsMs)
        assertEquals(listOf(25, 25, 25), s.idrIntervalsFrames)
        assertEquals(14 * 40L, s.lastIdrToLastFrameMs)
        // Del primer frame (t0 + 40 ms) al final (último + 100 ms) hay 4,22 s: 4 segundos completos de 25, 25, 11 y 25.
        assertEquals(4, s.fullSeconds)
        assertEquals(25, s.fpsMax)
        assertEquals(11, s.fpsMin)
        assertEquals(1, s.headers.size)
        assertEquals(91, s.headers[params])
        // El SPS del primer mensaje cubre las 91 cabeceras; empieza en el byte 0 de la grabación.
        val segment = sps.segments().single()
        assertEquals(0L, segment.fileOffset)
        assertEquals(30, segment.length)
        assertEquals(mapOf(Dims(1920, 1080) to 91), segment.headerSizes)
    }

    @Test
    fun emptyStats() {
        val s = VideoStats(30).summary(0, 1_000 * ms)
        assertEquals(0, s.messages)
        assertNull(s.fpsAvg)
        assertNull(s.maxGapMs)
        assertNull(s.fpsMin)
    }
}
