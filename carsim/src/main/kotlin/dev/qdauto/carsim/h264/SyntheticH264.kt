package dev.qdauto.carsim.h264

/**
 * H.264 sintético para el autotest:
 * - SPS y PPS válidos de Baseline restringido, el perfil que pide QDLink al encoder
 *   (QDLink: KEY_PROFILE = AVCProfileBaseline, SC/managers/a.java:579);
 * - frames falsos: start code + cabecera NAL de IDR (0x65) o P (0x41) + relleno sin ceros (no crea start codes).
 *   No se pueden decodificar: solo ejercitan el protocolo.
 */
object SyntheticH264 {
    private val START = byteArrayOf(0, 0, 0, 1)

    /** NAL SPS (cabecera 0x67 incluida, sin start code) para [width]x[height] (pares) a [fps]. */
    fun sps(width: Int, height: Int, fps: Int): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "tamaño $width x $height: tiene que ser par" }
        val widthMbs = (width + 15) / 16
        val heightMbs = (height + 15) / 16
        val w = BitWriter()
        w.u(8, 66) // profile_idc: Baseline
        w.u(8, 0xC0) // constraint_set0 y constraint_set1: Baseline restringido
        w.u(8, levelFor(widthMbs * heightMbs, fps))
        w.ue(0) // seq_parameter_set_id
        w.ue(0) // log2_max_frame_num_minus4
        w.ue(2) // pic_order_cnt_type: sin B-frames
        w.ue(1) // max_num_ref_frames
        w.flag(false) // gaps_in_frame_num_value_allowed_flag
        w.ue(widthMbs - 1)
        w.ue(heightMbs - 1)
        w.flag(true) // frame_mbs_only_flag
        w.flag(true) // direct_8x8_inference_flag
        val cropRight = widthMbs * 16 - width
        val cropBottom = heightMbs * 16 - height
        val crop = cropRight > 0 || cropBottom > 0
        w.flag(crop)
        if (crop) {
            // En 4:2:0 progresivo CropUnitX = CropUnitY = 2.
            w.ue(0)
            w.ue(cropRight / 2)
            w.ue(0)
            w.ue(cropBottom / 2)
        }
        w.flag(true) // vui_parameters_present_flag
        repeat(4) { w.flag(false) } // aspect_ratio, overscan, video_signal_type, chroma_loc
        w.flag(true) // timing_info_present_flag
        w.u(32, 1L) // num_units_in_tick
        w.u(32, 2L * fps) // time_scale: fps = time_scale / (2 * num_units_in_tick)
        w.flag(false) // fixed_frame_rate_flag
        w.flag(false) // nal_hrd_parameters_present_flag
        w.flag(false) // vcl_hrd_parameters_present_flag
        w.flag(false) // pic_struct_present_flag
        w.flag(true) // bitstream_restriction_flag
        w.flag(true) // motion_vectors_over_pic_boundaries_flag
        w.ue(0) // max_bytes_per_pic_denom
        w.ue(0) // max_bits_per_mb_denom
        w.ue(16) // log2_max_mv_length_horizontal
        w.ue(16) // log2_max_mv_length_vertical
        w.ue(0) // max_num_reorder_frames
        w.ue(1) // max_dec_frame_buffering
        w.trailingBits()
        return byteArrayOf(0x67) + Rbsp.escape(w.toByteArray())
    }

    /** NAL PPS mínima (CAVLC, un slice group): sale `68 ce 3c 80`, la de los encoders de Android (spec §8.8). */
    fun pps(): ByteArray {
        val w = BitWriter()
        w.ue(0) // pic_parameter_set_id
        w.ue(0) // seq_parameter_set_id
        w.flag(false) // entropy_coding_mode_flag: CAVLC
        w.flag(false) // bottom_field_pic_order_in_frame_present_flag
        w.ue(0) // num_slice_groups_minus1
        w.ue(0) // num_ref_idx_l0_default_active_minus1
        w.ue(0) // num_ref_idx_l1_default_active_minus1
        w.flag(false) // weighted_pred_flag
        w.u(2, 0) // weighted_bipred_idc
        w.se(0) // pic_init_qp_minus26
        w.se(0) // pic_init_qs_minus26
        w.se(0) // chroma_qp_index_offset
        w.flag(true) // deblocking_filter_control_present_flag
        w.flag(false) // constrained_intra_pred_flag
        w.flag(false) // redundant_pic_cnt_present_flag
        w.trailingBits()
        return byteArrayOf(0x68) + Rbsp.escape(w.toByteArray())
    }

    /** SPS ‖ PPS con start codes, como el csd-0 ‖ csd-1 que QDLink manda en un mensaje propio (SC/managers/a.java:795-809). */
    fun codecConfig(width: Int, height: Int, fps: Int): ByteArray = START + sps(width, height, fps) + START + pps()

    /** Frame falso de [size] bytes de payload tras la cabecera NAL. */
    fun fakeFrame(index: Int, keyframe: Boolean, size: Int): ByteArray {
        val out = ByteArray(START.size + 1 + size)
        START.copyInto(out)
        out[START.size] = if (keyframe) 0x65 else 0x41
        for (j in 0 until size) out[START.size + 1 + j] = ((j * 7 + index) % 255 + 1).toByte()
        return out
    }

    /** Nivel más bajo de la tabla A-1 que admite el tamaño (MaxFS) y la tasa de macrobloques (MaxMBPS). */
    fun levelFor(frameMbs: Int, fps: Int): Int {
        val mbps = frameMbs.toLong() * fps
        return LEVELS.firstOrNull { (_, maxFs, maxMbps) -> frameMbs <= maxFs && mbps <= maxMbps }?.first ?: 52
    }

    /** (level_idc, MaxFS, MaxMBPS) de la tabla A-1. */
    private val LEVELS = listOf(
        Triple(30, 1_620, 40_500L),
        Triple(31, 3_600, 108_000L),
        Triple(32, 5_120, 216_000L),
        Triple(40, 8_192, 245_760L),
        Triple(42, 8_704, 522_240L),
        Triple(50, 22_080, 589_824L),
        Triple(51, 36_864, 983_040L),
        Triple(52, 36_864, 2_073_600L),
    )
}
