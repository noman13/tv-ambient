package io.github.noman13.tvambient.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue

/**
 * DESIGN.md §7: the visual tokens, in one place. Dark and lively: near-black panels,
 * the classic LED colours as neon accents (one per settings group), a glowing focus ring, and Inter as the app font
 * (SIL OFL 1.1). No scale-on-focus.
 */
object Theme {
    const val BG = 0xFF0B0D10.toInt()
    const val SURFACE = 0xFF14171C.toInt()
    const val SURFACE_FOCUS = 0xFF1C2129.toInt()
    /** The thin light border of the Settings breadcrumb bar */
    const val BAR_BORDER = 0x55E8ECF1
    const val TEXT = 0xFFE8ECF1.toInt()
    const val TEXT_DIM = 0xFF8A93A0.toInt()
    const val TEXT_OFF = 0xFF4E5560.toInt() // a row that doesn't apply right now
    const val WARN = 0xFFFFB340.toInt()
    const val OK = 0xFF36FF8A.toInt()
    /** An unlit LED in the previews (no glow) */
    const val DARK_LED = 0xFF22272E.toInt()

    // the classic LED colours as neon accents (the icon's four bars)
    const val NEON_BLUE = 0xFF36C2FF.toInt()
    const val NEON_YELLOW = 0xFFE0B000.toInt() // a dark yellow: the Look group and Single colour
    const val NEON_GREEN = 0xFF36FF8A.toInt()
    const val NEON_PURPLE = 0xFFB07CFF.toInt() // the classic #8A5CFF, lifted for contrast on near-black
    const val NEON_RED = 0xFFFF3B5C.toInt() // the power button when the lights are off
    const val ACCENT = NEON_BLUE

    /** Each settings group's neon colour: its heading, its values and its focus ring. */
    fun groupColour(groupLabel: Int): Int = when (groupLabel) {
        io.github.noman13.tvambient.R.string.group_modes -> NEON_BLUE
        io.github.noman13.tvambient.R.string.group_look -> NEON_YELLOW
        io.github.noman13.tvambient.R.string.group_setup -> NEON_GREEN
        else -> NEON_PURPLE
    }

    // type sizes, sp: small and tidy (the TV's UI is 960×540 dp)
    const val TITLE_SP = 22f
    const val LABEL_SP = 16f
    const val HINT_SP = 12f
    const val SMALL_SP = 11f

    const val ROW_DP = 40f
    const val FOCUS_RING_DP = 2f
    const val FOCUS_FADE_MS = 120L

    // overscan-safe margins
    const val SAFE_H_DP = 48f
    const val SAFE_V_DP = 27f

    /** The remote's colour-button colours (dots on the Remote buttons rows, keys in the stats panel). */
    val KEY_COLOURS = intArrayOf(0xFFFF3B3B.toInt(), NEON_GREEN, 0xFFFFE03B.toInt(), NEON_BLUE)

    // The app font: Inter (SIL OFL 1.1, LICENSES/Inter-OFL-1.1.txt), Regular and SemiBold, subset to Latin and the
    // symbols the app shows; a glyph Inter lacks (▾ ⚙ ⚡) falls back to the system font. Loaded once by [init].
    @Volatile private var regular: Typeface? = null
    @Volatile private var semiBold: Typeface? = null

    /** Loads the app font (cheap after the first call). Activities and services call it before building views. */
    fun init(c: Context) {
        if (regular != null) return
        val r = c.applicationContext.resources
        semiBold = runCatching { r.getFont(io.github.noman13.tvambient.R.font.inter_semibold) }.getOrNull()
        regular = runCatching { r.getFont(io.github.noman13.tvambient.R.font.inter_regular) }.getOrNull()
    }

    val FONT: Typeface get() = regular ?: Typeface.DEFAULT
    val FONT_BOLD: Typeface get() = semiBold ?: Typeface.DEFAULT_BOLD

    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
    fun sp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.resources.displayMetrics)

    /** A text paint in the app font; [caps]: uppercase headings get letter spacing. */
    fun paint(c: Context, colour: Int, sizeSp: Float, bold: Boolean = false, caps: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        init(c)
        color = colour
        textSize = sp(c, sizeSp)
        typeface = if (bold) FONT_BOLD else FONT
        fontFeatureSettings = "tnum" // figures line up in values and stats
        if (caps) letterSpacing = 0.12f
    }

    /** A neon focus ring: a soft wide glow under a crisp line, both in [colour]. */
    fun neonRing(c: Canvas, r: RectF, radius: Float, colour: Int, level: Float, glow: Paint, line: Paint) {
        if (level <= 0f) return
        glow.color = colour
        glow.alpha = (70 * level).toInt()
        c.drawRoundRect(r, radius, radius, glow)
        line.color = colour
        line.alpha = (255 * level).toInt()
        c.drawRoundRect(r, radius, radius, line)
    }

    fun glowPaint(c: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(c, 7f) }
    fun linePaint(c: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(c, FOCUS_RING_DP) }

    /**
     * A colour as the LEDs show it, made bright for the screen: the strip's colours are dimmed by its brightness
     * setting (35 %), which looks matt on a screen, so previews lift each colour to full brightness keeping its
     * hue; near-black stays dark.
     */
    fun neon(r: Int, g: Int, b: Int): Int {
        val m = maxOf(r, g, b)
        if (m < 6) return DARK_LED
        val k = 255f / m
        return (0xff shl 24) or ((r * k).toInt().coerceAtMost(255) shl 16) or ((g * k).toInt().coerceAtMost(255) shl 8) or (b * k).toInt().coerceAtMost(255)
    }
}
