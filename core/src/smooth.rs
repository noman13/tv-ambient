//! Temporal smoothing and interpolation between captures (TECHNICAL.md §7 E6). Buffers are sized once.

use alloc::vec;
use alloc::vec::Vec;

use crate::math::round_java;

/// the reference output smoothing: `cur += (target - cur) * alpha`, alpha = 1 - (setting - 1) * 0.095, rounded like Java.
pub struct Smoother {
    cur: Vec<f32>,
    out: Vec<u8>,
    alpha: f32,
}

impl Smoother {
    /// `setting` 1 (instant) .. 10 (very soft); `channels` = 3 x LEDs.
    pub fn new(channels: usize, setting: u8) -> Smoother {
        let mut s = Smoother { cur: vec![0.0; channels], out: vec![0; channels], alpha: 1.0 };
        s.set(setting);
        s
    }

    pub fn set(&mut self, setting: u8) {
        self.alpha = 1.0 - (setting.clamp(1, 10) as f32 - 1.0) * 0.095;
    }

    /// One output tick towards `target`; `smooth` false = jump (the reference uses alpha 1 for effects and before the
    /// first capture). Returns whether any output byte changed.
    pub fn step(&mut self, target: &[u8], smooth: bool) -> bool {
        let alpha = if smooth { self.alpha } else { 1.0 };
        let mut changed = false;
        for ((c, o), &t) in self.cur.iter_mut().zip(self.out.iter_mut()).zip(target) {
            *c += (t as f32 - *c) * alpha;
            let v = round_java(*c).clamp(0, 255) as u8;
            if v != *o {
                *o = v;
                changed = true;
            }
        }
        changed
    }

    pub fn output(&self) -> &[u8] {
        &self.out
    }
}

/// Eases from the colours shown when a capture arrived to that capture, over the expected time to the next one, so
/// LEDs updated at 30-60/s move smoothly between 10-20 captures/s.
pub struct Interpolator {
    from: Vec<u8>,
    to: Vec<u8>,
    arrived_ns: u64,
    span_ns: u64,
    has: bool,
}

impl Interpolator {
    pub fn new(channels: usize) -> Interpolator {
        Interpolator { from: vec![0; channels], to: vec![0; channels], arrived_ns: 0, span_ns: 1, has: false }
    }

    /// A new capture at `now_ns`: the fade restarts from what's showing now towards `frame`.
    pub fn push(&mut self, now_ns: u64, frame: &[u8]) {
        if self.has {
            let span = now_ns.saturating_sub(self.arrived_ns).clamp(16_000_000, 250_000_000);
            let n = self.from.len();
            for i in 0..n.min(frame.len()) {
                let v = blend(self.from[i], self.to[i], ease(self.progress(now_ns)));
                self.from[i] = v;
            }
            self.span_ns = span;
        } else {
            let n = frame.len().min(self.from.len());
            self.from[..n].copy_from_slice(&frame[..n]);
            self.span_ns = 50_000_000;
        }
        let n = self.to.len().min(frame.len());
        self.to[..n].copy_from_slice(&frame[..n]);
        self.arrived_ns = now_ns;
        self.has = true;
    }

    fn progress(&self, now_ns: u64) -> u32 {
        let dt = now_ns.saturating_sub(self.arrived_ns);
        ((dt.min(self.span_ns) * 256) / self.span_ns.max(1)) as u32
    }

    /// The blended colours at `now_ns`. Returns false before the first capture.
    pub fn render(&self, now_ns: u64, out: &mut [u8]) -> bool {
        if !self.has {
            return false;
        }
        let t = ease(self.progress(now_ns));
        for (i, o) in out.iter_mut().enumerate().take(self.to.len()) {
            *o = blend(self.from[i], self.to[i], t);
        }
        true
    }
}

#[inline]
fn blend(a: u8, b: u8, t: u32) -> u8 {
    ((a as u32 * (256 - t) + b as u32 * t + 128) >> 8) as u8
}

/// Smoothstep on 0..256.
#[inline]
fn ease(t: u32) -> u32 {
    let t = t.min(256);
    (t * t * (768 - 2 * t)) >> 16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smoother_follows_1_3_0() {
        let mut s = Smoother::new(3, 6);
        assert!(s.step(&[200, 0, 0], true));
        assert_eq!(s.output(), &[105, 0, 0]); // 200 * 0.525 = 105
        for _ in 0..40 {
            s.step(&[200, 0, 0], true);
        }
        assert_eq!(s.output(), &[200, 0, 0]);
        assert!(!s.step(&[200, 0, 0], true));
    }

    #[test]
    fn interpolator_eases_between_captures() {
        let mut i = Interpolator::new(3);
        let mut out = [0u8; 3];
        assert!(!i.render(0, &mut out));
        i.push(0, &[0, 0, 0]);
        i.push(100_000_000, &[255, 0, 0]);
        i.render(100_000_000, &mut out);
        assert_eq!(out[0], 0);
        i.render(150_000_000, &mut out);
        assert!(out[0] > 100 && out[0] < 155);
        i.render(400_000_000, &mut out);
        assert_eq!(out[0], 255);
    }
}
