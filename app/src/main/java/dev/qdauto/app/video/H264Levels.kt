package dev.qdauto.app.video

import android.media.MediaCodecInfo.CodecProfileLevel
import kotlin.math.sqrt

/** Nivel H.264 mínimo para un tamaño, fps y bitrate (tabla A-1 de la norma). */
object H264Levels {
    private class Limits(val level: Int, val name: String, val maxMbPerSec: Long, val maxFrameMbs: Long, val maxKbps: Long)

    private val TABLE = listOf(
        Limits(CodecProfileLevel.AVCLevel1, "1", 1_485, 99, 64),
        Limits(CodecProfileLevel.AVCLevel11, "1.1", 3_000, 396, 192),
        Limits(CodecProfileLevel.AVCLevel12, "1.2", 6_000, 396, 384),
        Limits(CodecProfileLevel.AVCLevel13, "1.3", 11_880, 396, 768),
        Limits(CodecProfileLevel.AVCLevel2, "2", 11_880, 396, 2_000),
        Limits(CodecProfileLevel.AVCLevel21, "2.1", 19_800, 792, 4_000),
        Limits(CodecProfileLevel.AVCLevel22, "2.2", 20_250, 1_620, 4_000),
        Limits(CodecProfileLevel.AVCLevel3, "3", 40_500, 1_620, 10_000),
        Limits(CodecProfileLevel.AVCLevel31, "3.1", 108_000, 3_600, 14_000),
        Limits(CodecProfileLevel.AVCLevel32, "3.2", 216_000, 5_120, 20_000),
        Limits(CodecProfileLevel.AVCLevel4, "4", 245_760, 8_192, 20_000),
        Limits(CodecProfileLevel.AVCLevel41, "4.1", 245_760, 8_192, 50_000),
        Limits(CodecProfileLevel.AVCLevel42, "4.2", 522_240, 8_704, 50_000),
        Limits(CodecProfileLevel.AVCLevel5, "5", 589_824, 22_080, 135_000),
        Limits(CodecProfileLevel.AVCLevel51, "5.1", 983_040, 36_864, 240_000),
        Limits(CodecProfileLevel.AVCLevel52, "5.2", 2_073_600, 36_864, 240_000),
        Limits(CodecProfileLevel.AVCLevel6, "6", 4_177_920, 139_264, 240_000),
        Limits(CodecProfileLevel.AVCLevel61, "6.1", 8_355_840, 139_264, 480_000),
        Limits(CodecProfileLevel.AVCLevel62, "6.2", 16_711_680, 139_264, 800_000),
    )

    /**
     * Constante `CodecProfileLevel.AVCLevel*` del nivel más bajo que admite el stream, o `null` si no cabe ni en 6.2.
     * [high]: el perfil High admite 1,25 veces el bitrate de la tabla (cpbBrVclFactor 1250).
     */
    fun minimumLevel(width: Int, height: Int, fps: Int, bitrate: Int, high: Boolean): Int? {
        val mbW = (width + 15) / 16L
        val mbH = (height + 15) / 16L
        val frameMbs = mbW * mbH
        val mbPerSec = frameMbs * fps
        val factor = if (high) 1_250L else 1_000L
        return TABLE.firstOrNull { l ->
            val maxSide = sqrt(8.0 * l.maxFrameMbs)
            frameMbs <= l.maxFrameMbs && mbPerSec <= l.maxMbPerSec && mbW <= maxSide && mbH <= maxSide &&
                bitrate.toLong() <= l.maxKbps * factor
        }?.level
    }

    fun name(level: Int): String = TABLE.firstOrNull { it.level == level }?.name
        ?: if (level == CodecProfileLevel.AVCLevel1b) "1b" else "0x" + Integer.toHexString(level)
}
