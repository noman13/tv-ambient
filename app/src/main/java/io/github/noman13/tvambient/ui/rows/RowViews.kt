package io.github.noman13.tvambient.ui.rows

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.ui.SettingsSchema as S
import io.github.noman13.tvambient.ui.Theme

/** What a row asks of the Settings screen: open a sub-page, run an action, open a choice list. */
interface RowHost {
    /** The current section's neon colour (DESIGN.md §7): values, focus ring, key caps. */
    val tint: Int
    fun openPage(page: String)
    fun runAction(action: String)
    fun openChoices(row: ChoiceRowView)
    fun rowChanged(row: SettingRow)
}

/**
 * The base of the row views (DESIGN.md §4.2, §12). Navigation: Up/Down move between
 * rows and **Left always goes back to the section list**; a value only changes after **OK starts editing it**
 * (Left/Right then change it, OK or Back stops). Focus is a neon ring in the section's colour; a row that doesn't
 * apply is dimmed. Drawn on a Canvas.
 */
abstract class SettingRow(context: Context, val row: Row, protected val sp: SharedPreferences, protected val host: RowHost) : View(context) {
    protected val label = Theme.paint(context, Theme.TEXT, Theme.LABEL_SP)
    protected val value = Theme.paint(context, Theme.TEXT, Theme.LABEL_SP, bold = true)
    protected val dim = Theme.paint(context, Theme.TEXT_OFF, Theme.LABEL_SP)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Theme.glowPaint(context)
    private val line = Theme.linePaint(context)
    protected val padH = Theme.dp(context, 14f)
    protected val radius = Theme.dp(context, 6f)
    private val rect = RectF()
    private var focusAt = 0L

    /** OK pressed on a value row: Left/Right now change it. */
    var editing = false
        protected set

    /** The string resource saying why this row is off right now, or null. */
    val disabledReason: Int? get() = row.disabledReason(sp)

    init {
        isFocusable = row.focusable
        minimumHeight = Theme.dp(context, Theme.ROW_DP).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minimumHeight)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) editing = false
        focusAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val t = ((SystemClock.uptimeMillis() - focusAt).toFloat() / Theme.FOCUS_FADE_MS).coerceIn(0f, 1f)
        val level = if (isFocused) t else 0f
        if (t < 1f) postInvalidateOnAnimation()
        val off = disabledReason != null
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = if (editing) blend(Theme.SURFACE_FOCUS, host.tint, 0.18f) else blend(Theme.SURFACE, Theme.SURFACE_FOCUS, level)
        c.drawRoundRect(rect, radius, radius, bg)
        val inset = Theme.dp(context, 1.5f)
        rect.inset(inset, inset)
        Theme.neonRing(c, rect, radius, host.tint, level, glow, line)
        val base = baseline()
        label.color = if (off) Theme.TEXT_OFF else Theme.TEXT
        drawLabel(c, base)
        drawValue(c, base, off)
    }

    protected open fun drawLabel(c: Canvas, base: Float) {
        c.drawText(context.getString(row.label), padH, base, label)
    }

    protected fun baseline() = height / 2f - (label.descent() + label.ascent()) / 2

    protected abstract fun drawValue(c: Canvas, base: Float, off: Boolean)

    /** Right-aligned text at the row's end. */
    protected fun drawRight(c: Canvas, text: String, base: Float, p: Paint, endInset: Float = 0f) {
        c.drawText(text, width - padH - endInset - p.measureText(text), base, p)
    }

    protected fun changed() {
        invalidate()
        host.rowChanged(this)
    }

    /** Back while editing stops editing (the Settings screen asks first). */
    fun stopEditing(): Boolean {
        if (!editing) return false
        editing = false
        invalidate()
        host.rowChanged(this)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (disabledReason != null) return keyCode != KeyEvent.KEYCODE_DPAD_LEFT && super.onKeyDown(keyCode, event)
        return handleKey(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    /** True if handled. Left is not handled unless editing, so it returns to the section list. */
    protected abstract fun handleKey(keyCode: Int, event: KeyEvent): Boolean

    protected fun isOk(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t).toInt() shl s
            return (0xff shl 24) or ch(16) or ch(8) or ch(0)
        }

        fun create(context: Context, row: Row, sp: SharedPreferences, host: RowHost): View = when (row) {
            is ValueRow -> ValueRowView(context, row, sp, host)
            is SwitchRow -> SwitchRowView(context, row, sp, host)
            is FlagRow -> FlagRowView(context, row, sp, host)
            is SystemSwitchRow -> SystemSwitchRowView(context, row, sp, host)
            is ChoiceRow -> ChoiceRowView(context, row, sp, host)
            is LinkRow -> LinkRowView(context, row, sp, host)
            is ActionRow -> ActionRowView(context, row, sp, host)
            is HeadingRow, is InfoRow, is MoreRow -> PlainRowView(context, row, sp, host)
        }
    }
}

/**
 * Two small key caps, ◀ and ▶, in the spirit of Android Ambilight's − and + buttons (our own drawing): rounded
 * squares with a light top edge and a dark bottom edge. Lit in the section's colour while the row is being edited.
 */
internal class KeyCaps(context: Context) {
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 1f) }
    private val glyph = Theme.paint(context, Theme.TEXT, 11f, bold = true)
    private val r = RectF()
    val size = Theme.dp(context, 24f)
    val gap = Theme.dp(context, 6f)
    private val radius = Theme.dp(context, 5f)

    /** Draws both caps ending at [right], centred on [cy]; returns the left edge. */
    fun draw(c: Canvas, right: Float, cy: Float, lit: Boolean, tint: Int, off: Boolean): Float {
        val left = right - 2 * size - gap
        for (k in 0..1) {
            val x = left + k * (size + gap)
            r.set(x, cy - size / 2, x + size, cy + size / 2)
            cap.color = if (lit) tint else 0xFF20252D.toInt()
            if (lit) cap.alpha = 230
            c.drawRoundRect(r, radius, radius, cap)
            edge.color = 0x40FFFFFF
            c.drawLine(r.left + radius, r.top + 0.5f, r.right - radius, r.top + 0.5f, edge) // light top edge
            edge.color = 0x80000000.toInt()
            c.drawLine(r.left + radius, r.bottom - 0.5f, r.right - radius, r.bottom - 0.5f, edge) // dark bottom edge
            glyph.color = if (off) Theme.TEXT_OFF else if (lit) Theme.BG else Theme.TEXT_DIM
            val g = if (k == 0) "◀" else "▶"
            c.drawText(g, r.centerX() - glyph.measureText(g) / 2, r.centerY() - (glyph.descent() + glyph.ascent()) / 2, glyph)
        }
        return left
    }
}

/** `Brightness   35 %  [◀][▶]`: OK starts editing, Left/Right change it (faster after 400 ms held), OK or Back stops. */
class ValueRowView(context: Context, private val r: ValueRow, sp: SharedPreferences, host: RowHost) : SettingRow(context, r, sp, host) {
    private val caps = KeyCaps(context)
    private var downAt = 0L

    override fun drawValue(c: Canvas, base: Float, off: Boolean) {
        val v = S.get(sp, r.def)
        val left = caps.draw(c, width - padH, height / 2f, editing, host.tint, off)
        value.color = if (off) Theme.TEXT_OFF else host.tint
        val text = r.format(context, v)
        c.drawText(text, left - Theme.dp(context, 12f) - value.measureText(text), base, value)
        contentDescription = context.getString(r.label) + " " + text + if (editing) ", editing" else ""
    }

    override fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            if (event.repeatCount == 0) { editing = !editing; changed() }
            return true
        }
        if (!editing) return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT // Left goes back to the list
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true // stay on the row while editing
            else -> return false
        }
        if (event.repeatCount == 0) downAt = SystemClock.uptimeMillis()
        val held = SystemClock.uptimeMillis() - downAt
        val mult = when {
            held < 400 -> 1
            held < 1500 -> 5
            else -> 10
        }
        val v = S.get(sp, r.def)
        val nv = (v + dir * r.step * mult).coerceIn(r.def.min, r.def.max)
        if (nv != v) {
            sp.edit().putInt(r.def.key, nv).apply()
            changed()
        }
        return true
    }
}

/** A lit cap: `Start on boot   [ ON ]`. OK toggles. */
open class SwitchRowView(context: Context, private val r: Row, sp: SharedPreferences, host: RowHost) : SettingRow(context, r, sp, host) {
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 5f) }
    private val capText = Theme.paint(context, Theme.TEXT, 12f, bold = true, caps = true)
    private val cr = RectF()

    protected open fun on(): Boolean = (S.get(sp, (r as SwitchRow).def) == 1) != r.inverted
    protected open fun toggle(): Boolean {
        val d = (r as SwitchRow).def
        sp.edit().putInt(d.key, 1 - S.get(sp, d)).apply()
        return true
    }

    override fun drawValue(c: Canvas, base: Float, off: Boolean) {
        val isOn = on()
        val w = Theme.dp(context, 52f)
        val h = Theme.dp(context, 22f)
        cr.set(width - padH - w, height / 2f - h / 2, width - padH, height / 2f + h / 2)
        if (isOn && !off) {
            capGlow.color = host.tint
            capGlow.alpha = 70
            c.drawRoundRect(cr, h / 2, h / 2, capGlow)
        }
        capPaint.color = if (off) 0xFF20252D.toInt() else if (isOn) host.tint else 0xFF20252D.toInt()
        c.drawRoundRect(cr, h / 2, h / 2, capPaint)
        val t = context.getString(if (isOn) R.string.on else R.string.off).uppercase()
        capText.color = if (off) Theme.TEXT_OFF else if (isOn) Theme.BG else Theme.TEXT_DIM
        c.drawText(t, cr.centerX() - capText.measureText(t) / 2, cr.centerY() - (capText.descent() + capText.ascent()) / 2, capText)
        contentDescription = context.getString(r.label) + " " + t
    }

    override fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (!isOk(keyCode)) return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.repeatCount > 0) return true
        if (toggle()) changed()
        return true
    }
}

/** A switch on one bit of a bitmask setting. */
class FlagRowView(context: Context, private val f: FlagRow, sp: SharedPreferences, host: RowHost) : SwitchRowView(context, f, sp, host) {
    override fun on() = S.get(sp, f.def) and (1 shl f.bit) != 0
    override fun toggle(): Boolean {
        val v = S.get(sp, f.def) xor (1 shl f.bit)
        if (f.keepOne && v == 0) return false
        sp.edit().putInt(f.def.key, v).apply()
        return true
    }
}

/** A switch over a system setting: shows it, and OK asks the host to change it (the change arrives asynchronously). */
class SystemSwitchRowView(context: Context, private val s: SystemSwitchRow, sp: SharedPreferences, host: RowHost) : SwitchRowView(context, s, sp, host) {
    override fun on() = s.isOn(context)
    override fun toggle(): Boolean {
        host.runAction(s.action)
        return false
    }
}

/** `Audio source   Automatic ▾`: OK opens the list in place. */
class ChoiceRowView(context: Context, val r: ChoiceRow, sp: SharedPreferences, host: RowHost) : SettingRow(context, r, sp, host) {
    val index get() = r.indexOf(S.get(sp, r.def))

    fun pick(i: Int) {
        val v = r.valueAt(i.coerceIn(0, r.choices.size - 1))
        if (v != S.get(sp, r.def)) {
            sp.edit().putInt(r.def.key, v).apply()
            changed()
        }
    }

    override fun drawValue(c: Canvas, base: Float, off: Boolean) {
        value.color = if (off) Theme.TEXT_OFF else host.tint
        drawRight(c, context.getString(r.choices[index]) + " ▾", base, value)
        contentDescription = context.getString(r.label) + " " + context.getString(r.choices[index])
    }

    override fun handleKey(keyCode: Int, event: KeyEvent): Boolean = when {
        isOk(keyCode) -> { if (event.repeatCount == 0) host.openChoices(this); true }
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }
}

/** `Music effect   Spectrum ›`: OK or Right opens the sub-page. */
class LinkRowView(context: Context, private val r: LinkRow, sp: SharedPreferences, host: RowHost) : SettingRow(context, r, sp, host) {
    override fun drawValue(c: Canvas, base: Float, off: Boolean) {
        val v = r.value(context, sp)
        value.color = if (off) Theme.TEXT_OFF else host.tint
        drawRight(c, if (v.isEmpty()) "›" else "$v ›", base, value)
        contentDescription = context.getString(r.label) + " " + v
    }

    override fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (!isOk(keyCode) && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        if (event.repeatCount == 0) host.openPage(r.page)
        return true
    }
}

/** `[ Test pattern ]`: OK runs it. A row marked [ActionRow.twice] asks for a second OK first ("Reset this section"). */
class ActionRowView(context: Context, private val r: ActionRow, sp: SharedPreferences, host: RowHost) : SettingRow(context, r, sp, host) {
    private var armedAt = 0L
    private val armedPaint = Theme.paint(context, Theme.WARN, Theme.LABEL_SP, bold = true)

    override fun drawLabel(c: Canvas, base: Float) {
        val armed = SystemClock.uptimeMillis() - armedAt < ARM_MS
        val text = if (armed) context.getString(R.string.press_ok_again) else "> " + context.getString(r.label)
        c.drawText(text, padH, base, if (armed) armedPaint else label)
        contentDescription = text
    }

    override fun drawValue(c: Canvas, base: Float, off: Boolean) {}

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!gainFocus) armedAt = 0L
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (!isOk(keyCode)) return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.repeatCount > 0) return true
        if (r.twice && SystemClock.uptimeMillis() - armedAt >= ARM_MS) {
            armedAt = SystemClock.uptimeMillis()
            invalidate()
            postDelayed({ invalidate() }, ARM_MS)
            return true
        }
        armedAt = 0L
        invalidate()
        host.runAction(r.action)
        return true
    }

    private companion object {
        const val ARM_MS = 3000L
    }
}

/** Headings, info lines and the "More" divider: not focusable. Headings are uppercase, spaced, in the section's neon. */
class PlainRowView(context: Context, private val r: Row, private val sp: SharedPreferences, private val host: RowHost) : View(context) {
    private val heading = Theme.paint(context, Theme.TEXT_DIM, Theme.SMALL_SP, bold = true, caps = true)
    private val info = Theme.paint(context, Theme.TEXT_DIM, Theme.HINT_SP)
    private val line = Paint().apply { color = 0x26E8ECF1; strokeWidth = Theme.dp(context, 1f) }
    private val padH = Theme.dp(context, 14f)

    init {
        isFocusable = false
        minimumHeight = Theme.dp(context, if (r is InfoRow) 28f else 26f).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minimumHeight)
    }

    override fun onDraw(c: Canvas) {
        val base = height * 0.72f
        when (r) {
            is HeadingRow -> { heading.color = host.tint; c.drawText(context.getString(r.label).uppercase(), padH, base, heading) }
            is InfoRow -> c.drawText(context.getString(r.label) + " · " + r.value(context, sp), padH, base, info)
            is MoreRow -> {
                heading.color = Theme.TEXT_DIM
                val t = context.getString(R.string.more).uppercase()
                c.drawText(t, padH, base, heading)
                val x = padH * 1.5f + heading.measureText(t)
                c.drawLine(x, base - heading.textSize / 3, width - padH, base - heading.textSize / 3, line)
            }
            else -> {}
        }
    }
}
