//! Audio tests (TECHNICAL.md §14.2): beats found within ±50 ms on generated music, no beats in
//! silence, the right tempo, and music effects that render. The clips are generated here (kick drums at known times
//! over chords, hi-hats and noise), so there's nothing to download or license.

use std::f64::consts::TAU;

use tvambient_core::audio::{Audio, AudioConfig, Fft, FFT_N};
use tvambient_core::effects::music::{self, MusicEffect, MusicParams, MUSIC_EFFECTS};
use tvambient_core::effects::FlashCap;
use tvambient_core::layout::{Layout, LayoutConfig};

const RATE: u32 = 48_000;

struct Clip {
    pcm: Vec<i16>,
    beats_ms: Vec<u32>,
    music_from_ms: u32,
    music_to_ms: u32,
}

/// `silence_s` of silence, `music_s` of music at `bpm`, `silence_s` again.
fn clip(bpm: f64, music_s: f64, silence_s: f64, seed: u32) -> Clip {
    let total = ((2.0 * silence_s + music_s) * RATE as f64) as usize;
    let start = (silence_s * RATE as f64) as usize;
    let end = start + (music_s * RATE as f64) as usize;
    let beat = 60.0 / bpm;
    let mut pcm = vec![0i16; total];
    let mut rng = seed;
    let mut noise = || {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        (rng as f64 / u32::MAX as f64) * 2.0 - 1.0
    };
    // every kick that's played (the check leaves the last 300 ms out of the must-find list)
    let mut beats_ms = Vec::new();
    let mut b = 0.0;
    while start as f64 / RATE as f64 + b < end as f64 / RATE as f64 {
        beats_ms.push(((start as f64 / RATE as f64 + b) * 1000.0) as u32);
        b += beat;
    }
    for (i, s) in pcm.iter_mut().enumerate().take(end).skip(start) {
        let t = (i - start) as f64 / RATE as f64;
        let into_beat = t % beat;
        let into_half = (t + beat / 2.0) % beat;
        // kick: 150 -> 45 Hz sweep, 120 ms decay
        let f = 45.0 + 105.0 * (-into_beat / 0.04).exp();
        let kick = 0.8 * (-into_beat / 0.12).exp() * (TAU * f * into_beat).sin();
        // chords with a slow tremolo
        let chord = 0.08 * (1.0 + 0.3 * (TAU * 0.5 * t).sin()) * ((TAU * 220.0 * t).sin() + (TAU * 277.2 * t).sin() + (TAU * 329.6 * t).sin());
        // off-beat hi-hat
        let hat = 0.15 * (-into_half / 0.03).exp() * noise();
        let v = kick + chord + hat + 0.01 * noise();
        *s = (v.clamp(-1.0, 1.0) * 32767.0) as i16;
    }
    Clip { pcm, beats_ms, music_from_ms: (start as u64 * 1000 / RATE as u64) as u32, music_to_ms: (end as u64 * 1000 / RATE as u64) as u32 }
}

struct Run {
    detected: Vec<u32>,
    bpm: f32,
}

fn run_pcm(c: &Clip) -> Run {
    let mut a = Audio::new(AudioConfig::default());
    let mut detected = Vec::new();
    let chunk = 480; // 10 ms, as AudioRecord delivers it
    for (k, part) in c.pcm.chunks(chunk).enumerate() {
        let t = (k * chunk) as u64 * 1000 / RATE as u64;
        a.feed_pcm(part, t as u32);
        // collect beats from the history, each once
        let mut beats = [0u32; 8];
        let n = a.recent_beats(&mut beats);
        for &b in &beats[..n] {
            if !detected.contains(&b) {
                detected.push(b);
            }
        }
    }
    Run { detected, bpm: a.bpm() }
}

/// The Visualizer path: FFT bytes as `Visualizer.getFft` gives them (capture size 1024), 20 per second.
fn run_visualizer(c: &Clip) -> Run {
    let mut a = Audio::new(AudioConfig::default());
    let fft = Fft::new(FFT_N);
    let mut detected = Vec::new();
    let step = RATE as usize / 20;
    let mut i = FFT_N;
    while i <= c.pcm.len() {
        let mut re: Vec<f32> = c.pcm[i - FFT_N..i].iter().map(|&s| s as f32 / 32768.0).collect();
        let mut im = vec![0.0; FFT_N];
        fft.run(&mut re, &mut im);
        let mut bytes = vec![0u8; FFT_N];
        let q = |v: f32| ((v / 8.0).clamp(-127.0, 127.0) as i8) as u8;
        bytes[0] = q(re[0]);
        bytes[1] = q(re[FFT_N / 2]);
        for b in 1..FFT_N / 2 {
            bytes[2 * b] = q(re[b]);
            bytes[2 * b + 1] = q(im[b]);
        }
        let t = ((i - FFT_N / 2) as u64 * 1000 / RATE as u64) as u32;
        a.feed_visualizer(&bytes, t);
        if a.latest().is_some_and(|f| f.beat) {
            detected.push(t);
        }
        i += step;
    }
    Run { detected, bpm: a.bpm() }
}

/// Every beat after a 1.5 s warm-up found within `tol` ms, nothing in the silences, few extras.
fn check(name: &str, c: &Clip, r: &Run, tol: u32) {
    let warm = c.music_from_ms + 1500;
    let must = |b: u32| b >= warm && b + 300 <= c.music_to_ms;
    let mut missed = Vec::new();
    for &b in c.beats_ms.iter().filter(|&&b| must(b)) {
        if !r.detected.iter().any(|&d| d.abs_diff(b) <= tol) {
            missed.push(b);
        }
    }
    assert!(missed.is_empty(), "{name}: missed beats at {missed:?} (detected {:?})", r.detected);
    // a step is timestamped at the middle of its window, so the first kick may be placed up to `tol` early
    let in_silence: Vec<_> = r.detected.iter().filter(|&&d| d + tol < c.music_from_ms || d > c.music_to_ms + 300).collect();
    assert!(in_silence.is_empty(), "{name}: beats in silence at {in_silence:?}");
    let extra = r.detected.iter().filter(|&&d| d >= warm && !c.beats_ms.iter().any(|&b| d.abs_diff(b) <= tol)).count();
    let expected = c.beats_ms.iter().filter(|&&b| must(b)).count();
    assert!(extra * 20 <= expected, "{name}: {extra} false beats for {expected} real ones");
}

#[test]
fn pcm_beats_within_50_ms_at_three_tempos() {
    for (bpm, seed) in [(90.0, 1), (120.0, 2), (140.0, 3)] {
        let c = clip(bpm, 10.0, 2.0, seed);
        let r = run_pcm(&c);
        check(&format!("PCM {bpm} BPM"), &c, &r, 50);
        assert!((r.bpm - bpm as f32).abs() <= 3.0, "PCM {bpm} BPM: tempo {}", r.bpm);
    }
}

#[test]
fn visualizer_beats_within_one_capture() {
    // 20 captures per second: a beat can't be placed better than one capture (50 ms) plus half the window.
    for (bpm, seed) in [(90.0, 4), (120.0, 5)] {
        let c = clip(bpm, 10.0, 2.0, seed);
        let r = run_visualizer(&c);
        check(&format!("Visualizer {bpm} BPM"), &c, &r, 75);
    }
}

#[test]
fn music_effects_render_and_strobe_is_capped() {
    let layout = Layout::new(&LayoutConfig::default());
    let c = clip(140.0, 6.0, 0.5, 9);
    let mut a = Audio::new(AudioConfig::default());
    let n = layout.len() * 3;
    for (fx, name, flashes) in MUSIC_EFFECTS {
        let (mut frame, mut shown) = (vec![0u8; n], vec![0u8; n]);
        let mut cap = FlashCap::default();
        let mut onsets = Vec::new();
        let mut prev_mean = 0;
        let mut lit = false;
        for (k, part) in c.pcm.chunks(800).enumerate() {
            let now = (k * 800) as u32 * 1000 / RATE;
            a.feed_pcm(part, now);
            let f = a.latest().unwrap_or_default();
            let mut beats = [0u32; 8];
            let nb = a.recent_beats(&mut beats);
            assert!(music::render(fx, now, &f, &beats[..nb], &MusicParams::default(), &layout, &mut frame));
            if flashes {
                cap.apply(now, &shown, &mut frame);
            }
            shown.copy_from_slice(&frame);
            let m = tvambient_core::effects::mean(&shown);
            if m >= prev_mean + tvambient_core::effects::FLASH_RISE {
                onsets.push(now);
            }
            prev_mean = m;
            lit |= shown.iter().any(|&v| v > 40);
        }
        assert!(lit, "{name} never lit up during music");
        if flashes {
            let worst = onsets.iter().map(|&t| onsets.iter().filter(|&&u| u >= t && u < t + 1000).count()).max().unwrap_or(0);
            assert!(worst <= 3, "{name}: {worst} flashes in a second");
        }
    }
    assert_eq!(MusicEffect::Spectrum, MUSIC_EFFECTS[0].0);
}

/// Music presets: every music effect renders on the reference layout without panicking, deterministically,
/// and lights up.
#[test]
fn music_presets_render() {
    use tvambient_core::audio::{MusicFrame, BANDS};
    use tvambient_core::effects::music::{render, MusicParams, MUSIC_EFFECTS};
    use tvambient_core::layout::{Layout, LayoutConfig};

    let layout = Layout::new(&LayoutConfig::default());
    let mut f = MusicFrame::default();
    for k in 0..BANDS {
        f.bands[k] = k as f32 / BANDS as f32;
    }
    f.bass = 0.8;
    f.mid = 0.5;
    f.treble = 0.3;
    f.level_db = -20.0;
    f.loud = 0.8;
    let beats = [1000u32, 1500, 2000];
    let p = MusicParams::default();
    let mut a = vec![0u8; layout.len() * 3];
    let mut b = vec![0u8; layout.len() * 3];
    for &(fx, name, flashes) in MUSIC_EFFECTS.iter() {
        assert!(render(fx, 2100, &f, &beats, &p, &layout, &mut a), "{name}");
        assert!(render(fx, 2100, &f, &beats, &p, &layout, &mut b), "{name}");
        assert_eq!(a, b, "{name} is deterministic");
        // (Strobe beat only flashes in the 60 ms after a strong beat)
        assert!(flashes || a.iter().any(|&c| c > 40), "{name}: lights up");
    }
}

/// Over steady music with kicks, every
/// music effect's average brightness must swing clearly, both from PCM (sources B/C) and from the Visualizer's
/// normalised 8-bit FFT (source A, what the reference TV uses).
#[test]
fn music_effects_move_with_the_song() {
    use tvambient_core::audio::BANDS;
    let c = clip(120.0, 10.0, 0.5, 7);
    let layout = Layout::new(&LayoutConfig::default());
    let p = MusicParams::default();
    let mut out = vec![0u8; layout.len() * 3];

    for visualizer in [false, true] {
        let mut a = Audio::new(AudioConfig::default());
        let mut fft = Fft::new(FFT_N);
        let mut re = vec![0f32; FFT_N];
        let mut im = vec![0f32; FFT_N];
        let mut bytes = vec![0u8; FFT_N];
        let mut lo = [255f32; 13];
        let mut hi = [0f32; 13];
        let mut band_lo = [1f32; BANDS];
        let mut band_hi = [0f32; BANDS];
        let chunk = 480; // 10 ms
        let mut i = 0;
        while i + FFT_N <= c.pcm.len() {
            let t_ms = (i as u64 * 1000 / RATE as u64) as u32;
            if visualizer {
                // like Visualizer.getFft at 20 Hz: a 1024-sample FFT, normalised so the largest component is ±127
                if i % (RATE as usize / 20) < chunk {
                    for k in 0..FFT_N {
                        re[k] = c.pcm[i + k] as f32 / 32768.0;
                        im[k] = 0.0;
                    }
                    fft.run(&mut re, &mut im);
                    let max = re.iter().chain(im.iter()).fold(1e-9f32, |m, v| m.max(v.abs()));
                    bytes[0] = (re[0] / max * 127.0) as i8 as u8;
                    bytes[1] = (re[FFT_N / 2] / max * 127.0) as i8 as u8;
                    for b in 1..FFT_N / 2 {
                        bytes[2 * b] = (re[b] / max * 127.0) as i8 as u8;
                        bytes[2 * b + 1] = (im[b] / max * 127.0) as i8 as u8;
                    }
                    a.feed_visualizer(&bytes, t_ms);
                }
            } else {
                a.feed_pcm(&c.pcm[i..i + chunk], t_ms);
            }
            i += chunk;
            // judge the middle of the music, after 3 s to settle
            if t_ms < c.music_from_ms + 3000 || t_ms > c.music_to_ms - 1000 {
                continue;
            }
            let Some(f) = a.latest() else { continue };
            for k in 0..BANDS {
                band_lo[k] = band_lo[k].min(f.bands[k]);
                band_hi[k] = band_hi[k].max(f.bands[k]);
            }
            let mut beats = [0u32; 8];
            let nb = a.recent_beats(&mut beats);
            for (e, &(fx, _, _)) in MUSIC_EFFECTS.iter().enumerate() {
                music::render(fx, t_ms, &f, &beats[..nb], &p, &layout, &mut out);
                let mean = out.iter().map(|&v| v as f32).sum::<f32>() / out.len() as f32;
                lo[e] = lo[e].min(mean);
                hi[e] = hi[e].max(mean);
            }
        }
        let path = if visualizer { "visualizer" } else { "pcm" };
        assert!(band_hi[0] - band_lo[0] > 0.5, "{path}: the bass band swings with the kicks ({} .. {})", band_lo[0], band_hi[0]);
        for (e, &(_, name, _)) in MUSIC_EFFECTS.iter().enumerate() {
            // the punch gamma keeps the darks deep, so the swing is in fewer, brighter LEDs
            let need = 3.0;
            assert!(hi[e] - lo[e] > need && hi[e] > lo[e] * 1.5, "{path}: {name} moves with the song (mean brightness {:.1} .. {:.1})", lo[e], hi[e]);
        }
    }
}
