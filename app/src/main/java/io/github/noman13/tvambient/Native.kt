package io.github.noman13.tvambient

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

/**
 * The Rust core (`libtvambient.so`, TECHNICAL.md §4.4). State lives in Rust behind handles (`Long`, 0 = failed).
 * Settings are small byte arrays built by [NativeConfig]; buffers are direct [ByteBuffer]s allocated once.
 * Results are lengths, or negative codes: −1 bad handle, −2 bad buffer, −3 bad settings, −4 lock failed,
 * −5 unsupported pixel format.
 *
 * The app loads the library with [load]; the capture helper (another process) loads it from the APK path itself.
 */
object Native {
    @Volatile private var loaded = false

    /** Loads `libtvambient.so` from the app's library directory (once). */
    @JvmStatic
    fun load() {
        if (!loaded) {
            System.loadLibrary("tvambient")
            loaded = true
        }
    }

    /** Marks the library as loaded by other means (the helper's `System.load(path)`). */
    @JvmStatic
    fun loadedFrom(path: String) {
        System.load(path)
        loaded = true
    }

    @JvmStatic external fun version(): String?

    // analyser (capture helper)
    @JvmStatic external fun analyzerNew(config: ByteArray): Long
    @JvmStatic external fun analyzerFree(h: Long)
    @JvmStatic external fun analyzerSetMask(h: Long, left: Int, top: Int, right: Int, bottom: Int): Int
    @JvmStatic external fun analyzeHardwareBuffer(h: Long, hb: HardwareBuffer, out: ByteBuffer, seq: Int, captureNs: Long, captureUs: Int): Int
    @JvmStatic external fun analyzeBitmap(h: Long, bmp: Bitmap, out: ByteBuffer, seq: Int, captureNs: Long, captureUs: Int): Int
    @JvmStatic external fun analyzeDirect(h: Long, frame: ByteBuffer, width: Int, height: Int, stride: Int, format: Int, out: ByteBuffer, seq: Int, captureNs: Long, captureUs: Int): Int

    // pipeline (app)
    @JvmStatic external fun pipelineNew(config: ByteArray): Long
    @JvmStatic external fun pipelineFree(h: Long)
    @JvmStatic external fun pipelineSetPicture(h: Long, picture: ByteArray, smoothing: Int): Int
    @JvmStatic external fun pipelineSetSource(h: Long, kind: Int, arg: Int, params: ByteArray?, nowNs: Long): Int
    @JvmStatic external fun pipelineFeed(h: Long, frame: ByteBuffer, length: Int, nowNs: Long): Int
    @JvmStatic external fun pipelineRender(h: Long, nowNs: Long, audio: Long, out: ByteBuffer): Int
    @JvmStatic external fun pipelineLastPacket(h: Long, out: ByteBuffer): Int
    @JvmStatic external fun pipelineShown(h: Long, out: ByteBuffer): Int
    /** Per LED in strip order: edge (0 top, 1 right, 2 bottom, 3 left), position along it (u16 LE). */
    @JvmStatic external fun layoutGeometry(h: Long, out: ByteBuffer): Int
    @JvmStatic external fun effectCount(): Int
    @JvmStatic external fun effectName(id: Int): String?
    /** Bit 0 flashes (⚡), bit 1 in the Blue rotation by default, bits 8-15 the group; −3 for a bad id. */
    @JvmStatic external fun effectFlags(id: Int): Int
    @JvmStatic external fun effectPreview(h: Long, id: Int, tMs: Int, params: ByteArray, out: ByteBuffer): Int
    @JvmStatic external fun musicCount(): Int
    @JvmStatic external fun musicName(id: Int): String?
    /** Bit 0 flashes (⚡); −3 for a bad id. */
    @JvmStatic external fun musicFlags(id: Int): Int
    /** A music effect's tile colours, driven by the built-in 120 BPM test rhythm. */
    @JvmStatic external fun musicPreview(h: Long, id: Int, tMs: Int, music: ByteArray, out: ByteBuffer): Int

    // audio
    @JvmStatic external fun audioNew(config: ByteArray): Long
    @JvmStatic external fun audioFree(h: Long)
    @JvmStatic external fun audioFeedVisualizer(h: Long, fft: ByteArray, tMs: Int): Int
    @JvmStatic external fun audioFeedPcm(h: Long, pcm: ByteBuffer, samples: Int, tMs: Int): Int
    @JvmStatic external fun audioSnapshot(h: Long, out: ByteBuffer): Int

    // capture rate by content (TECHNICAL.md §7 E4b)
    /** The adaptive capture rate: steps down under pressure, back up slowly when calm. */
    @JvmStatic external fun adaptiveNew(start: Int): Long
    @JvmStatic external fun adaptiveFree(h: Long)
    @JvmStatic external fun adaptiveStep(h: Long, lateFrames: Int, captureMs: Int, systemCpuPct: Int, memAvailMb: Int, videoFpsX10: Int, dtMs: Int, ceiling: Int): Int
    @JvmStatic external fun rateChoose(width: Int, height: Int, fpsMilli: Int, path: Int, captureMs: Int): Int

    /** `pipelineSetSource` kinds. */
    const val SOURCE_OFF = 0
    const val SOURCE_SCREEN = 1
    const val SOURCE_EFFECT = 2
    const val SOURCE_SINGLE = 3
    const val SOURCE_MUSIC = 4

    /** Pixel formats for [analyzeDirect]. */
    const val FORMAT_RGB = 0
    const val FORMAT_RGBA = 1

    /** `TVA2` header size; a record is this plus 3 bytes per LED. */
    const val TVA2_HEADER = 32
}
