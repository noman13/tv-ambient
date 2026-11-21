package io.github.noman13.tvambient.keys

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.overlay.MessageView
import io.github.noman13.tvambient.overlay.StatsOverlayView
import io.github.noman13.tvambient.service.ModeController
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.ui.SettingsSchema as S

/**
 * The colour-button shortcuts and the stats overlay's host (TECHNICAL.md §11, §12.1). It filters keys and watches
 * which app is in front; it never reads window content. A key without an action, any key in Live TV, and every
 * other key go through unchanged.
 */
class KeyShortcutService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var foreground: String? = null
    private var consumedDown = -1 // key code whose DOWN we consumed, so its UP is consumed too
    private var lastActionAt = 0L
    private var overlay: StatsOverlayView? = null
    private var message: MessageView? = null
    private val hideMessage = Runnable { removeMessage() }

    // Blue (the Mode button, TECHNICAL.md §11, §13): long = still down 600 ms after DOWN, short = UP before that
    private var modeKeyDown = -1
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        modeAction(long = true)
    }

    private val tick = object : Runnable {
        override fun run() {
            val v = overlay ?: return
            v.foreground = foreground
            v.sample()
            csv?.record(v)
            handler.postDelayed(this, v.intervalMs.toLong())
        }
    }
    private var csv: io.github.noman13.tvambient.overlay.StatsCsv? = null

    /** The panel follows its settings live: layout, position, size, refresh and recording rebuild it if it's shown. */
    private val overlayPrefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (overlay != null && key in OVERLAY_KEYS) { hideOverlay(); showOverlay() }
    }

    override fun onServiceConnected() {
        sInstance = this
        io.github.noman13.tvambient.ui.Theme.init(this) // the app font, for the overlay and the messages
        Native.load() // effect names for the messages
        Log.i(TAG, "key shortcuts on")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != packageName) foreground = p // our own overlay doesn't change what's in front
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val def = when (e.keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> S.KEY_RED
            KeyEvent.KEYCODE_PROG_GREEN -> S.KEY_GREEN
            KeyEvent.KEYCODE_PROG_YELLOW -> S.KEY_YELLOW
            KeyEvent.KEYCODE_PROG_BLUE -> S.KEY_BLUE
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_UP && consumedDown == e.keyCode) {
            consumedDown = -1
            if (modeKeyDown == e.keyCode) { // the Mode button: short press unless the long one already fired
                modeKeyDown = -1
                handler.removeCallbacks(longPress)
                if (!longFired) modeAction(long = false)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val sp = S.prefs(this)
        val action = S.get(sp, def)
        if (action == ACTION_NONE) return false
        if (S.get(sp, S.NOT_IN_LIVE_TV) == 1 && foreground in S.LIVE_TV_PACKAGES) return false
        if (consumedDown == e.keyCode && e.repeatCount > 0) {
            // remotes that send repeats themselves (some IR ones): the first repeat with FLAG_LONG_PRESS is the long press
            if (modeKeyDown == e.keyCode && !longFired && e.flags and KeyEvent.FLAG_LONG_PRESS != 0) {
                handler.removeCallbacks(longPress)
                longPress.run()
            }
            return true
        }
        consumedDown = e.keyCode
        val now = SystemClock.uptimeMillis()
        if (now - lastActionAt < DEBOUNCE_MS) return true
        lastActionAt = now
        Log.i(TAG, "key ${e.keyCode} -> action $action")
        AmbientService.sLastKey = KEY_NAMES[e.keyCode - KeyEvent.KEYCODE_PROG_RED] + " → " + getString(ACTION_NAMES[action])
        when (action) {
            ACTION_START -> { AmbientService.start(this); showMessage("TV Ambient on") }
            ACTION_STOP -> { AmbientService.stop(this); showMessage("Lights off") }
            ACTION_TOGGLE -> if (AmbientService.sRunning) {
                AmbientService.stop(this); showMessage("Lights off")
            } else {
                AmbientService.start(this); showMessage("TV Ambient on")
            }
            ACTION_STATS -> toggleOverlay()
            ACTION_SETTINGS -> openSettings()
            ACTION_MODE -> {
                modeKeyDown = e.keyCode
                longFired = false
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
        }
        return true
    }

    /**
     * Opens Settings over whatever plays. Settings is translucent, so the
     * video keeps playing behind it. An accessibility service may start activities from the background.
     */
    /** Green (1.0.0): opens Settings, or closes them when they're already in front. */
    private fun openSettings() {
        if (io.github.noman13.tvambient.ui.SettingsActivity.closeIfShown()) return
        val i = android.content.Intent(this, io.github.noman13.tvambient.ui.SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(i) }.onFailure { Log.w(TAG, "settings: $it") }
    }

    /** The Mode button (DESIGN.md §11.2). With the lights off, any press turns them on in the current mode. */
    private fun modeAction(long: Boolean) {
        if (!AmbientService.sRunning) {
            AmbientService.start(this)
            showMessage("TV Ambient on")
            return
        }
        val modes = ModeController(S.prefs(this))
        val msg = if (long) modes.longPress() else modes.shortPress()
        Log.i(TAG, "mode button ${if (long) "long" else "short"} -> ${msg ?: "no change"}")
        if (msg != null) showMessage(msg)
    }

    /** A small static message for 2 s (DESIGN.md §8), centred just below the top LEDs' capture band. */
    private fun showMessage(text: String) {
        if (S.get(S.prefs(this), S.KEY_MESSAGE) == 0) return
        handler.removeCallbacks(hideMessage)
        val existing = message
        if (existing != null) {
            existing.text = text
        } else {
            val v = MessageView(this).also { it.text = text }
            val m = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // below the top LEDs' sampling band (depth up to 30 % of 128x72 rows is the max; the default 10 %
                // band ends at 10 % of the height), so the message doesn't colour the top LEDs
                y = m.heightPixels * 13 / 100
                title = "TV Ambient message"
            }
            if (runCatching { getSystemService(WindowManager::class.java).addView(v, lp) }.isSuccess) message = v
        }
        handler.postDelayed(hideMessage, MESSAGE_MS)
    }

    private fun removeMessage() {
        val v = message ?: return
        message = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun toggleOverlay() = if (overlay == null) showOverlay() else hideOverlay()

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val sp = S.prefs(this)
        val v = StatsOverlayView(this)
        v.intervalMs = S.get(sp, S.STATS_INTERVAL)
        v.layout = S.get(sp, S.STATS_LAYOUT)
        v.scale = floatArrayOf(0.85f, 1f, 1.2f)[S.get(sp, S.STATS_SIZE)]
        v.foreground = foreground
        val position = S.get(sp, S.STATS_POSITION) // 0 top left, 1 top right, 2 bottom left, 3 bottom right
        val m = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // the chosen corner, inside the overscan safe area (DESIGN.md §9.4): 5 % of each edge; the ticker spans the
            // width at the top or the bottom
            gravity = (if (position >= 2) Gravity.BOTTOM else Gravity.TOP) or
                (if (v.layout == 2) Gravity.CENTER_HORIZONTAL else if (position % 2 == 1) Gravity.END else Gravity.START)
            x = if (v.layout == 2) 0 else m.widthPixels * 5 / 100 // the ticker is centred: no sideways offset
            y = m.heightPixels * 5 / 100
            title = "TV Ambient stats"
        }
        v.sample()
        wm.addView(v, lp)
        overlay = v
        S.prefs(this).edit().putInt(S.STATS_SHOWN.key, 1).apply()
        AmbientService.setStats(true, v.intervalMs)
        // the panel changes height with the mode (rows that don't apply hide): keep the LED mask on it
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (overlay === v) sendMask(v) }
        if (S.get(sp, S.STATS_RECORD) == 1) csv = io.github.noman13.tvambient.overlay.StatsCsv(this)
        sp.registerOnSharedPreferenceChangeListener(overlayPrefs)
        handler.postDelayed(tick, v.intervalMs.toLong())
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        handler.removeCallbacks(tick)
        S.prefs(this).unregisterOnSharedPreferenceChangeListener(overlayPrefs)
        csv?.close()
        csv = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        overlay = null
        S.prefs(this).edit().putInt(S.STATS_SHOWN.key, 0).apply()
        AmbientService.setStats(false, v.intervalMs)
        AmbientService.setMask(0, 0, 0, 0)
    }

    /** The panel's rectangle in capture pixels, with a 1-pixel margin (TECHNICAL.md §12.3). */
    private fun sendMask(v: StatsOverlayView) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val wm = getSystemService(WindowManager::class.java)
        val size = Point()
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { size.set(it.width(), it.height()) }
        else @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size)
        val sw = size.x.coerceAtLeast(1)
        val sh = size.y.coerceAtLeast(1)
        AmbientService.setMask(
            (loc[0] * CAPTURE_W / sw - 1).coerceAtLeast(0),
            (loc[1] * CAPTURE_H / sh - 1).coerceAtLeast(0),
            ((loc[0] + v.width) * CAPTURE_W + sw - 1) / sw + 1,
            ((loc[1] + v.height) * CAPTURE_H + sh - 1) / sh + 1,
        )
    }

    override fun onDestroy() {
        sInstance = null
        handler.removeCallbacksAndMessages(null)
        removeMessage()
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private var sInstance: KeyShortcutService? = null // main thread

        /** Settings › Stats overlay › "Show or hide now": the panel lives in this service's overlay window. */
        fun toggleStats(): Boolean {
            val k = sInstance ?: return false
            k.toggleOverlay()
            return true
        }

        const val TAG = "TvAmbient"
        const val ACTION_NONE = 0
        const val ACTION_START = 1
        const val ACTION_STOP = 2
        const val ACTION_TOGGLE = 3
        const val ACTION_STATS = 4
        const val ACTION_MODE = 5
        const val ACTION_SETTINGS = 6
        private val KEY_NAMES = arrayOf("Red", "Green", "Yellow", "Blue")
        private val ACTION_NAMES = io.github.noman13.tvambient.ui.SettingsSections.ACTIONS
        private const val DEBOUNCE_MS = 400L
        private const val LONG_PRESS_MS = 600L
        private val OVERLAY_KEYS = setOf(S.STATS_LAYOUT.key, S.STATS_POSITION.key, S.STATS_SIZE.key, S.STATS_INTERVAL.key, S.STATS_RECORD.key)
        private const val MESSAGE_MS = 2000L
        private const val CAPTURE_W = 128 // the helper's default capture size (CaptureHelper --size)
        private const val CAPTURE_H = 72
    }
}
