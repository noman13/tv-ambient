package io.github.noman13.tvambient

/**
 * Settings as the byte layouts in `core/src/config.rs` (version byte first, little-endian). Keep both in step:
 * a change here without one there (or the other way round) makes the native side reject the settings (−3).
 */
object NativeConfig {
    private const val VERSION: Byte = 1

    class Layout(
        val top: Int = 56, val right: Int = 36, val bottom: Int = 56, val left: Int = 36,
        val bottomGap: Int = 0, val startCorner: Int = 0, val counterClockwise: Boolean = false, val offset: Int = 0,
        val depthTopBottomPct: Int = 10, val depthSidesPct: Int = 8,
    ) {
        val count get() = top + right + bottom + left
    }

    class Picture(
        val brightnessPct: Int = 35, val saturationPct: Int = 150, val gammaX10: Int = 19,
        val balanceR: Int = 100, val balanceG: Int = 100, val balanceB: Int = 100, val black: Int = 5,
    )

    class Analyzer(
        val compat13: Boolean = false, val bars: Boolean = true, val blackLuma: Int = 24, val barLinePct: Int = 97,
        val hysteresis: Int = 6, val sampleStep: Int = 2, val darkReachPct: Int = 0,
    )

    private class Writer(size: Int) {
        val bytes = ByteArray(size)
        var at = 0
        fun u8(v: Int) { bytes[at++] = v.toByte() }
        fun u16(v: Int) { bytes[at++] = v.toByte(); bytes[at++] = (v shr 8).toByte() }
        fun bool(v: Boolean) = u8(if (v) 1 else 0)
    }

    private fun Writer.layout(l: Layout) {
        u8(VERSION.toInt())
        u16(l.top); u16(l.right); u16(l.bottom); u16(l.left); u16(l.bottomGap)
        u8(l.startCorner); bool(l.counterClockwise); u16(l.offset)
        u8(l.depthTopBottomPct); u8(l.depthSidesPct)
    }

    private fun Writer.picture(p: Picture) {
        u8(VERSION.toInt())
        u8(p.brightnessPct); u16(p.saturationPct); u8(p.gammaX10)
        u8(p.balanceR); u8(p.balanceG); u8(p.balanceB); u8(p.black)
    }

    /** `config::analyzer`: layout (17) + analyser (7). */
    fun analyzer(l: Layout, a: Analyzer): ByteArray = Writer(24).run {
        layout(l)
        bool(a.compat13); bool(a.bars); u8(a.blackLuma); u8(a.barLinePct); u8(a.hysteresis); u8(a.sampleStep); u8(a.darkReachPct)
        bytes
    }

    /** `config::picture` (9). */
    fun picture(p: Picture): ByteArray = Writer(9).run { picture(p); bytes }

    /** `config::pipeline`: layout (17) + picture (9) + smoothing + colour order. */
    fun pipeline(l: Layout, p: Picture, smoothing: Int, colorOrder: Int): ByteArray = Writer(28).run {
        layout(l); picture(p); u8(smoothing); u8(colorOrder)
        bytes
    }

    /** `config::params` (6): effect and single-colour settings. */
    fun params(speed: Int = 5, hue: Int = 20, satPct: Int = 80, brightnessPct: Int = 100): ByteArray = Writer(6).run {
        u8(VERSION.toInt()); u8(speed); u16(hue); u8(satPct); u8(brightnessPct)
        bytes
    }

    /** `config::music` (12). */
    fun music(effect: Int, palette: Int, hue: Int, followLoudness: Boolean, minBrightnessPct: Int, beatResponsePct: Int,
              fadeMs: Int, delayMs: Int): ByteArray = Writer(12).run {
        u8(VERSION.toInt()); u8(effect); u8(palette); u16(hue); bool(followLoudness); u8(minBrightnessPct)
        u8(beatResponsePct); u16(fadeMs); u16(delayMs and 0xffff)
        bytes
    }

    /** `config::audio` (6). */
    fun audio(sampleRate: Int = 48_000, sensitivity: Int = 0, range: Int = 0, beatKx10: Int = 15): ByteArray = Writer(6).run {
        u8(VERSION.toInt()); u16(sampleRate / 100); u8(sensitivity); u8(range); u8(beatKx10)
        bytes
    }

    /** The analyser settings as hex, for the capture helper's command line. */
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
