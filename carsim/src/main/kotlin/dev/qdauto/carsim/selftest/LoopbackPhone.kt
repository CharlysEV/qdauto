package dev.qdauto.carsim.selftest

import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.discovery.DiscoveryListener
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.KeyframeReason
import dev.qdauto.core.session.MirrorServer
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.SessionConfig
import dev.qdauto.core.session.SessionListener
import dev.qdauto.core.session.VideoOverrides
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.UnknownMessage
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Teléfono simulado en el mismo proceso, con las piezas de `:core` que usará la app: [DiscoveryListener] →
 * [MirrorServer] abierto antes del ACK → ACK → `accept()` → [PhoneSession], con [FakeEncoder] como encoder.
 * Todo escucha solo en 127.0.0.1 (sin avisos del cortafuegos) y en puertos libres.
 */
class LoopbackPhone(
    phonePort: Int,
    carPort: Int,
    private val log: QdLog,
    /** Para las pruebas negativas: fuerza campos de la cabecera de vídeo. */
    private val videoOverrides: VideoOverrides = VideoOverrides(),
) : Closeable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val closed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val sessionClosed = CountDownLatch(1)
    private var connectThread: Thread? = null

    @Volatile
    private var server: MirrorServer? = null

    @Volatile
    var session: PhoneSession? = null
        private set

    @Volatile
    var closeReason: CloseReason? = null
        private set

    val encoder = FakeEncoder()
    val touches = CopyOnWriteArrayList<TouchEvent>()
    val keys = CopyOnWriteArrayList<CarKey>()
    val keyframeReasons = CopyOnWriteArrayList<KeyframeReason>()
    val unknown = CopyOnWriteArrayList<UnknownMessage>()

    private val discovery = DiscoveryListener(
        DiscoveryConfig(port = phonePort, ackPort = carPort, bindAddress = loopback),
        object : DiscoveryListener.Callback {
            override fun onCarFound(car: CarAnnouncement) = connectAsync(car)
        },
        log,
    )

    private val listener = object : SessionListener {
        override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
            if (play) session?.let(encoder::start)
        }

        override fun onKeyframeRequested(reason: KeyframeReason) {
            keyframeReasons += reason
            encoder.requestKeyframe()
        }

        override fun onTouch(event: TouchEvent) {
            touches += event
        }

        override fun onKey(key: CarKey) {
            keys += key
        }

        override fun onUnknownMessage(message: UnknownMessage) {
            unknown += message
        }

        override fun onClosed(reason: CloseReason) {
            closeReason = reason
            encoder.stop()
            sessionClosed.countDown()
        }
    }

    @Throws(IOException::class)
    fun start(): LoopbackPhone {
        discovery.start()
        return this
    }

    /** Espera a que la sesión termine (el coche cierra el TCP al acabar la prueba). */
    fun awaitSessionClosed(timeoutMs: Long): Boolean = session == null || sessionClosed.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        encoder.stop()
        session?.close()
        server?.close()
        discovery.close()
    }

    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun left() = maxOf(1L, deadline - System.currentTimeMillis())
        connectThread?.join(left())
        encoder.join(left())
        val sessionDone = session?.awaitTermination(left()) ?: true
        return discovery.awaitTermination(left()) && sessionDone && connectThread?.isAlive != true && !encoder.isAlive
    }

    /** Fuera del hilo de descubrimiento: sus callbacks no se pueden bloquear. */
    private fun connectAsync(car: CarAnnouncement) {
        if (closed.get() || !connecting.compareAndSet(false, true)) return
        connectThread = Thread({ connect(car) }, "autotest-conexion").apply {
            isDaemon = true
            start()
        }
    }

    private fun connect(car: CarAnnouncement) {
        try {
            val srv = MirrorServer(0, loopback, log)
            server = srv
            // El ServerSocket ya escucha cuando sale el ACK (spec §1.4).
            val ack = discovery.sendAck(car, srv.port)
            val socket = srv.accept(10_000)
            ack.cancel()
            val s = PhoneSession(socket, SessionConfig(videoOverrides = videoOverrides), listener, log)
            session = s
            if (closed.get()) {
                s.close()
                return
            }
            s.start()
        } catch (e: IOException) {
            if (!closed.get()) log.e(TAG, "el teléfono simulado no pudo conectar", e)
        }
    }

    private companion object {
        const val TAG = "QD/Autotest"
    }
}
