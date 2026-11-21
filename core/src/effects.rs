//! The effects catalogue (TECHNICAL.md §15, DESIGN.md §6.2): 52 effects from about 25 shared functions, each a
//! table entry naming a function and its preset. Effects are pure functions of time, settings and layout: no state,
//! no allocation, "randomness" from a stateless hash, so every frame is reproducible. Flashing effects (⚡) are
//! capped at 3 flashes per second by [`FlashCap`] in the renderer, whatever their speed.

pub mod music;

use crate::layout::{Edge, Layout};
use crate::math::{floor_f32, hash2, hsv, hsv_java, mix, sin_f32, PI};

pub type Rgb = [u8; 3];

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Group {
    Calm,
    Nature,
    Lively,
    Party,
    Seasonal,
    Setup,
}

/// Per-effect settings (DESIGN.md §6.2: speed, colour, brightness).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Params {
    /// 1..10, 5 = normal
    pub speed: u8,
    /// colour for single-colour effects, degrees
    pub hue: u16,
    /// 0..100 %
    pub sat: u8,
    /// 0..100 %, applied on top of the picture settings
    pub brightness: u8,
}

impl Default for Params {
    /// the reference colour defaults (hue 20°, saturation 80%).
    fn default() -> Self {
        Params { speed: 5, hue: 20, sat: 80, brightness: 100 }
    }
}

// ------------------------------------------------------------------------- palettes

const RAINBOW: &[Rgb] = &[[255, 0, 0], [255, 160, 0], [255, 255, 0], [0, 255, 0], [0, 160, 255], [80, 0, 255], [255, 0, 200]];
const SUNSET: &[Rgb] = &[[255, 90, 0], [255, 150, 30], [230, 60, 90], [180, 30, 60]];
const FOREST: &[Rgb] = &[[20, 140, 40], [0, 120, 90], [90, 160, 20], [10, 90, 60]];
const TWILIGHT: &[Rgb] = &[[40, 20, 140], [110, 30, 170], [20, 60, 180], [80, 40, 120]];
const OCEAN: &[Rgb] = &[[0, 20, 120], [0, 90, 220], [0, 200, 230], [0, 140, 160]];
const AURORA: &[Rgb] = &[[0, 220, 90], [0, 200, 170], [100, 40, 220], [20, 160, 120]];
const LAVA: &[Rgb] = &[[220, 20, 0], [255, 100, 0], [140, 0, 0], [255, 170, 20]];
const PASTEL: &[Rgb] = &[[255, 150, 190], [150, 240, 200], [190, 160, 255], [255, 200, 150], [150, 210, 255]];
const MEADOW: &[Rgb] = &[[60, 200, 20], [170, 220, 30], [30, 150, 40], [220, 230, 60]];
const SPRING: &[Rgb] = &[[170, 240, 140], [250, 240, 120], [255, 180, 210], [140, 230, 190]];
const HEAT: &[Rgb] = &[[0, 0, 0], [140, 10, 0], [255, 60, 0], [255, 160, 20], [255, 230, 120]];
const SUNRISE: &[Rgb] = &[[0, 0, 0], [90, 5, 0], [220, 60, 0], [255, 150, 40], [255, 220, 170]];
const COOL_WARM: &[Rgb] = &[[0, 120, 255], [180, 0, 255], [255, 80, 40]];

const WHITE: Rgb = [255, 255, 255];
const WARM_WHITE: Rgb = [255, 170, 90]; // ~2700 K
const MOVIE_WHITE: Rgb = [70, 66, 60]; // dim neutral
const RED: Rgb = [255, 0, 0];
const GREEN: Rgb = [0, 255, 0];
const BLUE: Rgb = [0, 0, 255];
const ORANGE: Rgb = [255, 90, 0];
const PURPLE: Rgb = [120, 0, 200];
const PINK: Rgb = [255, 60, 130];
const GOLD: Rgb = [255, 150, 30];
const NAVY: Rgb = [0, 4, 30];
const STORM: Rgb = [0, 8, 40];

const ALL: u8 = 0b1111;
const TOP: u8 = 0b0001;

#[derive(Clone, Copy, Debug)]
enum Spark {
    Color(Rgb),
    RandomHue,
}

/// The shared functions; each catalogue entry is one of these with a preset.
#[derive(Clone, Copy, Debug)]
enum Kind {
    /// the reference effects 0-4, with its float maths (golden-tested)
    Compat(u8),
    Gradient(&'static [Rgb]),
    Drift { pal: &'static [Rgb], period: f32, value: f32 },
    Waves { pal: &'static [Rgb], period: f32, waves: f32, sides: u8 },
    Plasma { pal: &'static [Rgb], period: f32 },
    Flicker { pal: &'static [Rgb], rate: f32, min: f32 },
    Fire,
    Drops { color: Rgb, speed: f32, flow: bool },
    Twinkle { base: Rgb, spark: Spark, per_mille: u32, rate: f32 },
    Sunrise { secs: f32 },
    Comet { heads: u8, opposite: bool, tail: f32, meteors: bool },
    Scanner,
    ColorWipe(&'static [Rgb]),
    ColorCycle,
    Heartbeat,
    TheatreChase(&'static [Rgb]),
    BouncingBalls,
    TradePlaces(Rgb, Rgb),
    Breathe2(Rgb, Rgb),
    Disco,
    RainbowPulse,
    Strobe,
    Police,
    Sparkle,
    Thunderstorm,
    Fireworks,
    Solid(Rgb),
    LayoutTest,
    Primaries,
    GreyRamp,
    Corners,
}

pub struct Effect {
    pub name: &'static str,
    pub group: Group,
    /// ⚡: flashes, left out of the Blue rotation by default, capped at 3 flashes/s
    pub flashes: bool,
    /// one of the five classic effects
    pub from_1_3: bool,
    kind: Kind,
}

impl Effect {
    /// In the Blue button's rotation by default (DESIGN.md §6.2): everything but flashing and setup effects.
    pub fn in_rotation_by_default(&self) -> bool {
        !self.flashes && self.group != Group::Setup
    }
}

const fn e(name: &'static str, group: Group, kind: Kind) -> Effect {
    Effect { name, group, flashes: false, from_1_3: false, kind }
}
const fn flash(name: &'static str, group: Group, kind: Kind) -> Effect {
    Effect { name, group, flashes: true, from_1_3: false, kind }
}
const fn old(name: &'static str, group: Group, k: u8) -> Effect {
    Effect { name, group, flashes: false, from_1_3: true, kind: Kind::Compat(k) }
}

use Group::*;

/// The catalogue, in DESIGN.md §6.2's order. Indices are stable ids: add new effects at the end of their group
/// only before release; after 2.0.0, append.
pub static EFFECTS: [Effect; 52] = [
    // Calm (12)
    old("Slow colours", Calm, 4),
    old("Breathing", Calm, 2),
    e("Gradient", Calm, Kind::Gradient(COOL_WARM)),
    e("Sunset", Calm, Kind::Drift { pal: SUNSET, period: 40.0, value: 0.45 }),
    e("Aurora", Calm, Kind::Waves { pal: AURORA, period: 14.0, waves: 1.5, sides: TOP }),
    e("Ocean", Calm, Kind::Waves { pal: OCEAN, period: 10.0, waves: 2.0, sides: ALL }),
    e("Candle", Calm, Kind::Flicker { pal: &[[255, 120, 20], [255, 90, 10], [255, 140, 40]], rate: 6.0, min: 0.55 }),
    e("Lava lamp", Calm, Kind::Plasma { pal: LAVA, period: 18.0 }),
    e("Forest", Calm, Kind::Drift { pal: FOREST, period: 30.0, value: 0.8 }),
    e("Twilight", Calm, Kind::Drift { pal: TWILIGHT, period: 36.0, value: 0.6 }),
    e("Warm white", Calm, Kind::Solid(WARM_WHITE)),
    e("Movie white", Calm, Kind::Solid(MOVIE_WHITE)),
    // Nature (8)
    e("Fire", Nature, Kind::Fire),
    e("Rain", Nature, Kind::Drops { color: [80, 140, 255], speed: 0.9, flow: false }),
    flash("Thunderstorm", Nature, Kind::Thunderstorm),
    e("Starry night", Nature, Kind::Twinkle { base: NAVY, spark: Spark::Color(WHITE), per_mille: 60, rate: 0.6 }),
    e("Sunrise", Nature, Kind::Sunrise { secs: 600.0 }),
    e("Waterfall", Nature, Kind::Drops { color: [120, 200, 255], speed: 0.7, flow: true }),
    e("Meadow", Nature, Kind::Waves { pal: MEADOW, period: 8.0, waves: 1.0, sides: ALL }),
    e("Northern sky", Nature, Kind::Waves { pal: AURORA, period: 10.0, waves: 1.2, sides: ALL }),
    // Lively (13)
    old("Rainbow swirl ↻", Lively, 0),
    old("Rainbow swirl ↺", Lively, 1),
    e("Comet", Lively, Kind::Comet { heads: 1, opposite: false, tail: 0.25, meteors: false }),
    e("Dual comets", Lively, Kind::Comet { heads: 2, opposite: true, tail: 0.2, meteors: false }),
    e("Scanner", Lively, Kind::Scanner),
    e("Color wipe", Lively, Kind::ColorWipe(RAINBOW)),
    e("Color cycle", Lively, Kind::ColorCycle),
    e("Twinkle", Lively, Kind::Twinkle { base: [8, 8, 12], spark: Spark::RandomHue, per_mille: 120, rate: 1.5 }),
    e("Heartbeat", Lively, Kind::Heartbeat),
    e("Theatre chase", Lively, Kind::TheatreChase(RAINBOW)),
    e("Plasma", Lively, Kind::Plasma { pal: RAINBOW, period: 6.0 }),
    e("Bouncing balls", Lively, Kind::BouncingBalls),
    e("Meteor rain", Lively, Kind::Comet { heads: 4, opposite: false, tail: 0.15, meteors: true }),
    // Party (6)
    flash("Strobe", Party, Kind::Strobe),
    flash("Police", Party, Kind::Police),
    flash("Sparkle", Party, Kind::Sparkle),
    e("Disco", Party, Kind::Disco),
    e("Confetti", Party, Kind::Twinkle { base: [0, 0, 0], spark: Spark::RandomHue, per_mille: 220, rate: 4.0 }),
    e("Rainbow pulse", Party, Kind::RainbowPulse),
    // Seasonal (8)
    e("Festive", Seasonal, Kind::TradePlaces(RED, GREEN)),
    e("Pastel", Seasonal, Kind::Drift { pal: PASTEL, period: 30.0, value: 0.85 }),
    e("Ice", Seasonal, Kind::Twinkle { base: [60, 110, 160], spark: Spark::Color([230, 245, 255]), per_mille: 150, rate: 2.0 }),
    e("Halloween", Seasonal, Kind::Flicker { pal: &[ORANGE, PURPLE, ORANGE], rate: 3.0, min: 0.35 }),
    e("Valentine", Seasonal, Kind::Breathe2(PINK, RED)),
    e("Spring", Seasonal, Kind::Waves { pal: SPRING, period: 12.0, waves: 1.0, sides: ALL }),
    e("Lantern", Seasonal, Kind::Flicker { pal: &[GOLD, [255, 120, 20], GOLD], rate: 1.5, min: 0.7 }),
    flash("Fireworks", Seasonal, Kind::Fireworks),
    // For setup (5)
    e("Layout test", Setup, Kind::LayoutTest),
    e("White", Setup, Kind::Solid(WHITE)),
    e("Primaries", Setup, Kind::Primaries),
    e("Grey ramp", Setup, Kind::GreyRamp),
    e("Corners", Setup, Kind::Corners),
];

// ------------------------------------------------------------------------- helpers

#[inline]
fn frac(x: f32) -> f32 {
    x - floor_f32(x)
}

/// A cyclic palette at x (one full cycle per 1.0).
fn pal(p: &[Rgb], x: f32) -> Rgb {
    let f = frac(x) * p.len() as f32;
    let i = f as usize % p.len();
    mix(p[i], p[(i + 1) % p.len()], ((f - floor_f32(f)) * 256.0) as u32)
}

/// A palette from first to last colour, x 0..1 (not cyclic).
fn pal_lin(p: &[Rgb], x: f32) -> Rgb {
    let f = x.clamp(0.0, 1.0) * (p.len() - 1) as f32;
    let i = (f as usize).min(p.len() - 2);
    mix(p[i], p[i + 1], ((f - i as f32) * 256.0) as u32)
}

#[inline]
fn val(c: Rgb, v: f32) -> Rgb {
    let v = (v.clamp(0.0, 1.0) * 256.0) as u32;
    crate::math::scale(c, v)
}

#[inline]
fn wave(x: f32) -> f32 {
    0.5 + 0.5 * sin_f32(2.0 * PI as f32 * x)
}

/// Value noise: smooth random 0..1 per (key, time).
fn noise(key: u32, t: f32) -> f32 {
    let b = floor_f32(t);
    let f = t - b;
    let a = (hash2(key, b as i32 as u32) >> 8) as f32 / 16_777_216.0;
    let c = (hash2(key, b as i32 as u32 + 1) >> 8) as f32 / 16_777_216.0;
    let s = f * f * (3.0 - 2.0 * f);
    a + (c - a) * s
}

#[inline]
fn unit(h: u32) -> f32 {
    (h >> 8) as f32 / 16_777_216.0
}

#[inline]
fn put(out: &mut [u8], i: usize, c: Rgb) {
    out[i * 3..i * 3 + 3].copy_from_slice(&c);
}

fn user_colour(p: &Params) -> Rgb {
    hsv(p.hue as f32, p.sat.min(100) as f32 / 100.0, 1.0)
}

/// The number of effects.
pub fn count() -> usize {
    EFFECTS.len()
}

/// Single-colour mode and the screen-off dim colour (the reference `AmbiService.solid`): `value` 1.0 or 0.12.
pub fn single_colour(p: &Params, value: f32, layout: &Layout, out: &mut [u8]) {
    let c = hsv_java(p.hue as i32, p.sat.min(100) as f32 / 100.0, value);
    for i in 0..layout.len().min(out.len() / 3) {
        put(out, i, c);
    }
}

/// Renders effect `id` at `t_ms` into `out` (3 bytes per LED, strip order). Returns false for an unknown id or a
/// short buffer. Brightness is applied here; the picture tables (gamma, balance) come after, in the pipeline.
pub fn render(id: usize, t_ms: u32, p: &Params, layout: &Layout, out: &mut [u8]) -> bool {
    let n = layout.len();
    let Some(fx) = EFFECTS.get(id) else { return false };
    if out.len() < n * 3 {
        return false;
    }
    let t = (t_ms as u64 * p.speed.clamp(1, 10) as u64 / 5) as f32 / 1000.0;
    let leds = &layout.leds;
    let user = user_colour(p);
    for (i, led) in leds.iter().enumerate() {
        let pos = led.pos as f32 / 65536.0;
        let along = led.along as f32 / 65536.0;
        let side_bit = 1u8 << led.edge as u8;
        let key = i as u32;
        let c: Rgb = match fx.kind {
            Kind::Compat(k) => compat(k, i, n, t, p),
            Kind::Gradient(g) => pal(g, pos),
            Kind::Drift { pal: g, period, value } => val(pal(g, t / period + pos * 0.25), value),
            Kind::Waves { pal: g, period, waves, sides } => {
                if sides & side_bit == 0 {
                    [0, 0, 0]
                } else {
                    let x = pos * waves - t / period;
                    val(pal(g, x), 0.45 + 0.55 * wave(pos * waves * 1.7 + t / period * 0.6))
                }
            }
            Kind::Plasma { pal: g, period } => {
                let v = sin_f32(2.0 * PI as f32 * (pos * 2.0 + t / period))
                    + sin_f32(2.0 * PI as f32 * (pos * 3.3 - t / (period * 1.7)))
                    + sin_f32(2.0 * PI as f32 * (along + t / (period * 2.3)));
                pal(g, (v + 3.0) / 6.0 + t / (period * 4.0))
            }
            Kind::Flicker { pal: g, rate, min } => {
                let own = noise(key, t * rate);
                let shared = noise(0xF11C, t * rate * 0.5);
                val(pal(g, own * 0.5), min + (1.0 - min) * (0.6 * own + 0.4 * shared))
            }
            Kind::Fire => {
                let n1 = noise(key, t * 8.0);
                let heat = match led.edge {
                    Edge::Left | Edge::Right => (along * 0.9 + 0.1) * (0.55 + 0.45 * n1) + 0.15 * noise(key ^ 77, t * 3.0),
                    Edge::Bottom => 0.75 + 0.25 * n1,
                    Edge::Top => 0.12 * n1,
                };
                pal_lin(HEAT, heat)
            }
            Kind::Drops { color, speed, flow } => match led.edge {
                Edge::Left | Edge::Right => {
                    if flow {
                        val(mix(color, WHITE, (wave(along * 3.0 - t * speed) * 120.0) as u32), 0.35 + 0.65 * wave(along * 5.0 - t * speed * 1.6))
                    } else {
                        let mut v: f32 = 0.06;
                        for k in 0..3u32 {
                            let seed = unit(hash2(led.edge as u32 * 31 + k, 7));
                            let head = frac(t * speed * (0.8 + 0.4 * seed) + seed);
                            let d = head - along;
                            if (0.0..0.18).contains(&d) {
                                v = v.max(1.0 - d / 0.18);
                            }
                        }
                        val(color, v)
                    }
                }
                Edge::Top if flow => val(color, 0.7),
                _ => val(color, 0.06),
            },
            Kind::Twinkle { base, spark, per_mille, rate } => {
                let phase_t = t * rate + unit(hash2(key, 0x51)) ;
                let bucket = floor_f32(phase_t) as i32 as u32;
                let h = hash2(key, bucket);
                if h % 1000 < per_mille {
                    let env = sin_f32(PI as f32 * frac(phase_t));
                    let s = match spark {
                        Spark::Color(c) => c,
                        Spark::RandomHue => hsv((h >> 10) as f32 % 360.0, 0.9, 1.0),
                    };
                    mix(base, s, (env * 256.0) as u32)
                } else {
                    base
                }
            }
            Kind::Sunrise { secs } => pal_lin(SUNRISE, t / secs),
            Kind::Comet { heads, opposite, tail, meteors } => {
                let mut v: f32 = 0.0;
                let mut hue_c = user;
                for k in 0..heads {
                    let dir: f32 = if opposite && k % 2 == 1 { -1.0 } else { 1.0 };
                    let head = frac(t * 0.25 * dir + k as f32 / heads as f32);
                    let d = frac((head - pos) * dir);
                    if d < tail {
                        let mut b = 1.0 - d / tail;
                        b *= b;
                        if meteors && hash2(key, (t * 12.0) as u32) % 100 < 35 {
                            b *= 0.4;
                        }
                        if b > v {
                            v = b;
                            if meteors {
                                hue_c = hsv(k as f32 * 90.0 + 180.0, 0.5, 1.0);
                            }
                        }
                    }
                }
                val(hue_c, v)
            }
            Kind::Scanner => {
                if led.edge == Edge::Top {
                    let x = 0.5 - 0.5 * crate::math::cos((t / 2.5 * 2.0) as f64 * PI) as f32;
                    val(user, 1.0 - crate::math::abs_f32(along - x) / 0.08)
                } else {
                    [0, 0, 0]
                }
            }
            Kind::ColorWipe(g) => {
                let cycle = t / 2.0;
                let k = floor_f32(cycle) as i32;
                let c_new = g[k.rem_euclid(g.len() as i32) as usize];
                let c_old = g[(k - 1).rem_euclid(g.len() as i32) as usize];
                if pos < frac(cycle) { c_new } else { c_old }
            }
            Kind::ColorCycle => {
                let step = t / 3.0;
                let k = floor_f32(step);
                let f = (frac(step) * 6.0).min(1.0); // fade in the first sixth of each step
                mix(hsv(k * 60.0 - 60.0, 1.0, 1.0), hsv(k * 60.0, 1.0, 1.0), (f * 256.0) as u32)
            }
            Kind::Heartbeat => {
                // Each pulse counts as a flash, so speed is capped at 6 (one beat = two pulses per second at most),
                // which keeps Heartbeat under 3 flashes per second without the ⚡ cap.
                let th = (t_ms as u64 * p.speed.clamp(1, 6) as u64 / 5) as f32 / 1000.0;
                let ph = frac(th / 1.2);
                let pulse = |c: f32, w: f32| if ph >= c && ph < c + w { let x = 1.0 - (ph - c) / w; x * x } else { 0.0 };
                val(user, (pulse(0.0, 0.14).max(0.7 * pulse(0.22, 0.14))).max(0.05))
            }
            Kind::TheatreChase(g) => {
                if (i + floor_f32(t * 8.0) as usize) % 3 == 0 { pal(g, pos) } else { [0, 0, 0] }
            }
            Kind::BouncingBalls => match led.edge {
                Edge::Left | Edge::Right => {
                    let up = 1.0 - along; // 0 at the bottom
                    let mut c = [0u8; 3];
                    for k in 0..3u32 {
                        let period = 1.4 + 0.3 * k as f32;
                        let ph = frac(t / period + k as f32 * 0.37 + led.edge as u32 as f32 * 0.21);
                        let y = 4.0 * ph * (1.0 - ph) * 0.9;
                        let v = 1.0 - crate::math::abs_f32(up - y) / 0.07;
                        if v > 0.0 {
                            c = val(hsv(k as f32 * 120.0, 1.0, 1.0), v);
                        }
                    }
                    c
                }
                _ => [0, 0, 0],
            },
            Kind::TradePlaces(a, b) => {
                let m = (wave(t / 8.0) * 256.0) as u32;
                if (i / 4) % 2 == 0 { mix(a, b, m) } else { mix(b, a, m) }
            }
            Kind::Breathe2(a, b) => {
                val(mix(a, b, (wave(t / 10.0) * 256.0) as u32), 0.15 + 0.85 * wave(t / 4.0))
            }
            Kind::Disco => {
                let seg = (i / 6) as u32;
                let h = hash2(seg, floor_f32(t * 2.5) as i32 as u32);
                if h % 4 == 0 { [0, 0, 0] } else { hsv((h >> 8) as f32 % 360.0, 1.0, 1.0) }
            }
            Kind::RainbowPulse => val(hsv(pos * 360.0 + t * 40.0, 1.0, 1.0), 0.25 + 0.75 * wave(t / 1.5)),
            Kind::Strobe => {
                if frac(t * 5.0) < 0.15 { WHITE } else { [0, 0, 0] }
            }
            Kind::Police => {
                let ph = frac(t * 2.0);
                let on = frac(ph * 4.0) < 0.5;
                match (ph < 0.5, pos < 0.5, on) {
                    (true, true, true) => RED,
                    (false, false, true) => BLUE,
                    _ => [0, 0, 0],
                }
            }
            Kind::Sparkle => {
                if hash2(key, floor_f32(t * 20.0) as i32 as u32) % 100 < 3 { WHITE } else { [0, 0, 0] }
            }
            Kind::Thunderstorm => {
                let sec = floor_f32(t) as i32 as u32;
                let h = hash2(0x7A11, sec);
                let into = frac(t);
                let at = (h % 600) as f32 / 1000.0;
                let strike = h % 3 == 0 && ((into >= at && into < at + 0.08) || (into >= at + 0.16 && into < at + 0.21));
                if strike { mix(WHITE, [180, 200, 255], 80) } else { val(STORM, 0.6 + 0.4 * noise(key / 8, t * 0.5)) }
            }
            Kind::Fireworks => {
                let mut best = [0u8; 3];
                for back in 0..2i32 {
                    let cycle = t / 1.6;
                    let k = floor_f32(cycle) as i32 - back;
                    let age = cycle - k as f32;
                    if age < 0.0 || age > 2.0 {
                        continue;
                    }
                    let h = hash2(0xF1E, k as u32);
                    let centre = unit(h);
                    let mut d = crate::math::abs_f32(pos - centre);
                    if d > 0.5 {
                        d = 1.0 - d;
                    }
                    let radius = 0.02 + age * 0.08;
                    if d < radius {
                        let v = (1.0 - age / 2.0) * (1.0 - d / radius * 0.6);
                        let c = if age < 0.06 { WHITE } else { hsv((h >> 9) as f32 % 360.0, 0.85, 1.0) };
                        let c = val(c, v);
                        if c[0] as u32 + c[1] as u32 + c[2] as u32 > best[0] as u32 + best[1] as u32 + best[2] as u32 {
                            best = c;
                        }
                    }
                }
                best
            }
            Kind::Solid(c) => c,
            Kind::LayoutTest => {
                let mut c = match led.edge {
                    Edge::Top => RED,
                    Edge::Right => GREEN,
                    Edge::Bottom => BLUE,
                    Edge::Left => [160, 160, 160],
                };
                if i < 3 && (t * 3.0) as i32 % 2 == 0 {
                    c = [255, 200, 0];
                }
                c
            }
            Kind::Primaries => [RED, GREEN, BLUE][(floor_f32(t / 2.0) as i32).rem_euclid(3) as usize],
            Kind::GreyRamp => {
                let v = (pos * 255.0) as u8;
                [v, v, v]
            }
            Kind::Corners => {
                let prev = leds[(i + n - 1) % n].edge;
                let next = leds[(i + 1) % n].edge;
                if prev != led.edge || next != led.edge { WHITE } else { [0, 0, 0] }
            }
        };
        put(out, i, c);
    }
    if p.brightness < 100 {
        let v = p.brightness as u32 * 256 / 100;
        for px in out[..n * 3].iter_mut() {
            *px = ((*px as u32 * v) >> 8) as u8;
        }
    }
    true
}

/// the reference `Effects.render`, float for float (golden-tested against `expected/effects.*.csv`).
fn compat(k: u8, i: usize, n: usize, t: f32, p: &Params) -> Rgb {
    let hue = p.hue as i32;
    let sat = p.sat.min(100) as f32 / 100.0;
    match k {
        0 | 1 => {
            let dir: f32 = if k == 0 { -1.0 } else { 1.0 };
            hsv_java((i as f32 * 360.0 / n as f32 + dir * t * 60.0) as i32, 1.0, 1.0)
        }
        2 => {
            let v = 0.12 + 0.88 * (0.5 + 0.5 * crate::math::sin(t as f64 * 2.0 * PI / 5.0) as f32);
            hsv_java(hue, sat, v)
        }
        _ => hsv_java((t * 8.0) as i32, 0.9, 1.0),
    }
}

/// Caps flashing at 3 per second (WCAG 2.3.1) whatever the effect or its speed: a frame whose mean brightness jumps
/// up by a flash's worth within 1/3 s of the last flash is replaced by the previous frame.
#[derive(Clone, Copy, Debug, Default)]
pub struct FlashCap {
    last_onset_ms: Option<u32>,
    prev_mean: u32,
}

/// A rise in mean channel value (0..255) that counts as the start of a flash.
pub const FLASH_RISE: u32 = 40;
pub const MIN_FLASH_GAP_MS: u32 = 334;

impl FlashCap {
    /// Applies the cap to `frame` (in place), with `prev` the frame shown last. Returns true if it held the frame.
    pub fn apply(&mut self, t_ms: u32, prev: &[u8], frame: &mut [u8]) -> bool {
        let mean = mean(frame);
        if mean >= self.prev_mean + FLASH_RISE {
            let too_soon = self.last_onset_ms.is_some_and(|l| t_ms.wrapping_sub(l) < MIN_FLASH_GAP_MS);
            if too_soon {
                frame.copy_from_slice(&prev[..frame.len()]);
                return true;
            }
            self.last_onset_ms = Some(t_ms);
        }
        self.prev_mean = mean;
        false
    }
}

pub fn mean(frame: &[u8]) -> u32 {
    if frame.is_empty() {
        return 0;
    }
    frame.iter().map(|&v| v as u32).sum::<u32>() / frame.len() as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::LayoutConfig;
    use alloc::vec;

    #[test]
    fn catalogue_shape() {
        assert_eq!(EFFECTS.len(), 52);
        let groups = [Calm, Nature, Lively, Party, Seasonal, Setup];
        let per: alloc::vec::Vec<usize> = groups.iter().map(|g| EFFECTS.iter().filter(|e| e.group == *g).count()).collect();
        assert_eq!(per, [12, 8, 13, 6, 8, 5]);
        assert_eq!(EFFECTS.iter().filter(|e| e.from_1_3).count(), 4);
        assert_eq!(EFFECTS.iter().filter(|e| e.flashes).count(), 5);
    }

    #[test]
    fn unknown_id_and_short_buffer() {
        let l = Layout::new(&LayoutConfig::default());
        let mut out = vec![0u8; l.len() * 3];
        assert!(!render(52, 0, &Params::default(), &l, &mut out));
        assert!(!render(0, 0, &Params::default(), &l, &mut out[..10]));
    }
}
