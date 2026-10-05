package dev.qdauto.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import dev.qdauto.app.AppInfo
import dev.qdauto.app.R
import dev.qdauto.app.link.LinkRuntime
import dev.qdauto.app.link.LinkStatus
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.log.LogExport
import dev.qdauto.app.p2p.P2pBlocker
import dev.qdauto.app.p2p.P2pCarStore
import dev.qdauto.app.p2p.P2pPermissions
import dev.qdauto.app.service.LinkService
import dev.qdauto.app.settings.AppSettings
import dev.qdauto.app.settings.ConnectionMode
import dev.qdauto.app.settings.SettingsStore
import dev.qdauto.app.util.Clock

/**
 * Pantalla única de la prueba: servicio, red, Wi-Fi Direct, coches, sesión, vídeo, táctil, mensajes, autoprueba y log.
 * Se refresca leyendo el estado del motor cada [REFRESH_MS] en el hilo principal; mantiene la pantalla encendida
 * mientras se ve.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var p2pPanel: P2pPanel
    private val p2pStore by lazy { P2pCarStore(this) }

    private lateinit var serviceState: TextView
    private lateinit var warnings: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var switchAuto: Switch
    private lateinit var networkText: TextView
    private lateinit var carsContainer: LinearLayout
    private lateinit var sessionText: TextView
    private lateinit var videoText: TextView
    private lateinit var touchView: TouchView
    private lateinit var touchText: TextView
    private lateinit var messagesText: TextView
    private lateinit var btnSelfTest: Button
    private lateinit var selfTestText: TextView
    private lateinit var btnExport: Button
    private lateinit var logPath: TextView
    private lateinit var logText: TextView

    private var updatingSwitch = false
    private var updatingMode = false

    /** Android ya no muestra el diálogo del permiso de Wi-Fi Direct: hay que ir a los ajustes de la app. */
    private var p2pPermissionDeniedForever = false
    private var qdlinkInstalled = false
    private var renderedCarKeys: List<String>? = null
    private val carLabels = HashMap<String, TextView>()
    private var lastLogSeq = 0L
    private val logLines = ArrayDeque<String>()
    private var exportNote: String? = null

    private val refresher = object : Runnable {
        override fun run() {
            try {
                render()
            } catch (e: Exception) {
                AppLog.e(TAG, "error refrescando la pantalla", e)
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.scroll).applySystemBarInsets()
        store = SettingsStore(this)

        serviceState = findViewById(R.id.service_state)
        warnings = findViewById(R.id.warnings)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        modeGroup = findViewById(R.id.mode_group)
        switchAuto = findViewById(R.id.switch_auto)
        networkText = findViewById(R.id.network_text)
        carsContainer = findViewById(R.id.cars_container)
        sessionText = findViewById(R.id.session_text)
        videoText = findViewById(R.id.video_text)
        touchView = findViewById(R.id.touch_view)
        touchText = findViewById(R.id.touch_text)
        messagesText = findViewById(R.id.messages_text)
        btnSelfTest = findViewById(R.id.btn_selftest)
        selfTestText = findViewById(R.id.selftest_text)
        btnExport = findViewById(R.id.btn_export)
        logPath = findViewById(R.id.log_path)
        logText = findViewById(R.id.log_text)

        btnStart.setOnClickListener { requestStart() }
        btnStop.setOnClickListener { stopLinkService() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btn_qdlink).setOnClickListener { openQdlinkSettings() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { openBatterySettings() }
        modeGroup.setOnCheckedChangeListener { _, id ->
            if (!updatingMode) onModeSelected(if (id == R.id.mode_p2p) ConnectionMode.WIFI_DIRECT else ConnectionMode.HOTSPOT)
        }
        // En modo Wi-Fi Direct el interruptor es el de la conexión automática al último coche.
        switchAuto.setOnCheckedChangeListener { _, checked ->
            if (!updatingSwitch) {
                val p2p = store.load().connectionMode == ConnectionMode.WIFI_DIRECT
                if (p2p) store.setP2pAutoConnect(checked) else store.setAutoConnect(checked)
                LinkRuntime.engine?.updateSettings(store.load())
                AppLog.i(TAG, "conexión automática${if (p2p) " (Wi-Fi Direct)" else ""}: $checked")
            }
        }
        p2pPanel = P2pPanel(this, P2pActions())
        findViewById<Button>(R.id.btn_disconnect).setOnClickListener { withEngine { it.disconnect() } }
        findViewById<Button>(R.id.btn_idr).setOnClickListener { withEngine { it.requestKeyframe() } }
        findViewById<Button>(R.id.btn_phone_info).setOnClickListener { withEngine { it.resendPhoneInfo() } }
        findViewById<Button>(R.id.btn_unlocked).setOnClickListener { withEngine { it.sendUnlocked() } }
        findViewById<Button>(R.id.btn_background).setOnClickListener { withEngine { it.sendCarAppBackground() } }
        btnSelfTest.setOnClickListener {
            withEngine { engine -> if (!LinkRuntime.selfTest.start(engine)) toast("La autoprueba ya está en marcha") }
        }
        btnExport.setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            logLines.clear()
            logText.text = ""
        }

        if (savedInstanceState == null && LinkRuntime.engine == null && store.load().autoStartService) requestStart()
    }

    override fun onStart() {
        super.onStart()
        qdlinkInstalled = AppInfo.isInstalled(this, QDLINK_PACKAGE)
        // Al volver de los ajustes del sistema (permiso, Wi-Fi, ubicación, zona Wi-Fi).
        LinkRuntime.engine?.p2pRecheck()
        handler.post(refresher)
    }

    override fun onStop() {
        handler.removeCallbacks(refresher)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            AppLog.i(TAG, "permiso ${p.substringAfterLast('.')}: ${if (granted) "concedido" else "denegado"}")
        }
        val essential = P2pPermissions.essential(Build.VERSION.SDK_INT)
        val index = permissions.indexOf(essential)
        if (index >= 0) onP2pPermissionResult(grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED, essential)
        // El servicio arranca igual: sin notificación no se ve, y sin el permiso P2P lo explica la sección Wi-Fi Direct.
        if (requestCode == REQ_START) startLinkService()
    }

    // ===================================================================== acciones

    private fun requestStart() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        // Solo al arrancar en modo Wi-Fi Direct (spec 05 §7.2): en modo punto de acceso no hace falta.
        if (store.load().connectionMode == ConnectionMode.WIFI_DIRECT && !p2pPermissionDeniedForever) {
            needed += P2pPermissions.missing(this)
        }
        if (needed.isEmpty()) startLinkService() else requestPermissions(needed.toTypedArray(), REQ_START)
    }

    private fun onModeSelected(mode: ConnectionMode) {
        store.setConnectionMode(mode)
        LinkRuntime.engine?.updateSettings(store.load())
        AppLog.i(TAG, "modo de conexión: ${mode.label}")
        if (mode == ConnectionMode.WIFI_DIRECT) requestP2pPermission()
    }

    /** «Dispositivos cercanos» (Android 13+) o ubicación precisa (10-12); si Android ya no pregunta, ajustes de la app. */
    private fun requestP2pPermission() {
        val missing = P2pPermissions.missing(this)
        when {
            missing.isEmpty() -> LinkRuntime.engine?.p2pRecheck()
            p2pPermissionDeniedForever -> {
                openSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
                    "Permisos ▸ ${p2pPermissionName()} ▸ Permitir",
                )
            }
            else -> requestPermissions(missing.toTypedArray(), REQ_P2P)
        }
    }

    private fun onP2pPermissionResult(granted: Boolean, permission: String) {
        if (granted) {
            p2pPermissionDeniedForever = false
            LinkRuntime.engine?.p2pRecheck()
            return
        }
        // Tras dos negativas Android deja de mostrar el diálogo: entonces solo queda ir a los ajustes de la app.
        p2pPermissionDeniedForever = !shouldShowRequestPermissionRationale(permission)
        toast(
            "Sin «${p2pPermissionName()}» no se puede usar Wi-Fi Direct: Android no deja buscar ni conectar con el coche. " +
                if (p2pPermissionDeniedForever) "Pulsa «Permiso…» para abrir los ajustes de la app." else "Pulsa «Permiso…» para volver a pedirlo.",
        )
    }

    private fun p2pPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Dispositivos cercanos" else "Ubicación precisa"

    private fun fixP2p(blocker: P2pBlocker) {
        when (blocker) {
            P2pBlocker.NO_NEARBY_PERMISSION, P2pBlocker.NO_LOCATION_PERMISSION -> requestP2pPermission()
            P2pBlocker.LOCATION_OFF -> openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Activa la ubicación")
            P2pBlocker.WIFI_OFF -> openSettings(Intent(Settings.Panel.ACTION_WIFI), "Activa la Wi-Fi")
            P2pBlocker.HOTSPOT_ON -> openSettings(
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                "Apaga «Zona Wi-Fi» (Conexiones ▸ Zona Wi-Fi y anclaje a red)",
            )
            P2pBlocker.UNSUPPORTED, P2pBlocker.CHANNEL_LOST -> Unit
        }
    }

    private fun openSettings(intent: Intent, hint: String) {
        try {
            startActivity(intent)
            toast(hint)
        } catch (_: ActivityNotFoundException) {
            toast("No se pudo abrir el ajuste del sistema. $hint")
        }
    }

    private inner class P2pActions : P2pPanel.Actions {
        override fun search() = withEngine { it.p2pSearch() }
        override fun disconnect() = withEngine { it.p2pDisconnect() }
        override fun connect(address: String) = withEngine { it.p2pConnect(address) }
        override fun forget() {
            val engine = LinkRuntime.engine
            if (engine != null) engine.p2pForgetCar() else p2pStore.clear()
            toast("Último coche olvidado")
        }

        override fun fix(blocker: P2pBlocker) = fixP2p(blocker)
    }

    private fun startLinkService() {
        try {
            LinkService.start(this)
            AppLog.i(TAG, "servicio pedido desde la pantalla")
        } catch (e: Exception) {
            AppLog.e(TAG, "no se pudo arrancar el servicio", e)
            LinkRuntime.serviceError = "No se pudo arrancar el servicio: $e"
        }
    }

    private fun stopLinkService() {
        try {
            LinkService.stop(this)
        } catch (e: Exception) {
            AppLog.e(TAG, "no se pudo parar el servicio", e)
        }
    }

    private fun withEngine(action: (dev.qdauto.app.link.LinkEngine) -> Unit) {
        val engine = LinkRuntime.engine
        if (engine == null) toast("El servicio está detenido") else action(engine)
    }

    private fun openQdlinkSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$QDLINK_PACKAGE".toUri())
        try {
            startActivity(intent)
            toast("Pulsa \"Forzar detención\" para que QDLink no conteste al coche a la vez")
        } catch (_: ActivityNotFoundException) {
            toast("No se pudo abrir la información de QDLink")
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            toast("Pon QDAuto Test en \"Sin restricciones\"")
        } catch (_: ActivityNotFoundException) {
            toast("No se pudo abrir el ajuste de batería")
        }
    }

    private fun exportLog() {
        btnExport.isEnabled = false
        val summary = exportSummary()
        Thread({
            val result = try {
                Result.success(LogExport.export(applicationContext, summary))
            } catch (e: Exception) {
                AppLog.e(TAG, "no se pudo exportar el log", e)
                Result.failure(e)
            }
            handler.post {
                btnExport.isEnabled = true
                result.onSuccess { exported ->
                    exportNote = "Exportado: Descargas/${LogExport.RELATIVE_DIR}/${exported.displayName} " +
                        "(${StatusText.bytes(exported.bytes)}, ${exported.files} ficheros de log)"
                    if (!isFinishing && !isDestroyed) {
                        try {
                            startActivity(LogExport.shareIntent(exported))
                        } catch (_: ActivityNotFoundException) {
                            toast("No hay ninguna app para compartir; el fichero está en Descargas")
                        }
                    }
                }
                result.onFailure { e -> toast("No se pudo exportar: ${e.message}") }
            }
        }, "qd-app-export").start()
    }

    private fun exportSummary(): String {
        val engine = LinkRuntime.engine
        val status = engine?.snapshot()
        return buildString {
            appendLine("QDAuto Test · log exportado el ${Clock.dateTime()}")
            appendLine(AppInfo.describe(this@MainActivity))
            appendLine("Optimización de batería: ${if (batteryUnrestricted()) "sin restricciones" else "optimizada"}")
            appendLine("QDLink instalado: ${if (AppInfo.isInstalled(this@MainActivity, QDLINK_PACKAGE)) "sí" else "no"}")
            appendLine()
            appendLine("== Ajustes ==")
            appendLine(store.dump())
            appendLine()
            appendLine(if (status != null) StatusText.full(status, engine.touches.snapshot()) else "Servicio detenido")
            appendLine()
            appendLine("== Autoprueba ==")
            appendLine(LinkRuntime.selfTest.status)
            appendLine()
            append("Ficheros de log en ${AppLog.directory()}")
        }
    }

    // ===================================================================== pintado

    private fun render() {
        val engine = LinkRuntime.engine
        val status: LinkStatus? = engine?.let {
            try {
                it.snapshot()
            } catch (e: Exception) {
                AppLog.w(TAG, "no se pudo leer el estado", e)
                null
            }
        }
        serviceState.show(
            if (engine == null) "Servicio: detenido" else "Servicio: en marcha · ${status?.let(StatusText::linkTitle) ?: "?"}",
        )
        btnStart.isEnabled = engine == null
        btnStop.isEnabled = engine != null
        warnings.show(warningsText(status))

        val settings = store.load()
        renderMode(settings)
        val p2pVisible = settings.connectionMode == ConnectionMode.WIFI_DIRECT
        p2pPanel.render(
            visible = p2pVisible,
            p = status?.p2p,
            serviceRunning = engine != null,
            localBlocker = if (p2pVisible) localP2pBlocker() else null,
            stored = if (p2pVisible && status?.p2p == null) p2pStore.load() else null,
        )

        networkText.show(status?.let(StatusText::network) ?: "Pulsa Iniciar para escuchar el broadcast del coche (UDP 18463).")
        renderCars(status)
        sessionText.show(status?.let(StatusText::session) ?: "—")
        videoText.show(status?.let(StatusText::video) ?: "—")

        val tracker = engine?.touches
        touchView.tracker = tracker
        val frame = status?.video?.size
        if (frame != null) {
            touchView.setFrameSize(frame.width, frame.height)
        } else {
            status?.session?.videoParams?.let { touchView.setFrameSize(it.width, it.height) }
        }
        touchView.invalidate()
        touchText.show(tracker?.snapshot()?.let(StatusText::touch) ?: "—")
        messagesText.show(status?.let(StatusText::messages) ?: "—")

        btnSelfTest.isEnabled = engine != null && !LinkRuntime.selfTest.isRunning
        selfTestText.show(LinkRuntime.selfTest.status)

        logPath.show(
            (exportNote?.let { "$it\n" } ?: "") +
                "Log de la sesión: ${AppLog.currentFile()?.path ?: "—"} (adb pull de la carpeta, o Exportar log)",
        )
        renderLog()
    }

    /** Modo e interruptor de la conexión automática (en Wi-Fi Direct, la del último coche). */
    private fun renderMode(settings: AppSettings) {
        val p2p = settings.connectionMode == ConnectionMode.WIFI_DIRECT
        val modeId = if (p2p) R.id.mode_p2p else R.id.mode_hotspot
        if (modeGroup.checkedRadioButtonId != modeId) {
            updatingMode = true
            modeGroup.check(modeId)
            updatingMode = false
        }
        updatingSwitch = true
        switchAuto.text = getString(if (p2p) R.string.auto_connect_p2p else R.string.auto_connect)
        switchAuto.isChecked = if (p2p) settings.p2p.autoConnect else settings.discovery.autoConnect
        updatingSwitch = false
    }

    /** Lo que se puede comprobar sin el servicio: el permiso y, en Android 10-12, la ubicación. */
    private fun localP2pBlocker(): P2pBlocker? = when {
        !P2pPermissions.granted(this) ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) P2pBlocker.NO_NEARBY_PERMISSION else P2pBlocker.NO_LOCATION_PERMISSION
        P2pPermissions.locationServicesNeeded(Build.VERSION.SDK_INT) && !P2pPermissions.locationEnabled(this) -> P2pBlocker.LOCATION_OFF
        else -> null
    }

    private fun renderCars(status: LinkStatus?) {
        val cars = status?.cars.orEmpty().sortedBy { it.key }
        val keys = cars.map { it.key }
        if (keys != renderedCarKeys) {
            carsContainer.removeAllViews()
            carLabels.clear()
            if (cars.isEmpty()) {
                carsContainer.addView(TextView(this).apply {
                    setTextAppearance(R.style.QdMono)
                    text = getString(R.string.no_cars)
                })
            }
            for (c in cars) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(this).apply {
                    setTextAppearance(R.style.QdMono)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val button = Button(this).apply {
                    text = getString(R.string.connect)
                    isAllCaps = false
                    setOnClickListener { withEngine { it.connect(c.key) } }
                }
                row.addView(label)
                row.addView(button)
                carsContainer.addView(row)
                carLabels[c.key] = label
            }
            renderedCarKeys = keys
        }
        for (c in cars) {
            carLabels[c.key]?.show(
                StatusText.carLine(c.name, c.uuid, c.host, c.sourcePort, c.lastSeenMillis, c.count) +
                    (if (c.qdlinkCompatible) "" else " · QDLink no lo aceptaría"),
            )
        }
    }

    private fun renderLog() {
        val fresh = AppLog.linesAfter(lastLogSeq, LOG_LINES)
        if (fresh.isEmpty()) return
        lastLogSeq = fresh.last().seq
        fresh.forEach { logLines.addLast(it.text) }
        while (logLines.size > LOG_LINES) logLines.removeFirst()
        // Lo más reciente arriba.
        logText.text = logLines.reversed().joinToString("\n")
    }

    private fun warningsText(status: LinkStatus?): String = buildString {
        if (qdlinkInstalled) {
            appendLine("• QDLink está instalado: ciérralo con \"Forzar detención\" antes de probar; si sigue abierto, también contesta al coche.")
        }
        if (status?.bindWifiIgnored == true) {
            appendLine("• «Coche como punto de acceso» está activado, pero en modo Wi-Fi Direct se ignora (no se vincula la Wi-Fi).")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLine("• Sin permiso de notificaciones: el servicio funciona, pero su notificación no se ve.")
        }
        if (!batteryUnrestricted()) {
            appendLine("• Batería optimizada: mejor \"Sin restricciones\" para que nada se pare con la pantalla apagada.")
        }
        LinkRuntime.serviceError?.let { appendLine("• $it") }
        LinkRuntime.internalError?.let { appendLine("• Error interno: $it") }
    }.trimEnd()

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun TextView.show(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "QD/UI"
        const val REFRESH_MS = 400L
        const val LOG_LINES = 200
        const val REQ_START = 1
        const val REQ_P2P = 2
        const val QDLINK_PACKAGE = "com.neusoft.qdrivelink"
    }
}
