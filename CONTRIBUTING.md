# Contributing to TV Ambient

Thanks for helping. TV Ambient is small on purpose: a ~326 KB APK that runs alongside video on modest TV hardware.
Most of the rules below exist to keep it that way. If you're unsure about something, open an issue first and ask.

## Ways to help

* **Report a bug.** Include your TV model and Android version, the LED controller (board and USB chip), what you
  expected and what happened, and if you can, a log: `adb logcat -s TvAmbient`. Please leave out IP addresses and
  anything personal on screen.
* **Report a TV.** "Works on my TV" is useful too: model, Android version, which capture method Home shows (shell
  screenshots or screen recording), and whether Music mode hears your sound.
* **Share measurements** from another TV, taken as described in
  [`.agents/PERFORMANCE.md`](.agents/PERFORMANCE.md#how-to-measure).
* **Send a pull request** for a bug, an effect, or an item on the
  [roadmap](.agents/TECHNICAL.md#17-roadmap-not-done-yet).

## Before you change code

Read the docs for the area you're touching. They're the source of truth and win over general Android advice:

| Doc | Covers |
|---|---|
| [`.agents/PROJECT.md`](.agents/PROJECT.md) | Repo map, build and install, reference hardware, **rules for changes**, ideas already tried and rejected |
| [`.agents/TECHNICAL.md`](.agents/TECHNICAL.md) | Architecture, capture, the Rust core and JNI, modes, music, effects |
| [`.agents/DESIGN.md`](.agents/DESIGN.md) | Every screen, navigation rules, visual tokens, remote buttons |
| [`.agents/PERFORMANCE.md`](.agents/PERFORMANCE.md) | Baseline numbers and how to measure |

Check the "Tried and rejected" list in `PROJECT.md` before proposing a new capture or rendering approach: several
obvious ideas were measured and made playback worse.

## Build and test

Docker is the only requirement.

```bash
scripts/build.sh          # release APK in dist/ (debug-signed unless KEYSTORE_DIR points at your keys)
scripts/build.sh debug    # debug APK
scripts/build.sh test     # Rust host tests: golden outputs, effects, music
scripts/build.sh shell    # a shell in the build image, repo at /src
```

On Windows: `.\scripts\build.ps1 [release|debug|test|shell]`.

To try a build on a TV, turn on USB or network debugging on the TV and install over ADB:

```bash
adb connect <tv-address>:5555
adb install -r dist/tv-ambient-1.0.0.apk
```

* The Rust tests must pass, and lint must report no issues (it runs with every release build).
* Test UI changes with a **real remote**: D-pad focus, OK, Back, and the four colour buttons. Injected key events
  don't behave like a remote, so they don't count as a test of the remote buttons.
* If you change capture, analysis or output, **measure before and after** (`PERFORMANCE.md`): CPU with `top -H`,
  video pacing with `dumpsys SurfaceFlinger --latency`, decoder drops for ExoPlayer-based players. Put the numbers
  in the pull request.

## Rules that matter

The full list is in [`.agents/PROJECT.md`](.agents/PROJECT.md#rules-for-changes). The ones reviews check first:

* **Hot path:** no allocation per frame in Rust or Kotlin, no JNI calls inside loops, and never panic across FFI
  (exports validate their inputs and return error codes).
* **Size:** the release APK stays ≤ 600 KB. No AppCompat, Material, Compose, coroutines or kotlin-reflect. A new
  dependency needs a reason and its size cost in the pull request.
* **Safety:** never ship a capture path that can crash or restart system processes. No network beyond the TV's own
  ADB port on `127.0.0.1:5555`, no analytics, and never open a microphone.
* **Stored settings are stable.** Setting keys and effect ids are saved on people's TVs. Add new effects at the end
  of the table; renumbering or removing anything needs a migration step in `SettingsSchema.migrate`.
* **UI:** five row types only, at most Home › section › sub-page, OK to edit a value, changes apply live. New
  settings go into `SettingsSchema.kt` (the key) and `SettingsSections.kt` (the row).
* **Flashing effects** are marked ⚡, stay out of the Blue rotation by default, and are capped at 3 flashes a
  second.
* **Don't commit** APKs, keystores, ADB keys, IP addresses, or screenshots with personal data. `work/` is ignored
  by git for local notes and captures.

## Adding an effect

Effects live in `core/src/effects.rs` (and `core/src/effects/`): a table entry names a function and its preset
(palette, speed, direction, sides). Many effects are presets of an existing function, which costs almost nothing.

1. Append the entry at the **end** of the table (ids are stored in settings).
2. Run `scripts/build.sh test`. The effects test renders every effect for 10 s and checks a hash of its first
   second. A new or changed effect changes `core/tests/effect_hashes.txt`: regenerate it with
   `UPDATE_EFFECT_HASHES=1 cargo test` (inside `scripts/build.sh shell`) and check that only your effect's line
   changed.
3. Add it to the effect list in `DESIGN.md` §6.2.

## Commits and pull requests

* One topic per pull request, with a short description of what changed, why, and how you tested it (TV model and
  Android version, and measurements if the change touches the hot path).
* Commit messages: `area: what changed`, for example `effects: add Ocean breeze` or `capture: retry when the
  display token is null`.
* Commits are authored by you. Don't add AI co-author trailers, and don't list an AI as a contributor. Using an
  AI assistant is fine; [`AGENTS.md`](AGENTS.md) points it at the right docs.
* Update the docs in the same pull request when you change behaviour, settings or numbers they describe.

## Licence

By contributing, you agree that your contributions are licensed under the [MIT licence](LICENSE).
