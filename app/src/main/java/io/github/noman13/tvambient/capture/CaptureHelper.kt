package io.github.noman13.tvambient.capture

import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.NativeConfig
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * The capture helper (TECHNICAL.md §4, §6). Runs as the shell user, started by the app over its own ADB connection:
 *
 *   CLASSPATH=<apk> app_process / io.github.noman13.tvambient.capture.CaptureHelper --apk <apk> --config <hex>
 *       --rate <max captures/s> [--size 128x72] [--stats-only]
 *
 * It analyses each capture with the Rust core in this process and writes `TVA2` records to stdout (LED colours and
 * bar geometry, ~600 bytes, instead of the whole frame), and `TVS1` stats records when asked. It reads `TVC1`
 * control messages on stdin (overlay mask, stats on/off and interval). A write error on stdout means the app has
 * gone: the helper exits.
 */
object CaptureHelper {
    @Volatile private var pendingMask: IntArray? = null
    @Volatile var statsOn = false
    @Volatile var statsIntervalMs = 1000

    /** How often the adaptive rate looks at the pressure, ms. */
    private const val ADAPT_MS = 2000L

    @JvmStatic
    fun main(args: Array<String>) {
        val a = parse(args)
        val apk = a["apk"] ?: fail("--apk missing")
        val (w, h) = (a["size"] ?: "128x72").split('x').map { it.toInt() }
        // The capturer first: on Android 14 its display-token lookup loads libandroid_servers.so through
        // services.jar's class loader, and that fails ("libadb_pairing_server.so not found") once our own .so has
        // been loaded into the system class loader's namespace.
        // --stats-only: no capture (stats for the panel, or with --pcm just the audio of Music mode, source B);
        // --pcm alone with capture: a music preset that shows the screen on two sides
        val noCapture = a.containsKey("stats-only")
        val capturer = if (noCapture) null else ScreenCapturer.create(w, h)
        loadCore(apk)
        val cfg = NativeConfig.unhex(a["config"] ?: fail("--config missing"))
        val leds = (0 until 4).sumOf { k -> (cfg[1 + 2 * k].toInt() and 0xff) or ((cfg[2 + 2 * k].toInt() and 0xff) shl 8) }
        val maxRate = (a["rate"] ?: "20").toInt().coerceIn(1, 60)
        val out = FileOutputStream(FileDescriptor.out).channel
        startControlReader()

        val stats = StatsSampler()
        val statsBuf = ByteBuffer.allocateDirect(512)
        if (a.containsKey("pcm")) startPcm(out)
        if (noCapture) {
            if (!a.containsKey("pcm")) statsOn = true // stats-only for the panel; with --pcm the app asks over TVC1
            while (true) {
                if (statsOn) {
                    stats.writeRecord(statsBuf, null)
                    writeAll(out, statsBuf)
                }
                SystemClock.sleep(statsIntervalMs.toLong())
            }
        }

        val analyzer = Native.analyzerNew(cfg)
        if (analyzer == 0L) fail("bad --config")
        capturer!!
        System.err.println("tvambient helper: ${capturer.name}, $leds LEDs, up to $maxRate/s")
        val frame = ByteBuffer.allocateDirect(Native.TVA2_HEADER + 3 * leds)
        var seq = 0
        var avgUs = 0
        var nextStats = SystemClock.uptimeMillis()
        // --adaptive: the rate follows the pressure on the video, CPU and memory; --rate is its ceiling
        val adaptive = if (a.containsKey("adaptive")) Native.adaptiveNew(15) else 0L
        var adaptiveRate = 15
        var nextAdapt = SystemClock.uptimeMillis() + ADAPT_MS
        var lastAdapt = SystemClock.uptimeMillis()
        while (true) {
            val t0 = SystemClock.uptimeMillis()
            pendingMask?.let { m ->
                Native.analyzerSetMask(analyzer, m[0], m[1], m[2], m[3])
                pendingMask = null
            }
            val len = capturer.captureInto(analyzer, frame, seq++, System.nanoTime())
            if (len > 0) {
                frame.position(0).limit(len)
                writeAll(out, frame)
                frame.clear()
            }
            avgUs = if (avgUs == 0) capturer.lastCaptureUs else (avgUs * 7 + capturer.lastCaptureUs) / 8
            stats.noteCapture(capturer.lastCaptureUs, len > 0)
            val wantStats = statsOn && t0 >= nextStats
            val wantAdapt = adaptive != 0L && t0 >= nextAdapt
            if (wantStats || wantAdapt) {
                stats.targetRate = if (adaptive != 0L) adaptiveRate else maxRate
                stats.writeRecord(statsBuf, capturer.name, emit = wantStats)
                if (wantStats) {
                    writeAll(out, statsBuf)
                    nextStats = t0 + statsIntervalMs
                }
                if (wantAdapt) {
                    adaptiveRate = Native.adaptiveStep(adaptive, stats.lateFrames, (avgUs + 999) / 1000, stats.systemCpuX10 / 10,
                        stats.memAvailMb, stats.videoFpsX10, (t0 - lastAdapt).toInt(), maxRate).coerceAtLeast(1)
                    lastAdapt = t0
                    nextAdapt = t0 + ADAPT_MS
                }
            }
            // §7 E4: never let capture use more than half the time between captures (the adaptive rate does too)
            val rate = if (adaptive != 0L) adaptiveRate else minOf(maxRate, Native.rateChoose(0, 0, 0, -1, (avgUs + 999) / 1000))
            val sleep = 1000L / rate - (SystemClock.uptimeMillis() - t0)
            if (sleep > 0) SystemClock.sleep(sleep)
        }
    }

    /** One record at a time: the PCM thread and the main loop share stdout. */
    private fun writeAll(out: java.nio.channels.FileChannel, b: ByteBuffer) = synchronized(out) {
        try {
            while (b.hasRemaining()) out.write(b)
        } catch (e: Exception) {
            System.exit(0) // the app closed the stream
        }
    }

    /**
     * Source B (TECHNICAL.md §14.1): playback PCM as `TVP1` records, 10 ms each: "TVP1", sample count (u16 LE),
     * then 48 kHz mono 16-bit little-endian samples. The policy is unregistered when the helper exits.
     */
    private fun startPcm(out: java.nio.channels.FileChannel) {
        val capture = PlaybackCapture()
        val rec = try {
            capture.start()
        } catch (e: Throwable) {
            System.err.println("tvambient helper: playback capture unavailable: $e")
            return
        }
        Runtime.getRuntime().addShutdownHook(Thread { capture.stop() })
        val samples = PlaybackCapture.RATE / 100
        // AudioRecord.read(ByteBuffer) always writes from index 0 whatever the position, so read into an array at
        // offset 6, after the header (measured: the ByteBuffer form overwrote every header)
        val arr = ByteArray(6 + 2 * samples)
        arr[0] = 'T'.code.toByte(); arr[1] = 'V'.code.toByte(); arr[2] = 'P'.code.toByte(); arr[3] = '1'.code.toByte()
        val buf = ByteBuffer.wrap(arr)
        Thread({
            while (true) {
                val n = rec.read(arr, 6, 2 * samples) // 16-bit PCM, little-endian bytes
                if (n <= 0) { SystemClock.sleep(10); continue }
                val count = n / 2
                arr[4] = count.toByte(); arr[5] = (count shr 8).toByte()
                buf.clear().limit(6 + 2 * count)
                writeAll(out, buf)
            }
        }, "tvambient-pcm").apply { isDaemon = true; start() }
    }

    /** `TVC1` messages on stdin: 1 mask (4 x u16, capture pixels), 2 stats on/off, 3 stats interval ms. */
    private fun startControlReader() {
        val t = Thread({
            val input = FileInputStream(FileDescriptor.`in`)
            val buf = ByteArray(256)
            var n = 0
            while (true) {
                val r = try { input.read(buf, n, buf.size - n) } catch (e: Exception) { -1 }
                if (r <= 0) System.exit(0) // stdin closed: the app is gone
                n += r
                var at = 0
                while (n - at >= 7) {
                    if (buf[at] != 'T'.code.toByte() || buf[at + 1] != 'V'.code.toByte() || buf[at + 2] != 'C'.code.toByte() || buf[at + 3] != '1'.code.toByte()) {
                        at++
                        continue
                    }
                    val type = buf[at + 4].toInt()
                    val len = (buf[at + 5].toInt() and 0xff) or ((buf[at + 6].toInt() and 0xff) shl 8)
                    if (n - at < 7 + len) break
                    val p = at + 7
                    fun u16(i: Int) = (buf[p + i].toInt() and 0xff) or ((buf[p + i + 1].toInt() and 0xff) shl 8)
                    when (type) {
                        1 -> if (len == 8) pendingMask = intArrayOf(u16(0), u16(2), u16(4), u16(6))
                        2 -> if (len == 1) statsOn = buf[p].toInt() != 0
                        3 -> if (len == 2) statsIntervalMs = u16(0).coerceIn(250, 5000)
                    }
                    at += 7 + len
                }
                System.arraycopy(buf, at, buf, 0, n - at)
                n -= at
            }
        }, "tvambient-control")
        t.isDaemon = true
        t.start()
    }

    /** Loads `libtvambient.so` straight from the APK (uncompressed, page-aligned), or from a copy if that fails. */
    private fun loadCore(apk: String) {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        var last: Throwable? = null
        for (abi in abis) {
            try {
                Native.loadedFrom("$apk!/lib/$abi/libtvambient.so")
                return
            } catch (t: Throwable) {
                last = t
            }
        }
        // fallback (TECHNICAL.md §4.2, §18): extract to /data/local/tmp, which the shell user can write
        ZipFile(apk).use { zip ->
            for (abi in abis) {
                val e = zip.getEntry("lib/$abi/libtvambient.so") ?: continue
                val dir = File("/data/local/tmp/tvambient/$abi").apply { mkdirs() }
                val f = File(dir, "libtvambient.so")
                if (f.length() != e.size) zip.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                Native.loadedFrom(f.absolutePath)
                return
            }
        }
        throw IllegalStateException("can't load libtvambient.so from $apk", last)
    }

    private fun parse(args: Array<String>): Map<String, String> {
        val m = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val k = args[i].removePrefix("--")
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                m[k] = args[i + 1]; i += 2
            } else {
                m[k] = ""; i++
            }
        }
        return m
    }

    private fun fail(msg: String): Nothing {
        System.err.println("tvambient helper: $msg")
        System.exit(2)
        throw IllegalStateException(msg)
    }
}
