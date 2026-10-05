package dev.qdauto.app.ui

import android.app.Activity
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.qdauto.app.R
import dev.qdauto.app.p2p.P2pBlocker
import dev.qdauto.app.p2p.P2pStatus
import dev.qdauto.app.p2p.PeerView
import dev.qdauto.app.p2p.StoredCar

/** Sección «Wi-Fi Direct» de la pantalla principal: estado, avisos y permisos, dispositivos con «Conectar» y el grupo. */
internal class P2pPanel(private val activity: Activity, private val actions: Actions) {
    interface Actions {
        fun search()
        fun disconnect()
        fun connect(address: String)
        fun forget()
        fun fix(blocker: P2pBlocker)
    }

    private val section: View = activity.findViewById(R.id.p2p_section)
    private val warning: TextView = activity.findViewById(R.id.p2p_warning)
    private val state: TextView = activity.findViewById(R.id.p2p_state)
    private val btnSearch: Button = activity.findViewById(R.id.btn_p2p_search)
    private val btnDisconnect: Button = activity.findViewById(R.id.btn_p2p_disconnect)
    private val btnFix: Button = activity.findViewById(R.id.btn_p2p_fix)
    private val btnForget: Button = activity.findViewById(R.id.btn_p2p_forget)
    private val peers: LinearLayout = activity.findViewById(R.id.p2p_peers)
    private val hidden: TextView = activity.findViewById(R.id.p2p_hidden)
    private val groupText: TextView = activity.findViewById(R.id.p2p_group)

    private var fixFor: P2pBlocker? = null
    private var renderedKeys: List<String>? = null
    private val labels = HashMap<String, TextView>()
    private val buttons = ArrayList<Button>()

    init {
        btnSearch.setOnClickListener { actions.search() }
        btnDisconnect.setOnClickListener { actions.disconnect() }
        btnForget.setOnClickListener { actions.forget() }
        btnFix.setOnClickListener { fixFor?.let(actions::fix) }
    }

    /**
     * [p]: estado del controlador (`null` con el servicio parado). [localBlocker]: lo que la actividad puede comprobar
     * por sí misma (permisos y ubicación), para avisar antes de arrancar.
     */
    fun render(visible: Boolean, p: P2pStatus?, serviceRunning: Boolean, localBlocker: P2pBlocker?, stored: StoredCar?) {
        section.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val now = SystemClock.elapsedRealtime()
        val blocker = p?.blocker ?: localBlocker
        warning.show(blocker?.let { b -> "• ${b.message}" + P2pText.hint(b).let { if (it.isEmpty()) "" else "\n  $it" } } ?: "")
        warning.visibility = if (blocker == null) View.GONE else View.VISIBLE
        fixFor = blocker
        val fixLabel = when (blocker) {
            P2pBlocker.NO_NEARBY_PERMISSION, P2pBlocker.NO_LOCATION_PERMISSION -> R.string.p2p_fix_permission
            P2pBlocker.LOCATION_OFF -> R.string.p2p_fix_location
            P2pBlocker.WIFI_OFF -> R.string.p2p_fix_wifi
            P2pBlocker.HOTSPOT_ON -> R.string.p2p_fix_hotspot
            else -> null
        }
        btnFix.visibility = if (fixLabel == null) View.GONE else View.VISIBLE
        fixLabel?.let { btnFix.text = activity.getString(it) }
        btnSearch.isEnabled = p != null
        btnDisconnect.isEnabled = p != null
        state.show(
            when {
                p != null -> P2pText.state(p, now)
                serviceRunning -> "Arrancando Wi-Fi Direct…"
                else -> "Servicio detenido: pulsa «Iniciar» y el móvil buscará solo.\nÚltimo coche: ${P2pText.storedCar(stored)}"
            },
        )
        groupText.show(if (p == null) "" else P2pText.group(p.group))
        renderPeers(p)
    }

    private fun renderPeers(p: P2pStatus?) {
        val list = p?.peers.orEmpty()
        val keys = list.map { it.peer.address }
        if (keys != renderedKeys) {
            peers.removeAllViews()
            labels.clear()
            buttons.clear()
            if (list.isEmpty()) peers.addView(mono(activity.getString(R.string.p2p_no_peers)))
            for (v in list) addRow(v)
            renderedKeys = keys
        }
        for (v in list) labels[v.peer.address]?.show(P2pText.peerLine(v))
        val enabled = p != null
        buttons.forEach { it.isEnabled = enabled }
        // Los FAILED/UNAVAILABLE no se listan, como en QDLink; van al log.
        val hiddenCount = p?.hiddenPeers ?: 0
        hidden.visibility = if (hiddenCount > 0) View.VISIBLE else View.GONE
        hidden.show(if (hiddenCount > 0) "$hiddenCount más sin listar (fallidos o no disponibles; están en el log)" else "")
    }

    private fun addRow(v: PeerView) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = mono("").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val address = v.peer.address
        val button = Button(activity).apply {
            text = activity.getString(R.string.connect)
            isAllCaps = false
            setOnClickListener { actions.connect(address) }
        }
        row.addView(label)
        row.addView(button)
        peers.addView(row)
        labels[address] = label
        buttons += button
    }

    private fun mono(value: String) = TextView(activity).apply {
        setTextAppearance(R.style.QdMono)
        text = value
    }

    private fun TextView.show(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }
}
