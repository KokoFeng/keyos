package com.fengios.keyos

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.SoundPool
import java.math.BigDecimal
import java.math.RoundingMode

object Prefs {
    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("keyos", Context.MODE_PRIVATE)
    fun tr(c: Context, en: String, id: String): String =
        if (sp(c).getString("lang", "en") == "id") id else en
}

fun withAlpha(col: Int, a: Int): Int = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))

class KTheme(
    val name: String, val bg: Int, val key: Int, val keyDown: Int, val special: Int,
    val text: Int, val popup: Int, val popupText: Int, val accent: Int, val glass: Boolean
)

object Themes {
    val all: List<KTheme> = listOf(
        KTheme("Liquid Glass", 0x8C1C1C26.toInt(), 0x44FFFFFF.toInt(), 0x77FFFFFF.toInt(), 0x26FFFFFF.toInt(),
            0xFFFFFFFF.toInt(), 0xF0404050.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), true),
        KTheme("Transparent Blur", 0xB3202028.toInt(), 0x38FFFFFF.toInt(), 0x66FFFFFF.toInt(), 0x22FFFFFF.toInt(),
            0xFFFFFFFF.toInt(), 0xF0363640.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), false),
        KTheme("Dark", 0xFF1E1E20.toInt(), 0xFF5A5A5E.toInt(), 0xFF8A8A8E.toInt(), 0xFF38383B.toInt(),
            0xFFFFFFFF.toInt(), 0xFF5A5A5E.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), false),
        KTheme("Light", 0xFFD2D5DB.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8BCC4.toInt(), 0xFFADB2BB.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF007AFF.toInt(), false),
        KTheme("White", 0xFFFFFFFF.toInt(), 0xFFF2F2F7.toInt(), 0xFFD1D1D6.toInt(), 0xFFE5E5EA.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF007AFF.toInt(), false),
        KTheme("Black", 0xFF000000.toInt(), 0xFF2C2C2E.toInt(), 0xFF48484A.toInt(), 0xFF1C1C1E.toInt(),
            0xFFFFFFFF.toInt(), 0xFF3A3A3C.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), false),
        KTheme("Blue", 0xFF0B3C91.toInt(), 0xFF2F6FE0.toInt(), 0xFF6FA0F5.toInt(), 0xFF1B4DA8.toInt(),
            0xFFFFFFFF.toInt(), 0xFF2F6FE0.toInt(), 0xFFFFFFFF.toInt(), 0xFF64D2FF.toInt(), false),
        KTheme("Pink", 0xFFF6C4D8.toInt(), 0xFFFFE6F0.toInt(), 0xFFF1A8C6.toInt(), 0xFFEE9DBD.toInt(),
            0xFF4A1830.toInt(), 0xFFFFE6F0.toInt(), 0xFF4A1830.toInt(), 0xFFFF2D78.toInt(), false)
    )
}

/** Kalkulator di bar saran: "12*3=" -> 36 */
object Calc {
    fun eval(src: String): Double? {
        val s = src.replace("x", "*").replace("×", "*").replace("÷", "/")
        if (!s.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '%' }) return null
        return try {
            val p = P(s)
            val v = p.expr()
            if (p.i == s.length && !v.isNaN() && !v.isInfinite()) v else null
        } catch (e: Exception) { null }
    }

    fun fmt(v: Double): String =
        BigDecimal(v).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private class P(val s: String) {
        var i = 0
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        fun factor(): Double {
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalStateException()
                i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalStateException()
            var v = s.substring(st, i).toDouble()
            if (i < s.length && s[i] == '%') { i++; v /= 100.0 }
            return v
        }
    }
}

/**
 * Otak "zona sentuh adaptif" ala iPhone (Paten US 8.232.973: hit region tiap tombol
 * berubah ukuran mengikuti urutan huruf yang sudah diketik).
 * Kita hitung peluang huruf berikutnya dari kamus, lalu KeyboardView memakainya.
 */
class Predictor {
    private var words: Array<String> = emptyArray()
    private var weights: FloatArray = FloatArray(0)
    @Volatile var ready = false
    val probs = FloatArray(26) { 1f / 26f }

    fun load(ctx: Context) {
        val map = HashMap<String, Float>()
        for (f in listOf("words_en.txt", "words_id.txt")) {
            try {
                ctx.assets.open(f).bufferedReader().useLines { seq ->
                    var i = 0
                    for (line in seq) {
                        val t = line.trim().lowercase()
                        if (t.isNotEmpty()) {
                            map[t] = (map[t] ?: 0f) + 1f / (i + 30f)
                            i++
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        val sorted = map.entries.sortedBy { it.key }
        val w = Array(sorted.size) { sorted[it].key }
        val wt = FloatArray(sorted.size) { sorted[it].value }
        words = w
        weights = wt
        ready = true
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Hitung distribusi huruf berikutnya untuk awalan [prefix]. */
    fun update(prefix: String) {
        java.util.Arrays.fill(probs, 0f)
        var total = 0f
        if (ready) {
            var i = lowerBound(prefix)
            while (i < words.size && words[i].startsWith(prefix)) {
                val w = words[i]
                if (w.length > prefix.length) {
                    val ch = w[prefix.length]
                    if (ch in 'a'..'z') { probs[ch - 'a'] += weights[i]; total += weights[i] }
                }
                i++
            }
        }
        if (total <= 0f) java.util.Arrays.fill(probs, 1f / 26f)
        else for (k in 0 until 26) probs[k] /= total
    }

    fun suggest(prefix: String, n: Int): List<String> {
        if (!ready) return emptyList()
        val idx = IntArray(n) { -1 }
        var i = lowerBound(prefix)
        while (i < words.size && words[i].startsWith(prefix)) {
            if (words[i].length > prefix.length) {
                val wt = weights[i]
                var pos = n
                while (pos > 0 && (idx[pos - 1] == -1 || weights[idx[pos - 1]] < wt)) pos--
                if (pos < n) {
                    for (j in n - 1 downTo pos + 1) idx[j] = idx[j - 1]
                    idx[pos] = i
                }
            }
            i++
        }
        return idx.filter { it >= 0 }.map { words[it] }
    }
}

/** Suara ketik: taruh standard.ogg, spacebar.ogg, delete.ogg, ret.ogg di res/raw. */
class SoundFx(ctx: Context) {
    private val pool: SoundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = HashMap<String, Int>()

    init {
        for (n in listOf("standard", "spacebar", "delete", "ret")) {
            val r = ctx.resources.getIdentifier(n, "raw", ctx.packageName)
            if (r != 0) ids[n] = pool.load(ctx, r, 1)
        }
    }

    fun play(name: String): Boolean {
        val id = ids[name] ?: ids["standard"] ?: return false
        pool.play(id, 1f, 1f, 1, 0, 1f)
        return true
    }

    fun release() = pool.release()
}

class Lang(val code: String, val name: String, val rows: List<String>, val dict: Boolean)

/** Bahasa yang tersedia. dict=true berarti punya kamus (saran kata + zona sentuh adaptif). */
object Langs {
    private val qwerty = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val all: List<Lang> = listOf(
        Lang("en", "English", qwerty, true),
        Lang("id", "Indonesia", qwerty, true),
        Lang("es", "Español", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), false),
        Lang("ru", "Русский", listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), false),
        Lang("ar", "العربية", listOf("ضصثقفغعهخحجد", "شسيبلاتنمكطذ", "ئءؤرىةوزظ"), false),
        Lang("hi", "हिन्दी", listOf("ौैाीूबहगदजड", "ोे्िुपरकतचट", "ंमनवलसय"), false)
    )
}
