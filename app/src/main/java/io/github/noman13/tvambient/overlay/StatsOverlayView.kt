package io.github.noman13.tvambient.overlay

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import io.github.noman13.tvambient.capture.StatId
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.service.Stats
import io.github.noman13.tvambient.ui.SettingsSchema as S
import io.github.noman13.tvambient.ui.StatBit
import java.io.RandomAccessFile

/**
 * The stats panel (DESIGN.md §9, TECHNICAL.md §12.1). One Canvas view. Its content is a fixed list of items (group,
 * short label, value, stat bit); [sample] formats the switched-on items that apply to the current mode into reused
 * char buffers once per refresh, and [onDraw] lays them out as **Full** (grouped lists), **Compact** (two columns of
 * label-value pairs) or **Ticker** (two lines separated by ·). Static: no animation (§9.4). [onDraw] allocates nothing.
 *
 * [layout], [scale] and [foreground] are set by the host (the key service) from the settings.
 */
class StatsOverlayView(context: Context) : View(context) {
    var layout = 0 // 0 full, 1 compact, 2 ticker
    var scale = 1f // text size: 0.85 small, 1 medium, 1.2 large
        set(v) { field = v; sizePaints() }
    /** The app in front, from the key service (for the "App in front" stat). */
    var foreground: String? = null
    var intervalMs = 1000

    private fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * scale, resources.displayMetrics)

    private val bg = Paint().apply { color = Color.argb(191, 0x0E, 0x0F, 0x12) } // #0E0F12 at 75 %
    private val heading = mono(0xFF9AA0A9.toInt())
    private val sub = mono(0xFF7E848D.toInt())
    private val value = mono(0xFFECEDEF.toInt())
    private val warn = mono(0xFFFFB340.toInt())
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5AC8FA.toInt() }
    private val divider = Paint().apply { color = 0x33ECEDEF }
    private val infoDim = mono(0xFF9AA0A9.toInt())
    private val infoText = mono(0xFFC4C7CC.toInt())
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFECEDEF.toInt() }
    private val keyColors = intArrayOf(0xE53935, 0x43A047, 0xFDD835, 0x1E88E5)
    private val rect = RectF()
    private var radius = 0f
    private var pad = 0f

    private fun mono(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; typeface = Typeface.MONOSPACE } // a terminal look for the stats only; the rest of the app uses Inter

    private fun sizePaints() {
        heading.textSize = px(12f)
        sub.textSize = px(10.5f)
        value.textSize = px(10.5f)
        warn.textSize = px(10.5f)
        infoDim.textSize = px(9.5f)
        infoText.textSize = px(9.5f)
        ring.strokeWidth = px(2f)
        radius = px(7f)
        pad = px(9f)
        divider.strokeWidth = px(1f)
        requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------- items

    private val groups = arrayOf("Mode", "Capture", "", "", "LEDs", "", "Video", "", "", "", "Music", "", "",
        "CPU", "", "", "", "", "", "RAM", "", "", "USB", "", "Network", "")
    private val subs = arrayOf("now", "rate", "time", "path", "rate", "link", "fps", "late", "display", "app", "level", "tempo", "source",
        "app", "helper", "system", "mmdisp", "SF", "freq", "app", "helper", "free", "rate", "load", "down", "up")
    private val bits = intArrayOf(StatBit.MODE, StatBit.CAPTURE_RATE, StatBit.CAPTURE_TIME, StatBit.CAPTURE_PATH, StatBit.LED_RATE,
        StatBit.LED_RATE, StatBit.VIDEO_FPS, StatBit.LATE_FRAMES, StatBit.DISPLAY_MODE, StatBit.FOREGROUND_APP, StatBit.MUSIC,
        StatBit.MUSIC, StatBit.MUSIC, StatBit.CPU_OURS, StatBit.CPU_OURS, StatBit.CPU_SYSTEM, StatBit.CPU_DISPLAY, StatBit.CPU_DISPLAY,
        StatBit.CPU_FREQ, StatBit.MEM_OURS, StatBit.MEM_OURS, StatBit.MEM_DEVICE, StatBit.USB, StatBit.USB, StatBit.NETWORK,
        StatBit.NETWORK)
    // 0 always, 1 only while the screen is captured, 2 only in Music mode (§9.2: stats that don't apply are hidden)
    private val kinds = intArrayOf(0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val count = groups.size
    private val values = Array(count) { CharArray(48) }
    private val lengths = IntArray(count)
    private val warned = BooleanArray(count)
    private val shown = BooleanArray(count)
    private val groupIndex = IntArray(count) { k -> var i = k; while (i > 0 && groups[i].isEmpty()) i--; i }
    private var shownKey = -1

    private var header = CharArray(8)
    private var headerLen = 0
    private var pulse = false

    // App-side sampling state (helper-side stats arrive in [Stats]).
    private val memInfo = ActivityManager.MemoryInfo()
    private val am = context.getSystemService(ActivityManager::class.java)
    private val ioBuf = ByteArray(1024)
    private var lastRx = -1L
    private var lastTx = -1L
    // The /proc read and the binder calls run on their own thread, never the main one (the key service's key filter
    // shares it). sample() shows what that thread last read and asks it for fresh numbers.
    private var sampler: android.os.HandlerThread? = null
    private var samplerH: android.os.Handler? = null
    @Volatile private var bgPssKb = 0
    @Volatile private var bgAvailMb = 0
    @Volatile private var bgTotalMb = 0
    @Volatile private var bgLowMem = false
    @Volatile private var bgRx = -1L
    @Volatile private var bgTx = -1L
    private val bgSample = Runnable {
        bgPssKb = readPssKb()
        am.getMemoryInfo(memInfo)
        bgAvailMb = (memInfo.availMem shr 20).toInt()
        bgTotalMb = (memInfo.totalMem shr 20).toInt()
        bgLowMem = memInfo.lowMemory
        bgRx = TrafficStats.getTotalRxBytes()
        bgTx = TrafficStats.getTotalTxBytes()
    }

    override fun onDetachedFromWindow() {
        sampler?.quitSafely()
        sampler = null
        samplerH = null
        super.onDetachedFromWindow()
    }

    private var lastAt = SystemClock.uptimeMillis()
    private var displayText = ""

    init {
        sizePaints()
    }

    /** For the CSV: each shown item's group, label and value from the last sample. */
    /** The shown items for the CSV log, never the app in front: a log of that would be a viewing history. */
    fun forEachShown(f: (String, String, CharArray, Int) -> Unit) {
        for (k in 0 until count) if (shown[k] && k != foregroundItem) f(groups[groupIndex[k]], subs[k], values[k], lengths[k])
    }

    private var foregroundItem = -1

    /** Which items show, as a key that changes whenever the set changes (a new CSV file starts then, §12.5). */
    val shownSet get() = shownKey

    /** Reads the numbers and formats every item; call once per refresh, then the view redraws. */
    fun sample() {
        val h = samplerH ?: android.os.HandlerThread("tvambient-sampler").let { t ->
            t.start(); sampler = t; android.os.Handler(t.looper).also { samplerH = it }
        }
        h.post(bgSample) // fresh numbers for the next refresh
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastAt).coerceAtLeast(1)
        lastAt = now
        pulse = !pulse
        warned.fill(false)
        val running = AmbientService.sRunning
        val stats = running && Stats.updatedAt != 0L
        val sp = S.prefs(context)
        val mode = S.get(sp, S.MODE)
        val capturing = running && mode == 0

        // which items show: the switched-on ones that apply now; a change resizes the panel (and the LED mask)
        val mask = S.get(sp, S.STATS_MASK)
        val key = mask or (if (capturing) 1 shl 30 else 0) or (if (mode == 1) 1 shl 29 else 0)
        if (key != shownKey) {
            shownKey = key
            for (k in 0 until count) {
                val on = mask and (1 shl bits[k]) != 0
                shown[k] = on && when (kinds[k]) { 1 -> capturing; 2 -> mode == 1; else -> true }
            }
            if (shown[8]) displayText = displayMode()
            requestLayout()
        }

        headerLen = putX10(header, 0, intervalMs / 100, intervalMs % 1000 != 0)
        headerLen = put(header, headerLen, " s")

        var i = 0
        // Mode
        lengths[i] = put(values[i], 0, if (running) AmbientService.sMode else "lights off"); i++
        // Capture: rate / target; average time and max (over half the time between captures is over budget, §7 E4)
        var v = values[i]; var n = 0
        if (!stats) n = put(v, n, "-") else {
            n = putX10(v, n, Stats.value(StatId.CAPTURE_RATE_X10), true)
            val target = Stats.value(StatId.TARGET_RATE)
            n = put(v, n, " of "); n = putInt(v, n, if (target > 0) target else S.get(sp, S.CAPTURE_RATE)); n = put(v, n, " /s")
            if (S.get(sp, S.CAPTURE_ADAPTIVE) == 1) n = put(v, n, " auto")
        }
        lengths[i++] = n
        val avgUs = Stats.value(StatId.CAPTURE_US_AVG)
        v = values[i]; n = 0
        if (!stats) n = put(v, n, "-") else {
            n = putX10(v, n, avgUs / 100, true); n = put(v, n, " ms, max "); n = putInt(v, n, Stats.value(StatId.CAPTURE_US_MAX) / 1000)
            warned[i] = avgUs > 500_000 / Stats.value(StatId.TARGET_RATE).let { if (it > 0) it else S.get(sp, S.CAPTURE_RATE) }
        }
        lengths[i++] = n
        lengths[i] = put(values[i], 0, AmbientService.sCapturePath); i++
        // LEDs
        v = values[i]; n = putX10(v, 0, if (running) Stats.ledRateX10 else 0, true); n = put(v, n, " /s"); lengths[i++] = n
        warned[i] = running && AmbientService.sUsbState != "USB"
        lengths[i] = put(values[i], 0, if (!running) "-" else if (AmbientService.sUsbState == "USB") "connected" else "not connected"); i++
        // Video
        val fps = Stats.value(StatId.VIDEO_FPS_X10)
        v = values[i]; n = 0
        if (!stats || fps <= 0) n = put(v, n, "-") else { n = putX10(v, n, fps, true); n = put(v, n, " fps") }
        lengths[i++] = n
        v = values[i]; n = 0
        if (!stats || fps <= 0) n = put(v, n, "-") else {
            val late = Stats.value(StatId.LATE_FRAMES); n = putInt(v, n, late); n = put(v, n, " frames"); warned[i] = late > 0
        }
        lengths[i++] = n
        lengths[i] = put(values[i], 0, displayText); i++
        foregroundItem = i
        lengths[i] = put(values[i], 0, foreground ?: "-"); i++
        // Music
        v = values[i]; n = if (!running) put(v, 0, "-") else put(v, putInt(v, 0, Stats.musicDb), " dB"); lengths[i++] = n
        v = values[i]; n = if (!running || Stats.musicBpm == 0) put(v, 0, "-") else put(v, putInt(v, 0, Stats.musicBpm), " BPM"); lengths[i++] = n
        warned[i] = running && AmbientService.sAudioSource.startsWith("unavailable")
        lengths[i] = put(values[i], 0, AmbientService.sAudioSource); i++
        // CPU, % of one core (system: whole device)
        for (id in CPU_IDS) {
            v = values[i]; n = 0
            if (!stats) n = put(v, n, "-") else { n = putX10(v, n, Stats.value(id), true); n = put(v, n, " %") }
            lengths[i++] = n
        }
        val khz = Stats.value(StatId.CPU_FREQ_KHZ)
        v = values[i]; n = 0
        if (!stats || khz <= 0) n = put(v, n, "-") else { n = putX10(v, n, khz / 100_000, true); n = put(v, n, " GHz") }
        lengths[i++] = n
        // RAM: app PSS, helper RSS, device free of total
        v = values[i]; n = putInt(v, 0, bgPssKb / 1024); n = put(v, n, " MB"); lengths[i++] = n
        v = values[i]; n = 0
        if (!stats) n = put(v, n, "-") else { n = putInt(v, n, Stats.value(StatId.HELPER_RSS_KB) / 1024); n = put(v, n, " MB") }
        lengths[i++] = n
        v = values[i]; n = putInt(v, 0, bgAvailMb); n = put(v, n, " of "); n = putInt(v, n, bgTotalMb)
        n = put(v, n, " MB")
        warned[i] = bgLowMem
        lengths[i++] = n
        // USB: bytes/s to the controller; % of the baud rate's capacity (8N1: 10 bits per byte)
        val bytesPerS = if (running) Stats.ledRateX10.toLong() * Stats.packetBytes / 10 else 0L
        val baud = S.BAUDS[S.get(sp, S.BAUD)]
        val pct = (bytesPerS * 1000 / baud).toInt()
        v = values[i]; n = putX10(v, 0, (bytesPerS / 100).toInt(), true); n = put(v, n, " KB/s"); lengths[i++] = n
        warned[i] = pct > 90
        v = values[i]; n = putInt(v, 0, pct); n = put(v, n, " % of "); n = putInt(v, n, baud); lengths[i++] = n
        // Network: device-wide, Mb/s
        val rx = bgRx
        val tx = bgTx
        if (lastRx < 0 || rx < 0) { lastRx = rx; lastTx = tx } // the first reading: no rate yet
        v = values[i]; n = putX10(v, 0, ((rx - lastRx) * 80 / dt / 1000).toInt(), true); n = put(v, n, " Mb/s"); lengths[i++] = n
        v = values[i]; n = putX10(v, 0, ((tx - lastTx) * 80 / dt / 1000).toInt(), true); n = put(v, n, " Mb/s"); lengths[i] = n
        lastRx = rx; lastTx = tx
        // the ticker's height follows how many lines its values need now
        if (layout == 2 && tickerLines(width.toFloat()) != tickerMeasuredLines) requestLayout()
        invalidate()
    }

    private var tickerMeasuredLines = 2

    /** How many lines the ticker needs at [w] px wide (the same wrapping as [drawTicker]; no allocation). */
    private fun tickerLines(w: Float): Int {
        if (w <= 0f) return 2
        var x = pad
        var lines = 1
        val space = value.measureText(" ")
        val sep = value.measureText(" · ")
        for (k in 0 until count) {
            if (!shown[k]) continue
            val wk = sub.measureText(subs[k]) + space + value.measureText(values[k], 0, lengths[k])
            if (x + wk > w - pad && x > pad) { x = pad; lines++ }
            x += wk + sep
        }
        return lines
    }

    private fun displayMode(): String {
        val wm = context.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION") val d = wm.defaultDisplay
        val m = d.mode
        val ui = context.resources.displayMetrics
        return "${m.physicalWidth}x${m.physicalHeight} ${m.refreshRate.toInt()} Hz, UI ${ui.widthPixels}x${ui.heightPixels}"
    }

    // ---------------------------------------------------------------- layout

    private fun visibleCount(): Int {
        var n = 0
        for (k in 0 until count) if (shown[k]) n++
        return n
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ch = value.measureText("0")
        val line = value.fontSpacing
        val info = infoText.fontSpacing * 1.5f + px(8f)
        val n = visibleCount()
        when (layout) {
            1 -> { // compact: two columns of "label value"
                val rows = (n + 1) / 2
                setMeasuredDimension((2 * pad + ch * 2 * (COMPACT_COLS + 2)).toInt(), (2 * pad + heading.fontSpacing * 1.3f + line * rows + info).toInt())
            }
            2 -> { // ticker: the overscan-safe width (5 % each side), as many lines as the values need
                val w = resources.displayMetrics.widthPixels * 0.9f
                tickerMeasuredLines = tickerLines(w)
                setMeasuredDimension(w.toInt(), (2 * pad + line * (tickerMeasuredLines + 0.1f)).toInt())
            }
            else -> { // full: heading + label + value per line, groups apart
                var groupsShown = 0
                var last = -1
                for (k in 0 until count) if (shown[k] && groupIndex[k] != last) { groupsShown++; last = groupIndex[k] }
                val list = n * line + (groupsShown - 1).coerceAtLeast(0) * line * 0.35f
                setMeasuredDimension((2 * pad + heading.measureText("0") * 9 + ch * (8 + 28)).toInt(), (2 * pad + heading.fontSpacing * 1.3f + list + info).toInt())
            }
        }
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, radius, radius, bg)
        when (layout) {
            1 -> drawCompact(c)
            2 -> drawTicker(c)
            else -> drawFull(c)
        }
    }

    private fun drawHeader(c: Canvas): Float {
        val y = pad - heading.ascent()
        c.drawText("TV Ambient stats", pad, y, heading)
        val ch = heading.measureText("0")
        c.drawText(header, 0, headerLen, width - pad - ch * (headerLen + 2), y, heading)
        if (pulse) c.drawCircle(width - pad - ch * 0.5f, y + heading.ascent() * 0.35f, ch * 0.3f, dot)
        return y
    }

    private fun drawFull(c: Canvas) {
        var y = drawHeader(c) + heading.fontSpacing * 0.3f
        val subX = pad + heading.measureText("0") * 9
        val valueX = subX + value.measureText("0") * 8
        var last = -1
        for (k in 0 until count) {
            if (!shown[k]) continue
            val newGroup = groupIndex[k] != last
            if (newGroup && last >= 0) y += value.fontSpacing * 0.35f
            y += value.fontSpacing
            if (newGroup) c.drawText(groups[groupIndex[k]], pad, y, heading)
            last = groupIndex[k]
            c.drawText(subs[k], subX, y, sub)
            c.drawText(values[k], 0, lengths[k], valueX, y, if (warned[k]) warn else value)
        }
        drawInfo(c, y, subX)
    }

    private fun drawCompact(c: Canvas) {
        var y = drawHeader(c) + heading.fontSpacing * 0.3f
        val colW = (width - 2 * pad) / 2
        var col = 0
        for (k in 0 until count) {
            if (!shown[k]) continue
            if (col == 0) y += value.fontSpacing
            val x = pad + col * colW
            c.drawText(subs[k], x, y, sub)
            c.drawText(values[k], 0, minOf(lengths[k], COMPACT_COLS - 8), x + sub.measureText("0") * 8, y, if (warned[k]) warn else value)
            col = 1 - col
        }
        drawInfo(c, y, pad + sub.measureText("0") * 8) // past the "Info" label, like the values
    }

    private fun drawTicker(c: Canvas) {
        // two lines of "label value · label value …", wrapping onto the second line; no info line (it's for space)
        var x = pad
        var y = pad - value.ascent()
        val space = value.measureText(" ")
        val sep = value.measureText(" · ")
        for (k in 0 until count) {
            if (!shown[k]) continue
            val w = sub.measureText(subs[k]) + space + value.measureText(values[k], 0, lengths[k])
            if (x + w > width - pad && x > pad) {
                x = pad
                y += value.fontSpacing
            }
            c.drawText(subs[k], x, y, sub)
            x += sub.measureText(subs[k]) + space
            c.drawText(values[k], 0, lengths[k], x, y, if (warned[k]) warn else value)
            x += value.measureText(values[k], 0, lengths[k]) + sep
        }
    }

    /** "Press yellow to close" and the remote's four buttons, Yellow with a static border (DESIGN.md §9.1). */
    private fun drawInfo(c: Canvas, lastBaseline: Float, x: Float) {
        val lineY = lastBaseline + value.descent() + value.fontSpacing * 0.3f
        c.drawLine(pad, lineY, width - pad, lineY, divider)
        val y = lineY + value.fontSpacing * 0.3f - infoText.ascent()
        c.drawText("Info", pad, y, infoDim)
        c.drawText("Press yellow to close", x, y, infoText) // the words say which button: never colour alone
        val unit = infoText.measureText("0")
        val kw = unit * 2.5f
        val kh = infoText.fontSpacing * 0.45f
        val gap = unit * 0.8f
        val top = y + infoText.descent() + gap
        for (k in 0 until 4) {
            val left = x + k * (kw + gap)
            rect.set(left, top, left + kw, top + kh)
            keyPaint.color = (if (k == 2) 0xFF000000.toInt() else 0x73000000) or keyColors[k] // others at 45 %
            c.drawRoundRect(rect, kh / 2, kh / 2, keyPaint)
            if (k == 2) {
                val g = gap * 0.45f
                rect.inset(-g, -g)
                c.drawRoundRect(rect, kh / 2 + g, kh / 2 + g, ring)
            }
        }
    }

    /** `Pss:` from /proc/self/smaps_rollup (API 28+), in KB; 0 if unreadable. */
    private fun readPssKb(): Int {
        val len = try {
            RandomAccessFile("/proc/self/smaps_rollup", "r").use { it.read(ioBuf) }
        } catch (e: Exception) { return 0 }
        var at = 0
        while (at + 4 < len) {
            if (ioBuf[at] == 'P'.code.toByte() && ioBuf[at + 1] == 's'.code.toByte() && ioBuf[at + 2] == 's'.code.toByte() &&
                ioBuf[at + 3] == ':'.code.toByte()) {
                var k = at + 4
                var kb = 0
                while (k < len && ioBuf[k] == ' '.code.toByte()) k++
                while (k < len && ioBuf[k] in '0'.code.toByte()..'9'.code.toByte()) { kb = kb * 10 + (ioBuf[k] - '0'.code.toByte()); k++ }
                return kb
            }
            at++
        }
        return 0
    }

    private companion object {
        const val COMPACT_COLS = 26 // per column: an 8-character label, then up to 18 of the value ("Shell screenshots")
        val CPU_IDS = intArrayOf(StatId.APP_CPU_X10, StatId.HELPER_CPU_X10, StatId.SYSTEM_CPU_X10, StatId.MMDISP_CPU_X10, StatId.SF_CPU_X10)

        fun put(b: CharArray, at: Int, s: String): Int {
            val n = minOf(s.length, b.size - at)
            s.toCharArray(b, at, 0, n)
            return at + n
        }

        fun putInt(b: CharArray, at: Int, value: Int): Int {
            var n = at
            var v = value
            if (v < 0) { if (n < b.size) b[n++] = '-'; v = -v }
            val start = n
            do { if (n < b.size) b[n++] = '0' + v % 10; v /= 10 } while (v > 0)
            var i = start
            var j = n - 1
            while (i < j) { val t = b[i]; b[i] = b[j]; b[j] = t; i++; j-- }
            return n
        }

        /** A tenths value as "12.3" (or "12" when [decimal] is false). */
        fun putX10(b: CharArray, at: Int, x10: Int, decimal: Boolean): Int {
            var n = putInt(b, at, x10 / 10)
            if (decimal && n + 1 < b.size) { b[n++] = '.'; b[n++] = '0' + (Math.abs(x10) % 10) }
            return n
        }
    }
}
