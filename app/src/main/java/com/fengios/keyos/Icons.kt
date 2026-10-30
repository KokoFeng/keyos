package com.fengios.keyos

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Ikon digambar manual (vektor) supaya tajam di semua ukuran layar. */
object Icons {
    private val path = Path()
    private val oval = RectF()

    private fun stroke(p: Paint, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.16f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    fun shift(c: Canvas, cx: Float, cy0: Float, s: Float, state: Int, p: Paint) {
        val cy = cy0 - s * 0.1f
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy + 0.05f * s)
        path.lineTo(cx + s * 0.45f, cy + 0.05f * s)
        path.lineTo(cx + s * 0.45f, cy + s * 0.85f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.85f)
        path.lineTo(cx - s * 0.45f, cy + 0.05f * s)
        path.lineTo(cx - s, cy + 0.05f * s)
        path.close()
        stroke(p, s)
        if (state != 0) p.style = Paint.Style.FILL_AND_STROKE
        c.drawPath(path, p)
        if (state == 2) {
            p.style = Paint.Style.FILL
            c.drawRect(cx - s * 0.45f, cy + s * 1.02f, cx + s * 0.45f, cy + s * 1.2f, p)
        }
        p.style = Paint.Style.FILL
    }

    fun del(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        path.moveTo(cx - s * 1.15f, cy)
        path.lineTo(cx - s * 0.45f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.8f)
        path.close()
        stroke(p, s)
        c.drawPath(path, p)
        val k = s * 0.3f
        val x0 = cx + s * 0.3f
        c.drawLine(x0 - k, cy - k, x0 + k, cy + k, p)
        c.drawLine(x0 - k, cy + k, x0 + k, cy - k, p)
        p.style = Paint.Style.FILL
    }

    fun globe(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        c.drawCircle(cx, cy, s, p)
        oval.set(cx - s * 0.45f, cy - s, cx + s * 0.45f, cy + s)
        c.drawOval(oval, p)
        c.drawLine(cx, cy - s, cx, cy + s, p)
        c.drawLine(cx - s, cy, cx + s, cy, p)
        c.drawLine(cx - s * 0.86f, cy - s * 0.5f, cx + s * 0.86f, cy - s * 0.5f, p)
        c.drawLine(cx - s * 0.86f, cy + s * 0.5f, cx + s * 0.86f, cy + s * 0.5f, p)
        p.style = Paint.Style.FILL
    }

    fun smiley(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        c.drawCircle(cx, cy, s, p)
        oval.set(cx - s * 0.55f, cy - s * 0.4f, cx + s * 0.55f, cy + s * 0.6f)
        c.drawArc(oval, 25f, 130f, false, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx - s * 0.35f, cy - s * 0.3f, s * 0.11f, p)
        c.drawCircle(cx + s * 0.35f, cy - s * 0.3f, s * 0.11f, p)
    }

    fun mic(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        oval.set(cx - s * 0.32f, cy - s, cx + s * 0.32f, cy + s * 0.3f)
        c.drawRoundRect(oval, s * 0.32f, s * 0.32f, p)
        oval.set(cx - s * 0.66f, cy - s * 0.45f, cx + s * 0.66f, cy + s * 0.75f)
        c.drawArc(oval, 0f, 180f, false, p)
        c.drawLine(cx, cy + s * 0.75f, cx, cy + s * 1.05f, p)
        p.style = Paint.Style.FILL
    }
}
