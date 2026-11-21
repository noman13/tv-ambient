//! Picture settings (TECHNICAL.md §8, the reference `ColorProcessor`): saturation, black level, then one lookup table per
//! channel with gamma, white balance and brightness combined. Tables are built once; applying them is integer only.

use crate::math::{pow, round_java};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PictureConfig {
    /// 5..100 %
    pub brightness_pct: u8,
    /// 0..200 %
    pub saturation_pct: u16,
    /// gamma x 10 (19 = 1.9)
    pub gamma_x10: u8,
    /// white balance per channel, 0..100 %
    pub balance_pct: [u8; 3],
    /// colours with no channel above 2 x this are black (0..40)
    pub black: u8,
}

impl Default for PictureConfig {
    /// The reference calibration (the reference defaults).
    fn default() -> Self {
        PictureConfig { brightness_pct: 35, saturation_pct: 150, gamma_x10: 19, balance_pct: [100, 100, 100], black: 5 }
    }
}

pub struct Picture {
    lut: [[u8; 256]; 3],
    sat256: i32,
    black2: i32,
}

impl Picture {
    pub fn new(c: &PictureConfig) -> Picture {
        let gamma = c.gamma_x10.clamp(5, 30) as f32 / 10.0;
        let bright = c.brightness_pct.min(100) as f32 / 100.0;
        let mut lut = [[0u8; 256]; 3];
        for (ch, table) in lut.iter_mut().enumerate() {
            let scale = bright * c.balance_pct[ch].min(100) as f32 / 100.0;
            for (i, v) in table.iter_mut().enumerate() {
                let p = pow((i as f32 / 255.0) as f64, gamma as f64) as f32;
                *v = round_java(p * 255.0 * scale).clamp(0, 255) as u8;
            }
        }
        Picture { lut, sat256: c.saturation_pct.min(200) as i32 * 256 / 100, black2: c.black.min(40) as i32 * 2 }
    }

    /// For captured colours: black level, saturation, then the tables. In place, 3 bytes per LED.
    pub fn apply_capture(&self, rgb: &mut [u8]) {
        for px in rgb.chunks_exact_mut(3) {
            let (r, g, b) = (px[0] as i32, px[1] as i32, px[2] as i32);
            if r.max(g).max(b) <= self.black2 {
                px.fill(0);
                continue;
            }
            let y = (r * 77 + g * 150 + b * 29) >> 8;
            let s = |c: i32| (y + (((c - y) * self.sat256) >> 8)).clamp(0, 255) as usize;
            px[0] = self.lut[0][s(r)];
            px[1] = self.lut[1][s(g)];
            px[2] = self.lut[2][s(b)];
        }
    }

    /// For colours the user chose (effects, single colour): the tables only.
    pub fn apply_output(&self, rgb: &mut [u8]) {
        for px in rgb.chunks_exact_mut(3) {
            px[0] = self.lut[0][px[0] as usize];
            px[1] = self.lut[1][px[1] as usize];
            px[2] = self.lut[2][px[2] as usize];
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_1_3_0() {
        let p = Picture::new(&PictureConfig::default());
        let mut c = [255u8, 12, 0, 10, 10, 10];
        p.apply_capture(&mut c);
        assert_eq!(c, [89, 0, 0, 0, 0, 0]); // round(255 * 0.35); dark grey under the black level
    }
}
