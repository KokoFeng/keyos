package com.fengios.keyos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var sp: SharedPreferences
    private lateinit var col: LinearLayout

    private fun t(en: String, id: String) = Prefs.tr(this, en, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float = 14f) {
        col.addView(TextView(this).apply { text = s; textSize = size; setPadding(0, dp(14), 0, dp(4)) })
    }

    private fun button(s: String, f: () -> Unit) {
        col.addView(Button(this).apply { text = s; setOnClickListener { f() } })
    }

    private fun toggle(en: String, id: String, key: String, def: Boolean) {
        col.addView(Switch(this).apply {
            text = t(en, id)
            isChecked = sp.getBoolean(key, def)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> sp.edit().putBoolean(key, v).apply() }
        })
    }

    private fun seek(en: String, id: String, key: String, def: Int, min: Int, max: Int) {
        label(t(en, id))
        col.addView(SeekBar(this).apply {
            this.max = max - min
            progress = sp.getInt(key, def) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    sp.edit().putInt(key, p + min).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun spinner(en: String, id: String, items: List<String>, key: String, def: Int, after: () -> Unit = {}) {
        label(t(en, id))
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(sp.getInt(key, def).coerceIn(0, items.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, i: Long) {
                    if (pos != sp.getInt(key, def)) { sp.edit().putInt(key, pos).apply(); after() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sp = Prefs.sp(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        label("KeyOS", 28f)
        label("Feng iOS on Droid", 13f)
        button(t("1. Enable KeyOS", "1. Aktifkan KeyOS")) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button(t("2. Choose KeyOS as keyboard", "2. Pilih KeyOS sebagai keyboard")) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        col.addView(EditText(this).apply { hint = t("Try typing here", "Coba ketik di sini") })

        spinner("Theme", "Tema", Themes.all.map { it.name }, "theme", 0)
        seek("Glass clarity (translucent themes)", "Kejernihan kaca (tema transparan)", "glass", 50, 0, 100)
        seek("Keyboard height (%)", "Tinggi keyboard (%)", "height", 100, 80, 130)
        spinner("Bottom-left icon", "Ikon kiri bawah", listOf("Emoji", "Globe"), "leftIcon", 0)
        label(t("Languages (tap the globe to switch)", "Bahasa (ketuk globe untuk ganti)"))
        for (l in Langs.all) toggle(l.name, l.name, "lg_" + l.code, l.code == "en" || l.code == "id")
        toggle("Adaptive touch zones (like iPhone)", "Zona sentuh adaptif (seperti iPhone)", "zones", true)
        toggle("Key popup", "Popup tombol", "popup", true)
        toggle("Typing sound", "Suara ketik", "sound", true)
        toggle("Haptic feedback", "Getar", "haptic", true)
        toggle("iOS emoji font", "Font emoji iOS", "iosEmoji", true)
        toggle("Auto-capitalization", "Huruf besar otomatis", "autoCap", true)
        toggle("Suggestions & calculator", "Saran kata & kalkulator", "suggest", true)
        spinner("App language", "Bahasa aplikasi", listOf("English", "Indonesia"), "langIdx", 0) {
            sp.edit().putString("lang", if (sp.getInt("langIdx", 0) == 1) "id" else "en").apply()
            recreate()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
