---
name: tv-ambient-android
description: Android TV engineering for TV Ambient (Kotlin platform Views + a Rust core over JNI) — TV UI and D-pad focus, general UI quality, ANR audits, permission and intent/exported-component security reviews, R8/APK size, and on-device profiling (Perfetto, top -H). Use for any UI screen, any review of AndroidManifest.xml or exported components, any ANR or jank question, any APK-size work, and any CPU/GPU cost measurement on the TV. One skill that routes to seven upstream guides and carries the project's overrides and lessons.
---

# TV Ambient: Android engineering

This skill combines seven upstream skills (under `references/`, unchanged except that each `SKILL.md` became
`GUIDE.md`; sources and licences in [`SOURCES.md`](SOURCES.md)) with how this project applies them. **The repo's
docs win over any guide**: `.agents/DESIGN.md` for the interface, `.agents/TECHNICAL.md` for architecture and
budgets, `.agents/PROJECT.md` for rules. Read the routed guide before the matching work; read only the parts you need.

When a guide talks about its "skill root" (scripts, relative paths), that is its own folder under `references/`.

## Route by task

| Task | Read | Project override |
|---|---|---|
| Any screen: D-pad reachability, focus states, overscan, two panes, 10-ft type, icon and banner | [`references/android-tv-design/GUIDE.md`](references/android-tv-design/GUIDE.md) | Colours, type and focus come from `DESIGN.md` §7 (neon tokens, the Inter font (monospace in the stats overlay), glow ring, no scale-on-focus, no Material Theme Builder). The launcher icon carries the app name on purpose (`DESIGN.md` §7.1) |
| UI quality: accessibility (content descriptions, contrast, never colour-only), states, list performance | [`references/mobile-android-design/GUIDE.md`](references/mobile-android-design/GUIDE.md) | Written for Compose + Material 3, which this app doesn't use (`TECHNICAL.md` §4.1): apply the principles, not the code. Ignore dynamic colour, touch targets and phone/foldable layouts |
| ANR audit of the service, the key service and the UI threads; triage of an ANR dump | [`references/anr-audit-and-fix/GUIDE.md`](references/anr-audit-and-fix/GUIDE.md) | None |
| Permissions review: `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, foreground-service types, `INTERNET` (localhost only), the USB `PendingIntent` | [`references/android-permissions-security/GUIDE.md`](references/android-permissions-security/GUIDE.md) | The self-grants over the app's own ADB (`pm grant`, `appops`) are intentional (`TECHNICAL.md` §11-14); check they only ever touch our package |
| Exported components: settings receiver (ADB-only), boot receiver, accessibility service, activities | [`references/android-intent-security/GUIDE.md`](references/android-intent-security/GUIDE.md) | None |
| APK size, keep rules, R8 settings | [`references/r8-analyzer/GUIDE.md`](references/r8-analyzer/GUIDE.md) | Run its Python (needs `protobuf`) inside the Docker build image, not on the host. Its "Path B" edits `settings.gradle` temporarily: revert it afterwards |
| Traces, frame drops, CPU, binder, memory; before/after comparisons of a change | [`references/android-profiler/GUIDE.md`](references/android-profiler/GUIDE.md) | Its `trace_processor` wrapper (which downloads a binary) is not vendored: use Perfetto's official `trace_processor` from outside the repo, kept out of the repository. Traces stay out of git (`work/` is ignored); only summaries are committed |

Rust and JNI have no guide here: `TECHNICAL.md` §4.4 (JNI surface) and §8 (optimisation) are the rules.

## Project rules that cut across the guides

* **Budgets:** release APK ≤ 600 KB; no AppCompat, Material, Compose, coroutines or kotlin-reflect. No allocation on
  the per-frame path, no JNI calls inside per-frame loops, never panic across FFI.
* **Navigation** (`DESIGN.md` §4): five row types only; OK to edit a value, Left/Right change it only while
  editing; Up/Down move in list order and stop at the ends; Back closes the innermost thing first. Never let
  Android's geometric focus search decide a move inside a list: step through children in order.
* **Remote buttons** go through an accessibility service: an injected `input keyevent` doesn't prove them; test
  with the real remote.
* **Settings are data** (`SettingsSchema.kt` keys and ranges, `SettingsSections.kt` rows). A new setting goes in the
  section where a user would look for it.

## Lessons from the reference TV (measure, don't guess)

* **Drawing many small coloured rects is expensive on this GPU.** A grid of ~20 live tiles drawn as ~90 rects each
  cost a whole core on the RenderThread; the UI thread was at 6 %. Drawing each tile's LEDs as one shared atlas
  bitmap (one pixel per LED, scaled, one upload per frame) brought it to ~18 %. Check `top -H -p <pid>` per thread
  before optimising the wrong side.
* **`simpleperf` can't record here** (perf events are denied, even for a `profileable` app), so use per-thread
  `top`, Perfetto (`TECHNICAL.md` §12) and A/B builds that switch one cost off.
* **Kotlin initialises properties in source order**: a field used from `init` must be declared above it (a
  `HashMap` declared below `init` crashed the Settings screen once).
* **The activity can be recreated** while another screen covers it (about 1.4 GB of RAM): keep what the user was
  doing in `onSaveInstanceState`, and never let a "Reset" row be the remembered focus.
* **SurfaceFlinger cost follows the capture rate**: an idle launcher captured at 30/s put SurfaceFlinger at ~30 %;
  the Adaptive rate is bounded by the video's own fps and by 15/s with no video (`TECHNICAL.md` §7).
* After any on-TV navigation test, **dump the settings** (`SettingsReceiver --ez dump true`) and put back anything a
  stray key changed.

## Updating an upstream guide

Copy the new version over its folder under `references/`, rename its `SKILL.md` to `GUIDE.md`, update its row in
[`SOURCES.md`](SOURCES.md) (commit, date), read the diff for anything that runs code or fetches files, and check the
overrides above still hold.
