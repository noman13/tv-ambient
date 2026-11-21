# Golden frames and expected outputs

Real screen captures from a TV, and the reference outputs the Rust core is checked against. The golden tests feed
the frames through `analyze`, `color`, `smooth` and `layout` and compare with these outputs: **±1 per channel** is
allowed, anything more is a bug.

The expected outputs were produced by an earlier, independent implementation of the same picture pipeline (in
Java), run unmodified on these frames. Matching it bit for bit pins down the maths (cropping, the black-frame guard,
per-LED averages, the colour tables, smoothing, the strip layout) so a change shows up at once.

## Frames (`frames/*.tvaf`)

Captured with a shell-user screenshot helper at 128 × 72, 10 captures per second, while a video player showed the
films below on an Android TV 14 set (Haier K66). Format, one record per frame: big-endian `"TVAF"`, width, height
(int32), then width × height × 3 bytes RGB.

| Clip | Frames | Source (at) | What it tests |
|---|---|---|---|
| `bright-cuts` | 24 | Big Buck Bunny 60fps 4K (1:10) | Bright, saturated full frame; two scene cuts |
| `seekbar-overlay` | 15 | Big Buck Bunny (5:00) | A player's seek bar and buttons over the bottom of the picture, then gone |
| `letterbox-dark` | 16 | Sintel (8:00) | 2.35:1 letterbox (9 dark rows top and bottom) in a dark scene, then a cut |
| `letterbox-near-black` | 16 | Sintel (8:00, later) | Letterbox with an almost black picture (mean luma ≈ 4) |
| `letterbox-cut-to-bright` | 12 | Sintel (11:45) | Letterbox, dark scene cutting to a bright one |
| `pillarbox-ui-overlay` | 22 | Night of the Living Dead (10:00) | 4:3 pillarbox (17 dark columns each side), black and white; player controls appear over it |
| `black-loading` | 8 | Sintel (start) | Whole frame black but for a small loading circle in the centre |

**Sintel's green edge.** That TV showed Sintel's 2048 × 880 VP9 stream with a green bar down the right edge of the
picture (inside the letterbox), probably the decoder's padding. It's in every capture, so it's kept: it's what the
LEDs really get.

Sources and licences:

* *Big Buck Bunny* (2008) © Blender Foundation, [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/),
  peach.blender.org.
* *Sintel* (2010) © Blender Foundation, [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/), sintel.org.
* *Night of the Living Dead* (1968), public domain in the United States.

The frames are 128 × 72 thumbnails, changed only by the TV's scaling and the capture.

## Expected outputs (`expected/*.csv`)

`jobs.txt` lists every file and the settings it was made with. Each CSV starts with `#` comment lines: the source,
all the settings, the clip. Colours are hex `RRGGBB` per LED in strip order (LED 0 first), before the RGB
byte-order setting.

| File | Columns |
|---|---|
| `<clip>.<config>.csv` | `frame, width, height, crop_x, crop_y, guard, raw_rgb, out_rgb`: the crop used for that frame (after a 3-frame hysteresis), the raw per-LED averages, `use`/`drop` from the black-frame guard, and the processed colours (saturation, black threshold, gamma/balance/brightness tables; empty when dropped) |
| `<clip>.smooth-<n>.csv` | `tick, t_ms, capture, changed, out_rgb`: the output smoothing at setting *n*, LED ticks every 33 ms, captures arriving every 50 ms (20/s) |
| `effects.<config>.csv` | `effect, name, t_s, raw_rgb, out_rgb`: five classic effects at t = 0, 0.5 … 10 s, plus single colour (−1) and the screen-off dim colour (−2); one of them (number 3, Running light) is no longer in the catalogue and is skipped |
| `layout.<config>.csv` | `led, edge, x0, y0, x1, y1`: each LED's sampling rectangle as a fraction of the frame |

Configs: `default` (brightness 35%, saturation 150%, gamma 1.9, black 5, border detection on, depth 10%/8%,
56/36/56/36), `border-off`, `layout-variant` (40/22/40/22, gap 4, counter-clockwise, offset 10, depth 15%/12%) and
`picture-variant` (brightness 100%, saturation 100%, gamma 2.2, balance 90/100/80, black 10). These are the test
inputs, not the app's defaults.

## The tests that use them

* `core/tests/golden_compat.rs`: every clip under every config through the analyser's compatible mode and the
  picture tables, plus smoothing and layout. Result: bit-identical (worst difference 0 over 328 frame checks).
* `core/tests/effects.rs`: the classic effects, single colour and dim colour against `effects.*.csv` (±1).

## The compatible mode

The app's own analyser goes further than the reference (`.agents/TECHNICAL.md` §5): bars per side, ≥ 97% of a
sparse sample instead of every sampled pixel, a 6-frame hysteresis instead of 3, regions along the content edge.
So the golden tests run it in a **compatible configuration** (symmetric bars, 100% rule, luma ≤ 24, 3 frames,
regions over the cropped frame) to compare with `expected/`, and test the app's own behaviour separately.
