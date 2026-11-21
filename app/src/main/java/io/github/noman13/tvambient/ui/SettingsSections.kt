package io.github.noman13.tvambient.ui

import android.content.Context
import android.content.SharedPreferences
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.service.AmbientService
import io.github.noman13.tvambient.ui.SettingsSchema as S
import io.github.noman13.tvambient.ui.rows.ActionRow
import io.github.noman13.tvambient.ui.rows.ChoiceRow
import io.github.noman13.tvambient.ui.rows.FlagRow
import io.github.noman13.tvambient.ui.rows.SystemSwitchRow
import io.github.noman13.tvambient.ui.rows.HeadingRow
import io.github.noman13.tvambient.ui.rows.InfoRow
import io.github.noman13.tvambient.ui.rows.LinkRow
import io.github.noman13.tvambient.ui.rows.MoreRow
import io.github.noman13.tvambient.ui.rows.Row
import io.github.noman13.tvambient.ui.rows.Section
import io.github.noman13.tvambient.ui.rows.SwitchRow
import io.github.noman13.tvambient.ui.rows.ValueRow

/**
 * Settings as data (DESIGN.md §3, §12): the sections in their groups, each a list of rows. The Settings screen is
 * generated from this table. A new setting goes into [SettingsSchema] (its key and range) and here (its row), in the
 * section where someone would look for it (PROJECT.md "Settings navigation").
 */
object SettingsSections {
    // left-pane group headings
    val MODES = R.string.group_modes
    val LOOK = R.string.group_look
    val SETUP = R.string.group_setup
    val SYSTEM = R.string.group_system

    private val pct: (Context, Int) -> String = { _, v -> "$v %" }
    private val ms: (Context, Int) -> String = { _, v -> "$v ms" }
    private val perS: (Context, Int) -> String = { _, v -> "$v /s" }
    private val gamma: (Context, Int) -> String = { _, v -> "${v / 10}.${v % 10}" }
    private val degrees: (Context, Int) -> String = { _, v -> "$v°" }

    private fun reset(id: String) = ActionRow(R.string.reset_section, R.string.reset_section_hint, "reset:$id", twice = true)

    val ACTIONS = intArrayOf(R.string.act_none, R.string.act_start, R.string.act_stop, R.string.act_toggle, R.string.act_stats,
        R.string.act_mode, R.string.act_settings)

    fun all(): List<Section> = listOf(
        // ---------------------------------------------------------------- MODES
        Section("screen", R.string.sec_screen, MODES, listOf(
            ChoiceRow(S.CAPTURE_METHOD, R.string.capture_method, R.string.capture_method_hint,
                intArrayOf(R.string.method_auto, R.string.method_shell, R.string.method_recording)),
            ChoiceRow(S.CAPTURE_ADAPTIVE, R.string.capture_rate, R.string.capture_rate_hint, intArrayOf(R.string.rate_fixed, R.string.rate_adaptive)),
            ValueRow(S.CAPTURE_RATE, R.string.capture_rate_fixed, R.string.capture_rate_fixed_hint, format = perS).apply {
                unless = { sp -> if (S.get(sp, S.CAPTURE_ADAPTIVE) == 1) R.string.capture_rate_fixed_off else null }
            },
            SwitchRow(S.BARS, R.string.black_bars, R.string.black_bars_hint),
            MoreRow(),
            ValueRow(S.DARK_REACH, R.string.dark_reach, R.string.dark_reach_hint, format = pct),
            reset("screen"),
        ), summary = { c, sp -> c.getString(listOf(R.string.method_auto, R.string.method_shell, R.string.method_recording)[S.get(sp, S.CAPTURE_METHOD)]) + " · " +
            if (S.get(sp, S.CAPTURE_ADAPTIVE) == 1) c.getString(R.string.rate_adaptive) else S.get(sp, S.CAPTURE_RATE).toString() + "/s" }),

        Section("music", R.string.sec_music, MODES, listOf(
            HeadingRow(R.string.music_look),
            LinkRow(R.string.music_effect, R.string.music_effect_hint, "music_effects") { _, sp -> Native.musicName(S.get(sp, S.MUSIC_EFFECT)) ?: "" },
            ChoiceRow(S.MUSIC_PALETTE, R.string.palette, R.string.palette_hint,
                intArrayOf(R.string.pal_rainbow, R.string.pal_warm, R.string.pal_cool, R.string.pal_neon, R.string.pal_single)),
            ChoiceRow(S.MUSIC_FOLLOW, R.string.bright_follows, R.string.bright_follows_hint, intArrayOf(R.string.follows_loudness, R.string.follows_fixed)),
            ValueRow(S.MUSIC_MIN, R.string.min_brightness, R.string.min_brightness_hint, format = pct),
            HeadingRow(R.string.music_reaction),
            ChoiceRow(S.MUSIC_SENS, R.string.sensitivity, R.string.sensitivity_hint,
                intArrayOf(R.string.auto, R.string.n1, R.string.n2, R.string.n3, R.string.n4, R.string.n5, R.string.n6, R.string.n7, R.string.n8, R.string.n9, R.string.n10)),
            ChoiceRow(S.MUSIC_BEAT, R.string.beat_response, R.string.beat_response_hint, intArrayOf(R.string.soft, R.string.medium, R.string.strong)),
            ChoiceRow(S.MUSIC_FADE, R.string.fade_speed, R.string.fade_speed_hint, intArrayOf(R.string.slow, R.string.medium, R.string.fast)),
            ValueRow(S.MUSIC_DELAY, R.string.audio_delay, R.string.audio_delay_hint, step = 10, format = ms),
            ActionRow(R.string.tap_to_beat, R.string.tap_to_beat_hint, "tap_to_beat"),
            HeadingRow(R.string.music_silent),
            ChoiceRow(S.MUSIC_SILENCE, R.string.after_silence, R.string.after_silence_hint,
                intArrayOf(R.string.silence_glow, R.string.silence_off, R.string.silence_screen, R.string.silence_keep)),
            HeadingRow(R.string.music_input),
            ChoiceRow(S.AUDIO_SOURCE, R.string.audio_source, R.string.audio_source_hint,
                intArrayOf(R.string.src_auto, R.string.src_mix, R.string.src_playback)),
            ActionRow(R.string.test_audio, R.string.test_audio_hint, "test_audio"),
            MoreRow(),
            ChoiceRow(S.MUSIC_RANGE, R.string.freq_range, R.string.freq_range_hint, intArrayOf(R.string.range_full, R.string.range_bass, R.string.range_vocals)),
            reset("music"),
        ), content = "music_status", summary = { c, sp ->
            c.getString(listOf(R.string.src_auto, R.string.src_mix, R.string.src_playback)[S.get(sp, S.AUDIO_SOURCE)]) +
                " · " + (Native.musicName(S.get(sp, S.MUSIC_EFFECT)) ?: "")
        }),

        Section("effects", R.string.sec_effects, MODES, listOf(reset("effects")), content = "effects_grid",
            summary = { _, sp -> Native.effectName(S.get(sp, S.EFFECT)) ?: "" }),

        Section("single", R.string.sec_single, MODES, listOf(
            ValueRow(S.SINGLE_HUE, R.string.hue, R.string.hue_hint, step = 5, format = degrees),
            ValueRow(S.SINGLE_SAT, R.string.colour_strength, R.string.colour_strength_hint, step = 5, format = pct),
            ValueRow(S.SINGLE_BRIGHT, R.string.brightness, R.string.single_bright_hint, step = 5, format = pct),
            reset("single"),
        ), content = "colour_swatch", summary = { _, sp -> "${S.get(sp, S.SINGLE_HUE)}° · ${S.get(sp, S.SINGLE_SAT)} %" }),

        Section("switching", R.string.sec_switching, MODES, listOf(
            HeadingRow(R.string.rotation_heading),
            FlagRow(S.ROTATION, 0, R.string.mode_screen, R.string.rotation_hint, keepOne = true),
            FlagRow(S.ROTATION, 1, R.string.mode_music, R.string.rotation_hint, keepOne = true),
            FlagRow(S.ROTATION, 2, R.string.mode_effect, R.string.rotation_hint, keepOne = true),
            FlagRow(S.ROTATION, 3, R.string.mode_single, R.string.rotation_hint, keepOne = true),
            ChoiceRow(S.LONG_PRESS, R.string.long_press, R.string.long_press_hint, intArrayOf(R.string.long_next_mode, R.string.long_nothing)),
            ChoiceRow(S.START_IN, R.string.start_in, R.string.start_in_hint, intArrayOf(R.string.start_last, R.string.mode_screen)),
            reset("switching"),
        ), summary = { c, sp ->
            val r = S.get(sp, S.ROTATION)
            listOf(R.string.mode_screen, R.string.mode_music, R.string.mode_effect, R.string.mode_single)
                .filterIndexed { i, _ -> r and (1 shl i) != 0 }.joinToString(" → ") { c.getString(it) }
        }),

        // ---------------------------------------------------------------- LOOK
        Section("picture", R.string.sec_picture, LOOK, listOf(
            ValueRow(S.BRIGHTNESS, R.string.brightness, R.string.brightness_hint, format = pct),
            ValueRow(S.SATURATION, R.string.saturation, R.string.saturation_hint, step = 5, format = pct),
            ValueRow(S.GAMMA, R.string.gamma, R.string.gamma_hint, format = gamma),
            LinkRow(R.string.white_balance, R.string.white_balance_hint, "white_balance") { _, sp ->
                "R${S.get(sp, S.BAL_R)} G${S.get(sp, S.BAL_G)} B${S.get(sp, S.BAL_B)}"
            },
            ValueRow(S.BLACK, R.string.black_level, R.string.black_level_hint),
            ValueRow(S.SMOOTHING, R.string.smoothing, R.string.smoothing_hint),
            reset("picture"),
        ), summary = { _, sp -> "${S.get(sp, S.BRIGHTNESS)} % · ${S.get(sp, S.SATURATION)} %" }),

        // ---------------------------------------------------------------- SETUP
        Section("strip", R.string.sec_strip, SETUP, listOf(
            ValueRow(S.TOP, R.string.leds_top, R.string.leds_side_hint),
            ValueRow(S.RIGHT, R.string.leds_right, R.string.leds_side_hint),
            ValueRow(S.BOTTOM, R.string.leds_bottom, R.string.leds_side_hint),
            ValueRow(S.LEFT, R.string.leds_left, R.string.leds_side_hint),
            InfoRow(R.string.leds_total) { _, sp -> S.layout(sp).count.toString() },
            ValueRow(S.GAP, R.string.bottom_gap, R.string.bottom_gap_hint),
            ChoiceRow(S.START_CORNER, R.string.start_corner, R.string.start_corner_hint,
                intArrayOf(R.string.corner_tl, R.string.corner_tr, R.string.corner_br, R.string.corner_bl)),
            ChoiceRow(S.DIRECTION, R.string.direction, R.string.direction_hint, intArrayOf(R.string.clockwise, R.string.counter_clockwise)),
            ValueRow(S.OFFSET, R.string.first_led_offset, R.string.first_led_offset_hint),
            ValueRow(S.DEPTH_TB, R.string.depth_tb, R.string.depth_hint, format = pct),
            ValueRow(S.DEPTH_LR, R.string.depth_lr, R.string.depth_hint, format = pct),
            HeadingRow(R.string.test_heading), // the Test and Controller headings divide this page; no "More" line
            ActionRow(R.string.test_layout, R.string.test_layout_hint, "test:layout"),
            ActionRow(R.string.test_white, R.string.test_colour_hint, "test:white"),
            ActionRow(R.string.test_red, R.string.test_colour_hint, "test:red"),
            ActionRow(R.string.test_green, R.string.test_colour_hint, "test:green"),
            ActionRow(R.string.test_blue, R.string.test_colour_hint, "test:blue"),
            ActionRow(R.string.test_grey, R.string.test_colour_hint, "test:grey"),
            HeadingRow(R.string.controller_heading),
            InfoRow(R.string.protocol) { c, _ -> c.getString(R.string.adalight_short) }, // the only protocol so far
            ChoiceRow(S.BAUD, R.string.baud, R.string.baud_hint, intArrayOf(R.string.b115200, R.string.b230400, R.string.b460800,
                R.string.b500000, R.string.b921600, R.string.b1000000, R.string.b2000000)),
            ChoiceRow(S.COLOR_ORDER, R.string.colour_order, R.string.colour_order_hint,
                intArrayOf(R.string.rgb, R.string.rbg, R.string.grb, R.string.gbr, R.string.brg, R.string.bgr)),
            ValueRow(S.LED_RATE, R.string.led_rate, R.string.led_rate_hint, format = perS),
            InfoRow(R.string.led_rate_max) { _, sp -> maxLedRate(sp).toString() + " /s" },
            reset("strip"),
        ), content = "strip_frame", summary = { c, sp -> c.getString(R.string.leds_n, S.layout(sp).count) }),

        Section("remote", R.string.sec_remote, SETUP, listOf(
            SystemSwitchRow(R.string.remote_shortcuts, R.string.remote_shortcuts_hint, "shortcuts") { c ->
                io.github.noman13.tvambient.adb.Shell.shortcutsEnabled(c)
            },
            ChoiceRow(S.KEY_RED, R.string.key_red, R.string.key_hint, ACTIONS),
            ChoiceRow(S.KEY_GREEN, R.string.key_green, R.string.key_hint, ACTIONS),
            ChoiceRow(S.KEY_YELLOW, R.string.key_yellow, R.string.key_hint, ACTIONS),
            ChoiceRow(S.KEY_BLUE, R.string.key_blue, R.string.key_hint, ACTIONS),
            SwitchRow(S.NOT_IN_LIVE_TV, R.string.not_live_tv, R.string.not_live_tv_hint),
            SwitchRow(S.KEY_MESSAGE, R.string.show_message, R.string.show_message_hint),
            reset("remote"),
        ), content = "key_test", summary = { c, _ ->
            c.getString(if (io.github.noman13.tvambient.adb.Shell.shortcutsEnabled(c)) R.string.on else R.string.off)
        }),

        Section("stats", R.string.sec_stats, SETUP, listOf(
            ActionRow(R.string.stats_show_now, R.string.stats_show_now_hint, "stats_toggle"),
            ChoiceRow(S.STATS_LAYOUT, R.string.stats_layout, R.string.stats_layout_hint, intArrayOf(R.string.layout_full, R.string.layout_compact, R.string.layout_ticker)),
            ChoiceRow(S.STATS_POSITION, R.string.stats_position, R.string.stats_position_hint,
                intArrayOf(R.string.corner_tl, R.string.corner_tr, R.string.corner_bl, R.string.corner_br)),
            ChoiceRow(S.STATS_SIZE, R.string.text_size, R.string.text_size_hint, intArrayOf(R.string.small, R.string.medium, R.string.large)),
            ChoiceRow(S.STATS_INTERVAL, R.string.refresh, R.string.refresh_hint, intArrayOf(R.string.s05, R.string.s1, R.string.s2), intArrayOf(500, 1000, 2000)),
            SwitchRow(S.STATS_RECORD, R.string.record_file, R.string.record_file_hint),
            HeadingRow(R.string.stats_heading),
            LinkRow(R.string.stats_lights, R.string.stats_group_hint, "stats:lights"),
            LinkRow(R.string.stats_video, R.string.stats_group_hint, "stats:video"),
            LinkRow(R.string.stats_cpu, R.string.stats_group_hint, "stats:cpu"),
            LinkRow(R.string.stats_memory, R.string.stats_group_hint, "stats:memory"),
            LinkRow(R.string.stats_bandwidth, R.string.stats_group_hint, "stats:bandwidth"),
            LinkRow(R.string.stats_music_mode, R.string.stats_group_hint, "stats:music"),
            reset("stats"),
        ), summary = { c, sp -> c.getString(listOf(R.string.layout_full, R.string.layout_compact, R.string.layout_ticker)[S.get(sp, S.STATS_LAYOUT)]) }),

        // ---------------------------------------------------------------- SYSTEM
        Section("power", R.string.sec_power, SYSTEM, listOf(
            SwitchRow(S.BOOT, R.string.start_on_boot, R.string.start_on_boot_hint),
            ChoiceRow(S.SCREEN_OFF, R.string.screen_off, R.string.screen_off_hint, intArrayOf(R.string.screen_off_dark, R.string.screen_off_keep)),
            InfoRow(R.string.usb_debugging) { c, _ -> c.getString(if (AmbientService.sShellOk) R.string.connected else R.string.not_checked) },
            ActionRow(R.string.check_shell, R.string.check_shell_hint, "check_shell"),
            ActionRow(R.string.first_run_again, R.string.first_run_again_hint, "first_run"),
        ), summary = { c, sp -> c.getString(if (S.get(sp, S.BOOT) == 1) R.string.starts_on_boot else R.string.manual_start) }),

        Section("about", R.string.sec_about, SYSTEM, listOf(
            InfoRow(R.string.version) { c, _ -> versionName(c) + " · core " + (Native.version() ?: "?") },
            InfoRow(R.string.protocol) { c, _ -> c.getString(R.string.adalight_short) },
            LinkRow(R.string.help_no_lights, 0, "help:no_lights"),
            LinkRow(R.string.help_no_sound, 0, "help:no_sound"),
            LinkRow(R.string.help_buttons, 0, "help:buttons"),
            LinkRow(R.string.help_stutter, 0, "help:stutter"),
            LinkRow(R.string.licences, 0, "help:licences"),
            ActionRow(R.string.reset_all, R.string.reset_all_hint, "reset_all", twice = true),
        ), summary = { c, _ -> versionName(c) }),
    )

    /** Each section's settings, for "Reset this section": the defs its value, switch, choice and flag rows touch. */
    fun defsOf(section: Section): List<S.Def> = section.rows.mapNotNull { r: Row ->
        when (r) {
            is ValueRow -> r.def
            is SwitchRow -> r.def
            is ChoiceRow -> r.def
            is FlagRow -> r.def
            else -> null
        }
    }.distinct() + when (section.id) {
        "picture" -> listOf(S.BAL_R, S.BAL_G, S.BAL_B)
        "music" -> listOf(S.MUSIC_EFFECT)
        "effects" -> listOf(S.EFFECT)
        "stats" -> listOf(S.STATS_MASK)
        else -> emptyList()
    }

    /** The highest LED rate the baud rate carries for this strip (8N1, 10 bits a byte; TECHNICAL.md §7). */
    fun maxLedRate(sp: SharedPreferences): Int {
        val bytes = 6 + 3 * S.layout(sp).count
        return S.BAUDS[S.get(sp, S.BAUD)] / 10 / bytes.coerceAtLeast(1)
    }

    fun versionName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
}
