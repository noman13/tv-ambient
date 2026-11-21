package io.github.noman13.tvambient.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.adb.Shell
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.ui.SettingsSchema as S

/**
 * First run (DESIGN.md §5.4): three skippable steps, each one screen with a live status: 1 plug in the controller,
 * 2 turn on USB debugging (or use screen recording instead), 3 strip setup. Every step can be redone later from
 * Start-up & power. Step 2 follows the TV's own switches (1.0.0): with Developer options off it sends you to About to
 * press Build seven times, with them on to Developer options to turn on USB debugging, and once USB debugging is on
 * it checks the connection by itself. While you're in the TV's Settings it keeps watching and brings this screen back
 * as soon as the switch you went to change is on (Android may block that from the background; then the step resumes
 * when you come back). A timeline at the top (three dots joined by a line, and a progress bar under it) shows where
 * you are. Step 1 says the app drives Adalight controllers only. Platform widgets plus one drawn view.
 */
class FirstRunActivity : Activity() {
    private var step = 0
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var live: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var timeline: StepTimeline
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            when (step) {
                0 -> live.text = if (!AmbientService.sRunning) getString(R.string.home_status_off)
                    else getString(if (AmbientService.sUsbState == "USB") R.string.usb_connected else R.string.usb_not_connected)
                1 -> { val s = debugState(); if (s != shownState) showDebugStep(s) }
                else -> live.text = ""
            }
            handler.postDelayed(this, 500)
        }
    }

    // Step 2's state, from the TV's own settings: 0 Developer options off, 1 on but USB debugging off, 2 USB debugging on
    private var shownState = -1
    private var checking = false
    /** The state we sent the person to Settings to reach; while they're there, [watch] waits for it. */
    private var awaiting = -1
    private var watchUntil = 0L
    private val watch = object : Runnable {
        override fun run() {
            if (awaiting < 0 || android.os.SystemClock.uptimeMillis() > watchUntil) return
            if (debugState() >= awaiting) {
                awaiting = -1
                // back to this screen (allowed while the app has a bound service such as the remote shortcuts; if
                // Android refuses, the step picks up when the person returns)
                runCatching {
                    startActivity(android.content.Intent(this@FirstRunActivity, FirstRunActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun debugState(): Int {
        val r = contentResolver
        val dev = android.provider.Settings.Global.getInt(r, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = android.provider.Settings.Global.getInt(r, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
        return if (adb) 2 else if (dev) 1 else 0
    }

    /** Step 2's text and buttons for [state]; with USB debugging on, the connection check runs by itself. */
    private fun showDebugStep(state: Int) {
        shownState = state
        when (state) {
            0 -> {
                body.setText(R.string.fr_dev_off)
                primary.setText(R.string.fr_open_about)
                primary.setOnClickListener { openSettings(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS, 1) }
                live.setText(R.string.fr_dev_off_status)
            }
            1 -> {
                body.setText(R.string.fr_dev_on)
                primary.setText(R.string.fr_open_dev)
                primary.setOnClickListener { openSettings(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, 2) }
                live.setText(R.string.fr_dev_on_status)
            }
            else -> {
                body.setText(R.string.fr_adb_on)
                if (AmbientService.sShellOk) {
                    // already checked: nothing left to do here
                    live.setText(R.string.shell_ok)
                    primary.setText(R.string.fr_next)
                    primary.setOnClickListener { next() }
                } else {
                    primary.setText(R.string.fr_check)
                    primary.setOnClickListener { check() }
                    check()
                }
            }
        }
        secondary.setText(R.string.fr_use_recording)
        secondary.setOnClickListener {
            S.prefs(this).edit().putInt(S.CAPTURE_METHOD.key, 2).apply()
            next()
        }
        primary.requestFocus()
    }

    /** Opens a page of the TV's Settings and watches (10 minutes at most) for [target] to be reached. */
    private fun openSettings(action: String, target: Int) {
        val ok = runCatching { startActivity(android.content.Intent(action)) }.isSuccess ||
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }.isSuccess
        if (!ok) return
        awaiting = target
        watchUntil = android.os.SystemClock.uptimeMillis() + 10 * 60_000L
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, 1000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val pad = Theme.dp(this, 96f).toInt()
        title = tv(Theme.TITLE_SP, Theme.TEXT)
        body = tv(Theme.LABEL_SP - 2, Theme.TEXT).apply { setLineSpacing(0f, 1.2f) }
        live = tv(Theme.HINT_SP, Theme.ACCENT)
        primary = neonButton()
        secondary = neonButton()
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(primary, LinearLayout.LayoutParams(-2, Theme.dp(this@FirstRunActivity, 44f).toInt()).apply { rightMargin = Theme.dp(this@FirstRunActivity, 16f).toInt() }); addView(secondary, LinearLayout.LayoutParams(-2, Theme.dp(this@FirstRunActivity, 44f).toInt())) }
        timeline = StepTimeline(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP // the timeline stays put from step to step; the text below grows downward
            setBackgroundColor(Theme.BG)
            setPadding(pad, Theme.dp(this@FirstRunActivity, 72f).toInt(), pad, pad / 2)
            addView(timeline, LinearLayout.LayoutParams(-1, Theme.dp(this@FirstRunActivity, 72f).toInt()).apply { bottomMargin = pad / 3 })
            addView(title)
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 4 })
            addView(live, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 6 })
            addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pad / 4 })
        })
        show()
    }

    private fun tv(sizeSp: Float, colour: Int) = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(colour); typeface = Theme.FONT }

    private fun show() {
        when (step) {
            0 -> {
                title.setText(R.string.fr_step1_title)
                body.text = getString(R.string.fr_step1_text) + "\n\n" + getString(R.string.adalight_long)
                if (!AmbientService.sRunning) AmbientService.start(this) // opens the USB controller and asks for permission
                primary.setText(R.string.fr_next); primary.setOnClickListener { next() }
                secondary.setText(R.string.fr_skip); secondary.setOnClickListener { next() }
            }
            1 -> {
                title.setText(R.string.fr_step2_title)
                showDebugStep(debugState())
            }
            else -> {
                title.setText(R.string.fr_step3_title); body.setText(R.string.fr_step3_text)
                primary.setText(R.string.fr_open_strip); primary.setOnClickListener { done(); SettingsActivity.open(this, "strip") }
                secondary.setText(R.string.fr_done); secondary.setOnClickListener { done() }
            }
        }
        timeline.step = step
        timeline.invalidate()
        primary.requestFocus()
    }

    private fun check() {
        if (checking) return
        checking = true
        live.setText(R.string.checking)
        val app = applicationContext
        Thread({
            val ok = runCatching { Shell.run(app, "echo ok").trim() == "ok" }.getOrDefault(false)
            if (ok) {
                AmbientService.sShellOk = true
                runCatching { Shell.enableShortcutsOnce(app) } // Remote shortcuts on by default
            }
            handler.post {
                checking = false
                if (step != 1) return@post
                if (ok) next() else live.setText(R.string.shell_fail)
            }
        }, "tvambient-check").start()
    }

    private fun next() {
        step++
        show()
    }

    private fun done() {
        S.prefs(this).edit().putInt(S.FIRST_RUN_DONE.key, 1).apply()
        finish()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(watch) // back here: the step itself follows the state again
        awaiting = -1
        if (step == 1) shownState = -1 // re-read on the next tick
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        if (awaiting >= 0) handler.postDelayed(watch, 1000) // in the TV's Settings: keep watching
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** A button in the app's look (DESIGN.md §7): a dark pill, a neon ring and lit text when focused, no caps. */
    private fun neonButton() = Button(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.LABEL_SP)
        typeface = Theme.FONT_BOLD
        isAllCaps = false
        stateListAnimator = null
        val r = Theme.dp(this@FirstRunActivity, 20f)
        fun shape(fill: Int, ring: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = r
            if (ring != 0) setStroke(Theme.dp(this@FirstRunActivity, Theme.FOCUS_RING_DP).toInt(), ring)
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Theme.SURFACE_FOCUS, Theme.ACCENT))
            addState(intArrayOf(), shape(Theme.SURFACE, 0))
        }
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Theme.ACCENT, Theme.TEXT)))
        val h = Theme.dp(this@FirstRunActivity, 22f).toInt()
        setPadding(h, 0, h, 0)
    }

    /**
     * The wizard's timeline: three dots joined by a line, each with its name under it (done: filled green, current:
     * a blue ring with a glow, later: dim), and a thin progress bar below that fills a third per step.
     */
    private class StepTimeline(c: android.content.Context) : android.view.View(c) {
        var step = 0
        private val names = intArrayOf(R.string.fr_tl_controller, R.string.fr_tl_usb, R.string.fr_tl_strip)
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = Theme.dp(c, 2f) }
        private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        private val label = Theme.paint(c, Theme.TEXT_DIM, Theme.SMALL_SP, caps = true)
        private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()

        init { isFocusable = false }

        override fun onDraw(cv: android.graphics.Canvas) {
            val rad = Theme.dp(context, 7f)
            val y = rad * 2.2f
            val x0 = width * 0.12f
            val x1 = width * 0.88f
            val xs = floatArrayOf(x0, (x0 + x1) / 2, x1)
            for (i in 0..1) { // the connecting line, lit up to the current step
                line.color = if (i < step) Theme.NEON_GREEN else Theme.SURFACE_FOCUS
                cv.drawLine(xs[i] + rad, y, xs[i + 1] - rad, y, line)
            }
            for (i in 0..2) {
                val colour = when {
                    i < step -> Theme.NEON_GREEN
                    i == step -> Theme.NEON_BLUE
                    else -> Theme.TEXT_OFF
                }
                if (i == step) {
                    ring.color = colour; ring.alpha = 80; ring.strokeWidth = Theme.dp(context, 8f)
                    cv.drawCircle(xs[i], y, rad * 1.5f, ring)
                    ring.alpha = 255; ring.strokeWidth = Theme.dp(context, 2f)
                    cv.drawCircle(xs[i], y, rad * 1.5f, ring)
                }
                dot.color = colour
                cv.drawCircle(xs[i], y, if (i == step) rad * 0.8f else rad, dot)
                val t = "${i + 1} " + context.getString(names[i]).uppercase()
                label.color = if (i <= step) Theme.TEXT else Theme.TEXT_DIM
                cv.drawText(t, xs[i] - label.measureText(t) / 2, y + rad * 2.6f - label.ascent(), label)
            }
            // the progress bar
            val by = height - Theme.dp(context, 6f)
            val bh = Theme.dp(context, 4f)
            rect.set(x0, by, x1, by + bh)
            bar.color = Theme.SURFACE_FOCUS
            cv.drawRoundRect(rect, bh / 2, bh / 2, bar)
            rect.right = x0 + (x1 - x0) * (step + 1) / 3f
            bar.color = Theme.NEON_GREEN
            cv.drawRoundRect(rect, bh / 2, bh / 2, bar)
        }
    }
}
