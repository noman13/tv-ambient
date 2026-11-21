package io.github.noman13.tvambient.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * The 2 s message after a colour-button press (DESIGN.md §8): "Music", "Effect: Comet", "Lights off". Static, drawn
 * once per change; the LEDs' own 300 ms cross-fade is the motion.
 */
class MessageView(context: Context) : View(context) {
    private fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(217, 0x0E, 0x0F, 0x12) } // 85 %
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFECEDEF.toInt()
        textSize = px(18f)
    }
    private val padH = px(20f)
    private val padV = px(10f)
    private val rect = RectF()

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (paint.measureText(text) + 2 * padH).toInt()
        val h = (paint.fontSpacing + 2 * padV).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, height / 2f, height / 2f, bg)
        c.drawText(text, padH, padV - paint.ascent(), paint)
    }
}
