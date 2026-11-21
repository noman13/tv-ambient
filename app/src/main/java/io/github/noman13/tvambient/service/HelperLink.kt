package io.github.noman13.tvambient.service

import android.util.Log
import io.github.noman13.tvambient.Native
import io.github.noman13.tvambient.adb.AdbClient
import io.github.noman13.tvambient.adb.AdbKey
import java.io.File
import java.nio.ByteBuffer

/**
 * Runs the capture helper over the TV's own ADB (TECHNICAL.md §3, §4) and delivers its output: `TVA2` frames to
 * [onFrame] (a direct buffer, valid during the call) and `TVS1` stats to [onStats]. Reconnects after errors.
 * All buffers are allocated once; one thread reads.
 */
class HelperLink(
    /** where the ADB key lives (the no-backup folder), and the older folder a previous build kept it in */
    private val keyDir: File,
    private val legacyKeyDir: File,
    private val apk: String,
    private val onFrame: (ByteBuffer, Int) -> Unit,
    private val onStats: (IntArray, IntArray, Int) -> Unit,
    /** `TVP1` playback PCM (source B): a direct little-endian buffer of 16-bit samples, valid during the call. */
    private val onPcm: (ByteBuffer, Int) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var stream: AdbClient.Stream? = null
    @Volatile private var client: AdbClient? = null
    private var thread: Thread? = null

    /** Starts (or restarts) the helper with these analyser settings (hex) and rate cap. `statsOnly` = no capture. */
    fun start(configHex: String, maxRate: Int, statsOnly: Boolean, pcm: Boolean = false, adaptive: Boolean = false) {
        stop()
        running = true
        val gen = Any()
        current = gen
        thread = Thread({ loop(gen, configHex, maxRate, statsOnly, pcm, adaptive) }, "tvambient-helper").apply { start() }
    }

    @Volatile private var current: Any? = null

    /** Stats sampling wanted by the overlay; sent again whenever the helper restarts. */
    @Volatile var statsOn = false
        private set
    @Volatile var statsIntervalMs = 1000
        private set
    @Volatile private var mask = ByteArray(8)

    fun setStats(on: Boolean, intervalMs: Int) {
        statsOn = on
        statsIntervalMs = intervalMs
        control(3, byteArrayOf(intervalMs.toByte(), (intervalMs shr 8).toByte()))
        control(2, byteArrayOf(if (on) 1 else 0))
    }

    /** Pixels the analyser ignores, in capture pixels (an all-zero rectangle clears it). */
    fun setMask(p: ByteArray) {
        mask = p
        control(1, p)
    }

    fun stop() {
        running = false
        current = null
        // Closing the socket ends the helper (its stdin closes). No CLSE packet: stop() may run on the main
        // thread, where sockets can't be written. The stream goes first, so the control writer sees a deliberate stop.
        stream = null
        client?.close()
        thread?.interrupt()
        thread = null
    }

    /** `TVC1` messages waiting for the writer thread: sockets can't be written on the main thread. */
    private val outbox = java.util.concurrent.LinkedBlockingQueue<ByteArray>(64)

    /** Queues a `TVC1` control message for the helper's stdin (dropped if it isn't running). Any thread. */
    fun control(type: Int, payload: ByteArray) {
        if (stream == null) return // sent again from the current state once the helper is up
        val msg = ByteArray(7 + payload.size)
        msg[0] = 'T'.code.toByte(); msg[1] = 'V'.code.toByte(); msg[2] = 'C'.code.toByte(); msg[3] = '1'.code.toByte()
        msg[4] = type.toByte(); msg[5] = payload.size.toByte(); msg[6] = (payload.size shr 8).toByte()
        System.arraycopy(payload, 0, msg, 7, payload.size)
        outbox.offer(msg)
    }

    /** Writes queued control messages to [s] until it closes. */
    private fun writer(s: AdbClient.Stream) = Thread({
        try {
            while (stream === s) {
                val m = outbox.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                s.write(m)
            }
        } catch (e: Exception) {
            if (stream === s) Log.w(TAG, "helper control: $e")
        }
    }, "tvambient-control").apply { isDaemon = true; start() }

    private fun loop(gen: Any, configHex: String, maxRate: Int, statsOnly: Boolean, pcm: Boolean, adaptive: Boolean) {
        val buf = ByteArray(64 * 1024)
        val frame = ByteBuffer.allocateDirect(64 * 1024)
        val ids = IntArray(64)
        val values = IntArray(64)
        while (running && current === gen) {
            // this loop's own connection: a restart's new loop may already own the fields when this one cleans up
            var myClient: AdbClient? = null
            var myStream: AdbClient.Stream? = null
            try {
                onStatus("Connecting to the TV's ADB (accept \"Allow USB debugging?\" on the TV the first time)")
                val key = AdbKey.loadOrCreate(keyDir, legacyKeyDir)
                val c = AdbClient(key, "tvambient@tv")
                myClient = c
                if (current !== gen) break
                client = c
                c.connect()
                val cmd = buildString {
                    append("CLASSPATH='").append(apk).append("' app_process / io.github.noman13.tvambient.capture.CaptureHelper")
                    append(" --apk '").append(apk).append("'").append(" --config ").append(configHex).append(" --rate ").append(maxRate)
                    if (statsOnly) append(" --stats-only")
                    if (pcm) append(" --pcm")
                    if (adaptive) append(" --adaptive")
                    append(" 2>/dev/null")
                }
                val s = c.exec(cmd)
                myStream = s
                AmbientService.sShellOk = true
                if (current !== gen) break
                outbox.clear()
                stream = s
                writer(s)
                setStats(statsOn, statsIntervalMs)
                control(1, mask)
                onStatus(if (statsOnly) "Stats only" else "Capturing (shell screenshots)")
                var n = 0
                while (running && current === gen) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                    var at = 0
                    while (n - at >= 6) {
                        val isFrame = magic(buf, at, 'A')
                        val isStats = magic(buf, at, 'S')
                        val isPcm = magic(buf, at, 'P')
                        if (!isFrame && !isStats && !isPcm) { at++; continue } // native libraries may print text first: resync
                        if (isPcm) {
                            val count = (buf[at + 4].toInt() and 0xff) or ((buf[at + 5].toInt() and 0xff) shl 8)
                            val len = 6 + 2 * count
                            if (count > 4800) { at++; continue } // not a real record: resync
                            if (n - at < len) break
                            frame.clear()
                            frame.put(buf, at + 6, 2 * count)
                            onPcm(frame, count)
                            at += len
                            continue
                        }
                        if (isFrame) {
                            if (n - at < Native.TVA2_HEADER) break
                            val leds = (buf[at + 6].toInt() and 0xff) or ((buf[at + 7].toInt() and 0xff) shl 8)
                            val len = Native.TVA2_HEADER + 3 * leds
                            if (n - at < len) break
                            frame.clear()
                            frame.put(buf, at, len)
                            onFrame(frame, len)
                            at += len
                        } else {
                            val count = buf[at + 5].toInt() and 0xff
                            val len = 6 + 5 * count
                            if (n - at < len) break
                            val k = minOf(count, ids.size)
                            for (i in 0 until k) {
                                val o = at + 6 + 5 * i
                                ids[i] = buf[o].toInt() and 0xff
                                values[i] = (buf[o + 1].toInt() and 0xff) or ((buf[o + 2].toInt() and 0xff) shl 8) or
                                    ((buf[o + 3].toInt() and 0xff) shl 16) or (buf[o + 4].toInt() shl 24)
                            }
                            onStats(ids, values, k)
                            at += len
                        }
                    }
                    System.arraycopy(buf, at, buf, 0, n - at)
                    n -= at
                }
            } catch (e: Exception) {
                if (!running || current !== gen) break
                Log.w(TAG, "helper link: $e")
                onStatus("Shell capture unavailable: ${e.message} (is USB debugging on, and \"Allow USB debugging\" accepted?)")
            } finally {
                if (stream === myStream) stream = null
                if (client === myClient) client = null
                myStream?.close()
                myClient?.close()
            }
            if (running && current === gen) {
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
        }
    }

    private fun magic(b: ByteArray, at: Int, kind: Char) =
        b[at] == 'T'.code.toByte() && b[at + 1] == 'V'.code.toByte() && b[at + 2] == kind.code.toByte() &&
            b[at + 3] == (if (kind == 'A') '2' else '1').code.toByte()

    companion object {
        const val TAG = "TvAmbient"
    }
}
