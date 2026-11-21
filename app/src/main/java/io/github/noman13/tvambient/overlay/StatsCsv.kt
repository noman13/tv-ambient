package io.github.noman13.tvambient.overlay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Record to file" (TECHNICAL.md §12.5): one CSV row per refresh in the app's own external storage
 * (`Android/data/<package>/files/stats/stats-<yyyyMMdd-HHmmss>.csv`, no permission needed): a timestamp, then each
 * stat shown on the panel. A new file starts when the set of stats changes. At start, files older than 7 days go and
 * the total is kept under 20 MB. Writing happens on its own thread, never the main one.
 */
class StatsCsv(context: Context) {
    private val app = context.applicationContext
    // resolved on the csv thread (prune runs first): getExternalFilesDir can make the folder through a binder call
    private val dir by lazy { File(app.getExternalFilesDir(null) ?: app.filesDir, "stats") }
    private val thread = HandlerThread("tvambient-csv").apply { start() }
    private val h = Handler(thread.looper)
    private var writer: FileWriter? = null // csv thread
    private var set = Int.MIN_VALUE
    private val row = StringBuilder(512) // main thread: built from the panel, handed over as a String
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)

    init {
        h.post { prune() }
    }

    /** Appends the panel's current values (main thread, after [StatsOverlayView.sample]). */
    fun record(panel: StatsOverlayView) {
        val newFile = panel.shownSet != set
        set = panel.shownSet
        row.setLength(0)
        val head = if (newFile) StringBuilder("time") else null
        row.append(stamp.format(Date()))
        panel.forEachShown { group, label, chars, len ->
            head?.append(',')?.append(group)?.append(' ')?.append(label)
            row.append(',').append('"')
            for (i in 0 until len) { val ch = chars[i]; if (ch == '"') row.append("\"\"") else row.append(ch) }
            row.append('"')
        }
        val line = row.toString()
        val header = head?.toString()
        h.post { write(header, line) }
    }

    private fun write(header: String?, line: String) {
        try {
            if (header != null || writer == null) {
                writer?.close()
                dir.mkdirs()
                val name = "stats-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".csv"
                writer = FileWriter(File(dir, name), true).also { it.write((header ?: "time") + "\n") }
            }
            writer?.apply { write(line); write("\n"); flush() }
        } catch (e: Exception) {
            Log.w("TvAmbient", "stats csv: $e")
        }
    }

    /** Files older than 7 days are deleted, then the oldest until the folder is under 20 MB. */
    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }?.sortedBy { it.lastModified() } ?: return
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (f.lastModified() < weekAgo || total > MAX_BYTES) {
                total -= f.length()
                f.delete()
            }
        }
    }

    fun close() {
        h.post {
            runCatching { writer?.close() }
            writer = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
