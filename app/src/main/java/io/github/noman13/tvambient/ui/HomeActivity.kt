package io.github.noman13.tvambient.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.service.Stats
import io.github.noman13.tvambient.ui.SettingsSchema as S

/**
 * Home (DESIGN.md §5.1): what the lights are doing and the mode. ON/OFF and the TV preview, the four mode tiles (each
 * showing its current choice) with a "settings" link under each, and Settings. The focus starts on ON/OFF. The
 * status line is the only technical text. Solid background. The power button is a drawn
 * neon power symbol (green on, red off) with Settings right under it, and a note that the app drives Adalight
 * controllers.
 */
class HomeActivity : Activity() {
    private lateinit var sp: SharedPreferences
    private lateinit var model: LedModel
    private lateinit var power: PowerButton
    private lateinit var status: TextView
    private lateinit var usb: TextView
    private val tiles = ArrayList<ModeTile>()
    private var content: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            power.on = AmbientService.sRunning
            power.invalidate()
            usb.text = if (!AmbientService.sRunning) "" else getString(if (AmbientService.sUsbState == "USB") R.string.usb_connected else R.string.usb_not_connected)
            usb.setTextColor(if (AmbientService.sUsbState == "USB") Theme.OK else Theme.WARN)
            status.text = statusLine()
            tiles.forEach { it.invalidate() }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Native.load()
        sp = S.prefs(this)
        model = LedModel(sp)
        val c = this
        val hPad = Theme.dp(c, Theme.SAFE_H_DP).toInt()
        val vPad = Theme.dp(c, Theme.SAFE_V_DP).toInt()
        val inner = Theme.dp(c, 16f).toInt()

        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            // an opaque panel inside the safe margins, over a light dim: the video stays visible round it, and
            // nothing behind (the launcher) shows through the text
            setPadding(inner, inner / 2, inner, inner / 2)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Theme.BG); cornerRadius = Theme.dp(c, 12f) }
        }
        val header = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(Theme.TITLE_SP, Theme.TEXT).apply { text = getString(R.string.app_name) }, LinearLayout.LayoutParams(0, -2, 1f))
        usb = text(Theme.HINT_SP, Theme.OK)
        header.addView(usb)
        column.addView(header)

        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // the TV UI is 960×540 dp (1080p at xhdpi): Home's parts are sized to fit in that height
        // the TV and its LEDs get most of the room; tiles and Settings sit lower
        top.addView(TvPreviewView(c, model), LinearLayout.LayoutParams(0, Theme.dp(c, 296f).toInt(), 1f))
        // the right column: the power button, Settings right under it, and what the app drives
        val side = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        power = PowerButton(c) { if (AmbientService.sRunning) AmbientService.stop(c) else AmbientService.start(c) }
        side.addView(power, LinearLayout.LayoutParams(Theme.dp(c, 150f).toInt(), Theme.dp(c, 104f).toInt()))
        val settings = Pill(c) { startActivity(Intent(c, SettingsActivity::class.java)) }.apply { text = "⚙  " + getString(R.string.settings) }
        side.addView(settings, LinearLayout.LayoutParams(Theme.dp(c, 150f).toInt(), Theme.dp(c, 36f).toInt()).apply { topMargin = Theme.dp(c, 10f).toInt() })
        side.addView(text(Theme.SMALL_SP, Theme.TEXT_DIM).apply {
            text = getString(R.string.adalight_short)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(Theme.dp(c, 200f).toInt(), -2).apply { topMargin = Theme.dp(c, 12f).toInt() })
        top.addView(side, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Theme.dp(c, 24f).toInt() })
        column.addView(top, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dp(c, 4f).toInt() })

        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val ids = arrayOf("screen", "music", "effects", "single")
        for (m in 0 until 4) {
            val cell = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val tile = ModeTile(c, m)
            tiles += tile
            cell.addView(tile, LinearLayout.LayoutParams(-1, Theme.dp(c, 58f).toInt()))
            cell.addView(LinkText(c) { SettingsActivity.open(c, ids[m]) }, LinearLayout.LayoutParams(-2, Theme.dp(c, 26f).toInt()))
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Theme.dp(c, 20f).toInt() })
        }
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dp(c, 6f).toInt() })

        val bottom = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = text(Theme.HINT_SP, Theme.TEXT_DIM).apply { gravity = Gravity.END }
        bottom.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dp(c, 6f).toInt() })

        setContentView(FrameLayout(c).apply {
            setBackgroundColor(Theme.BG)
            addView(column, FrameLayout.LayoutParams(-1, -1).apply { setMargins(hPad, vPad, hPad, vPad) })
        })
        content = column
        power.requestFocus()

        if (S.get(sp, S.FIRST_RUN_DONE) == 0) startActivity(Intent(c, FirstRunActivity::class.java))
    }

    private fun text(sizeSp: Float, colour: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(colour)
        typeface = Theme.FONT
    }

    /** "Shell screenshots · 20/s · LEDs 30/s" (DESIGN.md §5.1): the only technical text on Home. */
    private fun statusLine(): String {
        if (!AmbientService.sRunning) return getString(R.string.home_status_off)
        val mode = AmbientService.sMode
        val leds = "LEDs ${Stats.ledRateX10 / 10}/s"
        return when (S.get(sp, S.MODE)) {
            0 -> AmbientService.sCapturePath + " · ${Stats.framesInX10 / 10}/s · " + leds
            1 -> "$mode · ${AmbientService.sAudioSource} · $leds"
            else -> "$mode · $leds"
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        // screen recording needs Android's consent each time the lights start with it
        if (AmbientService.sRunning && AmbientService.sNeedProjection) startActivity(Intent(this, ProjectionRequestActivity::class.java))
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        model.close()
        super.onDestroy()
    }

    /**
     * The power button: a drawn power symbol (an open ring and a bar) in neon green when the lights are on, neon red
     * when off, with ON / OFF under it in the app font, semibold. Focus adds the glow ring. OK toggles.
     */
    private class PowerButton(c: Context, private val action: () -> Unit) : View(c) {
        var on = false
        private val t = Theme.paint(c, Theme.TEXT, Theme.HINT_SP, bold = true, caps = true)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        init { isFocusable = true }

        override fun onFocusChanged(g: Boolean, d: Int, p: android.graphics.Rect?) { super.onFocusChanged(g, d, p); invalidate() }

        override fun onDraw(c: Canvas) {
            val colour = if (on) Theme.NEON_GREEN else Theme.NEON_RED
            val label = context.getString(if (on) R.string.home_on else R.string.home_off).uppercase()
            val textH = t.fontSpacing
            val d = minOf(width.toFloat(), height - textH - Theme.dp(context, 6f))
            val cx = width / 2f
            val cy = d / 2f
            val rad = d / 2f - Theme.dp(context, 6f)
            disc.color = if (isFocused) Theme.SURFACE_FOCUS else Theme.SURFACE
            c.drawCircle(cx, cy, rad, disc)
            if (isFocused) {
                halo.color = colour; halo.alpha = 90; halo.strokeWidth = Theme.dp(context, 8f)
                c.drawCircle(cx, cy, rad, halo)
                halo.alpha = 255; halo.strokeWidth = Theme.dp(context, 2f)
                c.drawCircle(cx, cy, rad, halo)
            }
            // the symbol: a glow pass under a crisp pass
            val sr = rad * 0.48f
            r.set(cx - sr, cy - sr, cx + sr, cy + sr)
            for (pass in 0..1) {
                stroke.color = colour
                stroke.alpha = if (pass == 0) 70 else 255
                stroke.strokeWidth = Theme.dp(context, if (pass == 0) 9f else 3.5f)
                c.drawArc(r, -60f, 300f, false, stroke)
                c.drawLine(cx, cy - sr * 1.25f, cx, cy - sr * 0.2f, stroke)
            }
            t.color = colour
            c.drawText(label, cx - t.measureText(label) / 2, d + Theme.dp(context, 4f) - t.ascent(), t)
            contentDescription = context.getString(R.string.power) + ", " + label
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 0) { action(); return true }
            return super.onKeyDown(keyCode, event)
        }
    }

    /** A focusable pill button (Settings). OK runs it. */
    private class Pill(c: Context, private val action: () -> Unit) : View(c) {
        var text = ""
        var on = false
        private val t = Theme.paint(c, Theme.TEXT, Theme.LABEL_SP, bold = true)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Theme.linePaint(c)
        private val glow = Theme.glowPaint(c)
        private val r = RectF()

        init { isFocusable = true }

        override fun onFocusChanged(g: Boolean, d: Int, p: android.graphics.Rect?) { super.onFocusChanged(g, d, p); invalidate() }

        override fun onDraw(c: Canvas) {
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            r.inset(Theme.dp(context, 4f), Theme.dp(context, 4f))
            bg.color = if (on) Theme.NEON_GREEN else if (isFocused) Theme.SURFACE_FOCUS else Theme.SURFACE
            if (on) { glow.color = Theme.NEON_GREEN; glow.alpha = 80; c.drawRoundRect(r, r.height() / 2, r.height() / 2, glow) }
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
            if (isFocused) Theme.neonRing(c, r, r.height() / 2, if (on) Theme.TEXT else Theme.ACCENT, 1f, glow, ring)
            t.color = if (on) Theme.BG else Theme.TEXT
            c.drawText(text, (width - t.measureText(text)) / 2, height / 2f - (t.descent() + t.ascent()) / 2, t)
            contentDescription = text
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 0) { action(); return true }
            return super.onKeyDown(keyCode, event)
        }
    }

    /** "settings" under a mode tile: opens that mode's section directly (§4.1 rule 7). */
    private class LinkText(c: Context, private val action: () -> Unit) : View(c) {
        private val t = Theme.paint(c, Theme.TEXT_DIM, Theme.HINT_SP)
        private val f = Theme.paint(c, Theme.ACCENT, Theme.HINT_SP)
        private val label = c.getString(R.string.home_settings_link)

        init { isFocusable = true; contentDescription = label }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((t.measureText(label) + Theme.dp(context, 24f)).toInt(), MeasureSpec.getSize(h))

        override fun onFocusChanged(g: Boolean, d: Int, p: android.graphics.Rect?) { super.onFocusChanged(g, d, p); invalidate() }

        override fun onDraw(c: Canvas) {
            val p = if (isFocused) f else t
            c.drawText(label, Theme.dp(context, 12f), height / 2f - (p.descent() + p.ascent()) / 2, p)
            if (isFocused) c.drawLine(Theme.dp(context, 12f), height * 0.8f, width - Theme.dp(context, 12f), height * 0.8f, f)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 0) { action(); return true }
            return super.onKeyDown(keyCode, event)
        }
    }

    /** A mode tile: its name, its current choice, and a tick when it's the mode in use. OK picks the mode. */
    private inner class ModeTile(c: Context, private val mode: Int) : View(c) {
        private val name = Theme.paint(c, Theme.TEXT, Theme.LABEL_SP, bold = true)
        private val choice = Theme.paint(c, Theme.TEXT_DIM, Theme.HINT_SP)
        private val tick = Theme.paint(c, Theme.ACCENT, Theme.LABEL_SP, bold = true)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Theme.linePaint(c)
        private val glow = Theme.glowPaint(c)
        private val tileColour = intArrayOf(Theme.NEON_BLUE, Theme.NEON_PURPLE, Theme.NEON_GREEN, Theme.NEON_YELLOW)[mode]
        private val swatch = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val hsv = FloatArray(3)
        private val labels = intArrayOf(R.string.mode_screen, R.string.mode_music, R.string.mode_effect, R.string.mode_single)

        init { isFocusable = true }

        override fun onFocusChanged(g: Boolean, d: Int, p: android.graphics.Rect?) { super.onFocusChanged(g, d, p); invalidate() }

        override fun onDraw(c: Canvas) {
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            r.inset(Theme.dp(context, 3f), Theme.dp(context, 3f))
            bg.color = if (isFocused) Theme.SURFACE_FOCUS else Theme.SURFACE
            c.drawRoundRect(r, Theme.dp(context, 8f), Theme.dp(context, 8f), bg)
            if (isFocused) Theme.neonRing(c, r, Theme.dp(context, 8f), tileColour, 1f, glow, ring)
            name.color = tileColour
            val pad = Theme.dp(context, 10f)
            c.drawText(getString(labels[mode]), pad, pad - name.ascent(), name)
            val current = S.get(sp, S.MODE) == mode
            if (current) c.drawText("✓", width - pad - tick.measureText("✓"), pad - tick.ascent(), tick)
            val sub = when (mode) {
                1 -> Native.musicName(S.get(sp, S.MUSIC_EFFECT)) ?: ""
                2 -> Native.effectName(S.get(sp, S.EFFECT)) ?: ""
                3 -> "${S.get(sp, S.SINGLE_HUE)}°"
                else -> ""
            }
            var x = pad
            if (mode == 3) {
                hsv[0] = S.get(sp, S.SINGLE_HUE).toFloat(); hsv[1] = S.get(sp, S.SINGLE_SAT) / 100f; hsv[2] = 1f
                swatch.color = android.graphics.Color.HSVToColor(hsv)
                val s = choice.textSize
                c.drawRect(x, height - pad - s, x + s, height - pad, swatch)
                x += s * 1.6f
            }
            c.drawText(sub, x, height - pad, choice)
            contentDescription = getString(labels[mode]) + " " + sub + if (current) ", " + getString(R.string.selected) else ""
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 0) {
                sp.edit().putInt(S.MODE.key, mode).apply()
                if (!AmbientService.sRunning) AmbientService.start(context)
                tiles.forEach { it.invalidate() }
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
    }
}
