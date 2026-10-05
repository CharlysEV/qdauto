package dev.qdauto.carsim.run

import dev.qdauto.carsim.h264.BitstreamException
import dev.qdauto.carsim.h264.SpsInfo
import dev.qdauto.carsim.h264.SpsParser
import dev.qdauto.core.h264.AnnexB
import dev.qdauto.core.h264.NalType
import dev.qdauto.core.sim.VideoFrameInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Ancho y alto de una cabecera de vídeo de 32 bytes. */
data class Dims(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"
}

/**
 * Un mensaje de vídeo con SPS y los que le siguen hasta el siguiente SPS: dónde está su payload en la grabación
 * y qué ancho x alto dijeron las cabeceras de 32 bytes mientras ese SPS estaba vigente.
 */
data class SpsSegment(
    val messageIndex: Int,
    val tMs: Long,
    val fileOffset: Long,
    val length: Int,
    val headerSizes: Map<Dims, Int>,
)

/**
 * `VideoFrameInfo` no trae los bytes del vídeo, pero `CarSim` graba el payload de cada mensaje, uno tras otro, en
 * `recordVideoTo`. Sumando `payloadSize` se sabe dónde empieza cada mensaje con SPS en el fichero. No es
 * thread-safe: lo protege [RunRecorder].
 */
class SpsTracker {
    private class Open(val messageIndex: Int, val tMs: Long, val fileOffset: Long, val length: Int) {
        val headerSizes = LinkedHashMap<Dims, Int>()
    }

    private var offset = 0L
    private val segments = ArrayList<Open>()
    private val beforeFirst = LinkedHashMap<Dims, Int>()

    fun onMessage(tMs: Long, info: VideoFrameInfo) {
        val start = offset
        offset += info.payloadSize
        if (NalType.SPS in info.nalTypes) segments += Open(info.index, tMs, start, info.payloadSize)
        val params = info.header?.params ?: return
        val target = segments.lastOrNull()?.headerSizes ?: beforeFirst
        target.merge(Dims(params.width, params.height), 1, Int::plus)
    }

    fun first(): SpsSegment? = segments.firstOrNull()?.let(::freeze)

    fun segments(): List<SpsSegment> = segments.map(::freeze)

    /** Cabeceras de mensajes que llegaron antes del primer SPS. */
    fun headersBeforeFirstSps(): Map<Dims, Int> = LinkedHashMap(beforeFirst)

    private fun freeze(o: Open) = SpsSegment(o.messageIndex, o.tMs, o.fileOffset, o.length, LinkedHashMap(o.headerSizes))
}

/** SPS de un segmento ya leído de la grabación. */
class SpsResult(
    val segment: SpsSegment,
    val nal: ByteArray?,
    val info: SpsInfo?,
    val error: String?,
)

/** Lee de la grabación los SPS de los segmentos. */
object SpsReader {
    fun read(file: File, segment: SpsSegment): SpsResult {
        val payload = try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < segment.fileOffset + segment.length) {
                    return SpsResult(segment, null, null, "la grabación aún no llega al mensaje #${segment.messageIndex}")
                }
                ByteArray(segment.length).also {
                    raf.seek(segment.fileOffset)
                    raf.readFully(it)
                }
            }
        } catch (e: IOException) {
            return SpsResult(segment, null, null, "no se pudo leer la grabación: ${e.message}")
        }
        val unit = AnnexB.nalUnits(payload).firstOrNull { it.type == NalType.SPS }
            ?: return SpsResult(segment, null, null, "no se encontró la NAL SPS en el mensaje #${segment.messageIndex}")
        val nal = payload.copyOfRange(unit.offset, unit.offset + unit.length)
        return try {
            SpsResult(segment, nal, SpsParser.parse(nal), null)
        } catch (e: BitstreamException) {
            SpsResult(segment, nal, null, "SPS ilegible: ${e.message}")
        }
    }

    fun readAll(file: File, segments: List<SpsSegment>): List<SpsResult> = segments.map { read(file, it) }
}
