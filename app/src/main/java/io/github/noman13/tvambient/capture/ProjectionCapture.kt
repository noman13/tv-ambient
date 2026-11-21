package io.github.noman13.tvambient.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import io.github.noman13.tvambient.Native
import java.nio.ByteBuffer

/**
 * Screen recording (MediaProjection), the fallback capture for TVs without USB debugging (TECHNICAL.md §6).
 * Measured as costly on the reference TV (the display service at 58-79 %, 1080p24 video down to 13-17 fps), so it's never the default: shell screenshots come first, and this runs at [maxRate] at most (10/s).
 *
 * A virtual display of [W]×[H] mirrors the screen into an [ImageReader]; each image goes through the same Rust
 * analyser as the helper's captures and comes out as a `TVA2` record for [onFrame]. One handler thread; buffers are
 * allocated once.
 */
class ProjectionCapture(
    private val context: Context,
    private val analyzerConfig: ByteArray,
    private val leds: Int,
    private val maxRate: Int,
    private val onFrame: (ByteBuffer, Int) -> Unit,
    private val onStop: () -> Unit,
) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var analyzer = 0L
    private val out = ByteBuffer.allocateDirect(Native.TVA2_HEADER + 3 * leds.coerceAtLeast(1))
    private var seq = 0
    private var lastAt = 0L
    @Volatile var lastCaptureUs = 0
        private set

    /** Starts mirroring with the consent [resultCode]/[data] from `createScreenCaptureIntent`. False if refused. */
    fun start(resultCode: Int, data: Intent): Boolean {
        val mpm = context.getSystemService(MediaProjectionManager::class.java) ?: return false
        val p = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull() ?: return false
        analyzer = Native.analyzerNew(analyzerConfig)
        if (analyzer == 0L) { p.stop(); return false }
        val t = HandlerThread("tvambient-projection").apply { start() }
        thread = t
        val h = Handler(t.looper)
        // Android 14 requires a callback before the virtual display; the system stops the projection on its own too
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "screen recording stopped by the system")
                onStop()
            }
        }, h)
        val r = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> onImage(ir) }, h)
        reader = r
        display = p.createVirtualDisplay("tvambient", W, H, 1, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)
        projection = p
        return true
    }

    private fun onImage(r: ImageReader) {
        if (analyzer == 0L || reader !== r) return // stopping
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastAt < 1000L / maxRate) return // at most maxRate analyses a second
            lastAt = now
            val t0 = System.nanoTime()
            val plane = img.planes[0]
            val stride = plane.rowStride / plane.pixelStride
            val len = Native.analyzeDirect(analyzer, plane.buffer, W, H, stride, Native.FORMAT_RGBA, out, seq++, t0, 0)
            lastCaptureUs = ((System.nanoTime() - t0) / 1000).toInt()
            if (len > 0) onFrame(out, len)
        } finally {
            img.close()
        }
    }

    /** Pixels the analyser ignores (the stats panel), in capture pixels. */
    fun setMask(l: Int, t: Int, r: Int, b: Int) {
        val th = thread ?: return
        Handler(th.looper).post { if (analyzer != 0L) Native.analyzerSetMask(analyzer, l, t, r, b) }
    }

    fun stop() {
        val d = display
        val p = projection
        val r = reader
        val a = analyzer
        val th = thread
        display = null; projection = null; reader = null; analyzer = 0L; thread = null
        // everything onImage touches is released on the projection thread, after any frame in progress: closing the
        // reader from the main thread while onImage read its buffer could crash in Rust
        val release = Runnable {
            d?.release()
            runCatching { p?.stop() }
            r?.close()
            if (a != 0L) Native.analyzerFree(a)
        }
        if (th != null) {
            Handler(th.looper).post(release)
            th.quitSafely()
        } else release.run()
    }

    companion object {
        private const val TAG = "TvAmbient"
        /** The helper's capture size (TECHNICAL.md §6), so the analyser sees the same frames whatever the path. */
        const val W = 128
        const val H = 72
    }
}
