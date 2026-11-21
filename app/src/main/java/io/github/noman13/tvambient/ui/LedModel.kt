package io.github.noman13.tvambient.ui

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.NativeConfig
import io.github.noman13.tvambient.ui.SettingsSchema as S
import java.nio.ByteBuffer

/**
 * The UI's view of the strip: its own Rust pipeline (for effect and music tile previews, which must not disturb the
 * service's) and each LED's place round the screen, from the core itself (`layoutGeometry`), so the drawings always
 * match the real layout. Main thread only; [close] frees the native side.
 */
class LedModel(private val sp: SharedPreferences) : AutoCloseable {
    private var pipeline = 0L
    var count = 0
        private set
    /** per LED, strip order: 0 top, 1 right, 2 bottom, 3 left */
    var edge = ByteArray(0)
        private set
    /** per LED: 0..1 along its side (top and bottom left to right, sides top to bottom) */
    var along = FloatArray(0)
        private set
    private var out = ByteBuffer.allocateDirect(0)

    init {
        rebuild()
    }

    /** After a layout change (LED counts, corner, direction, gap, offset). */
    fun rebuild() {
        if (pipeline != 0L) Native.pipelineFree(pipeline)
        val layout = S.layout(sp)
        pipeline = Native.pipelineNew(NativeConfig.pipeline(layout, S.picture(sp), S.get(sp, S.SMOOTHING), S.get(sp, S.COLOR_ORDER)))
        count = layout.count
        out = ByteBuffer.allocateDirect(3 * count.coerceAtLeast(1))
        edge = ByteArray(count)
        along = FloatArray(count)
        if (pipeline != 0L && Native.layoutGeometry(pipeline, out) == 3 * count) {
            for (i in 0 until count) {
                edge[i] = out.get(3 * i)
                along[i] = ((out.get(3 * i + 1).toInt() and 0xff) or ((out.get(3 * i + 2).toInt() and 0xff) shl 8)) / 65536f
            }
        }
    }

    /** How many LEDs sit on each side. */
    fun topCount() = (0 until count).count { edge[it].toInt() == 0 }
    fun rightCount() = (0 until count).count { edge[it].toInt() == 1 }
    fun bottomCount() = (0 until count).count { edge[it].toInt() == 2 }
    fun leftCount() = (0 until count).count { edge[it].toInt() == 3 }

    /** An effect's colours at [tMs] into [dst] (3 × [count]); false if it can't render. */
    fun effect(id: Int, tMs: Int, params: ByteArray, dst: ByteArray): Boolean =
        fill(if (pipeline == 0L) -1 else Native.effectPreview(pipeline, id, tMs, params, out), dst)

    /** A music effect's colours at [tMs], driven by the built-in test rhythm. */
    fun music(id: Int, tMs: Int, cfg: ByteArray, dst: ByteArray): Boolean =
        fill(if (pipeline == 0L) -1 else Native.musicPreview(pipeline, id, tMs, cfg, out), dst)

    private fun fill(n: Int, dst: ByteArray): Boolean {
        if (n != 3 * count || dst.size < n) return false
        out.position(0)
        out.get(dst, 0, n)
        return true
    }

    override fun close() {
        if (pipeline != 0L) Native.pipelineFree(pipeline)
        pipeline = 0L
    }
}

/**
 * Draws LEDs round a rectangle (a TV outline) as neon: each LED a short bar just
 * outside the edge, a soft glow under a bright core, in its colour lifted to full brightness ([Theme.neon]) so it
 * reads as light rather than matt paint. Allocates nothing.
 */
object LedDraw {
    /**
     * [colours]: 3 bytes per LED in strip order (null: a dim grey). [size]: the bar's thickness. [gap]: distance
     * from the outline. [glow]: a second paint for the halo. [step]: draw every step-th LED as a longer segment (a
     * small tile can't show 184 separate dots anyway, and it's far cheaper).
     */
    fun ring(c: Canvas, r: RectF, m: LedModel, colours: ByteArray?, size: Float, gap: Float, paint: Paint, glow: Paint? = null, step: Int = 1) {
        val n = m.count
        if (n == 0) return
        var top = 0; var right = 0; var bottom = 0; var left = 0
        for (i in 0 until n) when (m.edge[i].toInt()) { 0 -> top++; 1 -> right++; 2 -> bottom++; else -> left++ }
        val halo = size * 0.9f
        val len = 0.7f + (step - 1).coerceAtLeast(0)
        for (i in 0 until n step step.coerceAtLeast(1)) {
            val colour = if (colours != null && colours.size >= 3 * n) {
                Theme.neon(colours[3 * i].toInt() and 0xff, colours[3 * i + 1].toInt() and 0xff, colours[3 * i + 2].toInt() and 0xff)
            } else Theme.DARK_LED
            val a = m.along[i]
            var x0: Float; var y0: Float; var x1: Float; var y1: Float
            when (m.edge[i].toInt()) {
                0 -> { val w = r.width() / top.coerceAtLeast(1); x0 = r.left + a * r.width(); x1 = x0 + w * len; y1 = r.top - gap; y0 = y1 - size }
                2 -> { val w = r.width() / bottom.coerceAtLeast(1); x0 = r.left + a * r.width(); x1 = x0 + w * len; y0 = r.bottom + gap; y1 = y0 + size }
                1 -> { val h = r.height() / right.coerceAtLeast(1); y0 = r.top + a * r.height(); y1 = y0 + h * len; x0 = r.right + gap; x1 = x0 + size }
                else -> { val h = r.height() / left.coerceAtLeast(1); y0 = r.top + a * r.height(); y1 = y0 + h * len; x1 = r.left - gap; x0 = x1 - size }
            }
            if (glow != null && colour != Theme.DARK_LED) { // only lit LEDs glow
                glow.color = colour
                glow.alpha = 70
                c.drawRect(x0 - halo, y0 - halo, x1 + halo, y1 + halo, glow)
            }
            paint.color = colour
            c.drawRect(x0, y0, x1, y1, paint)
        }
    }
}
