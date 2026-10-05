package dev.qdauto.carsim.h264

import dev.qdauto.carsim.Fmt

/** Campos de la VUI que interesan para el informe (H.264 Anexo E). */
data class VuiInfo(
    val sarWidth: Int?,
    val sarHeight: Int?,
    val fullRange: Boolean?,
    val colourPrimaries: Int?,
    val transferCharacteristics: Int?,
    val matrixCoefficients: Int?,
    val numUnitsInTick: Long?,
    val timeScale: Long?,
    val fixedFrameRate: Boolean?,
    val maxNumReorderFrames: Int?,
    val maxDecFrameBuffering: Int?,
) {
    /** fps = time_scale / (2 * num_units_in_tick): un frame son dos campos (E.2.1). */
    val fps: Double?
        get() = if (numUnitsInTick != null && timeScale != null && numUnitsInTick > 0) timeScale / (2.0 * numUnitsInTick) else null
}

/** SPS decodificado (H.264 §7.3.2.1.1). Los recortes ya van en píxeles de luma. */
data class SpsInfo(
    val profileIdc: Int,
    /** Byte completo de constraint flags: constraint_set0_flag es el bit más alto. */
    val constraintFlags: Int,
    val levelIdc: Int,
    val spsId: Int,
    val chromaFormatIdc: Int,
    val separateColourPlane: Boolean,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val scalingMatrix: Boolean,
    val log2MaxFrameNum: Int,
    val picOrderCntType: Int,
    val maxNumRefFrames: Int,
    val gapsInFrameNumAllowed: Boolean,
    val widthInMbs: Int,
    val heightInMapUnits: Int,
    val frameMbsOnly: Boolean,
    val mbAdaptiveFrameField: Boolean,
    val direct8x8Inference: Boolean,
    val cropLeft: Int,
    val cropRight: Int,
    val cropTop: Int,
    val cropBottom: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val width: Int,
    val height: Int,
    val vui: VuiInfo?,
    val vuiError: String?,
) {
    fun constraintSet(i: Int): Boolean = (constraintFlags shr (7 - i)) and 1 == 1

    /** constraint_set0..5 como "110000". */
    val constraintText: String get() = (0..5).joinToString("") { if (constraintSet(it)) "1" else "0" }

    val profileName: String
        get() = when (profileIdc) {
            66 -> if (constraintSet(1)) "Constrained Baseline" else "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> when {
                constraintSet(4) && constraintSet(5) -> "Constrained High"
                constraintSet(4) -> "Progressive High"
                else -> "High"
            }
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4 Predictive"
            44 -> "CAVLC 4:4:4 Intra"
            83 -> "Scalable Baseline"
            86 -> "Scalable High"
            118 -> "Multiview High"
            128 -> "Stereo High"
            else -> "perfil $profileIdc"
        }

    /** "3.1", "4.0" o "1b" (A.3.1: level_idc 11 con constraint_set3 en Baseline/Main/Extended, o 9). */
    val levelName: String
        get() = when {
            levelIdc == 9 -> "1b"
            levelIdc == 11 && constraintSet(3) && profileIdc in setOf(66, 77, 88) -> "1b"
            else -> "${levelIdc / 10}.${levelIdc % 10}"
        }

    val chromaName: String
        get() = if (separateColourPlane) {
            "4:4:4 (planos separados)"
        } else {
            when (chromaFormatIdc) {
                0 -> "4:0:0"
                1 -> "4:2:0"
                2 -> "4:2:2"
                3 -> "4:4:4"
                else -> "croma $chromaFormatIdc"
            }
        }

    val cropped: Boolean get() = codedWidth != width || codedHeight != height

    fun describe(): String = buildString {
        append(profileName).append(" (").append(profileIdc).append("), nivel ").append(levelName)
        append(", constraint_set ").append(constraintText).append("; ")
        append(Fmt.size(width, height))
        if (cropped) {
            append(" (codificado ").append(Fmt.size(codedWidth, codedHeight)).append(", recorte")
            if (cropLeft > 0) append(" izq ").append(cropLeft)
            if (cropRight > 0) append(" der ").append(cropRight)
            if (cropTop > 0) append(" arriba ").append(cropTop)
            if (cropBottom > 0) append(" abajo ").append(cropBottom)
            append(" px)")
        }
        append("; ").append(chromaName).append(' ').append(bitDepthLuma).append(" bits")
        if (!frameMbsOnly) append(", entrelazado")
        append("; POC tipo ").append(picOrderCntType).append(", ").append(maxNumRefFrames).append(" ref.")
        vui?.let { v ->
            v.fps?.let { append("; VUI ").append(Fmt.dec(it, 2)).append(" fps") }
            v.maxNumReorderFrames?.let { append(", reordenación ").append(it) }
        }
        vuiError?.let { append("; VUI ilegible: ").append(it) }
    }
}
