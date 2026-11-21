package io.github.noman13.tvambient.service

import android.content.SharedPreferences
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.ui.SettingsSchema as S

/**
 * The Mode button (Blue) as settings changes (TECHNICAL.md §13, DESIGN.md §11.2).  A **long press** goes to the next mode in the rotation (default Screen → Music → Effect), and a **short
 * press** changes what plays inside the mode: the next music effect in Music mode, the next effect in Effect mode.
 * [AmbientService] reacts to the settings, so the button, ADB and the UI share one path. Each press returns the
 * message to show (DESIGN.md §8), or null when nothing changed.
 */
class ModeController(private val sp: SharedPreferences) {
    val mode get() = S.get(sp, S.MODE)

    /** Short press: the next effect of the current mode (Music or Effect); Screen and Single colour have none. */
    fun shortPress(): String? = when (mode) {
        MUSIC -> nextMusicEffect()
        EFFECT -> nextEffect()
        else -> null
    }

    /** Long press: the next mode in the rotation. */
    fun longPress(): String? {
        if (S.get(sp, S.LONG_PRESS) == 1) return null
        val m = mode
        val rotation = S.get(sp, S.ROTATION)
        for (k in 1..4) {
            val c = (m + k) % 4
            if (rotation and (1 shl c) != 0) {
                if (c == m) return null
                sp.edit().putInt(S.MODE.key, c).apply()
                return name(c)
            }
        }
        return null
    }

    private fun nextEffect(): String? {
        val n = Native.effectCount()
        val cur = S.get(sp, S.EFFECT)
        for (k in 1..n) {
            val id = (cur + k) % n
            val flags = Native.effectFlags(id)
            if (flags >= 0 && S.effectInRotation(sp, id, flags and 2 != 0)) {
                if (id == cur) return null
                sp.edit().putInt(S.EFFECT.key, id).apply()
                return name(EFFECT)
            }
        }
        return null
    }

    /** Music effects in the rotation: all but the flashing ones (Strobe beat ⚡) by default. */
    private fun nextMusicEffect(): String? {
        val n = Native.musicCount()
        val cur = S.get(sp, S.MUSIC_EFFECT)
        for (k in 1..n) {
            val id = (cur + k) % n
            val flags = Native.musicFlags(id)
            if (flags >= 0 && S.musicInRotation(sp, id, flags and 1 == 0)) {
                if (id == cur) return null
                sp.edit().putInt(S.MUSIC_EFFECT.key, id).apply()
                return name(MUSIC)
            }
        }
        return null
    }

    /** The mode's name as shown everywhere (DESIGN.md §3): "Screen", "Music: Spectrum", "Effect: Comet", "Single colour". */
    fun name(m: Int = mode): String = when (m) {
        SCREEN -> "Screen"
        MUSIC -> "Music: " + (Native.musicName(S.get(sp, S.MUSIC_EFFECT)) ?: "?")
        EFFECT -> "Effect: " + (Native.effectName(S.get(sp, S.EFFECT)) ?: "?")
        else -> "Single colour"
    }

    companion object {
        const val SCREEN = 0
        const val MUSIC = 1
        const val EFFECT = 2
        const val SINGLE = 3
    }
}
