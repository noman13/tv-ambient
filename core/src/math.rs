//! Small maths for `no_std`: floor/ceil, Java-style rounding, sin/cos, ln/exp/pow, and the reference HSV.
//! Used at creation time (lookup tables) and by effects; the analysis hot loop is integer only.

pub const PI: f64 = core::f64::consts::PI;
pub const TAU: f64 = core::f64::consts::TAU;
const LN2: f64 = core::f64::consts::LN_2;

#[inline]
pub fn floor_f32(x: f32) -> f32 {
    let t = x as i32 as f32;
    if t > x { t - 1.0 } else { t }
}

#[inline]
pub fn ceil_f32(x: f32) -> f32 {
    let t = x as i32 as f32;
    if t < x { t + 1.0 } else { t }
}

#[inline]
pub fn floor_f64(x: f64) -> f64 {
    let t = x as i64 as f64;
    if t > x { t - 1.0 } else { t }
}

#[inline]
pub fn abs_f32(x: f32) -> f32 {
    if x < 0.0 { -x } else { x }
}

/// `Math.round(float)` as Java does it: floor(x + 0.5).
#[inline]
pub fn round_java(x: f32) -> i32 {
    floor_f32(x + 0.5) as i32
}

/// Sine to ~1e-10 for any finite x.
pub fn sin(x: f64) -> f64 {
    const HALF_PI: f64 = core::f64::consts::FRAC_PI_2;
    let mut r = x - TAU * floor_f64(x / TAU + 0.5); // [-pi, pi]
    if r > HALF_PI {
        r = PI - r;
    } else if r < -HALF_PI {
        r = -PI - r;
    }
    let r2 = r * r;
    r * (1.0
        + r2 * (-1.0 / 6.0
            + r2 * (1.0 / 120.0
                + r2 * (-1.0 / 5040.0
                    + r2 * (1.0 / 362_880.0 + r2 * (-1.0 / 39_916_800.0 + r2 / 6_227_020_800.0))))))
}

#[inline]
pub fn cos(x: f64) -> f64 {
    sin(x + core::f64::consts::FRAC_PI_2)
}

#[inline]
pub fn sin_f32(x: f32) -> f32 {
    sin(x as f64) as f32
}

/// Natural log for x > 0 (returns a large negative number for x <= 0).
pub fn ln(x: f64) -> f64 {
    if x <= 0.0 {
        return -1.0e300;
    }
    let bits = x.to_bits();
    let mut e = ((bits >> 52) & 0x7ff) as i64 - 1023;
    let mut m = f64::from_bits((bits & 0x000f_ffff_ffff_ffff) | 0x3ff0_0000_0000_0000); // [1, 2)
    if e == -1023 {
        // subnormal: normalise
        m = x * (1u64 << 52) as f64;
        let b = m.to_bits();
        e = ((b >> 52) & 0x7ff) as i64 - 1023 - 52;
        m = f64::from_bits((b & 0x000f_ffff_ffff_ffff) | 0x3ff0_0000_0000_0000);
    }
    if m > core::f64::consts::SQRT_2 {
        m *= 0.5;
        e += 1;
    }
    let s = (m - 1.0) / (m + 1.0); // |s| <= 0.172
    let s2 = s * s;
    let (mut term, mut sum, mut k) = (s, 0.0, 1.0);
    for _ in 0..10 {
        sum += term / k;
        term *= s2;
        k += 2.0;
    }
    2.0 * sum + e as f64 * LN2
}

/// e^x for |x| < ~700.
pub fn exp(x: f64) -> f64 {
    if x < -700.0 {
        return 0.0;
    }
    let k = floor_f64(x / LN2 + 0.5);
    let r = x - k * LN2; // |r| <= ln2/2
    let (mut term, mut sum) = (1.0, 1.0);
    for i in 1..=14 {
        term *= r / i as f64;
        sum += term;
    }
    sum * f64::from_bits(((k as i64 + 1023) as u64) << 52)
}

/// x^y for x >= 0, as `Math.pow` for the cases used here (0^y = 0 for y > 0, x^0 = 1).
pub fn pow(x: f64, y: f64) -> f64 {
    if y == 0.0 {
        1.0
    } else if x <= 0.0 {
        0.0
    } else {
        exp(y * ln(x))
    }
}

pub fn sqrt_f32(x: f32) -> f32 {
    if x <= 0.0 {
        return 0.0;
    }
    let mut g = f32::from_bits((x.to_bits() >> 1) + 0x1fc0_0000);
    for _ in 0..4 {
        g = 0.5 * (g + x / g);
    }
    g
}

/// the reference `Effects.hsv`: hue in degrees (any int), sat and val 0..1, rounded like Java.
pub fn hsv_java(hue: i32, sat: f32, val: f32) -> [u8; 3] {
    let h = (((hue % 360) + 360) % 360) as f32 / 60.0;
    let sector = h as i32;
    let f = h - sector as f32;
    let p = val * (1.0 - sat);
    let q = val * (1.0 - sat * f);
    let u = val * (1.0 - sat * (1.0 - f));
    let (r, g, b) = match sector {
        0 => (val, u, p),
        1 => (q, val, p),
        2 => (p, val, u),
        3 => (p, q, val),
        4 => (u, p, val),
        _ => (val, p, q),
    };
    [chan(r), chan(g), chan(b)]
}

#[inline]
fn chan(v: f32) -> u8 {
    round_java(v * 255.0).clamp(0, 255) as u8
}

/// HSV with a fractional hue (degrees), for effects that move smoothly.
pub fn hsv(hue: f32, sat: f32, val: f32) -> [u8; 3] {
    let mut h = hue - 360.0 * floor_f32(hue / 360.0);
    if h >= 360.0 {
        h = 0.0;
    }
    let h = h / 60.0;
    let sector = h as i32;
    let f = h - sector as f32;
    let (p, q, u) = (val * (1.0 - sat), val * (1.0 - sat * f), val * (1.0 - sat * (1.0 - f)));
    let (r, g, b) = match sector {
        0 => (val, u, p),
        1 => (q, val, p),
        2 => (p, val, u),
        3 => (p, q, val),
        4 => (u, p, val),
        _ => (val, p, q),
    };
    [chan(r), chan(g), chan(b)]
}

/// Linear blend of two colours, `t` 0..256.
#[inline]
pub fn mix(a: [u8; 3], b: [u8; 3], t: u32) -> [u8; 3] {
    let t = t.min(256);
    let m = |x: u8, y: u8| ((x as u32 * (256 - t) + y as u32 * t + 128) >> 8) as u8;
    [m(a[0], b[0]), m(a[1], b[1]), m(a[2], b[2])]
}

/// Scales a colour by `v` 0..256.
#[inline]
pub fn scale(c: [u8; 3], v: u32) -> [u8; 3] {
    let v = v.min(256);
    [((c[0] as u32 * v) >> 8) as u8, ((c[1] as u32 * v) >> 8) as u8, ((c[2] as u32 * v) >> 8) as u8]
}

/// Deterministic xorshift32 (TECHNICAL.md §15): seeded once, never from the clock.
#[derive(Clone, Copy, Debug)]
pub struct Rng(u32);

impl Rng {
    pub const fn new(seed: u32) -> Rng {
        Rng(if seed == 0 { 0x9e37_79b9 } else { seed })
    }
    #[inline]
    pub fn next(&mut self) -> u32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        x
    }
    /// 0..n
    #[inline]
    pub fn below(&mut self, n: u32) -> u32 {
        if n == 0 { 0 } else { self.next() % n }
    }
}

/// A stateless hash of (a, b) to 0..2^32, for "random" values that must be the same at any time step.
#[inline]
pub fn hash2(a: u32, b: u32) -> u32 {
    let mut x = a.wrapping_mul(0x9e37_79b1) ^ b.wrapping_mul(0x85eb_ca77);
    x ^= x >> 15;
    x = x.wrapping_mul(0x2c1b_3c6d);
    x ^= x >> 12;
    x = x.wrapping_mul(0x297a_2d39);
    x ^ (x >> 15)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn functions_match_std() {
        for i in -200..200 {
            let x = i as f64 * 0.137;
            assert!((sin(x) - x.sin()).abs() < 1e-9, "sin {x}");
            assert!((cos(x) - x.cos()).abs() < 1e-9, "cos {x}");
            assert!((exp(x / 10.0) - (x / 10.0).exp()).abs() / (x / 10.0).exp() < 1e-12, "exp {x}");
        }
        for i in 1..1000 {
            let x = i as f64 / 997.0;
            assert!((ln(x) - x.ln()).abs() < 1e-12, "ln {x}");
            assert!((pow(x, 1.9) - x.powf(1.9)).abs() < 1e-12, "pow {x}");
            assert!((sqrt_f32(x as f32 * 50.0) - (x as f32 * 50.0).sqrt()).abs() < 1e-4);
        }
        assert_eq!(pow(0.0, 1.9), 0.0);
        for x in [-2.5f32, -0.5, 0.0, 0.49, 0.5, 1.5, 7.99] {
            assert_eq!(floor_f32(x), x.floor());
            assert_eq!(ceil_f32(x), x.ceil());
        }
        assert_eq!(round_java(-0.5), 0);
        assert_eq!(round_java(2.5), 3);
    }

    #[test]
    fn hsv_matches_1_3_0_corners() {
        assert_eq!(hsv_java(0, 1.0, 1.0), [255, 0, 0]);
        assert_eq!(hsv_java(120, 1.0, 1.0), [0, 255, 0]);
        assert_eq!(hsv_java(-120, 1.0, 1.0), [0, 0, 255]);
        assert_eq!(hsv_java(20, 0.8, 1.0), [255, 119, 51]);
    }
}
