//! Music analysis (TECHNICAL.md §14.2): FFT, 12 log-spaced bands with automatic gain, beat detection by spectral
//! flux, tempo, and a short history for the audio-delay setting. Two inputs: PCM (sources B/C, analysed here with a
//! Hann-windowed 1024-point FFT, 50% overlap) or the Visualizer's FFT bytes (source A, ~20 per second).
//! Everything is allocated in [`Audio::new`]; feeding allocates nothing.

use alloc::vec;
use alloc::vec::Vec;

use crate::math::{cos, ln, sin, sqrt_f32, TAU};

pub const FFT_N: usize = 1024;
pub const BANDS: usize = 12;
const HISTORY: usize = 64;
const FLUX_HISTORY: usize = 160;
/// Band levels below this share of the peak are treated as noise (the Visualizer's FFT is only 8 bits).
const NOISE_FLOOR: f32 = 0.12;

/// A radix-2 FFT of a fixed size, tables built once.
pub struct Fft {
    n: usize,
    cos: Vec<f32>,
    sin: Vec<f32>,
    rev: Vec<u16>,
}

impl Fft {
    pub fn new(n: usize) -> Fft {
        assert!(n.is_power_of_two() && n >= 4);
        let bits = n.trailing_zeros();
        let rev = (0..n).map(|i| ((i as u32).reverse_bits() >> (32 - bits)) as u16).collect();
        let cos = (0..n / 2).map(|k| crate::math::cos(TAU * k as f64 / n as f64) as f32).collect();
        let sin = (0..n / 2).map(|k| -crate::math::sin(TAU * k as f64 / n as f64) as f32).collect();
        Fft { n, cos, sin, rev }
    }

    /// In-place forward transform of (re, im), each `n` long.
    pub fn run(&self, re: &mut [f32], im: &mut [f32]) {
        let n = self.n;
        for i in 0..n {
            let j = self.rev[i] as usize;
            if j > i {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let mut len = 2;
        while len <= n {
            let step = n / len;
            for start in (0..n).step_by(len) {
                for k in 0..len / 2 {
                    let (wr, wi) = (self.cos[k * step], self.sin[k * step]);
                    let (a, b) = (start + k, start + k + len / 2);
                    let tr = re[b] * wr - im[b] * wi;
                    let ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
            len <<= 1;
        }
    }
}

/// Which part of the sound drives the lights (DESIGN.md §10.1 "Frequency range").
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Range {
    Full,
    BassOnly,
    Vocals,
}

#[derive(Clone, Copy, Debug)]
pub struct AudioConfig {
    pub sample_rate: u32,
    /// 0 = automatic gain, 1..10 = fixed gain
    pub sensitivity: u8,
    pub range: Range,
    /// beat threshold: mean + k x deviation of the recent flux, k x 10
    pub beat_k_x10: u8,
}

impl Default for AudioConfig {
    fn default() -> Self {
        AudioConfig { sample_rate: 48_000, sensitivity: 0, range: Range::Full, beat_k_x10: 15 }
    }
}

/// What the music effects see for one analysis step.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct MusicFrame {
    pub t_ms: u32,
    /// 0..1 per band, about 40 Hz .. 16 kHz
    pub bands: [f32; BANDS],
    pub bass: f32,
    pub mid: f32,
    pub treble: f32,
    /// level in dB (0 = full scale; -96 = silence)
    pub level_db: f32,
    /// a beat started in this step
    pub beat: bool,
    /// beats per minute, 0 until known
    pub bpm: f32,
    /// loudness 0..1 relative to the song itself (between its quiet floor and its peaks), for the effects that follow
    /// loudness: the absolute level is useless for that on the output mix, which Android normalises
    pub loud: f32,
}

pub struct Audio {
    cfg: AudioConfig,
    fft: Fft,
    window: Vec<f32>,
    pcm: Vec<f32>,
    pcm_fill: usize,
    re: Vec<f32>,
    im: Vec<f32>,
    mags: Vec<f32>,
    prev_log: Vec<f32>,
    band_bins: [(u16, u16); BANDS],
    bass_bins: u16,
    peaks: [f32; BANDS],
    /// what the lights show per band: fast rise, smooth fall (the raw 20 Hz Visualizer levels flicker)
    shown: [f32; BANDS],
    /// per band, the song's steady level (a floor that rises slowly and falls fast): levels are shown above it
    floors: [f32; BANDS],
    loud_peak: f32,
    loud_floor: f32,
    loud_shown: f32,
    flux: Vec<f32>,
    flux_at: usize,
    flux_n: usize,
    last_beat_ms: Option<u32>,
    intervals: [u32; 8],
    intervals_n: usize,
    recent_beats: [u32; 8],
    beats_n: usize,
    history: Vec<MusicFrame>,
    history_at: usize,
    history_n: usize,
    sum_sq: f32,
    hop_samples: u32,
    last_t_ms: Option<u32>,
}

impl Audio {
    pub fn new(cfg: AudioConfig) -> Audio {
        let n = FFT_N;
        let window = (0..n).map(|i| (0.5 - 0.5 * cos(TAU * i as f64 / n as f64)) as f32).collect();
        let rate = cfg.sample_rate.max(8000) as f32;
        let bin_hz = rate / n as f32;
        let mut band_bins = [(1u16, 2u16); BANDS];
        for (k, b) in band_bins.iter_mut().enumerate() {
            // 40 Hz .. 16 kHz, log-spaced: 40 * 400^(k/12)
            let f0 = 40.0 * crate::math::exp(ln(400.0) * k as f64 / BANDS as f64) as f32;
            let f1 = 40.0 * crate::math::exp(ln(400.0) * (k + 1) as f64 / BANDS as f64) as f32;
            let lo = ((f0 / bin_hz) as u16).max(1);
            let hi = ((f1 / bin_hz) as u16).clamp(lo + 1, (n / 2) as u16);
            *b = (lo, hi);
        }
        let bass_bins = ((200.0 / bin_hz) as u16).max(2);
        Audio {
            cfg,
            fft: Fft::new(n),
            window,
            pcm: vec![0.0; n],
            pcm_fill: 0,
            re: vec![0.0; n],
            im: vec![0.0; n],
            mags: vec![0.0; n / 2],
            prev_log: vec![0.0; n / 2],
            band_bins,
            bass_bins,
            peaks: [1e-3; BANDS],
            shown: [0.0; BANDS],
            floors: [0.0; BANDS],
            loud_peak: 1e-3,
            loud_floor: 0.0,
            loud_shown: 0.0,
            flux: vec![0.0; FLUX_HISTORY],
            flux_at: 0,
            flux_n: 0,
            last_beat_ms: None,
            intervals: [0; 8],
            intervals_n: 0,
            recent_beats: [0; 8],
            beats_n: 0,
            history: vec![MusicFrame::default(); HISTORY],
            history_at: 0,
            history_n: 0,
            sum_sq: 0.0,
            hop_samples: 0,
            last_t_ms: None,
        }
    }

    /// Mono 16-bit PCM; `t_ms` is the time of the first sample. Analyses every 512 new samples (50% overlap).
    /// Returns how many analysis steps ran.
    pub fn feed_pcm(&mut self, samples: &[i16], t_ms: u32) -> usize {
        let n = FFT_N;
        let hop = n / 2;
        let rate = self.cfg.sample_rate.max(8000);
        let mut steps = 0;
        for (k, &s) in samples.iter().enumerate() {
            let v = s as f32 / 32768.0;
            if self.pcm_fill < n {
                self.pcm[self.pcm_fill] = v;
                self.pcm_fill += 1;
            }
            self.sum_sq += v * v;
            self.hop_samples += 1;
            if self.pcm_fill == n {
                let mean_sq = self.sum_sq / self.hop_samples.max(1) as f32;
                for i in 0..n {
                    self.re[i] = self.pcm[i] * self.window[i];
                    self.im[i] = 0.0;
                }
                self.fft.run(&mut self.re, &mut self.im);
                for b in 0..n / 2 {
                    self.mags[b] = sqrt_f32(self.re[b] * self.re[b] + self.im[b] * self.im[b]) / (n as f32 / 4.0);
                }
                // the step's time: the middle of the window
                let end_ms = t_ms as u64 + (k as u64 + 1) * 1000 / rate as u64;
                let mid_ms = end_ms.saturating_sub((n as u64 / 2) * 1000 / rate as u64) as u32;
                self.step(mid_ms, db(mean_sq));
                steps += 1;
                self.pcm.copy_within(hop.., 0);
                self.pcm_fill = n - hop;
                self.sum_sq = 0.0;
                self.hop_samples = 0;
            }
        }
        steps
    }

    /// The Visualizer's FFT (`Visualizer.getFft`, capture size 1024): `[0]` DC, `[1]` Nyquist, then (re, im) of
    /// bins 1..511 as signed bytes.
    pub fn feed_visualizer(&mut self, fft: &[u8], t_ms: u32) {
        let half = (fft.len() / 2).min(self.mags.len());
        let mut energy = 0.0;
        for b in 0..half {
            let (re, im) = if b == 0 { (fft[0] as i8 as f32, 0.0) } else { (fft[2 * b] as i8 as f32, fft[2 * b + 1] as i8 as f32) };
            let m = sqrt_f32(re * re + im * im) / 128.0;
            self.mags[b] = m;
            energy += m * m;
        }
        for b in half..self.mags.len() {
            self.mags[b] = 0.0;
        }
        self.step(t_ms, db(energy / 4.0));
    }

    fn step(&mut self, t_ms: u32, level_db: f32) {
        let dt = self.last_t_ms.map_or(10, |l| t_ms.saturating_sub(l).clamp(1, 200)) as f32 / 1000.0;
        self.last_t_ms = Some(t_ms);
        let mut f = MusicFrame { t_ms, level_db, ..MusicFrame::default() };

        // bands, auto gain: each band is shown between its floor (the song's steady level: rises over 2.5 s, falls in
        // 0.15 s) and its peak (catches up in 50 ms, lets go over 3 s), so steady sound reads low and every hit or
        // change stands out; then a small noise floor and a display envelope (40 ms rise, 250 ms fall).
        // History: 50 ms peak attack with no envelope flickered wildly; a plain v/peak ratio with a 0.3 s
        // attack sat near 1 for steady music, so most effects looked still and only Pulse (beats) moved.
        let attack = 1.0 - crate::math::exp(-(dt / 0.05) as f64) as f32;
        let release = 1.0 - crate::math::exp(-(dt / 3.0) as f64) as f32;
        let floor_up = 1.0 - crate::math::exp(-(dt / 2.5) as f64) as f32;
        let floor_down = 1.0 - crate::math::exp(-(dt / 0.15) as f64) as f32;
        let rise = 1.0 - crate::math::exp(-(dt / 0.04) as f64) as f32;
        let fall = 1.0 - crate::math::exp(-(dt / 0.25) as f64) as f32;
        let silent = level_db < -70.0;
        let mut energy = 0.0f32;
        for (k, &(lo, hi)) in self.band_bins.iter().enumerate() {
            let mut sum = 0.0;
            for b in lo..hi {
                sum += self.mags[b as usize];
            }
            let v = sum / (hi - lo) as f32;
            let weight = match (self.cfg.range, k) {
                (Range::Full, _) => 1.0,
                (Range::BassOnly, k) if k <= 3 => 1.0,
                (Range::Vocals, k) if (4..=8).contains(&k) => 1.0,
                _ => 0.15,
            };
            energy += v;
            let level = if self.cfg.sensitivity == 0 {
                let p = &mut self.peaks[k];
                *p += (v - *p) * if v > *p { attack } else { release };
                *p = p.max(2e-4);
                let fl = &mut self.floors[k];
                *fl += (v - *fl) * if v > *fl { floor_up } else { floor_down };
                if silent { 0.0 } else { follow(v, *fl, *p) }
            } else {
                (v * self.cfg.sensitivity as f32 * 40.0).min(1.0)
            };
            let level = ((level - NOISE_FLOOR) / (1.0 - NOISE_FLOOR)).max(0.0) * weight;
            let s = &mut self.shown[k];
            *s += (level - *s) * if level > *s { rise } else { fall };
            f.bands[k] = *s;
        }
        // loudness relative to the song: the same floor-to-peak follower on the total band energy
        self.loud_peak += (energy - self.loud_peak) * if energy > self.loud_peak { attack } else { release };
        self.loud_peak = self.loud_peak.max(2e-4);
        self.loud_floor += (energy - self.loud_floor) * if energy > self.loud_floor { floor_up } else { floor_down };
        let loud = if silent { 0.0 } else { follow(energy, self.loud_floor, self.loud_peak) };
        self.loud_shown += (loud - self.loud_shown) * if loud > self.loud_shown { rise } else { fall };
        f.loud = self.loud_shown;
        let avg = |r: core::ops::Range<usize>| f.bands[r.clone()].iter().sum::<f32>() / r.len() as f32;
        f.bass = avg(0..4);
        f.mid = avg(4..9);
        f.treble = avg(9..12);

        // spectral flux on the bass bins, against mean + k * deviation of the last ~1.5 s
        let mut flux = 0.0;
        for b in 1..self.bass_bins as usize {
            let lg = ln(1.0 + 100.0 * self.mags[b] as f64) as f32;
            let d = lg - self.prev_log[b];
            if d > 0.0 {
                flux += d;
            }
            self.prev_log[b] = lg;
        }
        for b in self.bass_bins as usize..self.mags.len() {
            self.prev_log[b] = 0.0;
        }
        let (mean, dev) = self.flux_stats();
        self.flux[self.flux_at] = flux;
        self.flux_at = (self.flux_at + 1) % FLUX_HISTORY;
        self.flux_n = (self.flux_n + 1).min(FLUX_HISTORY);
        let threshold = mean + self.cfg.beat_k_x10 as f32 / 10.0 * dev;
        let refractory = self.last_beat_ms.is_some_and(|l| t_ms.saturating_sub(l) < 200);
        if !silent && self.flux_n > 8 && flux > threshold && flux > 0.5 && !refractory {
            f.beat = true;
            if let Some(l) = self.last_beat_ms {
                let iv = t_ms.saturating_sub(l);
                if (250..=1500).contains(&iv) {
                    self.intervals[self.intervals_n % 8] = iv;
                    self.intervals_n += 1;
                }
            }
            self.last_beat_ms = Some(t_ms);
            self.recent_beats[self.beats_n % 8] = t_ms;
            self.beats_n += 1;
        }
        f.bpm = self.bpm();
        self.history[self.history_at] = f;
        self.history_at = (self.history_at + 1) % HISTORY;
        self.history_n = (self.history_n + 1).min(HISTORY);
    }

    fn flux_stats(&self) -> (f32, f32) {
        let n = self.flux_n.max(1);
        let vals = &self.flux[..self.flux_n.min(FLUX_HISTORY)];
        let mean = vals.iter().sum::<f32>() / n as f32;
        let var = vals.iter().map(|v| (v - mean) * (v - mean)).sum::<f32>() / n as f32;
        (mean, sqrt_f32(var))
    }

    /// Median of the last beat intervals as beats per minute (display only).
    pub fn bpm(&self) -> f32 {
        let k = self.intervals_n.min(8);
        if k < 3 {
            return 0.0;
        }
        let mut v = [0u32; 8];
        v[..k].copy_from_slice(&self.intervals[..k]);
        v[..k].sort_unstable();
        60_000.0 / v[k / 2] as f32
    }

    /// The newest analysis step at or before `t_ms` (for the audio-delay setting: ask for now - delay).
    pub fn frame_at(&self, t_ms: u32) -> Option<MusicFrame> {
        (1..=self.history_n)
            .map(|back| self.history[(self.history_at + HISTORY - back) % HISTORY])
            .find(|f| f.t_ms <= t_ms)
    }

    pub fn latest(&self) -> Option<MusicFrame> {
        if self.history_n == 0 { None } else { Some(self.history[(self.history_at + HISTORY - 1) % HISTORY]) }
    }

    /// The last up to 8 beat times, oldest first, into `out`; returns how many.
    pub fn recent_beats(&self, out: &mut [u32; 8]) -> usize {
        let k = self.beats_n.min(8);
        for (i, slot) in out.iter_mut().enumerate().take(k) {
            *slot = self.recent_beats[(self.beats_n - k + i) % 8];
        }
        k
    }

    pub fn last_beat_ms(&self) -> Option<u32> {
        self.last_beat_ms
    }
}

/// Where `v` sits between the song's floor and its peak, 0..1 (a band with no dynamics range reads 0).
fn follow(v: f32, floor: f32, peak: f32) -> f32 {
    let range = peak - floor;
    if range < peak * 0.05 {
        return 0.0;
    }
    ((v - floor) / range).clamp(0.0, 1.0)
}

fn db(mean_sq: f32) -> f32 {
    if mean_sq <= 1e-10 {
        -96.0
    } else {
        (10.0 * ln(mean_sq as f64) / ln(10.0)).max(-96.0) as f32
    }
}

/// A test tone generator for "Test audio input" and the host tests: `freq` Hz sine at `amp` (0..1), mono.
pub fn tone(freq: f32, amp: f32, rate: u32, start: usize, out: &mut [i16]) {
    for (i, s) in out.iter_mut().enumerate() {
        let t = (start + i) as f64 / rate as f64;
        *s = (sin(TAU * freq as f64 * t) as f32 * amp * 32767.0) as i16;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fft_finds_a_tone_in_its_bin() {
        let fft = Fft::new(1024);
        let mut re: Vec<f32> = (0..1024).map(|i| crate::math::sin(TAU * 64.0 * i as f64 / 1024.0) as f32).collect();
        let mut im = vec![0.0; 1024];
        fft.run(&mut re, &mut im);
        let mag = |b: usize| (re[b] * re[b] + im[b] * im[b]).sqrt();
        assert!((mag(64) - 512.0).abs() < 0.01);
        assert!(mag(63) < 0.01 && mag(65) < 0.01);
    }

    #[test]
    fn a_1khz_tone_lights_its_band_and_lifts_the_level() {
        let mut a = Audio::new(AudioConfig::default());
        let mut buf = vec![0i16; 4800];
        a.feed_pcm(&buf, 0);
        let quiet = a.latest().unwrap().level_db;
        tone(1000.0, 0.5, 48_000, 0, &mut buf);
        a.feed_pcm(&buf, 100);
        let f = a.latest().unwrap();
        let top = (0..BANDS).max_by(|&x, &y| f.bands[x].partial_cmp(&f.bands[y]).unwrap()).unwrap();
        assert_eq!(top, 6, "1 kHz is in band 6 (40 * 400^(6/12) = 800 .. 1340 Hz)");
        assert!(f.level_db - quiet > 20.0);
    }
}
