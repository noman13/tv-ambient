//! Capture rate by content (TECHNICAL.md §7, E4/E4b): the rate is chosen from the video's size and frame rate and
//! from how the player shows it, then capped by the measured capture time so capture never eats the frame budget.
//!
//! The table values come from measurements on the reference TV; a wider sweep could refine them.

/// How the player puts video on screen.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum VideoPath {
    /// A SurfaceView on the hardware video plane: each capture makes the vendor service copy it (~80 ms on the reference TV).
    HardwarePlane,
    /// Drawn into the app's own window (SmartTube on the reference TV): cheap captures (~12 ms).
    Window,
    Unknown,
}

#[derive(Clone, Copy, Debug)]
pub struct Content {
    pub width: u16,
    pub height: u16,
    /// frames per second x 1000 (23976 for 23.976)
    pub fps_milli: u32,
    pub path: VideoPath,
}

#[derive(Clone, Copy, Debug)]
pub struct RateTable {
    /// [up to 1080p, up to 1440p, 4K] for content up to 30 fps
    pub window_low_fps: [u8; 3],
    /// the same for content above 30 fps
    pub window_high_fps: [u8; 3],
    pub hardware_plane: [u8; 3],
    /// when nothing is known (no video, Live TV, UI)
    pub unknown: u8,
    pub min: u8,
    pub max: u8,
    /// capture may use at most this % of the time between captures
    pub max_duty_pct: u8,
}

impl Default for RateTable {
    /// Measured on the reference TV: capture is cheap in a player's own window, ~80 ms per capture on the hardware
    /// video plane.
    fn default() -> Self {
        RateTable {
            window_low_fps: [20, 20, 20],
            window_high_fps: [20, 15, 10],
            hardware_plane: [10, 10, 5],
            unknown: 15,
            min: 5,
            max: 30,
            max_duty_pct: 50,
        }
    }
}

/// Captures per second for `c`, capped so that `capture_ms` (the helper's own measured duration, if known) uses at
/// most `max_duty_pct` of each capture interval.
pub fn choose(c: Option<&Content>, capture_ms: Option<u32>, t: &RateTable) -> u8 {
    let base = match c {
        None => t.unknown,
        Some(c) => {
            let size = if c.height <= 1088 { 0 } else if c.height <= 1600 { 1 } else { 2 };
            match c.path {
                VideoPath::HardwarePlane => t.hardware_plane[size],
                VideoPath::Window if c.fps_milli > 30_500 => t.window_high_fps[size],
                VideoPath::Window => t.window_low_fps[size],
                VideoPath::Unknown => t.unknown,
            }
        }
    };
    let mut rate = base.clamp(t.min, t.max);
    if let Some(ms) = capture_ms.filter(|&ms| ms > 0) {
        let cap = (t.max_duty_pct as u32 * 10 / ms).min(255) as u8; // duty% * 1000 ms / 100 / ms
        rate = rate.min(cap.max(t.min));
    }
    rate
}

/// What the adaptive rate watches, sampled every couple of seconds by the helper.
#[derive(Clone, Copy, Debug, Default)]
pub struct Pressure {
    /// video frames later than 1.5 frame times since the last step (SurfaceFlinger timing; 0 when unknown)
    pub late_frames: u16,
    /// average capture duration, ms
    pub capture_ms: u16,
    /// whole-device CPU use, % (100 = all cores busy)
    pub system_cpu_pct: u8,
    /// memory available to apps, MB
    pub mem_avail_mb: u16,
    /// the playing video's frame rate ×10 from SurfaceFlinger's timing; 0 when no video is found
    pub video_fps_x10: u16,
}

/// The adaptive capture rate: steps **down** at once under pressure (late video frames, capture over half its time,
/// a busy CPU or low memory), and back **up** slowly once calm, between [`RateTable::min`] and `ceiling` (the user's
/// most, or the content's rate). Deterministic and allocation-free; the helper calls [`Adaptive::step`].
#[derive(Clone, Copy, Debug)]
pub struct Adaptive {
    rate: f32,
    calm_ms: u32,
}

/// Pressure thresholds: above any of these the rate drops by 20 %.
pub const BUSY_CPU_PCT: u8 = 85;
pub const LOW_MEM_MB: u16 = 120;
/// Calm enough to go up: everything under these for [`CALM_MS`].
pub const CALM_CPU_PCT: u8 = 70;
pub const CALM_MS: u32 = 10_000;
/// After a step up, the next one comes this much sooner (one per 5 s while it stays calm).
pub const STEP_UP_MS: u32 = 5_000;

impl Adaptive {
    pub fn new(start: u8) -> Adaptive {
        Adaptive { rate: start as f32, calm_ms: 0 }
    }

    pub fn rate(&self) -> u8 {
        self.rate as u8
    }

    /// One step: `dt_ms` since the last one; returns the new rate (captures per second).
    pub fn step(&mut self, p: &Pressure, dt_ms: u32, ceiling: u8, t: &RateTable) -> u8 {
        // the content's ceiling: never faster than the video's own frames (a capture between two frames sees the
        // same picture), and with no video found (the UI, Live TV) the table's rate for unknown content
        let content = if p.video_fps_x10 > 0 { ((p.video_fps_x10 as u32 + 9) / 10) as u8 } else { t.unknown };
        let ceiling = ceiling.min(content).clamp(t.min, t.max) as f32;
        // capture may use at most max_duty_pct of the time between captures, whatever else happens (E4)
        let duty_cap = if p.capture_ms > 0 {
            (t.max_duty_pct as f32 * 10.0 / p.capture_ms as f32).max(t.min as f32)
        } else {
            ceiling
        };
        let duty_pct = p.capture_ms as f32 * self.rate / 10.0;
        let pressed = p.late_frames > 0 || duty_pct > t.max_duty_pct as f32 || p.system_cpu_pct > BUSY_CPU_PCT
            || (p.mem_avail_mb > 0 && p.mem_avail_mb < LOW_MEM_MB);
        if pressed {
            self.rate *= 0.8;
            self.calm_ms = 0;
        } else {
            self.calm_ms = self.calm_ms.saturating_add(dt_ms);
            let calm = p.system_cpu_pct < CALM_CPU_PCT && duty_pct < t.max_duty_pct as f32 * 0.7;
            if !calm {
                self.calm_ms = 0;
            } else if self.calm_ms >= CALM_MS {
                self.rate += 1.0;
                self.calm_ms = CALM_MS - STEP_UP_MS;
            }
        }
        self.rate = self.rate.min(ceiling).min(duty_cap).max(t.min as f32);
        self.rate as u8
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rates_follow_content_and_capture_time() {
        let t = RateTable::default();
        let c = |w, h, fps, path| Content { width: w, height: h, fps_milli: fps, path };
        assert_eq!(choose(Some(&c(1920, 1080, 60_000, VideoPath::Window)), None, &t), 20);
        assert_eq!(choose(Some(&c(3840, 2160, 60_000, VideoPath::Window)), None, &t), 10);
        assert_eq!(choose(Some(&c(1920, 1080, 23_976, VideoPath::HardwarePlane)), Some(80), &t), 6);
        assert_eq!(choose(Some(&c(3840, 2160, 25_000, VideoPath::HardwarePlane)), Some(200), &t), 5);
        assert_eq!(choose(None, Some(12), &t), 15);
    }

    #[test]
    fn adaptive_drops_under_pressure_and_recovers_slowly() {
        let t = RateTable::default();
        let mut a = Adaptive::new(15);
        let calm = Pressure { late_frames: 0, capture_ms: 8, system_cpu_pct: 30, mem_avail_mb: 600, video_fps_x10: 600 };
        // late frames: down 20 % at once, again on the next step
        assert_eq!(a.step(&Pressure { late_frames: 3, ..calm }, 2000, 30, &t), 12);
        assert_eq!(a.step(&Pressure { late_frames: 1, ..calm }, 2000, 30, &t), 9);
        // a busy CPU and low memory count as pressure too
        assert!(a.step(&Pressure { system_cpu_pct: 95, ..calm }, 2000, 30, &t) < 9);
        let low = a.rate();
        assert!(a.step(&Pressure { mem_avail_mb: 80, ..calm }, 2000, 30, &t) <= low);
        // calm: nothing for the first 10 s, then +1 every 5 s
        let r0 = a.rate();
        for _ in 0..4 {
            assert_eq!(a.step(&calm, 2000, 30, &t), r0);
        }
        assert_eq!(a.step(&calm, 2000, 30, &t), r0 + 1);
        a.step(&calm, 2000, 30, &t);
        a.step(&calm, 2000, 30, &t);
        assert_eq!(a.step(&calm, 2000, 30, &t), r0 + 2);
        // never below the minimum, never above the ceiling or the duty limit
        let mut b = Adaptive::new(30);
        for _ in 0..40 {
            b.step(&Pressure { late_frames: 5, ..calm }, 2000, 30, &t);
        }
        assert_eq!(b.rate(), t.min);
        let mut c = Adaptive::new(5);
        for _ in 0..200 {
            c.step(&calm, 2000, 20, &t);
        }
        assert_eq!(c.rate(), 20);
        let mut d = Adaptive::new(30);
        // 80 ms captures (the hardware plane): half the time allows 6/s
        assert!(d.step(&Pressure { capture_ms: 80, ..calm }, 2000, 30, &t) <= 6);
        // the content's ceiling: a 24 fps video caps it at 24, no video at the unknown-content rate (15)
        let mut e = Adaptive::new(5);
        for _ in 0..200 {
            e.step(&Pressure { video_fps_x10: 240, ..calm }, 2000, 30, &t);
        }
        assert_eq!(e.rate(), 24);
        for _ in 0..3 {
            e.step(&Pressure { video_fps_x10: 0, ..calm }, 2000, 30, &t);
        }
        assert_eq!(e.rate(), t.unknown);
    }
}
