package io.github.noman13.tvambient.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Owned by the output thread: only it opens, writes and closes the port. Other threads call [requestClose].
 *
 * The LED controller: the first USB serial device (CH340/CH341, CP210x, FTDI, PL2303, CDC-ACM via
 * usb-serial-for-android). Writes complete Adalight packets built by the Rust pipeline.
 */
class LedPort(private val context: Context) {
    private var port: UsbSerialPort? = null
    private var permissionAsked = false

    val isOpen get() = port != null
    @Volatile private var closeRequested = false

    /** Any thread: the output thread closes the port at its next tick (and reopens it later). */
    fun requestClose() {
        closeRequested = true
    }

    /** Output thread: applies a [requestClose]. */
    fun closeIfRequested() {
        if (closeRequested) {
            closeRequested = false
            close()
        }
    }

    /** Tries to open the controller at `baud`; returns a status line. Asks for USB permission once if needed. */
    fun open(baud: Int): String {
        if (port != null) return "USB connected"
        val um = context.getSystemService(UsbManager::class.java) ?: return "No USB"
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull() ?: return "No USB LED controller found"
        val dev = driver.device
        if (!um.hasPermission(dev)) {
            if (!permissionAsked) {
                permissionAsked = true
                val i = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                um.requestPermission(dev, PendingIntent.getBroadcast(context, 0, i, flags))
            }
            return "Waiting for USB permission"
        }
        return try {
            val conn = um.openDevice(dev) ?: return "USB open failed"
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR and RTS equal: on ESP8266 boards with auto-reset that keeps the chip running normally
            runCatching { p.dtr = false; p.rts = false }
            port = p
            Thread.sleep(300) // boards that reset on open need a moment
            "USB connected"
        } catch (e: Exception) {
            close()
            "USB open failed: ${e.message}"
        }
    }

    /** Asking again after the user answered or a device was plugged in. */
    fun resetPermissionAsk() {
        permissionAsked = false
    }

    fun write(packet: ByteArray) {
        val p = port ?: return
        try {
            p.write(packet, 200)
        } catch (e: Exception) {
            close()
        }
    }

    fun close() {
        runCatching { port?.close() }
        port = null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "io.github.noman13.tvambient.USB_PERMISSION"
    }
}
