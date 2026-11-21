//! Edge analysis with black-bar fallback (TECHNICAL.md §5). Integer only, no allocation per frame.
//!
//! Two modes: [`Mode::V2`] is 2.0's analysis (bars per side, sparse sampling, hysteresis, regions along the content
//! edge, dark-edge reach, overlay mask); [`Mode::Compat13`] reproduces the reference `FrameAnalyzer`
//! exactly, for the golden tests against `core/tests/golden/`.

use alloc::vec;
use alloc::vec::Vec;

use crate::layout::{pixel_rect, Edge, Layout, PixelRect, Region};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PixelFormat {
    /// 3 bytes per pixel, R G B (the reference helper output).
    Rgb888,
    /// 4 bytes per pixel, R G B A in memory (Android `HardwareBuffer` RGBA_8888, `Bitmap` ARGB_8888).
    Rgba8888,
}

impl PixelFormat {
    #[inline]
    pub fn bytes(self) -> usize {
        match self {
            PixelFormat::Rgb888 => 3,
            PixelFormat::Rgba8888 => 4,
        }
    }
}

/// A borrowed frame: `stride` bytes per row.
#[derive(Clone, Copy)]
pub struct FrameView<'a> {
    pub data: &'a [u8],
    pub width: u16,
    pub height: u16,
    pub stride: usize,
    pub format: PixelFormat,
}

impl<'a> FrameView<'a> {
    /// Checks that the buffer holds the whole frame.
    pub fn new(data: &'a [u8], width: u16, height: u16, stride: usize, format: PixelFormat) -> Option<Self> {
        let need = if height == 0 { 0 } else { stride * (height as usize - 1) + width as usize * format.bytes() };
        if width == 0 || height == 0 || stride < width as usize * format.bytes() || data.len() < need {
            return None;
        }
        Some(FrameView { data, width, height, stride, format })
    }

    #[inline]
    fn rgb(&self, x: u16, y: u16) -> (u32, u32, u32) {
        let p = y as usize * self.stride + x as usize * self.format.bytes();
        (self.data[p] as u32, self.data[p + 1] as u32, self.data[p + 2] as u32)
    }

    #[inline]
    fn luma(&self, x: u16, y: u16) -> u32 {
        let (r, g, b) = self.rgb(x, y);
        (r * 77 + g * 150 + b * 29) >> 8
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    V2,
    Compat13,
}

#[derive(Clone, Copy, Debug)]
pub struct AnalyzerConfig {
    pub mode: Mode,
    pub bar_detection: bool,
    /// A pixel at or below this luma (0..255) counts as black.
    pub black_luma: u8,
    /// V2: a line is a bar line when at least this % of its sampled pixels are black.
    pub bar_line_pct: u8,
    /// Frames a new bar size must hold before it's used.
    pub hysteresis: u8,
    /// V2: sample every n-th pixel in each direction inside a region (1 = every pixel).
    pub sample_step: u8,
    /// V2: a dark region may reach this % further into the picture to find colour (0 = off).
    pub dark_reach_pct: u8,
}

impl AnalyzerConfig {
    pub fn v2() -> Self {
        AnalyzerConfig {
            mode: Mode::V2,
            bar_detection: true,
            black_luma: 24,
            bar_line_pct: 97,
            hysteresis: 6,
            sample_step: 2,
            dark_reach_pct: 0,
        }
    }

    pub fn compat_1_3() -> Self {
        AnalyzerConfig {
            mode: Mode::Compat13,
            bar_detection: true,
            black_luma: 24,
            bar_line_pct: 100,
            hysteresis: 3,
            sample_step: 1,
            dark_reach_pct: 0,
        }
    }
}

/// Bars in pixels from each edge (the content rectangle is what's inside them).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Bars {
    pub left: u16,
    pub top: u16,
    pub right: u16,
    pub bottom: u16,
}

impl Bars {
    pub fn any(&self) -> bool {
        self.left | self.top | self.right | self.bottom != 0
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct AnalyzeResult {
    /// The bars in use for this frame (after hysteresis).
    pub bars: Bars,
    pub whole_black: bool,
    /// The black-glitch guard held this frame back: don't show it (the reference guard).
    pub held_back: bool,
}

/// the reference black-glitch guard (`AmbiService.finishFrame`): a sudden all-black frame after a lit one is used only
/// when the next frame agrees.
#[derive(Clone, Copy, Debug, Default)]
pub struct BlackGuard {
    last_avg: u32,
    pending: bool,
}

impl BlackGuard {
    pub fn accept(&mut self, raw: &[u8]) -> bool {
        let sum: u32 = raw.iter().map(|&v| v as u32).sum();
        let avg = if raw.is_empty() { 0 } else { sum / raw.len() as u32 };
        if avg < 4 && self.last_avg > 12 && !self.pending {
            self.pending = true;
            return false;
        }
        self.pending = false;
        self.last_avg = avg;
        true
    }
}

pub struct Analyzer {
    cfg: AnalyzerConfig,
    regions: Vec<(Region, Edge)>,
    crop: Bars,
    cand: Option<Bars>,
    seen: u8,
    guard: BlackGuard,
    /// Overlay rectangle to ignore, in frame pixels (TECHNICAL.md §12.3).
    mask: Option<PixelRect>,
    masked: Vec<bool>,
}

impl Analyzer {
    pub fn new(cfg: AnalyzerConfig, layout: &Layout) -> Analyzer {
        let regions: Vec<(Region, Edge)> = layout.leds.iter().map(|l| (l.region, l.edge)).collect();
        let n = regions.len();
        Analyzer { cfg, regions, crop: Bars::default(), cand: None, seen: 0, guard: BlackGuard::default(), mask: None, masked: vec![false; n] }
    }

    /// LED count; `out` for [`Analyzer::analyze`] must hold 3 bytes per LED.
    pub fn len(&self) -> usize {
        self.regions.len()
    }

    pub fn is_empty(&self) -> bool {
        self.regions.is_empty()
    }

    /// Pixels to ignore (the stats overlay), already in capture pixels, or `None`.
    pub fn set_mask(&mut self, mask: Option<PixelRect>) {
        self.mask = mask.filter(|m| m.x1 > m.x0 && m.y1 > m.y0);
    }

    pub fn bars(&self) -> Bars {
        self.crop
    }

    /// Averages `frame` into `out` (RGB per LED, strip order). Returns the bars used and the guard's verdict.
    pub fn analyze(&mut self, f: &FrameView, out: &mut [u8]) -> AnalyzeResult {
        let n = self.regions.len();
        if out.len() < n * 3 {
            return AnalyzeResult::default();
        }
        let whole_black = match (self.cfg.mode, self.cfg.bar_detection) {
            (Mode::Compat13, true) => self.update_bars_compat(f),
            (Mode::Compat13, false) => {
                self.crop = Bars::default();
                false
            }
            (Mode::V2, bars) => {
                let black = self.whole_black_v2(f);
                if !bars {
                    self.crop = Bars::default();
                } else if !black {
                    self.update_bars_v2(f);
                }
                black
            }
        };
        match self.cfg.mode {
            Mode::Compat13 => self.average_compat(f, out),
            Mode::V2 => self.average_v2(f, out),
        }
        let held_back = !self.guard.accept(&out[..n * 3]);
        AnalyzeResult { bars: self.crop, whole_black, held_back }
    }

    // ---------------------------------------------------------------- reference-compatible

    fn dark_row_compat(&self, f: &FrameView, y: u16) -> bool {
        (0..f.width).step_by(2).all(|x| f.luma(x, y) <= self.cfg.black_luma as u32)
    }

    fn dark_col_compat(&self, f: &FrameView, x: u16) -> bool {
        (0..f.height).step_by(2).all(|y| f.luma(x, y) <= self.cfg.black_luma as u32)
    }

    /// the reference `updateCrop`: symmetric bars (the smaller of each pair), 2+ lines, `hysteresis` frames.
    fn update_bars_compat(&mut self, f: &FrameView) -> bool {
        let (w, h) = (f.width, f.height);
        let (max_y, max_x) = (h / 4, w / 4);
        let count = |limit: u16, dark: &dyn Fn(u16) -> bool| {
            let mut k = 0;
            while k < limit && dark(k) {
                k += 1;
            }
            k
        };
        let top = count(max_y, &|k| self.dark_row_compat(f, k));
        let bottom = count(max_y, &|k| self.dark_row_compat(f, h - 1 - k));
        let left = count(max_x, &|k| self.dark_col_compat(f, k));
        let right = count(max_x, &|k| self.dark_col_compat(f, w - 1 - k));
        if top >= max_y && left >= max_x {
            return true; // an all-black frame says nothing about bars: keep the crop
        }
        let mut y = top.min(bottom);
        let mut x = left.min(right);
        if y < 2 {
            y = 0;
        }
        if x < 2 {
            x = 0;
        }
        self.consider(Bars { left: x, top: y, right: x, bottom: y });
        false
    }

    fn average_compat(&self, f: &FrameView, out: &mut [u8]) {
        let (w, h) = (f.width as i32, f.height as i32);
        let (ax, ay) = (self.crop.left as i32, self.crop.top as i32);
        let (aw, ah) = (w - 2 * ax, h - 2 * ay);
        for (i, (reg, _)) in self.regions.iter().enumerate() {
            let r = pixel_rect(reg, ax, ay, aw, ah, w, h);
            let (mut sr, mut sg, mut sb, mut n) = (0u32, 0u32, 0u32, 0u32);
            for y in r.y0..r.y1 {
                for x in r.x0..r.x1 {
                    let (pr, pg, pb) = f.rgb(x, y);
                    sr += pr;
                    sg += pg;
                    sb += pb;
                    n += 1;
                }
            }
            let n = n.max(1);
            out[i * 3] = (sr / n) as u8;
            out[i * 3 + 1] = (sg / n) as u8;
            out[i * 3 + 2] = (sb / n) as u8;
        }
    }

    // ---------------------------------------------------------------- 2.0

    fn consider(&mut self, b: Bars) {
        if self.cand == Some(b) {
            self.seen = self.seen.saturating_add(1);
            if self.seen >= self.cfg.hysteresis.max(1) {
                self.crop = b;
            }
        } else {
            self.cand = Some(b);
            self.seen = 1;
            if self.cfg.hysteresis <= 1 {
                self.crop = b;
            }
        }
    }

    fn line_is_bar(&self, f: &FrameView, horizontal: bool, at: u16) -> bool {
        let (len, step) = if horizontal { (f.width, 2) } else { (f.height, 2) };
        let (mut dark, mut total) = (0u32, 0u32);
        let mut k = 0;
        while k < len {
            let l = if horizontal { f.luma(k, at) } else { f.luma(at, k) };
            if l <= self.cfg.black_luma as u32 {
                dark += 1;
            }
            total += 1;
            k += step;
        }
        dark * 100 >= self.cfg.bar_line_pct as u32 * total
    }

    fn whole_black_v2(&self, f: &FrameView) -> bool {
        let (mut dark, mut total) = (0u32, 0u32);
        for y in (0..f.height).step_by(4) {
            for x in (0..f.width).step_by(4) {
                if f.luma(x, y) <= self.cfg.black_luma as u32 {
                    dark += 1;
                }
                total += 1;
            }
        }
        dark * 100 >= self.cfg.bar_line_pct as u32 * total
    }

    fn update_bars_v2(&mut self, f: &FrameView) {
        let (w, h) = (f.width, f.height);
        let (max_y, max_x) = (h / 4, w / 4);
        let side = |limit: u16, horizontal: bool, line: &dyn Fn(u16) -> u16| {
            let mut k = 0;
            while k < limit && self.line_is_bar(f, horizontal, line(k)) {
                k += 1;
            }
            if k < 2 { 0 } else { k } // 1-line differences are logos and subtitles
        };
        let b = Bars {
            top: side(max_y, true, &|k| k),
            bottom: side(max_y, true, &|k| h - 1 - k),
            left: side(max_x, false, &|k| k),
            right: side(max_x, false, &|k| w - 1 - k),
        };
        self.consider(b);
    }

    fn average_v2(&mut self, f: &FrameView, out: &mut [u8]) {
        let (w, h) = (f.width as i32, f.height as i32);
        let c = self.crop;
        let (ax, ay) = (c.left as i32, c.top as i32);
        let (aw, ah) = ((w - c.left as i32 - c.right as i32).max(1), (h - c.top as i32 - c.bottom as i32).max(1));
        let step = self.cfg.sample_step.max(1) as u16;
        let black = self.cfg.black_luma as u32;
        let reach = self.cfg.dark_reach_pct as f32 / 100.0;
        let mask = self.mask;
        for i in 0..self.regions.len() {
            let (reg, edge) = self.regions[i];
            let mut ext = 0.0f32;
            loop {
                let mut r = reg;
                match edge {
                    Edge::Top => r.y1 = (r.y1 + ext).min(1.0),
                    Edge::Bottom => r.y0 = (r.y0 - ext).max(0.0),
                    Edge::Left => r.x1 = (r.x1 + ext).min(1.0),
                    Edge::Right => r.x0 = (r.x0 - ext).max(0.0),
                }
                let pr = pixel_rect(&r, ax, ay, aw, ah, w, h);
                let (rgb, masked_more_than_half) = region_average(f, pr, step, mask);
                self.masked[i] = masked_more_than_half;
                let luma = (rgb[0] as u32 * 77 + rgb[1] as u32 * 150 + rgb[2] as u32 * 29) >> 8;
                if reach > 0.0 && luma <= black && ext < reach && !masked_more_than_half {
                    ext = (ext + reach / 4.0).min(reach);
                    continue;
                }
                out[i * 3..i * 3 + 3].copy_from_slice(&rgb);
                break;
            }
        }
        if mask.is_some() {
            interpolate_masked(&self.masked, out);
        }
    }
}

/// Average of a pixel rectangle, sampling every `step` pixels, skipping masked pixels. Returns the colour and
/// whether more than half of the samples were masked.
fn region_average(f: &FrameView, r: PixelRect, step: u16, mask: Option<PixelRect>) -> ([u8; 3], bool) {
    let (mut sr, mut sg, mut sb, mut n, mut masked) = (0u32, 0u32, 0u32, 0u32, 0u32);
    // offset by half a step so a 1-2 pixel region still samples its middle
    let mut y = r.y0 + (step / 2).min(r.y1.saturating_sub(r.y0).saturating_sub(1));
    while y < r.y1 {
        let mut x = r.x0 + (step / 2).min(r.x1.saturating_sub(r.x0).saturating_sub(1));
        while x < r.x1 {
            if let Some(m) = mask {
                if x >= m.x0 && x < m.x1 && y >= m.y0 && y < m.y1 {
                    masked += 1;
                    x += step;
                    continue;
                }
            }
            let (pr, pg, pb) = f.rgb(x, y);
            sr += pr;
            sg += pg;
            sb += pb;
            n += 1;
            x += step;
        }
        y += step;
    }
    let more_masked = masked > n;
    let n = n.max(1);
    ([(sr / n) as u8, (sg / n) as u8, (sb / n) as u8], more_masked)
}

/// LEDs mostly under the mask take a colour interpolated from the nearest unmasked LEDs along the strip.
fn interpolate_masked(masked: &[bool], out: &mut [u8]) {
    let n = masked.len();
    if n == 0 || masked.iter().all(|&m| m) {
        return;
    }
    for i in 0..n {
        if !masked[i] {
            continue;
        }
        let (mut a, mut da) = (i, 0usize);
        while masked[a] {
            a = (a + n - 1) % n;
            da += 1;
        }
        let (mut b, mut db) = (i, 0usize);
        while masked[b] {
            b = (b + 1) % n;
            db += 1;
        }
        let t = (da * 256 / (da + db)) as u32;
        for c in 0..3 {
            let (va, vb) = (out[a * 3 + c] as u32, out[b * 3 + c] as u32);
            out[i * 3 + c] = ((va * (256 - t) + vb * t + 128) >> 8) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::LayoutConfig;
    use alloc::vec;

    fn frame(w: usize, h: usize, bar: usize, colour: [u8; 3]) -> Vec<u8> {
        let mut v = vec![0u8; w * h * 3];
        for y in bar..h - bar {
            for x in 0..w {
                v[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&colour);
            }
        }
        v
    }

    #[test]
    fn v2_finds_letterbox_after_hysteresis_and_samples_content_edge() {
        let layout = Layout::new(&LayoutConfig::default());
        let mut a = Analyzer::new(AnalyzerConfig::v2(), &layout);
        let data = frame(128, 72, 9, [200, 40, 10]);
        let f = FrameView::new(&data, 128, 72, 128 * 3, PixelFormat::Rgb888).unwrap();
        let mut out = vec![0u8; layout.len() * 3];
        for k in 0..6 {
            let r = a.analyze(&f, &mut out);
            assert_eq!(r.bars.top, if k < 5 { 0 } else { 9 }, "frame {k}");
        }
        // top LEDs now sample the content edge: the picture's colour, not the bar
        assert_eq!(&out[0..3], &[200, 40, 10]);
    }

    #[test]
    fn whole_black_keeps_bars_and_guard_holds_one_frame() {
        let layout = Layout::new(&LayoutConfig::default());
        let mut a = Analyzer::new(AnalyzerConfig::v2(), &layout);
        let lit = frame(64, 36, 0, [120, 120, 120]);
        let dark = vec![0u8; 64 * 36 * 3];
        let mut out = vec![0u8; layout.len() * 3];
        let fl = FrameView::new(&lit, 64, 36, 64 * 3, PixelFormat::Rgb888).unwrap();
        let fd = FrameView::new(&dark, 64, 36, 64 * 3, PixelFormat::Rgb888).unwrap();
        assert!(!a.analyze(&fl, &mut out).held_back);
        let r = a.analyze(&fd, &mut out);
        assert!(r.whole_black && r.held_back);
        let r = a.analyze(&fd, &mut out);
        assert!(r.whole_black && !r.held_back);
    }

    #[test]
    fn mask_is_ignored_and_interpolated() {
        let layout = Layout::new(&LayoutConfig::default());
        let mut a = Analyzer::new(AnalyzerConfig::v2(), &layout);
        let mut data = frame(128, 72, 0, [0, 100, 0]);
        for y in 0..20 {
            for x in 0..30 {
                data[(y * 128 + x) * 3..(y * 128 + x) * 3 + 3].copy_from_slice(&[255, 255, 255]);
            }
        }
        a.set_mask(Some(PixelRect { x0: 0, y0: 0, x1: 30, y1: 20 }));
        let f = FrameView::new(&data, 128, 72, 128 * 3, PixelFormat::Rgb888).unwrap();
        let mut out = vec![0u8; layout.len() * 3];
        a.analyze(&f, &mut out);
        assert_eq!(&out[0..3], &[0, 100, 0], "the white panel never colours the LEDs");
    }

    #[test]
    fn rejects_short_buffers() {
        assert!(FrameView::new(&[0u8; 10], 4, 4, 12, PixelFormat::Rgb888).is_none());
        assert!(FrameView::new(&[0u8; 64], 4, 4, 16, PixelFormat::Rgba8888).is_some());
    }
}
