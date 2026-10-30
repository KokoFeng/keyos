package com.fengios.keyos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.widget.FrameLayout

class KeyOSService : InputMethodService() {

    companion object { var instance: KeyOSService? = null }

    private lateinit var root: FrameLayout
    private lateinit var kv: KeyboardView
    private lateinit var ev: EmojiView
    private lateinit var prefs: SharedPreferences
    private val predictor = Predictor()
    private var fx: SoundFx? = null
    private val ui = Handler(Looper.getMainLooper())
    private var pending: String? = null
    private var lastSpace = 0L
    private var plain = false
    private var curWord = ""
    private var calc: String? = null
    private var sugg: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs.sp(this)
        fx = SoundFx(this)
        Thread {
            predictor.load(applicationContext)
            ui.post { if (::kv.isInitialized) afterEdit() }
        }.start()
    }

    override fun onDestroy() {
        instance = null
        fx?.release()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        kv = KeyboardView(this, this)
        ev = EmojiView(this, this)
        ev.visibility = View.GONE
        root = FrameLayout(this)
        root.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(ev, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kv.applyPrefs()
        ev.applyPrefs()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        plain = cls != InputType.TYPE_CLASS_TEXT || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
        kv.applyPrefs()
        ev.applyPrefs()
        showKeyboard()
        kv.setLayer(if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0)
        val act = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val multi = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnter = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        var label = "return"
        var blue = false
        if (!multi && !noEnter) {
            when (act) {
                EditorInfo.IME_ACTION_GO -> { label = "go"; blue = true }
                EditorInfo.IME_ACTION_SEARCH -> { label = "search"; blue = true }
                EditorInfo.IME_ACTION_SEND -> { label = "send"; blue = true }
                EditorInfo.IME_ACTION_NEXT -> label = "next"
                EditorInfo.IME_ACTION_DONE -> label = "done"
            }
        }
        kv.setAction(label, blue)
        pending?.let { currentInputConnection?.commitText(it, 1); pending = null }
        afterEdit()
    }

    // ---------- umpan balik ----------
    fun haptic() {
        if (::kv.isInitialized && prefs.getBoolean("haptic", true))
            kv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun keyFeedback(t: KT) {
        if (!prefs.getBoolean("sound", true)) return
        val name = when (t) {
            KT.SPACE -> "spacebar"
            KT.DELETE -> "delete"
            KT.RETURN -> "ret"
            else -> "standard"
        }
        if (fx?.play(name) != true) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }

    // ---------- aksi ketik ----------
    fun typeText(s: String) {
        currentInputConnection?.commitText(s, 1)
        afterEdit()
    }

    fun deleteKey() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    fun moveCursor(dir: Int) {
        sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        afterEdit()
    }

    fun space() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (now - lastSpace < 450 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)  // spasi dua kali -> titik
            lastSpace = 0
        } else {
            ic.commitText(" ", 1)
            lastSpace = now
        }
        afterEdit()
    }

    fun enter() {
        val info = currentInputEditorInfo
        val act = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnter = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multi = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!noEnter && !multi && act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendDefaultEditorAction(true)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        afterEdit()
    }

    // ---------- panel ----------
    fun showEmoji() { kv.visibility = View.INVISIBLE; ev.visibility = View.VISIBLE; ev.applyPrefs() }
    fun showKeyboard() { ev.visibility = View.GONE; kv.visibility = View.VISIBLE }

    fun showPicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    fun startVoice() {
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun commitExternal(t: String) {
        val ic = currentInputConnection
        if (ic != null) { ic.commitText("$t ", 1); afterEdit() } else pending = "$t "
    }

    // ---------- saran, auto-cap, kalkulator ----------
    private fun styleCase(s: String?, ref: String): String {
        if (s == null) return ""
        if (s == "i") return "I"
        if (ref.length > 1 && ref.all { it.isUpperCase() }) return s.uppercase()
        val cap = if (ref.isNotEmpty()) ref[0].isUpperCase() else kv.shift != 0
        return if (cap) s.replaceFirstChar { it.uppercase() } else s
    }

    fun afterEdit() {
        if (!::kv.isInitialized) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (prefs.getBoolean("autoCap", true) && info != null) {
            kv.autoCap(ic.getCursorCapsMode(info.inputType) != 0)
        }
        if (plain || !prefs.getBoolean("suggest", true) || !kv.hasDict()) {
            curWord = ""; calc = null; sugg = emptyList()
            kv.setSuggestions(sugg); kv.setProbs(null)
            return
        }
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""
        curWord = Regex("[\\p{L}']+$").find(before)?.value ?: ""
        val pre = curWord.lowercase()
        predictor.update(pre)
        kv.setProbs(predictor.probs)
        calc = null
        val cm = Regex("([0-9.()+\\-*/x×÷%]+)=$").find(before)
        if (cm != null) {
            val v = Calc.eval(cm.groupValues[1])
            if (v != null) calc = Calc.fmt(v)
        }
        val list = ArrayList<String>()
        val cv = calc
        if (cv != null) {
            list.add(""); list.add(cv); list.add("")
        } else {
            val top = predictor.suggest(pre, 3)
            if (curWord.isNotEmpty()) {
                list.add("\"$curWord\"")
                list.add(styleCase(top.getOrNull(0), curWord))
                list.add(styleCase(top.getOrNull(1), curWord))
            } else {
                for (i in 0..2) list.add(styleCase(top.getOrNull(i), ""))
            }
        }
        sugg = list
        kv.setSuggestions(list)
    }

    fun pickSuggestion(i: Int) {
        val ic = currentInputConnection ?: return
        val s = sugg.getOrNull(i) ?: return
        if (s.isEmpty()) return
        if (calc != null) {
            if (i == 1) ic.commitText(s, 1)
        } else if (i == 0 && curWord.isNotEmpty()) {
            ic.commitText(" ", 1)  // pertahankan ketikan asli
        } else {
            ic.deleteSurroundingText(curWord.length, 0)
            ic.commitText("$s ", 1)
        }
        afterEdit()
    }
}
