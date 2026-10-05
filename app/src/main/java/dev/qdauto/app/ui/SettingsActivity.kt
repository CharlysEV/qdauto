package dev.qdauto.app.ui

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.qdauto.app.R
import dev.qdauto.app.link.LinkRuntime
import dev.qdauto.app.log.AppLog
import dev.qdauto.app.p2p.P2pPermissions
import dev.qdauto.app.settings.BoolField
import dev.qdauto.app.settings.ChoiceField
import dev.qdauto.app.settings.ConnectionMode
import dev.qdauto.app.settings.Field
import dev.qdauto.app.settings.IntField
import dev.qdauto.app.settings.Settings
import dev.qdauto.app.settings.SettingsStore
import dev.qdauto.app.settings.TextField

/** Formulario generado a partir de [Settings.sections]: todo se cambia sin recompilar. */
class SettingsActivity : Activity() {
    private lateinit var store: SettingsStore
    private val rows = ArrayList<Row>()

    private interface Row {
        fun validate(): Boolean
        fun save(e: SharedPreferences.Editor)
        fun resetToDefault()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val prefs = store.prefs
        val pad = dp(12)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(24))
        }
        content.addView(text(getString(R.string.settings_title), R.style.QdTitle))
        for (section in Settings.sections) {
            content.addView(text(section.title, R.style.QdSection))
            section.note?.let { content.addView(text(it, R.style.QdHelp)) }
            for (field in section.fields) rows += addRow(content, field, prefs)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(R.string.save) { save() })
        buttons.addView(button(R.string.defaults) { rows.forEach(Row::resetToDefault) })
        buttons.addView(button(R.string.cancel) { finish() })
        content.addView(buttons)

        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        scroll.applySystemBarInsets()
    }

    private fun save() {
        val valid = rows.map { it.validate() }.all { it }
        if (!valid) {
            Toast.makeText(this, "Revisa los valores marcados", Toast.LENGTH_LONG).show()
            return
        }
        val editor = store.prefs.edit()
        rows.forEach { it.save(editor) }
        editor.apply()
        val settings = store.load()
        AppLog.i(TAG, "ajustes guardados desde la pantalla de ajustes")
        LinkRuntime.engine?.updateSettings(settings)
        Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
        // Al elegir Wi-Fi Direct se pide su permiso (spec 05 §7.2); la pantalla se cierra con la respuesta.
        val missing = if (settings.connectionMode == ConnectionMode.WIFI_DIRECT) P2pPermissions.missing(this) else emptyList()
        if (missing.isEmpty()) finish() else requestPermissions(missing.toTypedArray(), REQ_P2P)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_P2P) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        AppLog.i(TAG, "permiso de Wi-Fi Direct desde ajustes: ${if (granted) "concedido" else "denegado"}")
        if (!granted) {
            Toast.makeText(
                this,
                "Sin ese permiso Android no deja buscar ni conectar por Wi-Fi Direct. Se puede conceder luego con «Permiso…».",
                Toast.LENGTH_LONG,
            ).show()
        }
        LinkRuntime.engine?.p2pRecheck()
        finish()
    }

    private fun addRow(parent: LinearLayout, field: Field<*>, prefs: SharedPreferences): Row = when (field) {
        is BoolField -> BoolRow(parent, field, prefs)
        is IntField -> IntRow(parent, field, prefs)
        is TextField -> TextRow(parent, field, prefs)
        is ChoiceField<*> -> ChoiceRow(parent, field, prefs)
    }

    private inner class BoolRow(parent: LinearLayout, private val f: BoolField, prefs: SharedPreferences) : Row {
        private val view = Switch(this@SettingsActivity).apply {
            text = f.label
            isChecked = f.read(prefs)
            setTextAppearance(R.style.QdBody)
            setPadding(0, dp(6), 0, dp(2))
        }

        init {
            parent.addView(view)
            help(parent, f.help)
        }

        override fun validate() = true
        override fun save(e: SharedPreferences.Editor) = f.put(e, view.isChecked)
        override fun resetToDefault() {
            view.isChecked = f.default
        }
    }

    private inner class IntRow(parent: LinearLayout, private val f: IntField, prefs: SharedPreferences) : Row {
        private val edit = EditText(this@SettingsActivity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or if (f.min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
            setText(f.read(prefs).toString())
            setSingleLine()
        }

        init {
            parent.addView(label(f.label))
            parent.addView(edit)
            help(parent, f.help)
        }

        override fun validate(): Boolean {
            val error = f.errorFor(edit.text.toString().trim().toIntOrNull())
            edit.error = error
            return error == null
        }

        override fun save(e: SharedPreferences.Editor) {
            edit.text.toString().trim().toIntOrNull()?.let { f.put(e, it) }
        }

        override fun resetToDefault() {
            edit.setText(f.default.toString())
            edit.error = null
        }
    }

    private inner class TextRow(parent: LinearLayout, private val f: TextField, prefs: SharedPreferences) : Row {
        private val edit = EditText(this@SettingsActivity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(f.read(prefs))
            setSingleLine()
        }

        init {
            parent.addView(label(f.label))
            parent.addView(edit)
            help(parent, f.help)
        }

        override fun validate(): Boolean {
            val ok = edit.text.length <= f.maxLength
            edit.error = if (ok) null else "Máximo ${f.maxLength} caracteres"
            return ok
        }

        override fun save(e: SharedPreferences.Editor) = f.put(e, edit.text.toString())
        override fun resetToDefault() {
            edit.setText(f.default)
            edit.error = null
        }
    }

    private inner class ChoiceRow(parent: LinearLayout, private val f: ChoiceField<*>, prefs: SharedPreferences) : Row {
        private val spinner = Spinner(this@SettingsActivity).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, f.labels()).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(f.indexIn(prefs))
        }

        init {
            parent.addView(label(f.label))
            parent.addView(spinner)
            help(parent, f.help)
        }

        override fun validate() = true
        override fun save(e: SharedPreferences.Editor) = f.putIndex(e, spinner.selectedItemPosition)
        override fun resetToDefault() {
            spinner.setSelection(f.defaultIndex())
        }
    }

    private fun label(value: String): View = text(value, R.style.QdBody).apply { setPadding(0, dp(8), 0, 0) }

    private fun help(parent: LinearLayout, value: String?) {
        if (value != null) parent.addView(text(value, R.style.QdHelp))
    }

    private fun text(value: String, style: Int) = TextView(this).apply {
        setTextAppearance(style)
        text = value
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(label)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "QD/Settings"
        const val REQ_P2P = 1
    }
}
