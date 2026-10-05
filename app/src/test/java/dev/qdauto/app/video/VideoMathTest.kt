package dev.qdauto.app.video

import android.media.MediaCodecInfo.CodecProfileLevel
import dev.qdauto.app.settings.BitrateMode
import dev.qdauto.app.settings.H264Profile
import dev.qdauto.app.settings.SizeSource
import dev.qdauto.app.settings.VideoSettings
import dev.qdauto.core.session.EncoderSuggestion
import dev.qdauto.core.wire.VideoArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoMathTest {
    @Test
    fun minimumLevels() {
        assertEquals(CodecProfileLevel.AVCLevel4, H264Levels.minimumLevel(1920, 1080, 30, 4_000_000, high = false))
        assertEquals(CodecProfileLevel.AVCLevel4, H264Levels.minimumLevel(1920, 1088, 30, 4_000_000, high = false))
        assertEquals(CodecProfileLevel.AVCLevel42, H264Levels.minimumLevel(1920, 1080, 60, 8_000_000, high = false))
        assertEquals(CodecProfileLevel.AVCLevel31, H264Levels.minimumLevel(1280, 720, 30, 4_000_000, high = false))
        assertEquals(CodecProfileLevel.AVCLevel3, H264Levels.minimumLevel(800, 480, 24, 2_764_800, high = false))
        assertEquals(CodecProfileLevel.AVCLevel5, H264Levels.minimumLevel(2560, 1440, 30, 10_000_000, high = false))
        assertEquals(CodecProfileLevel.AVCLevel51, H264Levels.minimumLevel(2560, 1440, 60, 10_000_000, high = false))
        // El bitrate también cuenta: 720p30 a 20 Mbit/s no cabe en 3.1 (14 Mbit/s; 17,5 en High).
        assertEquals(CodecProfileLevel.AVCLevel32, H264Levels.minimumLevel(1280, 720, 30, 20_000_000, high = false))
        assertEquals(CodecProfileLevel.AVCLevel31, H264Levels.minimumLevel(1280, 720, 30, 17_000_000, high = true))
        assertNull(H264Levels.minimumLevel(8192, 8192, 120, 1_000_000, high = false))
        assertEquals("5.1", H264Levels.name(CodecProfileLevel.AVCLevel51))
        assertEquals("1b", H264Levels.name(CodecProfileLevel.AVCLevel1b))
    }

    @Test
    fun alignment() {
        assertEquals(1088, VideoSizing.align16(1080))
        assertEquals(1072, VideoSizing.align16(1078))
        assertEquals(1440, VideoSizing.align16(1440))
        assertEquals(720, VideoSizing.align16(720))
        assertEquals(16, VideoSizing.align16(1))
        assertEquals(1920, VideoSizing.even(1919))
        assertEquals(1920, VideoSizing.even(1920))
    }

    @Test
    fun sizeSources() {
        val car = IntSize(1920, 1080)
        val aligned = VideoSizing.resolve(SizeSource.CAR_INFO, car, null, IntSize(1, 1), align16 = true)
        assertEquals(IntSize(1920, 1088), aligned.size)
        assertTrue(aligned.notes.any { "alineado" in it })
        assertEquals(car, VideoSizing.resolve(SizeSource.CAR_INFO, car, null, IntSize(1, 1), align16 = false).size)
        // VIDEO_ARGS: redondeo a par; sin Width/Height se usa CAR_INFO.
        assertEquals(IntSize(1280, 720), VideoSizing.resolve(SizeSource.VIDEO_ARGS, car, IntSize(1279, 719), IntSize(1, 1), false).size)
        assertEquals(car, VideoSizing.resolve(SizeSource.VIDEO_ARGS, car, IntSize(0, 0), IntSize(1, 1), false).size)
        assertEquals(car, VideoSizing.resolve(SizeSource.VIDEO_ARGS, car, null, IntSize(1, 1), false).size)
        // Manual, con el respaldo de QDLink (800×480) y el recorte a 4096.
        assertEquals(IntSize(1024, 600), VideoSizing.resolve(SizeSource.MANUAL, car, null, IntSize(1024, 600), false).size)
        assertEquals(VideoSizing.FALLBACK, VideoSizing.resolve(SizeSource.MANUAL, car, null, IntSize(0, 0), false).size)
        assertEquals(IntSize(4096, 2160), VideoSizing.resolve(SizeSource.MANUAL, car, null, IntSize(5000, 2160), false).size)
    }

    @Test
    fun encoderSpecFromVideoArgsAndOverrides() {
        val suggestion = EncoderSuggestion(1920, 1080, frameRate = 30, bitRate = 4_000_000, iFrameIntervalSec = 1)
        val args = VideoArgs(1920, 1080, 3, 30, 4_000_000, 1, stoppedAt = null)
        val auto = EncoderSpec.resolve(settings(), suggestion, args)
        assertEquals(IntSize(1920, 1088), auto.size)
        assertEquals(30, auto.fps)
        assertEquals(4_000_000, auto.bitrate)
        assertEquals(1, auto.gopSeconds)
        assertEquals(100_000L, auto.repeatPreviousUs)

        val forced = EncoderSpec.resolve(settings(fps = 60, kbps = 8_000, gop = 2, align = false), suggestion, args)
        assertEquals(IntSize(1920, 1080), forced.size)
        assertEquals(60, forced.fps)
        assertEquals(8_000_000, forced.bitrate)
        assertEquals(2, forced.gopSeconds)

        val clamped = EncoderSpec.resolve(settings(kbps = 199_000), suggestion.copy(iFrameIntervalSec = 600), args)
        assertEquals(199_000_000, clamped.bitrate)
        assertEquals(60, clamped.gopSeconds)
    }

    private fun settings(fps: Int = 0, kbps: Int = 0, gop: Int = 0, align: Boolean = true) = VideoSettings(
        sizeSource = SizeSource.CAR_INFO, manualWidth = 1920, manualHeight = 1080, alignTo16 = align, fps = fps,
        bitrateKbps = kbps, gopSeconds = gop, profile = H264Profile.BASELINE, bitrateMode = BitrateMode.VBR,
        repeatFrameMs = 100, appType = 1, headerAngle = 90, headerOrientation = 1,
    )
}
