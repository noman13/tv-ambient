# DESIGN.md

The interface design for **TV Ambient 1.0.0**: what each screen looks like and how it behaves with a remote. §2
records the study of two existing apps that shaped strip setup (§6) and the effects list (§6.2). Every screen
follows the TV-design guide in the agent skill (`.agents/skills/tv-ambient-android/`) and this file.

## 1. Principles

1. **Remote first.** Every action works with Up/Down/Left/Right/OK/Back. No touch-only gestures, no on-screen
   keyboard except for numbers. Inside the app nothing needs a long press. The Blue button's long press (§11) is a
   shortcut, and everything it does can also be done from Home or Settings.
2. **Easy to find, short to reach.** Home shows state, the mode and Settings. Every setting is at most two steps
   from Home (Settings › section), and lists or editors at three (› sub-page). The navigation rules are in §4.
3. **See it before you set it.** Changes apply live to the LEDs and show in an on-screen preview. There's no Save
   button.
4. **Minimal.** Dark theme only, one accent colour, one font (Inter), no images except the app icon, no animation
   beyond focus feedback and the live preview. Small, fast, no splash screen.
5. **Don't disturb the video.** Opening settings must not pause playback when avoidable (the app opens on top of
   the player only when the user asks), and the UI itself draws nothing while hidden.
6. **Our own look.** We take ideas about structure and flow from the two studied apps. We don't copy their screens,
   wording, icons, colours or assets.
7. **Follow the TV conventions.** The TV-design guide in the agent skill (`.agents/skills/tv-ambient-android/`)
   is the checklist for every screen: D-pad reachability with no dead ends, a visible focus state on
   everything focusable, overscan-safe margins, at most two panes, 10-ft legibility, and **no on-screen back
   button** (the remote's Back key does that; breadcrumbs show where you are). Where this file differs from the
   guide, this file wins, and the difference is noted in place.

## 2. Study of existing apps

Apps studied on the reference TV, from screenshots and use only (no decompiling):

| App | Package | Version |
|---|---|---|
| Android Ambilight Application | `com.sevson.androidambiapp` | 1.1.0 |
| Universal Ambient Light | `com.vasmarfas.UniversalAmbientLight` | 2.2.0 |

For each app, the study looked at:
* Screen map: every screen and how you reach it.
* How settings are grouped and named, and which settings people actually change.
* Strip/LED setup flow: how counts, direction and start position are entered and checked.
* Remote usability: focus visibility, dead ends, how far common actions are from Home.
* What works well, to adapt, and what is confusing or slow, to avoid.

Each app is the model for one part of our interface:

| Our screen | Modelled on | What to study closely |
|---|---|---|
| **LED strip setup** (§6) | **Android Ambilight** | How it collects LEDs per side, the start position and direction, gaps; how it shows the result; how the test works |
| **Effects list** (§6.2) | **Universal Ambient Light** | How effects are listed and previewed, how one is chosen and tuned (speed, colour), how it returns to picture mode |
| Everything else | Neither: our own minimal design (§3, §4) | Notes only, to avoid their dead ends |

"Modelled on" means we follow the flow and structure that works. The drawing, wording, layout details, colours and
icons are ours.

The study was kept short: it covered the screens worth seeing (Android Ambilight's LED and colour settings with
its test images; Universal Ambient Light's effects page and Home), and the rest of our design comes from the
TV-design guide and this file.

| Android Ambilight: take | Change | Avoid |
|---|---|---|
| A live drawing of the strip round the **whole screen edge**: a dot per LED, the LED number at each corner, an arrow at LED 1 | Counts per side (top, right, bottom, left), not one "horizontal" and one "vertical" number | Separate − and + buttons per value (each step = move + OK) |
| **Capture depth drawn on the same picture** (dashed lines), and the total LED count updating live | Values change with Left/Right on the row (our value row, §4.2) | Two-column forms where Left/Right moves between columns |
| A choice of **on-screen test images** (solid primaries, bars) to compare the LEDs with known colours | Name test images by what they show, not "Color Test 13" | A faint focus highlight; sliders with no number |
| Colour correction kept apart from setup, with a live test picture behind it | One gamma plus white balance, not per-channel gamma | Content cut off at the panel's edge; strip, device and capture settings mixed on one screen |

| Universal Ambient Light: take | Change | Avoid |
|---|---|---|
| **Each effect shown as its colours**: a small TV outline with the effect's light glowing round it, so you see it before choosing | Settings of an effect open next to it (§6.2), not after the whole list | An on-screen back arrow (the remote's Back does that) |
| Effects grouped under small headings; a **check mark** on the selected one; test patterns grouped "for setup" | A tile animates only while focused (cost) | A grid that runs off the top or bottom edge |
| Full-width setting rows that show their number, a one-line explanation under a switch | | Phone-control and donation buttons on Home; a loud full-screen background behind text |
| Home: power is the biggest, first-focused control; a status card says in plain words what's running (output, capture method) | | |

## 3. Information architecture

Two places only: **Home** (what the lights are doing, and the mode) and **Settings** (everything else). Settings
is split into sections, grouped by the question you're asking: *how does each mode behave*, *how does the light
look*, *how is it set up*, *how does the app run*.

```
Home ─────────── on/off · mode tiles (Screen, Music, Effect, Single colour) · status · Settings
Settings
├─ MODES
│  ├── Screen mode         capture method, rate, auto rate for 4K, black bars, edge depth, dark-edge reach
│  ├── Music mode          its own section (§10): music effect, audio input, response, look, when it's silent
│  ├── Effects             the effect list (§6.2), speed and palette per effect, Blue rotation ticks
│  ├── Single colour       colour picker, brightness
│  └── Mode switching      which modes the Blue button cycles through, long press, start-up mode (§11)
├─ LOOK
│  └── Picture             brightness, saturation, gamma, white balance › , black level, smoothing
├─ SETUP
│  ├── LED strip           visual setup editor (§6), test pattern, baud rate, colour order, LED update rate
│  ├── Remote buttons      Red/Green/Yellow/Blue actions, Live TV exception, messages (§8)
│  └── Stats overlay       layout, position, size, refresh, which stats › , record to file (§9)
└─ SYSTEM
   ├── Start-up & power    start on boot, what to do when the screen turns off, USB debugging status
   └── About & help        version, help pages, licences, reset everything
```

* Every setting has a home, and nothing is hidden behind an "expert mode". In each section the common
  settings come first and the rarely used ones last, under a thin "More" divider.
* Each mode's settings live in that mode's section, so "how do I change the music lights?" has one answer:
  Settings › Music mode. Picture settings (brightness, gamma…) apply to all modes and stay in Picture.
* The same names are used everywhere: on Home, in Settings, in the Blue-button messages and in the stats overlay.

## 4. Navigation

### 4.1 Rules

1. **Never more than three levels:** Home › section › sub-page (for example Music mode › Music effect). A sub-page
   is used only for a list or an editor that doesn't fit in a row.
2. **Two panes in Settings:** the section list on the left, its settings on the right. Moving through the list
   shows each section's settings straight away, without pressing OK, so you can browse.
3. **Predictable keys everywhere:**

| Key | In the section list | On a row | While editing a value (after OK) |
|---|---|---|---|
| Up / Down | Next or previous section | Next or previous row, in list order; nothing at the first or last row | Nothing (the value stays put) |
| Right | Into the section's rows | Opens a link; otherwise nothing | Increase the value |
| Left | — | Back to the section list | Decrease the value |
| OK | Into the section's rows | Edit a value, toggle a switch, open a choice list or sub-page, run an action | Done editing |
| Back | To Home | To the section list (closes a choice list or sub-page first) | Done editing |

   **OK to edit:** Left/Right change a value only after OK has selected the row (its
   ◀ ▶ keys light up in the section's colour); OK or Back ends editing. Without OK, Left always goes back to the
   section list, so a stray Left/Right never changes a setting. The effect options under a tile (§6.2) work the
   same way.
4. **Focus is remembered:** leaving and returning puts the focus back on the same section and row, also after
   another screen (first run, a system page) covered Settings and the TV reclaimed it. Closing a sub-page focuses
   the row that opened it. An action row keeps the focus after it runs (for example Start-up & power › Check USB
   debugging). A **Reset** row is never remembered, so coming back never lands on it.
5. **You can always see the effect:** a live preview strip sits at the top of the right pane on every section, and
   changes apply at once. There's no Save button.
6. **Every section ends with "Reset this section"**, which asks for confirmation (OK twice).
7. **Shortcuts:**
   * On Home, each mode tile has a small "settings" link below it that opens that mode's section directly.
   * The remote's Menu or Settings key (if it reaches the app) opens Settings from Home.
   * From the stats overlay or a Blue-button message nothing opens: shortcuts never interrupt the video.

### 4.2 Row types

Only five kinds of row, so every screen behaves the same:

| Row | Looks like | Keys |
|---|---|---|
| **Value** | `Brightness      35 %  [◀][▶]` | OK starts editing (the key caps light up), Left/Right change it, holding speeds up after 400 ms; OK or Back ends |
| **Switch** | `Start on boot          (ON)` | OK toggles (a lit pill when on). A system switch (`SystemSwitchRow`, e.g. Remote shortcuts) is the same row with its state outside the app's settings: it shows Android's own state, and OK asks Android to change it |
| **Choice** | `Audio source    Automatic  ▾` | OK opens a short list in place in the section's colour (max 6 visible), Up/Down + OK picks, Back cancels |
| **Link** | `Music effect    Spectrum   ›` | OK or Right opens the sub-page |
| **Action** | `> Test pattern` | OK runs it; a Reset asks for OK a second time, and moving away cancels |

* A focused row shows a one-line hint underneath ("Lower this if dark scenes look grey").
* A row that doesn't apply right now (for example capture settings while screen recording is selected) is dimmed,
  says why in its hint, and can't be focused into a dead end.

## 5. Screens

### 5.1 Home
```
┌──────────────────────────────────────────────────────────────────────┐
│  TV Ambient                                         ● USB connected  │
│                                                                      │
│   ┌──────────────────────────────────────────────┐      ( ⏻ )        │
│   │  live preview: a TV outline with the LEDs    │       ON          │
│   │  drawn round it in their current colours     │  [ ⚙ Settings ]   │
│   │                                              │  Adalight LED     │
│   └──────────────────────────────────────────────┘  controllers/USB  │
│                                                                      │
│   ┌────────┐  ┌────────┐  ┌────────┐  ┌──────────────┐               │
│   │ Screen │  │ Music  │  │ Effect │  │ Single colour│               │
│   │   ✓    │  │VU meter│  │Northern│  │   ■ 45°      │               │
│   │        │  │        │  │  sky   │  │              │               │
│   └────────┘  └────────┘  └────────┘  └──────────────┘               │
│    settings    settings    settings    settings                      │
│                                                                      │
│                           Shell screenshots · auto · LEDs 30/s       │
└──────────────────────────────────────────────────────────────────────┘
```
* The focus starts on the **power button**: a drawn power symbol in a round neon button, green with "ON" under it
  while the lights are on, red with "OFF" while they're off (Inter SemiBold like the rest). Down goes
  to **Settings**, right under it, then to the mode tiles (OK picks a mode, and the tick moves) and each tile's
  "settings" link.
* Under Settings a small note says what the app drives: **Adalight LED controllers over USB** (the only protocol so
  far; Settings › LED strip › Controller and About say the same, and first run explains it).
* The background is solid (`bg`), not a see-through dim over whatever plays.
* With the lights off, the preview's LEDs go dark too.
* The TV preview takes most of the panel (296 dp high) with the LEDs drawn as neon dots
  round it; the tiles and Settings sit lower. Each mode tile has its own neon colour (Screen blue, Music purple,
  Effect green, Single colour dark yellow).
* Each tile shows its current choice (effect name, colour) so you know what you'll get before choosing it.
* The status line is the only technical text on Home.
* The preview updates at most 15 times a second and stops when the activity isn't visible.

### 5.2 Settings (two panes)
```
┌──────────────────────────────────────────────────────────────────────┐
│  Settings › Picture                           [ live preview strip ] │
│ ┌──────────────────┐                                                 │
│ │ MODES            │   Brightness          ◀  35 %  ▶  ━━━━━──────   │
│ │  Screen mode     │   Saturation          ◀ 150 %  ▶                │
│ │  Music mode      │   Gamma               ◀  2.0   ▶                │
│ │  Effects         │   White balance        R75 G75 B75      ›       │
│ │  Single colour   │   Black level         ◀   5    ▶                │
│ │  Mode switching  │   Smoothing           ◀   6    ▶                │
│ │ LOOK             │                                                 │
│ │ ▌Picture         │   Lower this if dark scenes look grey.          │
│ │ SETUP            │                                                 │
│ │  LED strip       │   [ Reset this section ]                        │
│ │  Remote buttons  │                                                 │
│ │  Stats overlay   │                                                 │
│ │ SYSTEM           │                                                 │
│ │  Start-up & power│                                                 │
│ │  About & help    │                                                 │
│ └──────────────────┘                                                 │
└──────────────────────────────────────────────────────────────────────┘
```
* The left list shows group headings (not focusable) and sections. The selected section has an accent bar.
* Under each section name, a dim one-line summary of its current state appears when it's focused, for example
  "Music mode · Automatic · Spectrum".
* The title sits in a **breadcrumb bar** that stands out: a `surface` band with a thin light border and
  rounded corners, the live preview strip at its right end. It shows where you are, on one line: `Settings › Music mode`, and on a sub-page `Music mode › Music
  effect` (the "Settings ›" goes so the line never wraps; a still longer one loses its start, never the page name).
* Each settings group has its neon colour (§7): its heading, its values, its focus ring and its choice lists.
* The section list is about 30% of the width; the right pane scrolls by itself, with the focused row kept in the
  middle third of the screen.

### 5.3 LED strip setup (§6)

### 5.4 First run
Three steps, skippable, each one screen: **1** plug in the controller (live USB status, and a note that the app
drives Adalight controllers over USB serial only), **2** turn on USB debugging, **3** strip setup (§6). After that,
Home. Every first-run step can be redone later from its section (LED strip, Start-up & power).

**Step 2 follows the TV's state.** It reads `Settings.Global` `DEVELOPMENT_SETTINGS_ENABLED` and `ADB_ENABLED`
every 0.5 s and shows one of three states:

| State | Text | Button |
|---|---|---|
| Developer options off | How to turn them on: About › Build, press OK seven times | **Open About** |
| Developer options on, USB debugging off | Turn on USB debugging (and Network debugging, if the TV lists it) | **Open Developer options** |
| USB debugging on | The app runs the connection check itself | **Next**, once the check has passed |

While the person is in the TV's Settings, the step keeps watching for up to 10 minutes and brings the wizard back to
the front once the switch is on. Android may block that from the background unless a bound service of ours (the
remote shortcuts) is on; then the step resumes when they come back. **Use screen recording instead** is always
offered, with a note that it costs more.

A **timeline** sits at the top of every step: three dots joined by a line, named Controller, USB
debugging and Strip. Done steps and the line up to them are green, the current dot is blue with a glow ring, later
ones are dim. A thin progress bar under it fills a third per step. The timeline sits at the same height (72 dp
from the top) on every step; the step's text starts under it. The buttons are dark pills with a neon ring on
focus, in the app font.

## 6. Strip setup editor

**Modelled on Android Ambilight's strip drawing** (§2), with our own rows and look.

* **The drawing is the screen's frame.** While Settings › LED strip is open, a neon dot for every LED runs round a
  rectangle well inside the screen (64 dp from the sides, 40 dp from top and bottom), in the LEDs'
  live colours; only lit LEDs glow. The LED number of each corner sits **outside** the rectangle, so overscan never
  cuts it. LED 1 has a neon ring and a `1 ▶` label, and a short green comet runs from it in the strip's direction
  (15 fps, only while the page is open). A bottom gap shows as a break in the dots. The Settings panel shrinks to fit
  inside the rectangle, and the background behind is darkened so nothing playing shows between the dots and the
  panel.
* **Rows** (value rows, Left/Right changes, §4.2), in the order you'd set them up:
  LEDs top, LEDs right, LEDs bottom, LEDs left, bottom gap (for a stand), start corner, direction, first-LED offset,
  then depth top/bottom and depth sides. A total ("184 LEDs") updates live under the counts.
* **Every change shows at once** on the drawing and on the real LEDs (the strip shows the layout test while this
  section is focused), so you can look from the screen to the wall and back.
* **Test** (its own group heading): Layout test (top red, right green, bottom blue, left white, the first 3 LEDs blink
  yellow), and solid **White**, **Red**, **Green**, **Blue**, **Grey 50%** shown on the screen *and* the
  LEDs together, to compare against known colours (for Picture › White balance too). Each named for what it shows.
  Back ends a colour test (back to the layout test) before it does anything else.
* Baud rate, colour order and LED update rate stay in this section under a **Controller** heading; the maximum LED
  rate for the chosen baud is shown (`TECHNICAL.md` §7).

### 6.2 Effects list

**Modelled on Universal Ambient Light's effects page** (§2): effects are shown **as their colours, not as
buttons**. Every effect is a colour preview, and there are a lot of them out of the box.

* **A grid of colour tiles**, 7 per row (a larger panel with smaller previews). While the
  grid has focus the section list folds away and the grid takes the panel's whole width. Each tile is a small 2:1 TV
  outline with the effect's light as neon dots round it in the effect's own colours, and the name below (a long name
  is squeezed up to 20 % before it's ever cut, so "Rainbow swirl ↻" keeps its arrow). Tiles are grouped under small
  headings (Calm, Nature, Lively, Party, Seasonal, For setup). No buttons, numbers or controls on a tile.
* **Every visible tile runs live**, from the same Rust functions
  (`TECHNICAL.md` §15): the grid redraws at 10 fps; the focused tile renders every frame as neon dots with a glow,
  the others re-render at about 3 fps, staggered, and draw as blocks from one shared ring bitmap (one pixel per LED,
  scaled up). Flashing (⚡) tiles stay still unless focused. Measured on the reference TV: the live grid adds about
  18 % of one core while the page is open (the first version, ~90 rects per tile, cost a whole core on the GPU
  thread), and nothing once it's closed. Up/Down moves between rows and groups, Left/Right within a row; the grid
  scrolls with the focused row kept in the middle third, inside the overscan-safe area.
* **OK on a tile selects it**: the effect starts on the LEDs at once (with the 300 ms cross-fade) and the mode
  switches to Effect. The selected tile has a check mark and a thicker accent edge, and stays selected after Back.
* **OK on the selected tile opens its settings** in place, as rows directly under that tile's row (speed, colour or
  palette, brightness, and an **In Blue rotation** switch); OK edits a value as in §4.1, Back closes them and
  returns to the tile. Nothing else is needed on the tile itself.
* Effects marked ⚡ flash: they carry a small ⚡ on the tile, show a one-time photosensitivity note when first
  selected, are left out of the Blue rotation by default, and are capped at 3 flashes per second.
* The Music screen uses the same tile grid for music effects (§10.2), with tiles driven by a built-in test rhythm.

**Built-in effects: 52** (all computed by the Rust `effects` module; adding one = one function + one table entry,
and a preset of an existing function = one table entry). The default is **Northern sky**. The four **classic**
effects (✓) are also checked against reference outputs in the golden tests. Five effects flash (⚡).

| Group | Effects |
|---|---|
| **Calm** (12) | Slow colours ✓ (whole strip fades through the colour wheel), Breathing ✓ (one colour fades in and out), Gradient (static two- or three-colour gradient), Sunset (warm amber-to-rose drift, very dim), Aurora (green-teal-violet waves along the top), Ocean (blue-cyan waves rolling round), Candle (warm, gently flickering), Lava lamp (slow blobs of warm colour merging), Forest (green-teal drift), Twilight (blue-violet slow fade), Warm white (static 2700 K), Movie white (dim neutral white for watching in the dark) |
| **Nature** (8) | Fire (flames rising up the sides), Rain (blue drops running down the sides), Thunderstorm ⚡ (dim blue with lightning), Starry night (dark blue with slow twinkling white), Sunrise (a 10-minute ramp from deep red to daylight), Waterfall (light flowing down both sides), Meadow (green-yellow breeze), Northern sky (Aurora across the whole strip) |
| **Lively** (13) | Rainbow swirl ↻ ✓, Rainbow swirl ↺ ✓, Comet (bright head, fading tail), Dual comets (two comets in opposite directions), Scanner (sweeps back and forth along the top), Color wipe (each colour fills the strip in turn), Color cycle (whole strip steps through hues), Twinkle (random LEDs sparkle on a dim base), Heartbeat (double pulse), Theatre chase (marquee dots), Plasma (smooth colour waves round the strip), Bouncing balls (lights bounce up the sides), Meteor rain (meteors with decaying tails) |
| **Party** (6) | Strobe ⚡ (white flashes), Police ⚡ (red and blue halves alternating), Sparkle ⚡ (bright white sparks), Disco (coloured segments jumping), Confetti (random coloured specks fading), Rainbow pulse (rainbow breathing in brightness) |
| **Seasonal** (8) | Festive (red and green trading places), Pastel (soft pastels drifting), Ice (cool white-blue shimmer), Halloween (orange and purple flicker), Valentine (pink-red breathing), Spring (pastel greens and yellows), Lantern (warm gold glow with a slow shimmer), Fireworks ⚡ (bursts that bloom and fade) |
| **For setup** (5) | Layout test (sides in red, green, blue, white; first LEDs blink yellow), White (for white balance), Primaries (red, green, blue in turn), Grey ramp (dark to bright round the strip), Corners (only the four corner LEDs lit) |

The classic effects must keep matching their golden outputs (`core/tests/golden/expected/effects.*`). Effect ids
are stored in settings, so a removed effect needs a settings migration (`TECHNICAL.md` §9).

## 7. Visual tokens

Dark and lively, with four LED colours as neon accents. The tokens live in `ui/Theme.kt`.

| Token | Value | Use |
|---|---|---|
| `bg` | `#0B0D10` | Every screen's solid background (no see-through dim) |
| `bar-border` | `#E8ECF1` at 33 % | The thin border of Settings' breadcrumb bar |
| `surface` | `#14171C` | Rows, cards, tiles |
| `surface-focus` | `#1C2129` | Focused row background |
| `text` | `#E8ECF1` | Primary text |
| `text-dim` | `#8A93A0` | Hints, status |
| `text-off` | `#4E5560` | A row that doesn't apply right now |
| `neon-blue` | `#36C2FF` | **Modes** group; the general accent |
| `neon-yellow` | `#E0B000` | **Look** group; the Single colour tile (a dark yellow) |
| `neon-green` | `#36FF8A` | **Setup** group; ON, connected |
| `neon-purple` | `#B07CFF` | **System** group (`#8A5CFF` lifted for contrast) |
| `neon-red` | `#FF3B5C` | The power button while the lights are off |
| `warn` | `#FFB340` | "Press OK again to confirm" and similar |

* Type: **Inter** everywhere except the stats overlay (§9.1, monospace), Regular and SemiBold, with tabular figures (`tnum`) so values don't jump. The two
  files in `res/font/` are subset to Latin and the app's symbols (about 44 KB each, SIL OFL 1.1,
  `LICENSES/Inter-OFL-1.1.txt`); a glyph Inter lacks (▾ ⚙ ⚡) falls back to the system font. Title 22 sp, row
  label 16 sp, hint 12 sp, small 11 sp. Group headings are upper case with letter spacing. Sizes fit the reference
  TV's 960×540 dp UI.
* Focus: a neon ring in the group's colour (a soft 7 dp glow under a crisp 2 dp line) + `surface-focus`
  background. No scale animation (cheaper to draw, and calmer). Value rows show ◀ ▶ as small bevelled key caps,
  lit while editing; switches are pills, lit when on.
* LEDs in every preview are drawn as neon dots: each colour lifted to full brightness (the strip's own brightness
  setting would look matt on a screen), with a glow only on lit LEDs.
* Lists that run past their edge fade out over 24 dp instead of cutting a line of text.
* Overscan safe area: 48 dp left/right, 27 dp top/bottom.
### 7.1 App icon and banner

The icon follows standard Android practice and **carries the app name**, since Android TV launchers show the
icon or the banner, often without a label underneath.

| Asset | Spec | Content |
|---|---|---|
| **TV banner** `android:banner` | 16:9: 320×180 at xhdpi (160×90 mdpi … 640×360 xxxhdpi). One vector, or PNGs per density if the vector renders poorly | Full logo, as the TV skill asks: glyph on the left, **"TV Ambient"** wordmark on the right, on the `bg` colour with a soft accent glow. This is what the Android TV home row shows. |
| **Adaptive launcher icon** `android:icon` (API 26+) | 108×108 dp layers, content inside the safe zone (72 dp per the TV skill) | Foreground: the glyph above a short **"TV Ambient"** wordmark (bold, 2 lines if needed, readable at 48 dp). Background: solid `bg` with a faint accent halo. No monochrome layer: TV launchers don't use themed icons. |
| Legacy icon (API 25 and lower) | Not needed: minSdk is 26 | |
| Notification icon | 24 dp, white glyph only | No text |

* Glyph: a dark screen with four light bars (orange top, blue bottom, purple left, green right), with **"TV" and "AMBIENT"** inside the screen in our own 5×7 dot-matrix
  letters; the banner reads "TV AMBIENT". Generated by `tools/icon/make_icons.py` (plain Python, no font).
* Vectors only, with no PNGs except the banner if a vector banner renders poorly on the reference launcher.
* The wordmark is drawn as paths (dot-matrix squares) in the vector, not as a font, so it looks identical
  everywhere and adds no font file or licence.
* Check on a TV that the name is readable in the home row, in the launcher's app drawer and in Settings → Apps.
* **Deliberate difference from the TV-design guide:** it advises no text in the launcher icon (text belongs in the
  banner). The icon carries the name too, because some launchers show icons without labels. If the name can't be
  made readable at the icon size on a launcher, the fallback is the glyph only in the icon and the name in the
  banner.

## 8. Remote buttons (colour-key shortcuts)

The four colour buttons on Android TV remotes (**Red, Green, Yellow, Blue**) control the app from anywhere, even
while a video plays in another app.

| Button | Default action | Choices |
|---|---|---|
| Red | **On / off** (toggle) | None, Start, Stop, On / off, Show/hide stats, Mode button, Open or close settings |
| Green | **Open or close settings** | same |
| Yellow | **Show/hide stats** (§9) | same |
| Blue | **Mode button** (§11) | same |

**Mode button** has its own press behaviour: a long press goes to the next mode, and a short press steps through the
effects of Music and Effect mode (§11). **Open or close settings** opens Settings; pressed while Settings is in
front, it closes the app's screens (Settings and Home) and returns to what was on screen. The other actions treat
short and long presses the same.

**Remote buttons screen**
```
┌──────────────────────────────────────────────────────────────────────┐
│  Settings › Remote buttons                                           │
│                                                                      │
│  Remote shortcuts                     (ON)                           │
│  ● Red                 On / off                ▾                     │
│  ● Green               Open or close settings  ▾                     │
│  ● Yellow              Show/hide stats         ▾                     │
│  ● Blue                Mode button             ▾                     │
│  Not in Live TV                       (ON)                           │
│  Show a message                       (ON)                           │
│                                                                      │
│  Press a colour button now to test it: Red → On / off ✓              │
└──────────────────────────────────────────────────────────────────────┘
```
* Each label has a dot in the button's own colour, so the row is recognisable at a glance.
* **Remote shortcuts** is an on/off switch showing whether Android has the key service enabled (`TECHNICAL.md`
  §11); it reads Android's accessibility state, not a setting of ours. If USB debugging is approved, OK turns the
  service on or off through the app's own ADB connection. Otherwise OK opens the Accessibility settings page, with a
  one-line explanation in the hint. **On by default:** the first time the app's ADB connection works (first run's
  check, or the capture helper starting), the app turns the service on once; from then on the switch is the
  person's, and turning it off sticks.
* A test line at the bottom shows the last colour button received and what it did, so setup can be checked
  without leaving the screen.
* **Not in Live TV** (default On): buttons pass through untouched while the TV's own tuner app is in front, because
  teletext and HbbTV use them.
* Only the buttons with an action are taken. A button set to None always reaches the app in front.
* **Show a message**: a small message with the new state ("Music: Spectrum", "Effect: Comet", "Lights off"): a
  static pill, 18 sp text on `bg` at 85 %, centred at 13 % of the screen height, just below the top LEDs' sampling
  band so it never colours them, gone after 2 s. The LEDs
  themselves fade between states over 300 ms, so the change is visible even with messages off.

## 9. Stats overlay ("stats for nerds")

A small, see-through panel drawn **on top of the video**, in the spirit of YouTube's "Stats for nerds" or a console
performance overlay, but our own layout. It's for comparing how the lights perform against the video: capture and
LED rates, CPU, memory and bandwidth, live.

* **Hidden by default.** The **Yellow** button shows or hides it from any app (configurable, §8). Hiding it stops
  all stats collection, so it costs nothing while hidden.
* **You choose what's on it.** Every stat has its own switch in Settings → Stats overlay. The panel shows only the
  switched-on stats, in a fixed order, and grows or shrinks to fit.

### 9.1 Panel

Each group's values are a **list**, one value per line with a short sub-label, and the values use a smaller font
than the group headings (headings 14 sp, sub-labels and values 12 sp). The panel ends with an **Info** section
(11 sp) below a thin divider: "Press yellow to close" and the remote's four colour buttons, with **Yellow in full
colour and a white border**, the others dimmed to 45 %. The words name the button, so the hint never relies on
colour alone.

```
┌ TV Ambient stats ───────── 1 s • ┐
│ Capture   rate    12.5 of 15 /s  │
│           time    5.4 ms, max 6  │
│ LEDs      rate    29.6 /s        │
│           link    connected      │
│ Video     fps     59.6 fps       │
│           late    0 frames       │
│ CPU       app     4.6 %          │
│           helper  2.4 %          │
│           system  38.0 %         │
│           mmdisp  0.0 %          │
│           SF      21.0 %         │
│ RAM       app     21 MB          │
│           helper  73 MB          │
│           free    630 of 1366 MB │
│ USB       rate    15.3 KB/s      │
│           load    66 % of 230400 │
│ Network   down    0.1 Mb/s       │
│           up      0.1 Mb/s       │
│ ──────────────────────────────── │
│ Info      Press yellow to close  │
│           ▬ ▬ [▬] ▬              │
└──────────────────────────────────┘
```

* The system **monospace** font (the only place that doesn't use Inter: a terminal look, and figures that line
  up). Text 12 sp headings, 10.5 sp values, 9.5 sp info lines at the Medium size (Small ×0.85, Large ×1.2); the
  panel's width and height follow the text, with 9 dp padding.
* Labels in `text-dim`, values in `text`, and a value turns `warn` when
  it crosses a threshold (for example video fps below 97% of the content rate, capture time above the budget, USB
  above 90%).
* Background `#0E0F12` at 75% opacity, 7 dp corner radius, no shadow.
* Header: the app name and the refresh interval. A dot in the header pulses once per refresh, to show it's live.
* Every layout is **multi-line**; a stat that doesn't fit wraps onto the next line instead of being cut off.

**Layouts**

| Layout | Look | Use |
|---|---|---|
| **Full** (default) | Grouped, with a heading per group (Lights, Video, CPU, Memory, Bandwidth, Music), one or two lines per stat, as above | Reading everything |
| **Compact** | Multi-line, no headings, short labels, two columns of `label value` pairs | Watching a film with stats on, covering less picture |
| **Ticker** | Two lines (three if the values need it) across the overscan-safe width at the screen's top or bottom edge, stats separated by `·` | The least intrusive: it reads like a subtitle |

Compact example:
```
┌──────────────────────────────────┐
│ cap 19.8/s 7.4ms   led 30.0/s    │
│ vid 59.6fps late 0 cpu 4/11/38%  │
│ ram 31/58MB      usb 73%         │
└──────────────────────────────────┘
```

### 9.2 Stats and their switches

| Group | Stat | Default | What it shows |
|---|---|---|---|
| **Lights** | Capture rate | On | Captures per second, actual / target ("auto" with the Adaptive rate) |
| | Capture time | On | Average capture duration (ms), with the max over the interval |
| | Capture path | On | Shell screenshots / screen recording, API path |
| | LED rate and USB link | On | Packets sent to the LEDs per second, and whether the controller is connected |
| **Video** | Video fps | On | Frames the player delivered per second, from the display's frame timing |
| | Late frames | On | Frames later than 1.5× the frame time, per interval |
| | Display mode | Off | Panel resolution and refresh rate, UI size |
| | App in front | Off | Package name of the app in front (shown live, never written to the CSV log) |
| **CPU** | App and helper CPU | On | Our two processes, % of one core |
| | System CPU | On | Whole-device CPU use |
| | Display services CPU | On | mmdisp and SurfaceFlinger CPU (the processes capture affects) |
| | CPU frequency | Off | Current CPU frequency (temperature isn't readable by shell on the reference TV) |
| **Memory** | App and helper memory | On | PSS of the app, RSS of the helper |
| | Device memory | On | Available memory of total |
| **Bandwidth** | USB (LEDs) | On | Bytes per second to the LED controller, and % of the baud rate's capacity |
| | Network | On | Device download and upload rate; while streaming this is close to the video's bitrate |
| **Music** | Audio level, tempo, source | On (Music mode) | Input level (dB), detected beats per minute, and which audio source is in use (§10) |
| **Mode** | Current mode | On | Screen, Music (with the music effect), Effect (with the effect's name) or Single colour |

Stats that don't apply to the current mode are hidden automatically, for example capture stats in Music mode and
music stats in Screen mode.

### 9.3 Settings → Stats overlay

```
┌──────────────────────────────────────────────────────────────────────┐
│  Settings › Stats overlay                                            │
│                                                                      │
│  Show now              ◀  Off ▶      (Yellow toggles it anywhere)    │
│  Layout                ◀  Full ▶     Full / Compact / Ticker         │
│  Position              ◀  Top left ▶ 4 corners                       │
│  Text size             ◀  Medium ▶   Small / Medium / Large          │
│  Refresh every         ◀  1 s ▶      0.5 / 1 / 2 s                   │
│  Record to file        ◀  Off ▶      CSV, one row per refresh        │
│  ─ Stats ───────────────────────────────────────────────────────     │
│  Lights   ▸ Capture rate ✓  Capture time ✓  Capture path ✓ …         │
│  Video    ▸ Video fps ✓  Late frames ✓  Display mode ·  …            │
│  CPU      ▸ …                                                        │
│  Memory   ▸ …                                                        │
│  Bandwidth▸ …                                                        │
│  Music    ▸ Audio level, tempo, source ✓                             │
│  Mode     ▸ Current mode ✓                                           │
└──────────────────────────────────────────────────────────────────────┘
```

* Each group row opens a checklist (OK toggles a stat). The overlay updates live if it's showing.
* **Record to file** writes the same stats as CSV for later comparison (`TECHNICAL.md` §12.5), never the app in
  front. The path is shown on the row.

### 9.4 Rules

* The panel sits inside the overscan safe area, in the chosen corner. The capture ignores the panel's area, so it
  never colours the LEDs (`TECHNICAL.md` §12.3).
* It never takes focus or keys: the remote keeps controlling the player.
* It updates at the refresh interval only. Text is redrawn into the same view, with no animation. *(Measured: a
  breathing animation on the Yellow button, even at 15 frames/s in its own tiny window, raised the app from
  ~3 % to ~8 % CPU, because every frame makes the window repaint and recompose. Dropped for a static border.)*

## 10. Music mode

The lights react to whatever the TV is playing: music apps, YouTube concerts, games. Neither studied app has this,
so the design is our own. In Music mode the screen isn't captured at all, which also saves CPU.

### 10.1 Settings › Music mode

Music mode has **its own settings section** (MODES › Music mode), separate from Effects and Screen mode. Its rows
are grouped under small headings and ordered by how often people change them: what it looks like first, then how
it reacts, then the audio plumbing.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Settings › Music mode                        [ live preview strip ] │
│ ┌──────────────────┐ ┌─────────────────────────────────────────────┐ │
│ │ MODES            │ │ ● Listening · Automatic (output mix)        │ │
│ │  Screen mode     │ │   ▁▃▅▇▅▃▂▁▂▃▅▃   −18 dB · 124 BPM           │ │
│ │ ▌Music mode      │ └─────────────────────────────────────────────┘ │
│ │  Effects         │   LOOK                                          │
│ │  Single colour   │   Music effect           VU meter           ›   │
│ │  Mode switching  │   Colour palette         Rainbow            ▾   │
│ │ LOOK             │   Brightness follows     Loudness           ▾   │
│ │  Picture         │   Minimum brightness   ◀  5 %  ▶                │
│ │ SETUP            │   REACTION                                      │
│ │  LED strip       │   Sensitivity            Auto               ▾   │
│ │  Remote buttons  │   Beat response        ◀  Medium  ▶             │
│ │  Stats overlay   │   Fade speed           ◀  Medium  ▶             │
│ │ SYSTEM           │   Audio delay          ◀  0 ms  ▶   [ Tap to beat ]│
│ │  Start-up & power│   WHEN IT'S SILENT                              │
│ │  About & help    │   After 5 s of silence   Dim glow           ▾   │
│ └──────────────────┘   AUDIO INPUT                                   │
│                        Audio source           Automatic          ▾   │
│                        [ Test audio input ]                          │
│                        ── More ──                                    │
│                        Frequency range        Full               ▾   │
│                        [ Reset this section ]                        │
└──────────────────────────────────────────────────────────────────────┘
```

**Status card** (top, not focusable): whether audio is coming in, which source is in use, a 12-band mini spectrum,
level and tempo. It says "Play some music to see the lights react" when nothing plays, and "No sound reaches the
lights" (with a link to the help page) when something plays but the source gives silence for 5 s.

| Group | Row | Type | Values (default **bold**) | What it does |
|---|---|---|---|---|
| Look | Music effect | Link › | **VU meter** and the 12 others in §10.2 | Opens the music effect list (§10.2) |
| | Colour palette | Choice | **Rainbow**, Warm, Cool, Neon, Single colour (uses Single colour's colour) | Colours the effect uses |
| | Brightness follows | Choice | **Loudness**, Fixed | Louder music = brighter, or always the same |
| | Minimum brightness | Value | 0-30 %, **5 %** | Glow kept during quiet parts so the room doesn't go dark |
| Reaction | Sensitivity | Choice | **Auto**, 1-10 | Auto adapts to the volume, so quiet and loud songs both move the lights fully |
| | Beat response | Value | Soft, **Medium**, Strong | How hard the lights jump on a beat |
| | Fade speed | Value | Slow, **Medium**, Fast | How quickly the lights settle after a beat |
| | Audio delay | Value + action | −200 … +500 ms, **0 ms** | Lines the lights up with the sound when a soundbar or Bluetooth adds delay. **Tap to beat**: a click plays and you press OK in time 8 times; the app sets the delay |
| When it's silent | After 5 s of silence | Choice | **Dim glow**, Lights off, Switch to Screen mode, Keep last colours | What happens when the music stops |
| Audio input | Audio source | Choice | **Automatic**, Output mix, Playback capture (Android 13+) | Automatic picks the best that works (`TECHNICAL.md` §14.1). Others are for troubleshooting. Remote submix (source C) isn't built |
| | Test audio input | Action | | Plays a short test tone and reports whether the lights would hear it |
| More | Frequency range | Choice | **Full**, Bass only, Vocals (mid) | Which part of the sound drives the lights |

* If no audio source works on a TV, the status card explains why in one line (for example "Sound goes straight to
  the soundbar as Dolby"); Music mode stays in the Blue rotation unless you take it out in Mode switching.
* Options that don't apply are dimmed with a reason, for example Playback capture on Android 12 and lower.
* Settings change the lights live, so you can tune while music plays.

### 10.2 Music effect list (sub-page)

`Settings › Music mode › Music effect`. It uses the same colour-tile grid as the effects (§6.2): each tile shows the
effect's colours (all visible tiles animated, driven by a built-in test rhythm), OK selects, OK on
the selected tile opens its one option in place (In Blue rotation), Back returns to Music mode
with the new effect named on its row.

| Effect | What it does |
|---|---|
| Spectrum | Bass at the bottom centre, rising through the sides to treble at the top |
| Pulse | The whole strip flashes softly on each beat |
| VU meter | Both sides fill up with loudness |
| Bass glow | Warm glow that swells with the bass |
| Wave | Colour waves race round the TV on beats |
| Centre burst | Beats burst outward from the bottom centre |
| Frequency colour | Colour follows the dominant pitch |
| Strobe beat ⚡ | White flash on strong beats only, capped at 3 Hz; one-time warning |
| Spectrum bars | A spectrum analyser round the TV: left and right are bars rising from the bottom (bass on the left, mid and treble on the right); top and bottom are the 12 bands as separate bar segments, bass on the left |
| Spectrum bars ↻ | Rotated: the 12 band segments run up the sides (bass at the bottom); top and bottom are bars growing left to right |
| Spectrum wave | Bars rising up the sides; top and bottom a smooth wave of the 12 bands, bass on the left |
| Spectrum wave ↻ | Rotated: the wave up the sides, bars growing left to right on top and bottom |
| Colour shift | The whole strip in one colour that changes on each beat, brightness following the loudness |

No music effect uses the screen, so Music mode never captures it and never runs the capture helper. **All music effects are high-contrast** for light on a white wall: most of
the white is taken out of each colour (saturated, never pastel) and the level gets a gamma of 1.5 at 90 % of the
light, so the darks between hits are deep and the peaks stay strong (`TECHNICAL.md` §14.3).

## 11. Modes and the Blue button

There are four modes:

| Mode | The lights… |
|---|---|
| **Screen** | follow the picture (capture) |
| **Music** | react to the sound (§10) |
| **Effect** | play an effect from §6.2 |
| **Single colour** | show one fixed colour |

### 11.1 Settings › Mode switching

Shown in the right pane of the two-pane Settings screen (§5.2), like every section.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Settings › Mode switching                                           │
│                                                                      │
│  Current mode            Screen             ▾                        │
│  ─ Blue button cycles through ─────────────────────────────────────  │
│  ✓ Screen                                                            │
│  ✓ Music                                                             │
│  ✓ Effect                                                            │
│    Single colour                                                     │
│  Long press Blue         Next mode          ▾  (Next mode / Nothing) │
│  Start in                Last mode          ▾  (Last mode / Screen)  │
└──────────────────────────────────────────────────────────────────────┘
```

* **Default rotation: Screen, Music and Effect.** At least one mode must stay ticked.
* "Start in" decides the mode when the lights start after a reboot or a Start action.

### 11.2 What the Blue button does

A long press goes to the next mode; a short press changes what plays inside the mode.

| You're in | Short press | Long press (hold ≥ 0.6 s) |
|---|---|---|
| Screen | Nothing (Screen has no variants) | Next mode in the rotation (default: Music) |
| **Music** | **Next music effect**, wrapping round, skipping those not in the Blue rotation (Strobe beat ⚡ by default) | Next mode (default: Effect) |
| **Effect** | **Next effect**, wrapping round, skipping effects not in the Blue rotation | Next mode (default: Screen) |
| Single colour | Nothing | Next mode in the rotation |

* With the defaults, holding Blue goes Screen → Music → Effect → Screen; short presses step through music effects
  in Music mode and effects in Effect mode.
* Each press shows the message (if on) and fades the LEDs to the new mode in 300 ms.
* If the lights are off, Blue turns them on in the current mode.

## 12. Implementation rules

* Platform `Activity` + Views, written in Kotlin; no AppCompat, Material or Compose (`TECHNICAL.md` §4.1).
* One `SettingsActivity` with the two-pane layout (§5.2): a section list on the left, a row container on the
  right. Sections aren't separate activities, so switching is instant and focus memory is simple.
* Settings are **declared as data**: one Kotlin table of sections → groups → rows, each row with its type (§4.2),
  preference key, range or choices, default, hint, and an optional "applies when" condition. The UI is generated
  from it, which keeps every screen consistent and makes adding a setting one table entry.
* Five row views, one per row type (§4.2), all subclasses of one `SettingRow` base that handles focus, hint and
  dimming.
* Screens drawn as standalone wireframes elsewhere in this file (Remote buttons §8, Stats overlay §9.3, Mode
  switching §11.1) render in the right pane like every other section; the wireframes show only the pane's content.
* The live preview is one custom `View` drawing rectangles on a `Canvas`; it reads colours from the service through
  a shared direct buffer and redraws only while visible.
* Strings in `res/values/strings.xml` from day one, so translations are possible later.
* Each screen is checked on the TV: D-pad from every edge, Back from every screen, nothing unreachable.
* **Navigation test:** starting from Home, reach each of these within the step counts in §1 (principle 2) with the
  remote only, and time it: change brightness, pick a music effect, set the audio delay, change the Blue button,
  show the stats overlay, run the LED test pattern. Anything that takes longer than 10 s, or that someone new to the
  app can't find without help, gets moved or renamed.
