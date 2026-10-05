package dev.qdauto.carsim.net

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Copia del `Connect_Broadcast` para los destinos que `CarSim` no cubre (solo admite uno). Sale de un puerto
 * efímero: da igual, porque el teléfono contesta siempre a IP_origen:18464 (QDLink: WF/a.java:31), donde escucha
 * el simulador. Se envía mientras [active] sea cierto (hasta que llega el ACK).
 */
class BroadcastFanout(
    private val targets: List<InetSocketAddress>,
    private val payload: ByteArray,
    private val intervalMs: Long,
    private val active: () -> Boolean,
    private val onError: (String) -> Unit,
) : Closeable {
    private val socket: DatagramSocket = try {
        DatagramSocket().apply { broadcast = true }
    } catch (e: SocketException) {
        throw IOException("no se pudo abrir el socket UDP para los broadcasts extra: ${e.message}", e)
    }
    private val thread = Thread(::loop, "carsim-difusion").apply { isDaemon = true }
    private val failed = HashSet<InetSocketAddress>()

    @Volatile
    private var closed = false

    @Volatile
    var sent = 0
        private set

    fun start(): BroadcastFanout {
        thread.start()
        return this
    }

    private fun loop() {
        while (!closed && active()) {
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, target))
                    sent++
                } catch (e: IOException) {
                    if (!closed && failed.add(target)) onError("no se pudo enviar el broadcast a ${target.address.hostAddress}: ${e.message}")
                }
            }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    override fun close() {
        closed = true
        thread.interrupt()
        socket.close()
    }
}
