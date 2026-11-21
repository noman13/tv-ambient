//! Music effects (TECHNICAL.md §14.3, DESIGN.md §10.2): pure functions of the latest [`MusicFrame`], the recent beat
//! times and the Music mode settings. No state, no allocation. Strobe beat (⚡) goes through [`super::FlashCap`].
//!
//! Every music effect ends with [`punch`]: strong, saturated LED colours with deep darks,
//! so the light reflected off a white wall doesn't wash out into pastel.

use crate::audio::{MusicFrame, BANDS};
use crate::layout::{Edge, Layout};
use crate::math::{abs_f32, exp, hsv, mix};

use super::Rgb;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Palette {
    Rainbow,
    Warm,
    Cool,
    Neon,
    /// Single colour mode's colour
    Mono,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MusicEffect {
    Spectrum,
    Pulse,
    VuMeter,
    BassGlow,
    Wave,
    CentreBurst,
    FrequencyColour,
    /// ⚡
    StrobeBeat,
    // Spectrum visualizers made of bars and of a wave, and one colour changing with the beat.
    /// left and right are bars rising from the bottom (bass left, treble right); top and bottom a bar spectrum
    /// (12 band segments, bass on the left)
    SpectrumBars,
    /// rotated: the bar spectrum up the sides (bass at the bottom), bars growing left to right on top and bottom
    SpectrumBarsSides,
    /// left and right bars rising from the bottom; top and bottom a smooth wave of the bands (bass on the left)
    SpectrumWave,
    /// rotated: the wave up the sides, bars growing left to right on top and bottom
    SpectrumWaveSides,
    /// the whole strip in one colour that changes on each beat
    ColourShift,
}

pub const MUSIC_EFFECTS: [(MusicEffect, &str, bool); 13] = [
    (MusicEffect::Spectrum, "Spectrum", false),
    (MusicEffect::Pulse, "Pulse", false),
    (MusicEffect::VuMeter, "VU meter", false),
    (MusicEffect::BassGlow, "Bass glow", false),
    (MusicEffect::Wave, "Wave", false),
    (MusicEffect::CentreBurst, "Centre burst", false),
    (MusicEffect::FrequencyColour, "Frequency colour", false),
    (MusicEffect::StrobeBeat, "Strobe beat ⚡", true),
    (MusicEffect::SpectrumBars, "Spectrum bars", false),
    (MusicEffect::SpectrumBarsSides, "Spectrum bars ↻", false),
    (MusicEffect::SpectrumWave, "Spectrum wave", false),
    (MusicEffect::SpectrumWaveSides, "Spectrum wave ↻", false),
    (MusicEffect::ColourShift, "Colour shift", false),
];

/// Music mode settings (DESIGN.md §10.1) as the renderer uses them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MusicParams {
    pub palette: Palette,
    /// hue for the Mono palette, degrees
    pub hue: u16,
    /// true: brightness follows loudness; false: fixed
    pub follow_loudness: bool,
    /// 0..30 %
    pub min_brightness_pct: u8,
    /// beat envelope amplitude 0..100 % (Soft 40, Medium 70, Strong 100)
    pub beat_response_pct: u8,
    /// beat envelope decay, ms (Slow 400, Medium 250, Fast 120)
    pub fade_ms: u16,
}

impl Default for MusicParams {
    fn default() -> Self {
        MusicParams { palette: Palette::Rainbow, hue: 20, follow_loudness: true, min_brightness_pct: 5, beat_response_pct: 70, fade_ms: 250 }
    }
}

pub fn palette_colour(p: Palette, hue: u16, x: f32) -> Rgb {
    let x = x - crate::math::floor_f32(x);
    match p {
        Palette::Rainbow => hsv(x * 360.0, 1.0, 1.0),
        Palette::Warm => hsv(x * 60.0 - 10.0, 0.95, 1.0),
        Palette::Cool => hsv(170.0 + x * 110.0, 0.9, 1.0),
        Palette::Neon => [[255, 0, 180], [0, 255, 200], [180, 255, 0], [120, 0, 255]][(x * 4.0) as usize % 4],
        Palette::Mono => hsv(hue as f32, 0.85, 1.0),
    }
}

/// Beat envelope 0..1 at `now` for a beat at `beat`: fast attack, exponential decay over `fade_ms`.
fn envelope(now: u32, beat: Option<u32>, fade_ms: u16) -> f32 {
    match beat {
        Some(b) if now >= b => exp(-((now - b) as f64) / fade_ms.max(30) as f64) as f32,
        _ => 0.0,
    }
}

/// Distance round the loop from the bottom centre (0) to the top centre (1), for each LED position `pos` (0..1).
fn from_bottom_centre(pos: f32, layout: &Layout) -> f32 {
    let c = &layout.config;
    let n = (c.top + c.right + c.bottom + c.left).max(1) as f32;
    let bc = (c.top as f32 + c.right as f32 + c.bottom as f32 / 2.0) / n;
    let mut d = abs_f32(pos - bc);
    if d > 0.5 {
        d = 1.0 - d;
    }
    d * 2.0
}

/// A built-in test rhythm for the effect tiles (DESIGN.md §10.2) when no music plays: 120 BPM, a kick on every beat
/// and a sweep through the bands. Writes the recent beat times into `beats` and returns the frame and their count.
pub fn test_rhythm(t_ms: u32, beats: &mut [u32; 8]) -> (MusicFrame, usize) {
    const BEAT_MS: u32 = 500;
    let since = t_ms % BEAT_MS;
    let kick = exp(-(since as f64) / 150.0) as f32;
    let mut f = MusicFrame { t_ms, level_db: -12.0 - 10.0 * (1.0 - kick), beat: since < 20, bpm: 120.0, loud: 0.25 + 0.75 * kick, ..MusicFrame::default() };
    let sweep = (t_ms % 4000) as f32 / 4000.0;
    for k in 0..BANDS {
        let x = k as f32 / (BANDS - 1) as f32;
        let bass = if k < 4 { kick } else { 0.0 };
        let hump = (1.0 - abs_f32(x - sweep) * 4.0).max(0.0);
        f.bands[k] = (0.15 + bass * 0.85).max(hump * 0.8).min(1.0);
    }
    f.bass = (f.bands[0] + f.bands[1] + f.bands[2] + f.bands[3]) / 4.0;
    f.mid = f.bands[4..9].iter().sum::<f32>() / 5.0;
    f.treble = f.bands[9..].iter().sum::<f32>() / 3.0;
    let n = ((t_ms / BEAT_MS) as usize + 1).min(8);
    let last = t_ms - since;
    for (i, b) in beats.iter_mut().take(n).enumerate() {
        *b = last.saturating_sub(((n - 1 - i) as u32) * BEAT_MS);
    }
    (f, n)
}

/// A band level at a fractional band position (0..1 across the 12 bands), linearly interpolated so a wave is smooth.
fn band_at(f: &MusicFrame, x: f32) -> f32 {
    let p = x.clamp(0.0, 1.0) * (BANDS - 1) as f32;
    let k = (p as usize).min(BANDS - 2);
    let t = p - k as f32;
    f.bands[k] * (1.0 - t) + f.bands[k + 1] * t
}

/// A music bar: `x` is the distance from where the bar starts (0..1), `level` how far it reaches. The LED at the
/// bar's tip is partly lit, so the bar grows smoothly; unlit LEDs keep a faint glow.
fn bar(x: f32, level: f32, p: &MusicParams) -> (Rgb, f32) {
    let colour = palette_colour(p.palette, p.hue, x * 0.8);
    let v = ((level - x) * 12.0 + 1.0).clamp(0.0, 1.0);
    (colour, v.max(0.04))
}

/// A bar spectrum along an edge: `x` (0..1) picks one of the 12 bands, each a segment lit by its level, with a dark
/// LED between segments when there's room, so it reads as separate bars.
fn spectrum_segment(f: &MusicFrame, x: f32, edge_leds: u16, p: &MusicParams) -> (Rgb, f32) {
    let x = x.clamp(0.0, 0.9999);
    let k = (x * BANDS as f32) as usize;
    let within = x * BANDS as f32 - k as f32;
    let per = edge_leds as f32 / BANDS as f32;
    let gap = per >= 3.0 && within * per < 1.0;
    let colour = palette_colour(p.palette, p.hue, k as f32 / BANDS as f32 * 0.85);
    (colour, if gap { 0.0 } else { f.bands[k] })
}

/// The wave spectrum: the bands interpolated smoothly along an edge, brighter where the music is.
fn spectrum_wave(f: &MusicFrame, x: f32, p: &MusicParams) -> (Rgb, f32) {
    let v = band_at(f, x);
    (palette_colour(p.palette, p.hue, x * 0.85), v * v * 1.3)
}

/// High-contrast LED colour: most of the white is taken out of the colour (so it stays
/// saturated on a white wall) and the level gets a gamma of 1.5 and 90 % of the light (deeper darks between hits, less overall light).
fn punch(c: Rgb, v: f32) -> (Rgb, f32) {
    let mx = c[0].max(c[1]).max(c[2]) as u32;
    let mn = c[0].min(c[1]).min(c[2]) as u32;
    let mut o = c;
    if mx > 0 && mn < mx {
        let cut = mn * 85 / 100;
        let span = mx - cut;
        for (k, ch) in c.iter().enumerate() {
            o[k] = (((*ch as u32).saturating_sub(cut)) * mx / span.max(1)).min(255) as u8;
        }
    }
    let g = v * crate::math::sqrt_f32(v.max(0.0)); // v^1.5: darker lows, the peaks kept
    (o, g * 0.9)
}

/// Loudness 0..1 relative to the song (the engine's floor-to-peak follower, `MusicFrame::loud`).
fn loudness(f: &MusicFrame) -> f32 {
    f.loud
}

/// Renders a music effect into `out`. `beats` holds recent beat times, oldest first.
pub fn render(fx: MusicEffect, now: u32, f: &MusicFrame, beats: &[u32], p: &MusicParams, layout: &Layout, out: &mut [u8]) -> bool {
    let n = layout.len();
    if out.len() < n * 3 {
        return false;
    }
    let last = beats.last().copied();
    let resp = p.beat_response_pct.min(100) as f32 / 100.0;
    let env = envelope(now, last, p.fade_ms) * resp;
    let loud = loudness(f);
    let floor = p.min_brightness_pct.min(30) as f32 / 100.0;
    let dominant = (0..BANDS).fold(0, |b, k| if f.bands[k] > f.bands[b] { k } else { b });
    for (i, led) in layout.leds.iter().enumerate() {
        let pos = led.pos as f32 / 65536.0;
        let d = from_bottom_centre(pos, layout);
        let (c, v): (Rgb, f32) = match fx {
            MusicEffect::Spectrum => {
                let band = ((d * BANDS as f32) as usize).min(BANDS - 1);
                (palette_colour(p.palette, p.hue, band as f32 / BANDS as f32), f.bands[band])
            }
            MusicEffect::Pulse => (palette_colour(p.palette, p.hue, beats.len() as f32 * 0.13), 0.15 + 0.85 * env),
            MusicEffect::VuMeter => match led.edge {
                Edge::Left | Edge::Right => {
                    let up = 1.0 - led.along as f32 / 65536.0;
                    let lit = up <= loud;
                    (mix([0, 255, 0], [255, 0, 0], (up * 256.0) as u32), if lit { 1.0 } else { 0.04 })
                }
                _ => ([0, 40, 0], 0.15 * loud),
            },
            MusicEffect::BassGlow => {
                let spread = 0.2 + 0.8 * f.bass;
                (palette_colour(Palette::Warm, p.hue, d * 0.3), if d <= spread { (1.0 - d / spread.max(0.01)) * 0.4 + 0.6 * f.bass } else { 0.0 })
            }
            MusicEffect::Wave => {
                let mut best = 0.0f32;
                let mut colour = palette_colour(p.palette, p.hue, 0.0);
                for (k, &b) in beats.iter().enumerate() {
                    if now < b {
                        continue;
                    }
                    let front = (now - b) as f32 / 1200.0; // a front crosses half the loop in 1.2 s
                    if front > 1.2 {
                        continue;
                    }
                    let v = (1.0 - abs_f32(d - front) / 0.08).max(0.0) * (1.0 - front / 1.2);
                    if v > best {
                        best = v;
                        colour = palette_colour(p.palette, p.hue, (beats.len() - k) as f32 * 0.17);
                    }
                }
                (colour, best)
            }
            MusicEffect::CentreBurst => {
                let r = last.map_or(0.0, |b| now.saturating_sub(b) as f32 / 500.0);
                (palette_colour(p.palette, p.hue, d * 0.5), if d <= r { env } else { 0.0 })
            }
            MusicEffect::FrequencyColour => (palette_colour(p.palette, p.hue, dominant as f32 / BANDS as f32), loud),
            MusicEffect::StrobeBeat => {
                let strong = f.bass > 0.7 || env > 0.6;
                ([255, 255, 255], if strong && last.is_some_and(|b| now.saturating_sub(b) < 60) { 1.0 } else { 0.0 })
            }
            MusicEffect::SpectrumBars | MusicEffect::SpectrumWave => {
                // `along` is in screen terms: left to right on top and bottom, top to bottom on the sides
                let along = led.along as f32 / 65536.0;
                let c = &layout.config;
                match led.edge {
                    // bars rise from the bottom: bass on the left, mid and treble on the right
                    Edge::Left => bar(1.0 - along, f.bass, p),
                    Edge::Right => bar(1.0 - along, f.mid.max(f.treble), p),
                    Edge::Top if fx == MusicEffect::SpectrumBars => spectrum_segment(f, along, c.top, p),
                    Edge::Bottom if fx == MusicEffect::SpectrumBars => spectrum_segment(f, along, c.bottom, p),
                    Edge::Top | Edge::Bottom => spectrum_wave(f, along, p),
                }
            }
            MusicEffect::SpectrumBarsSides | MusicEffect::SpectrumWaveSides => {
                let along = led.along as f32 / 65536.0;
                let c = &layout.config;
                match led.edge {
                    // the rotated bars grow left to right: treble on top, bass at the bottom
                    Edge::Top => bar(along, f.mid.max(f.treble), p),
                    Edge::Bottom => bar(along, f.bass, p),
                    // the spectrum up the sides: bass at the bottom, treble at the top
                    Edge::Left if fx == MusicEffect::SpectrumBarsSides => spectrum_segment(f, 1.0 - along, c.left, p),
                    Edge::Right if fx == MusicEffect::SpectrumBarsSides => spectrum_segment(f, 1.0 - along, c.right, p),
                    Edge::Left | Edge::Right => spectrum_wave(f, 1.0 - along, p),
                }
            }
            MusicEffect::ColourShift => {
                // a new colour on each beat (seeded by the beat's time, so it doesn't stall), drifting with the pitch
                let seed = last.map_or(0, |b| (b / 7) % 16) as f32 / 16.0;
                let drift = dominant as f32 / BANDS as f32 * 0.1;
                (palette_colour(p.palette, p.hue, seed + drift), (0.35 + 0.65 * loud).max(env))
            }
        };
        let mut v = v.clamp(0.0, 1.0);
        let level_drawn = matches!(
            fx,
            MusicEffect::VuMeter | MusicEffect::StrobeBeat | MusicEffect::SpectrumBars | MusicEffect::SpectrumBarsSides
                | MusicEffect::SpectrumWave | MusicEffect::SpectrumWaveSides
        );
        if p.follow_loudness && !level_drawn {
            v *= floor.max(loud);
        }
        let (c, mut v) = punch(c, v.clamp(0.0, 1.0));
        v = v.max(if matches!(fx, MusicEffect::StrobeBeat) { 0.0 } else { floor * 0.5 });
        let c = crate::math::scale(c, (v * 256.0) as u32);
        out[i * 3..i * 3 + 3].copy_from_slice(&c);
    }
    true
}
