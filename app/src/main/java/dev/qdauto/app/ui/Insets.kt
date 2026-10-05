package dev.qdauto.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Con targetSdk 36 la app siempre va de borde a borde (ya no se puede desactivar): se añade como padding el espacio
 * de las barras del sistema, el recorte de la cámara y el teclado.
 */
fun View.applySystemBarInsets() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
        )
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}
