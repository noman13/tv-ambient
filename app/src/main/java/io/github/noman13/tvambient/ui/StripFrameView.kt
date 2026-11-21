package io.github.noman13.tvambient.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import io.github.noman13.tvambient.ui.SettingsSchema as S

/**
 * The strip setup drawing: while Settings › LED strip is open, a neon dot for
 * every LED runs round a rectangle well inside the screen ([RECT_H_DP], [RECT_V_DP] from the edges, so nothing is
 * cut by overscan), in the LEDs' live colours. The LED number of each corner sits outside the rectangle, LED 1 has a
 * neon marker, and a short comet runs from it in the strip's direction (15 fps, only while this page is open). The
 * Settings panel shrinks to fit inside the rectangle on this page. Never takes focus.
 */
class StripFrameView(context: Context, private val model: LedModel, private val sp: SharedPreferences) : LivePreview(context) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 2f) }
    private val markerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 6f) }
    private val number = Theme.paint(context, Theme.TEXT_DIM, Theme.SMALL_SP, bold = true)
    private val first = Theme.paint(context, Theme.NEON_GREEN, Theme.HINT_SP, bold = true)
    private val r = RectF()
    private val pt = FloatArray(2)
    private val out = FloatArray(2) // the outward direction at a dot: where its label goes
    private val start = SystemClock.uptimeMillis()
    private val comet = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            invalidate()
            postDelayed(this, 66)
        }
    }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(comet)
        if (isVisible) post(comet)
    }

    /** Where LED [i]'s dot is, round [r], and which way is outward there. */
    private fun place(i: Int) {
        val a = model.along[i]
        when (model.edge[i].toInt()) {
            0 -> { pt[0] = r.left + a * r.width(); pt[1] = r.top; out[0] = 0f; out[1] = -1f }
            1 -> { pt[0] = r.right; pt[1] = r.top + a * r.height(); out[0] = 1f; out[1] = 0f }
            2 -> { pt[0] = r.left + a * r.width(); pt[1] = r.bottom; out[0] = 0f; out[1] = 1f }
            else -> { pt[0] = r.left; pt[1] = r.top + a * r.height(); out[0] = -1f; out[1] = 0f }
        }
    }

    private fun colourOf(i: Int, live: Boolean): Int = if (live) {
        Theme.neon(colours[3 * i].toInt() and 0xff, colours[3 * i + 1].toInt() and 0xff, colours[3 * i + 2].toInt() and 0xff)
    } else Theme.DARK_LED

    override fun onDraw(c: Canvas) {
        val n = model.count
        if (n == 0) return
        val mx = Theme.dp(context, RECT_H_DP)
        val my = Theme.dp(context, RECT_V_DP)
        r.set(mx, my, width - mx, height - my)
        val radius = Theme.dp(context, 3f)
        val live = leds == n
        for (i in 0 until n) {
            place(i)
            val col = colourOf(i, live)
            if (col != Theme.DARK_LED) { // only lit LEDs glow
                glow.color = col
                glow.alpha = 60
                c.drawCircle(pt[0], pt[1], radius * 2.6f, glow)
            }
            dot.color = col
            c.drawCircle(pt[0], pt[1], radius, dot)
        }
        // the comet: a bright head running from LED 1 in the strip's direction over its first 16 LEDs, with a tail
        val span = minOf(16, n)
        val t = ((SystemClock.uptimeMillis() - start) % 1600L) / 1600f
        val head = (t * span).toInt()
        for (k in 0..4) {
            val i = head - k
            if (i < 0) break
            place(i)
            val a = 1f - k / 5f
            markerGlow.color = Theme.NEON_GREEN
            markerGlow.alpha = (90 * a).toInt()
            c.drawCircle(pt[0], pt[1], radius * 2.4f, markerGlow)
            dot.color = Theme.NEON_GREEN
            dot.alpha = (255 * a).toInt()
            c.drawCircle(pt[0], pt[1], radius * 1.3f, dot)
        }
        dot.alpha = 255
        // LED 1: a neon ring and its number, outside the rectangle
        place(0)
        marker.color = Theme.NEON_GREEN
        markerGlow.color = Theme.NEON_GREEN
        markerGlow.alpha = 110
        c.drawCircle(pt[0], pt[1], radius * 4f, markerGlow)
        c.drawCircle(pt[0], pt[1], radius * 4f, marker)
        drawLabel(c, "1 ▶", first)
        // the LED number at each corner (the last LED of each side), outside the rectangle
        for (i in 0 until n) {
            if (model.edge[i] != model.edge[(i + 1) % n]) {
                place(i)
                drawLabel(c, (i + 1).toString(), number)
            }
        }
    }

    /** A label just outside the rectangle at the current dot, never over the dots. */
    private fun drawLabel(c: Canvas, text: String, p: Paint) {
        val w = p.measureText(text)
        val gap = Theme.dp(context, 12f)
        val x = pt[0] + out[0] * gap - (if (out[0] < 0) w else if (out[0] > 0) 0f else w / 2)
        val y = pt[1] + out[1] * gap + (if (out[1] > 0) p.textSize else if (out[1] < 0) 0f else p.textSize / 3)
        c.drawText(text, x, y, p)
    }

    companion object {
        /** The LED rectangle's distance from the screen edges, dp: room for the numbers outside it. */
        const val RECT_H_DP = 64f
        const val RECT_V_DP = 40f
        /** The Settings panel's margins on the LED strip page, so it fits inside the rectangle. */
        const val PANEL_H_DP = 92f
        const val PANEL_V_DP = 64f
    }
}
