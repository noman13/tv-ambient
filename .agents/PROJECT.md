# Project guide

How the repository is laid out, how to build and test it, the reference hardware, and the rules for changing it.
Start from [`AGENTS.md`](../AGENTS.md) at the root; the architecture is in [`TECHNICAL.md`](TECHNICAL.md), the
interface in [`DESIGN.md`](DESIGN.md), measured resource use in [`PERFORMANCE.md`](PERFORMANCE.md).

## Repo map

| Path | What it is |
|---|---|
| `core/` | Rust crate `tvambient-core`: `analyze` (edge scan, bars, per-LED averages, plus a reference-compatible mode for the golden tests), `color` (LUTs), `smooth` (smoothing, interpolation between captures), `effects` (the 52-effect catalogue, TECHNICAL.md §15, flash cap), `effects::music` (the 13 music effects, §14.3), `audio` (FFT, bands, auto gain, beats, delay; §14.2), `modes` (cross-fade), `pipeline` (the app's whole LED tick), `layout`, `adalight`, `frame` (`TVA2`/`TVC1`/`TVS1`), `config` (settings byte layouts for JNI), `rate` (capture rate by content and the Adaptive rate, §7), `math`. `no_std` + `alloc`, no Android dependencies, no crates, tested on the host (`scripts/build.sh test`) |
| `core/tests/` | Host tests: `golden_compat.rs` (reference outputs), `effects.rs` (+ `effect_hashes.txt`, rewrite with `UPDATE_EFFECT_HASHES=1`), `audio.rs` (music generated in the test: kicks at known times, so nothing to download) |
| `core/tests/golden/` | `frames/` (real 128×72 captures from a TV), `expected/` (reference outputs for them, from an earlier independent implementation of the same pipeline), `jobs.txt`, README with sources and licences. The Rust output must match (±1 per channel where `TECHNICAL.md` allows) |
| `jni/` | Rust crate `tvambient-jni` → `libtvambient.so`: 33 thin `extern "system"` exports (TECHNICAL.md §4.4), AHardwareBuffer/Bitmap locking, no logic; `no_std` with its own panic handler and malloc allocator; `env.rs` binds the five JNI calls by hand |
| `app/` | Android app, package `io.github.noman13.tvambient`, Kotlin, platform Views |
| `app/src/main/java/.../capture/CaptureHelper.kt` | Runs as the shell user via `app_process`; version-specific capture (TECHNICAL.md §6); writes `TVA2` frames and `TVS1` stats to stdout, reads `TVC1` control messages from stdin; can run in stats-only mode |
| `app/src/main/java/.../capture/StatsSampler.kt` | In the helper: `/proc` CPU and memory, SurfaceFlinger frame timing via binder `dump` (TECHNICAL.md §12.2) |
| `app/src/main/java/.../capture/PlaybackCapture.kt` | Music source B in the helper: loop-back audio policy, `TVP1` PCM records |
| `app/src/main/java/.../capture/ProjectionCapture.kt` | Screen recording (MediaProjection), the capture fallback without USB debugging (TECHNICAL.md §6) |
| `app/src/main/java/.../adb/` | `AdbClient.kt`: minimal ADB client for `127.0.0.1:5555`; `AdbKey.kt`: the app's own RSA key, kept in the no-backup folder |
| `app/src/main/java/.../service/AmbientService.kt` | Foreground service: modes (TECHNICAL.md §13), helper lifecycle, output thread, USB |
| `app/src/main/java/.../service/ModeController.kt` | The Blue button's state machine as settings changes (mode, effect, music effect) |
| `app/src/main/java/.../service/Receivers.kt` | Boot receiver, and the ADB-only settings receiver (requires `android.permission.DUMP`) |
| `app/src/main/java/.../audio/AudioInput.kt` | Music audio sources: Visualizer on the output mix (A, on its own thread), PCM from the helper's playback capture (B) (TECHNICAL.md §14.1) |
| `app/src/main/java/.../keys/KeyShortcutService.kt` | AccessibilityService for the colour-button shortcuts and the Blue mode button's short/long press (TECHNICAL.md §11, §13), and host of the stats overlay window; key filtering only, no window content |
| `app/src/main/java/.../overlay/StatsOverlayView.kt` | The overlay panel: one Canvas-drawn view, no per-draw allocation, sampling on a background thread (TECHNICAL.md §12.1, DESIGN.md §9) |
| `app/src/main/java/.../overlay/StatsCsv.kt` | "Record to file" CSV writer with rotation (TECHNICAL.md §12.5) |
| `app/src/main/java/.../overlay/MessageView.kt` | The 2 s message after a colour-button press |
| `app/src/main/res/xml/accessibility_service.xml` | The key service's config: key filtering + window-state events, nothing else |
| `app/src/main/java/.../ui/HomeActivity.kt` | Home: the neon power button with Settings under it, the TV preview, mode tiles with their settings links, status (DESIGN.md §5.1) |
| `app/src/main/java/.../ui/SettingsActivity.kt` | The two-pane Settings screen: section list + generated rows, focus memory, breadcrumbs (DESIGN.md §4, §5.2) |
| `app/src/main/java/.../ui/SettingsSchema.kt` | **Every setting as data**: key, range, default, and the one-time settings migration at start-up (TECHNICAL.md §9). Add a setting's key here |
| `app/src/main/java/.../ui/SettingsSections.kt` | The sections and their rows as data (DESIGN.md §3); a new setting's row goes here |
| `app/src/main/java/.../ui/rows/` | The five row types (value, switch, choice, link, action) on one `SettingRow` base (DESIGN.md §4.2); `SystemSwitchRow` is the switch whose state lives outside the app's settings |
| `app/src/main/java/.../ui/` (other) | `TileGridView` (effect and music tiles), `StripFrameView` (the LED dots round the screen), `PreviewViews` (live strip and TV previews), `LedModel` (the UI's own pipeline and LED geometry), `AudioTools` (Tap to beat, Test audio input), `FirstRunActivity`, `ProjectionRequestActivity`, `Theme` (DESIGN.md §7 tokens) |
| `app/src/main/res/drawable/` | Vector adaptive icon and TV banner, with the dot-matrix wordmark as paths (DESIGN.md §7.1) |
| `app/src/main/res/font/` | Inter Regular and SemiBold, subset to Latin and the app's symbols (DESIGN.md §7) |
| `LICENSES/` | Third-party licences: Inter (SIL OFL 1.1) |
| `tools/icon/make_icons.py` | Generates the adaptive icon and the banner (light bars round a screen) with "TV AMBIENT" in its own dot-matrix letters; plain Python, no font |
| `docker/Dockerfile` | JDK 17, Android SDK 34, NDK r26, Rust + cargo-ndk |
| `scripts/build.sh`, `scripts/build.ps1` | The only build entry points |

## Build

Docker is the only requirement. The image `tv-ambient-builder` (`docker/Dockerfile`: JDK 17, Android SDK 34,
build-tools 34.0.0, NDK r26d, Rust 1.83.0 with the Android targets, cargo-ndk 3.5.4; about 7 GB) is built on first
use; Gradle and Cargo caches live in the named volumes `tv-ambient-builder-gradle` and `tv-ambient-builder-cargo`. Gradle 8.9,
Android Gradle Plugin 8.5.2, Kotlin 2.0.21.

```bash
scripts/build.sh                                         # dist/tv-ambient-<version>.apk, debug-signed
KEYSTORE_DIR=/path/to/your/keys scripts/build.sh         # release-signed
scripts/build.sh debug                                   # dist/tv-ambient-<version>-debug.apk
scripts/build.sh test                                    # Rust host tests: cargo test -p tvambient-core
scripts/build.sh shell                                   # a shell in the image, repo at /src
```

PowerShell: `.\scripts\build.ps1 [release|debug|test|shell]`, same behaviour. Inside the image, Gradle's
`buildRust` task runs
`cargo ndk -t armeabi-v7a -t arm64-v8a --platform 26 -o app/src/main/jniLibs build --release -p tvambient-jni`
before packaging (`app/src/main/jniLibs/` is gitignored).

Install on a TV (Android 14's `adb install` can hang on a confirmation; push + `pm install` doesn't):

```bash
adb push dist/tv-ambient-1.0.0.apk /data/local/tmp/tva.apk
adb shell pm install -r /data/local/tmp/tva.apk
```

**Only one app may use the LED controller at a time**: stop any other ambient-light app's service before testing.

## Reference hardware

Tested on a Haier K66 (Android TV 14, MStar `m7332`, 32-bit userspace (`armeabi-v7a`), 3840×2160 panel with a
1920×1080 UI, ~1.4 GB RAM) with an ESP8266 + CH340 Adalight controller at 230400 baud driving 184 WS2812B LEDs
(56/36/56/36, clockwise). Details in `TECHNICAL.md` §2. Reach a TV with network debugging
(`adb connect <tv-address>:5555`) or any ADB client; never commit an address.

Test players: one that decodes into its own window (e.g. SmartTube) and one on the hardware video plane (e.g.
Nuvio); capture costs differ a lot between the two (`TECHNICAL.md` §3, §7).

## Rules for changes

* **Performance first.** A change to capture, analysis or output needs measurements before and after: CPU with
  `top -H` (the helper, the app, `surfaceflinger`, `mstar.hardware.mmdisp`, the player) and video pacing with
  `dumpsys SurfaceFlinger --latency '<player layer>'`, polled continuously, with the stats overlay hidden. See
  [`PERFORMANCE.md`](PERFORMANCE.md) for the reference numbers. A regression needs a reason in the commit.
* **Size budget.** The release APK must stay ≤ 600 KB. No AppCompat, Material, Compose, coroutines or
  kotlin-reflect. A new Rust crate or Android dependency needs a reason, and its size cost goes in the commit
  message.
* **The hot path doesn't allocate**: no per-frame allocation in Rust or Kotlin, and no JNI calls inside loops.
* **Never panic across FFI.** Exported functions validate inputs and return error codes.
* **Never ship a capture path that can crash or restart system processes.** Test new capture tricks with start on
  boot **off**, then with a reboot with it on.
* **No network** beyond `127.0.0.1:5555`. No analytics, telemetry, or update checks.
* **Defaults** equal the reference calibration (`TECHNICAL.md`, `SettingsSchema.kt`: brightness 35 %, saturation
  150 %, gamma 2.0, white balance R75 G75 B75, black 5, smoothing 6; capture method Automatic, effect Northern sky,
  music effect VU meter, Single colour a dark yellow, 45° at 90 %) unless a commit says why.
* **Stored settings are stable.** Effect ids and setting keys are stored on the TV; renumbering or removing one
  needs a step in the start-up migration (`SettingsSchema.migrate`, TECHNICAL.md §9).
* **UI** follows `DESIGN.md`. Strip setup is modelled on Android Ambilight's flow and the effects list on Universal
  Ambient Light's, but nothing is copied from either app (layouts, text, icons, colours).
* **Settings navigation.** A new setting's key goes into `SettingsSchema.kt` and its row into `SettingsSections.kt`,
  in the section where a user would look for it (a mode's behaviour → that mode's section, e.g. anything about
  music → Music mode). Use one of the five row types; don't add a sixth without updating DESIGN.md §4.2. Keep
  Home › section › sub-page as the maximum depth, and rerun the navigation test in DESIGN.md §12 when sections
  change.
* **Colour-key shortcuts.** The accessibility service consumes only keys that have an action, passes everything
  through in Live TV by default, and never reads window content. When enabling it over ADB, **append** to
  `enabled_accessibility_services` and remove only our entry when disabling: other services must survive. Test
  with the **real remote**: an injected `input keyevent` isn't proof for a button that goes through an
  accessibility service.
* **Stats overlay.** It costs nothing while hidden (window removed, sampling stopped); shown, it stays within
  TECHNICAL.md §12.4, and anything that could block runs off the main thread. The analyser must mask its rectangle
  so it never colours the LEDs. A new stat says where its number comes from and what it costs, in TECHNICAL.md
  §12.2, and defaults to off if it's heavier. The CSV log never records the app in front.
* **Modes.** Each mode runs only what it needs (TECHNICAL.md §13 table): leaving Screen stops the capture helper,
  and Music mode never captures the screen. Mode changes cross-fade in Rust, never via a black frame. Test the Blue
  button's short and long press with the real remote.
* **Music.** Never open a microphone. Audio is analysed in memory and discarded; nothing is stored or sent. Remote
  submix (source C) diverts sound from the speakers, so it must never be chosen automatically. Note which content
  can't drive Music mode (Dolby passthrough, Live TV) in TECHNICAL.md §14.1 rather than working around it with root
  or system permissions.
* **Effects.** A new effect is one pure Rust function plus one table entry (appended, so stored ids don't move),
  with no allocation, deterministic randomness, and the host test (TECHNICAL.md §15). Anything that flashes is
  marked ⚡, left out of the Blue rotation by default, and capped at 3 flashes per second by the renderer.
* Don't commit APKs, keystores, `keystore.properties`, ADB keys, IP addresses, or screenshots with personal data.
  Local notes and screenshots stay in `work/` (gitignored).
* Commit style: `area: what changed` (`core: dark-edge reach for letterboxed scenes`). Commits are authored by the
  maintainer; **don't add AI co-author trailers**.

## Tried and rejected (don't reintroduce without new evidence)

Measured on the reference TV.

| Idea | Result |
|---|---|
| MediaProjection mirror as the main path | mmdisp 58-79% CPU, 1080p24 dropped to 13-17 fps |
| Duty-cycling the virtual display (`setSurface(null)` between frames) | mmdisp fell to 20%, but every attach/detach broadcast a display change; SurfaceFlinger, system_server and the player rose to 40-46% |
| Holding ImageReader buffers (back-pressure) | SurfaceFlinger composes anyway: no gain |
| `VirtualDisplayConfig.setRequestedRefreshRate` via hidden `MediaProjection.createVirtualDisplay(Builder, …)` | Worked (video 23.4 fps) but needed `hidden_api_policy=1` and **crashed SurfaceFlinger** (`Output::updateColorProfile`); a reboot loop with start on boot |
| Vsync-lateness "adaptive" rate in the app | The app's Choreographer never saw the player's drops |
| `dumpsys gfxinfo <player>` as a jank metric | Counts the player's UI, not video frames |
