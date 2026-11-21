package io.github.noman13.tvambient.ui

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.noman13.tvambient.R
import io.github.noman13.tvambient.ui.SettingsSchema as S
import kotlin.math.PI
import kotlin.math.sin

/**
 * Music mode's two tools (TECHNICAL.md §14.2): **Tap to beat** plays a 120 BPM click and sets the audio delay from 8
 * taps on OK (after 2 to settle in), using the audio clock's timestamp so the click times are when they're heard; and
 * **Test audio input** plays a 1 kHz tone for 1 s and checks that the output mix (source A) sees that band rise by more
 * than 20 dB. Nothing is recorded or kept. [report] shows a line in the Settings hint.
 */
class AudioTools(private val context: Context, private val sp: SharedPreferences, private val report: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var tapping = false
    private val taps = LongArray(10)
    private var tapCount = 0
    private var clickStartNs = 0L // when the first click is heard (nanoTime)

    /** While Tap to beat runs, OK presses are taps. */
    fun onKey(e: KeyEvent): Boolean {
        if (!tapping) return false
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (!ok) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) tap(System.nanoTime())
        return true
    }

    fun tapToBeat() {
        stop()
        val rate = 48_000
        val beat = rate / 2 // 120 BPM
        val seconds = 12
        val pcm = ShortArray(rate * seconds)
        for (b in 0 until seconds * 2) {
            val at = b * beat
            for (i in 0 until 480) { // a 10 ms 2 kHz click
                pcm[at + i] = (sin(2 * PI * 2000 * i / rate) * 12000 * (1 - i / 480.0)).toInt().toShort()
            }
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        t.write(pcm, 0, pcm.size)
        track = t
        tapCount = 0
        tapping = true
        clickStartNs = 0L
        t.play()
        report(context.getString(R.string.tap_now, 0))
        // the audio clock: frame 0 is heard at (timestamp.nanoTime - framePosition / rate)
        main.postDelayed({
            val ts = AudioTimestamp()
            if (track === t && t.getTimestamp(ts)) clickStartNs = ts.nanoTime - ts.framePosition * 1_000_000_000L / rate
        }, 600)
        main.postDelayed({ if (track === t) finishTaps() }, seconds * 1000L)
    }

    private fun tap(now: Long) {
        if (tapCount < taps.size) taps[tapCount++] = now
        val counted = (tapCount - 2).coerceAtLeast(0)
        report(context.getString(R.string.tap_now, counted))
        if (counted >= 8) finishTaps()
    }

    private fun finishTaps() {
        tapping = false
        val start = clickStartNs
        stopTrack()
        val n = tapCount - 2
        if (n < 4 || start == 0L) {
            report(context.getString(R.string.tap_too_few))
            return
        }
        // each tap's offset from the nearest click, in ms; the median is the delay
        val offs = IntArray(n) { k ->
            val sinceStart = (taps[k + 2] - start) / 1_000_000.0
            val nearest = Math.round(sinceStart / 500.0) * 500.0
            (sinceStart - nearest).toInt()
        }
        offs.sort()
        val delay = (offs[n / 2] / 10 * 10).coerceIn(S.MUSIC_DELAY.min, S.MUSIC_DELAY.max)
        sp.edit().putInt(S.MUSIC_DELAY.key, delay).apply()
        report(context.getString(R.string.tap_done, delay))
    }

    fun testInput() {
        stop()
        val vis = try {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                enabled = true
            }
        } catch (e: Throwable) {
            report(context.getString(R.string.audio_test_fail))
            return
        }
        visualizer = vis
        report(context.getString(R.string.audio_test_running))
        val rate = 48_000
        val fft = ByteArray(vis.captureSize)
        // the bins round 1 kHz: bin k covers k * rate / captureSize
        val k0 = (1000.0 * vis.captureSize / rate).toInt().coerceAtLeast(1)
        fun level(): Double {
            if (vis.getFft(fft) != Visualizer.SUCCESS) return -96.0
            var e = 0.0
            for (k in k0 - 1..k0 + 1) {
                val re = fft[2 * k].toDouble(); val im = fft[2 * k + 1].toDouble()
                e += re * re + im * im
            }
            return 10 * Math.log10(e.coerceAtLeast(1e-9))
        }
        val before = DoubleArray(1)
        main.postDelayed({ before[0] = level(); playTone(rate) }, 300)
        main.postDelayed({
            val during = level()
            stopTrack()
            runCatching { vis.release() }
            visualizer = null
            report(context.getString(if (during - before[0] > 20) R.string.audio_test_ok else R.string.audio_test_fail))
        }, 1100)
    }

    private var visualizer: Visualizer? = null

    private fun playTone(rate: Int) {
        val pcm = ShortArray(rate)
        for (i in pcm.indices) pcm[i] = (sin(2 * PI * 1000 * i / rate) * 8000).toInt().toShort()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        t.write(pcm, 0, pcm.size)
        track = t
        t.play()
    }

    private fun stopTrack() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        tapping = false
        stopTrack()
        visualizer?.let { runCatching { it.release() } }
        visualizer = null
    }
}
