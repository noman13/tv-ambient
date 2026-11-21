package io.github.noman13.tvambient.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import io.github.noman13.tvambient.Native
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * One-shot screenshots of the main display through the hidden capture APIs, per Android version (TECHNICAL.md §6).
 * Runs in the capture helper as the shell user, where hidden-API restrictions don't apply. Each path analyses the
 * result in place (HardwareBuffer lock, or Bitmap lock), so pixels are never copied into Java.
 *
 * Only the API 34 path is tested on hardware (the reference TV); the others follow AOSP and are untested (§9).
 */
abstract class ScreenCapturer(val name: String) {
    /** Captures one frame at the configured size and analyses it into `out` as a `TVA2` record; returns its length,
     *  0 if the display gave nothing, or a negative error from the native side. */
    abstract fun captureInto(h: Long, out: ByteBuffer, seq: Int, captureNs: Long): Int

    /** Microseconds the last capture call took (feeds the adaptive rate, §7 E4). */
    var lastCaptureUs = 0
        protected set

    companion object {
        /** The first path that works on this device, tried in the order most likely for its API level. */
        fun create(width: Int, height: Int): ScreenCapturer {
            val token = displayToken()
            val sdk = Build.VERSION.SDK_INT
            val order: List<() -> ScreenCapturer> = when {
                sdk >= 34 -> listOf({ CaptureArgs("android.window.ScreenCapture", token, width, height, "api34+ ScreenCapture") },
                    { CaptureArgs("android.view.SurfaceControl", token, width, height, "api31 SurfaceControl.captureDisplay") })
                sdk >= 31 -> listOf({ CaptureArgs("android.view.SurfaceControl", token, width, height, "api31 SurfaceControl.captureDisplay") },
                    { ScreenshotToBuffer(token, width, height) })
                sdk >= 29 -> listOf({ ScreenshotToBuffer(token, width, height) }, { BitmapScreenshot(width, height) })
                else -> listOf({ BitmapScreenshot(width, height) })
            }
            var last: Throwable? = null
            for (make in order) {
                try {
                    return make()
                } catch (t: Throwable) {
                    last = t
                }
            }
            throw IllegalStateException("no capture path works on API $sdk", last)
        }

        /**
         * The main display's token. Android 14 moved the lookup to DisplayControl in services.jar (the reference code).
         * Lint's BlockedPrivateApi is suppressed: this runs only in the shell helper (app_process as the shell user),
         * where the hidden-API policy doesn't apply, never in the app process (TECHNICAL.md §10).
         */
        @android.annotation.SuppressLint("BlockedPrivateApi")
        fun displayToken(): IBinder? {
            val sc = Class.forName("android.view.SurfaceControl")
            if (Build.VERSION.SDK_INT < 29) {
                return runCatching { sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0) as IBinder }.getOrNull()
            }
            runCatching { return sc.getMethod("getInternalDisplayToken").invoke(null) as IBinder }
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val cl = factory.getMethod(
                "createClassLoader", String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
            val dc = cl.loadClass("com.android.server.display.DisplayControl")
            val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            load.isAccessible = true
            load.invoke(Runtime.getRuntime(), dc, "android_servers")
            val ids = dc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            return dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, ids[0]) as IBinder
        }
    }

    /** API 31-33 (`SurfaceControl`) and 34+ (`ScreenCapture`): `captureDisplay(DisplayCaptureArgs)`. */
    private class CaptureArgs(owner: String, token: IBinder?, w: Int, h: Int, name: String) : ScreenCapturer(name) {
        private val capture: Method
        private val args: Any
        private val getBuffer: Method

        init {
            val o = Class.forName(owner)
            val argsCls = Class.forName("$owner\$DisplayCaptureArgs")
            val builderCls = Class.forName("$owner\$DisplayCaptureArgs\$Builder")
            val builder = builderCls.getConstructor(IBinder::class.java).newInstance(token)
            builderCls.getMethod("setSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(builder, w, h)
            args = builderCls.getMethod("build").invoke(builder)!!
            capture = o.getMethod("captureDisplay", argsCls)
            getBuffer = Class.forName("$owner\$ScreenshotHardwareBuffer").getMethod("getHardwareBuffer")
        }

        override fun captureInto(h: Long, out: ByteBuffer, seq: Int, captureNs: Long): Int {
            val t0 = System.nanoTime()
            val shot = capture.invoke(null, args) ?: return 0
            val hb = getBuffer.invoke(shot) as HardwareBuffer
            lastCaptureUs = ((System.nanoTime() - t0) / 1000).toInt()
            try {
                return Native.analyzeHardwareBuffer(h, hb, out, seq, captureNs, lastCaptureUs)
            } finally {
                hb.close()
            }
        }
    }

    /** API 29-30: `screenshotToBuffer(token, crop, w, h, identity, rotation)` → GraphicBuffer → HardwareBuffer. */
    private class ScreenshotToBuffer(private val token: IBinder?, private val w: Int, private val h: Int) :
        ScreenCapturer("api29 SurfaceControl.screenshotToBuffer") {
        private val shot: Method
        private val getGraphicBuffer: Method
        private val fromGraphicBuffer: Method
        private val crop = Rect()

        init {
            val sc = Class.forName("android.view.SurfaceControl")
            shot = sc.getMethod(
                "screenshotToBuffer", IBinder::class.java, Rect::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            getGraphicBuffer = Class.forName("android.view.SurfaceControl\$ScreenshotGraphicBuffer").getMethod("getGraphicBuffer")
            fromGraphicBuffer = HardwareBuffer::class.java.getMethod("createFromGraphicBuffer", Class.forName("android.graphics.GraphicBuffer"))
        }

        override fun captureInto(h: Long, out: ByteBuffer, seq: Int, captureNs: Long): Int {
            val t0 = System.nanoTime()
            val s = shot.invoke(null, token, crop, w, this.h, false, 0) ?: return 0
            val hb = fromGraphicBuffer.invoke(null, getGraphicBuffer.invoke(s)) as HardwareBuffer
            lastCaptureUs = ((System.nanoTime() - t0) / 1000).toInt()
            try {
                return Native.analyzeHardwareBuffer(h, hb, out, seq, captureNs, lastCaptureUs)
            } finally {
                hb.close()
            }
        }
    }

    /** API 26-28: `SurfaceControl.screenshot(...)` → Bitmap (a hardware one is copied once to software). */
    private class BitmapScreenshot(private val w: Int, private val h: Int) : ScreenCapturer("api26 SurfaceControl.screenshot") {
        private val sc = Class.forName("android.view.SurfaceControl")
        private val crop = Rect()
        private val call: () -> Bitmap?

        init {
            call = when {
                Build.VERSION.SDK_INT >= 28 -> {
                    val m = sc.getMethod("screenshot", Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    ({ m.invoke(null, crop, w, h, 0) as Bitmap? })
                }
                else -> {
                    val m = sc.getMethod("screenshot", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    ({ m.invoke(null, w, h) as Bitmap? })
                }
            }
        }

        override fun captureInto(h: Long, out: ByteBuffer, seq: Int, captureNs: Long): Int {
            val t0 = System.nanoTime()
            val shot = call() ?: return 0
            val soft = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() } else shot
            lastCaptureUs = ((System.nanoTime() - t0) / 1000).toInt()
            try {
                return Native.analyzeBitmap(h, soft, out, seq, captureNs, lastCaptureUs)
            } finally {
                soft.recycle()
            }
        }
    }
}
