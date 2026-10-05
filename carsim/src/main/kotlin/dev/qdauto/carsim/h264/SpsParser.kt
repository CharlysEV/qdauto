package dev.qdauto.carsim.h264

/** Parser de SPS H.264 (§7.3.2.1.1) con Exp-Golomb, recorte (§7.4.2.1.1) y la parte útil de la VUI (Anexo E). */
object SpsParser {
    /** Perfiles que llevan chroma_format_idc, profundidad y matrices de escalado. */
    private val CHROMA_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Tabla E-1: aspect_ratio_idc 1-16. */
    private val SAR = listOf(
        1 to 1, 12 to 11, 10 to 11, 16 to 11, 40 to 33, 24 to 11, 20 to 11, 32 to 11,
        80 to 33, 18 to 11, 15 to 11, 64 to 33, 160 to 99, 4 to 3, 3 to 2, 2 to 1,
    )
    private const val EXTENDED_SAR = 255
    private const val MAX_MBS_PER_SIDE = 4096

    /** [nal] empieza en la cabecera NAL (tipo 7), sin start code y con los bytes de prevención de emulación. */
    fun parse(nal: ByteArray, off: Int = 0, len: Int = nal.size - off): SpsInfo {
        if (len < 4) throw BitstreamException("SPS de solo $len bytes")
        val header = nal[off].toInt() and 0xFF
        if (header and 0x1F != 7) throw BitstreamException("la NAL es de tipo ${header and 0x1F}, no SPS (7)")
        if (header and 0x80 != 0) throw BitstreamException("forbidden_zero_bit a 1")
        val r = BitReader(Rbsp.unescape(nal, off + 1, len - 1))

        val profileIdc = r.u(8)
        val constraintFlags = r.u(8)
        val levelIdc = r.u(8)
        val spsId = r.ue()
        var chromaFormatIdc = 1
        var separateColourPlane = false
        var bitDepthLuma = 8
        var bitDepthChroma = 8
        var scalingMatrix = false
        if (profileIdc in CHROMA_PROFILES) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc > 3) throw BitstreamException("chroma_format_idc $chromaFormatIdc")
            if (chromaFormatIdc == 3) separateColourPlane = r.flag()
            bitDepthLuma = r.ue() + 8
            bitDepthChroma = r.ue() + 8
            r.flag() // qpprime_y_zero_transform_bypass_flag
            scalingMatrix = r.flag()
            if (scalingMatrix) {
                repeat(if (chromaFormatIdc != 3) 8 else 12) { i -> if (r.flag()) skipScalingList(r, if (i < 6) 16 else 64) }
            }
        }
        val log2MaxFrameNum = r.ue() + 4
        val pocType = r.ue()
        when (pocType) {
            0 -> r.ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                r.flag() // delta_pic_order_always_zero_flag
                r.se() // offset_for_non_ref_pic
                r.se() // offset_for_top_to_bottom_field
                val cycle = r.ue()
                if (cycle > 255) throw BitstreamException("num_ref_frames_in_pic_order_cnt_cycle $cycle")
                repeat(cycle) { r.se() }
            }
            2 -> Unit
            else -> throw BitstreamException("pic_order_cnt_type $pocType")
        }
        val maxNumRefFrames = r.ue()
        val gaps = r.flag()
        val widthInMbs = r.ue().toLong() + 1
        val heightInMapUnits = r.ue().toLong() + 1
        if (widthInMbs > MAX_MBS_PER_SIDE || heightInMapUnits > MAX_MBS_PER_SIDE) {
            throw BitstreamException("tamaño imposible: $widthInMbs x $heightInMapUnits macrobloques")
        }
        val frameMbsOnly = r.flag()
        val mbaff = if (!frameMbsOnly) r.flag() else false
        val direct8x8 = r.flag()
        var cropL = 0L
        var cropR = 0L
        var cropT = 0L
        var cropB = 0L
        if (r.flag()) {
            cropL = r.ue().toLong()
            cropR = r.ue().toLong()
            cropT = r.ue().toLong()
            cropB = r.ue().toLong()
        }
        var vui: VuiInfo? = null
        var vuiError: String? = null
        if (r.flag()) {
            try {
                vui = parseVui(r)
            } catch (e: BitstreamException) {
                vuiError = e.message
            }
        }

        // §7.4.2.1.1: CropUnitX/Y según ChromaArrayType y frame_mbs_only_flag.
        val chromaArrayType = if (separateColourPlane) 0 else chromaFormatIdc
        val subWidthC = if (chromaFormatIdc == 3) 1 else 2
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        val fieldFactor = if (frameMbsOnly) 1 else 2
        val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
        val cropUnitY = (if (chromaArrayType == 0) 1 else subHeightC) * fieldFactor
        val codedWidth = (widthInMbs * 16).toInt()
        val codedHeight = (heightInMapUnits * 16 * fieldFactor).toInt()
        val width = codedWidth - cropUnitX * (cropL + cropR)
        val height = codedHeight - cropUnitY * (cropT + cropB)
        if (width <= 0 || height <= 0) throw BitstreamException("el recorte deja un tamaño de $width x $height")

        return SpsInfo(
            profileIdc = profileIdc,
            constraintFlags = constraintFlags,
            levelIdc = levelIdc,
            spsId = spsId,
            chromaFormatIdc = chromaFormatIdc,
            separateColourPlane = separateColourPlane,
            bitDepthLuma = bitDepthLuma,
            bitDepthChroma = bitDepthChroma,
            scalingMatrix = scalingMatrix,
            log2MaxFrameNum = log2MaxFrameNum,
            picOrderCntType = pocType,
            maxNumRefFrames = maxNumRefFrames,
            gapsInFrameNumAllowed = gaps,
            widthInMbs = widthInMbs.toInt(),
            heightInMapUnits = heightInMapUnits.toInt(),
            frameMbsOnly = frameMbsOnly,
            mbAdaptiveFrameField = mbaff,
            direct8x8Inference = direct8x8,
            cropLeft = (cropUnitX * cropL).toInt(),
            cropRight = (cropUnitX * cropR).toInt(),
            cropTop = (cropUnitY * cropT).toInt(),
            cropBottom = (cropUnitY * cropB).toInt(),
            codedWidth = codedWidth,
            codedHeight = codedHeight,
            width = width.toInt(),
            height = height.toInt(),
            vui = vui,
            vuiError = vuiError,
        )
    }

    /** §7.3.2.1.1.1: solo hay que consumir los bits. */
    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = Math.floorMod(last + r.se(), 256)
            if (next != 0) last = next
        }
    }

    /** E.1.1 hasta bitstream_restriction. */
    private fun parseVui(r: BitReader): VuiInfo {
        var sarW: Int? = null
        var sarH: Int? = null
        if (r.flag()) {
            val idc = r.u(8)
            if (idc == EXTENDED_SAR) {
                sarW = r.u(16)
                sarH = r.u(16)
            } else {
                SAR.getOrNull(idc - 1)?.let {
                    sarW = it.first
                    sarH = it.second
                }
            }
        }
        if (r.flag()) r.flag() // overscan_info_present_flag → overscan_appropriate_flag
        var fullRange: Boolean? = null
        var primaries: Int? = null
        var transfer: Int? = null
        var matrix: Int? = null
        if (r.flag()) {
            r.u(3) // video_format
            fullRange = r.flag()
            if (r.flag()) {
                primaries = r.u(8)
                transfer = r.u(8)
                matrix = r.u(8)
            }
        }
        if (r.flag()) {
            r.ue() // chroma_sample_loc_type_top_field
            r.ue() // chroma_sample_loc_type_bottom_field
        }
        var numUnits: Long? = null
        var timeScale: Long? = null
        var fixed: Boolean? = null
        if (r.flag()) {
            numUnits = r.u32()
            timeScale = r.u32()
            fixed = r.flag()
        }
        val nalHrd = r.flag()
        if (nalHrd) skipHrd(r)
        val vclHrd = r.flag()
        if (vclHrd) skipHrd(r)
        if (nalHrd || vclHrd) r.flag() // low_delay_hrd_flag
        r.flag() // pic_struct_present_flag
        var reorder: Int? = null
        var decBuffering: Int? = null
        if (r.flag()) {
            r.flag() // motion_vectors_over_pic_boundaries_flag
            r.ue() // max_bytes_per_pic_denom
            r.ue() // max_bits_per_mb_denom
            r.ue() // log2_max_mv_length_horizontal
            r.ue() // log2_max_mv_length_vertical
            reorder = r.ue()
            decBuffering = r.ue()
        }
        return VuiInfo(sarW, sarH, fullRange, primaries, transfer, matrix, numUnits, timeScale, fixed, reorder, decBuffering)
    }

    /** E.1.2. */
    private fun skipHrd(r: BitReader) {
        val cpbCount = r.ue() + 1
        if (cpbCount > 32) throw BitstreamException("cpb_cnt_minus1 ${cpbCount - 1}")
        r.u(4) // bit_rate_scale
        r.u(4) // cpb_size_scale
        repeat(cpbCount) {
            r.ue() // bit_rate_value_minus1
            r.ue() // cpb_size_value_minus1
            r.flag() // cbr_flag
        }
        r.u(5) // initial_cpb_removal_delay_length_minus1
        r.u(5) // cpb_removal_delay_length_minus1
        r.u(5) // dpb_output_delay_length_minus1
        r.u(5) // time_offset_length
    }
}
