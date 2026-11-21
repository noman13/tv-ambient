package io.github.noman13.tvambient.ui

import android.app.Activity
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
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.adb.Shell
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.service.Stats
import io.github.noman13.tvambient.ui.SettingsSchema as S
import io.github.noman13.tvambient.ui.rows.ChoiceRowView
import io.github.noman13.tvambient.ui.rows.FlagRow
import io.github.noman13.tvambient.ui.rows.Row
import io.github.noman13.tvambient.ui.rows.RowHost
import io.github.noman13.tvambient.ui.rows.Section
import io.github.noman13.tvambient.ui.rows.SettingRow
import io.github.noman13.tvambient.ui.rows.ValueRow

/**
 * Settings (DESIGN.md §4, §5.2): two panes, the section list on the left and its rows on the right, generated from
 * [SettingsSections]. Every change shows on the LEDs at once. Focus is remembered per section; changes apply at once.
 */
class SettingsActivity : Activity(), RowHost, SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var sp: SharedPreferences
    private lateinit var model: LedModel
    private val sections = SettingsSections.all()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var title: TextView
    private lateinit var list: LinearLayout
    private lateinit var listScroll: ScrollView
    private val items = ArrayList<SectionItemView>()
    private lateinit var pane: FrameLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var rowsBox: LinearLayout
    private lateinit var extra: FrameLayout
    private lateinit var hint: TextView
    private lateinit var frame: StripFrameView
    private var grid: TileGridView? = null
    private var choices: ChoiceListView? = null
    private var tools: AudioTools? = null

    private var current = 0
    private val pages = ArrayList<String>() // sub-page stack inside the current section
    private var hintOverride: String? = null

    private val refresh = object : Runnable {
        override fun run() {
            // the few live lines: the key test, the music status, info rows
            extra.getChildAt(0)?.invalidate()
            for (i in 0 until rowsBox.childCount) rowsBox.getChildAt(i).let { if (it !is SettingRow) it.invalidate() }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Native.load()
        sp = S.prefs(this)
        model = LedModel(sp)
        current = sLastSection.coerceIn(0, sections.size - 1)
        buildLayout()
        // recreated while another screen covered it (first run, a system page; the TV has little memory): back on
        // the row the person left, not the section list
        val inPane = savedInstanceState?.getBoolean(STATE_IN_PANE) == true
        showSection(current, focusRows = inPane)
        if (!inPane) items[current].requestFocus()
        // the current section in view in the list, also when it's a lower one and focus is in the pane
        listScroll.post { listScroll.scrollTo(0, (items[current].top - listScroll.height / 3).coerceAtLeast(0)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IN_PANE, pages.isEmpty() && focusInPane(currentFocus))
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout() {
        val hPad = Theme.dp(this, Theme.SAFE_H_DP).toInt()
        val vPad = Theme.dp(this, Theme.SAFE_V_DP).toInt()
        // a solid background; the content sits inside the safe margins, so the strip
        // drawing's dots run in that margin without crossing any text
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        frame = StripFrameView(this, model, sp).apply { visibility = View.GONE }
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = Theme.dp(this@SettingsActivity, 20f).toInt()
            setPadding(inner, inner, inner, inner)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Theme.BG)
                cornerRadius = Theme.dp(this@SettingsActivity, 12f)
            }
        }
        // the breadcrumb bar stands out: a lighter surface with a thin light border
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = Theme.dp(this@SettingsActivity, 16f).toInt()
            val v = Theme.dp(this@SettingsActivity, 6f).toInt()
            setPadding(h, v, h, v)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Theme.SURFACE)
                cornerRadius = Theme.dp(this@SettingsActivity, 10f)
                setStroke(Theme.dp(this@SettingsActivity, 1f).toInt(), Theme.BAR_BORDER)
            }
        }
        title = text(Theme.TITLE_SP - 2f, Theme.TEXT).apply {
            // one line: a long path loses its start, never the page you're on
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(PreviewStripView(this))
        column.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; fade(this); addView(list) }
        body.addView(listScroll, LinearLayout.LayoutParams(0, -1, 0.28f).apply { topMargin = Theme.dp(this@SettingsActivity, 10f).toInt() })
        var lastGroup = 0
        sections.forEachIndexed { i, s ->
            if (s.group != lastGroup) {
                lastGroup = s.group
                list.addView(text(Theme.SMALL_SP, Theme.groupColour(s.group)).apply {
                    text = getString(s.group).uppercase()
                    letterSpacing = 0.15f
                    typeface = Theme.FONT_BOLD
                    setPadding(Theme.dp(this@SettingsActivity, 12f).toInt(), Theme.dp(this@SettingsActivity, 12f).toInt(), 0, Theme.dp(this@SettingsActivity, 4f).toInt())
                })
            }
            val item = SectionItemView(this, s, i)
            items += item
            list.addView(item)
        }

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Theme.dp(this@SettingsActivity, 24f).toInt(), Theme.dp(this@SettingsActivity, 16f).toInt(), 0, 0) }
        extra = FrameLayout(this)
        right.addView(extra, LinearLayout.LayoutParams(-1, -2))
        pane = FrameLayout(this)
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowsScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; fade(this); addView(rowsBox) }
        pane.addView(rowsScroll, FrameLayout.LayoutParams(-1, -1))
        right.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        hint = text(Theme.HINT_SP, Theme.TEXT_DIM).apply { setPadding(Theme.dp(this@SettingsActivity, 20f).toInt(), Theme.dp(this@SettingsActivity, 8f).toInt(), 0, 0); minLines = 2 }
        right.addView(hint, LinearLayout.LayoutParams(-1, -2))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 0.7f))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1).apply { setMargins(hPad, vPad, hPad, vPad) })
        panel = column
        setContentView(root)

        root.viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> onFocusMoved(now) })
    }

    private fun text(sizeSp: Float, colour: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(colour)
        typeface = Theme.FONT
    }

    // ---------------------------------------------------------------- sections and pages

    private fun showSection(i: Int, focusRows: Boolean) {
        if (i != current || pages.isNotEmpty()) leaveSection()
        current = i
        sLastSection = i
        pages.clear()
        items.forEach { it.invalidate() }
        render()
        val s = sections[i]
        // the LED strip shows the layout test while its section is open (DESIGN.md §6)
        if (s.id == "strip") { frame.visibility = View.VISIBLE; showLayoutTest() } else frame.visibility = View.GONE
        stripPanel(s.id == "strip")
        if (focusRows) focusFirstRow(sFocusedRow[s.id] ?: 0)
    }

    private lateinit var panel: View

    /** On the LED strip page the panel shrinks to fit inside the LED rectangle (StripFrameView), else the safe area. */
    private fun stripPanel(on: Boolean) {
        // the strip drawing needs a clean dark ground: whatever plays behind would show between the dots and the panel
        root.setBackgroundColor(Theme.BG)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        val h = Theme.dp(this, if (on) StripFrameView.PANEL_H_DP else Theme.SAFE_H_DP).toInt()
        val v = Theme.dp(this, if (on) StripFrameView.PANEL_V_DP else Theme.SAFE_V_DP).toInt()
        if (lp.leftMargin != h || lp.topMargin != v) {
            lp.setMargins(h, v, h, v)
            panel.layoutParams = lp
        }
    }

    private fun leaveSection() {
        AmbientService.endTest()
        colourTest = false
        frame.setBackgroundColor(0)
        frame.visibility = View.GONE
        tools?.stop()
        tools = null
    }

    /** True while the right pane is rebuilt: removing the focused row makes Android move focus to the section list,
     *  which must not count as browsing to another section. */
    private var rebuilding = false
    private lateinit var root: FrameLayout

    /** Builds the right pane for the current section, or the top sub-page. */
    private fun render() {
        rebuilding = true
        try { renderPane() } finally { rebuilding = false }
    }

    private fun renderPane() {
        val s = sections[current]
        val page = pages.lastOrNull()
        title.text = buildString {
            // on a sub-page "Settings ›" goes, so the line fits without cutting a word (Home › section › sub-page)
            if (page == null) append(getString(R.string.settings)).append(" › ")
            append(getString(s.label))
            if (page != null) append(" › ").append(pageTitle(page))
        }
        rowsBox.removeAllViews()
        extra.removeAllViews()
        grid?.let { pane.removeView(it) }
        grid = null
        resetPaneLayout()
        rowsScroll.visibility = View.VISIBLE
        hintOverride = null
        when {
            page == "music_effects" -> showGrid(music = true)
            page == "white_balance" -> { addRows(whiteBalanceRows()); AmbientService.showTest(Native.SOURCE_SINGLE, 0, testColour(0, 0, 100)) }
            page != null && page.startsWith("stats:") -> addRows(statRows(page.removePrefix("stats:")))
            page != null && page.startsWith("help:") -> showHelp(page.removePrefix("help:"))
            s.content == "effects_grid" -> { showGrid(music = false); addRows(s.rows) }
            else -> addRows(s.rows)
        }
        when (if (page == null) s.content else null) {
            "music_status" -> extra.addView(MusicStatusCard(this))
            "key_test" -> extra.addView(KeyTestLine(this))
            "colour_swatch" -> extra.addView(ColourSwatch(this, sp))
        }
        showHint(null)
    }

    private fun pageTitle(page: String): String = when {
        page == "music_effects" -> getString(R.string.music_effect)
        page == "white_balance" -> getString(R.string.white_balance)
        page.startsWith("stats:") -> getString(when (page) {
            "stats:lights" -> R.string.stats_lights
            "stats:video" -> R.string.stats_video
            "stats:cpu" -> R.string.stats_cpu
            "stats:memory" -> R.string.stats_memory
            "stats:bandwidth" -> R.string.stats_bandwidth
            else -> R.string.stats_music_mode
        })
        page == "help:licences" -> getString(R.string.licences)
        page.startsWith("help:") -> getString(helpTitle(page.removePrefix("help:")))
        else -> page
    }

    private fun addRows(rows: List<Row>) {
        for (r in rows) rowsBox.addView(SettingRow.create(this, r, sp, this), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Theme.dp(this@SettingsActivity, 6f).toInt()
        })
    }

    private fun showGrid(music: Boolean) {
        val g = TileGridView(this, sp, model, music) { showHint(it) }
        grid = g
        if (music) {
            rowsScroll.visibility = View.GONE
            pane.addView(g, FrameLayout.LayoutParams(-1, -1))
        } else {
            // effects: the grid fills the pane above the section's reset row
            rowsScroll.visibility = View.VISIBLE
            pane.removeView(rowsScroll)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
            (rowsScroll.parent as? ViewGroup)?.removeView(rowsScroll)
            box.addView(rowsScroll, LinearLayout.LayoutParams(-1, -2))
            pane.addView(box, FrameLayout.LayoutParams(-1, -1))
            gridBox = box
        }
    }

    private var gridBox: LinearLayout? = null

    private fun resetPaneLayout() {
        gridBox?.let { box ->
            box.removeView(rowsScroll)
            pane.removeView(box)
            pane.addView(rowsScroll, 0, FrameLayout.LayoutParams(-1, -1))
        }
        gridBox = null
    }

    private fun whiteBalanceRows(): List<Row> = listOf(
        ValueRow(S.BAL_R, R.string.balance_red, R.string.balance_hint, format = { _, v -> "$v %" }),
        ValueRow(S.BAL_G, R.string.balance_green, R.string.balance_hint, format = { _, v -> "$v %" }),
        ValueRow(S.BAL_B, R.string.balance_blue, R.string.balance_hint, format = { _, v -> "$v %" }),
    )

    private fun statRows(group: String): List<Row> {
        fun f(bit: Int, label: Int) = FlagRow(S.STATS_MASK, bit, label, R.string.stat_hint)
        return when (group) {
            "lights" -> listOf(f(StatBit.CAPTURE_RATE, R.string.stat_capture_rate), f(StatBit.CAPTURE_TIME, R.string.stat_capture_time),
                f(StatBit.CAPTURE_PATH, R.string.stat_capture_path), f(StatBit.LED_RATE, R.string.stat_led_rate))
            "video" -> listOf(f(StatBit.VIDEO_FPS, R.string.stat_video_fps), f(StatBit.LATE_FRAMES, R.string.stat_late),
                f(StatBit.DISPLAY_MODE, R.string.stat_display), f(StatBit.FOREGROUND_APP, R.string.stat_foreground))
            "cpu" -> listOf(f(StatBit.CPU_OURS, R.string.stat_cpu_ours), f(StatBit.CPU_SYSTEM, R.string.stat_cpu_system),
                f(StatBit.CPU_DISPLAY, R.string.stat_cpu_display), f(StatBit.CPU_FREQ, R.string.stat_cpu_freq))
            "memory" -> listOf(f(StatBit.MEM_OURS, R.string.stat_mem_ours), f(StatBit.MEM_DEVICE, R.string.stat_mem_device))
            "bandwidth" -> listOf(f(StatBit.USB, R.string.stat_usb), f(StatBit.NETWORK, R.string.stat_network))
            else -> listOf(f(StatBit.MUSIC, R.string.stat_music), f(StatBit.MODE, R.string.stat_mode))
        }
    }

    private fun helpTitle(id: String) = when (id) {
        "no_lights" -> R.string.help_no_lights
        "no_sound" -> R.string.help_no_sound
        "buttons" -> R.string.help_buttons
        "stutter" -> R.string.help_stutter
        else -> R.string.licences
    }

    private fun showHelp(id: String) {
        val body = when (id) {
            "no_lights" -> R.string.help_no_lights_text
            "no_sound" -> R.string.help_no_sound_text
            "buttons" -> R.string.help_buttons_text
            "stutter" -> R.string.help_stutter_text
            else -> R.string.licences_text
        }
        // a focusable text page: Up/Down scroll it, Back returns
        val t = text(Theme.LABEL_SP - 2, Theme.TEXT).apply {
            text = getString(body)
            isFocusable = true
            setLineSpacing(0f, 1.25f)
            setPadding(Theme.dp(this@SettingsActivity, 20f).toInt(), 0, Theme.dp(this@SettingsActivity, 20f).toInt(), 0)
        }
        rowsBox.addView(t)
    }

    /** A soft fade where a list runs past its edge, instead of a hard cut through a line of text. */
    private fun fade(v: ScrollView) {
        v.isVerticalFadingEdgeEnabled = true
        v.setFadingEdgeLength(Theme.dp(this, 24f).toInt())
    }

    private fun focusFirstRow(index: Int) {
        grid?.let { it.requestFocus(); return }
        val focusables = (0 until rowsBox.childCount).map { rowsBox.getChildAt(it) }.filter { it.isFocusable }
        (focusables.getOrNull(index) ?: focusables.firstOrNull())?.requestFocus()
    }

    private fun focusInPane(v: View?): Boolean {
        var p = v?.parent
        while (p != null) {
            if (p === pane) return true
            p = p.parent
        }
        return false
    }

    private fun onFocusMoved(now: View?) {
        if (rebuilding) return
        // effect grids get the whole width: the section list folds away while a grid has focus
        listScroll.visibility = if (now is TileGridView) View.GONE else View.VISIBLE
        if (now is SectionItemView) {
            if (now.index != current) showSection(now.index, focusRows = false)
            showHint(null)
            return
        }
        if (now is SettingRow) {
            // keep the focused row in the middle third (§5.2) and remember it
            rowsScroll.post {
                val y = now.top - rowsScroll.height / 3
                rowsScroll.smoothScrollTo(0, y.coerceAtLeast(0))
            }
            val focusables = (0 until rowsBox.childCount).map { rowsBox.getChildAt(it) }.filter { it.isFocusable }
            // remembered for the next visit, except a confirm-twice action (a reset): coming back must never land on it
            val risky = (now.row as? io.github.noman13.tvambient.ui.rows.ActionRow)?.twice == true
            if (pages.isEmpty() && !risky) sFocusedRow[sections[current].id] = focusables.indexOf(now).coerceAtLeast(0)
            showHint(now)
        }
    }

    private fun showHint(row: View?) {
        hintOverride?.let { hint.text = it; return }
        val r = (row as? SettingRow) ?: (currentFocus as? SettingRow)
        hint.text = when {
            r == null -> ""
            r.disabledReason != null -> getString(r.disabledReason!!)
            r.row.hint != 0 -> getString(r.row.hint)
            else -> ""
        }
    }

    private fun showHint(text: String) {
        hintOverride = text
        hint.text = text
        handler.removeCallbacks(clearHint)
        handler.postDelayed(clearHint, 6000)
    }

    private val clearHint = Runnable { hintOverride = null; showHint(currentFocus) }

    // ---------------------------------------------------------------- RowHost

    override val tint: Int get() = Theme.groupColour(sections[current].group)

    override fun openPage(page: String) {
        tools?.stop()
        pages += page
        resetPaneLayout()
        render()
        focusFirstRow(0)
    }

    override fun openChoices(row: ChoiceRowView) {
        val c = ChoiceListView(this, row, tint) { closeChoices() }
        choices = c
        val loc = IntArray(2)
        row.getLocationInWindow(loc)
        val root = window.decorView as ViewGroup
        root.addView(c, FrameLayout.LayoutParams(row.width / 2, -2).apply {
            leftMargin = loc[0] + row.width / 2
            topMargin = (loc[1] + row.height).coerceAtMost(root.height - c.wantedHeight() - Theme.dp(this@SettingsActivity, 27f).toInt())
        })
        c.requestFocus()
    }

    private fun closeChoices() {
        val c = choices ?: return
        choices = null
        // focus the row first: removing the focused list would make Android hand focus to the section list
        c.row.requestFocus()
        rebuilding = true
        try { (c.parent as? ViewGroup)?.removeView(c) } finally { rebuilding = false }
        c.row.invalidate()
    }

    override fun rowChanged(row: SettingRow) {
        showHint(row)
        for (i in 0 until rowsBox.childCount) rowsBox.getChildAt(i).invalidate() // dimming and summaries can depend on it
        items[current].invalidate()
    }

    override fun runAction(action: String) {
        when {
            action.startsWith("reset:") -> resetSection(action.removePrefix("reset:"))
            action.startsWith("test:") -> runTest(action.removePrefix("test:"))
            action == "tap_to_beat" -> (tools ?: AudioTools(this, sp) { showHint(it) }.also { tools = it }).tapToBeat()
            action == "test_audio" -> (tools ?: AudioTools(this, sp) { showHint(it) }.also { tools = it }).testInput()
            action == "shortcuts" -> toggleShortcuts()
            action == "stats_toggle" -> if (!io.github.noman13.tvambient.keys.KeyShortcutService.toggleStats()) showHint(getString(R.string.stats_need_shortcuts))
            action == "check_shell" -> checkShell()
            action == "first_run" -> startActivity(Intent(this, FirstRunActivity::class.java))
            action == "reset_all" -> resetAll()
        }
    }

    private fun resetSection(id: String) {
        val s = sections.firstOrNull { it.id == id } ?: return
        val e = sp.edit()
        for (d in SettingsSections.defsOf(s)) e.remove(d.key)
        if (id == "effects") {
            e.remove(S.EFFECT_ROTATION_CHANGED)
            sp.all.keys.filter { it.startsWith(S.FX_PREFIX) }.forEach { e.remove(it) }
        }
        if (id == "music") e.remove(S.MUSIC_ROTATION_CHANGED)
        e.apply()
        render()
        focusFirstRow(0)
    }

    private fun resetAll() {
        val keep = sp.getInt(S.FIRST_RUN_DONE.key, 0)
        sp.edit().clear().putInt(S.FIRST_RUN_DONE.key, keep).apply()
        render()
        focusFirstRow(0)
    }

    private fun testColour(hue: Int, sat: Int, bright: Int) = io.github.noman13.tvambient.NativeConfig.params(hue = hue, satPct = sat, brightnessPct = bright)

    private fun showLayoutTest() {
        val id = (0 until Native.effectCount()).firstOrNull { Native.effectName(it) == "Layout test" } ?: return
        AmbientService.showTest(Native.SOURCE_EFFECT, id, io.github.noman13.tvambient.NativeConfig.params())
    }

    /** A colour test (white, red...) is showing: Back ends it, back to the page's layout test, before anything else. */
    private var colourTest = false

    private fun runTest(which: String) {
        colourTest = which != "layout"
        when (which) {
            "layout" -> showLayoutTest()
            "white" -> AmbientService.showTest(Native.SOURCE_SINGLE, 0, testColour(0, 0, 100))
            "red" -> AmbientService.showTest(Native.SOURCE_SINGLE, 0, testColour(0, 100, 100))
            "green" -> AmbientService.showTest(Native.SOURCE_SINGLE, 0, testColour(120, 100, 100))
            "blue" -> AmbientService.showTest(Native.SOURCE_SINGLE, 0, testColour(240, 100, 100))
            "grey" -> AmbientService.showTest(Native.SOURCE_SINGLE, 0, testColour(0, 0, 50))
        }
        // the same colour on the screen as on the strip, to compare (§6)
        frame.setBackgroundColor(when (which) {
            "white" -> 0xFFFFFFFF.toInt()
            "red" -> 0xFFFF0000.toInt()
            "green" -> 0xFF00FF00.toInt()
            "blue" -> 0xFF0000FF.toInt()
            "grey" -> 0xFF808080.toInt()
            else -> 0
        })
        if (!AmbientService.sRunning) showHint(getString(R.string.test_lights_off))
    }

    private fun toggleShortcuts() {
        val on = !Shell.shortcutsEnabled(this)
        val app = applicationContext
        Thread({
            val ok = runCatching { Shell.setShortcuts(app, on) }.isSuccess
            handler.post {
                if (!ok) {
                    // no shell access: Android's own page, with the one-line explanation in the hint (§8)
                    showHint(getString(R.string.shortcuts_manual))
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                items[current].invalidate()
                for (i in 0 until rowsBox.childCount) rowsBox.getChildAt(i).invalidate()
            }
        }, "tvambient-shortcuts").start()
    }

    private fun checkShell() {
        showHint(getString(R.string.checking))
        val app = applicationContext
        Thread({
            val ok = runCatching { Shell.run(app, "echo ok").trim() == "ok" }.getOrDefault(false)
            if (ok) AmbientService.sShellOk = true
            handler.post {
                // the status row changes, so the page is rebuilt; focus stays on the row that ran the check (it used
                // to fall into the section list)
                val focused = (0 until rowsBox.childCount).map { rowsBox.getChildAt(it) }.filter { it.isFocusable }.indexOf(currentFocus)
                render()
                if (focused >= 0) focusFirstRow(focused)
                showHint(getString(if (ok) R.string.shell_ok else R.string.shell_fail))
            }
        }, "tvambient-check").start()
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tools?.onKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val f = currentFocus
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (f is SectionItemView) {
                focusFirstRow(sFocusedRow[sections[current].id] ?: 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (focusInPane(f)) {
                listScroll.visibility = View.VISIBLE // folded while a grid had focus
                items[current].requestFocus()
                return true
            }
            // no dead ends: Up/Down move between the rows, but never out of the pane (this runs before Android's own
            // focus move, so only block it when the next view that way isn't in the pane)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (f is SectionItemView) {
                // the section list: previous or next section, and nothing past either end (Up on the first one
                // once jumped into the right pane)
                items.getOrNull(f.index + if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)?.requestFocus()
                return true
            } else if (focusInPane(f)) {
                // a row: step through the rows in their order, never by screen geometry (a smooth scroll in progress
                // once let Android's search land in the section list and switch sections), and stay put at the ends
                if (f != null && f.parent === rowsBox) {
                    val rows = (0 until rowsBox.childCount).map { rowsBox.getChildAt(it) }.filter { it.isFocusable && it.visibility == View.VISIBLE }
                    val i = rows.indexOf(f)
                    rows.getOrNull(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) i - 1 else i + 1)?.requestFocus()
                    return true
                }
                val next = f?.focusSearch(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                if (next == null || !focusInPane(next)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back key handling on API 26-32")
    override fun onBackPressed() {
        when {
            (currentFocus as? SettingRow)?.stopEditing() == true -> {}
            colourTest -> runTest("layout")
            choices != null -> closeChoices()
            grid?.back() == true -> {}
            pages.isNotEmpty() -> {
                AmbientService.endTest()
                pages.removeAt(pages.size - 1)
                resetPaneLayout()
                render()
                if (sections[current].id == "strip") showLayoutTest()
                // back on the section: focus the row that opened the sub-page
                focusFirstRow(if (pages.isEmpty()) sFocusedRow[sections[current].id] ?: 0 else 0)
            }
            focusInPane(currentFocus) -> { listScroll.visibility = View.VISIBLE; items[current].requestFocus() }
            else -> finish()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onSharedPreferenceChanged(s: SharedPreferences, key: String?) {
        if (key in LAYOUT_KEYS) { model.rebuild(); frame.invalidate() }
        items.getOrNull(current)?.invalidate()
    }

    override fun onResume() {
        super.onResume()
        shown = java.lang.ref.WeakReference(this)
        sp.registerOnSharedPreferenceChangeListener(this)
        handler.post(refresh)
        if (sections[current].id == "strip" && pages.isEmpty()) showLayoutTest()
    }


    override fun onPause() {
        if (shown?.get() === this) shown = null
        sp.unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacksAndMessages(null)
        tools?.stop()
        AmbientService.endTest()
        super.onPause()
    }

    override fun onDestroy() {
        model.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- small views

    /** One section in the left list: its name, and when focused a dim summary of its state; an accent bar if open. */
    private inner class SectionItemView(ctx: android.content.Context, val section: Section, val index: Int) : View(ctx) {
        private val name = Theme.paint(ctx, Theme.TEXT, Theme.LABEL_SP)
        private val summary = Theme.paint(ctx, Theme.TEXT_DIM, Theme.SMALL_SP)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Theme.glowPaint(ctx)
        private val ring = Theme.linePaint(ctx)
        private val tintColour = Theme.groupColour(section.group)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColour }
        private val r = RectF()

        init {
            isFocusable = true
            contentDescription = ctx.getString(section.label)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = Theme.dp(context, if (isFocused) 48f else 32f)
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            requestLayout()
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val pad = Theme.dp(context, 12f)
            r.set(0f, Theme.dp(context, 2f), width.toFloat(), height - Theme.dp(context, 2f))
            if (isFocused) {
                bg.color = Theme.SURFACE_FOCUS
                c.drawRoundRect(r, Theme.dp(context, 6f), Theme.dp(context, 6f), bg)
                Theme.neonRing(c, r, Theme.dp(context, 6f), tintColour, 1f, glow, ring)
            }
            if (index == current) c.drawRoundRect(0f, height * 0.25f, Theme.dp(context, 3f), height * 0.75f, 3f, 3f, bar)
            name.color = if (index == current) tintColour else Theme.TEXT
            val base = if (isFocused) height * 0.46f else height / 2f - (name.descent() + name.ascent()) / 2
            c.drawText(context.getString(section.label), pad + Theme.dp(context, 6f), base, name)
            if (isFocused) {
                val sum = section.summary(context, sp)
                if (sum.isNotEmpty()) c.drawText(ellipsize(sum, summary, width - 2 * pad), pad + Theme.dp(context, 6f), height * 0.84f, summary)
            }
        }

        private fun ellipsize(s: String, p: Paint, w: Float): String {
            if (p.measureText(s) <= w) return s
            var n = s.length
            while (n > 0 && p.measureText(s, 0, n) + p.measureText("…") > w) n--
            return s.substring(0, n) + "…"
        }
    }

    /** Music mode's status card (DESIGN.md §10.1): listening state, source, a 12-band mini spectrum, level, tempo. */
    private inner class MusicStatusCard(ctx: android.content.Context) : View(ctx) {
        private val t = Theme.paint(ctx, Theme.TEXT, Theme.HINT_SP)
        private val d = Theme.paint(ctx, Theme.TEXT_DIM, Theme.HINT_SP)
        private val bar = Paint().apply { color = Theme.ACCENT }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SURFACE }
        private val r = RectF()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Theme.dp(context, 76f).toInt())

        override fun onDraw(c: Canvas) {
            r.set(0f, 0f, width.toFloat(), height - Theme.dp(context, 8f))
            c.drawRoundRect(r, Theme.dp(context, 8f), Theme.dp(context, 8f), bg)
            val pad = Theme.dp(context, 20f)
            val musicOn = AmbientService.sRunning && S.get(sp, S.MODE) == 1
            val line = when {
                !musicOn -> getString(R.string.music_play_some)
                Stats.musicDb <= -70 -> getString(R.string.music_play_some)
                else -> getString(R.string.music_listening, AmbientService.sAudioSource)
            }
            c.drawText(line, pad, pad + t.textSize, if (musicOn) t else d)
            if (musicOn) {
                val bw = Theme.dp(context, 8f)
                val maxH = Theme.dp(context, 24f)
                val base = height - Theme.dp(context, 18f)
                for (k in 0 until 12) {
                    val h = (Stats.musicBands[k].toInt() and 0xff) / 255f * maxH
                    c.drawRect(pad + k * (bw + 3), base - h, pad + k * (bw + 3) + bw, base, bar)
                }
                val info = "${Stats.musicDb} dB" + if (Stats.musicBpm > 0) " · ${Stats.musicBpm} BPM" else ""
                c.drawText(info, pad + 12 * (bw + 3) + pad, base - Theme.dp(context, 4f), d)
            }
        }
    }

    /** Remote buttons: "Press a colour button now to test it: Green → Open settings" (DESIGN.md §8). */
    private inner class KeyTestLine(ctx: android.content.Context) : View(ctx) {
        private val t = Theme.paint(ctx, Theme.TEXT_DIM, Theme.HINT_SP)
        private val a = Theme.paint(ctx, Theme.ACCENT, Theme.HINT_SP)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Theme.dp(context, 44f).toInt())

        override fun onDraw(c: Canvas) {
            val pad = Theme.dp(context, 20f)
            val base = height * 0.6f
            var x = pad
            for (k in 0 until 4) {
                dot.color = Theme.KEY_COLOURS[k]
                c.drawCircle(x + Theme.dp(context, 6f), base - t.textSize / 3, Theme.dp(context, 6f), dot)
                x += Theme.dp(context, 18f)
            }
            val enabled = getString(if (Shell.shortcutsEnabled(context)) R.string.shortcuts_on else R.string.shortcuts_off)
            val last = AmbientService.sLastKey
            val text = "$enabled  ·  " + if (last.isEmpty()) getString(R.string.key_test_prompt) else last
            c.drawText(text, x + Theme.dp(context, 8f), base, if (last.isEmpty()) t else a)
        }
    }

    /** Single colour's swatch: the chosen colour, as the LEDs show it. */
    private class ColourSwatch(ctx: android.content.Context, private val sp: SharedPreferences) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val hsv = FloatArray(3)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Theme.dp(context, 56f).toInt())

        override fun onDraw(c: Canvas) {
            hsv[0] = S.get(sp, S.SINGLE_HUE).toFloat()
            hsv[1] = S.get(sp, S.SINGLE_SAT) / 100f
            hsv[2] = S.get(sp, S.SINGLE_BRIGHT) / 100f
            p.color = android.graphics.Color.HSVToColor(hsv)
            r.set(Theme.dp(context, 20f), 0f, Theme.dp(context, 220f), height - Theme.dp(context, 12f))
            c.drawRoundRect(r, Theme.dp(context, 8f), Theme.dp(context, 8f), p)
        }
    }

    companion object {
        private const val STATE_IN_PANE = "in_pane"
        private val LAYOUT_KEYS = setOf(S.TOP.key, S.RIGHT.key, S.BOTTOM.key, S.LEFT.key, S.GAP.key, S.START_CORNER.key,
            S.DIRECTION.key, S.OFFSET.key)
        /** Focus memory across visits (§4.1 rule 4). */
        private var sLastSection = 0
        private val sFocusedRow = HashMap<String, Int>()

        /** Opens Settings at a section (Home's "settings" links under the mode tiles). */
        /** The Settings screen while it's in front (main thread only), for the Green button's toggle. */
        private var shown: java.lang.ref.WeakReference<SettingsActivity>? = null

        /**
         * Closes the app's screens if Settings is in front (Settings and Home share the app's task, so Home goes
         * too), back to whatever was on screen before; false when Settings isn't in front (then
         * the caller opens it). The lights keep running. Main thread.
         */
        fun closeIfShown(): Boolean {
            val a = shown?.get() ?: return false
            a.finishAffinity()
            return true
        }

        fun open(c: android.content.Context, sectionId: String) {
            val i = SettingsSections.all().indexOfFirst { it.id == sectionId }
            if (i >= 0) sLastSection = i
            c.startActivity(Intent(c, SettingsActivity::class.java))
        }
    }
}

/**
 * A choice row's list, opened in place (§4.2): up to 6 visible, Up/Down + OK picks, Back cancels. Focusable while
 * open, so the D-pad stays inside it.
 */
class ChoiceListView(context: android.content.Context, val row: ChoiceRowView, tint: Int, private val close: () -> Unit) : View(context) {
    private val t = Theme.paint(context, Theme.TEXT, Theme.LABEL_SP - 2)
    private val a = Theme.paint(context, tint, Theme.LABEL_SP - 2)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SURFACE }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SURFACE_FOCUS }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Theme.dp(context, Theme.FOCUS_RING_DP); color = tint }
    private val itemH = Theme.dp(context, 36f)
    private val r = RectF()
    private var pos = row.index
    private var first = (pos - 2).coerceAtLeast(0)
    private val n = row.r.choices.size

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        elevation = Theme.dp(context, 8f)
    }

    fun wantedHeight() = (itemH * minOf(6, n)).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), wantedHeight())

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, Theme.dp(context, 8f), Theme.dp(context, 8f), bg)
        for (k in 0 until minOf(6, n)) {
            val i = first + k
            if (i >= n) break
            val y = k * itemH
            if (i == pos) {
                r.set(0f, y, width.toFloat(), y + itemH)
                c.drawRoundRect(r, Theme.dp(context, 8f), Theme.dp(context, 8f), sel)
                c.drawRoundRect(r, Theme.dp(context, 8f), Theme.dp(context, 8f), ring)
            }
            val label = context.getString(row.r.choices[i])
            c.drawText(label + if (i == row.index) "  ✓" else "", Theme.dp(context, 20f), y + itemH / 2 + t.textSize / 3, if (i == pos) a else t)
        }
        contentDescription = context.getString(row.r.choices[pos])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> pos = (pos - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> pos = (pos + 1).coerceAtMost(n - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { row.pick(pos); close(); return true }
            KeyEvent.KEYCODE_BACK -> { close(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            else -> return super.onKeyDown(keyCode, event)
        }
        if (pos < first) first = pos
        if (pos >= first + 6) first = pos - 5
        invalidate()
        return true
    }
}
