//! Shared helpers for the golden tests: TVAF clips, the reference CSV outputs and the settings line they start with.
#![allow(dead_code)]

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use tvambient_core::color::PictureConfig;
use tvambient_core::layout::{LayoutConfig, StartCorner};

pub fn golden() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/golden")
}

/// Frames of a TVAF clip (the reference helper output) as (width, height, rgb).
pub fn read_tvaf(path: &Path) -> Vec<(u16, u16, Vec<u8>)> {
    let data = fs::read(path).unwrap();
    let be = |i: usize| u32::from_be_bytes(data[i..i + 4].try_into().unwrap());
    let (mut frames, mut i) = (Vec::new(), 0);
    while i + 12 <= data.len() {
        assert_eq!(be(i), 0x5456_4146, "{}: no frame marker at {i}", path.display());
        let (w, h) = (be(i + 4) as usize, be(i + 8) as usize);
        frames.push((w as u16, h as u16, data[i + 12..i + 12 + w * h * 3].to_vec()));
        i += 12 + w * h * 3;
    }
    frames
}

pub struct Csv {
    pub settings: HashMap<String, i64>,
    pub header: Vec<String>,
    pub rows: Vec<Vec<String>>,
}

impl Csv {
    pub fn col(&self, row: &[String], name: &str) -> String {
        let i = self.header.iter().position(|h| h == name).unwrap_or_else(|| panic!("no column {name}"));
        row[i].clone()
    }
}

pub fn read_csv(path: &Path) -> Csv {
    let text = fs::read_to_string(path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    let mut settings = HashMap::new();
    let mut header = Vec::new();
    let mut rows = Vec::new();
    for line in text.lines() {
        if let Some(s) = line.strip_prefix("# settings:") {
            for kv in s.split_whitespace() {
                let (k, v) = kv.split_once('=').unwrap();
                settings.insert(k.to_owned(), v.parse().unwrap());
            }
        } else if line.starts_with('#') {
        } else if header.is_empty() {
            header = line.split(',').map(str::to_owned).collect();
        } else {
            rows.push(line.split(',').map(str::to_owned).collect());
        }
    }
    Csv { settings, header, rows }
}

pub fn hex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

pub fn layout_config(s: &HashMap<String, i64>) -> LayoutConfig {
    LayoutConfig {
        top: s["top"] as u16,
        right: s["right"] as u16,
        bottom: s["bottom"] as u16,
        left: s["left"] as u16,
        bottom_gap: s["gap"] as u16,
        start: StartCorner::TopLeft,
        counter_clockwise: s["direction"] == 1,
        offset: s["offset"] as u16,
        depth_top_bottom_pct: s["depth_tb"] as u8,
        depth_sides_pct: s["depth_lr"] as u8,
    }
}

pub fn picture_config(s: &HashMap<String, i64>) -> PictureConfig {
    PictureConfig {
        brightness_pct: s["bright"] as u8,
        saturation_pct: s["sat"] as u16,
        gamma_x10: s["gamma"] as u8,
        balance_pct: [s["bal_r"] as u8, s["bal_g"] as u8, s["bal_b"] as u8],
        black: s["black"] as u8,
    }
}

/// Largest per-channel difference, and how many channels differ at all.
pub fn diff(a: &[u8], b: &[u8]) -> (u8, usize) {
    assert_eq!(a.len(), b.len());
    a.iter().zip(b).fold((0, 0), |(m, n), (&x, &y)| {
        let d = x.abs_diff(y);
        (m.max(d), n + (d > 0) as usize)
    })
}
