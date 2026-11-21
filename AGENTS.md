# AGENTS.md

Instructions for AI coding agents (and a quick start for people) working on **TV Ambient**, an open-source
ambient-light app for Android TV (Android 8-16, tuned for 14). It drives Adalight LED controllers over USB serial:
in Screen mode the lights follow the picture, and there are Music, Effect and Single colour modes too. The core is
**Rust** (`no_std`, built as `libtvambient.so`); the Android side is **Kotlin** with platform Views, joined over a
hand-written JNI surface. Version **1.0.0**.

## Where things are

Everything for agents lives in [`.agents/`](.agents/), so any tool can find it:

| File | Read it for |
|---|---|
| [`.agents/PROJECT.md`](.agents/PROJECT.md) | Repo map, build and install, reference hardware, **rules for changes**, ideas tried and rejected |
| [`.agents/TECHNICAL.md`](.agents/TECHNICAL.md) | Architecture: capture, analysis, the Rust core and JNI, modes, music, effects, roadmap |
| [`.agents/DESIGN.md`](.agents/DESIGN.md) | The interface: navigation rules, every screen, visual tokens, remote buttons |
| [`.agents/PERFORMANCE.md`](.agents/PERFORMANCE.md) | Measured resource use on the reference TV, and how to measure it |
| [`.agents/skills/tv-ambient-android/SKILL.md`](.agents/skills/tv-ambient-android/SKILL.md) | The agent skill: routes UI, security, ANR, APK-size and profiling work to upstream guides, with this project's overrides |

Claude Code reads this file through [`CLAUDE.md`](CLAUDE.md). The skill isn't registered with any one tool: when a
task matches its description (UI, manifest or security review, ANR, APK size, profiling), open
[`.agents/skills/tv-ambient-android/SKILL.md`](.agents/skills/tv-ambient-android/SKILL.md) and follow it. People:
see [`README.md`](README.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Build and test

Docker is the only requirement.

```bash
scripts/build.sh          # release APK in dist/ (debug-signed unless KEYSTORE_DIR points at your keys)
scripts/build.sh test     # Rust host tests (golden outputs, effects, music)
scripts/build.sh shell    # a shell in the build image, repo at /src
```

PowerShell: `.\scripts\build.ps1 [release|debug|test|shell]`.

## Rules you must not break

The full list is in [`.agents/PROJECT.md`](.agents/PROJECT.md#rules-for-changes); these are the ones that bite:

* **Read the docs first, and they win** over any skill or general Android advice.
* **Hot path:** no per-frame allocation in Rust or Kotlin, no JNI calls inside loops, never panic across FFI.
* **Size:** release APK ≤ 600 KB (about 326 KB now); no AppCompat, Material, Compose, coroutines or kotlin-reflect.
  The only bundled asset is the Inter font (subset, SIL OFL 1.1, `LICENSES/`).
* **Safety:** never ship a capture path that can crash or restart system processes; no network beyond
  `127.0.0.1:5555`; never open a microphone.
* **Settings:** keys and effect ids are stored on users' TVs. Add effects at the end; anything renumbered or
  removed needs a migration step (`SettingsSchema.migrate`). Defaults are the reference calibration listed in
  `.agents/PROJECT.md` (Northern sky, VU meter, Automatic capture, white balance 75/75/75).
* **UI:** five row types only, Home › section › sub-page at most, OK to edit a value. Remote buttons are tested
  with a real remote, not injected key events.
* **Measure** CPU (`top -H`) and video pacing (`dumpsys SurfaceFlinger --latency`) before and after any change to
  capture, analysis or output.
* **Never commit** APKs, keystores, ADB keys, IP addresses or personal screenshots (`work/` is gitignored).
* **Commits** are authored by the maintainer: `area: what changed`. **Don't add AI co-author trailers** or list an
  AI as a contributor anywhere.
