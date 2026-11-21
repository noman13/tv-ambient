//! `libtvambient.so`: thin `extern "system"` exports for the app and the capture helper (TECHNICAL.md §4.4). No logic
//! here: inputs are checked, handed to `tvambient-core`, and results returned as lengths or negative error codes.
//! `no_std` with `panic = "abort"`: a panic aborts (there is no unwinding across FFI to worry about), and nothing
//! in here or the core is written to panic on bad input.

#![no_std]

extern crate alloc;

use alloc::boxed::Box;
use core::ffi::c_void;
use core::ptr::{null_mut, NonNull};

mod env;

use env::{jbyteArray, jclass, jint, jlong, jobject, jstring, JNIEnv};
use tvambient_core::adalight::ColorOrder;
use tvambient_core::analyze::{Analyzer, FrameView, PixelFormat};
use tvambient_core::audio::{Audio, BANDS};
use tvambient_core::config;
use tvambient_core::effects;
use tvambient_core::effects::music;
use tvambient_core::frame::{self, flags, FrameHeader, TVA2_HEADER};
use tvambient_core::layout::{Layout, PixelRect};
use tvambient_core::pipeline::{Pipeline, Source};
use tvambient_core::rate;

// ------------------------------------------------------------------------------ runtime: allocator, panic

// libc and libm by name, so they're DT_NEEDED and the loader never depends on another library pulling them in.
#[link(name = "c")]
extern "C" {
    fn malloc(size: usize) -> *mut c_void;
    fn free(p: *mut c_void);
    fn posix_memalign(out: *mut *mut c_void, align: usize, size: usize) -> i32;
    fn abort() -> !;
}

#[link(name = "m")]
extern "C" {}

struct Malloc;

unsafe impl core::alloc::GlobalAlloc for Malloc {
    unsafe fn alloc(&self, l: core::alloc::Layout) -> *mut u8 {
        if l.align() <= 8 {
            malloc(l.size()) as *mut u8
        } else {
            let mut p = null_mut();
            if posix_memalign(&mut p, l.align(), l.size()) == 0 { p as *mut u8 } else { null_mut() }
        }
    }
    unsafe fn dealloc(&self, p: *mut u8, _: core::alloc::Layout) {
        free(p as *mut c_void)
    }
}

#[global_allocator]
static ALLOCATOR: Malloc = Malloc;

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    unsafe { abort() }
}

/// Referenced by the precompiled `core`/`alloc` (built with unwinding); never called, since panics abort.
#[no_mangle]
pub extern "C" fn rust_eh_personality() {}

// ------------------------------------------------------------------------------ errors and JNI helpers

const E_HANDLE: jint = -1;
const E_BUFFER: jint = -2;
const E_CONFIG: jint = -3;
const E_LOCK: jint = -4;
const E_FORMAT: jint = -5;

/// Copies a small `byte[]` (settings, ≤ 64 bytes) into a stack buffer.
unsafe fn read_array(env: *mut JNIEnv, a: jbyteArray, buf: &mut [u8; 64]) -> Option<usize> {
    let len = env::array_length(env, a)?;
    if len > buf.len() || !env::byte_array_region(env, a, len, buf.as_mut_ptr()) {
        return None;
    }
    Some(len)
}

/// A direct `ByteBuffer`'s memory.
unsafe fn direct<'a>(env: *mut JNIEnv, b: jobject) -> Option<&'a mut [u8]> {
    env::direct_buffer(env, b)
}

fn ok_env(env: *mut JNIEnv) -> bool {
    unsafe { env::valid(env) }
}

unsafe fn handle<'a, T>(h: jlong) -> Option<&'a mut T> {
    NonNull::new(h as usize as *mut T).map(|mut p| p.as_mut())
}

fn into_handle<T>(v: T) -> jlong {
    Box::into_raw(Box::new(v)) as usize as jlong
}

unsafe fn free_handle<T>(h: jlong) {
    if h != 0 {
        drop(Box::from_raw(h as usize as *mut T));
    }
}

// ------------------------------------------------------------------------------ version

/// `Native.version()`: the core's version string.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_version(env: *mut JNIEnv, _: jclass) -> jstring {
    if !ok_env(env) {
        return null_mut();
    }
    env::new_string_utf(env, tvambient_core::VERSION_C.as_ptr().cast())
}

// ------------------------------------------------------------------------------ analyser (capture helper)

struct AnalyzerHandle {
    analyzer: Analyzer,
    leds: usize,
}

/// `analyzerNew(config: ByteArray): Long` (config: `tvambient_core::config::analyzer`); 0 on bad config.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_analyzerNew(env: *mut JNIEnv, _: jclass, cfg: jbyteArray) -> jlong {
    let mut buf = [0u8; 64];
    if !ok_env(env) {
        return 0;
    }
    let Some(n) = read_array(env, cfg, &mut buf) else { return 0 };
    let Some((l, a)) = config::analyzer(&buf[..n]) else { return 0 };
    let layout = Layout::new(&l);
    into_handle(AnalyzerHandle { leds: layout.len(), analyzer: Analyzer::new(a, &layout) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_analyzerFree(_: *mut JNIEnv, _: jclass, h: jlong) {
    free_handle::<AnalyzerHandle>(h)
}

/// `analyzerSetMask(h, left, top, right, bottom)` in capture pixels; an empty rectangle clears it.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_analyzerSetMask(
    _: *mut JNIEnv, _: jclass, h: jlong, l: jint, t: jint, r: jint, b: jint,
) -> jint {
    let Some(a) = handle::<AnalyzerHandle>(h) else { return E_HANDLE };
    let c = |v: jint| v.clamp(0, u16::MAX as jint) as u16;
    a.analyzer.set_mask(Some(PixelRect { x0: c(l), y0: c(t), x1: c(r), y1: c(b) }));
    0
}

/// Analyses one frame and writes a `TVA2` record into `out`; returns its length.
unsafe fn analyze_into(a: &mut AnalyzerHandle, f: &FrameView, out: &mut [u8], seq: jint, ns: jlong, us: jint) -> jint {
    let len = TVA2_HEADER + 3 * a.leds;
    if out.len() < len {
        return E_BUFFER;
    }
    let r = a.analyzer.analyze(f, &mut out[TVA2_HEADER..len]);
    let mut fl = 0;
    if r.bars.any() {
        fl |= flags::BARS;
    }
    if r.whole_black {
        fl |= flags::WHOLE_BLACK;
    }
    if r.held_back {
        fl |= flags::HELD_BACK;
    }
    let b = r.bars;
    let h = FrameHeader {
        flags: fl,
        leds: a.leds as u16,
        seq: seq as u32,
        capture_ns: ns as u64,
        capture_us: us as u32,
        content: [b.left, b.top, f.width - b.right.min(f.width), f.height - b.bottom.min(f.height)],
    };
    if frame::write_tva2_header(&h, out).is_none() {
        return E_BUFFER;
    }
    len as jint
}

/// `analyzeDirect(h, frame: ByteBuffer, width, height, stride, format (0 RGB, 1 RGBA), out: ByteBuffer, seq,
/// captureNs, captureUs): Int`
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_analyzeDirect(
    env: *mut JNIEnv, _: jclass, h: jlong, frame: jobject, w: jint, ht: jint, stride: jint, format: jint, out: jobject,
    seq: jint, ns: jlong, us: jint,
) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(a) = handle::<AnalyzerHandle>(h) else { return E_HANDLE };
    let (Some(src), Some(dst)) = (direct(env, frame), direct(env, out)) else { return E_BUFFER };
    let fmt = if format == 1 { PixelFormat::Rgba8888 } else { PixelFormat::Rgb888 };
    if !(1..=4096).contains(&w) || !(1..=4096).contains(&ht) || stride < 0 {
        return E_BUFFER;
    }
    let Some(f) = FrameView::new(src, w as u16, ht as u16, stride as usize, fmt) else { return E_BUFFER };
    analyze_into(a, &f, dst, seq, ns, us)
}

#[repr(C)]
struct AHardwareBufferDesc {
    width: u32,
    height: u32,
    layers: u32,
    format: u32,
    usage: u64,
    stride: u32,
    rfu0: u32,
    rfu1: u64,
}

#[link(name = "android")]
#[link(name = "nativewindow")]
extern "C" {
    fn AHardwareBuffer_fromHardwareBuffer(env: *mut JNIEnv, hb: jobject) -> *mut c_void;
    fn AHardwareBuffer_describe(b: *const c_void, desc: *mut AHardwareBufferDesc);
    fn AHardwareBuffer_lock(b: *mut c_void, usage: u64, fence: i32, rect: *const c_void, out: *mut *mut c_void) -> i32;
    fn AHardwareBuffer_unlock(b: *mut c_void, fence: *mut i32) -> i32;
}

const AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN: u64 = 3;
const AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM: u32 = 1;
const AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM: u32 = 2;
const AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM: u32 = 3;

/// `analyzeHardwareBuffer(h, hb: HardwareBuffer, out: ByteBuffer, seq, captureNs, captureUs): Int` (API 29+
/// capture paths): locks the buffer for CPU reading, no copy.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_analyzeHardwareBuffer(
    env: *mut JNIEnv, _: jclass, h: jlong, hb: jobject, out: jobject, seq: jint, ns: jlong, us: jint,
) -> jint {
    if !ok_env(env) || hb.is_null() {
        return E_BUFFER;
    }
    let Some(a) = handle::<AnalyzerHandle>(h) else { return E_HANDLE };
    let Some(dst) = direct(env, out) else { return E_BUFFER };
    let buf = AHardwareBuffer_fromHardwareBuffer(env, hb);
    if buf.is_null() {
        return E_BUFFER;
    }
    let mut d = AHardwareBufferDesc { width: 0, height: 0, layers: 0, format: 0, usage: 0, stride: 0, rfu0: 0, rfu1: 0 };
    AHardwareBuffer_describe(buf, &mut d);
    let (fmt, bpp) = match d.format {
        AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM | AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM => (PixelFormat::Rgba8888, 4),
        AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM => (PixelFormat::Rgb888, 3),
        _ => return E_FORMAT,
    };
    if d.width == 0 || d.height == 0 || d.width > 4096 || d.height > 4096 {
        return E_FORMAT;
    }
    let mut addr: *mut c_void = null_mut();
    if AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, core::ptr::null(), &mut addr) != 0 || addr.is_null() {
        return E_LOCK;
    }
    let stride = d.stride as usize * bpp;
    let len = stride * d.height as usize;
    let src = core::slice::from_raw_parts(addr as *const u8, len);
    let r = match FrameView::new(src, d.width as u16, d.height as u16, stride, fmt) {
        Some(f) => analyze_into(a, &f, dst, seq, ns, us),
        None => E_BUFFER,
    };
    AHardwareBuffer_unlock(buf, null_mut());
    r
}

#[repr(C)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(env: *mut JNIEnv, bmp: jobject, info: *mut AndroidBitmapInfo) -> i32;
    fn AndroidBitmap_lockPixels(env: *mut JNIEnv, bmp: jobject, addr: *mut *mut c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut JNIEnv, bmp: jobject) -> i32;
}

const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

/// `analyzeBitmap(h, bitmap: Bitmap, out: ByteBuffer, seq, captureNs, captureUs): Int` (API 26-28 capture paths;
/// the Bitmap must be a software ARGB_8888 one).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_analyzeBitmap(
    env: *mut JNIEnv, _: jclass, h: jlong, bmp: jobject, out: jobject, seq: jint, ns: jlong, us: jint,
) -> jint {
    if !ok_env(env) || bmp.is_null() {
        return E_BUFFER;
    }
    let Some(a) = handle::<AnalyzerHandle>(h) else { return E_HANDLE };
    let Some(dst) = direct(env, out) else { return E_BUFFER };
    let mut info = AndroidBitmapInfo { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
    if AndroidBitmap_getInfo(env, bmp, &mut info) != 0 {
        return E_BUFFER;
    }
    if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 || info.width > 4096 || info.height > 4096 {
        return E_FORMAT;
    }
    let mut addr: *mut c_void = null_mut();
    if AndroidBitmap_lockPixels(env, bmp, &mut addr) != 0 || addr.is_null() {
        return E_LOCK;
    }
    let len = info.stride as usize * info.height as usize;
    let src = core::slice::from_raw_parts(addr as *const u8, len);
    let r = match FrameView::new(src, info.width as u16, info.height as u16, info.stride as usize, PixelFormat::Rgba8888) {
        Some(f) => analyze_into(a, &f, dst, seq, ns, us),
        None => E_BUFFER,
    };
    AndroidBitmap_unlockPixels(env, bmp);
    r
}

// ------------------------------------------------------------------------------ pipeline (app)

/// `pipelineNew(config: ByteArray): Long` (config: `tvambient_core::config::pipeline`); 0 on bad config.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineNew(env: *mut JNIEnv, _: jclass, cfg: jbyteArray) -> jlong {
    let mut buf = [0u8; 64];
    if !ok_env(env) {
        return 0;
    }
    let Some(n) = read_array(env, cfg, &mut buf) else { return 0 };
    let Some((l, p, smoothing, order)) = config::pipeline(&buf[..n]) else { return 0 };
    into_handle(Pipeline::new(&l, &p, smoothing, ColorOrder::from_u8(order)))
}

#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineFree(_: *mut JNIEnv, _: jclass, h: jlong) {
    free_handle::<Pipeline>(h)
}

/// `pipelineSetPicture(h, picture: ByteArray, smoothing: Int): Int`
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineSetPicture(
    env: *mut JNIEnv, _: jclass, h: jlong, cfg: jbyteArray, smoothing: jint,
) -> jint {
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let mut buf = [0u8; 64];
    if !ok_env(env) {
        return E_CONFIG;
    }
    let Some(n) = read_array(env, cfg, &mut buf) else { return E_CONFIG };
    let Some(c) = config::picture(&buf[..n]) else { return E_CONFIG };
    p.set_picture(&c, smoothing.clamp(1, 10) as u8);
    0
}

/// `pipelineSetSource(h, kind, arg, params: ByteArray?, nowNs): Int`. kind: 0 off, 1 screen, 2 effect (arg =
/// id, params = effect params), 3 single colour (params), 4 music (params = music config).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineSetSource(
    env: *mut JNIEnv, _: jclass, h: jlong, kind: jint, arg: jint, params: jbyteArray, now: jlong,
) -> jint {
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let mut buf = [0u8; 64];
    let n = if ok_env(env) { read_array(env, params, &mut buf).unwrap_or(0) } else { 0 };
    let b = &buf[..n];
    let source = match kind {
        0 => Source::Off,
        1 => Source::Screen,
        2 if (arg as usize) < effects::count() => match config::params(b) {
            Some(prm) => Source::Effect { id: arg as u16, params: prm },
            None => return E_CONFIG,
        },
        3 => match config::params(b) {
            Some(prm) => Source::Single { params: prm },
            None => return E_CONFIG,
        },
        4 => match config::music(b) {
            Some((effect, params, delay_ms)) => Source::Music { effect, params, delay_ms },
            None => return E_CONFIG,
        },
        _ => return E_CONFIG,
    };
    p.set_source(source, now.max(0) as u64);
    0
}

/// `pipelineFeed(h, frame: ByteBuffer, length: Int, nowNs): Int`: a `TVA2` record from the helper (at the
/// buffer's start).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineFeed(
    env: *mut JNIEnv, _: jclass, h: jlong, buf: jobject, len: jint, now: jlong,
) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let Some(b) = direct(env, buf) else { return E_BUFFER };
    let b = &b[..(len.max(0) as usize).min(b.len())];
    let Some((hdr, total)) = frame::read_tva2_header(b) else { return E_FORMAT };
    if b.len() < total {
        return E_BUFFER;
    }
    if p.feed(&hdr, &b[TVA2_HEADER..total], now.max(0) as u64) { 0 } else { E_CONFIG }
}

/// `pipelineRender(h, nowNs, audio: Long, out: ByteBuffer): Int`: an Adalight packet's length, or 0 if nothing
/// changed. `audio` is an `audioNew` handle or 0.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineRender(
    env: *mut JNIEnv, _: jclass, h: jlong, now: jlong, audio: jlong, out: jobject,
) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    let a = handle::<Audio>(audio).map(|a| &*a);
    p.render(now.max(0) as u64, a, o) as jint
}

/// `pipelineLastPacket(h, out: ByteBuffer): Int` (the 500 ms keep-alive).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineLastPacket(env: *mut JNIEnv, _: jclass, h: jlong, out: jobject) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    p.last_packet(o) as jint
}

/// `pipelineShown(h, out: ByteBuffer): Int`: the colours shown last (3 bytes per LED), for the UI preview.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_pipelineShown(env: *mut JNIEnv, _: jclass, h: jlong, out: jobject) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    let s = p.shown();
    if o.len() < s.len() {
        return E_BUFFER;
    }
    o[..s.len()].copy_from_slice(s);
    s.len() as jint
}

/// `layoutGeometry(h pipeline, out: ByteBuffer): Int`: 3 bytes per LED in strip
/// order: edge (0 top, 1 right, 2 bottom, 3 left), then the position along that side (u16 LE, 0..65535: top and bottom
/// left to right, sides top to bottom). Returns the bytes written.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_layoutGeometry(env: *mut JNIEnv, _: jclass, h: jlong, out: jobject) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    let leds = &p.layout().leds;
    if o.len() < leds.len() * 3 {
        return E_BUFFER;
    }
    for (i, led) in leds.iter().enumerate() {
        o[i * 3] = led.edge as u8;
        o[i * 3 + 1..i * 3 + 3].copy_from_slice(&led.along.to_le_bytes());
    }
    (leds.len() * 3) as jint
}

/// `effectCount(): Int`
#[no_mangle]
pub extern "system" fn Java_io_github_noman13_tvambient_Native_effectCount(_: *mut JNIEnv, _: jclass) -> jint {
    effects::count() as jint
}

/// `effectName(id): String?`: the effect's English name (DESIGN.md §6.2), or null for a bad id.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_effectName(env: *mut JNIEnv, _: jclass, id: jint) -> jstring {
    let Some(fx) = usize::try_from(id).ok().and_then(|i| effects::EFFECTS.get(i)) else { return null_mut() };
    if !ok_env(env) {
        return null_mut();
    }
    // NUL-terminated copy for NewStringUTF (names are short; longer ones are cut at a char boundary)
    let mut buf = [0u8; 64];
    let mut n = fx.name.len().min(buf.len() - 1);
    while !fx.name.is_char_boundary(n) {
        n -= 1;
    }
    buf[..n].copy_from_slice(&fx.name.as_bytes()[..n]);
    env::new_string_utf(env, buf.as_ptr().cast())
}

/// `effectFlags(id): Int`: bit 0 flashes (⚡), bit 1 in the Blue rotation by default, bits 8-15 the group
/// (0 Calm, 1 Nature, 2 Lively, 3 Party, 4 Seasonal, 5 Setup); −3 for a bad id.
#[no_mangle]
pub extern "system" fn Java_io_github_noman13_tvambient_Native_effectFlags(_: *mut JNIEnv, _: jclass, id: jint) -> jint {
    let Some(fx) = usize::try_from(id).ok().and_then(|i| effects::EFFECTS.get(i)) else { return E_CONFIG };
    (fx.flashes as jint) | ((fx.in_rotation_by_default() as jint) << 1) | ((fx.group as jint) << 8)
}

/// `effectPreview(h pipeline, id, tMs, params: ByteArray, out: ByteBuffer): Int`: an effect's colours for its UI
/// tile, on the pipeline's layout (3 bytes per LED).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_effectPreview(
    env: *mut JNIEnv, _: jclass, h: jlong, id: jint, t_ms: jint, params: jbyteArray, out: jobject,
) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let mut buf = [0u8; 64];
    let Some(n) = read_array(env, params, &mut buf) else { return E_CONFIG };
    let Some(prm) = config::params(&buf[..n]) else { return E_CONFIG };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    if id < 0 || !effects::render(id as usize, t_ms.max(0) as u32, &prm, p.layout(), o) {
        return E_CONFIG;
    }
    (p.leds() * 3) as jint
}

/// `musicCount(): Int`
#[no_mangle]
pub extern "system" fn Java_io_github_noman13_tvambient_Native_musicCount(_: *mut JNIEnv, _: jclass) -> jint {
    music::MUSIC_EFFECTS.len() as jint
}

/// `musicName(id): String?`: the music effect's name (DESIGN.md §10.2), or null for a bad id.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_musicName(env: *mut JNIEnv, _: jclass, id: jint) -> jstring {
    let Some(&(_, name, _)) = usize::try_from(id).ok().and_then(|i| music::MUSIC_EFFECTS.get(i)) else { return null_mut() };
    if !ok_env(env) {
        return null_mut();
    }
    let mut buf = [0u8; 64];
    let mut n = name.len().min(buf.len() - 1);
    while !name.is_char_boundary(n) {
        n -= 1;
    }
    buf[..n].copy_from_slice(&name.as_bytes()[..n]);
    env::new_string_utf(env, buf.as_ptr().cast())
}

/// `musicFlags(id): Int`: bit 0 flashes (⚡); −3 for a bad id.
#[no_mangle]
pub extern "system" fn Java_io_github_noman13_tvambient_Native_musicFlags(_: *mut JNIEnv, _: jclass, id: jint) -> jint {
    let Some(&(_, _, flashes)) = usize::try_from(id).ok().and_then(|i| music::MUSIC_EFFECTS.get(i)) else { return E_CONFIG };
    flashes as jint
}

/// `musicPreview(h pipeline, id, tMs, music: ByteArray, out: ByteBuffer): Int`: a music effect's colours for its
/// tile, driven by the built-in 120 BPM test rhythm (`music::test_rhythm`).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_musicPreview(
    env: *mut JNIEnv, _: jclass, h: jlong, id: jint, t_ms: jint, cfg: jbyteArray, out: jobject,
) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(p) = handle::<Pipeline>(h) else { return E_HANDLE };
    let Some(&(fx, _, _)) = usize::try_from(id).ok().and_then(|i| music::MUSIC_EFFECTS.get(i)) else { return E_CONFIG };
    let mut buf = [0u8; 64];
    let Some(n) = read_array(env, cfg, &mut buf) else { return E_CONFIG };
    let Some((_, params, _)) = config::music(&buf[..n]) else { return E_CONFIG };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    let t = t_ms.max(0) as u32;
    let mut beats = [0u32; 8];
    let (frame, nb) = music::test_rhythm(t, &mut beats);
    if !music::render(fx, t, &frame, &beats[..nb], &params, p.layout(), o) {
        return E_BUFFER;
    }
    (p.leds() * 3) as jint
}

// ------------------------------------------------------------------------------ audio

/// `audioNew(config: ByteArray): Long` (config: `tvambient_core::config::audio`); 0 on bad config.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_audioNew(env: *mut JNIEnv, _: jclass, cfg: jbyteArray) -> jlong {
    let mut buf = [0u8; 64];
    if !ok_env(env) {
        return 0;
    }
    let Some(n) = read_array(env, cfg, &mut buf) else { return 0 };
    let Some(c) = config::audio(&buf[..n]) else { return 0 };
    into_handle(Audio::new(c))
}

#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_audioFree(_: *mut JNIEnv, _: jclass, h: jlong) {
    free_handle::<Audio>(h)
}

/// `audioFeedVisualizer(h, fft: ByteArray, tMs): Int` (source A, `Visualizer.getFft`, capture size ≤ 1024).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_audioFeedVisualizer(
    env: *mut JNIEnv, _: jclass, h: jlong, fft: jbyteArray, t_ms: jint,
) -> jint {
    let Some(a) = handle::<Audio>(h) else { return E_HANDLE };
    if !ok_env(env) || fft.is_null() {
        return E_BUFFER;
    }
    let mut buf = [0u8; 1024];
    let Some(len) = env::array_length(env, fft) else { return E_BUFFER };
    if len < 4 || len > buf.len() || !env::byte_array_region(env, fft, len, buf.as_mut_ptr()) {
        return E_BUFFER;
    }
    // tMs is the low 32 bits of the app's millisecond clock (as `pipelineRender`'s nowNs / 1e6 as u32): keep the bits,
    // don't clamp, or a TV up for more than 24.8 days would feed time 0 forever
    a.feed_visualizer(&buf[..len], t_ms as u32);
    0
}

/// `audioFeedPcm(h, pcm: ByteBuffer, samples: Int, tMs): Int` (sources B/C: mono 16-bit little-endian).
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_audioFeedPcm(
    env: *mut JNIEnv, _: jclass, h: jlong, pcm: jobject, samples: jint, t_ms: jint,
) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(a) = handle::<Audio>(h) else { return E_HANDLE };
    let Some(b) = direct(env, pcm) else { return E_BUFFER };
    let n = (samples.max(0) as usize).min(b.len() / 2);
    let mut chunk = [0i16; 256];
    let mut steps = 0;
    let mut done = 0;
    while done < n {
        let k = (n - done).min(chunk.len());
        for (i, s) in chunk[..k].iter_mut().enumerate() {
            let j = 2 * (done + i);
            *s = i16::from_le_bytes([b[j], b[j + 1]]);
        }
        let t = (t_ms as u32).wrapping_add((done as u64 * 1000 / 48_000) as u32);
        steps += a.feed_pcm(&chunk[..k], t);
        done += k;
    }
    steps as jint
}

/// `audioSnapshot(h, out: ByteBuffer): Int`: for the Music status card and the stats overlay, 16 bytes: 12 band
/// levels (0..255), level dB (i8), BPM (u8), beat (0/1), reserved.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_audioSnapshot(env: *mut JNIEnv, _: jclass, h: jlong, out: jobject) -> jint {
    if !ok_env(env) {
        return E_BUFFER;
    }
    let Some(a) = handle::<Audio>(h) else { return E_HANDLE };
    let Some(o) = direct(env, out) else { return E_BUFFER };
    if o.len() < 16 {
        return E_BUFFER;
    }
    let f = a.latest().unwrap_or_default();
    for k in 0..BANDS {
        o[k] = (f.bands[k].clamp(0.0, 1.0) * 255.0) as u8;
    }
    o[12] = (f.level_db.clamp(-96.0, 0.0) as i8) as u8;
    o[13] = f.bpm.clamp(0.0, 255.0) as u8;
    o[14] = f.beat as u8;
    o[15] = 0;
    16
}

// ------------------------------------------------------------------------------ capture rate (E4b)

/// `adaptiveNew(start): Long`: the adaptive capture rate (`rate::Adaptive`); free with `adaptiveFree`.
#[no_mangle]
pub extern "system" fn Java_io_github_noman13_tvambient_Native_adaptiveNew(_: *mut JNIEnv, _: jclass, start: jint) -> jlong {
    into_handle(rate::Adaptive::new(start.clamp(1, 60) as u8))
}

#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_adaptiveFree(_: *mut JNIEnv, _: jclass, h: jlong) {
    free_handle::<rate::Adaptive>(h)
}

/// `adaptiveStep(h, lateFrames, captureMs, systemCpuPct, memAvailMb, videoFpsX10, dtMs, ceiling): Int`: the new rate.
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_noman13_tvambient_Native_adaptiveStep(
    _: *mut JNIEnv, _: jclass, h: jlong, late: jint, capture_ms: jint, cpu: jint, mem: jint, video_fps_x10: jint, dt_ms: jint, ceiling: jint,
) -> jint {
    let Some(a) = handle::<rate::Adaptive>(h) else { return E_HANDLE };
    let p = rate::Pressure {
        late_frames: late.clamp(0, u16::MAX as jint) as u16,
        capture_ms: capture_ms.clamp(0, u16::MAX as jint) as u16,
        system_cpu_pct: cpu.clamp(0, 100) as u8,
        mem_avail_mb: mem.clamp(0, u16::MAX as jint) as u16,
        video_fps_x10: video_fps_x10.clamp(0, 2400) as u16,
    };
    a.step(&p, dt_ms.max(0) as u32, ceiling.clamp(1, 60) as u8, &rate::RateTable::default()) as jint
}

/// `rateChoose(width, height, fpsMilli, path (0 hardware plane, 1 window, 2 unknown, -1 no video), captureMs): Int`
#[no_mangle]
pub extern "system" fn Java_io_github_noman13_tvambient_Native_rateChoose(
    _: *mut JNIEnv, _: jclass, w: jint, h: jint, fps_milli: jint, path: jint, capture_ms: jint,
) -> jint {
    let c = rate::Content {
        width: w.clamp(0, u16::MAX as jint) as u16,
        height: h.clamp(0, u16::MAX as jint) as u16,
        fps_milli: fps_milli.max(0) as u32,
        path: match path {
            0 => rate::VideoPath::HardwarePlane,
            1 => rate::VideoPath::Window,
            _ => rate::VideoPath::Unknown,
        },
    };
    let content = if path < 0 { None } else { Some(&c) };
    let ms = if capture_ms > 0 { Some(capture_ms as u32) } else { None };
    rate::choose(content, ms, &rate::RateTable::default()) as jint
}
