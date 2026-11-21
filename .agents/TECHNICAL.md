# TECHNICAL.md

Architecture and design notes for **TV Ambient 1.0.0**, an ambient-light app for Android TV written in **Rust**
(the core, as a `.so`) and **Kotlin** (the Android side). Facts marked *(measured)* were measured on the reference
TV (§2); *(to verify)* marks an assumption that hasn't been confirmed on hardware yet. Update this file when the
design changes. Performance numbers: see `PERFORMANCE.md`.

## 1. Goals

| Goal | Target |
|---|---|
| Smooth video with the lights on | 1080p24/60, 2K60, 4K25 and **4K60**: at least 99% of the video's frame rate, no gap longer than one missed refresh caused by capture |
| Resource use | As little CPU and memory as the capture allows; nothing running while the lights are off |
| Size | Release APK **≤ 600 KB** with both ABIs |
| Platforms | Android **8.0 (API 26) to 16**, built and tuned for **Android 14 (API 34)** |
| Interface | Minimal, remote-first, our own design: strip setup modelled on Android Ambilight, the effects list on Universal Ambient Light (see `DESIGN.md`) |
| Remote shortcuts | Colour buttons control the app from any app (Red = lights on/off, Green = open or close Settings, Yellow = stats overlay, Blue = mode button by default), configurable (§11, §13) |
| Modes | Screen, Music, Effect, Single colour; Blue long press goes to the next mode in the rotation (default Screen → Music → Effect), short press steps through the effects of Music and Effect mode (§13) |
| Music sync | Lights react to the TV's audio with < 1% CPU on the Visualizer path; no microphone, nothing stored (§14) |
| Effects | 52 built-in effects, shown as colour tiles, and 13 music effects, flash-capped at 3 Hz (§14.3, §15) |
| Stats overlay | A "stats for nerds" panel over the video: capture/LED rates, video fps, CPU, memory, USB and network bandwidth; each stat switchable; hidden by default; < 1% CPU when shown (§12) |
| Icon | Standard adaptive icon and TV banner, both carrying the "TV Ambient" name (§16) |
| Maintainability | Rust core with unit and golden tests; documented for agents in `AGENTS.md` and `.agents/` |

Non-goals: network features, accounts, cloud, phone apps, WLED/UDP output (possible later), root.

## 2. Reference hardware

Tested on a Haier K66 (Android TV 14, MStar `m7332`, 32-bit userspace, 3840×2160 panel with a 1920×1080 UI,
~1.4 GB RAM) with an ESP8266 + CH340 Adalight controller at 230400 baud driving 184 WS2812B LEDs (56/36/56/36,
clockwise).

| | |
|---|---|
| TV | Haier K66, MediaTek/MStar `m7332`, Android TV 14 (API 34) |
| CPU | 4 × Cortex-A53 (`CPU part 0xd03`) at up to 1.45 GHz (ondemand), with NEON, **32-bit userspace only**: `ro.product.cpu.abilist = armeabi-v7a,armeabi`, `uname -m = armv7l` *(measured)* |
| RAM | ~1.4 GB |
| Display | Panel 3840×2160 @ 60 Hz; window manager **override 1920×1080** (the interface is drawn at 1080p and scaled) *(measured)* |
| Video path | Decoded video goes to a hardware video plane; `mstar.hardware.mmdisp@1.0-service` copies it **on the CPU** whenever a capture composes it *(measured)* |
| LED controller | Wemos D1 mini-style ESP8266, CH340 USB (1a86:7523), Adalight firmware, 230400 baud |
| Strip | 184 × WS2812B: 56 top, 36 right, 56 bottom, 36 left, clockwise |

## 3. Lessons measured on the reference TV

* **MediaProjection mirroring is costly on this hardware.** The mirror is composed on every video frame, so the
  display service (mmdisp) runs at 58-79% of a core and 1080p24 video drops to 13-17 fps *(measured)*. It's only
  the fallback here (§6).
* **One-shot shell screenshots avoid it.** The app opens the TV's own adbd (`127.0.0.1:5555`) with its own RSA key,
  starts a helper with `app_process` as the shell user, and the helper calls the display capture API at a chosen
  rate *(measured: 1080p24 plays at 23.97 fps)*.
* **Don't rate-limit a VirtualDisplay** (`setRequestedRefreshRate` through a hidden API): it crashed SurfaceFlinger
  on this firmware and caused a reboot loop when combined with start on boot. Duty-cycling the virtual display
  broadcasts a display change on every attach and detach, and holding buffers gains nothing (see `PROJECT.md`,
  "Tried and rejected").
* **Measure the video's layer, not gfxinfo.** `dumpsys SurfaceFlinger --latency <layer>`, polled continuously (one
  poll holds only 127 frames). SmartTube on this TV always draws into its own window (no SurfaceView). Count the
  decoder's frames and drops in a **streamed** logcat (the main log buffer is only 64 KiB, ~15 s), and check
  `dumpsys media_session` and decoder gaps for buffering before blaming capture.
* **Capture costs depend on how the player shows video** *(measured)*. A player on the **hardware video plane**
  (a SurfaceView, e.g. Nuvio) makes `mstar.hardware.mmdisp` copy the plane on the CPU for each screenshot:
  **80-85 ms per capture, at most ~12 captures/s, mmdisp at 60-74%**. A player that **decodes into its own window**
  (e.g. SmartTube) costs ~12 ms per capture, mmdisp stays at 0%, and SurfaceFlinger carries the cost instead.
* **SmartTube can't show 4K60 on this TV**, even with no capture running (about 11 fps). A 4K60 test on the
  hardware plane needs another player; none has been found yet (§17).

## 4. Architecture

```
 ┌──────────────── shell user (app_process, started over 127.0.0.1:5555) ────────────────┐
 │ CaptureHelper.kt                                                                         │
 │   display capture API (version-specific, §6) ──► HardwareBuffer / Bitmap                │
 │        │  zero-copy: AHardwareBuffer_lock in Rust                                        │
 │        ▼                                                                                 │
 │   libtvambient.so  ── edge scan + black-bar detection + per-LED averages (§5)           │
 │        │                                                                                 │
 │   stdout ◄── "TVA2" frame: seq, capture time, bars, N × RGB (552 B for 184 LEDs)        │
 └──────────────────────────────────────────────────────────────────────────────────────────┘
          │ ADB exec stream (localhost only)
          ▼
 ┌──────────────── app process (io.github.noman13.tvambient) ───────────────────────────────┐
 │ AmbientService.kt  (foreground service, one output thread, no coroutines)                 │
 │   AdbClient.kt (own minimal ADB: CNXN/AUTH/OPEN/WRTE, RSA via java.security)               │
 │   libtvambient.so ── colour pipeline (LUTs) + temporal smoothing + effects + Adalight      │
 │                      + audio analysis and music effects (§14)                              │
 │   AudioInput.kt ── Visualizer(0) FFT  (or PCM from the helper on API 33+, §14.1)          │
 │   UsbSerial (usb-serial-for-android) ──► LED controller                                    │
 │ KeyShortcutService.kt (AccessibilityService: colour keys, mode button, stats overlay)      │
 │ UI: Activities with plain Views (DESIGN.md)                                                │
 └────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 Why the split is where it is

| Component | Language | Reason |
|---|---|---|
| Edge analysis, bar detection, colour, smoothing, effects, Adalight | Rust (`.so`) | Per-frame numeric work; one implementation reused by the helper and the app; testable without Android |
| Capture helper | Kotlin | Display capture is a hidden **Java** API reached by reflection; it cannot be called from native code in a stable way |
| ADB client | Kotlin | Avoids dadb + okio (size). RSA/SHA-1 signing already exists in `java.security` |
| USB serial | Kotlin + usb-serial-for-android | The Android USB host API is Java-only; the library is small after R8 |
| UI | Kotlin, Android Views | Compose (+ tv-material) would add ~2 MB and more RAM; this conflicts with the size goal |

### 4.2 Analysis happens in the helper

The helper loads the same `.so` straight from the APK (`<apk>!/lib/<abi>/libtvambient.so`, stored uncompressed and
page-aligned), or, if that fails, from a copy extracted to `/data/local/tmp/tvambient/<abi>/`. It sends only the
LED colours plus bar geometry instead of a 128×72 RGB frame (27.6 KB) per capture. That cuts the pipe traffic by
~50×, removes one RGB copy, and keeps the app thread idle between frames.

Analysis settings (LED counts, depth, bar detection) go to the helper as **arguments**. Changing them restarts the
helper, which takes 1.3-3.5 s from launch to first frame on the reference TV *(measured; the first start after
install is the slowest)*; it happens only in strip setup and on a switch back to Screen mode. Picture settings
(brightness, gamma, …) apply in the app and need no restart.

Live changes that can't wait for a restart go over the helper's **stdin** (the ADB `exec:` stream carries data both
ways): short binary control messages to set the overlay mask rectangle (§12.3), start or stop stats sampling, and
set the stats interval. Each message is `"TVC1"`, type (u8), length (u16), payload.

### 4.3 Helper → app frame format (`TVA2`, little-endian)

| Bytes | Field |
|---|---|
| 4 | `"TVA2"` magic (the app resyncs on it: native libraries may print to stdout first) |
| 1 | format version (1) |
| 1 | flags: bit0 = bars detected, bit1 = whole frame black, bit2 = capture failed, bit3 = held back by the black-glitch guard (keep the previous colours) |
| 2 | LED count N |
| 4 | sequence number |
| 8 | capture timestamp, `CLOCK_MONOTONIC` ns |
| 4 | capture duration, µs (feeds the adaptive rate, §7) |
| 8 | content rectangle in capture pixels: left, top, right, bottom (u16 each) |
| 3·N | RGB per LED, in strip order |

The same stream carries `TVS1` stats records (§12.2) and, in Music mode with source B (§14.1), `TVP1` audio
records: `"TVP1"`, sample count (u16), then that many 48 kHz mono 16-bit samples, 10 ms per record.

### 4.4 Rust ↔ Kotlin interface (JNI)

There's one small surface, and the state lives in Rust behind an opaque handle (`jlong`): 33 exports in
`jni/src/lib.rs`, class `io.github.noman13.tvambient.Native`, all static. Settings travel as small `byte[]` whose
layouts are in `core/src/config.rs` (version byte first, little-endian); results are lengths, or negative codes:
−1 bad handle, −2 bad buffer, −3 bad settings, −4 lock failed, −5 unsupported pixel format.

| Function | Called by | Purpose |
|---|---|---|
| `version(): String` | app | The core's version |
| `analyzerNew(config: ByteArray): Long` / `analyzerFree(h)` | helper | Layout + analyser settings (`config::analyzer`) |
| `analyzerSetMask(h, l, t, r, b): Int` | helper | The overlay rectangle to ignore, capture pixels (§12.3) |
| `analyzeHardwareBuffer(h, hb: HardwareBuffer, out: ByteBuffer, seq, captureNs, captureUs): Int` | helper, API 29+ | Lock with `AHardwareBuffer_fromHardwareBuffer` + `AHardwareBuffer_lock`, analyse, write a `TVA2` record into `out` |
| `analyzeBitmap(h, bmp: Bitmap, out, seq, captureNs, captureUs): Int` | helper, API 26-28 | `AndroidBitmap_lockPixels` path (software ARGB_8888) |
| `analyzeDirect(h, frame: ByteBuffer, w, h, stride, format, out, seq, captureNs, captureUs): Int` | helper, screen recording, tests | Any RGB/RGBA bytes in a direct buffer |
| `pipelineNew(config: ByteArray): Long` / `pipelineFree(h)` | app | Layout, picture, smoothing, colour order (`config::pipeline`) |
| `pipelineSetPicture(h, picture: ByteArray, smoothing): Int` | app | New picture settings, live |
| `pipelineSetSource(h, kind, arg, params: ByteArray?, nowNs): Int` | app | Off / Screen / Effect (id) / Single colour / Music, with the 300 ms cross-fade (§13) |
| `pipelineFeed(h, frame: ByteBuffer, length, nowNs): Int` | app | A `TVA2` record from the helper |
| `pipelineRender(h, nowNs, audio: Long, out: ByteBuffer): Int` | app | Interpolated, smoothed, cross-faded colours → complete Adalight packet; returns length, 0 = unchanged |
| `pipelineLastPacket(h, out): Int` / `pipelineShown(h, out): Int` | app | The 500 ms keep-alive; the colours shown, for the UI preview |
| `layoutGeometry(...)` | app UI | LED positions round the screen, for the strip drawing and previews |
| `effectCount(): Int` / `effectPreview(h, id, tMs, params, out): Int` | app UI | Effect tiles (DESIGN.md §6.2) |
| `effectName(id): String?` / `effectFlags(id): Int` | app | The Blue-button message and rotation: name, bit 0 flashes, bit 1 in the rotation by default, bits 8-15 group |
| `musicCount()`, `musicName(id)`, `musicFlags(id)`, `musicPreview(...)` | app, app UI | The same for music effects (§14.3) |
| `audioNew(config): Long` / `audioFree(h)` | app | Music analysis (`config::audio`) |
| `audioFeedVisualizer(h, fft: ByteArray, tMs): Int` / `audioFeedPcm(h, pcm: ByteBuffer, samples, tMs): Int` | app | Source A / sources B and C (§14.1) |
| `audioSnapshot(h, out): Int` | app UI, overlay | 12 band levels, level dB, BPM, beat (16 bytes) |
| `rateChoose(width, height, fpsMilli, path, captureMs): Int` | helper | Captures per second by content (§7 E4b) |
| `adaptiveNew(start): Long`, `adaptiveFree(h)`, `adaptiveStep(h, lateFrames, captureMs, systemCpuPct, memAvailMb, videoFpsX10, dtMs, ceiling): Int` | helper | The Adaptive capture rate (§7) |

Rules: no JNI calls inside hot loops, no allocation per frame on either side (direct `ByteBuffer`s allocated once,
small settings arrays copied to the stack), no panics across FFI: the library is `no_std` with `panic = "abort"`, and
every export checks its inputs and returns an error code instead. The audio feeds keep the low 32 bits of the
millisecond clock rather than clamping negatives, so a TV up for more than 24.8 days keeps working.

## 5. Edge analysis with black-bar fallback

The capture is a small frame (default 128×72; §7 may shrink or crop it). Only edges matter, so the analyser never
touches the centre unless the edges are black.

1. **Bar scan.** From each side, step inward one line at a time while the line is "black" (≥ 97% of a sparse
   sample, every 2nd pixel, below the black threshold). Stop at the first non-black line or at 25% of the
   dimension. This yields the content rectangle (letterbox, pillarbox or windowboxed).
2. **Hysteresis.** A new rectangle must hold for K frames (default 6, ~0.3 s at 20/s) before it's used, and a
   fade to black doesn't count as a bar change (whole-frame black is flagged instead). Covered by golden tests.
3. **Per-LED region.** Each LED owns a strip segment along the **content** edge (not the screen edge), `depth`%
   deep (default 10% top/bottom, 8% sides). Sampling uses a fixed sub-grid (for example every 2nd pixel in each
   direction), so cost ∝ edge area. For 184 LEDs at 128×72 that's ~1.5-2.5 k pixel reads instead of 9.2 k.
4. **Dark-edge reach.** If an LED's region averages below the black threshold and isn't part of a bar, the
   region extends inward in steps (up to `reach_max`, default +15%) until it finds colour. That keeps the room lit
   by the nearest part of the picture without flooding it from the centre. If nothing is found, the LED goes dark.
   Off by default (Settings › Screen mode).
5. **Whole-frame black.** If every line is black, flag it. The app fades to the "black" colour rather than
   treating it as bars. A single black capture after lit ones is dropped unless the next agrees (the black-glitch
   guard).
6. **Averaging.** Integer sums of linear-ish RGB per region (u32 accumulators), divided once. No floats in the
   hot loop. An ARM NEON version only if the scalar version shows up in profiles (this SoC has NEON: `Features`
   in `/proc/cpuinfo` lists `neon vfpv4 idiva` *(measured)*).

All of this is pure Rust over a `&[u8]` view with stride, so it's unit-testable on a PC with synthetic frames
(letterbox, pillarbox, dark scene, flashes) and real golden frames captured on a TV (`core/tests/golden/`). The
golden outputs come from an earlier, independent implementation of the same pipeline, which differs from the steps
above in a few places (symmetric bars, every sampled pixel at luma ≤ 24, 3 frames of hysteresis, regions over the
cropped frame). The analyser therefore has a **reference-compatible configuration** that reproduces it; it's used
only by the golden tests.

## 6. Capture across Android versions

**Screen recording fallback:** without USB debugging there's no helper, so Screen mode can use MediaProjection
instead (`ProjectionCapture.kt`): a 128×72 virtual display mirrored into an `ImageReader`, each image through the
same Rust analyser (`analyzeDirect`), at most 10 captures a second. Android asks for consent every time it starts
(a system dialog, from Home), and only with that consent granted does the service take the `mediaProjection`
foreground-service type. Settings › Screen mode › Capture method: Automatic (shell screenshots; screen recording
only after the shell has failed three times in a row), Shell screenshots, or Screen recording. Mirroring is costly
on this hardware (§3), so it's never chosen while the shell works. *Its cost in this app hasn't been measured.*

The helper picks a path by `Build.VERSION.SDK_INT` and finds methods by reflection, probing signatures in order.
The rows below are the API shapes from AOSP. **Only API 34 has been run on hardware**; the others are written from
AOSP sources and not yet run (§9, §17).

| API | Display token | Capture call | Result | Crop support |
|---|---|---|---|---|
| 26-27 (8.x) | `SurfaceControl.getBuiltInDisplay(0)` | `SurfaceControl.screenshot(Rect crop, int w, int h, int minLayer, int maxLayer, boolean identity, int rotation)` | `Bitmap` | yes |
| 28 (9) | `getBuiltInDisplay(0)` | `SurfaceControl.screenshot(Rect crop, int w, int h, int rotation)` | `Bitmap` (hardware) | yes |
| 29-30 (10-11) | `SurfaceControl.getInternalDisplayToken()` | `SurfaceControl.screenshotToBuffer(token, crop, w, h, identity, rotation)` | `ScreenshotGraphicBuffer` → `HardwareBuffer` | yes |
| 31-33 (12-13) | `getInternalDisplayToken()` | `SurfaceControl.captureDisplay(DisplayCaptureArgs)` | `ScreenshotHardwareBuffer` | `setSourceCrop` |
| 34+ (14-16) | `DisplayControl.getPhysicalDisplayToken` via the `services.jar` class loader. **Order matters** *(measured)*: it loads `libandroid_servers.so`, which fails ("`libadb_pairing_server.so` not found") once our own `.so` has been loaded, so the helper sets up the capturer before loading the core | `android.window.ScreenCapture.captureDisplay(DisplayCaptureArgs)` | `ScreenshotHardwareBuffer` | `setSourceCrop` |

* Fallback for every version: **screen recording** (MediaProjection) with an `ImageReader` at the capture size, for
  TVs without ADB. It has the same stutter as other mirroring apps on MStar TVs; the UI says so.
* Hidden API: processes started by `app_process` aren't zygote apps, so hidden-API enforcement doesn't apply
  *(measured on API 34; to verify on API 26-33)*.
* Android 8-9 Bitmaps may be hardware Bitmaps: use `copy(ARGB_8888)` once into a reused software Bitmap if
  `AndroidBitmap_lockPixels` fails (*to verify*).

## 7. The 4K60 strategy

**Problem.** When the player uses the hardware video plane (a SurfaceView), each capture composes that plane, and
on MStar mmdisp copies it on the CPU. With a 1080p24 video on the hardware plane: **80-85 ms per capture, at most
~12 captures/s, mmdisp at 60-74% of a core, late frames 0 → 67-90 per ~34 s** *(measured)*. A Perfetto trace shows
the chain: the helper's capture call into SurfaceFlinger takes 79 ms, of which SurfaceFlinger's call into mmdisp
takes 68 ms, and 88 of 238 display frames are "SurfaceFlinger GPU Deadline Missed" (0 without capture)
*(measured)*. At 4K60 the decoder has the least slack, so each copy risks a late frame. A smaller output size
barely changes the cost, because the copy is of the **source** plane before scaling *(hypothesis: mmdisp load
scaled with capture rate, not output size)*. Players that decode into their own window (e.g. SmartTube) don't
touch mmdisp: ~12 ms per capture, and the cost moves to SurfaceFlinger (+8-15% of a core at 20-30/s).

Experiments still to run (§17), each measured on 4K60 and 2K60 content, **in a hardware-plane player** (that's
where the cost is) and in a player that draws into its window. They need a 4K60 player that works on this TV (§3).

| # | Idea | Why it might help | Keep it if |
|---|---|---|---|
| E1 | **Capture rate sweep** 5/10/15/20/30 per second | Find the rate where 4K60 stops dropping | Always kept: it sets the defaults |
| E2 | **Source crop to edge bands.** Four thin crops (or two: top+bottom, left+right) instead of one full frame | If mmdisp/HWC copies only the crop area, cost falls by ~70-85% | mmdisp CPU per capture falls by ≥ 30% and the colours match the full capture |
| E3 | **Smaller output** (64×36 vs 128×72 vs 160×90) | Less GPU/CPU scaling work | Measurable CPU drop with no visible colour difference |
| E4 | **Adaptive rate.** The helper watches its own capture duration; if it rises (heavy content), the rate falls; it recovers slowly | 4K60 automatically gets fewer captures, 1080p keeps more | No user-visible pumping; 4K60 within the goal. A first version is built (below) |
| E4b | **Rate by content.** The capture rate is chosen from the video's resolution and frame rate and from the player's path (hardware plane via SurfaceView vs drawn in its window), so playback doesn't glitch: fewer captures at 4K and on the hardware plane. Inputs: the player layer's buffer size and type from SurfaceFlinger, the display mode, the measured capture time (E4 stays as the safety net). The table of rates per case comes from E1's sweep | Each kind of video gets the most captures it can take without late frames | Video fps and late frames within the goal (§1) for every test video at its chosen rate |
| E5 | **Phase alignment.** Schedule captures just after a vsync (`Choreographer` in the helper) | Avoid colliding with the decoder's queue/present | Fewer late frames at the same rate |
| E6 | **Interpolated output at a high rate.** Captures at 10-15/s, LEDs updated at 30-60/s with eased interpolation | Perceived smoothness without more captures | Always kept when the baud rate allows (below); built |

**The Adaptive rate (the default).** Screen mode › Captures per second is *Adaptive* or *Fixed* (with the fixed
rate, default 15/s, the calibrated value on the reference TV). Adaptive is `rate::Adaptive` in Rust, stepped by the
helper every 2 s (`--adaptive`; JNI `adaptiveNew/adaptiveStep/adaptiveFree`) with what `StatsSampler` already reads
in the helper, so it costs no extra sampling:

* **Pressure, down at once (×0.8):** any late video frame (SurfaceFlinger's present times on the player's layer),
  capture using more than half its interval, whole-device CPU over 85 %, or less than 120 MB of memory available.
* **Calm, up slowly (+1 every 5 s after 10 s calm):** CPU under 70 % and capture under 35 % of its interval.
* **Bounds:** never under the table's minimum (5/s); never over the user's ceiling (the fixed rate's range, 30/s)
  or E4's duty cap (capture time at most 50 % of the interval); and never over the **content**: the playing video's
  own frame rate (a capture between two frames sees the same picture), or the unknown-content rate (15/s) when no
  video layer is found (the launcher, a menu, Live TV). Without that last bound the rate climbed to 30/s on an idle
  launcher and SurfaceFlinger rose to 30 % *(measured)*.
* On the hardware plane the duty cap holds the rate at about 6/s on the reference TV.
* The stats overlay shows the target as "auto" next to the measured rate. Resolution is reflected through the
  capture time (a 4K frame costs more to copy, so the duty cap falls); the full E4b table waits for E1.

**Serial bandwidth limits the LED output rate:**
`max fps = baud / 10 / (6 + 3·N)`. For 184 LEDs: **230400 baud → 41 fps**, 500000 → 89 fps, 1000000 → 179 fps.
The default output stays 30/s at 230400. The UI shows the maximum for the chosen baud, and the docs explain that the
controller's firmware must match a higher baud.

## 8. Optimisation checklist

**Rust**
* `opt-level = "s"` (size) for the library, `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, `strip = true`.
* `core` is `#![no_std]` + `alloc` (host tests still use `std`), and `jni` is `#![no_std]` with `panic = "abort"`,
  its own `#[panic_handler]` (calls `abort()`), an allocator over libc `malloc`/`free`, an empty
  `rust_eh_personality` (referenced by the precompiled `core`), and a hand-written binding of the five JNI calls it
  uses (`jni/src/env.rs`, by their slots in the JNI function table): `jni-sys` pulled in `std`. No crates at all
  besides our two. `catch_unwind` isn't available, so every export checks its inputs.
  Why: with `std`, a one-function `.so` was 2,292 bytes on armeabi-v7a but 272,904 on arm64-v8a, because on aarch64
  Android `std` kept its panic hook and backtrace printer (`gimli`, `addr2line`, `miniz_oxide`), and
  `panic = "abort"` alone didn't remove it. With the whole core linked (analysis, 52 effects, FFT and beats, music
  effects, pipeline) and 33 exports, `libtvambient.so` stays under 60 KB per ABI. It needs only libc, libm,
  libandroid and libjnigraphics.
* No allocation per frame; fixed buffers sized at creation.
* LUTs: one 256-entry table per channel, with gamma × balance × brightness pre-combined.

**Kotlin/Android**
* R8 full mode, `minifyEnabled` + `shrinkResources`, no AndroidX AppCompat (platform `Activity` + Views),
  no Kotlin coroutines (one `Thread` + `LockSupport.parkNanos` timing), no kotlin-reflect.
* Only the needed ABIs: `armeabi-v7a`, `arm64-v8a`. No `x86` in the release (emulator builds only).
* Helper JVM: start with the smallest heap flags `app_process` accepts (*to verify*), and avoid creating Bitmaps per
  frame (reuse, or use the HardwareBuffer path).
* Service idle cost: when lights are off or the screen is off, stop the helper and the output thread; nothing
  polls.
* The output thread sends a packet only when colours change, plus a keep-alive every 500 ms.
* Work off the main thread: the Visualizer runs on its own thread (`tvambient-audio`), and the overlay's sampling
  (PSS, memory info, network) on a background `HandlerThread`, so the key service's key filter, which shares the
  main thread, never waits.
* `<profileable android:shell="true"/>` is set, so release builds can be profiled with Perfetto (on the reference TV the kernel blocks `simpleperf`'s perf events).
* **Checked with the agent skill** (`.agents/skills/tv-ambient-android/`): the R8 guide on release builds (keep rules for the
  helper's `main`, the JNI natives and the reflection-reached capture classes must stay narrow), and the ANR guide
  on the service, key service and UI threads.

## 9. Android 8 → 16 compatibility

| Area | API 26-28 | API 29-33 | API 34+ |
|---|---|---|---|
| Foreground service | `startForegroundService`, no type | `foregroundServiceType` attribute | Types required: `connectedDevice` (USB) and `mediaProjection` (fallback, only with a granted consent), with their `FOREGROUND_SERVICE_*` permissions |
| Notification | Channel (26+) | same | `POST_NOTIFICATIONS` needs no prompt on TV for an FGS notification *(to verify on 33)* |
| Boot start | `BOOT_COMPLETED` → FGS | same | Verified on API 34 |
| USB permission | `PendingIntent` flags: none | `FLAG_MUTABLE` needed on 31+ | same |
| USB permission receiver | Registered with a signature-level permission only this app holds | same; `RECEIVER_NOT_EXPORTED` from 33 | `RECEIVER_NOT_EXPORTED` |
| Capture | §6 | §6 | §6 |
| Music audio | Visualizer(0) (A) | Visualizer(0); playback capture (B) from 33 | A, then B |
| `RECORD_AUDIO` | Runtime permission, granted via `pm grant` over the app's ADB, or the normal prompt | same | same; no microphone privacy indicator appears, as no mic is opened *(measured on 34)* |

Builds: `minSdk 26`, `targetSdk 34`, `compileSdk 34`. **Only API 34 has been tested on real hardware.** The
capture paths for API 26-33 are written from AOSP sources but not run; probing them needs emulators for 26, 28, 30
and 33 (x86_64 images and a debug build with an x86_64 `.so`).

Other robustness points:
* The app's ADB private key lives in the **no-backup** folder (`noBackupFilesDir`), so it never leaves the TV in a
  backup.
* The settings receiver used for ADB control is exported but requires `android.permission.DUMP`, which the shell
  holds and apps can't.
* Stored settings are migrated once at start-up (`SettingsSchema.migrate`). Schema 1: the effect ids after the
  removed "Running light" effect move down one place (the saved effect, per-effect settings and the rotation list).
* Defaults (`SettingsSchema.kt`) apply only to keys never saved, so changing one doesn't touch a TV that already
  has a value. The 1.0.0 defaults: brightness 35 %, saturation 150 %, gamma 2.0, white balance R75 G75 B75, black 5,
  smoothing 6, capture method Automatic, effect Northern sky (id 19), music effect VU meter, Single colour 45° at
  90 %, and first run not done.

## 10. Build

* `docker/Dockerfile`: eclipse-temurin 17 + Android SDK 34 + build-tools 34.0.0 + **NDK r26 LTS** + Rust (pinned
  version via rustup) with targets `armv7-linux-androideabi`, `aarch64-linux-android` (+ `x86_64-linux-android`
  for emulator debug) + `cargo-ndk`.
* Gradle task `buildRust` runs `cargo ndk -t armeabi-v7a -t arm64-v8a -o app/src/main/jniLibs build --release`
  before `merge*JniLibFolders`.
* `scripts/build.sh` / `build.ps1` stay the single entry point; volumes cache Gradle and Cargo.
* Lint runs on every release build and must report no issues. One check is suppressed on purpose:
  `BlockedPrivateApi` on `ScreenCapturer.displayToken()`, which reflects into `Runtime.loadLibrary0`. That code runs
  only in the shell helper (`app_process` as the shell user), where the hidden-API policy doesn't apply.
* Signing: the `KEYSTORE_DIR` mechanism, keys in a folder outside the repository (never in it).
* Version 1.0.0, `versionCode` 10000; the release APK is `dist/tv-ambient-1.0.0.apk`.
* The release APK is about 326 KB. The two Inter font files (subset with `pyftsubset` to Latin and the app's
  symbols) are about 88 KB of that; the rest is mostly the two `.so` files and the dex.

## 11. Remote colour-button shortcuts

**Goal.** Red/Green/Yellow/Blue control the app from any app, configurable in the UI (`DESIGN.md` §8). Defaults:
Red = lights on/off, Green = open or close Settings, Yellow = show/hide stats, Blue = mode button (§13).

**Key codes.** `KEYCODE_PROG_RED` 183, `KEYCODE_PROG_GREEN` 184, `KEYCODE_PROG_YELLOW` 185,
`KEYCODE_PROG_BLUE` 186 *(measured)*. The reference remote is Bluetooth ("HaierTV-RC Consumer Control",
HID vendor 0x005d, product 0x0001); its colour keys send HID consumer usages 0x0C003A-0x0C003D, which Linux leaves as
`KEY_UNKNOWN` and the vendor layout `Vendor_005d_Product_0001.kl` (not readable by shell) maps to PROG_RED…BLUE.
A key-logging test app saw all four in an activity and through an accessibility key filter, on the launcher and
during SmartTube playback. Other remotes or firmwares may send vendor codes, so the service allows mapping any key
code. **Live TV pass-through is untested** on the real remote (§17).

**Press timing** *(measured)*: the remote sends exactly one DOWN and one UP per press, with no repeats even when
held; short presses lasted 135-191 ms, held ones 2.1-2.9 s. In an activity Android adds its own repeats (the first at
+401 ms with `FLAG_LONG_PRESS`, then every ~50 ms), but **an accessibility key filter gets no repeats**: a held key
is one DOWN and one UP. So the long press is the 600 ms timer below, never repeat-based.

**Mechanism: a tiny AccessibilityService** (`KeyShortcutService`), the only supported way for an app to see keys
pressed while another app is in front.

* Config `accessibility_service.xml`: `canRequestFilterKeyEvents="true"`, `accessibilityFlags=flagRequestFilterKeyEvents`,
  `accessibilityEventTypes=typeWindowStateChanged` (only to know when Live TV is in front),
  `notificationTimeout=0`, and **no** `canRetrieveWindowContent` (it reads no screen content).
* `onKeyEvent`: if the key is one of the four and has an action, act on `ACTION_DOWN` (the Mode button acts on the
  long-press timer or `ACTION_UP`, §13), and return `true` for both DOWN and UP (consume). Otherwise return `false`
  immediately (the key reaches the app in front unchanged).
* "Not in Live TV": track the foreground package from `TYPE_WINDOW_STATE_CHANGED`; when it's a tuner app
  (`com.mediatek.wwtv.tvcenter` on the reference TV, plus the stock Live Channels packages), pass every key through.
* **Open or close settings** (Green by default): opens Settings; pressed while Settings is in front, it closes the
  app's screens (Settings and Home) and returns to what was on screen.
* Actions call `AmbientService.start/stop` through an explicit intent. Starting the foreground service from the
  bound accessibility service is exempt from Android 12+'s background-start restriction *(measured: the lights
  started while SmartTube was in front)*. An ADB broadcast to the settings receiver is **not** exempt while no
  activity of ours is showing (`ForegroundServiceStartNotAllowedException`); `AmbientService.start` catches it and
  says so in the status instead of crashing.
* Cost: no polling and no window content. It only wakes on key events and window changes (target < 0.1% CPU idle,
  < 8 MB PSS shared with the app process).
* Debounce: ignore auto-repeats for the action (`repeatCount > 0`) and a second press within 400 ms.
* **Short vs long press** (only the Mode button action uses it, §13): long = the key is still down 600 ms after
  `ACTION_DOWN` (a `Handler` timer fires the long action at 600 ms, and its `ACTION_UP` is then ignored). Short =
  `ACTION_UP` before that. The repeat path (`repeatCount ≥ 1` with `FLAG_LONG_PRESS`) is kept only as a fallback for
  remotes that send repeats themselves (some IR remotes): an accessibility filter never sees Android's own repeats
  *(measured)*. 600 ms sits well between the reference remote's short (≤ 191 ms) and held presses.

**Turning it on.** Android requires the accessibility service to be enabled in Settings. Settings › Remote buttons ›
**Remote shortcuts** is an on/off switch that shows Android's own state (a `SystemSwitchRow`: its state lives
outside the app's settings). When the app has shell access (USB debugging approved), OK enables or disables it over
the app's own ADB connection:
`settings put secure enabled_accessibility_services <existing>:<ours>` + `settings put secure accessibility_enabled 1`.
It **appends** to the existing value and never overwrites it: other services must stay enabled. Turning it off
removes only our entry, and deletes the setting if ours was the only one. Without shell access, OK opens
`Settings.ACTION_ACCESSIBILITY_SETTINGS`.

**On by default (1.0.0).** Android keeps this switch, so a fresh install starts with it off. `Shell.enableShortcutsOnce`
turns it on the first time the app's ADB works (first run's connection check, or the capture helper connecting) and
records `shortcuts_default_done`; the switch sets the same flag, so a person who turns it off is never overridden.

**Conflicts on the reference TV** *(measured, real remote)*: none. No other accessibility service is enabled there
(`enabled_accessibility_services` = null), and all four buttons reached ours on the launcher, in Nuvio and in
SmartTube. Keys injected with `input keyevent` never reach the filter on this TV *(measured)*: only the real remote
proves a shortcut.

## 12. Stats overlay

A see-through performance panel over the video (`DESIGN.md` §9), toggled by the Yellow button. Every stat can be
switched on or off.

### 12.1 Window

* Drawn by `KeyShortcutService` as a **`TYPE_ACCESSIBILITY_OVERLAY`** window: an accessibility service may add one
  without the "display over other apps" permission (API 22+). Flags: `NOT_FOCUSABLE | NOT_TOUCHABLE |
  LAYOUT_IN_SCREEN`, so the remote keeps controlling the player.
* Not built: a fallback for when the accessibility service is off, `TYPE_APPLICATION_OVERLAY` with
  `SYSTEM_ALERT_WINDOW` granted over the app's own ADB (§17). Today the panel needs the accessibility service
  (Yellow goes through it anyway). If the fallback is ever added, its `appops` command must name only this package.
* One custom `View` that draws text and coloured values on a `Canvas`; no layouts or `TextView`s per stat. It
  `invalidate()`s once per refresh interval and allocates nothing per draw (pre-built `Paint`s, reused
  `StringBuilder`s, numbers formatted into a char buffer).
* Sampling that could block (PSS, `ActivityManager` memory info, network counters) runs on a background
  `HandlerThread`; each draw shows what it last read.
* Hidden = the window is removed and all sampling stops (the cost is zero, not just invisible).

### 12.2 Where each stat comes from

Our app can't read other processes' `/proc` entries (Android hides them from apps), but the **shell-user helper
can**. So system-wide sampling runs in the helper and is sent to the app in the frame stream (a `TVS1` stats record
next to the `TVA2` frames, once per interval). When the lights are off but the overlay is on, the app starts the
helper in **stats-only mode** (no capture).

| Stat | Collected by | Source | Cost |
|---|---|---|---|
| Capture rate, time, path | helper | its own counters, already in `TVA2` headers | free |
| LED rate and USB link | app | output-thread counters | free |
| Video fps, late frames | helper | SurfaceFlinger frame timing for the player's layer, read **in-process** through the SurfaceFlinger service's `dump` binder call with `--latency <layer>` (no `dumpsys` process per sample). The layer is looked up again only when the foreground app changes | low |
| Display mode | app | `Display.getMode()`, `getRealSize` | free |
| App in front | key service | `TYPE_WINDOW_STATE_CHANGED` (already tracked for "Not in Live TV") | free |
| App / helper CPU | helper | `/proc/<pid>/stat` utime+stime deltas ÷ elapsed | low |
| System CPU | helper | `/proc/stat` deltas | low |
| Display services CPU (mmdisp, SurfaceFlinger) | helper | `/proc/<pid>/stat`; pids found once by scanning `/proc/*/cmdline`, and again only if a process restarts | low |
| CPU frequency | helper | `/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq` (readable by shell). Temperature isn't shown: `/sys/class/thermal/thermal_zone*/temp` is **not** readable by shell on the reference TV *(measured)* | low |
| App memory | app | `/proc/self/smaps_rollup` Pss (API 28+), `/proc/self/statm` before | low |
| Helper memory | helper | `/proc/self/status` VmRSS | low |
| Device memory | app | `ActivityManager.getMemoryInfo` (availMem / totalMem) | low |
| USB (LEDs) | app | bytes written to the controller; capacity from §7's formula | free |
| Network ↓/↑ | app | `TrafficStats.getTotalRxBytes/TxBytes` deltas (device-wide; while streaming it approximates the video bitrate) | free |
| Audio level, tempo, source | app | `audioSnapshot` (§4.4) | free |
| Current mode | app | the mode controller (§13) | free |

Sampling interval: 0.5, 1 (default) or 2 s, set live over the `TVC1` control channel (§4.2).

### 12.3 Keeping the panel out of the LED colours

The capture photographs the screen, so it would include the panel, and a corner panel sits right on the edges the
LEDs sample. The fix is a **mask**:

* When the overlay shows, moves or resizes, the app sends its rectangle (display pixels) to the helper over `TVC1`.
  The helper maps it into capture coordinates, with a 1-pixel margin.
* The Rust analyser skips masked pixels in the bar scan, the per-LED averages and the dark-edge reach.
* An LED whose region is more than half masked takes a colour interpolated from the nearest unmasked LEDs on either
  side along the strip. With the default top-left position, that's a few LEDs on the top and left edges near the
  corner.
* Possible later (API 31+): exclude the overlay layer from the capture itself through the capture args'
  exclude-layers option. That needs the overlay's `SurfaceControl` in the helper, which is not straightforward;
  try it only if masking looks wrong.

### 12.4 Budgets and checks

* With the default stats at 1 s, the overlay plus sampling must cost **< 1% CPU** (app + helper together) and
  **< 4 MB** extra PSS.
* *(Measured on the launcher, capture on at 15/s)*: the app uses ~1 % CPU with the panel hidden and ~3 % with it
  shown at 1 s. Anything that animates costs far more on this TV: a 15 fps button animation took the app to ~8 %,
  so the panel stays static (DESIGN.md §9.4).
* **Video must not notice it.** Video fps and late frames at 1080p60 and 4K60 should be the same within noise with
  the overlay on and off. An extra UI layer over the video plane could change how the TV composes, so this is a real
  check, not a formality. *Not measured yet* (§17).
* Published performance numbers are always taken with the overlay **hidden**.

### 12.5 Recording to CSV

"Record to file" appends one row per refresh to
`/sdcard/Android/data/<package>/files/stats/stats-<yyyyMMdd-HHmmss>.csv` (app-specific storage, no permission).
Columns: timestamp, then every stat enabled at the start of the recording (header row names them). The app in
front is never recorded. A new file starts when the set of stats changes. Pull with `adb pull`. Files older than
7 days are deleted at start, and the total is capped at 20 MB.

## 13. Modes and the mode button

Four modes: **Screen** (capture), **Music** (§14), **Effect** (§15), **Single colour**. The service owns one small
state machine (`ModeController.kt`, used by the key service). All state is in `SharedPreferences`, restored at
start.

```
state: mode ∈ {Screen, Music, Effect, Single}, rotation ⊆ modes (default {Screen, Music, Effect}),
       effect, musicEffect, lightsOn

Blue long:   mode = next mode in rotation after mode (wraps); nothing if "Long press Blue" = Nothing
Blue short:  if mode == Music  → musicEffect = next music effect in its rotation (wraps)
             if mode == Effect → effect = next effect in its rotation (wraps)
             otherwise nothing
any Blue when lights are off → lightsOn = true (no mode change)
music silent 5 s, if "Switch to Screen mode" → mode = Screen, autoSwitched = true
music resumes while autoSwitched (detected by source A staying open) → mode = Music, autoSwitched = false
any manual mode change → autoSwitched = false
```

The effect rotation holds every effect except the flashing (⚡) ones and those under "For setup" by default (42 of
52); the music rotation holds every music effect except Strobe beat ⚡. Each effect's tile has an "In Blue rotation"
switch (DESIGN.md §6.2).

To notice music resuming while in Screen mode, the audio source stays open, but only after an automatic switch,
and only the cheap source A, so this costs < 1% CPU. Silence handling runs on the output thread every 250 ms from
the music engine's level.

**What each mode runs** (only what it needs, so switching modes saves resources):

| Mode | Capture helper | Audio | Output thread |
|---|---|---|---|
| Screen | running (capture) | off | interpolate captures (§7 E6) |
| Music | stopped (or stats-only if the overlay is on) | on (§14) | music effect at the LED rate |
| Effect | stopped (or stats-only) | off | effect at the LED rate |
| Single colour | stopped (or stats-only) | off | one packet, then the 500 ms keep-alive only |

* Mode changes cross-fade over 300 ms in Rust: the old and new outputs are rendered and blended, so there's no
  black flash.
* The helper takes seconds to start, so the cross-fade from another mode into Screen starts when the first capture
  arrives. Until then the old output keeps running. *Measured* (real-remote Blue presses): first capture 3.5 s
  after the first switch back to Screen, then 1.3-1.4 s once the TV has the helper's code cached; switches into
  Music take 0.2 s. There's no warm helper: keeping it running would hold 50-120 MB on a 1.4 GB TV to save about
  1.3 s.
* Each press shows a 2 s on-screen message (DESIGN.md §8). DESIGN.md §11.2 has the full table.

## 14. Music sync

Neither studied app has this, so this design is new. The lights react to the audio the TV is playing. **Music mode
never captures the screen.**

### 14.1 Audio sources (tried in order by "Automatic")

| # | Source | API | How | Pros | Cons / notes |
|---|---|---|---|---|---|
| A | **Visualizer on the output mix** | 9+ (all our versions) | `android.media.audiofx.Visualizer(0)` (session 0 = the global mix), FFT callbacks at up to `getMaxCaptureRate()` (typically 20 Hz), capture size 1024, on the app's own audio thread | Tiny cost: the analysis runs in audioserver; no extra process; works while any app plays | Session 0 is deprecated and **may return silence on some firmwares**. Audio sent as a bitstream (Dolby passthrough over HDMI ARC/eARC) or by hardware paths (Live TV tuner on MStar) never reaches the mixer. Needs `RECORD_AUDIO` (granted over the app's own ADB with `pm grant`) + `MODIFY_AUDIO_SETTINGS`. **Works on the reference TV** *(measured: 48 kHz, capture size 1024, up to 20 captures/s)*: SmartTube video (RMS −20…−31 dB, bass following the soundtrack), a song in SmartTube (−15…−17 dB, strong beat in the bass band), Nuvio with a Dolby Digital Plus 5.1 track decoded by the TV for its speakers (−26…−31 dB); exact silence (−96 dB) when nothing plays. No microphone indicator or prompt; app CPU in Music mode about 3.6 %, the same as Effect mode (the cost is the LED output, not the audio), audioserver about +1 point. "Automatic" chooses it on this TV |
| B | **Playback capture in the shell helper** | 33+ | The helper (shell user) records the device's playback with an audio-policy loop-back while it keeps playing on the TV, the approach scrcpy uses for its "playback" audio source with duplication. 48 kHz mono PCM, 10 ms buffers | Real PCM at 100 Hz updates, accurate beats | Android 13+ only; apps that disallow capture (often DRM video apps) give silence; needs the helper running (more memory). **Works on the reference TV** *(measured)*: hidden `AudioPolicy` with a players mix rule on media/game/unknown usages and render + loop-back route flags, registered through `AudioManager.registerAudioPolicyStatic` (package-private on this firmware, so `getDeclaredMethod`); the speakers kept playing normally. Cost: app 7.8-10.8 % CPU (its own FFT about 94 times a second) plus the helper 0.6-2.7 % and 92 MB RSS, over the < 3 % budget, so Automatic uses it only when A is silent while music plays |
| C | **Remote submix** (not built) | 30+ | `AudioSource.REMOTE_SUBMIX` from the shell helper | Works when B doesn't | **Diverts audio away from the TV speakers** while recording: if built, offered only as a manual choice, with that warning, never automatic |

**Live TV and Dolby passthrough** are untested (§17). The MStar audio path suggests the tuner's sound bypasses the
mixer, so A and B would hear silence and Music mode falls back to its silence behaviour. Dolby passthrough needs a
soundbar over HDMI ARC; a bitstream never reaches the mixer, so the same applies. No root or system-permission
workaround is attempted (`PROJECT.md`, "Music").

"Automatic" tries A, checks for non-silence over 3 s while `AudioManager.isMusicActive()` says something plays,
then tries B (on 13+). It remembers what worked. The chosen source is shown on the Music screen and in the stats
overlay.

No microphone is ever used. Audio is analysed in memory and discarded. Nothing is stored or sent anywhere.

### 14.2 Processing (Rust `audio` module)

* Input: either Visualizer FFT magnitudes (512 bins) or PCM (B/C → a 1024-point real FFT in Rust with a Hann
  window, 50% overlap, using a small in-crate radix-2 FFT, no dependency).
* **Bands:** fold the bins into 12 log-spaced bands (≈ 40 Hz … 16 kHz), plus bass (< 200 Hz), mid and treble sums.
* **Auto gain:** a slow peak follower per band (attack 0.3 s, release 4 s) normalises to 0-1, so quiet and loud
  songs both fill the range. Manual sensitivity scales the follower's target.
* **Display envelope:** levels below 12 % of the peak are noise (the Visualizer's FFT is 8-bit); above that each
  band rises in 40 ms and falls over 250 ms. *(Measured: with a 50 ms attack and no envelope the lights flickered
  wildly at the Visualizer's 20 Hz.)* Beats use the raw spectrum, so the envelope doesn't delay them.
* **Beat detection:** spectral flux on the bass bands against an adaptive threshold (mean + k·σ over the last
  1.5 s), with a 200 ms refractory period. Tempo (BPM) is estimated from beat intervals for the display only.
* **Audio delay:** analysed values go into a short ring buffer, and the renderer reads them `delay` ms later
  (0-500 ms). Negative delay (−200 ms) shifts the rendering earlier by predicting from the last beat interval (tempo
  effects only).
* Output: a `MusicFrame` (12 bands, bass/mid/treble, level dB, beat flag, BPM) consumed by the music effects.
* Cost target: < 1% CPU for A; < 3% for B including the helper's PCM path.

**How the Music mode settings (`DESIGN.md` §10.1) map onto the engine** (all live, no restart):

| Setting | Engine parameter |
|---|---|
| Music effect, Colour palette | effect id and palette id passed to `effects::music` |
| Brightness follows / Minimum brightness | output brightness = `max(min, level)` (Loudness) or fixed |
| Sensitivity | Auto: the peak follower's target; 1-10: a fixed gain curve |
| Beat response | beat envelope amplitude (0.4 / 0.7 / 1.0) |
| Fade speed | beat envelope decay (400 / 250 / 120 ms) and band release |
| Audio delay | ring-buffer read offset (§14.2); **Tap to beat** plays a 120 BPM click through `AudioTrack` and sets the delay to the median offset of 8 taps after the first 2 |
| After 5 s of silence | level below −55 dB for 5 s → dim glow (min brightness, slow palette drift), off, keep last frame, or ask the state machine (§13) to switch to Screen mode; music resuming switches back only if the switch was automatic |
| Audio source | source A/B selection and the Automatic probe (§14.1) |
| Test audio input | plays a 1 kHz tone for 1 s via `AudioTrack`, and passes if the chosen source sees that band rise by > 20 dB |
| Frequency range | band weights: Full (all), Bass only (< 250 Hz), Vocals (300 Hz-3 kHz) |

### 14.3 Music effects (Rust `effects::music`)

13 effects; ids are stable (settings store them).

| Effect | Mapping |
|---|---|
| Spectrum | 12 bands mapped around the strip: bass at the bottom centre, rising through the sides to treble at the top centre; band level → brightness, band index → palette colour |
| Pulse | Whole strip at palette colour, brightness = beat envelope (fast attack, 250 ms decay) |
| VU meter | Left and right sides fill from the bottom with loudness (mono source: both the same) |
| Bass glow | Warm palette, brightness and spread follow the bass band |
| Wave | Each beat launches a colour front that travels round the TV |
| Centre burst | Beats burst outward from the bottom centre along both directions |
| Frequency colour | Hue follows the dominant band, brightness follows loudness |
| Strobe beat ⚡ | White flash on strong beats only, rate-capped at 3 Hz |
| Spectrum bars, Spectrum bars ↻ | Bars (rising from the bottom, or growing left to right when rotated) on two opposite sides, the 12 bands as bar segments along the other two, with a dark LED between segments when a band has 3 LEDs or more |
| Spectrum wave, Spectrum wave ↻ | Bars on two sides, the 12 bands interpolated into a smooth wave along the other two |
| Colour shift | One colour for the whole strip, a new one on each beat (seeded by the beat time so it never stalls), drifting with the dominant band |

Tiles in the UI use a built-in 120 BPM test rhythm (`music::test_rhythm`) when no music plays.

**High contrast.** Every music effect ends with `punch()`: 85 % of the colour's white component (its smallest
channel) is removed and the rest rescaled to keep the peak, and the level becomes `0.9 · v^1.5`. The light on a
white wall stays saturated instead of washing out to pastel, and the swing between quiet and loud is larger. The
music tests check that every effect still lights up and moves with the song.

Palettes: Rainbow, Warm, Cool, Neon, Single colour. Every music effect runs at the LED output rate with the same
cross-fade and smoothing pipeline.

## 15. Effects catalogue

The full list is in `DESIGN.md` §6.2 (52 effects in six groups; the default is **Northern sky**, Aurora waves over the whole strip with a 10 s period). Technical rules:

* Each effect is a pure Rust function `fn(t_ms, params, &layout, out: &mut [Rgb])`, with no allocation and integer
  or fixed-point maths where possible (32-bit ARMv7). A table lists name, group, whether it flashes, and (derived
  from those) whether it's in the Blue rotation by default.
* Effect ids are table indices and are stored in settings, so they're stable: new effects are appended. A removed
  effect needs a settings migration (§9).
* The position of each LED around the TV (0-1 along the strip, plus its side) comes from the layout, so effects like
  Fire (rising up the sides) or Scanner (the top only) look right on any strip.
* Randomness (Twinkle, Fire, Candle) uses a tiny xorshift PRNG seeded once: deterministic and testable.
* **Flashing limit:** effects marked ⚡ (5 of them) are clamped to ≤ 3 flashes per second (WCAG 2.3.1) in the
  renderer, not just in the effect, so a fast speed setting can't exceed it.
* Tests: every effect is rendered for 10 s of simulated time at 30 fps in a host test, checking for no panics, no
  values out of range, the flash cap, and a golden hash of the first second so accidental changes show up. Four
  **classic** effects (Rainbow swirl ↻/↺, Breathing, Slow colours) are also checked against reference outputs in
  `core/tests/golden/expected/`.
* Previews in the UI (`DESIGN.md` §6.2 colour tiles) call the same functions over JNI: the focused tile every
  frame, the others at about 3 fps.
* **Many effects, little code.** The 52 effects come from fewer functions: an effect is a table entry naming a
  function plus its preset (palette, speed, direction, which sides), so Rainbow swirl ↻/↺, Comet/Dual comets or
  Aurora/Northern sky share code. Budget: the whole catalogue adds at most ~40 KB to the `.so` per ABI.

## 16. App icon and banner

Specs are in `DESIGN.md` §7.1. Technical points:
* `android:banner` on the application (TV home row) and an adaptive `android:icon` (no monochrome layer: TV launchers have no themed icons).
* All drawn as vector drawables, with the "TV AMBIENT" wordmark as dot-matrix paths generated by
  `tools/icon/make_icons.py`: no font file, small size.
* Size budget for all icon assets: ≤ 12 KB in the APK.

## 17. Roadmap (not done yet)

* **Nuvio 1080p24 stutters in Screen mode** (`PERFORMANCE.md`): each capture of its video takes ~74 ms against
  8-10 ms with SmartTube, and a fifth of the frames come late even at ~5 captures a second. Find out why that video
  path is so costly to capture, and whether a lower rate, a smaller capture or a different capture call avoids it.
* **Let the Adaptive rate see decoder drops.** At 4K60 it kept capturing at 15/s while the player's drops went from
  170 to 410 a minute (`PERFORMANCE.md`). The player's dropped-frame count (or SurfaceFlinger's late frames for the
  video layer) could be one of its pressure signals.

* **4K60 tuning of the capture rate.** Experiments E1-E6 (§7) haven't been run, so the rate-by-content table (E4b)
  isn't built; the Adaptive rate and its 50 % duty cap stand in. It needs a 4K60 player that works on the reference
  TV (§3).
* **Live TV and Dolby passthrough** are untested, both for Music mode (§14.1) and for the colour-key pass-through in
  Live TV (§11).
* **Audio source C** (remote submix) is not built (§14.1).
* **API 26-33 capture paths** are written but only API 34 has been run on hardware (§6, §9).
* **The stats overlay's own cost** on video (fps and late frames with it on vs off at 1080p60 and 4K60) hasn't been
  measured (§12.4).
* **An overlay fallback without the accessibility service** (`TYPE_APPLICATION_OVERLAY`) is not built (§12.1).

For UI, security, ANR, APK-size or profiling work, use the agent skill in `.agents/skills/tv-ambient-android/`.

## 18. Risks

| Risk | Mitigation |
|---|---|
| 4K60 still drops frames at any useful capture rate on this SoC | E2/E4/E6; publish the honest number; recommend 10/s for 4K60 |
| Capture API differs per vendor/version | Reflection probe with ordered signatures; screen-recording fallback; log which path ran |
| `.so` loading inside the shell helper fails | Extract to `/data/local/tmp` from the APK and load that copy (built, §4.2) |
| Rust + Kotlin push the APK over budget | Hand-written JNI, `no_std` core, R8; measure APK and `.so` size on every release build |
| Golden mismatch from rounding | Allow ±1 per channel where documented; any larger diff is a bug |
| SurfaceFlinger instability from new capture tricks (E2/E5) | Test each with start on boot **off** first; never ship a path that restarts system processes |
| The remote's colour buttons send vendor key codes, or the firmware keeps them for the TV app | Record codes with `getevent`; allow mapping any key code in the service; if the firmware swallows them, document it and let the user assign another button the remote does send |
| Enabling our accessibility service overwrites another one | Append and remove only our entry; read back and compare after every change |
| Colour buttons stolen from apps that use them (teletext, some players) | Only keys with an action are consumed; Live TV is excluded by default; the user picks the buttons |
| The overlay changes how the TV composes video and costs frames | Measure with it on and off (§12.4); keep it small and static; if it still costs frames, document it and recommend the Compact or Ticker layout |
| The overlay's colours leak into the LEDs | Mask in the analyser (§12.3); check with a bright overlay on a black video |
| Reading SurfaceFlinger frame timing every second is costly | Binder `dump` in-process, not `dumpsys`; if still over budget, make video fps a "heavier" stat that's off by default |
| Visualizer(0) returns silence on a firmware, or for Dolby passthrough and Live TV | Source B on Android 13+; explain on the Music screen which content can't drive the lights; drop Music from the rotation automatically if no source works |
| Playback capture blocked by apps that disallow it (DRM video apps) | Prefer A; show "this app blocks audio capture" when B gets silence while music is active |
| The remote can't make a reliable long press (IR repeat quirks) | 600 ms timer with a repeat-based fallback; "Long press Blue → Nothing" |
| Flashing effects trigger photosensitive viewers | ⚡ effects off the Blue rotation by default, one-time warning, renderer-level 3 Hz cap |
| More effects and audio code push the APK over budget | Integer maths, no FFT or audio crates, measure `.so` size per release; drop the least-used effects before raising the budget |
