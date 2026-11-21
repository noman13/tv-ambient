//! The reference-compatible analysis, colour, smoothing and layout against the reference implementation's outputs for real captures
//! (`core/tests/golden/`): ±1 per channel allowed (TECHNICAL.md §5, §18), anything more is a bug.

mod common;

use std::fs;

use common::*;
use tvambient_core::analyze::{Analyzer, AnalyzerConfig, FrameView, PixelFormat};
use tvambient_core::color::Picture;
use tvambient_core::layout::{Edge, Layout};
use tvambient_core::smooth::Smoother;

fn expected_files(suffix_filter: &dyn Fn(&str) -> bool) -> Vec<std::path::PathBuf> {
    let mut v: Vec<_> = fs::read_dir(golden().join("expected"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| suffix_filter(p.file_name().unwrap().to_str().unwrap()))
        .collect();
    v.sort();
    v
}

fn clip_of(csv_path: &std::path::Path) -> std::path::PathBuf {
    let name = csv_path.file_name().unwrap().to_str().unwrap();
    golden().join("frames").join(format!("{}.tvaf", name.split('.').next().unwrap()))
}

#[test]
fn frames_match_1_3_0() {
    let files = expected_files(&|n| !n.starts_with("effects") && !n.starts_with("layout") && !n.contains(".smooth-"));
    assert_eq!(files.len(), 19, "7 default + 7 border-off + 3 layout + 2 picture variants");
    let (mut frames_checked, mut worst, mut channels_off) = (0, 0u8, 0usize);
    for path in files {
        let csv = read_csv(&path);
        let layout = Layout::new(&layout_config(&csv.settings));
        let picture = Picture::new(&picture_config(&csv.settings));
        let mut cfg = AnalyzerConfig::compat_1_3();
        cfg.bar_detection = csv.settings["border"] == 1;
        let mut an = Analyzer::new(cfg, &layout);
        let clip = read_tvaf(&clip_of(&path));
        assert_eq!(clip.len(), csv.rows.len(), "{}", path.display());
        let mut raw = vec![0u8; layout.len() * 3];
        for (row, (w, h, rgb)) in csv.rows.iter().zip(&clip) {
            let f = FrameView::new(rgb, *w, *h, *w as usize * 3, PixelFormat::Rgb888).unwrap();
            let r = an.analyze(&f, &mut raw);
            let k = csv.col(row, "frame");
            let at = format!("{} frame {k}", path.file_name().unwrap().to_str().unwrap());
            assert_eq!(r.bars.left.to_string(), csv.col(row, "crop_x"), "{at}: crop x");
            assert_eq!(r.bars.top.to_string(), csv.col(row, "crop_y"), "{at}: crop y");
            assert_eq!(if r.held_back { "drop" } else { "use" }, csv.col(row, "guard"), "{at}: guard");
            let (d, n) = diff(&raw, &hex(&csv.col(row, "raw_rgb")));
            assert!(d <= 1, "{at}: raw averages off by {d}");
            worst = worst.max(d);
            channels_off += n;
            if !r.held_back {
                let mut out = raw.clone();
                picture.apply_capture(&mut out);
                let (d, n) = diff(&out, &hex(&csv.col(row, "out_rgb")));
                assert!(d <= 1, "{at}: processed colours off by {d}");
                worst = worst.max(d);
                channels_off += n;
            }
            frames_checked += 1;
        }
    }
    println!("frames checked: {frames_checked}, worst difference {worst}, channels off by 1: {channels_off}");
}

#[test]
fn smoothing_matches_1_3_0() {
    const RATE_FPS: [u32; 4] = [10, 15, 20, 30];
    for path in expected_files(&|n| n.contains(".smooth-")) {
        let csv = read_csv(&path);
        let s = &csv.settings;
        let layout = Layout::new(&layout_config(s));
        let picture = Picture::new(&picture_config(s));
        let mut cfg = AnalyzerConfig::compat_1_3();
        cfg.bar_detection = s["border"] == 1;
        let mut an = Analyzer::new(cfg, &layout);
        let clip = read_tvaf(&clip_of(&path));
        let cap_ms = 1000 / RATE_FPS[s["rate"] as usize];
        // the CSV's own t_ms column carries the tick times (1000 / out_fps, integer ms, as the reference sleeps)
        // accepted, processed targets in arrival order (GoldenHarness.smooth)
        let mut targets: Vec<(u32, Vec<u8>)> = Vec::new();
        let mut raw = vec![0u8; layout.len() * 3];
        for (k, (w, h, rgb)) in clip.iter().enumerate() {
            let f = FrameView::new(rgb, *w, *h, *w as usize * 3, PixelFormat::Rgb888).unwrap();
            if !an.analyze(&f, &mut raw).held_back {
                let mut o = raw.clone();
                picture.apply_capture(&mut o);
                targets.push((k as u32 * cap_ms, o));
            }
        }
        let mut sm = Smoother::new(layout.len() * 3, s["smooth"] as u8);
        let zeros = vec![0u8; layout.len() * 3];
        let (mut next, mut used): (usize, Option<usize>) = (0, None);
        for row in &csv.rows {
            let t: u32 = csv.col(row, "t_ms").parse().unwrap();
            while next < targets.len() && targets[next].0 <= t {
                used = Some(next);
                next += 1;
            }
            match used {
                Some(u) => sm.step(&targets[u].1, true),
                None => sm.step(&zeros, false),
            };
            let (d, _) = diff(sm.output(), &hex(&csv.col(row, "out_rgb")));
            assert!(d <= 1, "{} tick {}: off by {d}", path.display(), csv.col(row, "tick"));
        }
    }
}

#[test]
fn layout_matches_1_3_0() {
    for path in expected_files(&|n| n.starts_with("layout")) {
        let csv = read_csv(&path);
        let layout = Layout::new(&layout_config(&csv.settings));
        assert_eq!(layout.len(), csv.rows.len());
        for (led, row) in layout.leds.iter().zip(&csv.rows) {
            let edge = match csv.col(row, "edge").as_str() {
                "top" => Edge::Top,
                "right" => Edge::Right,
                "bottom" => Edge::Bottom,
                _ => Edge::Left,
            };
            assert_eq!(led.edge, edge);
            let f = |c: &str| csv.col(row, c).parse::<f32>().unwrap();
            assert_eq!((led.region.x0, led.region.y0, led.region.x1, led.region.y1), (f("x0"), f("y0"), f("x1"), f("y1")));
        }
    }
}
