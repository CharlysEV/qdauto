package dev.qdauto.app.touch

import dev.qdauto.app.util.Clock
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchEvent

/**
 * Estado del táctil del coche para pintarlo en el coche (patrón de prueba) y en el móvil: dedos activos con sus
 * valores crudos y una estela corta, los últimos eventos y los extremos vistos en la sesión. Thread-safe: escribe el
 * hilo de eventos de la sesión y leen el hilo de render y la UI.
 */
class TouchTracker {
    /** Un dedo visible. [trail] son pares `x,y` crudos, del más antiguo al más reciente. */
    class Pointer(
        val id: Int,
        val action: Int,
        val x: Float,
        val y: Float,
        val xBits: Int,
        val yBits: Int,
        val down: Boolean,
        /** Tiempo desde que se levantó (0 si sigue pulsado). */
        val releasedAgoMs: Long,
        val trail: FloatArray,
    ) {
        val actionName: String get() = TouchCodec.fingerActionName(action)
    }

    class Snapshot(
        val pointers: List<Pointer>,
        /** Últimos eventos, el más reciente primero. */
        val history: List<String>,
        val mode: TouchMapping,
        /** Interpretación efectiva (nunca [TouchMapping.AUTO]). */
        val resolved: TouchMapping,
        val space: TouchSpace,
        val extent: TouchExtent,
        val events: Long,
        /** [Long.MAX_VALUE] si aún no ha llegado ningún toque. */
        val lastEventAgoMs: Long,
    )

    private class Track {
        var action = 0
        var x = 0f
        var y = 0f
        var xBits = 0
        var yBits = 0
        var down = true
        var releasedAtNanos = 0L
        var updatedAtNanos = 0L
        private val trail = FloatArray(TRAIL_POINTS * 2)
        private var head = 0
        private var count = 0

        fun add(px: Float, py: Float) {
            trail[head * 2] = px
            trail[head * 2 + 1] = py
            head = (head + 1) % TRAIL_POINTS
            if (count < TRAIL_POINTS) count++
        }

        fun clearTrail() {
            head = 0
            count = 0
        }

        fun trailCopy(): FloatArray {
            val out = FloatArray(count * 2)
            val start = (head - count + TRAIL_POINTS) % TRAIL_POINTS
            for (i in 0 until count) {
                val k = (start + i) % TRAIL_POINTS
                out[i * 2] = trail[k * 2]
                out[i * 2 + 1] = trail[k * 2 + 1]
            }
            return out
        }
    }

    private val lock = Any()
    private val tracks = LinkedHashMap<Int, Track>()
    private val history = ArrayDeque<String>()
    private var extent = TouchExtent.EMPTY
    private var events = 0L
    private var lastEventNanos = 0L
    private var currentSpace = TouchSpace(1920, 1080, 0, 0, 2340, 1080)

    @Volatile
    var mode: TouchMapping = TouchMapping.AUTO

    val space: TouchSpace get() = synchronized(lock) { currentSpace }

    fun setFrame(width: Int, height: Int) = synchronized(lock) { currentSpace = currentSpace.copy(frameW = width, frameH = height) }
    fun setCar(width: Int, height: Int) = synchronized(lock) { currentSpace = currentSpace.copy(carW = width, carH = height) }
    fun setPhone(longSide: Int, shortSide: Int) = synchronized(lock) {
        currentSpace = currentSpace.copy(phoneLong = longSide, phoneShort = shortSide)
    }

    fun onEvent(e: TouchEvent) {
        val now = System.nanoTime()
        val line = describe(e)
        synchronized(lock) {
            events++
            lastEventNanos = now
            for (p in e.pointers) {
                val finite = p.x.isFinite() && p.y.isFinite()
                if (finite) extent = extent.include(p.x, p.y)
                val t = tracks.getOrPut(p.id) { Track() }
                if (!t.down) t.clearTrail() // nuevo gesto con este dedo
                t.action = p.action
                t.x = p.x
                t.y = p.y
                t.xBits = p.xBits
                t.yBits = p.yBits
                t.updatedAtNanos = now
                if (finite) t.add(p.x, p.y)
                // fingerAction 2 = up; action global 1 = fin del gesto (spec §9.2).
                if (p.action == TouchCodec.FINGER_UP || e.action == TouchCodec.ACTION_UP) {
                    t.down = false
                    t.releasedAtNanos = now
                } else {
                    t.down = true
                    t.releasedAtNanos = 0
                }
            }
            history.addFirst(line)
            while (history.size > HISTORY) history.removeLast()
            expire(now)
        }
    }

    fun snapshot(): Snapshot {
        val now = System.nanoTime()
        synchronized(lock) {
            expire(now)
            val pointers = tracks.entries.map { (id, t) ->
                Pointer(
                    id, t.action, t.x, t.y, t.xBits, t.yBits, t.down,
                    if (t.down) 0 else (now - t.releasedAtNanos) / 1_000_000, t.trailCopy(),
                )
            }
            val m = mode
            return Snapshot(
                pointers = pointers,
                history = history.toList(),
                mode = m,
                resolved = TouchMapper.resolve(m, extent, currentSpace),
                space = currentSpace,
                extent = extent,
                events = events,
                lastEventAgoMs = if (lastEventNanos == 0L) Long.MAX_VALUE else (now - lastEventNanos) / 1_000_000,
            )
        }
    }

    /** Nueva sesión: se olvidan los dedos, el historial y los extremos (el tamaño del frame se conserva). */
    fun reset() = synchronized(lock) {
        tracks.clear()
        history.clear()
        extent = TouchExtent.EMPTY
        events = 0
        lastEventNanos = 0
        currentSpace = currentSpace.copy(carW = 0, carH = 0)
    }

    private fun expire(now: Long) {
        val it = tracks.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val faded = !t.down && now - t.releasedAtNanos > FADE_NANOS
            val stale = now - t.updatedAtNanos > STALE_NANOS
            if (faded || stale) it.remove()
        }
    }

    companion object {
        const val TRAIL_POINTS = 48
        const val HISTORY = 10
        const val FADE_MS = 1_500L
        private const val FADE_NANOS = FADE_MS * 1_000_000
        private const val STALE_NANOS = 5_000L * 1_000_000

        fun describe(e: TouchEvent): String = buildString {
            append(Clock.now()).append(' ').append(e.describe())
            e.pointers.filter { !it.x.isFinite() || !it.y.isFinite() }.forEach { p ->
                append(" bits(id=").append(p.id).append(")=0x").append(Integer.toHexString(p.xBits))
                append("/0x").append(Integer.toHexString(p.yBits))
            }
            if (e.wouldQdlinkDrop) append(" (QDLink lo descartaría)")
        }
    }
}
