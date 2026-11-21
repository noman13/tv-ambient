package io.github.noman13.tvambient.adb

import android.content.ComponentName
import android.content.Context
import io.github.noman13.tvambient.keys.KeyShortcutService
import java.io.ByteArrayOutputStream

/**
 * One-off shell commands over the app's own ADB connection (the same key as the capture helper). Blocking: call
 * from a background thread.
 */
object Shell {
    /** Runs [command] and returns its stdout (stderr too if the command redirects it). */
    fun run(c: Context, command: String): String {
        AdbClient(AdbKey.loadOrCreate(c.noBackupFilesDir, c.filesDir), "tvambient@tv").use { client ->
            client.connect()
            val s = client.exec(command)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val r = s.read(buf, 0, buf.size)
                if (r < 0) break
                out.write(buf, 0, r)
            }
            s.close()
            return out.toString("UTF-8")
        }
    }

    private const val ENABLED = "enabled_accessibility_services"
    private val COMPONENT = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")

    fun shortcutsComponent(c: Context) = ComponentName(c, KeyShortcutService::class.java).flattenToString()

    /** Whether our accessibility service is in the enabled list (read without ADB). */
    fun shortcutsEnabled(c: Context): Boolean {
        val v = android.provider.Settings.Secure.getString(c.contentResolver, ENABLED) ?: return false
        val me = shortcutsComponent(c)
        return v.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /**
     * Remote shortcuts default to **on** (1.0.0). Android keeps the switch, so the app turns it on itself, **once**,
     * the first time its ADB connection works; after that the switch is the person's (turning it off sticks). Without
     * ADB it throws and is tried again next time. Blocking.
     */
    fun enableShortcutsOnce(c: Context) {
        val sp = io.github.noman13.tvambient.ui.SettingsSchema.prefs(c)
        val done = io.github.noman13.tvambient.ui.SettingsSchema.SHORTCUTS_DEFAULT_DONE
        if (io.github.noman13.tvambient.ui.SettingsSchema.get(sp, done) == 1) return
        if (shortcutsEnabled(c)) sp.edit().putInt(done.key, 1).apply() else setShortcuts(c, true)
    }

    /**
     * Turns the colour-button service on or off (TECHNICAL.md §11). On **appends** our entry to whatever is enabled;
     * off removes only ours and deletes the setting if nothing else is left. Other services are never touched.
     */
    fun setShortcuts(c: Context, on: Boolean) {
        val me = shortcutsComponent(c)
        val current = run(c, "settings get secure $ENABLED").trim()
        val others = if (current.isEmpty() || current == "null") emptyList()
        else current.split(':').filter { it.isNotEmpty() && !it.equals(me, ignoreCase = true) }
        // Everything goes back into a quoted shell command: refuse anything that isn't a plain component name
        // rather than drop another app's entry or risk the quoting.
        require(others.all { COMPONENT.matches(it) }) { "unexpected entry in $ENABLED" }
        val list = if (on) others + me else others
        // component names contain only [A-Za-z0-9._/$]; quote for the $ in nested class names
        val cmd = if (list.isEmpty()) "settings delete secure $ENABLED"
        else "settings put secure $ENABLED '${list.joinToString(":")}'" + if (on) " && settings put secure accessibility_enabled 1" else ""
        run(c, cmd)
        // the person's choice from now on: the once-only default never overrides it
        val sp = io.github.noman13.tvambient.ui.SettingsSchema.prefs(c)
        sp.edit().putInt(io.github.noman13.tvambient.ui.SettingsSchema.SHORTCUTS_DEFAULT_DONE.key, 1).apply()
    }
}
