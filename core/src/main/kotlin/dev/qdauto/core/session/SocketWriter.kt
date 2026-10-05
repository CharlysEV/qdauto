package dev.qdauto.core.session

import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.Traces
import dev.qdauto.core.wire.VideoExtHeader
import dev.qdauto.core.wire.VideoMessage
import java.io.IOException
import java.io.OutputStream

/**
 * Bucle del hilo escritor: único que escribe en el socket, un `write()` por mensaje (como QDLink, LC/a.java:2666-2667),
 * en el orden que da [SendQueue] (control antes que vídeo).
 */
internal class SocketWriter(
    private val queue: SendQueue,
    private val counters: SessionCounters,
    private val config: SessionConfig,
    private val trace: (TraceEvent) -> Unit,
    private val onWriteError: (IOException) -> Unit,
    private val onCloseAfter: (Outgoing) -> Unit,
) {
    /** `System.nanoTime()` del `write()` en curso, o 0 (para detectar escrituras bloqueadas). */
    @Volatile
    var writeStartNanos = 0L
        private set

    fun run(out: OutputStream) {
        while (true) {
            val item = queue.take() ?: return
            writeStartNanos = System.nanoTime()
            try {
                out.write(item.bytes)
                out.flush()
            } catch (e: IOException) {
                writeStartNanos = 0
                onWriteError(e)
                return
            }
            writeStartNanos = 0
            written(item)
            if (item.closeAfter) {
                onCloseAfter(item)
                return
            }
        }
    }

    private fun written(item: Outgoing) {
        val size = item.bytes.size
        counters.bytesSent.addAndGet(size.toLong())
        counters.messagesSent.incrementAndGet()
        if (item.kind == OutKind.CONTROL) {
            val text = item.text
            trace(
                if (text != null) {
                    Traces.ofJson(Direction.OUT, item.label, item.msgType ?: MsgType.CONTROL, item.bytes, text, config.traceMaxJsonChars)
                } else {
                    Traces.ofBinary(Direction.OUT, item.label, item.msgType, item.bytes, item.label, config.traceHexPrefixBytes)
                },
            )
            return
        }
        counters.videoBytesSent.addAndGet(size.toLong())
        when (item.kind) {
            OutKind.VIDEO_CONFIG -> counters.codecConfigsSent.incrementAndGet()
            OutKind.VIDEO_KEY -> counters.keyframesSent.incrementAndGet()
            else -> Unit
        }
        if (item.isFrame) {
            counters.videoFramesSent.incrementAndGet()
            counters.videoRate.add(size)
        }
        if (config.traceVideoFrames) {
            val p = VideoExtHeader.decode(item.bytes, Header.SIZE).params
            val waitedMs = (System.nanoTime() - item.enqueuedNanos) / 1_000_000
            val summary = "${size - VideoMessage.HEADER_SIZE} B ${p.width}x${p.height} app=${p.appType} " +
                "ang=${p.angle} or=${p.orientation} cola=${waitedMs}ms" + (if (item.ptsUs >= 0) " pts=${item.ptsUs}" else "")
            trace(Traces.ofVideo(Direction.OUT, item.label, size, summary))
        }
    }
}
