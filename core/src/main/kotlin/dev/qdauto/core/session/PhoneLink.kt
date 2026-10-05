package dev.qdauto.core.session

import dev.qdauto.core.discovery.AckHandle
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.discovery.DiscoveryListener
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.e
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

enum class LinkState {
    /** Sin arrancar o cerrado. */
    STOPPED,

    /** Escuchando broadcasts, sin sesión. */
    SEARCHING,

    /** `ServerSocket` abierto y ACK enviado; esperando el TCP del coche. */
    CONNECTING,

    /** Hay una [PhoneSession] activa. */
    CONNECTED,
}

/** Configuración de [PhoneLink]. */
data class PhoneLinkConfig(
    val discovery: DiscoveryConfig = DiscoveryConfig(),
    val session: SessionConfig = SessionConfig(),
    /** [MirrorServer.RANDOM_PORT] (10001-65535, como QDLink) o un puerto fijo. */
    val mirrorPort: Int = MirrorServer.RANDOM_PORT,
    /** Espera del TCP tras el ACK (QDLink: 20 s, WF/d.java:65). */
    val acceptTimeoutMs: Long = MirrorServer.DEFAULT_ACCEPT_TIMEOUT_MS,
    /** Conectar solo con el primer coche que pase [carFilter]; si no, hay que llamar a [PhoneLink.connect]. */
    val autoConnect: Boolean = true,
    val carFilter: (CarAnnouncement) -> Boolean = { true },
    /** Tras cerrarse la sesión o fallar un intento, volver a conectar automáticamente con el siguiente broadcast. */
    val reconnect: Boolean = true,
    /** Espera mínima entre el final de un intento y el siguiente automático. */
    val retryDelayMs: Long = 3_000,
)

/** Eventos de [PhoneLink]. Llegan desde hilos internos (descubrimiento, aceptación o eventos de la sesión). */
interface PhoneLinkListener {
    fun onLinkStateChanged(state: LinkState) {}
    fun onCarFound(car: CarAnnouncement) {}
    fun onCarSeen(car: CarAnnouncement) {}
    fun onConnecting(car: CarAnnouncement, mirrorPort: Int) {}
    fun onAcceptTimeout(car: CarAnnouncement) {}
    fun onSessionStarted(session: PhoneSession) {}
    fun onSessionEnded(session: PhoneSession, reason: CloseReason) {}

    /** El coche abrió otra conexión TCP con una sesión ya activa (se registra y se cierra). */
    fun onExtraConnection(from: InetSocketAddress?) {}
    fun onError(message: String, error: Throwable?) {}
}

/**
 * Orquestación opcional del lado del teléfono (spec §10.1, pasos 1-5) sobre [DiscoveryListener], [MirrorServer] y
 * [PhoneSession]: escucha broadcasts, elige coche, abre el `ServerSocket` **antes** del ACK, envía el ACK desde el
 * socket de 18463, acepta con límite de tiempo, arranca la sesión y, cuando termina, vuelve a buscar.
 * Las piezas sueltas siguen disponibles para quien necesite otro flujo.
 *
 * Hilos: los del [DiscoveryListener], uno de aceptación por intento (`qd-link-accept`) y los de cada sesión.
 * Los callbacks de [PhoneLinkListener] nunca se llaman con locks internos tomados.
 */
class PhoneLink(
    val config: PhoneLinkConfig = PhoneLinkConfig(),
    private val listener: PhoneLinkListener = object : PhoneLinkListener {},
    private val sessionListener: SessionListener = object : SessionListener {},
    private val log: QdLog = QdLog.NONE,
) : Closeable {
    private val lock = Any()
    private var linkState = LinkState.STOPPED
    private var attempt: Attempt? = null
    private var lastAttempt: Attempt? = null
    private var lastAttemptEndMillis = 0L
    private var attempts = 0
    private var reserving = false
    private var closed = false

    val discovery: DiscoveryListener = DiscoveryListener(config.discovery, DiscoveryCallbacks(), log)

    val state: LinkState get() = synchronized(lock) { linkState }

    /** Sesión activa (o `null`). */
    val currentSession: PhoneSession? get() = synchronized(lock) { attempt?.session?.takeUnless { it.isClosed } }

    /** Abre el socket UDP (lanza si no se puede) y empieza a buscar. */
    @Throws(IOException::class)
    fun start(): PhoneLink {
        discovery.start()
        if (transition(LinkState.SEARCHING)) listener.onLinkStateChanged(LinkState.SEARCHING)
        return this
    }

    /** Conecta con [car] si no hay otro intento o sesión en marcha. Devuelve `false` si está ocupado o cerrado. */
    fun connect(car: CarAnnouncement): Boolean {
        synchronized(lock) {
            if (closed || attempt != null || reserving) return false
            reserving = true
        }
        var error: IOException? = null
        val server = try {
            MirrorServer(config.mirrorPort, log = log)
        } catch (e: IOException) {
            error = e
            null
        }
        val a = synchronized(lock) {
            reserving = false
            attempts++
            if (server == null || closed) {
                lastAttemptEndMillis = System.currentTimeMillis()
                null
            } else {
                Attempt(car, server).also {
                    attempt = it
                    lastAttempt = it
                }
            }
        }
        if (a == null) {
            server?.close()
            if (error != null) {
                log.e(TAG, "no se pudo abrir el puerto TCP ${config.mirrorPort}", error)
                listener.onError("no se pudo abrir el puerto TCP ${config.mirrorPort}: ${error.message}", error)
            }
            return false
        }
        if (transition(LinkState.CONNECTING)) listener.onLinkStateChanged(LinkState.CONNECTING)
        log.i(TAG, "conectando con $car en el puerto ${a.server.port}")
        listener.onConnecting(car, a.server.port)
        // El ServerSocket ya escucha antes de enviar el ACK (recomendación spec §1.4).
        a.ack = try {
            discovery.sendAck(car, a.server.port)
        } catch (e: Exception) {
            listener.onError("no se pudo enviar el ACK: ${e.message}", e)
            null
        }
        a.thread = Thread({ acceptLoop(a) }, "qd-link-accept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Cierra la sesión o el intento en curso; con [PhoneLinkConfig.reconnect] se volverá a conectar. */
    fun disconnect() {
        val a = synchronized(lock) { attempt } ?: return
        a.ack?.cancel()
        a.session?.close()
        a.server.close()
    }

    override fun close() {
        val a = synchronized(lock) {
            if (closed) return
            closed = true
            attempt
        }
        discovery.close()
        a?.ack?.cancel()
        a?.session?.close()
        a?.server?.close()
        if (transition(LinkState.STOPPED)) listener.onLinkStateChanged(LinkState.STOPPED)
    }

    /** Espera a que terminen todos los hilos (tras [close]). No llamar desde un callback. */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun left() = maxOf(1L, deadline - System.currentTimeMillis())
        val a = synchronized(lock) { lastAttempt }
        a?.thread?.join(left())
        val sessionDone = a?.session?.awaitTermination(left()) ?: true
        return discovery.awaitTermination(left()) && a?.thread?.isAlive != true && sessionDone
    }

    /** Número de intentos de conexión hechos (para pruebas y estadísticas). */
    val attemptCount: Int get() = synchronized(lock) { attempts }

    private fun acceptLoop(a: Attempt) {
        try {
            val socket = try {
                a.server.accept(config.acceptTimeoutMs)
            } catch (_: SocketTimeoutException) {
                log.w(TAG, "el coche no conectó en ${config.acceptTimeoutMs} ms")
                listener.onAcceptTimeout(a.car)
                return
            } catch (e: IOException) {
                if (!a.server.isClosed) listener.onError("error esperando la conexión: ${e.message}", e)
                return
            }
            a.ack?.cancel()
            val session = PhoneSession(socket, config.session, SessionWatcher(a), log)
            val accepted = synchronized(lock) {
                if (closed || attempt !== a) {
                    false
                } else {
                    a.session = session
                    true
                }
            }
            if (!accepted) {
                session.close()
                return
            }
            try {
                session.start()
            } catch (e: IOException) {
                listener.onError("no se pudo arrancar la sesión: ${e.message}", e)
                session.close() // SessionWatcher.onClosed cierra el intento
                return
            }
            if (transition(LinkState.CONNECTED)) listener.onLinkStateChanged(LinkState.CONNECTED)
            listener.onSessionStarted(session)
            // QDLink deja el ServerSocket abierto; registramos (y cerramos) cualquier conexión extra del coche.
            while (!a.server.isClosed && !session.isClosed) {
                val extra = try {
                    a.server.accept(0)
                } catch (_: IOException) {
                    break
                }
                val from = extra.remoteSocketAddress as? InetSocketAddress
                log.w(TAG, "conexión TCP extra de $from con la sesión activa; se cierra")
                listener.onExtraConnection(from)
                try {
                    extra.close()
                } catch (_: IOException) {
                }
            }
        } finally {
            a.ack?.cancel()
            a.server.close()
            if (a.session == null) endAttempt(a)
        }
    }

    /** Termina el intento: vuelve a SEARCHING (o se queda en STOPPED si se cerró). */
    private fun endAttempt(a: Attempt) {
        val searching = synchronized(lock) {
            if (attempt !== a) return
            attempt = null
            lastAttemptEndMillis = System.currentTimeMillis()
            !closed
        }
        if (searching && transition(LinkState.SEARCHING)) listener.onLinkStateChanged(LinkState.SEARCHING)
    }

    private fun maybeAutoConnect(car: CarAnnouncement) {
        if (!config.autoConnect || !config.carFilter(car)) return
        val canTry = synchronized(lock) {
            val first = attempts == 0
            !closed && attempt == null && !reserving && (first || config.reconnect) &&
                (first || System.currentTimeMillis() - lastAttemptEndMillis >= config.retryDelayMs)
        }
        if (canTry) connect(car)
    }

    /** Cambia el estado; devuelve si cambió (el aviso se hace fuera del lock). */
    private fun transition(s: LinkState): Boolean {
        val changed = synchronized(lock) {
            if (linkState == s || (closed && s != LinkState.STOPPED)) {
                false
            } else {
                linkState = s
                true
            }
        }
        if (changed) log.i(TAG, "enlace → $s")
        return changed
    }

    private class Attempt(val car: CarAnnouncement, val server: MirrorServer) {
        @Volatile
        var ack: AckHandle? = null

        @Volatile
        var thread: Thread? = null

        @Volatile
        var session: PhoneSession? = null
    }

    private inner class DiscoveryCallbacks : DiscoveryListener.Callback {
        override fun onCarFound(car: CarAnnouncement) {
            listener.onCarFound(car)
            maybeAutoConnect(car)
        }

        override fun onCarSeen(car: CarAnnouncement) {
            listener.onCarSeen(car)
            maybeAutoConnect(car)
        }

        override fun onError(error: Throwable) = listener.onError("descubrimiento: ${error.message}", error)
    }

    /** Reenvía todo al listener de la app y detecta el final de la sesión. */
    private inner class SessionWatcher(private val a: Attempt) : SessionListener by sessionListener {
        override fun onClosed(reason: CloseReason) {
            try {
                sessionListener.onClosed(reason)
            } finally {
                a.server.close()
                a.session?.let { listener.onSessionEnded(it, reason) }
                endAttempt(a)
            }
        }
    }

    private companion object {
        const val TAG = "QD/Link"
    }
}
