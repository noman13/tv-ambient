//! TV Ambient core: edge analysis, colour, smoothing, effects, audio and Adalight output (TECHNICAL.md §4-§15).
//! Pure Rust, `no_std` + `alloc`, no Android dependencies: all of it is tested on the host. Memory is allocated
//! when things are created, never per frame.

#![cfg_attr(not(test), no_std)]

extern crate alloc;

pub mod adalight;
pub mod analyze;
pub mod audio;
pub mod color;
pub mod config;
pub mod effects;
pub mod frame;
pub mod layout;
pub mod math;
pub mod modes;
pub mod pipeline;
pub mod rate;
pub mod smooth;

/// The core's version, as in `Cargo.toml`.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// [`VERSION`] with a trailing NUL, for handing to C and JNI without allocating.
pub const VERSION_C: &str = concat!(env!("CARGO_PKG_VERSION"), "\0");

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_semver_and_nul_terminated() {
        assert_eq!(VERSION.split('.').count(), 3);
        assert!(VERSION.split('.').all(|p| p.parse::<u32>().is_ok()));
        assert_eq!(VERSION_C, format!("{VERSION}\0"));
    }
}
