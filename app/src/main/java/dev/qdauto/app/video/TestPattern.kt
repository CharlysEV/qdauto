package dev.qdauto.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import dev.qdauto.app.touch.TouchMapper
import dev.qdauto.app.touch.TouchTracker
import dev.qdauto.app.util.Clock
import java.util.Locale

/**
 * Patrón de prueba que se codifica y se ve en la pantalla del coche:
 * - barras de color, rejilla del 10 % con coordenadas en px, borde y esquinas (recortes y escalado);
 * - parches de líneas de 1 px (si el coche escala, aparece moiré);
 * - barra que se desplaza en horizontal (fluidez y tearing) y tira de 10 casillas (frames perdidos o repetidos);
 * - contador de frames, tiempo transcurrido, reloj, parámetros del vídeo y estado de la sesión;
 * - los toques del coche como cruces con id, valores crudos y estela, para ver si las coordenadas cuadran 1:1.
 *
 * Lo estático se pinta una vez en un bitmap; [draw] lo copia y añade lo dinámico. Lo usa solo el hilo de render.
 */
class TestPattern(
    private val width: Int,
    private val height: Int,
    /** `CarWidth×CarHeight`: si el frame es mayor, se marca el límite. */
    private val carSize: IntSize?,
    private val touches: TouchTracker,
    /** Línea con el estado de la sesión (se refresca dos veces por segundo). */
    private val liveLine: () -> String,
) {
    /** Líneas fijas de información (encoder, CAR_INFO, VIDEO_ARGS…). Se pueden cambiar desde otro hilo. */
    @Volatile
    var infoLines: List<String> = listOf("QDAuto · fase 0 · patrón de prueba")

    private val u = height / 100f
    private val background: Bitmap = createBitmap(width, height)
    private val rect = RectF()

    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bigText = textPaint(17 * u, Color.WHITE, bold = true)
    private val midText = textPaint(4.2f * u, Color.WHITE, bold = true)
    private val infoText = textPaint(2.7f * u, Color.WHITE, bold = false)
    private val liveText = textPaint(2.7f * u, Color.rgb(255, 230, 90), bold = false)
    private val touchText = textPaint(2.8f * u, Color.WHITE, bold = true)
    private val smallText = textPaint(2.2f * u, Color.WHITE, bold = false)
    private val backdrop = Paint().apply { color = Color.argb(170, 0, 0, 0) }

    private var live = ""
    private var liveAtNanos = 0L

    init {
        drawStatic(Canvas(background))
    }

    fun draw(canvas: Canvas, frame: Long, elapsedNanos: Long) {
        canvas.drawBitmap(background, 0f, 0f, null)
        drawMovingBar(canvas, elapsedNanos)
        drawCounter(canvas, frame, elapsedNanos)
        drawInfo(canvas)
        drawFrameStrip(canvas, frame)
        drawTouches(canvas)
    }

    fun release() {
        background.recycle()
    }

    // ===================================================================== estático

    private fun drawStatic(c: Canvas) {
        c.drawColor(Color.rgb(28, 28, 32))
        // Barras de color (12 % superior).
        val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK)
        val barW = width / bars.size.toFloat()
        bars.forEachIndexed { i, color ->
            fill.color = color
            c.drawRect(i * barW, 0f, (i + 1) * barW, 12 * u, fill)
        }
        // Rejilla del 10 % con coordenadas en px.
        val grid = Paint().apply { color = Color.rgb(96, 96, 104); strokeWidth = 1f }
        val label = textPaint(maxOf(12f, 1.9f * u), Color.rgb(200, 200, 210), bold = false)
        for (k in 1..9) {
            val x = width * k / 10f
            val y = height * k / 10f
            c.drawLine(x, 0f, x, height.toFloat(), grid)
            c.drawLine(0f, y, width.toFloat(), y, grid)
        }
        for (i in 1..9) {
            for (j in 1..9) {
                val x = width * i / 10
                val y = height * j / 10
                drawLabel(c, "$x,$y", x + 3f, y - 3f, label, Color.argb(120, 0, 0, 0))
            }
        }
        // Parches de líneas de 1 px (escalado → moiré), abajo a la derecha.
        val patch = (12 * u).toInt()
        val px = width - 2 * patch - 8 - (4 * u).toInt()
        val py = (76 * u).toInt()
        val line = Paint().apply { strokeWidth = 1f }
        for (k in 0 until patch) {
            line.color = if (k % 2 == 0) Color.WHITE else Color.BLACK
            c.drawLine(px + k + 0.5f, py.toFloat(), px + k + 0.5f, (py + patch).toFloat(), line)
            c.drawLine((px + patch + 8).toFloat(), py + k + 0.5f, (px + 2 * patch + 8).toFloat(), py + k + 0.5f, line)
        }
        drawLabel(c, "líneas de 1 px", px.toFloat(), py - 4f, label, Color.argb(160, 0, 0, 0))
        // Centro.
        val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f }
        val cx = width / 2f
        val cy = height / 2f
        c.drawLine(cx - 3 * u, cy, cx + 3 * u, cy, center)
        c.drawLine(cx, cy - 3 * u, cx, cy + 3 * u, center)
        // Borde de 2 px y esquinas con sus coordenadas.
        stroke.color = Color.GREEN
        stroke.strokeWidth = 2f
        c.drawRect(1f, 1f, width - 1f, height - 1f, stroke)
        val corner = textPaint(maxOf(14f, 2.4f * u), Color.GREEN, bold = true)
        val cornerH = corner.textSize
        drawLabel(c, "(0,0)", 4f, 12 * u + cornerH + 4f, corner, Color.BLACK)
        drawLabelRight(c, "(${width - 1},0)", width - 4f, 12 * u + cornerH + 4f, corner)
        drawLabel(c, "(0,${height - 1})", 4f, height - 6f, corner, Color.BLACK)
        drawLabelRight(c, "(${width - 1},${height - 1})", width - 4f, height - 6f, corner)
        // Límite del tamaño de CAR_INFO si el frame es mayor (p. ej. alineado a 16).
        if (carSize != null && carSize.width > 0 && carSize.height > 0) {
            val limit = Paint().apply { color = Color.RED; strokeWidth = 2f }
            val note = textPaint(maxOf(12f, 2f * u), Color.RED, bold = true)
            if (carSize.width < width) {
                c.drawLine(carSize.width.toFloat(), 0f, carSize.width.toFloat(), height.toFloat(), limit)
            }
            if (carSize.height < height) {
                c.drawLine(0f, carSize.height.toFloat(), width.toFloat(), carSize.height.toFloat(), limit)
                drawLabel(c, "límite CAR_INFO $carSize", width * 0.35f, carSize.height - 4f, note, Color.BLACK)
            }
        }
        background.prepareToDraw()
    }

    // ===================================================================== dinámico

    private fun drawMovingBar(c: Canvas, elapsedNanos: Long) {
        val top = 13 * u
        val bottom = 21 * u
        fill.color = Color.rgb(50, 50, 58)
        c.drawRect(0f, top, width.toFloat(), bottom, fill)
        val barW = maxOf(8f, width / 48f)
        val period = 2_000_000_000L
        val x = (elapsedNanos % period).toFloat() / period * (width - barW)
        fill.color = Color.WHITE
        c.drawRect(x, top, x + barW, bottom, fill)
        fill.color = Color.RED
        c.drawRect(x + barW / 2 - 1, top, x + barW / 2 + 1, bottom, fill)
    }

    private fun drawCounter(c: Canvas, frame: Long, elapsedNanos: Long) {
        val baseline = 40 * u
        val counter = String.format(Locale.ROOT, "#%06d", frame)
        rect.set(3 * u, baseline - bigText.textSize * 0.85f, 3 * u + bigText.measureText(counter) + 2 * u, baseline + 2 * u)
        c.drawRect(rect, backdrop)
        c.drawText(counter, 4 * u, baseline, bigText)
        val seconds = elapsedNanos / 1e9
        val right = width - 4 * u
        drawLabelRight(c, String.format(Locale.ROOT, "%.3f s", seconds), right, 30 * u, midText)
        drawLabelRight(c, Clock.now(), right, 36 * u, midText)
    }

    private fun drawInfo(c: Canvas) {
        val now = System.nanoTime()
        if (now - liveAtNanos > 500_000_000L) {
            liveAtNanos = now
            live = try {
                liveLine()
            } catch (e: Exception) {
                "estado no disponible: $e"
            }
        }
        var y = 46 * u
        val step = 3.9f * u
        for (line in infoLines) {
            drawLabel(c, line, 4 * u, y, infoText, backdrop.color)
            y += step
        }
        for (line in live.split('\n')) {
            drawLabel(c, line, 4 * u, y, liveText, backdrop.color)
            y += step
        }
    }

    private fun drawFrameStrip(c: Canvas, frame: Long) {
        val size = 5 * u
        val gap = 1 * u
        val top = 80 * u
        val lit = (frame % 10).toInt()
        for (i in 0 until 10) {
            fill.color = if (i == lit) Color.WHITE else Color.rgb(70, 70, 80)
            val x = 4 * u + i * (size + gap)
            c.drawRect(x, top, x + size, top + size, fill)
        }
        drawLabel(c, "frame % 10", 4 * u + 10 * (size + gap), top + size * 0.8f, infoText, backdrop.color)
    }

    private fun drawTouches(c: Canvas) {
        val snap = touches.snapshot()
        val mapping = snap.resolved
        val space = snap.space.copy(frameW = width, frameH = height)
        for (p in snap.pointers) {
            val color = POINTER_COLORS[Math.floorMod(p.id, POINTER_COLORS.size)]
            val alpha = if (p.down) 255 else (255 * (1f - p.releasedAgoMs / TouchTracker.FADE_MS.toFloat())).toInt().coerceIn(40, 255)
            // Estela.
            stroke.color = color
            stroke.alpha = alpha / 2
            stroke.strokeWidth = maxOf(3f, 0.5f * u)
            val t = p.trail
            var i = 2
            while (i + 1 < t.size) {
                val a = TouchMapper.toFrame(t[i - 2], t[i - 1], mapping, space)
                val b = TouchMapper.toFrame(t[i], t[i + 1], mapping, space)
                c.drawLine(a.x, a.y, b.x, b.y, stroke)
                i += 2
            }
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            val m = TouchMapper.toFrame(p.x, p.y, mapping, space)
            val mx = m.x.coerceIn(0f, width.toFloat())
            val my = m.y.coerceIn(0f, height.toFloat())
            // Cruz de lado a lado y círculo.
            stroke.alpha = alpha
            stroke.strokeWidth = 2f
            c.drawLine(0f, my, width.toFloat(), my, stroke)
            c.drawLine(mx, 0f, mx, height.toFloat(), stroke)
            stroke.strokeWidth = maxOf(4f, 0.6f * u)
            c.drawCircle(mx, my, 4 * u, stroke)
            val text = "id=${p.id} ${p.actionName} x=${p.x} y=${p.y} → (${m.x.toInt()},${m.y.toInt()}) ${mapping.short}" +
                if (m.inside) "" else " FUERA"
            touchText.color = color
            touchText.alpha = alpha
            val tw = touchText.measureText(text)
            val tx = if (mx + 5 * u + tw < width) mx + 5 * u else maxOf(0f, mx - 5 * u - tw)
            val ty = if (my - 5 * u > 25 * u) my - 5 * u else my + 8 * u
            drawLabel(c, text, tx, ty, touchText, Color.argb(alpha * 2 / 3, 0, 0, 0))
        }
        val last = snap.history.firstOrNull() ?: "sin toques todavía"
        drawLabel(c, "Táctil (${snap.events}, ${mapping.short}): $last", 4 * u, 96 * u, smallText, backdrop.color)
    }

    // ===================================================================== utilidades

    private fun drawLabel(c: Canvas, text: String, x: Float, y: Float, paint: Paint, background: Int) {
        val w = paint.measureText(text)
        val fm = paint.fontMetrics
        fill.color = background
        c.drawRect(x - 2, y + fm.ascent - 1, x + w + 2, y + fm.descent + 1, fill)
        c.drawText(text, x, y, paint)
    }

    private fun drawLabelRight(c: Canvas, text: String, right: Float, y: Float, paint: Paint) {
        drawLabel(c, text, right - paint.measureText(text), y, paint, backdrop.color)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private companion object {
        val POINTER_COLORS = intArrayOf(
            Color.rgb(255, 80, 80), Color.rgb(80, 220, 255), Color.rgb(120, 255, 120), Color.rgb(255, 200, 60),
            Color.rgb(230, 120, 255), Color.rgb(255, 140, 200), Color.rgb(160, 160, 255), Color.rgb(255, 255, 255),
        )
    }
}
