package io.github.noman13.tvambient.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.ui.SettingsSchema as S

/**
 * The colour-tile grid (DESIGN.md §6.2, §10.2): effects shown as their colours round a small TV outline, [COLS] per
 * row, grouped under headings. One focusable view that moves its own focus with the D-pad. Every visible tile runs
 * live at 15 fps while the grid is on screen, except flashing (⚡) ones, which stay still unless
 * focused; only visible rows are drawn, and nothing runs while the grid is hidden.
 *
 * OK on a tile selects it (the LEDs cross-fade to it); OK on the selected tile opens its options in place, under the
 * tile's row; Back closes them ([back]). [music]: the music effect list instead of the effects.
 */
class TileGridView(
    context: Context, private val sp: SharedPreferences, private val model: LedModel, private val music: Boolean,
    private val onHint: (String) -> Unit,
) : View(context) {
    private class Tile(val id: Int, val name: String, val group: Int, val flashes: Boolean, val still: ByteArray) {
        /** the name shortened to the tile's width (set when the size is known) */
        var label = name
        /** a long name is squeezed a little (it condenses well) before it's ever cut */
        var squeeze = 1f
        var dirty = true // the colours changed since its part of the atlas was last filled
    }

    private val tiles = ArrayList<Tile>()
    private val groupNames = intArrayOf(R.string.grp_calm, R.string.grp_nature, R.string.grp_lively, R.string.grp_party,
        R.string.grp_seasonal, R.string.grp_setup)
    // layout: rows of up to COLS tiles; a heading row starts each group
    private class Line(val group: Int, val first: Int, val n: Int) // group >= 0: heading line; else tiles [first, first+n)
    private val lines = ArrayList<Line>()

    private var focus = 0 // tile index
    private var scrollY = 0f
    private var options = -1 // -1 closed; else the focused option row under the selected tile
    private var optEditing = false // OK pressed on a value option: Left/Right now change it
    private val live = ByteArray(3 * model.count.coerceAtLeast(1))
    private var animStart = SystemClock.uptimeMillis()
    private var frame = 0
    // each tile's parameters, read from the settings once (not per frame); an option change refreshes them.
    // (Declared before `init`, which renders the still frames.)
    private val params = HashMap<Int, ByteArray>()
    private var musicCfg: ByteArray? = null

    private val tint = if (music) Theme.NEON_BLUE else Theme.NEON_BLUE
    private val name = Theme.paint(context, Theme.TEXT, Theme.SMALL_SP, bold = true)
    private val nameDim = Theme.paint(context, Theme.TEXT_DIM, Theme.SMALL_SP)
    private val heading = Theme.paint(context, tint, Theme.SMALL_SP, bold = true, caps = true)
    private val opt = Theme.paint(context, Theme.TEXT, Theme.HINT_SP)
    private val optOn = Theme.paint(context, tint, Theme.HINT_SP, bold = true)
    private val ledGlow = Paint()
    private val glow = Theme.glowPaint(context)
    private val tv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BG }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SURFACE }
    private val cardFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SURFACE_FOCUS }
    private val ring = Theme.linePaint(context)
    private val selectedEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, 1.5f); color = Theme.NEON_GREEN }
    private val led = Paint()
    private val r = RectF()
    private val tvRect = RectF()
    private val ringRect = RectF()
    private val pixelPaint = Paint() // no filtering: each LED stays a crisp block
    // the LED ring as a bitmap: one pixel per LED, the border of a (bw × bh) bitmap; positions computed once
    // a 2-pixel-deep border: thick enough to read as a neon bar once scaled
    private val bw = maxOf(model.topCount(), model.bottomCount(), 1) + 4
    private val bh = maxOf(model.leftCount(), model.rightCount(), 1) + 4
    // all tiles' rings in one atlas bitmap (tile i at rows i·bh ..), uploaded at most once a frame: per-tile bitmaps,
    // or ~90 small rects per tile, kept this TV's RenderThread at 30-80 % of a core
    private var px = IntArray(0)
    private var atlas: android.graphics.Bitmap? = null
    private var atlasDirty = false
    private val src = android.graphics.Rect()
    private val ledPixel = IntArray(model.count).also { idx ->
        for (i in 0 until model.count) {
            val a = model.along[i]
            // the outer pixel of the LED's place; the one inside it gets the same colour (see drawPixels)
            idx[i] = when (model.edge[i].toInt()) {
                0 -> 2 + (a * (bw - 4)).toInt().coerceIn(0, bw - 5)
                2 -> (bh - 1) * bw + 2 + (a * (bw - 4)).toInt().coerceIn(0, bw - 5)
                1 -> (2 + (a * (bh - 4)).toInt().coerceIn(0, bh - 5)) * bw + bw - 1
                else -> (2 + (a * (bh - 4)).toInt().coerceIn(0, bh - 5)) * bw
            }
        }
    }

    /** Draws tile [ti]'s colours as its part of the ring atlas round [tvRect]. */
    private fun drawPixels(c: Canvas, ti: Int, colours: ByteArray, gap: Float, size: Float) {
        val t = tiles[ti]
        val bmp = atlas ?: android.graphics.Bitmap.createBitmap(bw, bh * tiles.size, android.graphics.Bitmap.Config.ARGB_8888).also {
            atlas = it
            px = IntArray(bw * bh * tiles.size)
        }
        val base = ti * bw * bh
        if (t.dirty) {
            java.util.Arrays.fill(px, base, base + bw * bh, 0)
            for (i in 0 until model.count) {
                if (colours.size < 3 * (i + 1)) break
                val col = Theme.neon(colours[3 * i].toInt() and 0xff, colours[3 * i + 1].toInt() and 0xff, colours[3 * i + 2].toInt() and 0xff)
                val o = base + ledPixel[i]
                px[o] = col
                // one pixel inwards
                px[when (model.edge[i].toInt()) { 0 -> o + bw; 2 -> o - bw; 1 -> o - 1; else -> o + 1 }] = col
            }
            t.dirty = false
            atlasDirty = true
        }
        src.set(0, ti * bh, bw, ti * bh + bh)
        // the border pixels land just outside the TV outline, about [size] thick
        val sx = (tvRect.width() + 2 * (gap + size)) / bw * 1f
        val sy = (tvRect.height() + 2 * (gap + size)) / bh * 1f
        ringRect.set(tvRect.left - gap - size, tvRect.top - gap - size, tvRect.left - gap - size + sx * bw, tvRect.top - gap - size + sy * bh)
        c.drawBitmap(bmp, src, ringRect, pixelPaint)
    }

    private val gap = Theme.dp(context, 8f)
    private val headH = Theme.dp(context, 24f)
    private val optH = Theme.dp(context, 32f)
    private val radius = Theme.dp(context, 6f)
    private val TILE_PAD = Theme.dp(context, 7f)

    private val anim = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown) return
            invalidate()
            postDelayed(this, 100) // 10 fps, only while the grid is on screen
        }
    }

    init {
        isFocusable = true
        val n = if (music) Native.musicCount() else Native.effectCount()
        for (id in 0 until n) {
            val flags = if (music) Native.musicFlags(id) else Native.effectFlags(id)
            val nm = (if (music) Native.musicName(id) else Native.effectName(id)) ?: continue
            val still = ByteArray(3 * model.count.coerceAtLeast(1))
            render(id, 1500, still)
            tiles += Tile(id, nm, if (music) -1 else (flags shr 8) and 0xff, flags and 1 != 0, still)
        }
        var i = 0
        while (i < tiles.size) {
            val g = tiles[i].group
            if (g >= 0 && (i == 0 || tiles[i - 1].group != g)) lines += Line(g, 0, 0)
            var k = 0
            while (k < COLS && i + k < tiles.size && tiles[i + k].group == g) k++
            lines += Line(-1, i, k)
            i += k
        }
        val selected = S.get(sp, if (music) S.MUSIC_EFFECT else S.EFFECT)
        focus = tiles.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        contentDescription = tiles.getOrNull(focus)?.name
    }

    private fun render(id: Int, t: Int, dst: ByteArray): Boolean =
        if (music) model.music(id, t, musicCfg ?: S.music(sp).also { musicCfg = it }, dst)
        else model.effect(id, t, params.getOrPut(id) { S.effectParams(sp, id) }, dst)

    private fun tileW() = (width - (COLS - 1) * gap) / COLS.toFloat()
    /** padding + the TV drawing + room for its bottom LEDs + the name line + padding */
    private fun tileH() = TILE_PAD * 2 + (tileW() - 2 * TILE_PAD) * TV_ASPECT + Theme.dp(context, 8f) + name.fontSpacing

    /** The y of each line's top, in content coordinates (options open under the selected tile's line). */
    private fun lineTop(li: Int): Float {
        var y = 0f
        for (k in 0 until li) y += lineHeight(k)
        return y
    }

    private fun lineHeight(li: Int): Float {
        val l = lines[li]
        if (l.group >= 0) return headH
        val base = tileH() + gap
        val open = options >= 0 && focus in l.first until l.first + l.n
        return base + if (open) optionCount() * optH + gap / 2 else 0f
    }

    private fun lineOf(tile: Int) = lines.indexOfFirst { it.group < 0 && tile in it.first until it.first + it.n }

    private fun selectedId() = S.get(sp, if (music) S.MUSIC_EFFECT else S.EFFECT)

    private fun optionCount() = if (music) 1 else 4

    override fun onDraw(c: Canvas) {
        val tw = tileW()
        val now = (SystemClock.uptimeMillis() - animStart).toInt()
        frame++
        c.save()
        c.translate(0f, -scrollY)
        var y = 0f
        for (li in lines.indices) {
            val lh = lineHeight(li)
            if (y + lh >= scrollY && y <= scrollY + height) {
                val l = lines[li]
                if (l.group >= 0) {
                    c.drawText(context.getString(groupNames[l.group.coerceIn(0, groupNames.size - 1)]).uppercase(), 0f, y + headH * 0.7f, heading)
                } else {
                    for (k in 0 until l.n) {
                        val ti = l.first + k
                        val t = tiles[ti]
                        val x = k * (tw + gap)
                        r.set(x, y, x + tw, y + tileH())
                        val focused = ti == focus && isFocused && options < 0
                        c.drawRoundRect(r, radius, radius, if (focused) cardFocus else card)
                        val pad = TILE_PAD
                        tvRect.set(x + pad, y + pad, x + tw - pad, y + pad + (tw - 2 * pad) * TV_ASPECT)
                        c.drawRoundRect(tvRect, Theme.dp(context, 3f), Theme.dp(context, 3f), tv)
                        // live: the focused tile renders every frame into the shared buffer; the others re-render
                        // at 5 fps, staggered, into their own buffer, and draw as fewer, longer segments without glow
                        //
                        val hot = ti == focus && isFocused
                        val colours = if (hot && render(t.id, now, live)) live else {
                            if (!t.flashes && (frame + ti) % 3 == 0 && render(t.id, now, t.still)) t.dirty = true
                            t.still
                        }
                        if (hot) LedDraw.ring(c, tvRect, model, colours, Theme.dp(context, 2.5f), Theme.dp(context, 1.5f), led, ledGlow)
                        else drawPixels(c, ti, colours, Theme.dp(context, 1.5f), Theme.dp(context, 2.5f))
                        val np = if (t.id == selectedId()) name else nameDim
                        np.textScaleX = t.squeeze
                        c.drawText(t.label, x + pad, tvRect.bottom + Theme.dp(context, 8f) - name.ascent(), np)
                        np.textScaleX = 1f
                        if (t.id == selectedId()) c.drawRoundRect(r, radius, radius, selectedEdge)
                        if (focused) Theme.neonRing(c, r, radius, tint, 1f, glow, ring)
                    }
                    if (options >= 0 && focus in l.first until l.first + l.n) drawOptions(c, y + tileH() + gap / 2)
                }
            }
            y += lh
        }
        c.restore()
        // one upload for every tile that changed this frame (the drawing above reads the atlas at sync time)
        if (atlasDirty) {
            atlas?.setPixels(px, 0, bw, 0, 0, bw, bh * tiles.size)
            atlasDirty = false
        }
    }

    private fun drawOptions(c: Canvas, top: Float) {
        val id = tiles[focus].id
        for (k in 0 until optionCount()) {
            val y = top + k * optH
            r.set(0f, y, width.toFloat(), y + optH - Theme.dp(context, 4f))
            c.drawRoundRect(r, radius, radius, if (k == options) cardFocus else card)
            if (k == options) Theme.neonRing(c, r, radius, tint, 1f, glow, ring)
            val (label, value) = option(k, id)
            val base = y + optH / 2 + opt.textSize / 3
            c.drawText(label, gap, base, opt)
            c.drawText(value, width - gap - opt.measureText(value), base, if (k == options && (optEditing || music || k == 3)) optOn else opt)
        }
    }

    /** Option rows: effects have speed, colour, brightness and In Blue rotation; music effects the rotation only. */
    private fun option(k: Int, id: Int): Pair<String, String> {
        val rot = if (music) S.musicInRotation(sp, id, Native.musicFlags(id) and 1 == 0) else S.effectInRotation(sp, id, Native.effectFlags(id) and 2 != 0)
        val rotation = context.getString(R.string.effect_in_rotation) to context.getString(if (rot) R.string.on else R.string.off)
        if (music) return rotation
        return when (k) {
            0 -> context.getString(R.string.effect_speed) to "◀  ${sp.getInt(S.fxSpeedKey(id), 5)}  ▶"
            1 -> context.getString(R.string.effect_hue) to "◀  ${sp.getInt(S.fxHueKey(id), 20)}°  ▶"
            2 -> context.getString(R.string.effect_bright) to "◀  ${sp.getInt(S.fxBrightKey(id), 100)} %  ▶"
            else -> rotation
        }
    }

    private fun changeOption(dir: Int) {
        val id = tiles[focus].id
        val k = if (music) 3 else options
        val e = sp.edit()
        when (k) {
            0 -> e.putInt(S.fxSpeedKey(id), (sp.getInt(S.fxSpeedKey(id), 5) + dir).coerceIn(1, 10))
            1 -> e.putInt(S.fxHueKey(id), (sp.getInt(S.fxHueKey(id), 20) + 10 * dir + 360) % 360)
            2 -> e.putInt(S.fxBrightKey(id), (sp.getInt(S.fxBrightKey(id), 100) + 5 * dir).coerceIn(5, 100))
            else -> { S.toggleRotation(sp, if (music) S.MUSIC_ROTATION_CHANGED else S.EFFECT_ROTATION_CHANGED, id); return }
        }
        e.apply()
        params.clear()
        musicCfg = null
        render(id, 1500, tiles[focus].still); tiles[focus].dirty = true
    }

    /** Back: closes the options if open. Returns true if it did. */
    fun back(): Boolean {
        if (optEditing) { optEditing = false; invalidate(); return true }
        if (options < 0) return false
        options = -1
        optEditing = false
        reveal()
        invalidate()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (options >= 0) {
            val isSwitch = music || options == 3
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (!optEditing) { if (options > 0) options-- else options = -1 }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!optEditing) options = (options + 1).coerceAtMost(optionCount() - 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (optEditing) changeOption(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (optEditing) changeOption(1)
                else -> if (ok) {
                    if (event.repeatCount == 0) { if (isSwitch) changeOption(1) else optEditing = !optEditing }
                } else return super.onKeyDown(keyCode, event)
            }
            invalidate()
            return true
        }
        val li = lineOf(focus)
        val col = focus - lines[li].first
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (col == 0) return false else focus--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (col < lines[li].n - 1) focus++
            KeyEvent.KEYCODE_DPAD_UP -> {
                var p = li - 1
                while (p >= 0 && lines[p].group >= 0) p--
                if (p < 0) return true
                focus = lines[p].first + col.coerceAtMost(lines[p].n - 1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                var nx = li + 1
                while (nx < lines.size && lines[nx].group >= 0) nx++
                if (nx >= lines.size) return false // to the row under the grid ("Reset this section")
                focus = lines[nx].first + col.coerceAtMost(lines[nx].n - 1)
            }
            else -> {
                if (!ok) return super.onKeyDown(keyCode, event)
                if (event.repeatCount > 0) return true
                val t = tiles[focus]
                if (t.id == selectedId()) {
                    options = 0
                    onHint(context.getString(R.string.effect_options_hint))
                } else select(t)
            }
        }
        animStart = SystemClock.uptimeMillis()
        contentDescription = tiles[focus].name
        reveal()
        invalidate()
        return true
    }

    private fun select(t: Tile) {
        val e = sp.edit()
        if (music) e.putInt(S.MUSIC_EFFECT.key, t.id).putInt(S.MODE.key, 1) else e.putInt(S.EFFECT.key, t.id).putInt(S.MODE.key, 2)
        e.apply()
        if (t.flashes && !sp.getBoolean(FLASH_NOTE_KEY, false)) {
            sp.edit().putBoolean(FLASH_NOTE_KEY, true).apply()
            onHint(context.getString(R.string.effect_flash_note))
        }
    }

    /** Keeps the focused line in the middle third of the view. */
    private fun reveal() {
        val li = lineOf(focus)
        val top = lineTop(li)
        val h = lineHeight(li)
        val content = lineTop(lines.size)
        val target = (top + h / 2 - height / 2f).coerceIn(0f, (content - height).coerceAtLeast(0f))
        if (top < scrollY + height / 3f || top + h > scrollY + height * 2f / 3f) scrollY = target
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) animStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(anim)
        if (isVisible) post(anim)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val room = tileW() - 2 * TILE_PAD
        for (t in tiles) {
            val full = t.name + if (t.flashes && !t.name.contains('⚡')) " ⚡" else ""
            val w = name.measureText(full)
            // squeeze up to 20 % first, so "Rainbow swirl ↻" keeps its arrow; cut only beyond that
            t.squeeze = if (w > room) (room / w).coerceAtLeast(0.8f) else 1f
            var n = full.length
            while (n > 1 && name.measureText(full, 0, n) * t.squeeze > room) n--
            t.label = if (n == full.length) full else full.substring(0, (n - 1).coerceAtLeast(1)) + "…"
        }
        reveal()
    }

    private companion object {
        const val FLASH_NOTE_KEY = "flash_note_shown"
        /** 7 small tiles a row, so many effects fit on one view */
        const val COLS = 7
        /** the preview TV's height / width: wider than 16:9, so a row is lower and more rows fit */
        const val TV_ASPECT = 0.5f
    }
}
