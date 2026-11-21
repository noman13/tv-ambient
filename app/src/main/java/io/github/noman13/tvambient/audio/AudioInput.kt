package io.github.noman13.tvambient.audio

import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log

/**
 * Music audio, source A (TECHNICAL.md §14.1): `Visualizer` on the output mix (session 0). The FFT goes straight to
 * [onFft] on this class's own thread (`tvambient-audio`: the Visualizer delivers to the looper of the thread that
 * set its listener, which would otherwise be the main one); nothing is stored. No microphone is ever opened: the output mix is
 * what the TV is already playing.
 *
 * Needs `RECORD_AUDIO` (self-granted over the app's own ADB, like the app's other self-grants) and
 * `MODIFY_AUDIO_SETTINGS`. [start] returns null on success, or why it failed.
 */
class AudioInput(private val onFft: (ByteArray, Int) -> Unit) {
    private var visualizer: Visualizer? = null
    private var thread: android.os.HandlerThread? = null // main thread
    @Volatile var lastFftAt = 0L
        private set

    /** Started (or starting): the thread exists. */
    val running get() = thread != null

    /**
     * Opens the Visualizer on the audio thread (so its listener binds to that looper) and reports back on the main
     * thread: null on success, or why it failed. Never waits on the calling thread.
     */
    fun start(done: (String?) -> Unit) {
        if (thread != null) { done(null); return }
        val th = android.os.HandlerThread("tvambient-audio").apply { start() }
        thread = th
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        android.os.Handler(th.looper).post {
            val err = open()
            main.post {
                if (thread !== th) return@post // stopped meanwhile
                if (err != null) stop()
                done(err)
            }
        }
    }

    /** On the audio thread. */
    private fun open(): String? {
        var made: Visualizer? = null
        return try {
            val v = Visualizer(0)
            made = v
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            val rate = Visualizer.getMaxCaptureRate() // mHz, typically 20000
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null) return
                    lastFftAt = SystemClock.uptimeMillis()
                    // the low 32 bits of the millisecond clock the pipeline renders with (System.nanoTime)
                    onFft(fft, (System.nanoTime() / 1_000_000).toInt())
                }
            }, rate, false, true)
            v.enabled = true
            visualizer = v // audio thread only
            Log.i(TAG, "audio: output mix, capture size ${v.captureSize}, ${rate / 1000} Hz")
            null
        } catch (e: Throwable) { // RuntimeException / UnsupportedOperationException: no permission or no effect
            Log.w(TAG, "audio: output mix unavailable: $e")
            if (visualizer == null) made?.let { runCatching { it.release() } } // a half set-up effect isn't kept
            e.message ?: e.javaClass.simpleName
        }
    }

    fun stop() {
        val th = thread ?: return
        thread = null
        // the Visualizer lives on the audio thread: released there, after the set-up and any callback in progress
        android.os.Handler(th.looper).post {
            visualizer?.let { runCatching { it.enabled = false }; runCatching { it.release() } }
            visualizer = null
        }
        th.quitSafely()
    }

    private companion object {
        const val TAG = "TvAmbient"
    }
}
