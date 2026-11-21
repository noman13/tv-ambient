//! Byte layouts for settings passed over JNI as `byte[]` (TECHNICAL.md §4.4). Each starts with a version byte;
//! integers are little-endian. Kotlin builds the same bytes (`app/.../NativeConfig.kt`).

use crate::analyze::{AnalyzerConfig, Mode};
use crate::audio::{AudioConfig, Range};
use crate::color::PictureConfig;
use crate::effects::music::{MusicEffect, MusicParams, Palette};
use crate::effects::Params;
use crate::layout::{LayoutConfig, StartCorner};

pub const VERSION: u8 = 1;

fn u16_at(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes([b[i], b[i + 1]])
}

/// Layout: version, top, right, bottom, left, gap (u16 each), start, ccw, offset (u16), depth tb, depth sides.
pub const LAYOUT_LEN: usize = 17;

pub fn layout(b: &[u8]) -> Option<LayoutConfig> {
    if b.len() < LAYOUT_LEN || b[0] != VERSION {
        return None;
    }
    Some(LayoutConfig {
        top: u16_at(b, 1),
        right: u16_at(b, 3),
        bottom: u16_at(b, 5),
        left: u16_at(b, 7),
        bottom_gap: u16_at(b, 9),
        start: StartCorner::from_u8(b[11]),
        counter_clockwise: b[12] != 0,
        offset: u16_at(b, 13),
        depth_top_bottom_pct: b[15],
        depth_sides_pct: b[16],
    })
}

pub fn write_layout(c: &LayoutConfig, out: &mut [u8; LAYOUT_LEN]) {
    out[0] = VERSION;
    for (k, v) in [c.top, c.right, c.bottom, c.left, c.bottom_gap].iter().enumerate() {
        out[1 + 2 * k..3 + 2 * k].copy_from_slice(&v.to_le_bytes());
    }
    out[11] = c.start as u8;
    out[12] = c.counter_clockwise as u8;
    out[13..15].copy_from_slice(&c.offset.to_le_bytes());
    out[15] = c.depth_top_bottom_pct;
    out[16] = c.depth_sides_pct;
}

/// Analyzer: layout (17 bytes) then mode (0 = own, 1 = reference-compatible), bars, black luma, bar line %, hysteresis, sample
/// step, dark reach %.
pub const ANALYZER_LEN: usize = LAYOUT_LEN + 7;

pub fn analyzer(b: &[u8]) -> Option<(LayoutConfig, AnalyzerConfig)> {
    let l = layout(b)?;
    if b.len() < ANALYZER_LEN {
        return None;
    }
    let a = &b[LAYOUT_LEN..];
    Some((
        l,
        AnalyzerConfig {
            mode: if a[0] == 1 { Mode::Compat13 } else { Mode::V2 },
            bar_detection: a[1] != 0,
            black_luma: a[2],
            bar_line_pct: a[3].clamp(50, 100),
            hysteresis: a[4].clamp(1, 60),
            sample_step: a[5].clamp(1, 8),
            dark_reach_pct: a[6].min(30),
        },
    ))
}

/// Picture: version, brightness, saturation (u16), gamma x10, balance r g b, black.
pub const PICTURE_LEN: usize = 9;

pub fn picture(b: &[u8]) -> Option<PictureConfig> {
    if b.len() < PICTURE_LEN || b[0] != VERSION {
        return None;
    }
    Some(PictureConfig {
        brightness_pct: b[1],
        saturation_pct: u16_at(b, 2),
        gamma_x10: b[4],
        balance_pct: [b[5], b[6], b[7]],
        black: b[8],
    })
}

/// Pipeline: layout (17), picture (9), smoothing 1..10, colour order 0..5.
pub const PIPELINE_LEN: usize = LAYOUT_LEN + PICTURE_LEN + 2;

pub fn pipeline(b: &[u8]) -> Option<(LayoutConfig, PictureConfig, u8, u8)> {
    if b.len() < PIPELINE_LEN {
        return None;
    }
    let l = layout(&b[..LAYOUT_LEN])?;
    let p = picture(&b[LAYOUT_LEN..LAYOUT_LEN + PICTURE_LEN])?;
    Some((l, p, b[PIPELINE_LEN - 2], b[PIPELINE_LEN - 1]))
}

/// Effect params: version, speed, hue (u16), sat, brightness.
pub const PARAMS_LEN: usize = 6;

pub fn params(b: &[u8]) -> Option<Params> {
    if b.len() < PARAMS_LEN || b[0] != VERSION {
        return None;
    }
    Some(Params { speed: b[1], hue: u16_at(b, 2) % 360, sat: b[4], brightness: b[5] })
}

/// Music: version, effect 0..7, palette 0..4, hue (u16), follow loudness, min brightness %, beat response %,
/// fade ms (u16), delay ms (i16).
pub const MUSIC_LEN: usize = 12;

pub fn music(b: &[u8]) -> Option<(MusicEffect, MusicParams, i16)> {
    if b.len() < MUSIC_LEN || b[0] != VERSION {
        return None;
    }
    let fx = crate::effects::music::MUSIC_EFFECTS.get(b[1] as usize)?.0;
    let palette = match b[2] {
        1 => Palette::Warm,
        2 => Palette::Cool,
        3 => Palette::Neon,
        4 => Palette::Mono,
        _ => Palette::Rainbow,
    };
    let p = MusicParams {
        palette,
        hue: u16_at(b, 3) % 360,
        follow_loudness: b[5] != 0,
        min_brightness_pct: b[6].min(30),
        beat_response_pct: b[7].min(100),
        fade_ms: u16_at(b, 8).clamp(50, 2000),
    };
    Some((fx, p, u16_at(b, 10) as i16))
}

/// Audio: version, sample rate / 100 (u16: 480 = 48 kHz), sensitivity (0 = auto), range, beat k x10.
pub const AUDIO_LEN: usize = 6;

pub fn audio(b: &[u8]) -> Option<AudioConfig> {
    if b.len() < AUDIO_LEN || b[0] != VERSION {
        return None;
    }
    Some(AudioConfig {
        sample_rate: u16_at(b, 1) as u32 * 100,
        sensitivity: b[3].min(10),
        range: match b[4] {
            1 => Range::BassOnly,
            2 => Range::Vocals,
            _ => Range::Full,
        },
        beat_k_x10: b[5].clamp(5, 40),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_round_trip_and_rejects() {
        let c = LayoutConfig { bottom_gap: 4, counter_clockwise: true, offset: 10, start: StartCorner::BottomLeft, ..LayoutConfig::default() };
        let mut b = [0u8; LAYOUT_LEN];
        write_layout(&c, &mut b);
        let r = layout(&b).unwrap();
        assert_eq!((r.top, r.bottom_gap, r.counter_clockwise, r.offset, r.start), (56, 4, true, 10, StartCorner::BottomLeft));
        assert!(layout(&b[..10]).is_none());
        b[0] = 9;
        assert!(layout(&b).is_none());
        // hue 360 wraps to 0
        assert_eq!(params(&[1, 5, 0x68, 0x01, 80, 100]).map(|p| (p.speed, p.hue, p.sat)), Some((5, 0, 80)));
    }
}
