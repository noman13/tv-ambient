# Performance

Measured resource use of TV Ambient 1.0.0 on the reference TV, and how to measure it again. Use these numbers as
the baseline when a change touches capture, analysis or output (`PROJECT.md`, rules for changes): measure before
and after, the same way, and give the reason for any regression in the commit.

## Setup

* **TV:** Haier K66, Android TV 14, 4 cores, 32-bit userspace, ~1.4 GB RAM, 1920×1080 UI on a 3840×2160 panel
  (`PROJECT.md`, reference hardware).
* **LEDs:** ESP8266 + CH340 Adalight controller, 184 LEDs, 230400 baud, LED output 30/s.
* **App:** release build, default settings (Screen mode with the Adaptive capture rate, shell screenshots).
* **Players:** SmartTube (it decodes into its own window) playing *Big Buck Bunny 60fps 4K* (Blender Foundation,
  CC BY 3.0) at a fixed quality, not Auto; Nuvio playing a 1080p, 24 fps film.
* Each video was measured twice back to back, while it kept playing: 60 s with the lights **off**, then 60 s in
  **Screen** mode. The difference between the two is what TV Ambient costs.

## How to read the numbers

* **CPU of a process** is a percentage of **one core** (from `/proc/<pid>/stat`), so 100 % is one full core.
* **System CPU** is a percentage of **all four cores** (from `/proc/stat`).
* **App** is the TV Ambient app process; **helper** is the capture helper it runs as the shell user
  (`TECHNICAL.md` §4.2); **SF** is SurfaceFlinger.
* **Decoder drops** are frames the player's decoder dropped (`MediaCodecVideoRenderer: dropOutputBuffer` lines in
  logcat). This is the reliable smoothness measure for SmartTube, whose window also redraws on its own.
* **Video fps / late** come from `dumpsys SurfaceFlinger --latency` on the player's video layer, sampled every
  second: frames per second, and frames that came later than 1.5× the content's frame time. For SmartTube the layer
  is its window, so treat these as a rough guide only.
* **Capture** is the app's own log line: captures per second and the average time per capture.

## Video

| Clip | Lights | System CPU | App | Helper | SF | Player | Smoothness | Capture |
|---|---|---|---|---|---|---|---|---|
| 1080p24, Nuvio | off | 34.1 % | 0 % | 0 % | 19.3 % | 37.6 % | 23.98 fps, 1 late | |
| | Screen | 51.7 % | 3.4 % | 8.0 % | 20.6 % | 49.3 % | **22.2 fps, 354 late** | 4.9/s, **74 ms** each |
| 1080p60, SmartTube | off | 42.8 % | 0 % | 0 % | 18.2 % | 68.4 % | 0 drops (59.0 fps) | |
| | Screen | 49.9 % | 4.7 % | 7.8 % | 25.7 % | 68.0 % | 0 drops (58.9 fps) | 15.0/s, 8.1 ms |
| 1440p60, SmartTube | off | 46.5 % | 0 % | 0 % | 19.0 % | 74.2 % | 0 drops (58.7 fps) | |
| | Screen | 55.8 % | 4.5 % | 8.5 % | 29.1 % | 76.2 % | 7 drops | 15.1/s, 9.6 ms |
| 4K30, SmartTube | off | 33.9 % | 0 % | 0 % | 11.3 % | 46.7 % | 0 drops (30.0 fps) | |
| | Screen | 41.1 % | 4.4 % | 7.8 % | 18.4 % | 49.6 % | 0 drops (30.0 fps) | 14.1/s, 7.6 ms |
| 4K60, SmartTube | off | 41.0 % | 0 % | 0 % | 7.5 % | 60.9 % | **170 drops** | |
| | Screen | 49.4 % | 4.6 % | 8.0 % | 13.8 % | 60.8 % | **410 drops** | 15.1/s, 7.1 ms |

Memory during the video runs: app about 15-24 MB (PSS), helper about 120 MB (RSS, most of it the shared Android
runtime), 300-380 MB free on the TV with SmartTube, about 140 MB with Nuvio.

**What it shows**

* With SmartTube up to 1440p60 and at 4K30, Screen mode costs about **12-13 % of one core** (app ~4.5 % + helper
  ~8 %) plus 6-10 % of a core in SurfaceFlinger, and playback stays smooth (0-7 decoder drops a minute).
* **4K60 is beyond this TV even with the lights off** (170 drops a minute), and Screen mode makes it worse (410).
  The Adaptive rate kept capturing at 15/s: its pressure signals don't include the player's decoder drops.
* **Nuvio 1080p24 stutters in Screen mode.** Each capture of its video takes about 74 ms (8-10 ms with SmartTube),
  the Adaptive rate backs off to about 5/s, and still a fifth of the frames come late. With the lights off it plays
  cleanly. The cause isn't settled yet: Nuvio's video path makes capture much more expensive (`PROJECT.md`,
  reference hardware, and `TECHNICAL.md` §3, §7). Earlier builds measured 23.97 fps at 10 captures per second on
  this player, so this is the first thing to investigate.

**Caveats**

* The Nuvio pair was measured right after restarting Nuvio. A first attempt with Nuvio long-running, when the TV had
  under 100 MB free and its swap nearly full, gave the same picture (26.5 fps / 31 late with the lights off,
  22.5 fps / 348 late in Screen mode) and is kept with the raw data.
* An HDR 1440p60 stream stuttered on its own (289 decoder drops a minute with the lights off, 85 in Screen mode), so
  the table uses the non-HDR stream. Choose non-HDR streams when comparing runs.
* SmartTube's window-based fps for the 1440p60 Screen run covered only 546 sampled frames; its decoder drop count is
  the number to use.

## Music, effects and idle

| Run | Front app | System CPU | App | Helper | SF | LEDs |
|---|---|---|---|---|---|---|
| Lights off (idle) | TV launcher | 4.9 % | 1.8 % | 0 % | 0.9 % | off |
| Music, VU meter, song playing | SmartTube | 29.9 % | 3.3 % | 0 % | 8.6 % | 16.9/s |
| Same song, lights off | SmartTube | 28.5 % | 0 % | 0 % | 8.4 % | off |
| Effect: Northern sky | TV launcher | 6.9 % | 4.3 % | 4.4 %\* | 1.5 % | 29.9/s |
| Effect: Slow colours | TV launcher | 12.6 % | 14.4 % | 4.5 %\* | 8.8 % | 7.9/s |
| Effect: Plasma | TV Ambient Home\*\* | 14.6 % | 21.8 % | 4.6 %\* | 10.5 % | 29.9/s |
| Single colour | TV Ambient Home\*\* | 13.9 % | 19.1 % | 4.2 %\* | 10.5 % | 1.9/s |
| Screen, still launcher | TV Ambient Home\*\* | 21.1 % | 24.8 % | 8.1 % | 21.6 % | 2.5/s |

App memory: 32-33 MB PSS in these runs (48 MB with the Home screen in front during Screen mode).

* **Music mode costs about 3 % of one core** and needs no helper: the output mix comes through Android's
  `Visualizer` (`TECHNICAL.md` §14.1). The system total barely moves (28.5 → 29.9 %).
* An **effect** costs about 4 % of one core in the app.
* The LED rate follows the picture: identical frames aren't resent, so a still colour sends ~2 updates a second and
  a moving effect 30.

**Caveats (kept as measured)**

* \* The stats overlay was open during these runs (not during idle, music or video). Its stats-only helper (~4-5 %
  of a core, ~90 MB) and its 1 s refresh are included.
* \*\* TV Ambient's own Home screen was in front, so the app and SurfaceFlinger numbers include its live LED
  preview. Slow colours' higher app CPU than Northern sky probably has the same cause during part of its run.

## How to measure

Everything runs over ADB; nothing extra is installed on the TV.

```sh
# CPU: one snapshot per thread, or sample /proc yourself over a fixed window
adb shell top -H -b -n 1 -p $(adb shell pidof io.github.noman13.tvambient)
adb shell cat /proc/stat                      # whole system: sum the columns, idle = 4th + 5th value
adb shell cat /proc/<pid>/stat                # one process: utime + stime are fields 14 and 15 (clock ticks, 100/s)

# Video pacing: find the player's layer, then read its frame times (127 most recent frames per call)
adb shell dumpsys SurfaceFlinger --list | grep SurfaceView
adb shell dumpsys SurfaceFlinger --latency '<layer name>'

# Decoder drops (ExoPlayer-based players such as SmartTube)
adb logcat -c; sleep 60; adb logcat -d | grep -c dropOutputBuffer

# Memory
adb shell dumpsys meminfo io.github.noman13.tvambient | grep 'TOTAL PSS'
adb shell grep MemAvailable /proc/meminfo

# The app's own rates (every 5 s while the lights are on)
adb logcat -s TvAmbient | grep 'LEDs'
```

Method used for the tables: start the clip, let it play for at least 10 s, stop the lights and measure 60 s, start
Screen mode, wait 15 s for the capture rate to settle, measure 60 s. Sample the latency every second and merge the
samples by present time, ignoring gaps over 1 s. Keep the stats overlay closed and the TV Ambient Home screen in the
background unless that's what you're measuring. The in-app **stats overlay** (`DESIGN.md` §9) shows the same CPU,
memory, capture and video numbers live, at a cost of ~5 % of one core.
