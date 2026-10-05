package dev.qdauto.app.link

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** Tamaño real de la pantalla del teléfono, como lo lee QDLink (`getRealSize`, spec §6.4). */
object ScreenInfo {
    class Size(val longSide: Int, val shortSide: Int, val description: String)

    fun read(context: Context): Size {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return Size(2340, 1080, "sin pantalla por defecto: 2340×1080")
        val p = Point()
        // Igual que QDLink (SC/service/ScreenCaptureService.java:516-523). Un servicio no tiene ventana para usar
        // WindowMetrics, y getRealSize sigue dando la resolución activa (FHD+/WQHD+ en Samsung).
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        val mode = display.mode
        return Size(
            maxOf(p.x, p.y), minOf(p.x, p.y),
            "getRealSize ${p.x}×${p.y}, modo ${mode.physicalWidth}×${mode.physicalHeight} @${mode.refreshRate} Hz",
        )
    }
}
