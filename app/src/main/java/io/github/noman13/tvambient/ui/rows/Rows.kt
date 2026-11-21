package io.github.noman13.tvambient.ui.rows

import android.content.Context
import android.content.SharedPreferences
import io.github.noman13.tvambient.ui.SettingsSchema.Def

/**
 * The settings UI as data (DESIGN.md §4.2, §12): a section is a list of rows, and every row is one of five kinds
 * (value, switch, choice, link, action) plus non-focusable headings, info lines and the "More" divider. The views are
 * generated from these, so every screen behaves the same and adding a setting is one table entry.
 */
sealed class Row(val label: Int, val hint: Int) {
    /** Why the row doesn't apply right now (a string resource), or null when it does (§4.2: dimmed, with the reason). */
    var unless: ((SharedPreferences) -> Int?)? = null

    fun disabledReason(sp: SharedPreferences): Int? = unless?.invoke(sp)

    open val focusable get() = true
}

/** `Brightness   ◀  35 %  ▶` with a thin bar. Left/Right change it; holding speeds up after 400 ms. */
class ValueRow(
    val def: Def, label: Int, hint: Int, val step: Int = 1,
    val format: (Context, Int) -> String = { _, v -> v.toString() },
) : Row(label, hint)

/** `Start on boot   ● On`. OK or Left/Right toggles. [inverted]: the stored 1 means Off. */
class SwitchRow(val def: Def, label: Int, hint: Int, val inverted: Boolean = false) : Row(label, hint)

/**
 * A switch whose state lives outside the app's settings (Android's own, e.g. whether the remote-shortcuts
 * accessibility service is on): [isOn] reads it, OK runs [action] through the host, which changes it. Still a switch
 * row (DESIGN.md §4.2), only its storage differs.
 */
class SystemSwitchRow(label: Int, hint: Int, val action: String, val isOn: (android.content.Context) -> Boolean) : Row(label, hint)

/** A switch for one bit of a bitmask setting (the modes in the Blue rotation, the stats on the panel). [keepOne]: the
 * last set bit can't be cleared ("at least one mode must stay ticked"). */
class FlagRow(val def: Def, val bit: Int, label: Int, hint: Int, val keepOne: Boolean = false) : Row(label, hint)

/**
 * `Audio source   Automatic ▾`. OK opens a short list in place; Left/Right step through the choices. [values] maps a
 * choice's index to the stored value (default: the index itself).
 */
class ChoiceRow(val def: Def, label: Int, hint: Int, val choices: IntArray, val values: IntArray? = null) : Row(label, hint) {
    fun indexOf(stored: Int): Int = values?.indexOf(stored)?.takeIf { it >= 0 } ?: stored.coerceIn(0, choices.size - 1)
    fun valueAt(index: Int): Int = values?.get(index) ?: index
}

/** `Music effect   Spectrum   ›`. OK or Right opens the sub-page [page]. */
class LinkRow(label: Int, hint: Int, val page: String, val value: (Context, SharedPreferences) -> String = { _, _ -> "" }) : Row(label, hint)

/** `[ Test pattern ]`. OK runs [action] (handled by the Settings screen). [twice]: asks for a second OK first. */
class ActionRow(label: Int, hint: Int, val action: String, val twice: Boolean = false) : Row(label, hint)

/** A small group heading inside a section (`LOOK`, `REACTION`). Not focusable. */
class HeadingRow(label: Int) : Row(label, 0) {
    override val focusable get() = false
}

/** A read-only line, for example "USB debugging · Connected". Not focusable. */
class InfoRow(label: Int, val value: (Context, SharedPreferences) -> String) : Row(label, 0) {
    override val focusable get() = false
}

/** The thin "More" divider before the rarely used rows (§3). Not focusable. */
class MoreRow : Row(0, 0) {
    override val focusable get() = false
}

/**
 * One section of Settings. [summary] is the dim one-line state under its name in the list ("Music mode · Automatic ·
 * Spectrum"). [content]: what the right pane shows besides rows: the effect tile grid, the strip frame and so on.
 */
class Section(
    val id: String, val label: Int, val group: Int, val rows: List<Row>,
    val content: String? = null,
    val summary: (Context, SharedPreferences) -> String = { _, _ -> "" },
)
