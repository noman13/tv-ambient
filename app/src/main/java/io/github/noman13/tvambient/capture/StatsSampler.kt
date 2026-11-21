package io.github.noman13.tvambient.capture

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * System-wide stats for the overlay (TECHNICAL.md §12.2), sampled in the helper because only the shell user can read
 * other processes' `/proc` entries. Sampled once per interval (never per frame) and sent as a `TVS1` record:
 * "TVS1", version 1, count, then count × (id u8, value i32 LE). Ids are [StatId].
 */
class StatsSampler {
    private var captures = 0
    private var failures = 0
    private var captureUsSum = 0L
    private var captureUsMax = 0
    private var lastAt = SystemClock.uptimeMillis()
    private val cpu = HashMap<Int, Long>() // pid -> last utime+stime ticks
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var lastWallMs = SystemClock.uptimeMillis()
    private val pids = HashMap<String, Int>()
    private var pidsAt = 0L
    private var layer: String? = null
    private var layerAt = 0L
    private var lastPresent = 0L

    fun noteCapture(us: Int, ok: Boolean) {
        if (ok) captures++ else failures++
        captureUsSum += us
        if (us > captureUsMax) captureUsMax = us
    }

    /** From the last sample, for the adaptive rate: late video frames, whole-device CPU ×10, available memory MB. */
    var lateFrames = 0
        private set
    /** The rate the helper aims for (the adaptive one, or the fixed setting), sent as [StatId.TARGET_RATE]. */
    var targetRate = 0
    var systemCpuX10 = 0
        private set
    var memAvailMb = 0
        private set
    /** The playing video's frame rate ×10, 0 when no video layer is found */
    var videoFpsX10 = 0
        private set

    /**
     * Samples everything and writes one `TVS1` record into `buf` (position 0 .. limit) when [emit]; with `emit` false
     * it only updates [lateFrames], [systemCpuX10] and [memAvailMb] (the adaptive rate's view).
     */
    fun writeRecord(buf: ByteBuffer, capturePath: String?, emit: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastAt).coerceAtLeast(1)
        val entries = ArrayList<Pair<Int, Int>>(16)
        if (capturePath != null) {
            entries += StatId.CAPTURE_RATE_X10 to (captures * 10_000L / dt).toInt()
            entries += StatId.CAPTURE_US_AVG to if (captures + failures > 0) (captureUsSum / (captures + failures)).toInt() else 0
            entries += StatId.CAPTURE_US_MAX to captureUsMax
            entries += StatId.CAPTURE_FAILED to failures
        }
        if (capturePath != null && targetRate > 0) entries += StatId.TARGET_RATE to targetRate
        captures = 0; failures = 0; captureUsSum = 0; captureUsMax = 0; lastAt = now

        if (now - pidsAt > 10_000 || pids.isEmpty()) findPids(now)
        val (total, idle) = readProcStat()
        val dTotal = (total - lastTotal).coerceAtLeast(1)
        if (lastTotal != 0L) {
            systemCpuX10 = (1000 - (idle - lastIdle) * 1000 / dTotal).toInt()
            entries += StatId.SYSTEM_CPU_X10 to systemCpuX10
        }
        memAvailMb = readMemAvailMb() ?: 0
        val wallTicks = (now - lastWallMs) * TICKS_PER_SEC / 1000
        for ((key, id) in listOf("self" to StatId.HELPER_CPU_X10, "app" to StatId.APP_CPU_X10, "mmdisp" to StatId.MMDISP_CPU_X10,
                "surfaceflinger" to StatId.SF_CPU_X10)) {
            val pid = if (key == "self") android.os.Process.myPid() else pids[key] ?: continue
            val ticks = procTicks(pid) ?: continue
            val prev = cpu.put(pid, ticks)
            if (prev != null && wallTicks > 0) entries += id to ((ticks - prev) * 1000 / wallTicks).toInt()
        }
        lastTotal = total; lastIdle = idle; lastWallMs = now
        readRssKb("self")?.let { entries += StatId.HELPER_RSS_KB to it }
        readFreqKhz()?.let { entries += StatId.CPU_FREQ_KHZ to it }
        lateFrames = 0
        videoFpsX10 = 0
        videoTiming(now)?.let { (fpsX10, late) ->
            videoFpsX10 = fpsX10
            entries += StatId.VIDEO_FPS_X10 to fpsX10
            entries += StatId.LATE_FRAMES to late
            lateFrames = late
        }
        if (!emit) return

        buf.clear()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.put('T'.code.toByte()).put('V'.code.toByte()).put('S'.code.toByte()).put('1'.code.toByte())
        buf.put(1).put(entries.size.toByte())
        for ((id, v) in entries) buf.put(id.toByte()).putInt(v)
        buf.flip()
    }

    private fun findPids(now: Long) {
        pidsAt = now
        pids.clear()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ').trim() }.getOrNull() ?: return@forEach
            when {
                cmd == "io.github.noman13.tvambient" -> pids["app"] = pid
                cmd.contains("mmdisp") -> pids["mmdisp"] = pid
                cmd == "/system/bin/surfaceflinger" || cmd == "surfaceflinger" -> pids["surfaceflinger"] = pid
            }
        }
    }

    private fun readProcStat(): Pair<Long, Long> {
        val line = runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull() ?: return 0L to 0L
        val v = line.split(' ').filter { it.isNotEmpty() }.drop(1).mapNotNull { it.toLongOrNull() }
        return v.sum() to (v.getOrElse(3) { 0 } + v.getOrElse(4) { 0 })
    }

    private fun procTicks(pid: Int): Long? {
        val s = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        return (f.getOrNull(11)?.toLongOrNull() ?: return null) + (f.getOrNull(12)?.toLongOrNull() ?: 0)
    }

    private fun readRssKb(pid: String): Int? = runCatching {
        File("/proc/$pid/status").useLines { l -> l.first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toInt() }
    }.getOrNull()

    private fun readMemAvailMb(): Int? = runCatching {
        File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemAvailable:") }.filter { it.isDigit() }.toInt() / 1024 }
    }.getOrNull()

    private fun readFreqKhz(): Int? = runCatching {
        File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toInt()
    }.getOrNull()

    /** Frames presented on the player's layer since the last sample, from SurfaceFlinger's own frame timing,
     *  through an in-process binder `dump` (no dumpsys process). The layer is chosen again every 5 s. */
    private fun videoTiming(now: Long): Pair<Int, Int>? {
        if (layer == null || now - layerAt > 5_000) {
            layerAt = now
            layer = pickLayer()
        }
        val l = layer ?: return null
        val text = sfDump(arrayOf("--latency", l)) ?: return null
        val times = text.lineSequence().drop(1).mapNotNull { row ->
            val p = row.trim().split('\t', ' ').filter { it.isNotEmpty() }
            p.getOrNull(1)?.toLongOrNull()?.takeIf { it in 1 until Long.MAX_VALUE }
        }.sorted().toList()
        val fresh = times.filter { it > lastPresent }
        if (fresh.size < 2) return null
        val span = fresh.last() - fresh.first()
        val fps = ((fresh.size - 1) * 10_000_000_000L / span.coerceAtLeast(1)).toInt()
        val frame = span / (fresh.size - 1)
        val late = fresh.zipWithNext().count { (a, b) -> b - a > frame * 3 / 2 }
        lastPresent = fresh.last()
        return fps to late
    }

    private fun pickLayer(): String? {
        val list = sfDump(arrayOf("--list")) ?: return null
        val candidates = list.lineSequence().map { it.trim() }.filter { it.startsWith("SurfaceView[") && it.contains("(BLAST)") }.toList()
        return candidates.maxByOrNull { name ->
            val t = sfDump(arrayOf("--latency", name)) ?: return@maxByOrNull 0L
            t.lineSequence().drop(1).mapNotNull { it.trim().split('\t', ' ').getOrNull(1)?.toLongOrNull() }.filter { it in 1 until Long.MAX_VALUE }.maxOrNull() ?: 0L
        }
    }

    private fun sfDump(args: Array<String>): String? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "SurfaceFlinger") as IBinder
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = FileInputStream(pipe[0].fileDescriptor)
        val text = StringBuilder()
        val t = Thread {
            val b = ByteArray(8192)
            while (true) {
                val n = reader.read(b)
                if (n <= 0) break
                text.append(String(b, 0, n))
            }
        }
        t.start()
        binder.dump(pipe[1].fileDescriptor, args)
        pipe[1].close()
        t.join(1000)
        pipe[0].close()
        text.toString()
    }.getOrNull()

    companion object {
        private const val TICKS_PER_SEC = 100L
    }
}

/** `TVS1` stat ids (TECHNICAL.md §12.2). Rates and CPU are × 10 (one decimal); CPU is % of one core. */
object StatId {
    const val CAPTURE_RATE_X10 = 1
    const val CAPTURE_US_AVG = 2
    const val CAPTURE_US_MAX = 3
    const val CAPTURE_FAILED = 4
    const val HELPER_CPU_X10 = 5
    const val SYSTEM_CPU_X10 = 6
    const val MMDISP_CPU_X10 = 7
    const val SF_CPU_X10 = 8
    const val HELPER_RSS_KB = 9
    const val APP_CPU_X10 = 10
    const val VIDEO_FPS_X10 = 11
    const val LATE_FRAMES = 12
    const val CPU_FREQ_KHZ = 13
    // 14, 15: retired (they described another app's process); not reused
    /** the capture rate the helper aims for now (adaptive or fixed), per second */
    const val TARGET_RATE = 16
}
