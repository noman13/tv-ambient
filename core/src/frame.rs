//! Wire formats between the capture helper and the app (TECHNICAL.md §4.2-§4.3, §12.2), little-endian:
//! `TVA2` frames (helper → app), `TVS1` stats records (helper → app), `TVC1` control messages (app → helper).

pub const TVA2: [u8; 4] = *b"TVA2";
pub const TVS1: [u8; 4] = *b"TVS1";
pub const TVC1: [u8; 4] = *b"TVC1";
pub const TVA2_VERSION: u8 = 1;
pub const TVA2_HEADER: usize = 32;

pub mod flags {
    pub const BARS: u8 = 1 << 0;
    pub const WHOLE_BLACK: u8 = 1 << 1;
    pub const CAPTURE_FAILED: u8 = 1 << 2;
    /// The black-glitch guard held this frame back: the app keeps the previous colours.
    pub const HELD_BACK: u8 = 1 << 3;
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FrameHeader {
    pub flags: u8,
    pub leds: u16,
    pub seq: u32,
    /// `CLOCK_MONOTONIC` ns
    pub capture_ns: u64,
    pub capture_us: u32,
    /// content rectangle in capture pixels: left, top, right, bottom
    pub content: [u16; 4],
}

/// Writes a `TVA2` frame; returns its length or `None` if `out` is too short or `rgb` doesn't match `leds`.
pub fn write_tva2(h: &FrameHeader, rgb: &[u8], out: &mut [u8]) -> Option<usize> {
    let len = TVA2_HEADER + 3 * h.leds as usize;
    if rgb.len() != 3 * h.leds as usize || out.len() < len {
        return None;
    }
    write_tva2_header(h, out)?;
    out[TVA2_HEADER..len].copy_from_slice(rgb);
    Some(len)
}

/// Writes only the 32-byte `TVA2` header (the colours already sit after it, written in place by the analyser).
pub fn write_tva2_header(h: &FrameHeader, out: &mut [u8]) -> Option<()> {
    if out.len() < TVA2_HEADER {
        return None;
    }
    out[0..4].copy_from_slice(&TVA2);
    out[4] = TVA2_VERSION;
    out[5] = h.flags;
    out[6..8].copy_from_slice(&h.leds.to_le_bytes());
    out[8..12].copy_from_slice(&h.seq.to_le_bytes());
    out[12..20].copy_from_slice(&h.capture_ns.to_le_bytes());
    out[20..24].copy_from_slice(&h.capture_us.to_le_bytes());
    for (k, v) in h.content.iter().enumerate() {
        out[24 + 2 * k..26 + 2 * k].copy_from_slice(&v.to_le_bytes());
    }
    Some(())
}

fn u16_at(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes([b[i], b[i + 1]])
}

fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
}

/// Reads a `TVA2` header at the start of `buf`; returns it and the total frame length, or `None` if `buf` doesn't
/// start with a valid header (the frame body may still be arriving: check `buf.len() >= length`).
pub fn read_tva2_header(buf: &[u8]) -> Option<(FrameHeader, usize)> {
    if buf.len() < TVA2_HEADER || buf[0..4] != TVA2 || buf[4] != TVA2_VERSION {
        return None;
    }
    let leds = u16_at(buf, 6);
    let h = FrameHeader {
        flags: buf[5],
        leds,
        seq: u32_at(buf, 8),
        capture_ns: u32_at(buf, 12) as u64 | (u32_at(buf, 16) as u64) << 32,
        capture_us: u32_at(buf, 20),
        content: [u16_at(buf, 24), u16_at(buf, 26), u16_at(buf, 28), u16_at(buf, 30)],
    };
    Some((h, TVA2_HEADER + 3 * leds as usize))
}

/// The first position of `magic` in `buf` (the app resyncs on it: native libraries may print to stdout first).
pub fn find(buf: &[u8], magic: &[u8; 4]) -> Option<usize> {
    buf.windows(4).position(|w| w == magic)
}

/// Control messages, app → helper.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Control {
    /// Overlay rectangle to mask, display pixels (left, top, right, bottom); all zero = none.
    Mask([u16; 4]),
    StatsOn(bool),
    StatsIntervalMs(u16),
}

impl Control {
    pub fn write(&self, out: &mut [u8]) -> Option<usize> {
        let (kind, payload_len) = match self {
            Control::Mask(_) => (1u8, 8usize),
            Control::StatsOn(_) => (2, 1),
            Control::StatsIntervalMs(_) => (3, 2),
        };
        let len = 7 + payload_len;
        if out.len() < len {
            return None;
        }
        out[0..4].copy_from_slice(&TVC1);
        out[4] = kind;
        out[5..7].copy_from_slice(&(payload_len as u16).to_le_bytes());
        match self {
            Control::Mask(r) => {
                for (k, v) in r.iter().enumerate() {
                    out[7 + 2 * k..9 + 2 * k].copy_from_slice(&v.to_le_bytes());
                }
            }
            Control::StatsOn(on) => out[7] = *on as u8,
            Control::StatsIntervalMs(ms) => out[7..9].copy_from_slice(&ms.to_le_bytes()),
        }
        Some(len)
    }

    /// Parses one message at the start of `buf`: `(message, length)`; unknown types are skipped (`None` message).
    pub fn read(buf: &[u8]) -> Option<(Option<Control>, usize)> {
        if buf.len() < 7 || buf[0..4] != TVC1 {
            return None;
        }
        let len = 7 + u16_at(buf, 5) as usize;
        if buf.len() < len {
            return None;
        }
        let p = &buf[7..len];
        let msg = match (buf[4], p.len()) {
            (1, 8) => Some(Control::Mask([u16_at(p, 0), u16_at(p, 2), u16_at(p, 4), u16_at(p, 6)])),
            (2, 1) => Some(Control::StatsOn(p[0] != 0)),
            (3, 2) => Some(Control::StatsIntervalMs(u16_at(p, 0))),
            _ => None,
        };
        Some((msg, len))
    }
}

/// A `TVS1` stats record: "TVS1", version 1, count, then count x (id u8, value i32).
pub fn write_tvs1(stats: &[(u8, i32)], out: &mut [u8]) -> Option<usize> {
    let len = 6 + 5 * stats.len();
    if stats.len() > 255 || out.len() < len {
        return None;
    }
    out[0..4].copy_from_slice(&TVS1);
    out[4] = 1;
    out[5] = stats.len() as u8;
    for (k, (id, v)) in stats.iter().enumerate() {
        out[6 + 5 * k] = *id;
        out[7 + 5 * k..11 + 5 * k].copy_from_slice(&v.to_le_bytes());
    }
    Some(len)
}

/// Reads a `TVS1` record into `into`; returns (entries, length).
pub fn read_tvs1(buf: &[u8], into: &mut [(u8, i32)]) -> Option<(usize, usize)> {
    if buf.len() < 6 || buf[0..4] != TVS1 || buf[4] != 1 {
        return None;
    }
    let n = buf[5] as usize;
    let len = 6 + 5 * n;
    if buf.len() < len {
        return None;
    }
    let k = n.min(into.len());
    for (i, slot) in into.iter_mut().enumerate().take(k) {
        *slot = (buf[6 + 5 * i], u32_at(buf, 7 + 5 * i) as i32);
    }
    Some((k, len))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tva2_round_trip_and_resync() {
        let h = FrameHeader { flags: flags::BARS, leds: 2, seq: 7, capture_ns: 1 << 40, capture_us: 9000, content: [0, 9, 128, 63] };
        let mut buf = [0u8; 64];
        buf[..5].copy_from_slice(b"junk\n");
        let len = write_tva2(&h, &[1, 2, 3, 4, 5, 6], &mut buf[5..]).unwrap();
        let at = find(&buf, &TVA2).unwrap();
        assert_eq!(at, 5);
        let (r, l) = read_tva2_header(&buf[at..]).unwrap();
        assert_eq!((r, l), (h, len));
        assert_eq!(&buf[at + TVA2_HEADER..at + l], &[1, 2, 3, 4, 5, 6]);
    }

    #[test]
    fn control_and_stats_round_trip() {
        let mut buf = [0u8; 32];
        for c in [Control::Mask([1, 2, 300, 400]), Control::StatsOn(true), Control::StatsIntervalMs(500)] {
            let n = c.write(&mut buf).unwrap();
            assert_eq!(Control::read(&buf[..n]), Some((Some(c), n)));
        }
        let n = write_tvs1(&[(1, -5), (2, 1_000_000)], &mut buf).unwrap();
        let mut into = [(0u8, 0i32); 4];
        assert_eq!(read_tvs1(&buf[..n], &mut into), Some((2, n)));
        assert_eq!(&into[..2], &[(1, -5), (2, 1_000_000)]);
    }
}
