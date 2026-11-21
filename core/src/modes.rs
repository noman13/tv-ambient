//! Cross-fades between outputs (TECHNICAL.md §13): when the mode, effect or colour changes, the old and new outputs
//! are both rendered and blended over 300 ms, so there's never a black flash.

/// Default fade length.
pub const FADE_NS: u64 = 300_000_000;

#[derive(Clone, Copy, Debug, Default)]
pub struct CrossFade {
    start_ns: u64,
    len_ns: u64,
    active: bool,
}

impl CrossFade {
    pub fn start(&mut self, now_ns: u64, len_ns: u64) {
        self.start_ns = now_ns;
        self.len_ns = len_ns.max(1);
        self.active = true;
    }

    /// Whether the old output still needs rendering.
    pub fn active(&self, now_ns: u64) -> bool {
        self.active && now_ns < self.start_ns + self.len_ns
    }

    /// Blends `old` into `new` in place (new = result). Ends the fade when it's complete.
    pub fn blend(&mut self, now_ns: u64, old: &[u8], new: &mut [u8]) {
        if !self.active(now_ns) {
            self.active = false;
            return;
        }
        let t = ((now_ns - self.start_ns) * 256 / self.len_ns) as u32;
        for (n, &o) in new.iter_mut().zip(old) {
            *n = ((o as u32 * (256 - t) + *n as u32 * t + 128) >> 8) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fades_without_going_dark() {
        let mut f = CrossFade::default();
        f.start(0, FADE_NS);
        let mut new = [0u8, 200];
        f.blend(150_000_000, &[200, 0], &mut new);
        assert_eq!(new, [100, 100]);
        let mut new = [0u8, 200];
        f.blend(FADE_NS, &[200, 0], &mut new);
        assert_eq!(new, [0, 200]);
        assert!(!f.active(FADE_NS));
    }
}
