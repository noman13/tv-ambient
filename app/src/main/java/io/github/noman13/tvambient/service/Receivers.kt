package io.github.noman13.tvambient.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.noman13.tvambient.ui.SettingsSchema as S

/** Starts the lights after boot when "Start on boot" is on (the default). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && S.get(S.prefs(c), S.BOOT) == 1) AmbientService.start(c)
    }
}

/**
 * Changes settings or starts/stops without opening the app (which would pause the video being measured). Only
 * senders holding android.permission.DUMP can reach it, which in practice means ADB :
 *   adb shell am broadcast -a io.github.noman13.tvambient.SET -n io.github.noman13.tvambient/.service.SettingsReceiver \
 *       --ei capture_rate 20 --ez start true
 */
class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val sp = S.prefs(c)
        val e = sp.edit()
        val changed = StringBuilder("settings from ADB:")
        for (d in S.ALL) {
            if (i.hasExtra(d.key)) {
                val v = i.getIntExtra(d.key, d.default).coerceIn(d.min, d.max)
                e.putInt(d.key, v)
                changed.append(' ').append(d.key).append('=').append(v)
            }
        }
        e.apply()
        Log.i(AmbientService.TAG, changed.toString())
        // --ez dump true: every setting that differs from its default, for checking a TV's state over ADB
        if (i.getBooleanExtra("dump", false)) {
            val diff = S.ALL.filter { S.get(sp, it) != it.default }.joinToString(" ") { "${it.key}=${S.get(sp, it)}(default ${it.default})" }
            Log.i(AmbientService.TAG, "settings not at default: " + diff.ifEmpty { "none" })
        }
        when {
            i.getBooleanExtra("stop", false) -> AmbientService.stop(c)
            i.getBooleanExtra("start", false) -> AmbientService.start(c)
        }
    }
}
