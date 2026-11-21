package io.github.noman13.tvambient.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.service.Shown

/**
 * The live colours of the strip, read from the service ([Shown]) at most 15 times a second, and only while the view
 * is attached and visible (DESIGN.md §4.1 rule 5, §5.1). Subclasses draw them. Nothing is allocated per frame.
 */
abstract class LivePreview(context: Context) : View(context) {
    protected var colours = ByteArray(0)
    protected var leds = 0
    private var seenSeq = -1
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val n = Shown.leds
            // lights off: the preview goes dark too, instead of keeping the last colours
            if (!AmbientService.sRunning && leds > 0) {
                leds = 0
                seenSeq = -1
                invalidate()
            } else if (AmbientService.sRunning && Shown.seq != seenSeq && n > 0) {
                seenSeq = Shown.seq
                if (colours.size != 3 * n) colours = ByteArray(3 * n)
                leds = Shown.copy(colours)
                invalidate()
            }
            postDelayed(this, FRAME_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        users++
        AmbientService.sPreviewWanted = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        if (--users == 0) AmbientService.sPreviewWanted = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        if (isVisible && isAttachedToWindow) post(tick)
    }

    private companion object {
        const val FRAME_MS = 66L
        var users = 0 // main thread
    }
}

/** Settings' header strip: the LEDs in strip order as one thin bar (DESIGN.md §5.2 "live preview strip"). */
class PreviewStripView(context: Context) : LivePreview(context) {
    private val paint = Paint()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 1f); color = 0x33ECEDEF }
    private val rect = RectF()

    init {
        contentDescription = context.getString(io.github.noman13.tvambient.R.string.preview_strip)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Theme.dp(context, 300f).toInt(), Theme.dp(context, 8f).toInt())
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (leds == 0) {
            paint.color = Theme.SURFACE
            c.drawRect(rect, paint)
        } else {
            val w = width.toFloat() / leds
            for (i in 0 until leds) {
                paint.color = Theme.neon(colours[3 * i].toInt() and 0xff, colours[3 * i + 1].toInt() and 0xff, colours[3 * i + 2].toInt() and 0xff)
                c.drawRect(i * w, 0f, (i + 1) * w + 0.5f, height.toFloat(), paint)
            }
        }
        c.drawRect(rect, frame)
    }
}

/** Home's preview: a TV outline with the LEDs drawn round it in their current colours (DESIGN.md §5.1). */
class TvPreviewView(context: Context, private val model: LedModel) : LivePreview(context) {
    private val led = Paint()
    private val ledGlow = Paint()
    private val tv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SURFACE }
    private val tvEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 2f); color = 0xFF2E323A.toInt() }
    private val rect = RectF()
    private val size = Theme.dp(context, 6f)
    private val gap = Theme.dp(context, 8f)

    init {
        contentDescription = context.getString(io.github.noman13.tvambient.R.string.preview_tv)
    }

    override fun onDraw(c: Canvas) {
        val m = size + gap + Theme.dp(context, 4f)
        val w = width - 2 * m
        val h = (w * 9f / 16f).coerceAtMost(height - 2 * m)
        val ww = h * 16f / 9f
        rect.set((width - ww) / 2, (height - h) / 2, (width + ww) / 2, (height + h) / 2)
        c.drawRoundRect(rect, Theme.dp(context, 6f), Theme.dp(context, 6f), tv)
        c.drawRoundRect(rect, Theme.dp(context, 6f), Theme.dp(context, 6f), tvEdge)
        LedDraw.ring(c, rect, model, if (leds == model.count && AmbientService.sRunning) colours else null, size, gap, led, ledGlow)
    }
}
