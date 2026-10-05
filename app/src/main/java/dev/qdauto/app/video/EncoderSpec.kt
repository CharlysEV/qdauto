package dev.qdauto.app.video

import dev.qdauto.app.settings.BitrateMode
import dev.qdauto.app.settings.H264Profile
import dev.qdauto.app.settings.VideoSettings
import dev.qdauto.core.session.EncoderSuggestion
import dev.qdauto.core.wire.VideoArgs

/** Lo que se pide al encoder, ya resuelto a partir de los ajustes, `CAR_INFO` y `VIDEO_ARGS`. */
data class EncoderSpec(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** bps. */
    val bitrate: Int,
    val gopSeconds: Int,
    val profile: H264Profile,
    val bitrateMode: BitrateMode,
    /** `KEY_REPEAT_PREVIOUS_FRAME_AFTER` en µs; 0 = no. */
    val repeatPreviousUs: Long,
    val notes: List<String>,
) {
    val size: IntSize get() = IntSize(width, height)

    companion object {
        /**
         * fps/bitrate/GOP a 0 en los ajustes = los de `VIDEO_ARGS` con los valores de QDLink si llegan a 0
         * (24 fps, 2 764 800 bps, 4 s; c0/a.java:12-30), que es lo que ya trae [EncoderSuggestion].
         */
        fun resolve(v: VideoSettings, suggestion: EncoderSuggestion, args: VideoArgs?): EncoderSpec {
            val size = VideoSizing.resolve(
                v.sizeSource,
                IntSize(suggestion.width, suggestion.height),
                args?.let { IntSize(it.width, it.height) },
                IntSize(v.manualWidth, v.manualHeight),
                v.alignTo16,
            )
            val fps = (if (v.fps > 0) v.fps else suggestion.frameRate).coerceIn(1, 120)
            val bitrate = (if (v.bitrateKbps > 0) v.bitrateKbps * 1_000L else suggestion.bitRate.toLong())
                .coerceIn(100_000L, 200_000_000L).toInt()
            val gop = (if (v.gopSeconds > 0) v.gopSeconds else suggestion.iFrameIntervalSec).coerceIn(1, 60)
            return EncoderSpec(
                width = size.size.width,
                height = size.size.height,
                fps = fps,
                bitrate = bitrate,
                gopSeconds = gop,
                profile = v.profile,
                bitrateMode = v.bitrateMode,
                repeatPreviousUs = v.repeatFrameMs * 1_000L,
                notes = size.notes,
            )
        }
    }
}
