//! Where each LED sits and which part of the picture it samples (TECHNICAL.md §5, DESIGN.md §6).
//!
//! Logical order starts at the top-left corner and runs clockwise: top (left to right), right (top to bottom),
//! bottom (right to left, with an optional gap in the middle), left (bottom to top). The start corner, direction and
//! offset say where physical LED 0 sits. With the start at top-left this is exactly the reference `LedLayout`, float maths
//! included, so the golden outputs match.

use alloc::vec::Vec;

use crate::math::ceil_f32;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Edge {
    Top = 0,
    Right = 1,
    Bottom = 2,
    Left = 3,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum StartCorner {
    TopLeft = 0,
    TopRight = 1,
    BottomRight = 2,
    BottomLeft = 3,
}

impl StartCorner {
    pub fn from_u8(v: u8) -> StartCorner {
        match v {
            1 => StartCorner::TopRight,
            2 => StartCorner::BottomRight,
            3 => StartCorner::BottomLeft,
            _ => StartCorner::TopLeft,
        }
    }
}

/// The most LEDs per side (the reference allowed 300).
pub const MAX_PER_SIDE: u16 = 300;

#[derive(Clone, Copy, Debug)]
pub struct LayoutConfig {
    pub top: u16,
    pub right: u16,
    pub bottom: u16,
    pub left: u16,
    /// LEDs missing in the middle of the bottom (a stand).
    pub bottom_gap: u16,
    pub start: StartCorner,
    pub counter_clockwise: bool,
    /// Logical positions to skip before physical LED 0.
    pub offset: u16,
    /// How deep each region reaches into the picture, % of the height (top/bottom) or width (sides).
    pub depth_top_bottom_pct: u8,
    pub depth_sides_pct: u8,
}

impl Default for LayoutConfig {
    /// The reference setup (the reference defaults): 56/36/56/36 clockwise from the top-left, depth 10%/8%.
    fn default() -> Self {
        LayoutConfig {
            top: 56,
            right: 36,
            bottom: 56,
            left: 36,
            bottom_gap: 0,
            start: StartCorner::TopLeft,
            counter_clockwise: false,
            offset: 0,
            depth_top_bottom_pct: 10,
            depth_sides_pct: 8,
        }
    }
}

/// A region as fractions (0..1) of the content area, as the reference stores it.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Region {
    pub x0: f32,
    pub y0: f32,
    pub x1: f32,
    pub y1: f32,
}

#[derive(Clone, Copy, Debug)]
pub struct Led {
    pub region: Region,
    pub edge: Edge,
    /// Position along the strip, clockwise from the top-left corner: 0..65536 over the whole loop.
    pub pos: u16,
    /// Position along its own side, 0..65535: top and bottom left to right, sides top to bottom.
    pub along: u16,
}

pub struct Layout {
    pub leds: Vec<Led>,
    pub config: LayoutConfig,
}

impl Layout {
    pub fn new(c: &LayoutConfig) -> Layout {
        let (t, r, b, l) = (
            c.top.min(MAX_PER_SIDE) as u32,
            c.right.min(MAX_PER_SIDE) as u32,
            c.bottom.min(MAX_PER_SIDE) as u32,
            c.left.min(MAX_PER_SIDE) as u32,
        );
        let gap = c.bottom_gap.min(MAX_PER_SIDE) as u32;
        let d_tb = c.depth_top_bottom_pct.min(50) as f32 / 100.0;
        let d_lr = c.depth_sides_pct.min(50) as f32 / 100.0;
        let n = (t + r + b + l) as usize;
        let mut logical: Vec<(Region, Edge, u16)> = Vec::with_capacity(n);
        let frac = |i: u32, k: u32| i as f32 / k as f32;
        let along = |num: u32, den: u32| ((num as u64 * 65535) / den.max(1) as u64) as u16;

        for i in 0..t {
            let reg = Region { x0: frac(i, t), y0: 0.0, x1: frac(i + 1, t), y1: d_tb };
            logical.push((reg, Edge::Top, along(2 * i + 1, 2 * t)));
        }
        for i in 0..r {
            let reg = Region { x0: 1.0 - d_lr, y0: frac(i, r), x1: 1.0, y1: frac(i + 1, r) };
            logical.push((reg, Edge::Right, along(2 * i + 1, 2 * r)));
        }
        let slots = b + gap;
        let gap_start = b / 2;
        let mut s = 0;
        for _ in 0..b {
            if s == gap_start {
                s += gap; // the slots hidden behind a stand
            }
            let reg = Region { x0: 1.0 - frac(s + 1, slots), y0: 1.0 - d_tb, x1: 1.0 - frac(s, slots), y1: 1.0 };
            logical.push((reg, Edge::Bottom, along(2 * (slots - s) - 1, 2 * slots)));
            s += 1;
        }
        for i in 0..l {
            let reg = Region { x0: 0.0, y0: 1.0 - frac(i + 1, l), x1: d_lr, y1: 1.0 - frac(i, l) };
            logical.push((reg, Edge::Left, along(2 * (l - i) - 1, 2 * l)));
        }

        let base = match c.start {
            StartCorner::TopLeft => 0,
            StartCorner::TopRight => t,
            StartCorner::BottomRight => t + r,
            StartCorner::BottomLeft => t + r + b,
        } as i64;
        let mut leds = Vec::with_capacity(n);
        for p in 0..n as i64 {
            let li = if n == 0 {
                0
            } else if c.counter_clockwise {
                (base - 1 - c.offset as i64 - p).rem_euclid(n as i64) as usize
            } else {
                (base + c.offset as i64 + p).rem_euclid(n as i64) as usize
            };
            let (region, edge, along) = logical[li];
            let pos = ((li as u64 * 65536) / n as u64) as u16;
            leds.push(Led { region, edge, pos, along });
        }
        Layout { leds, config: *c }
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.leds.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.leds.is_empty()
    }
}

/// A pixel rectangle, half-open: x0..x1, y0..y1.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PixelRect {
    pub x0: u16,
    pub y0: u16,
    pub x1: u16,
    pub y1: u16,
}

/// A region in pixels over the area (ax, ay, aw, ah) of a w x h frame, rounded exactly as the reference does
/// `FrameAnalyzer`: start truncated, end rounded up, at least one pixel, clipped to the frame.
pub fn pixel_rect(reg: &Region, ax: i32, ay: i32, aw: i32, ah: i32, w: i32, h: i32) -> PixelRect {
    let px0 = ax + (reg.x0 * aw as f32) as i32;
    let mut px1 = ax + ceil_f32(reg.x1 * aw as f32) as i32;
    let py0 = ay + (reg.y0 * ah as f32) as i32;
    let mut py1 = ay + ceil_f32(reg.y1 * ah as f32) as i32;
    if px1 <= px0 {
        px1 = px0 + 1;
    }
    if py1 <= py0 {
        py1 = py0 + 1;
    }
    let px1 = px1.min(w);
    let py1 = py1.min(h);
    PixelRect {
        x0: px0.clamp(0, w) as u16,
        y0: py0.clamp(0, h) as u16,
        x1: px1.max(0) as u16,
        y1: py1.max(0) as u16,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reference_layout() {
        let l = Layout::new(&LayoutConfig::default());
        assert_eq!(l.len(), 184);
        assert_eq!(l.leds[0].edge, Edge::Top);
        assert_eq!(l.leds[56].edge, Edge::Right);
        assert_eq!(l.leds[92].edge, Edge::Bottom);
        assert_eq!(l.leds[148].edge, Edge::Left);
        assert_eq!(l.leds[0].region, Region { x0: 0.0, y0: 0.0, x1: 1.0 / 56.0, y1: 0.1 });
        // positions run round the loop
        assert!(l.leds.windows(2).all(|w| w[1].pos > w[0].pos));
    }

    #[test]
    fn start_corner_and_direction() {
        let c = LayoutConfig { start: StartCorner::BottomRight, ..LayoutConfig::default() };
        let l = Layout::new(&c);
        assert_eq!(l.leds[0].edge, Edge::Bottom);
        let c = LayoutConfig { counter_clockwise: true, ..LayoutConfig::default() };
        let l = Layout::new(&c);
        assert_eq!(l.leds[0].edge, Edge::Left); // from the top-left, down the left side first
        assert_eq!(l.leds[35].edge, Edge::Left);
        assert_eq!(l.leds[36].edge, Edge::Bottom);
    }

    #[test]
    fn empty_and_gap() {
        let c = LayoutConfig { top: 0, right: 0, bottom: 0, left: 0, ..LayoutConfig::default() };
        assert!(Layout::new(&c).is_empty());
        let c = LayoutConfig { bottom_gap: 4, ..LayoutConfig::default() };
        assert_eq!(Layout::new(&c).len(), 184);
    }
}
