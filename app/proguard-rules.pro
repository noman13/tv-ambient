# JNI: the native methods' class and member names are part of the exported symbol names in libtvambient.so.
-keepclasseswithmembernames class io.github.noman13.tvambient.Native { native <methods>; }

# The capture helper is started by name with app_process as the shell user; nothing in the app references it.
-keep class io.github.noman13.tvambient.capture.CaptureHelper { public static void main(java.lang.String[]); }

# usb-serial-for-android's default prober reaches each driver by reflection: the static getSupportedDevices() and the
# (UsbDevice) constructor. The classes themselves are referenced from its probe table, so only those members need
# keeping (phase 8 R8 review: this replaced a keep-everything rule for the whole driver package).
-keepclassmembers class com.hoho.android.usbserial.driver.*SerialDriver {
    public static java.util.Map getSupportedDevices();
    public <init>(android.hardware.usb.UsbDevice);
}
