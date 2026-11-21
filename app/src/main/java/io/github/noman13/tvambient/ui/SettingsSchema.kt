package io.github.noman13.tvambient.ui

import android.content.Context
import android.content.SharedPreferences
import io.github.noman13.tvambient.NativeConfig

/**
 * Every setting as data (PROJECT.md "Settings navigation", DESIGN.md §12): key, range and default. Defaults are the
 * reference calibration (TECHNICAL.md). `SettingsSections` says where each one shows in Settings.
 */
object SettingsSchema {
    class Def(val key: String, val min: Int, val max: Int, val default: Int)

    // LED strip (Setup › LED strip)
    val TOP = Def("top", 0, 300, 56)
    val RIGHT = Def("right", 0, 300, 36)
    val BOTTOM = Def("bottom", 0, 300, 56)
    val LEFT = Def("left", 0, 300, 36)
    val GAP = Def("gap", 0, 100, 0)
    val START_CORNER = Def("start_corner", 0, 3, 0)
    val DIRECTION = Def("direction", 0, 1, 0) // 0 clockwise, 1 counter-clockwise
    val OFFSET = Def("offset", 0, 1199, 0)
    val BAUD = Def("baud", 0, 6, 1) // index into BAUDS
    val COLOR_ORDER = Def("order", 0, 5, 0)
    val LED_RATE = Def("led_rate", 20, 60, 30)

    // Screen mode (Modes › Screen mode). Capture method: 0 automatic (shell screenshots when USB debugging
    // works, otherwise screen recording), 1 shell screenshots, 2 screen recording (MediaProjection, TECHNICAL.md §6).
    val CAPTURE_METHOD = Def("capture_method", 0, 2, 0)
    // 15/s: the calibrated setting on the reference TV
    val CAPTURE_RATE = Def("capture_rate", 5, 30, 15) // the most captures per second (fixed rate)
    /** Adaptive capture rate: 1 adapts to content and to CPU, memory and video pressure;
     *  0 uses the fixed [CAPTURE_RATE]. */
    val CAPTURE_ADAPTIVE = Def("capture_adaptive", 0, 1, 1)
    val BARS = Def("bars", 0, 1, 1)
    val DEPTH_TB = Def("depth_tb", 2, 30, 10)
    val DEPTH_LR = Def("depth_lr", 2, 30, 8)
    val DARK_REACH = Def("dark_reach", 0, 30, 0)

    // Picture (Look › Picture)
    val BRIGHTNESS = Def("bright", 5, 100, 35)
    val SATURATION = Def("sat", 0, 200, 150)
    val GAMMA = Def("gamma", 5, 30, 20) // 2.0 (1.0.0's calibration)
    val BAL_R = Def("bal_r", 0, 100, 75)
    val BAL_G = Def("bal_g", 0, 100, 75)
    val BAL_B = Def("bal_b", 0, 100, 75)
    val BLACK = Def("black", 0, 40, 5)
    val SMOOTHING = Def("smooth", 1, 10, 6)

    // Start-up & power (System)
    val BOOT = Def("boot", 0, 1, 1)
    val SCREEN_OFF = Def("screen_off", 0, 1, 0) // 0 LEDs off, 1 keep last colours

    // Remote buttons (Setup › Remote buttons): actions 0 none, 1 start, 2 stop, 3 on/off toggle, 4 stats, 5 mode
    // button, 6 open or close settings. Red toggles the lights and Green opens or closes Settings by default.
    val KEY_RED = Def("key_red", 0, 6, 3)
    val KEY_GREEN = Def("key_green", 0, 6, 6)
    val KEY_YELLOW = Def("key_yellow", 0, 6, 4)
    val KEY_BLUE = Def("key_blue", 0, 6, 5)
    val NOT_IN_LIVE_TV = Def("keys_not_live_tv", 0, 1, 1)

    // Modes (Modes › Mode switching, TECHNICAL.md §13, DESIGN.md §11): mode 0 Screen, 1 Music, 2 Effect, 3 Single
    val MODE = Def("mode", 0, 3, 0)
    val ROTATION = Def("rotation", 1, 15, 0b0111) // bit per mode a long press cycles through; default Screen, Music, Effect
    val LONG_PRESS = Def("long_press", 0, 1, 0) // 0 next mode, 1 nothing
    val START_IN = Def("start_in", 0, 1, 0) // 0 last mode, 1 Screen
    val EFFECT = Def("effect", 0, 255, 19) // the current effect: 19 = Northern sky
    val KEY_MESSAGE = Def("key_message", 0, 1, 1) // the 2 s message on a colour-button press (DESIGN.md §8)

    // Single colour (Modes › Single colour): the classic colour
    val SINGLE_HUE = Def("single_hue", 0, 359, 45) // a dark yellow
    val SINGLE_SAT = Def("single_sat", 0, 100, 90)
    val SINGLE_BRIGHT = Def("single_bright", 5, 100, 100)

    // Music mode (Modes › Music mode, DESIGN.md §10.1, TECHNICAL.md §14.2)
    val MUSIC_EFFECT = Def("music_effect", 0, 12, 2) // 2 VU meter (default); 0 Spectrum; 8-11 spectrum bars and waves, 12 colour shift
    val MUSIC_PALETTE = Def("music_palette", 0, 4, 0) // Rainbow, Warm, Cool, Neon, Single colour
    val MUSIC_FOLLOW = Def("music_follow", 0, 1, 0) // 0 loudness, 1 fixed
    val MUSIC_MIN = Def("music_min", 0, 30, 5) // minimum brightness %
    val MUSIC_SENS = Def("music_sens", 0, 10, 0) // 0 auto
    val MUSIC_BEAT = Def("music_beat", 0, 2, 1) // soft, medium, strong
    val MUSIC_FADE = Def("music_fade", 0, 2, 1) // slow, medium, fast
    val MUSIC_DELAY = Def("music_delay", -200, 500, 0) // ms
    val MUSIC_SILENCE = Def("music_silence", 0, 3, 0) // dim glow, lights off, switch to Screen, keep last colours
    val AUDIO_SOURCE = Def("audio_source", 0, 2, 0) // automatic, output mix (A), playback capture (B); remote submix (C) is not built
    val MUSIC_RANGE = Def("music_range", 0, 2, 0) // full, bass only, vocals

    // Stats overlay (Setup › Stats overlay)
    val STATS_SHOWN = Def("stats_shown", 0, 1, 0)
    val STATS_INTERVAL = Def("stats_interval_ms", 500, 2000, 1000)
    val STATS_LAYOUT = Def("stats_layout", 0, 2, 0) // full, compact, ticker
    val STATS_POSITION = Def("stats_position", 0, 3, 0) // top left, top right, bottom left, bottom right
    val STATS_SIZE = Def("stats_size", 0, 2, 1) // small, medium, large
    val STATS_RECORD = Def("stats_record", 0, 1, 0) // CSV, TECHNICAL.md §12.5
    /** Which stats the panel shows, one bit each ([StatBit]); the default is DESIGN.md §9.2's "On" column. */
    val STATS_MASK = Def("stats_mask", 0, (1 shl StatBit.COUNT) - 1, StatBit.DEFAULT)

    // first run (DESIGN.md §5.4)
    val FIRST_RUN_DONE = Def("first_run_done", 0, 1, 0)
    /** Remote shortcuts are on by default (1.0.0): set once the app has turned them on the first time ([Shell.enableShortcutsOnce]). */
    val SHORTCUTS_DEFAULT_DONE = Def("shortcuts_default_done", 0, 1, 0)

    val ALL = listOf(
        TOP, RIGHT, BOTTOM, LEFT, GAP, START_CORNER, DIRECTION, OFFSET, BAUD, COLOR_ORDER, LED_RATE,
        CAPTURE_RATE, BARS, DEPTH_TB, DEPTH_LR, DARK_REACH,
        BRIGHTNESS, SATURATION, GAMMA, BAL_R, BAL_G, BAL_B, BLACK, SMOOTHING,
        BOOT, SCREEN_OFF, KEY_RED, KEY_GREEN, KEY_YELLOW, KEY_BLUE, NOT_IN_LIVE_TV, STATS_SHOWN, STATS_INTERVAL,
        MODE, ROTATION, LONG_PRESS, START_IN, EFFECT, KEY_MESSAGE, SINGLE_HUE, SINGLE_SAT,
        MUSIC_EFFECT, MUSIC_PALETTE, MUSIC_FOLLOW, MUSIC_MIN, MUSIC_SENS, MUSIC_BEAT, MUSIC_FADE, MUSIC_DELAY,
        MUSIC_SILENCE, AUDIO_SOURCE, MUSIC_RANGE, CAPTURE_METHOD, CAPTURE_ADAPTIVE, SINGLE_BRIGHT, STATS_LAYOUT, STATS_POSITION, STATS_SIZE,
        STATS_RECORD, STATS_MASK, FIRST_RUN_DONE, SHORTCUTS_DEFAULT_DONE,
    )

    /** Effects toggled away from their default Blue-rotation membership (ids, comma-separated). */
    const val EFFECT_ROTATION_CHANGED = "effect_rotation_changed"

    /** Music effects toggled away from their default Blue-rotation membership (all but ⚡ are in by default). */
    const val MUSIC_ROTATION_CHANGED = "music_rotation_changed"

    fun musicInRotation(sp: SharedPreferences, id: Int, defaultIn: Boolean): Boolean {
        val changed = sp.getString(MUSIC_ROTATION_CHANGED, "") ?: ""
        val toggled = changed.isNotEmpty() && changed.split(',').any { it == id.toString() }
        return defaultIn != toggled
    }

    fun effectInRotation(sp: SharedPreferences, id: Int, defaultIn: Boolean): Boolean {
        val changed = sp.getString(EFFECT_ROTATION_CHANGED, "") ?: ""
        val toggled = changed.isNotEmpty() && changed.split(',').any { it == id.toString() }
        return defaultIn != toggled
    }

    /** Per-effect options (DESIGN.md §6.2, set on the effect's tile): speed 1-10, colour (hue), brightness %. */
    fun fxSpeedKey(id: Int) = "fx_speed_$id"
    fun fxHueKey(id: Int) = "fx_hue_$id"
    fun fxBrightKey(id: Int) = "fx_bright_$id"
    const val FX_PREFIX = "fx_"

    fun effectParams(sp: SharedPreferences, id: Int) = NativeConfig.params(
        speed = sp.getInt(fxSpeedKey(id), 5).coerceIn(1, 10),
        hue = sp.getInt(fxHueKey(id), 20).coerceIn(0, 359),
        brightnessPct = sp.getInt(fxBrightKey(id), 100).coerceIn(5, 100),
    )

    /** Toggles an id in one of the rotation-exception lists ([EFFECT_ROTATION_CHANGED], [MUSIC_ROTATION_CHANGED]). */
    fun toggleRotation(sp: SharedPreferences, key: String, id: Int) {
        val ids = (sp.getString(key, "") ?: "").split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!ids.remove(id.toString())) ids.add(id.toString())
        sp.edit().putString(key, ids.joinToString(",")).apply()
    }

    /** The music engine settings (`config::music`) from the Music mode rows. */
    fun music(sp: SharedPreferences) = NativeConfig.music(
        effect = get(sp, MUSIC_EFFECT), palette = get(sp, MUSIC_PALETTE), hue = get(sp, SINGLE_HUE),
        followLoudness = get(sp, MUSIC_FOLLOW) == 0, minBrightnessPct = get(sp, MUSIC_MIN),
        beatResponsePct = intArrayOf(40, 70, 100)[get(sp, MUSIC_BEAT)], fadeMs = intArrayOf(400, 250, 120)[get(sp, MUSIC_FADE)],
        delayMs = get(sp, MUSIC_DELAY),
    )

    fun audio(sp: SharedPreferences) = NativeConfig.audio(sensitivity = get(sp, MUSIC_SENS), range = get(sp, MUSIC_RANGE))

    fun single(sp: SharedPreferences) = NativeConfig.params(hue = get(sp, SINGLE_HUE), satPct = get(sp, SINGLE_SAT), brightnessPct = get(sp, SINGLE_BRIGHT))

    val BAUDS = intArrayOf(115_200, 230_400, 460_800, 500_000, 921_600, 1_000_000, 2_000_000)

    /** Live TV apps where the colour keys always pass through (teletext, HbbTV). */
    val LIVE_TV_PACKAGES = setOf("com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.android.tv")

    const val FILE = "settings"

    fun prefs(c: Context): SharedPreferences {
        val sp = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!migrated) synchronized(this) { if (!migrated) { migrate(sp); migrated = true } }
        return sp
    }

    @Volatile private var migrated = false
    private const val SCHEMA = "schema"
    /** The effect removed in 1.0.0 (Running light): every effect after it moved down one place. */
    private const val REMOVED_EFFECT = 22

    /**
     * One-time changes to stored settings between versions. Schema 1 (1.0.0): Running light was removed, so the
     * saved effect, the per-effect settings and the rotation list move down one place after it.
     */
    private fun migrate(sp: SharedPreferences) {
        if (sp.getInt(SCHEMA, 0) >= 1) return
        val e = sp.edit()
        fun shift(id: Int) = if (id > REMOVED_EFFECT) id - 1 else id
        if (sp.contains(EFFECT.key)) {
            val id = sp.getInt(EFFECT.key, EFFECT.default)
            e.putInt(EFFECT.key, if (id == REMOVED_EFFECT) EFFECT.default else shift(id))
        }
        // per-effect settings: drop the removed one's, move the later ones down (in id order, so nothing collides)
        for (key in listOf(::fxSpeedKey, ::fxHueKey, ::fxBrightKey)) {
            e.remove(key(REMOVED_EFFECT))
            for (id in REMOVED_EFFECT + 1..255) {
                if (!sp.contains(key(id))) continue
                e.putInt(key(id - 1), sp.getInt(key(id), 0))
                e.remove(key(id))
            }
        }
        val changed = sp.getString(EFFECT_ROTATION_CHANGED, "") ?: ""
        if (changed.isNotEmpty()) {
            val ids = changed.split(',').mapNotNull { it.toIntOrNull() }.filter { it != REMOVED_EFFECT }.map(::shift)
            e.putString(EFFECT_ROTATION_CHANGED, ids.joinToString(","))
        }
        e.putInt(SCHEMA, 1)
        e.apply()
    }

    fun def(key: String): Def? = ALL.firstOrNull { it.key == key }

    fun get(sp: SharedPreferences, d: Def): Int = sp.getInt(d.key, d.default).coerceIn(d.min, d.max)

    fun layout(sp: SharedPreferences) = NativeConfig.Layout(
        get(sp, TOP), get(sp, RIGHT), get(sp, BOTTOM), get(sp, LEFT), get(sp, GAP), get(sp, START_CORNER),
        get(sp, DIRECTION) == 1, get(sp, OFFSET), get(sp, DEPTH_TB), get(sp, DEPTH_LR),
    )

    fun picture(sp: SharedPreferences) = NativeConfig.Picture(
        get(sp, BRIGHTNESS), get(sp, SATURATION), get(sp, GAMMA), get(sp, BAL_R), get(sp, BAL_G), get(sp, BAL_B), get(sp, BLACK),
    )

    fun analyzer(sp: SharedPreferences) = NativeConfig.Analyzer(bars = get(sp, BARS) == 1, darkReachPct = get(sp, DARK_REACH))
}

/** The stats panel's switches (DESIGN.md §9.2), one bit each in [SettingsSchema.STATS_MASK]. */
object StatBit {
    const val CAPTURE_RATE = 0
    const val CAPTURE_TIME = 1
    const val CAPTURE_PATH = 2
    const val LED_RATE = 3
    const val VIDEO_FPS = 4
    const val LATE_FRAMES = 5
    const val DISPLAY_MODE = 6
    const val FOREGROUND_APP = 7
    const val CPU_OURS = 8
    const val CPU_SYSTEM = 9
    const val CPU_DISPLAY = 10
    const val CPU_FREQ = 11
    const val MEM_OURS = 12
    const val MEM_DEVICE = 13
    const val USB = 14
    const val NETWORK = 15
    const val MUSIC = 16
    const val MODE = 17
    const val COUNT = 18
    const val DEFAULT = (1 shl CAPTURE_RATE) or (1 shl CAPTURE_TIME) or (1 shl CAPTURE_PATH) or (1 shl LED_RATE) or
        (1 shl VIDEO_FPS) or (1 shl LATE_FRAMES) or (1 shl CPU_OURS) or (1 shl CPU_SYSTEM) or (1 shl CPU_DISPLAY) or
        (1 shl MEM_OURS) or (1 shl MEM_DEVICE) or (1 shl USB) or (1 shl NETWORK) or (1 shl MUSIC) or (1 shl MODE)
}
