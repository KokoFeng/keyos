package com.fengios.keyos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller

/** Panel emoji: grid 8 kolom, scroll vertikal, tab kategori + ABC + hapus di bawah. */
@SuppressLint("ClickableViewAccessibility")
class EmojiView(ctx: Context, private val svc: KeyOSService) : View(ctx) {

    private class Row(val header: String?, val cells: List<String>, val y: Float, val h: Float)

    private val dn = resources.displayMetrics.density
    private fun d(v: Float) = v * dn
    private val ep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private var th: KTheme = Themes.all[0]
    private val cols = 8
    private val rows = ArrayList<Row>()
    private val catStart = FloatArray(9)
    private var contentH = 0f
    private var off = 0f
    private val scroller = OverScroller(ctx)
    private val barH get() = d(44f)
    private val cellW get() = width / cols.toFloat()
    private val tabIcons = listOf("🕘", "😀", "🧑", "🐻", "🍔", "⚽", "🚗", "💡", "🔣")
    private val names = listOf(
        "Frequently Used", "Smileys", "People & Gestures", "Animals & Nature", "Food & Drink",
        "Activity", "Travel & Places", "Objects", "Symbols"
    )
    private var data: List<List<String>> = List(9) { emptyList() }

    private val iosFont: Typeface? by lazy {
        try { Typeface.createFromAsset(context.assets, "fonts/ios_emoji.ttf") } catch (e: Exception) { null }
    }

    init {
        ep.textAlign = Paint.Align.CENTER
    }

    private fun range(vararg r: IntRange): List<String> {
        val out = ArrayList<String>()
        for (rg in r) for (cp in rg) {
            val s = String(Character.toChars(cp)) + (if (cp < 0x10000) "\uFE0F" else "")
            if (ep.hasGlyph(s)) out.add(s)
        }
        return out
    }

    fun applyPrefs() {
        val sp = Prefs.sp(context)
        th = Themes.all[sp.getInt("theme", 0).coerceIn(0, Themes.all.size - 1)]
        ep.typeface = if (sp.getBoolean("iosEmoji", true)) iosFont else null
        data = listOf(
            emptyList(),
            range(0x1F600..0x1F64F, 0x1F910..0x1F92F, 0x1F970..0x1F976),
            range(0x1F44A..0x1F450, 0x1F466..0x1F478, 0x1F9D1..0x1F9DD, 0x1F90C..0x1F90F, 0x1F918..0x1F91F, 0x270A..0x270D, 0x1F440..0x1F445),
            range(0x1F400..0x1F43E, 0x1F980..0x1F9AE, 0x1F330..0x1F344),
            range(0x1F345..0x1F37F, 0x1F950..0x1F96B),
            range(0x1F3A0..0x1F3CA, 0x26BD..0x26BE, 0x1F94A..0x1F94F),
            range(0x1F680..0x1F6A4, 0x1F3E0..0x1F3F0, 0x1F30D..0x1F31F),
            range(0x1F4B0..0x1F4FF),
            range(0x2764..0x2764, 0x1F493..0x1F49F, 0x1F500..0x1F53D, 0x2705..0x2705, 0x274C..0x274C, 0x2B50..0x2B50, 0x2728..0x2728)
        )
        build()
        invalidate()
    }

    private fun recents(): List<String> =
        Prefs.sp(context).getString("recents", "")!!.split("|").filter { it.isNotEmpty() }

    private fun build() {
        rows.clear()
        if (width <= 0) return
        ep.textSize = cellW * 0.62f
        var y = 0f
        for (ci in 0 until 9) {
            val items = if (ci == 0) recents() else data[ci]
            catStart[ci] = y
            rows.add(Row(names[ci], emptyList(), y, d(30f)))
            y += d(30f)
            for (chunk in items.chunked(cols)) {
                rows.add(Row(null, chunk, y, cellW))
                y += cellW
            }
        }
        contentH = y
        off = off.coerceIn(0f, maxScroll())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { build() }

    private fun maxScroll() = maxOf(0f, contentH - (height - barH))

    override fun onDraw(c: Canvas) {
        c.drawColor(if (Color.alpha(th.bg) < 255) th.bg else th.bg)
        val vh = height - barH
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), vh)
        for (r in rows) {
            val ry = r.y - off
            if (ry + r.h < 0f || ry > vh) continue
            val hdr = r.header
            if (hdr != null) {
                tp.textSize = d(13f)
                tp.color = withAlpha(th.text, 150)
                tp.textAlign = Paint.Align.LEFT
                c.drawText(hdr, d(12f), ry + r.h * 0.72f, tp)
            } else {
                val fm = ep.fontMetrics
                for ((i, s) in r.cells.withIndex()) {
                    c.drawText(s, cellW * (i + 0.5f), ry + r.h / 2f - (fm.ascent + fm.descent) / 2f, ep)
                }
            }
        }
        c.restore()
        // bar bawah
        val by = height - barH
        fp.style = Paint.Style.FILL
        fp.color = withAlpha(th.special, 255).let { if (Color.alpha(th.bg) < 255) th.special else it }
        c.drawRect(0f, by, width.toFloat(), height.toFloat(), fp)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = d(16f)
        tp.color = th.text
        c.drawText("ABC", d(28f), by + barH / 2f - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2f, tp)
        ip.color = th.text
        Icons.del(c, width - d(28f), by + barH / 2f, d(10f), ip)
        val tabW = (width - d(112f)) / 9f
        var active = 0
        for (i in 0 until 9) if (catStart[i] <= off + 1f) active = i
        val saved = ep.textSize
        ep.textSize = d(20f)
        val fm = ep.fontMetrics
        for (i in 0 until 9) {
            val cx = d(56f) + tabW * (i + 0.5f)
            if (i == active) {
                fp.color = withAlpha(th.text, 40)
                c.drawRoundRect(RectF(cx - tabW / 2f + d(2f), by + d(6f), cx + tabW / 2f - d(2f), height - d(6f)), d(8f), d(8f), fp)
            }
            c.drawText(tabIcons[i], cx, by + barH / 2f - (fm.ascent + fm.descent) / 2f, ep)
        }
        ep.textSize = saved
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            off = (off + dy).coerceIn(0f, maxScroll()); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, off.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt()); invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            off = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gd.onTouchEvent(e)

    private fun tap(x: Float, y: Float) {
        if (y > height - barH) {
            svc.haptic()
            if (x < d(56f)) { svc.showKeyboard(); return }
            if (x > width - d(56f)) { svc.keyFeedback(KT.DELETE); svc.deleteKey(); return }
            val tabW = (width - d(112f)) / 9f
            val i = ((x - d(56f)) / tabW).toInt()
            if (i in 0..8) { off = catStart[i].coerceIn(0f, maxScroll()); invalidate() }
            return
        }
        val yy = y + off
        for (r in rows) {
            if (r.header != null || yy < r.y || yy >= r.y + r.h) continue
            val s = r.cells.getOrNull((x / cellW).toInt()) ?: return
            svc.haptic()
            svc.keyFeedback(KT.CHAR)
            svc.typeText(s)
            addRecent(s)
            return
        }
    }

    private fun addRecent(s: String) {
        val list = (listOf(s) + recents().filter { it != s }).take(32)
        Prefs.sp(context).edit().putString("recents", list.joinToString("|")).apply()
    }
}
