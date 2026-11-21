//! The app's output pipeline (TECHNICAL.md §4, §7 E6, §13): one call per LED tick turns the current source (screen
//! captures, an effect, a single colour or music) into an Adalight packet, with the picture settings, smoothing,
//! interpolation between captures, the flash cap and a cross-fade whenever the source changes. All buffers are
//! allocated in [`Pipeline::new`].

use alloc::vec;
use alloc::vec::Vec;

use crate::adalight::{packet_len, write_packet, ColorOrder};
use crate::audio::Audio;
use crate::color::{Picture, PictureConfig};
use crate::effects::music::{self, MusicEffect, MusicParams};
use crate::effects::{self, FlashCap, Params, EFFECTS};
use crate::frame::{flags, FrameHeader};
use crate::layout::{Layout, LayoutConfig};
use crate::modes::{CrossFade, FADE_NS};
use crate::smooth::{Interpolator, Smoother};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Source {
    /// LEDs dark
    Off,
    /// follow the screen captures
    Screen,
    Effect { id: u16, params: Params },
    Single { params: Params },
    Music { effect: MusicEffect, params: MusicParams, delay_ms: i16 },
}

pub struct Pipeline {
    layout: Layout,
    picture: Picture,
    order: ColorOrder,
    smoother: Smoother,
    interp: Interpolator,
    source: Source,
    old: Source,
    fade: CrossFade,
    /// a fade into Screen waits for the first capture
    fade_pending: bool,
    have_capture: bool,
    t0_ns: Option<u64>,
    cur: Vec<u8>,
    prev: Vec<u8>,
    scratch: Vec<u8>,
    shown: Vec<u8>,
    flash: FlashCap,
}

impl Pipeline {
    pub fn new(layout: &LayoutConfig, picture: &PictureConfig, smoothing: u8, order: ColorOrder) -> Pipeline {
        let layout = Layout::new(layout);
        let ch = layout.len() * 3;
        Pipeline {
            picture: Picture::new(picture),
            order,
            smoother: Smoother::new(ch, smoothing),
            interp: Interpolator::new(ch),
            source: Source::Off,
            old: Source::Off,
            fade: CrossFade::default(),
            fade_pending: false,
            have_capture: false,
            t0_ns: None,
            cur: vec![0; ch],
            prev: vec![0; ch],
            scratch: vec![0; ch],
            shown: vec![0; ch],
            flash: FlashCap::default(),
            layout,
        }
    }

    pub fn leds(&self) -> usize {
        self.layout.len()
    }

    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    /// Bytes needed for [`Pipeline::render`]'s output.
    pub fn packet_len(&self) -> usize {
        packet_len(self.layout.len())
    }

    pub fn set_picture(&mut self, c: &PictureConfig, smoothing: u8) {
        self.picture = Picture::new(c);
        self.smoother.set(smoothing);
    }

    pub fn source(&self) -> Source {
        self.source
    }

    /// Switches source with a 300 ms cross-fade (into Screen: from the first capture on).
    pub fn set_source(&mut self, s: Source, now_ns: u64) {
        if s == self.source {
            return;
        }
        self.old = self.source;
        self.source = s;
        if s == Source::Screen && !self.have_capture {
            self.fade_pending = true;
        } else {
            self.fade.start(now_ns, FADE_NS);
            self.fade_pending = false;
        }
        if s != Source::Screen {
            self.have_capture = false;
        }
    }

    /// A `TVA2` frame from the helper (header already parsed). Frames held back by the black guard or failed
    /// captures are ignored. Returns false if the LED count doesn't match.
    pub fn feed(&mut self, h: &FrameHeader, rgb: &[u8], now_ns: u64) -> bool {
        if h.leds as usize != self.layout.len() || rgb.len() != self.cur.len() {
            return false;
        }
        if h.flags & (flags::HELD_BACK | flags::CAPTURE_FAILED) != 0 {
            return true;
        }
        self.scratch.copy_from_slice(rgb);
        self.picture.apply_capture(&mut self.scratch);
        self.interp.push(now_ns, &self.scratch);
        if !self.have_capture {
            self.have_capture = true;
            if self.fade_pending {
                self.fade.start(now_ns, FADE_NS);
                self.fade_pending = false;
            }
        }
        true
    }

    fn effect_ms(&mut self, now_ns: u64) -> u32 {
        let t0 = *self.t0_ns.get_or_insert(now_ns);
        (now_ns.saturating_sub(t0) / 1_000_000) as u32
    }

    /// Renders `s` at `now_ns` into `out` (colours after the picture tables). Returns whether `s` is a flashing one.
    fn draw(&mut self, s: Source, now_ns: u64, audio: Option<&Audio>, out_is_prev: bool) -> bool {
        let t_ms = self.effect_ms(now_ns);
        let buf = if out_is_prev { &mut self.prev } else { &mut self.cur };
        match s {
            Source::Off => {
                buf.fill(0);
                false
            }
            Source::Screen => {
                if !self.interp.render(now_ns, buf) {
                    buf.fill(0);
                }
                false
            }
            Source::Effect { id, params } => {
                if !effects::render(id as usize, t_ms, &params, &self.layout, buf) {
                    buf.fill(0);
                }
                self.picture.apply_output(buf);
                EFFECTS.get(id as usize).is_some_and(|e| e.flashes)
            }
            Source::Single { params } => {
                effects::single_colour(&params, 1.0, &self.layout, buf);
                self.picture.apply_output(buf);
                false
            }
            Source::Music { effect, params, delay_ms } => {
                let now_ms = (now_ns / 1_000_000) as u32;
                let at = (now_ms as i64 - delay_ms.max(0) as i64).max(0) as u32;
                let mut beats = [0u32; 8];
                let (frame, nb) = match audio {
                    Some(a) => (a.frame_at(at).unwrap_or_default(), a.recent_beats(&mut beats)),
                    None => (Default::default(), 0),
                };
                // shift beat times by the delay so effects see them when the sound is heard
                for b in beats[..nb].iter_mut() {
                    *b = (*b as i64 + delay_ms.max(0) as i64) as u32;
                }
                music::render(effect, now_ms, &frame, &beats[..nb], &params, &self.layout, buf);
                self.picture.apply_output(buf);
                effect == MusicEffect::StrobeBeat
            }
        }
    }

    /// One LED tick: writes an Adalight packet into `out` and returns its length, or 0 when nothing changed (the
    /// caller still sends a keep-alive every 500 ms with [`Pipeline::last_packet`]).
    pub fn render(&mut self, now_ns: u64, audio: Option<&Audio>, out: &mut [u8]) -> usize {
        if out.len() < self.packet_len() {
            return 0;
        }
        let flashes = self.draw(self.source, now_ns, audio, false);
        let fading = self.fade.active(now_ns) || self.fade_pending;
        if fading {
            let old = self.old;
            self.draw(old, now_ns, audio, true);
            if self.fade_pending {
                // waiting for the first capture: keep showing the old source
                self.cur.copy_from_slice(&self.prev);
            } else {
                let (prev, cur) = (&self.prev, &mut self.cur);
                self.fade.blend(now_ns, prev, cur);
            }
        }
        if flashes {
            let t = (now_ns / 1_000_000) as u32;
            self.flash.apply(t, &self.shown, &mut self.cur);
        }
        let smooth = self.source == Source::Screen && !fading;
        let changed = self.smoother.step(&self.cur, smooth);
        self.shown.copy_from_slice(self.smoother.output());
        if changed { write_packet(&self.shown, self.order, out) } else { 0 }
    }

    /// The last packet again (for the 500 ms keep-alive).
    pub fn last_packet(&self, out: &mut [u8]) -> usize {
        write_packet(&self.shown, self.order, out)
    }

    /// The colours shown last (3 bytes per LED), for the UI preview.
    pub fn shown(&self) -> &[u8] {
        &self.shown
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pipe() -> Pipeline {
        Pipeline::new(&LayoutConfig::default(), &PictureConfig::default(), 1, ColorOrder::Rgb)
    }

    #[test]
    fn screen_capture_reaches_the_packet() {
        let mut p = pipe();
        p.set_source(Source::Screen, 0);
        let rgb = vec![255u8; 184 * 3];
        let h = FrameHeader { leds: 184, ..FrameHeader::default() };
        assert!(p.feed(&h, &rgb, 1_000_000));
        let mut out = vec![0u8; p.packet_len()];
        let n = p.render(400_000_000, None, &mut out);
        assert_eq!(n, 6 + 184 * 3);
        assert_eq!(&out[..3], b"Ada");
        assert_eq!(out[6], 89, "white at brightness 35%");
        assert_eq!(p.render(400_000_000, None, &mut out), 0, "unchanged");
    }

    #[test]
    fn switching_source_never_goes_dark() {
        let mut p = pipe();
        let warm = Source::Effect { id: 10, params: Params::default() }; // Warm white
        let red = Source::Single { params: Params { hue: 0, sat: 100, ..Params::default() } };
        let mut out = vec![0u8; p.packet_len()];
        p.set_source(warm, 0);
        p.render(500_000_000, None, &mut out);
        p.set_source(red, 1_000_000_000);
        let mut min_mean = u32::MAX;
        for k in 0..40u64 {
            p.render(1_000_000_000 + k * 10_000_000, None, &mut out);
            min_mean = min_mean.min(effects::mean(p.shown()));
        }
        assert!(min_mean >= 25, "the cross-fade dipped to {min_mean}");
        assert_eq!(&p.shown()[..3], &[89, 0, 0]);
    }

    #[test]
    fn into_screen_waits_for_the_first_capture() {
        let mut p = pipe();
        let mut out = vec![0u8; p.packet_len()];
        p.set_source(Source::Single { params: Params::default() }, 0);
        p.render(500_000_000, None, &mut out);
        let before = p.shown()[0];
        p.set_source(Source::Screen, 600_000_000);
        p.render(800_000_000, None, &mut out);
        assert_eq!(p.shown()[0], before, "old output keeps running until a capture arrives");
    }
}
