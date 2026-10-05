package dev.qdauto.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import dev.qdauto.app.touch.TouchMapper
import dev.qdauto.app.touch.TouchMapping
import dev.qdauto.app.touch.TouchTracker
import kotlin.math.roundToInt

/**
 * El frame de vídeo a escala con los toques del coche y las hipótesis de coordenadas (spec §9.3):
 * ● la interpretación efectiva (con estela), □ px del teléfono en horizontal, △ normalizado 0-1.
 */
class TouchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var tracker: TouchTracker? = null

    private var frameW = 1920
    private var frameH = 1080
    private val density = resources.displayMetrics.density
    private val legendHeight = (36 * density).roundToInt()

    private val frameFill = Paint().apply { color = Color.rgb(24, 24, 30) }
    private val grid = Paint().apply { color = Color.rgb(70, 70, 80); strokeWidth = 1f }
    private val border = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hollow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * density
        typeface = Typeface.MONOSPACE
    }
    private val path = Path()

    /** Tamaño del frame que se representa (el del encoder). */
    fun setFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == frameW && height == frameH)) return
        frameW = width
        frameH = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w.toFloat() * frameH / frameW).roundToInt() + legendHeight
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = w * frameH / frameW
        val scale = w / frameW
        canvas.drawRect(0f, 0f, w, h, frameFill)
        for (k in 1..9) {
            canvas.drawLine(w * k / 10, 0f, w * k / 10, h, grid)
            canvas.drawLine(0f, h * k / 10, w, h * k / 10, grid)
        }
        canvas.drawRect(1f, 1f, w - 1, h - 1, border)
        text.color = Color.LTGRAY
        canvas.drawText("0,0", 4 * density, 14 * density, text)
        val corner = "$frameW×$frameH"
        canvas.drawText(corner, w - text.measureText(corner) - 4 * density, h - 4 * density, text)

        val t = tracker
        if (t == null) {
            text.color = Color.WHITE
            canvas.drawText("Servicio detenido", 8 * density, h / 2, text)
            drawLegend(canvas, h, null)
            return
        }
        val snap = t.snapshot()
        val space = snap.space.copy(frameW = frameW, frameH = frameH)
        val mapping = snap.resolved
        for (p in snap.pointers) {
            val color = COLORS[Math.floorMod(p.id, COLORS.size)]
            val alpha = if (p.down) 255 else (255 * (1f - p.releasedAgoMs / TouchTracker.FADE_MS.toFloat())).toInt().coerceIn(50, 255)
            line.color = color
            line.alpha = alpha / 2
            val tr = p.trail
            var i = 2
            while (i + 1 < tr.size) {
                val a = TouchMapper.toFrame(tr[i - 2], tr[i - 1], mapping, space)
                val b = TouchMapper.toFrame(tr[i], tr[i + 1], mapping, space)
                canvas.drawLine(a.x * scale, a.y * scale, b.x * scale, b.y * scale, line)
                i += 2
            }
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            // Interpretación efectiva.
            val m = TouchMapper.toFrame(p.x, p.y, mapping, space)
            val mx = (m.x * scale).coerceIn(0f, w)
            val my = (m.y * scale).coerceIn(0f, h)
            solid.color = color
            solid.alpha = alpha
            canvas.drawCircle(mx, my, 7 * density, solid)
            line.alpha = alpha
            canvas.drawLine(0f, my, w, my, line)
            canvas.drawLine(mx, 0f, mx, h, line)
            // Hipótesis B: px del teléfono en horizontal.
            if (mapping != TouchMapping.PHONE_PX) {
                val b = TouchMapper.toFrame(p.x, p.y, TouchMapping.PHONE_PX, space)
                hollow.color = color
                hollow.alpha = alpha
                val bx = (b.x * scale).coerceIn(0f, w)
                val by = (b.y * scale).coerceIn(0f, h)
                canvas.drawRect(bx - 6 * density, by - 6 * density, bx + 6 * density, by + 6 * density, hollow)
            }
            // Normalizado, solo si cabe en [0, 1].
            if (mapping != TouchMapping.NORMALIZED && p.x in 0f..1f && p.y in 0f..1f) {
                val n = TouchMapper.toFrame(p.x, p.y, TouchMapping.NORMALIZED, space)
                drawTriangle(canvas, n.x * scale, n.y * scale, color, alpha)
            }
            text.color = color
            text.alpha = alpha
            val label = "id=${p.id} ${p.actionName} ${p.x},${p.y}"
            val lx = if (mx + 10 * density + text.measureText(label) < w) mx + 10 * density else maxOf(0f, mx - 10 * density - text.measureText(label))
            val ly = if (my > 20 * density) my - 8 * density else my + 18 * density
            canvas.drawText(label, lx, ly, text)
        }
        drawLegend(canvas, h, mapping)
        if (snap.lastEventAgoMs < 2_000 || snap.pointers.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawLegend(canvas: Canvas, top: Float, mapping: TouchMapping?) {
        text.color = Color.LTGRAY
        text.alpha = 255
        canvas.drawText("● ${mapping?.label ?: "—"}", 4 * density, top + 15 * density, text)
        canvas.drawText("□ px del teléfono   △ normalizado 0-1", 4 * density, top + 30 * density, text)
    }

    private fun drawTriangle(canvas: Canvas, x: Float, y: Float, color: Int, alpha: Int) {
        val r = 7 * density
        path.reset()
        path.moveTo(x, y - r)
        path.lineTo(x + r, y + r)
        path.lineTo(x - r, y + r)
        path.close()
        hollow.color = color
        hollow.alpha = alpha
        canvas.drawPath(path, hollow)
    }

    private companion object {
        val COLORS = intArrayOf(
            Color.rgb(255, 80, 80), Color.rgb(80, 220, 255), Color.rgb(120, 255, 120), Color.rgb(255, 200, 60),
            Color.rgb(230, 120, 255), Color.rgb(255, 140, 200), Color.rgb(160, 160, 255), Color.WHITE,
        )
    }
}
