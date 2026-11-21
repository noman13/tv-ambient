//! Adalight packets: "Ada", (count - 1) high and low byte, checksum, then 3 bytes per LED in the strip's byte order.

/// The six byte orders, as the "RGB byte order" setting numbers them.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ColorOrder {
    Rgb = 0,
    Rbg = 1,
    Grb = 2,
    Gbr = 3,
    Brg = 4,
    Bgr = 5,
}

impl ColorOrder {
    pub fn from_u8(v: u8) -> ColorOrder {
        match v {
            1 => ColorOrder::Rbg,
            2 => ColorOrder::Grb,
            3 => ColorOrder::Gbr,
            4 => ColorOrder::Brg,
            5 => ColorOrder::Bgr,
            _ => ColorOrder::Rgb,
        }
    }

    fn indices(self) -> [usize; 3] {
        match self {
            ColorOrder::Rgb => [0, 1, 2],
            ColorOrder::Rbg => [0, 2, 1],
            ColorOrder::Grb => [1, 0, 2],
            ColorOrder::Gbr => [1, 2, 0],
            ColorOrder::Brg => [2, 0, 1],
            ColorOrder::Bgr => [2, 1, 0],
        }
    }
}

/// Bytes in a packet for `leds` LEDs.
pub const fn packet_len(leds: usize) -> usize {
    6 + 3 * leds
}

/// Writes a packet for `rgb` (3 bytes per LED) into `out`; returns its length, or 0 if `out` is too short or there
/// are no LEDs.
pub fn write_packet(rgb: &[u8], order: ColorOrder, out: &mut [u8]) -> usize {
    let n = rgb.len() / 3;
    let len = packet_len(n);
    if n == 0 || n > 65536 || out.len() < len {
        return 0;
    }
    let hi = (((n - 1) >> 8) & 0xff) as u8;
    let lo = ((n - 1) & 0xff) as u8;
    out[..6].copy_from_slice(&[b'A', b'd', b'a', hi, lo, hi ^ lo ^ 0x55]);
    let [a, b, c] = order.indices();
    for (px, dst) in rgb.chunks_exact(3).zip(out[6..len].chunks_exact_mut(3)) {
        dst[0] = px[a];
        dst[1] = px[b];
        dst[2] = px[c];
    }
    len
}

/// The highest full-strip update rate a baud rate allows: baud / 10 bits per byte / packet bytes (TECHNICAL.md §7).
pub fn max_fps(baud: u32, leds: usize) -> u32 {
    baud / 10 / packet_len(leds) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packet_and_rate() {
        let mut out = [0u8; 12];
        assert_eq!(write_packet(&[1, 2, 3, 4, 5, 6], ColorOrder::Grb, &mut out), 12);
        assert_eq!(out, [b'A', b'd', b'a', 0, 1, 0x54, 2, 1, 3, 5, 4, 6]);
        assert_eq!(max_fps(230_400, 184), 41);
        assert_eq!(max_fps(1_000_000, 184), 179);
    }
}
