//! Effect host tests (TECHNICAL.md §15): the five classic effects against the reference outputs; every effect rendered for 10 s
//! without trouble and deterministically; the flash cap; and a hash of each effect's first second, so an accidental
//! change shows up. `UPDATE_EFFECT_HASHES=1 cargo test` rewrites `tests/effect_hashes.txt` after a deliberate change.

mod common;

use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use common::*;
use tvambient_core::color::Picture;
use tvambient_core::effects::{self, FlashCap, Params, EFFECTS, FLASH_RISE};
use tvambient_core::layout::{Layout, LayoutConfig};

// The golden files' effect numbers; Running light (3) was removed in 1.0.0, so its rows are skipped.
const OLD_NAMES: [Option<&str>; 5] = [Some("Rainbow swirl ↻"), Some("Rainbow swirl ↺"), Some("Breathing"), None, Some("Slow colours")];

fn id_of(name: &str) -> usize {
    EFFECTS.iter().position(|e| e.name == name).unwrap()
}

#[test]
fn old_effects_match_1_3_0() {
    for file in ["effects.default.csv", "effects.hue200-sat100.csv"] {
        let csv = read_csv(&golden().join("expected").join(file));
        let s = &csv.settings;
        let layout = Layout::new(&layout_config(s));
        let picture = Picture::new(&picture_config(s));
        let p = Params { hue: s["hue"] as u16, sat: s["color_sat"] as u8, ..Params::default() };
        let mut raw = vec![0u8; layout.len() * 3];
        for row in &csv.rows {
            let e: i32 = csv.col(row, "effect").parse().unwrap();
            let t_ms = (csv.col(row, "t_s").parse::<f32>().unwrap() * 1000.0).round() as u32;
            match e {
                -1 => effects::single_colour(&p, 1.0, &layout, &mut raw),
                -2 => effects::single_colour(&p, 0.12, &layout, &mut raw),
                k => match OLD_NAMES[k as usize] {
                    Some(name) => assert!(effects::render(id_of(name), t_ms, &p, &layout, &mut raw)),
                    None => continue,
                },
            }
            let at = format!("{file}: effect {e} at {t_ms} ms");
            let (d, _) = diff(&raw, &hex(&csv.col(row, "raw_rgb")));
            assert!(d <= 1, "{at}: colours off by {d}");
            let mut out = raw.clone();
            picture.apply_output(&mut out);
            let (d, _) = diff(&out, &hex(&csv.col(row, "out_rgb")));
            assert!(d <= 1, "{at}: output off by {d}");
        }
    }
}

/// Flash onsets (mean brightness rising by FLASH_RISE or more frame to frame) in any 1 s window, at most.
fn max_flashes_per_second(means: &[(u32, u32)]) -> usize {
    let onsets: Vec<u32> = means.windows(2).filter(|w| w[1].1 >= w[0].1 + FLASH_RISE).map(|w| w[1].0).collect();
    onsets.iter().map(|&t| onsets.iter().filter(|&&u| u >= t && u < t + 1000).count()).max().unwrap_or(0)
}

#[test]
fn every_effect_renders_and_respects_the_flash_limit() {
    let layout = Layout::new(&LayoutConfig::default());
    let n = layout.len() * 3;
    for (id, fx) in EFFECTS.iter().enumerate() {
        for speed in [1u8, 5, 10] {
            let p = Params { speed, ..Params::default() };
            let (mut frame, mut again, mut shown) = (vec![0u8; n], vec![0u8; n], vec![0u8; n]);
            let mut cap = FlashCap::default();
            let mut means = Vec::new();
            for k in 0..600u32 {
                let t = k * 1000 / 60; // 10 s at 60 fps
                assert!(effects::render(id, t, &p, &layout, &mut frame), "{}", fx.name);
                assert!(effects::render(id, t, &p, &layout, &mut again));
                assert_eq!(frame, again, "{} isn't deterministic at {t} ms", fx.name);
                if fx.flashes {
                    cap.apply(t, &shown, &mut frame);
                }
                shown.copy_from_slice(&frame);
                means.push((t, effects::mean(&shown)));
            }
            let worst = max_flashes_per_second(&means);
            assert!(worst <= 3, "{} at speed {speed}: {worst} flashes in one second", fx.name);
        }
    }
}

fn fnv1a(bytes: &[u8], mut h: u64) -> u64 {
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

#[test]
fn first_second_hashes_are_unchanged() {
    let layout = Layout::new(&LayoutConfig::default());
    let p = Params::default();
    let mut frame = vec![0u8; layout.len() * 3];
    let mut now = BTreeMap::new();
    for (id, fx) in EFFECTS.iter().enumerate() {
        let mut h = 0xcbf2_9ce4_8422_2325u64;
        for k in 0..30u32 {
            effects::render(id, k * 1000 / 30, &p, &layout, &mut frame);
            h = fnv1a(&frame, h);
        }
        now.insert(id, format!("{id:02} {h:016x} {}", fx.name));
    }
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/effect_hashes.txt");
    let text: String = now.values().map(|l| format!("{l}\n")).collect();
    if std::env::var("UPDATE_EFFECT_HASHES").is_ok() || !path.exists() {
        fs::write(&path, &text).unwrap();
        return;
    }
    let stored = fs::read_to_string(&path).unwrap().replace("\r\n", "\n");
    for (a, b) in stored.lines().zip(text.lines()) {
        assert_eq!(a, b, "an effect changed; if it's deliberate, run with UPDATE_EFFECT_HASHES=1");
    }
    assert_eq!(stored.lines().count(), text.lines().count());
}
