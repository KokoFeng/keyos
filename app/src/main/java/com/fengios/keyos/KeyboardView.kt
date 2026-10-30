package com.fengios.keyos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

enum class KT { CHAR, SHIFT, DELETE, MODE, SYMS, SPACE, RETURN, EMOJI, MIC }

class Key(val type: KT, val label: String = "", val bare: Boolean = false) {
    val r = RectF()
}

@SuppressLint("ClickableViewAccessibility")
class KeyboardView(ctx: Context, private val svc: KeyOSService) : View(ctx) {

    private val dn = resources.displayMetrics.density
    private fun d(v: Float) = v * dn

    private var th: KTheme = Themes.all[0]
    private var glassPct = 50
    private var hs = 1f
    private var zones = true
    private var popupOn = true
    private var leftIcon = 0

    private var langs: List<Lang> = listOf(Langs.all[0])
    private var langIdx = 0
    private val lang: Lang get() = langs[langIdx.coerceIn(0, langs.size - 1)]
    private var spaceLabel = "space"
    private val spaceRun = Runnable { spaceLabel = "space"; invalidate() }

    private var layer = 0
    var shift = 0
        private set
    private var shiftAuto = false
    private var lastShiftTap = 0L
    private var actionLabel = "return"
    private var actionBlue = false

    private val keys = ArrayList<Key>()
    private var sugg: List<String> = emptyList()
    private var probs: FloatArray? = null
    private val hd = Handler(Looper.getMainLooper())

    private var pressed: Key? = null
    private var activeId = -1
    private var sugDown = -1
    private var lastX = 0f
    private var lastY = 0f
    private var consumed = false
    private var trackpad = false
    private var trackX = 0f
    private var altShown = false
    private var altList: List<String> = emptyList()
    private var altSel = 0
    private val altRect = RectF()
    private val altCellW get() = d(36f)

    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val altMap: Map<Char, String> = mapOf(
        'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "èéêëēėę", 'i' to "îïíīįì", 'l' to "ł",
        'n' to "ñń", 'o' to "ôöòóœøōõ", 's' to "ßśš", 'u' to "ûüùúū", 'y' to "ÿ", 'z' to "žźż",
        '0' to "°", '-' to "–—•", '$' to "€£¥¢₹", '&' to "§", '"' to "“”„»«", '\'' to "‘’`",
        'е' to "ё", 'ь' to "ъ", '?' to "¿", '!' to "¡", '.' to "…", '=' to "≠≈", '%' to "‰"
    )

    // ---------- ukuran ----------
    private val suggH get() = d(44f)
    private val keyH get() = d(43f) * hs
    private val gapY get() = d(11f) * hs
    private val stripH get() = d(44f)

    override fun onMeasure(w: Int, h: Int) {
        val total = suggH + d(4f) + 4 * keyH + 3 * gapY + d(2f) + stripH
        setMeasuredDimension(MeasureSpec.getSize(w), total.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { build() }

    // ---------- API untuk service ----------
    fun applyPrefs() {
        val sp = Prefs.sp(context)
        th = Themes.all[sp.getInt("theme", 0).coerceIn(0, Themes.all.size - 1)]
        glassPct = sp.getInt("glass", 50)
        hs = sp.getInt("height", 100) / 100f
        zones = sp.getBoolean("zones", true)
        popupOn = sp.getBoolean("popup", true)
        leftIcon = sp.getInt("leftIcon", 0)
        val enabled = Langs.all.filter { sp.getBoolean("lg_" + it.code, it.code == "en" || it.code == "id") }
        langs = if (enabled.isEmpty()) listOf(Langs.all[0]) else enabled
        if (langIdx >= langs.size) langIdx = 0
        requestLayout()
        build()
        invalidate()
    }

    fun hasDict(): Boolean = lang.dict

    private fun nextLang() {
        langIdx = (langIdx + 1) % langs.size
        spaceLabel = lang.name
        hd.removeCallbacks(spaceRun)
        hd.postDelayed(spaceRun, 1200)
        build()
        invalidate()
        svc.afterEdit()
    }

    fun setLayer(i: Int) { layer = i; build(); invalidate() }
    fun setProbs(p: FloatArray?) { probs = p }
    fun setSuggestions(l: List<String>) { sugg = l; invalidate() }
    fun setAction(label: String, blue: Boolean) { actionLabel = label; actionBlue = blue; build(); invalidate() }

    fun autoCap(on: Boolean) {
        if (on && shift == 0) { shift = 1; shiftAuto = true; invalidate() }
        else if (!on && shiftAuto && shift == 1) { shift = 0; shiftAuto = false; invalidate() }
    }

    // ---------- layout ----------
    private fun place(k: Key, x: Float, y: Float, w: Float) {
        k.r.set(x, y, x + w, y + keyH)
        keys.add(k)
    }

    private fun build() {
        keys.clear()
        val w = width.toFloat()
        if (w <= 0f) return
        val m = d(3f)
        val g = d(6f)
        val rows: List<String> = when (layer) {
            0 -> lang.rows
            1 -> listOf("1234567890", "-/:;()$&@\"", ".,?!'")
            else -> listOf("[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'")
        }
        val n0 = rows[0].length
        val kw = (w - 2 * m - (n0 - 1) * g) / n0
        val top = suggH + d(4f)
        val pitch = keyH + gapY
        var x = m
        for (ch in rows[0]) { place(Key(KT.CHAR, ch.toString()), x, top, kw); x += kw + g }
        val n1 = rows[1].length
        x = m + (w - 2 * m - (n1 * kw + (n1 - 1) * g)) / 2f
        for (ch in rows[1]) { place(Key(KT.CHAR, ch.toString()), x, top + pitch, kw); x += kw + g }
        val y2 = top + 2 * pitch
        val n2 = rows[2].length
        val sw: Float
        if (layer == 0) {
            val lw = n2 * kw + (n2 - 1) * g
            sw = (w - 2 * m - lw - 2 * g) / 2f
            place(Key(KT.SHIFT), m, y2, sw)
            x = m + sw + g
            for (ch in rows[2]) { place(Key(KT.CHAR, ch.toString()), x, y2, kw); x += kw + g }
        } else {
            sw = (w - 2 * m - 7 * kw - 8 * g) / 2f
            place(Key(KT.SYMS, if (layer == 1) "#+=" else "123"), m, y2, sw)
            val kw2 = (w - 2 * m - 2 * sw - 6 * g) / 5f
            x = m + sw + g
            for (ch in rows[2]) { place(Key(KT.CHAR, ch.toString()), x, y2, kw2); x += kw2 + g }
        }
        place(Key(KT.DELETE), w - m - sw, y2, sw)
        val y3 = top + 3 * pitch
        val retW = sw * 1.55f
        place(Key(KT.MODE, if (layer == 0) "123" else "ABC"), m, y3, sw)
        place(Key(KT.SPACE, "space"), m + sw + g, y3, w - 2 * m - sw - retW - 2 * g)
        place(Key(KT.RETURN, actionLabel), w - m - retW, y3, retW)
        val st = y3 + keyH + d(2f)
        val w1 = sw + g + kw
        if (langs.size > 1) {
            val gk = Key(KT.EMOJI, "globe", true)
            gk.r.set(m, st, m + w1 / 2f, st + stripH)
            keys.add(gk)
            val ek = Key(KT.EMOJI, "emoji", true)
            ek.r.set(m + w1 / 2f, st, m + w1, st + stripH)
            keys.add(ek)
        } else {
            val e = Key(KT.EMOJI, if (leftIcon == 1) "globe" else "emoji", true)
            e.r.set(m, st, m + w1, st + stripH)
            keys.add(e)
        }
        val mk = Key(KT.MIC, "", true)
        mk.r.set(w - m - sw - g - kw, st, w - m, st + stripH)
        keys.add(mk)
    }

    // ---------- ZONA SENTUH ADAPTIF ----------
    // Tombol terlihat tetap sama. Yang berubah hanya "bobot" tiap huruf saat menentukan
    // huruf mana yang kena: huruf yang mungkin jadi berikutnya zonanya melebar,
    // huruf tetangga yang mustahil menyempit.
    private fun pick(x: Float, y: Float): Key? {
        if (keys.isEmpty()) return null
        var inside: Key? = null
        for (k in keys) if (k.r.contains(x, y)) { inside = k; break }
        if (inside != null && inside.type != KT.CHAR) return inside
        var near: Key? = inside
        if (near == null) {
            var best = Float.MAX_VALUE
            for (k in keys) {
                val dx = maxOf(k.r.left - x, 0f, x - k.r.right)
                val dy = maxOf(k.r.top - y, 0f, y - k.r.bottom)
                val dd = dx * dx + dy * dy
                if (dd < best) { best = dd; near = k }
            }
        }
        val nr = near ?: return null
        if (nr.type != KT.CHAR) return nr
        val pr = probs
        if (!zones || layer != 0 || !lang.dict || pr == null) return nr
        var bestK: Key? = null
        var bestS = Float.MAX_VALUE
        val pitch = keyH + gapY
        for (k in keys) {
            if (k.type != KT.CHAR) continue
            val cx = k.r.centerX()
            val cy = k.r.centerY()
            if (abs(cy - y) > pitch * 0.8f || abs(cx - x) > k.r.width() * 1.5f) continue
            val ex = (cx - x) / (k.r.width() / 2f)
            val ey = (cy - y) / (k.r.height() / 2f)
            val c = k.label[0]
            val p = if (c in 'a'..'z') pr[c - 'a'] else 0f
            val boost = 0.55f + 1.35f * sqrt(p)
            val s = (ex * ex + ey * ey) / boost
            if (s < bestS) { bestS = s; bestK = k }
        }
        return bestK ?: nr
    }

    // ---------- gambar ----------
    private fun adj(col: Int): Int {
        if (Color.alpha(th.bg) >= 255) return col
        val f = 1.6f - glassPct / 100f * 1.2f
        val a = (Color.alpha(col) * f).toInt().coerceIn(0, 255)
        return withAlpha(col, a)
    }

    private fun casedLabel(k: Key): String =
        if (layer == 0 && shift != 0) k.label.uppercase() else k.label

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, col: Int) {
        tp.textSize = size
        tp.color = col
        tp.textAlign = Paint.Align.CENTER
        val fm = tp.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(adj(th.bg))
        drawSugg(c)
        for (k in keys) drawKey(c, k)
        drawPopup(c)
    }

    private fun drawSugg(c: Canvas) {
        val w3 = width / 3f
        val cy = suggH / 2f
        for (i in 0..2) {
            var s = sugg.getOrNull(i) ?: ""
            if (s.isEmpty()) continue
            tp.textSize = d(16f)
            while (tp.measureText(s) > w3 - d(16f) && s.length > 1) s = s.dropLast(1)
            text(c, s, w3 * i + w3 / 2f, cy, d(16f), th.text)
        }
        if (sugg.size >= 3 && sugg[1].isNotEmpty()) {
            fp.color = withAlpha(th.text, 70)
            fp.style = Paint.Style.FILL
            for (i in 1..2) c.drawRect(w3 * i - d(0.5f), d(10f), w3 * i + d(0.5f), suggH - d(10f), fp)
        }
    }

    private fun drawKey(c: Canvas, k: Key) {
        val isDown = pressed === k
        val cx = k.r.centerX()
        val cy = k.r.centerY()
        if (k.bare) {
            ip.color = if (isDown) withAlpha(th.text, 110) else th.text
            val ix = if (langs.size > 1) cx else k.r.left + d(26f)
            if (k.type == KT.MIC) Icons.mic(c, k.r.right - d(26f), cy, d(10f), ip)
            else if (k.label == "globe") Icons.globe(c, ix, cy, d(11f), ip)
            else Icons.smiley(c, ix, cy, d(11f), ip)
            return
        }
        val rr = d(5.5f)
        val special = k.type == KT.SHIFT || k.type == KT.DELETE || k.type == KT.MODE ||
            k.type == KT.SYMS || (k.type == KT.RETURN && !actionBlue)
        var bg = if (special) adj(th.special) else adj(th.key)
        val shiftOn = k.type == KT.SHIFT && shift != 0
        if (shiftOn) bg = th.text
        if (isDown) bg = if (special) adj(th.key) else adj(th.keyDown)
        if (k.type == KT.RETURN && actionBlue) bg = if (isDown) withAlpha(th.accent, 190) else th.accent
        val hideBody = isDown && k.type == KT.CHAR && popupOn && !trackpad
        if (!hideBody) {
            fp.style = Paint.Style.FILL
            if (Color.alpha(th.bg) >= 255) {
                fp.color = 0x66000000
                c.drawRoundRect(k.r.left, k.r.top + d(1f), k.r.right, k.r.bottom + d(1f), rr, rr, fp)
            }
            fp.color = bg
            c.drawRoundRect(k.r, rr, rr, fp)
            if (th.glass) {
                fp.style = Paint.Style.STROKE
                fp.strokeWidth = d(1f)
                fp.color = 0x55FFFFFF
                c.drawRoundRect(k.r, rr, rr, fp)
                fp.style = Paint.Style.FILL
            }
        }
        val fg = if (shiftOn) (th.bg or (0xFF shl 24)) else if (k.type == KT.RETURN && actionBlue) Color.WHITE else th.text
        when (k.type) {
            KT.CHAR -> if (!trackpad && !hideBody) text(c, casedLabel(k), cx, cy, d(24f), fg)
            KT.SHIFT -> { ip.color = fg; Icons.shift(c, cx, cy, d(9f), shift, ip) }
            KT.DELETE -> { ip.color = fg; Icons.del(c, cx, cy, d(10f), ip) }
            KT.MODE, KT.SYMS -> text(c, k.label, cx, cy, d(16f), fg)
            KT.SPACE -> if (!trackpad) text(c, spaceLabel, cx, cy, d(16f), fg)
            KT.RETURN -> text(c, actionLabel, cx, cy, d(16f), fg)
            else -> {}
        }
    }

    private fun drawPopup(c: Canvas) {
        val k = pressed ?: return
        if (altShown) { drawAlts(c, k); return }
        if (!popupOn || k.type != KT.CHAR || trackpad) return
        val l = k.r.left; val t = k.r.top; val r = k.r.right; val b = k.r.bottom
        val ex = d(11f)
        var bl = l - ex
        var br = r + ex
        if (bl < d(2f)) { val sh = d(2f) - bl; bl += sh; br += sh }
        if (br > width - d(2f)) { val sh = br - (width - d(2f)); bl -= sh; br -= sh }
        val bt = t - keyH * 1.15f
        val rc = d(9f)
        val rk = d(5.5f)
        val nk = d(7f)
        path.reset()
        path.moveTo(l, b - rk)
        path.lineTo(l, t + nk)
        path.cubicTo(l, t - nk * 0.2f, bl, t + nk * 0.2f, bl, t - nk)
        path.lineTo(bl, bt + rc)
        path.quadTo(bl, bt, bl + rc, bt)
        path.lineTo(br - rc, bt)
        path.quadTo(br, bt, br, bt + rc)
        path.lineTo(br, t - nk)
        path.cubicTo(br, t + nk * 0.2f, r, t - nk * 0.2f, r, t + nk)
        path.lineTo(r, b - rk)
        path.quadTo(r, b, r - rk, b)
        path.lineTo(l + rk, b)
        path.quadTo(l, b, l, b - rk)
        path.close()
        fp.style = Paint.Style.FILL
        c.save()
        c.translate(0f, d(1.5f))
        fp.color = 0x44000000
        c.drawPath(path, fp)
        c.restore()
        fp.color = th.popup
        c.drawPath(path, fp)
        text(c, casedLabel(k), (bl + br) / 2f, (bt + t) / 2f, d(36f), th.popupText)
    }

    private fun drawAlts(c: Canvas, k: Key) {
        val n = altList.size
        val barW = n * altCellW + d(8f)
        val barL = (k.r.centerX() - barW / 2f).coerceIn(d(2f), maxOf(d(2f), width - barW - d(2f)))
        val barB = k.r.top - d(6f)
        altRect.set(barL, barB - keyH * 1.15f, barL + barW, barB)
        fp.style = Paint.Style.FILL
        fp.color = th.popup
        c.drawRoundRect(altRect, d(10f), d(10f), fp)
        for (i in 0 until n) {
            val cl = altRect.left + d(4f) + i * altCellW
            if (i == altSel) {
                fp.color = th.accent
                c.drawRoundRect(cl, altRect.top + d(4f), cl + altCellW, altRect.bottom - d(4f), d(7f), d(7f), fp)
            }
            text(c, altList[i], cl + altCellW / 2f, altRect.centerY(), d(26f),
                if (i == altSel) Color.WHITE else th.popupText)
        }
    }

    // ---------- sentuhan ----------
    private val repeatRun = object : Runnable {
        override fun run() { svc.deleteKey(); hd.postDelayed(this, 55) }
    }
    private val altRun = Runnable {
        val k = pressed
        if (k != null && k.type == KT.CHAR) {
            val base = altMap[k.label[0]]
            if (base != null) {
                val up = layer == 0 && shift != 0
                altList = base.map { if (up) it.toString().uppercase() else it.toString() }
                altSel = 0
                altShown = true
                consumed = true
                svc.haptic()
                invalidate()
            }
        }
    }
    private val trackRun = Runnable {
        val k = pressed
        if (k != null && k.type == KT.SPACE) {
            trackpad = true
            consumed = true
            trackX = lastX
            svc.haptic()
            invalidate()
        }
    }
    private val globeRun = Runnable {
        val k = pressed
        if (k != null && k.type == KT.EMOJI) { consumed = true; svc.showPicker() }
    }

    private fun clearTimers() {
        hd.removeCallbacks(repeatRun); hd.removeCallbacks(altRun)
        hd.removeCallbacks(trackRun); hd.removeCallbacks(globeRun)
    }

    override fun onDetachedFromWindow() { clearTimers(); super.onDetachedFromWindow() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { activeId = e.getPointerId(0); down(e.x, e.y) }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                finish(lastX, lastY)  // jempol sebelumnya dianggap selesai -> ketikan cepat 2 jempol
                activeId = e.getPointerId(idx)
                down(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activeId) { finish(lastX, lastY); activeId = -1 }
            }
            MotionEvent.ACTION_UP -> {
                if (activeId != -1) {
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) finish(e.getX(i), e.getY(i)) else finish(lastX, lastY)
                    activeId = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> { cancelAll(); activeId = -1 }
        }
        return true
    }

    private fun down(x: Float, y: Float) {
        lastX = x; lastY = y
        if (y < suggH) { sugDown = (x / (width / 3f)).toInt().coerceIn(0, 2); invalidate(); return }
        val k = pick(x, y) ?: return
        press(k)
    }

    private fun press(k: Key) {
        pressed = k
        consumed = false
        svc.haptic()
        svc.keyFeedback(k.type)
        when (k.type) {
            KT.DELETE -> { svc.deleteKey(); hd.postDelayed(repeatRun, 400) }
            KT.SPACE -> hd.postDelayed(trackRun, 380)
            KT.CHAR -> if (altMap.containsKey(k.label[0])) hd.postDelayed(altRun, 380)
            KT.EMOJI -> hd.postDelayed(globeRun, 450)
            else -> {}
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        lastX = x; lastY = y
        if (sugDown >= 0) return
        val k0 = pressed ?: return
        if (altShown) {
            val n = altList.size
            altSel = ((x - altRect.left - d(4f)) / altCellW).toInt().coerceIn(0, n - 1)
            invalidate()
            return
        }
        if (trackpad) {
            val step = d(12f)
            while (x - trackX > step) { svc.moveCursor(1); trackX += step }
            while (trackX - x > step) { svc.moveCursor(-1); trackX -= step }
            return
        }
        if (k0.type != KT.CHAR) return
        val k = pick(x, y) ?: return
        if (k !== k0 && k.type == KT.CHAR) {
            hd.removeCallbacks(altRun)
            pressed = k
            svc.keyFeedback(KT.CHAR)
            if (altMap.containsKey(k.label[0])) hd.postDelayed(altRun, 380)
            invalidate()
        }
    }

    private fun finish(x: Float, y: Float) {
        clearTimers()
        if (sugDown >= 0) {
            val i = sugDown
            sugDown = -1
            if (y < suggH) svc.pickSuggestion(i)
            invalidate()
            return
        }
        val k0 = pressed
        pressed = null
        if (k0 == null) { invalidate(); return }
        if (altShown) {
            altShown = false
            val s = altList.getOrNull(altSel)
            if (s != null) { if (shift == 1) { shift = 0; shiftAuto = false }; svc.typeText(s) }
        } else if (trackpad) {
            trackpad = false
        } else if (!consumed) {
            val k = if (k0.type == KT.CHAR) (pick(x, y)?.takeIf { it.type == KT.CHAR } ?: k0) else k0
            when (k.type) {
                KT.CHAR -> {
                    val s = casedLabel(k)
                    if (shift == 1) { shift = 0; shiftAuto = false }
                    svc.typeText(s)
                }
                KT.SHIFT -> {
                    val now = SystemClock.uptimeMillis()
                    shiftAuto = false
                    shift = if (now - lastShiftTap < 320 && shift == 1) 2 else if (shift == 0) 1 else 0
                    lastShiftTap = now
                }
                KT.MODE -> { layer = if (layer == 0) 1 else 0; build() }
                KT.SYMS -> { layer = if (layer == 1) 2 else 1; build() }
                KT.SPACE -> svc.space()
                KT.RETURN -> svc.enter()
                KT.EMOJI -> if (k.label == "globe") { if (langs.size > 1) nextLang() else svc.showPicker() } else svc.showEmoji()
                KT.MIC -> svc.startVoice()
                KT.DELETE -> {}
            }
        }
        consumed = false
        invalidate()
    }

    private fun cancelAll() {
        clearTimers()
        pressed = null; altShown = false; trackpad = false; sugDown = -1; consumed = false
        invalidate()
    }
}
