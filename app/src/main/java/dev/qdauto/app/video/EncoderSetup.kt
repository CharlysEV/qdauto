package dev.qdauto.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import dev.qdauto.app.settings.BitrateMode
import dev.qdauto.app.settings.H264Profile
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w

/**
 * Elección y configuración del encoder H.264 de entrada por Surface. Las claves opcionales solo se ponen si el encoder
 * dice soportarlas; si `configure()`/`start()` fallan se reintenta con menos claves:
 * 0 = completo, 1 = sin perfil/nivel/baja latencia/B-frames, 2 = mínimo (tamaño, bitrate, fps, GOP).
 */
object EncoderSetup {
    private const val TAG = "QD/Encoder"
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    const val STEPS = 3

    /** Encoder elegido y spec ajustada a sus capacidades. */
    class Prepared(val info: MediaCodecInfo?, val spec: EncoderSpec)

    /** Encoder configurado y arrancado, con su Surface de entrada. */
    class Created(
        val codec: MediaCodec,
        val surface: Surface,
        val codecName: String,
        val hardware: Boolean?,
        val step: Int,
        val profileText: String,
        val levelText: String,
        val modeText: String,
        val format: String,
        val notes: List<String>,
    )

    private class Built(val format: MediaFormat, val profileText: String, val levelText: String, val modeText: String)

    @Volatile
    private var catalogLogged = false

    fun prepare(spec: EncoderSpec, log: QdLog): Prepared {
        val encoders = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }
        } catch (e: Exception) {
            log.e(TAG, "no se pudo leer la lista de codecs", e)
            emptyList()
        }
        if (!catalogLogged) {
            catalogLogged = true
            if (encoders.isEmpty()) log.w(TAG, "no hay encoders H.264 en MediaCodecList")
            encoders.forEach { log.i(TAG, describe(it)) }
        }
        val info = encoders.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly && !it.isAlias }
            ?: encoders.firstOrNull { !it.isAlias }
        if (info == null) return Prepared(null, spec.copy(notes = spec.notes + "sin lista de encoders: createEncoderByType"))
        val notes = ArrayList<String>()
        var w = spec.width
        var h = spec.height
        var bitrate = spec.bitrate
        try {
            val vc = info.getCapabilitiesForType(MIME).videoCapabilities
                ?: throw IllegalStateException("${info.name} no publica videoCapabilities")
            if (!vc.isSizeSupported(w, h)) {
                val fitted = fitSize(vc, w, h)
                if (fitted != null) {
                    notes += "${info.name} no admite ${w}×$h: se usa $fitted"
                    w = fitted.width
                    h = fitted.height
                } else {
                    notes += "${info.name} no declara ${w}×$h; se intenta igualmente"
                }
            }
            if (!vc.areSizeAndRateSupported(w, h, spec.fps.toDouble())) {
                notes += "${w}×$h@${spec.fps} está fuera de lo que declara ${info.name}"
            }
            val clamped = vc.bitrateRange.clamp(bitrate)
            if (clamped != bitrate) {
                notes += "bitrate $bitrate → $clamped (rango ${vc.bitrateRange})"
                bitrate = clamped
            }
        } catch (e: Exception) {
            log.w(TAG, "no se pudieron leer las capacidades de ${info.name}", e)
        }
        notes.forEach { log.i(TAG, it) }
        return Prepared(info, spec.copy(width = w, height = h, bitrate = bitrate, notes = spec.notes + notes))
    }

    /** Configura y arranca el encoder empezando en el paso [firstStep]. Lanza si ningún paso funciona. */
    fun create(p: Prepared, firstStep: Int, log: QdLog): Created {
        var last: Exception? = null
        for (step in firstStep.coerceIn(0, STEPS - 1) until STEPS) {
            val codec = try {
                if (p.info != null) MediaCodec.createByCodecName(p.info.name) else MediaCodec.createEncoderByType(MIME)
            } catch (e: Exception) {
                log.e(TAG, "no se pudo crear el encoder", e)
                last = e
                continue
            }
            val notes = ArrayList<String>()
            var surface: Surface? = null
            try {
                val built = buildFormat(p, step, notes)
                log.i(TAG, "configurando ${codec.name} (paso $step): ${built.format}")
                codec.configure(built.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                notes.forEach { log.i(TAG, it) }
                return Created(
                    codec, surface, codec.name, p.info?.isHardwareAccelerated, step,
                    built.profileText, built.levelText, built.modeText, built.format.toString(), notes,
                )
            } catch (e: Exception) {
                log.w(TAG, "el paso $step falló: $e", e)
                last = e
                try {
                    surface?.release()
                } catch (_: Exception) {
                }
                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }
        }
        throw IllegalStateException("no se pudo configurar ningún encoder H.264", last)
    }

    private fun buildFormat(p: Prepared, step: Int, notes: MutableList<String>): Built {
        val s = p.spec
        val f = MediaFormat.createVideoFormat(MIME, s.width, s.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, s.bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, s.gopSeconds)
        if (step >= 2) return Built(f, "del encoder", "del encoder", "del encoder (mínimo)")

        val caps: CodecCapabilities? = try {
            p.info?.getCapabilitiesForType(MIME)
        } catch (_: Exception) {
            null
        }
        // Modo de bitrate, solo si el encoder lo declara.
        var modeText = "del encoder"
        val mode = bitrateModeConstant(s.bitrateMode)
        val enc = caps?.encoderCapabilities
        if (mode != null && enc != null && enc.isBitrateModeSupported(mode)) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            modeText = s.bitrateMode.name
            if (mode == EncoderCapabilities.BITRATE_MODE_CQ) {
                val q = enc.qualityRange
                f.setInteger(MediaFormat.KEY_QUALITY, (q.lower + q.upper) / 2)
            }
        } else {
            notes += "modo ${s.bitrateMode} no declarado por el encoder: se deja el suyo"
        }
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
        if (s.repeatPreviousUs > 0) f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, s.repeatPreviousUs)
        if (step >= 1) return Built(f, "del encoder", "del encoder", modeText)

        // Perfil y nivel, solo si están entre los profileLevels del encoder.
        var profileText = "del encoder"
        var levelText = "del encoder"
        val levels = caps?.profileLevels.orEmpty()
        val chosen = profileCandidates(s.profile).firstOrNull { c -> levels.any { it.profile == c } }
        if (chosen != null) {
            f.setInteger(MediaFormat.KEY_PROFILE, chosen)
            profileText = profileName(chosen)
            val maxLevel = levels.filter { it.profile == chosen }.maxOf { it.level }
            val needed = H264Levels.minimumLevel(s.width, s.height, s.fps, s.bitrate, high = s.profile == H264Profile.HIGH)
            if (needed != null && needed <= maxLevel) {
                f.setInteger(MediaFormat.KEY_LEVEL, needed)
                levelText = H264Levels.name(needed)
            } else {
                notes += "nivel necesario ${needed?.let(H264Levels::name) ?: "> 6.2"} y máximo del encoder " +
                    "${H264Levels.name(maxLevel)}: no se fija KEY_LEVEL"
            }
        } else {
            notes += "perfil ${s.profile} no declarado por el encoder: se deja el suyo"
        }
        // Sin B-frames: el protocolo no lleva timestamps y añadirían latencia.
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps?.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) == true) {
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            notes += "KEY_LOW_LATENCY = 1"
        } else {
            notes += "el encoder no declara FEATURE_LowLatency: sin KEY_LOW_LATENCY"
        }
        return Built(f, profileText, levelText, modeText)
    }

    private fun profileCandidates(p: H264Profile): List<Int> = when (p) {
        H264Profile.BASELINE -> listOf(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline)
        H264Profile.MAIN -> listOf(CodecProfileLevel.AVCProfileMain)
        H264Profile.HIGH -> listOf(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh)
    }

    private fun bitrateModeConstant(m: BitrateMode): Int? = when (m) {
        BitrateMode.VBR -> EncoderCapabilities.BITRATE_MODE_VBR
        BitrateMode.CBR -> EncoderCapabilities.BITRATE_MODE_CBR
        BitrateMode.CQ -> EncoderCapabilities.BITRATE_MODE_CQ
        BitrateMode.CBR_FD -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) EncoderCapabilities.BITRATE_MODE_CBR_FD else null
    }

    fun profileName(profile: Int): String = when (profile) {
        CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        CodecProfileLevel.AVCProfileConstrainedBaseline -> "ConstrainedBaseline"
        CodecProfileLevel.AVCProfileMain -> "Main"
        CodecProfileLevel.AVCProfileExtended -> "Extended"
        CodecProfileLevel.AVCProfileHigh -> "High"
        CodecProfileLevel.AVCProfileConstrainedHigh -> "ConstrainedHigh"
        else -> "0x" + Integer.toHexString(profile)
    }

    /** Busca un tamaño admitido: primero alineado a lo que pide el encoder y después reducido manteniendo el aspecto. */
    private fun fitSize(vc: MediaCodecInfo.VideoCapabilities, w: Int, h: Int): IntSize? {
        val wa = vc.widthAlignment.coerceAtLeast(2)
        val ha = vc.heightAlignment.coerceAtLeast(2)
        fun align(v: Int, a: Int) = (v + a - 1) / a * a
        val candidates = ArrayList<IntSize>()
        candidates += IntSize(align(w, wa), align(h, ha))
        var scale = 0.9
        while (scale > 0.2) {
            candidates += IntSize(align((w * scale).toInt(), wa), align((h * scale).toInt(), ha))
            scale -= 0.1
        }
        return candidates.firstOrNull { it.width > 0 && it.height > 0 && vc.isSizeSupported(it.width, it.height) }
    }

    private fun describe(info: MediaCodecInfo): String = buildString {
        append("encoder ").append(info.name)
        append(if (info.isHardwareAccelerated) " (hardware" else " (software")
        if (info.isVendor) append(", fabricante")
        if (info.isAlias) append(", alias")
        append(')')
        try {
            val caps = info.getCapabilitiesForType(MIME)
            val vc = caps.videoCapabilities
            if (vc != null) {
                append(" anchos ").append(vc.supportedWidths).append(" altos ").append(vc.supportedHeights)
                append(" alineación ").append(vc.widthAlignment).append('×').append(vc.heightAlignment)
                append(" bitrate ").append(vc.bitrateRange)
                append(" 1920×1080@").append(runCatching { vc.getSupportedFrameRatesFor(1920, 1080) }.getOrNull() ?: "-")
            }
            val enc = caps.encoderCapabilities
            if (enc != null) {
                val modes = listOf(
                    EncoderCapabilities.BITRATE_MODE_CQ to "CQ",
                    EncoderCapabilities.BITRATE_MODE_VBR to "VBR",
                    EncoderCapabilities.BITRATE_MODE_CBR to "CBR",
                ).filter { enc.isBitrateModeSupported(it.first) }.joinToString("/") { it.second }
                append(" modos ").append(modes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enc.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR_FD)) {
                    append("/CBR_FD")
                }
            }
            append(" perfiles ")
            append(
                caps.profileLevels.groupBy { it.profile }.entries.joinToString(", ") { (profile, list) ->
                    profileName(profile) + "≤" + H264Levels.name(list.maxOf { it.level })
                },
            )
            if (caps.isFeatureSupported(CodecCapabilities.FEATURE_IntraRefresh)) append(" intra-refresh")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
                append(" baja-latencia")
            }
        } catch (e: Exception) {
            append(" (capacidades ilegibles: ").append(e).append(')')
        }
    }
}
