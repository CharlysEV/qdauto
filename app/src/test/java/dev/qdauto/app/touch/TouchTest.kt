package dev.qdauto.app.touch

import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.TouchPointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTest {
    private val space = TouchSpace(frameW = 1920, frameH = 1088, carW = 1920, carH = 1080, phoneLong = 2340, phoneShort = 1080)

    private fun extentOf(vararg points: Pair<Float, Float>) =
        points.fold(TouchExtent.EMPTY) { e, (x, y) -> e.include(x, y) }

    @Test
    fun guessesTheSmallestSpaceThatFitsEverything() {
        assertEquals(TouchMapping.FRAME_PX, TouchMapper.guess(TouchExtent.EMPTY, space))
        assertEquals(TouchMapping.NORMALIZED, TouchMapper.guess(extentOf(0.5f to 0.25f, 0.9f to 1f), space))
        // 0 y 1 enteros no bastan para decir "normalizado".
        assertEquals(TouchMapping.FRAME_PX, TouchMapper.guess(extentOf(0f to 0f, 1f to 1f), space))
        assertEquals(TouchMapping.FRAME_PX, TouchMapper.guess(extentOf(960f to 540f, 1919.5f to 1087f), space))
        assertEquals(TouchMapping.PHONE_PX, TouchMapper.guess(extentOf(2000f to 500f), space))
        val small = space.copy(frameW = 1280, frameH = 720)
        assertEquals(TouchMapping.CAR_PX, TouchMapper.guess(extentOf(1500f to 900f), small))
        assertEquals(TouchMapping.FRAME_PX, TouchMapper.guess(extentOf(-5f to 10f), space))
        assertEquals(TouchMapping.PHONE_PX, TouchMapper.resolve(TouchMapping.PHONE_PX, extentOf(1f to 1f), space))
    }

    @Test
    fun mapsToFramePixels() {
        val frame = TouchSpace(1920, 1080, 1280, 720, 2340, 1080)
        assertEquals(FramePoint(960f, 540f, true), TouchMapper.toFrame(0.5f, 0.5f, TouchMapping.NORMALIZED, frame))
        assertEquals(FramePoint(960f, 540f, true), TouchMapper.toFrame(640f, 360f, TouchMapping.CAR_PX, frame))
        assertEquals(FramePoint(1920f, 1080f, true), TouchMapper.toFrame(2340f, 1080f, TouchMapping.PHONE_PX, frame))
        assertEquals(FramePoint(100.5f, 200.25f, true), TouchMapper.toFrame(100.5f, 200.25f, TouchMapping.FRAME_PX, frame))
        assertFalse(TouchMapper.toFrame(2000f, 10f, TouchMapping.FRAME_PX, frame).inside)
        assertFalse(TouchMapper.toFrame(Float.NaN, 10f, TouchMapping.FRAME_PX, frame).inside)
    }

    @Test
    fun trackerFollowsFingersAndHistory() {
        val t = TouchTracker()
        t.setFrame(1920, 1080)
        t.onEvent(event(TouchCodec.ACTION_DOWN, TouchPointer.down(0, 100f, 200f)))
        t.onEvent(event(TouchCodec.ACTION_MOVE, TouchPointer.move(0, 110f, 210f), TouchPointer.down(1, 500f, 600f)))
        var snap = t.snapshot()
        assertEquals(2, snap.pointers.size)
        val first = snap.pointers.first { it.id == 0 }
        assertTrue(first.down)
        assertEquals(4, first.trail.size) // dos puntos
        assertEquals(110f, first.x)
        assertEquals(2, snap.history.size)
        assertEquals(TouchMapping.FRAME_PX, snap.resolved)

        // action global 1 (fin del gesto) levanta los dedos del evento; siguen visibles un momento.
        t.onEvent(event(TouchCodec.ACTION_UP, TouchPointer.up(0, 110f, 210f), TouchPointer.up(1, 500f, 600f)))
        snap = t.snapshot()
        assertTrue(snap.pointers.none { it.down })
        assertEquals(3L, snap.events)

        // Un dedo nuevo con el mismo id empieza estela nueva.
        t.onEvent(event(TouchCodec.ACTION_DOWN, TouchPointer.down(0, 5f, 5f)))
        assertEquals(2, t.snapshot().pointers.first { it.id == 0 }.trail.size)

        // Valores fuera del frame pero dentro del teléfono: la interpretación automática cambia para toda la sesión.
        t.setPhone(2340, 1080)
        t.onEvent(event(TouchCodec.ACTION_MOVE, TouchPointer.move(0, 2200f, 1000f)))
        assertEquals(TouchMapping.PHONE_PX, t.snapshot().resolved)

        t.reset()
        snap = t.snapshot()
        assertTrue(snap.pointers.isEmpty())
        assertTrue(snap.history.isEmpty())
        assertEquals(0L, snap.events)
        assertEquals(1920, snap.space.frameW)
    }

    @Test
    fun describeShowsRawBitsForNonFiniteValues() {
        val nan = Float.fromBits(0x7fc00001)
        val line = TouchTracker.describe(event(TouchCodec.ACTION_MOVE, TouchPointer(3, 3, nan, 1f)))
        assertTrue("bits(id=3)=0x7fc00001" in line, line)
    }

    private fun event(action: Int, vararg pointers: TouchPointer) = TouchEvent(
        action = action,
        declaredCount = pointers.size,
        pointers = pointers.toList(),
        payloadLength = 5 + 10 * pointers.size,
        truncated = false,
        extraBytes = 0,
    )
}
