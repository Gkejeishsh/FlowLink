# LoopJet — Round 5 design & motion plan

**Status: proposal. Nothing in this document has been implemented yet.**

Goal: make LoopJet feel like a top-tier product — super-fluid motion, a confident type
system, deliberate placement — without touching the transfer engine, without losing a byte
per second, and without introducing a single new permission.

---

## 1. What the app actually has today (audited, not assumed)

| Area | Current state (verified in source) |
|---|---|
| Navigation | A single `Crossfade(240ms)` in `MainActivity`. Every screen switch is a plain opacity blend — no direction, no shared elements, no predictive back (`BackHandler` only). |
| Motion | One press-scale spring (ζ 0.55 / k 700), one radar loop, one `AnimatedVisibility`. **Zero** `animateItem`, `animateContentSize`, `animateColorAsState` in the whole UI. |
| Haptics | **None.** `LocalHapticFeedback` and `performHapticFeedback` appear 0 times in the codebase. |
| Typography | Public Sans variable bundled, all 15 Material styles mapped. Good base; no hero/numeral styles, uniform tracking. |
| Colour | Brand blue/violet/cyan, no `surfaceContainer` tiers → every card is the same flat surface with a 1 px border. No success colour. |
| Spacing | Ad-hoc: 10, 11, 12, 14, 16, 18, 20, 28 dp gutters all in use. No 4 dp grid discipline. |
| Shapes | 10 / 14 / 20 / 28 / 34 dp. No shape morphing on press. |
| Composition-time I/O | `vm.history()` (reads the history JSON from disk) and `vm.deviceSummary()` (three WifiManager calls) are called **inside Home's composable body**, i.e. on every recomposition. |
| App icons | `AppRepository.load()` eagerly calls `getApplicationIcon()` for **every** installed app and keeps all Drawables in `PickerState.apps`. ~200 apps ≈ tens of MB resident. |
| Dependencies | Compose BOM 2026.09, material3 **1.4.0**, animation **1.12.1** (shared transitions stable), minSdk 26. |

Two consequences worth stating plainly:

- The "fluid" gap is not a matter of tweaking durations — the app has no motion *system*, no
  continuity between screens, and no tactile layer at all.
- The biggest perceived-speed wins are mundane: removing disk/IPC from composition and not
  holding 200 app icons in memory.

---

## 2. Design direction: **Jet Current**

One metaphor, applied everywhere: **a current of air moving through the UI**. Elements are
carried in, they settle with a small overshoot, and energy is shown as motion — never as noise.

What that means concretely:

- **Motion carries meaning.** Things enter from where they were born (the button you tapped,
  the row you ticked), not from off-screen.
- **Overshoot is signal, not decoration.** Only spatial properties bounce (ζ 0.6–0.8). Colour
  and opacity never bounce (ζ 1.0), because a bouncing fade reads as a stutter.
- **Type does the branding.** Restrained colour, bold hierarchical type, big numbers with
  tabular digits so speed readouts don't jitter.
- **Depth from tone, not shadow.** Layered `surfaceContainer` tiers instead of 1 px borders.
- **One accent gradient** (blue → violet). Cyan becomes a 1 px highlight only. Today's
  three-stop gradient muddies in the middle.
- **Satisfying = quiet + responsive.** No confetti, no particle bursts, no bouncy-bounce on
  every tap. Spring convergence plus a haptic tick.

---

## 3. Design tokens (the foundation everything else stands on)

### 3.1 Motion — the six springs

Values are the published Material 3 Expressive spring constants (spatial ζ 0.6/0.8, stiffness
800/380/200; effects ζ 1.0, stiffness 3800/1600/800). They are defined in **our own**
`JetMotion` object rather than imported from material3, because the expressive theme APIs are
still partly internal in material3 1.4.0 — our tokens compile today, and cannot be broken by a
library bump.

| Token | ζ | k | Settle | Used for |
|---|---|---|---|---|
| `fastSpatial` | 0.6 | 800 | ≈360 ms, ~9 % overshoot | tick circles, chips, icon press, chips' indicator |
| `defaultSpatial` | 0.8 | 380 | ≈440 ms, ~1.5 % | cards, tray, sheets, hero morphs, corner morphs |
| `slowSpatial` | 0.8 | 200 | ≈600 ms | screen transitions, radar rings |
| `fastEffects` | 1.0 | 3800 | ≈150 ms | colour/alpha on small elements |
| `defaultEffects` | 1.0 | 1600 | ≈240 ms | cross-fades, surface tone changes |
| `slowEffects` | 1.0 | 800 | ≈330 ms | full-screen fades |

Degradation ladder (already half-implemented as `MotionSettings`, extended):

1. **Full** — all springs as above.
2. **Reduced** (`rich = false`: power save or low-RAM device) — overshoot removed
   (ζ 1.0 everywhere), durations kept, radar/sparkline loops stop.
3. **Off** (system animator scale ≈ 0 / user toggle) — `snap()`, everything instant, nothing
   breaks layout.

New Settings row: **Motion: Full / Reduced / System**, default Full, respects the system
animation scale automatically.

### 3.2 Spacing — 4 dp grid

`4 · 8 · 12 · 16 · 20 · 24 · 32 · 48`.
Rules: screen gutter **20**, card padding **20**, list row vertical **10**, section gap **24**,
inline gaps **8/12**. Every ad-hoc 11/14/18 dp value in `Screens.kt` migrates onto the grid
(cosmetic only, no layout re-architecture).

### 3.3 Radii & shape morph

Scale: `12 / 18 / 26 / 34` + `full` (pill).
Press morph (new, expressive): cards **26 → 20** while held, buttons **pill → 18**, tick circle
**13 → 10** — all on `fastSpatial`, all via `graphicsLayer` so no relayout.

### 3.4 Type scale (Public Sans, variable `wght`)

| Style | Size / line | Weight | Tracking | Notes |
|---|---|---|---|---|
| `displayHero` *(new)* | 56 / 58 | 300 | −2.0 % | the live speed number, completion headline |
| `displayLarge` | 48 / 52 | 700 | −1.8 % | onboarding headlines |
| `headlineLarge` | 28 / 34 | 700 | −0.9 % | screen titles |
| `headlineMedium` | 22 / 28 | 600 | −0.5 % | card titles |
| `titleLarge` | 19 / 26 | 600 | −0.2 % | |
| `titleMedium` | 16 / 22 | 600 | 0 | |
| `bodyLarge` | 16 / 24 | 400 | +0.1 % | |
| `bodyMedium` | 14 / 21 | 400 | +0.15 % | |
| `labelLarge` | 14 / 18 | 600 | +0.6 % | chips, buttons |
| `labelMedium` | 12 / 16 | 600 | +0.8 % | captions, stats |
| `numeral` *(new)* | inherits | 600 | −0.5 % | `fontFeatureSettings = "tnum"` |

Two structural changes beyond sizing:

- **Add weight 300** to the bundled family (the variable font already contains it) for
  `displayHero` — large type at light weight is what makes a number look engineered rather
  than shouted.
- **Tabular numerals** for every live number (speed, ETA, %, bytes). Without this, digits
  re-shuffle their width several times a second and the readout visibly shivers. If the face
  lacks `tnum` on some OEM renderer, the fallback is a fixed-width digit grouping in the UI
  formatter — decided at implementation time, tested by a unit test on the formatter.

### 3.5 Colour & depth

Keep the brand. Add what's missing:

| Addition | Light | Dark |
|---|---|---|
| `surfaceContainerLow` | `#F7F8FC` | `#0E131C` |
| `surfaceContainer` | `#F1F3FA` | `#131926` |
| `surfaceContainerHigh` | `#E9ECF7` | `#1A2130` |
| `surfaceContainerHighest` | `#E1E5F4` | `#222A3B` |
| `success` *(new)* | `#17B26A` | `#4ADE80` |
| `warning` *(new)* | `#F59E0B` | `#FBBF24` |

- Dark keeps true black (`#000000`) — OLED battery and the "no glow" look the current build
  already has. Depth comes from the container tiers above.
- Cards move from *border* to *tone*: `surfaceContainerLow` on `surface`, no 1 px stroke
  except on interactive rows.
- Brand gradient becomes two-stop `#2F6BFF → #7C5CFF` at 135°; cyan survives only in the mark.
- **Contrast is tested, not eyeballed.** Palette constants move to a plain-`Int` object, and a
  JVM unit test asserts WCAG ratios ≥ 4.5:1 for every text/background pair we use, both
  themes. That adds tests (currently 31 → ~38) and makes "dark themes → light text" provable.

---

## 4. Motion system, screen by screen

### 4.1 Navigation (biggest single change)

Replace `Crossfade` with a **fade-through** transition, with direction:

```
outgoing: alpha 1→0   (fastEffects)      + scale 1→0.92 + 8 dp lift
incoming: alpha 0→1   (fastEffects)      + scale 1.04→1  (slowSpatial)
```

Rules:

- **Forward** (Home → Picker → Nearby → Transfer): incoming scales up from 1.04, outgoing
  recedes to 0.92.
- **Back**: reversed, so back feels like retracing a path, not a new navigation.
- `AnimatedContent` with `contentKey = { it }`, and — importantly for efficiency — each
  screen receives **only its own slice of state**, so a notice or a free-space refresh no
  longer recomposes the whole tree.

**Predictive back**: wrap the content in `PredictiveBackHandler`; while the gesture is in
progress the current screen follows the finger — scale to 0.92, corner radius 0 → 26 dp,
horizontal offset — exactly the pattern Android users now expect. Falls back to the existing
`BackHandler` when the API/OS doesn't report progress.

**Shared elements** (2–3 per transition, no more — the guidance is 3–5 max, and each one
costs modifier instantiation in a lazy list):

| Element | From → To |
|---|---|
| Picked file thumbnail | picker row → tray chip |
| Peer avatar / name | Nearby row → Transfer hero card |
| LoopJet mark | Home → Receive (the mark becomes the radar centre) |

Attached only while the item is visible (`onVisibilityChanged`), so scrolling a 20 000-row
picker pays nothing for off-screen rows.

### 4.2 Micro-interactions

| Event | Animation | Haptic |
|---|---|---|
| Tick a file/app | circle fills on `fastSpatial`, check path draws (160 ms), row background fades (`fastEffects`) | `ToggleOn` |
| Untick | fill recedes, check erases | `ToggleOff` |
| Press a card | radius morph 26→20 + scale 0.97 | — |
| Long-press | row lifts to `surfaceContainerHigh`, context menu springs in | `LongPress` |
| Tray appears | height 0→92 dp on `defaultSpatial`, 3 thumbnails fan in staggered 20 ms | — |
| Selection count changes | odometer roll of the digits, 180 ms | — |
| Send tapped | button squashes 1→0.94→1, then the screen transitions | `Confirm` |
| Pairing succeeds | peer row morphs into the hero card; two rings pulse outwards once | `Confirm` |
| Transfer fails | error card slides in, icon shakes 2× (2 dp amplitude, 240 ms) | `Reject` |
| Copy / allow / settings jumps | snackbar with spring | — |
| Completion | progress ring morphs into a check (path draw), then the action row staggers in | `Confirm` |
| Drag-to-dismiss sheet | sheet tracks finger 1:1, releases on `fastSpatial`, scrim follows | `GestureStart/End` |

Every one is gated by `JetMotion` — with motion off, all of it becomes an instant state change.

### 4.3 Live data feel (transfer screen)

- Speed readout uses `displayHero` + tabular digits, and **updates at 4 Hz, not 10 Hz** —
  animation of the value itself (short `animateFloatAsState` 250 ms) smooths the gaps, so the
  number glides instead of flickering.
- Sparkline (`SpeedGraph`, already present) gains a gradient fill + a moving head dot; the
  window normalises to the session's peak so it never flatlines.
- Progress ring: gradient sweep, thickness +2 dp while active, and a slow 4 s ambient shimmer
  arc *only* at full motion.
- Ring → checkmark morph at completion (single path morph, no library).

---

## 5. Placement & arrangement (screen by screen)

### 5.1 Home — today

```
[mark] LoopJet            [gear]
       device name
[  Send  ] [ Receive ]
MORE WAYS
[Invite ] [Web Share]
[History] [Settings]     ← Settings duplicated with the gear
[ This phone ]
  Network ...
  Free space ...
  Trusted devices ...
```

Problems: Settings twice; "MORE WAYS" is shouting caps; History uses a *refresh* icon (wrong
semantics); no live-state presence; "This phone" card eats the bottom third; nothing hints at
speed, which is the product.

### 5.2 Home — proposed

```
[mark] LoopJet                        [gear]
       Pixel 8 · Wi-Fi 5 GHz  ● live
┌────────────────────────┐ ┌──────────┐
│  Send                  │ │ Receive  │
│  ↑ 2.4 GB ready        │ │   ⌁      │
│      (big, primary)    │ │  radar   │
└────────────────────────┘ └──────────┘
Recent
[📄 IMG_2201 · 12 MB] [🎬 clip.mp4 · 88 MB] [📦 app.apk · 40 MB]
                             ← one tap re-opens selection
┌ live session ───────────────────────┐
│ ● Sending to Rana  47 MB/s  ▓▓▓░░  │
└─────────────────────────────────────┘
[Invite] [Web Share] [History] [Settings]   ← proper icons, or move to a "More" sheet
```

- **Device chip with live band** replaces the plain device name; the network string is
  cached (see §7) and refreshes on resume.
- **Asymmetric hero**: Send is the wide card, Receive is the compact accent card with the
  radar motif. Send is what people do most; the layout should say so.
- **"Recent" strip** — the last 3 selections from history, one tap to re-pick. New feature,
  purely local, reuses `HistoryEntry`.
- **Active session becomes a live row** (speed + mini bar), replacing the current banner.
- **Settings appears once** (gear). The History tile gets `DateRange`, not `Refresh`.
- **"This phone" card** shrinks to two chips (Network, Free space) on Home; the rest moves to
  Settings › About.

### 5.3 Picker — today vs proposed

Today: a row of 9 text chips + a sort toggle + a plain list + a bottom bar. It works, but every
chip is the same weight, and the tray (the star of the multi-source flow) is a plain row.

Proposed:

```
[ Search files…                    ✕ ]  [Gallery]
[ Apps ][ Photos ][ Videos ][ More ▾ ]     ← sliding pill indicator
❰ Up ❱  Internal storage ❯ DCIM ❯ Camera   ← breadcrumb, crumbs compress to "…"
─────────────────────────────────────────
IMG_2201.jpg   2.4 MB · Today        ◉
clip.mp4      88 MB · Yesterday      ○
─────────────────────────────────────────
 Send 3 · 92.4 MB        [▣▣▣] Clear  〉
```

- **Segmented categories** with a spring-driven sliding indicator; 4 primary + "More" sheet
  for the rest (all 9 stay reachable).
- **Search** grows on focus (width + elevation), shows "N results", and a clear button that
  scales in. Keyboard opens on tap, closes on scroll.
- **Long-press = range select**; a compact selection header offers All / None / Invert.
- **Tray** shows up to 3 fanned thumbnails (shared elements from the tapped rows), the total,
  and a Send button that squashes on press. Clear is an icon + **undo snackbar**.

### 5.4 Transfer

```
        ╭───────────────╮
        │    47.2       │   ← displayHero + tabular
        │    MB/s       │
        ╰───────────────╯
        Rana's Pixel  ● Wi-Fi 5 GHz (fastest path)
        ▓▓▓▓▓▓▓▓░░░░  68 %   ·  1:12 left
┌ IMG_2201.jpg ▓▓▓▓▓▓▓ 100 %  ✓ ┐
┌ clip.mp4    ▓▓▓▓▓░░░░  41 %   ┐
[ Pause ]            [ Cancel ]
```

Completed rows collapse into a summary line (`animateContentSize`); failures get a clear
retry affordance and `error` tint. The path chip states which link is in use, with the
existing caption wording preserved (it is already accurate).

### 5.5 Receive / Nearby / Settings / Onboarding

- **Receive**: the mark becomes the radar centre; rings tighten while a peer is found; the
  code card lifts when a scanner is confirmed. Scan-only flow unchanged.
- **Nearby**: each peer row gains a path badge (LAN / Hotspot / Direct) and an animated
  signal-strength pip row; the selected row morphs into the hero on connect.
- **Settings**: grouped cards with labels on the left and controls on the right; speed mode
  becomes a segmented control with a one-line live explanation; new **Motion** row and
  **Appearance** (System / Light / Dark). Nothing removed.
- **Onboarding**: 3 full-height slides on a pager with spring snapping and a morphing page
  indicator; the permissions step keeps its current, honest copy.

---

## 6. Functionality & features proposed (each is optional)

| # | Feature | Value | Risk |
|---|---|---|---|
| 1 | Range-select + All/None/Invert in the picker | big for many files | low (UI state only) |
| 2 | Undo after "Clear" | prevents a costly mis-tap | low |
| 3 | Recent strip on Home (re-pick last selection) | removes repeat navigation | low (reads local history) |
| 4 | File-type filter inside a folder (Images / Video / Docs / All) | makes 5 000-file folders sane | low |
| 5 | Path badge on peer rows (LAN / Hotspot / Direct) | explains speed differences | low (already computed) |
| 6 | Motion setting (Full / Reduced / System) | accessibility + battery | low |
| 7 | "Open folder when finished" option | removes the last tap after a transfer | low |
| 8 | History: day grouping, swipe-to-delete, tap-to-resend | makes the screen useful | low |
| 9 | Long-press a file → info sheet (path, size, date, mime) | answers "which file is this?" | low |
| 10 | Per-file pause/skip during a transfer | nice | **high — needs engine changes; excluded** |

Everything in this table except #10 is UI/state only. #10 is called out explicitly as *not*
part of this plan because it would touch `core/`, which is the one thing that must not change.

---

## 7. Efficiency (verified findings → fixes)

| # | Finding | Fix | Effect |
|---|---|---|---|
| E1 | `vm.history()` reads the history JSON **inside** Home's composition, and again for the count | move to `state.historyCount` / cached list, refreshed on entry and after a transfer | removes disk I/O from the frame |
| E2 | `vm.deviceSummary()` performs 3 WifiManager calls inside composition | snapshot into `AppState` on a 3 s ticker (the address watcher already exists) | removes IPC from the frame |
| E3 | `Crossfade` + whole `AppState` passed to every screen → any state change recomposes the active screen tree | `AnimatedContent` + per-screen state slices (`PickerState`, `TransferSlice`, …) | fewer recompositions per state emission |
| E4 | `AppRepository.load()` loads a Drawable for every installed app | metadata only + `AppIconCache` (LRU 64, decoded per visible row on IO) | ~200 Drawables → ≤64 small ones; faster first open of Apps |
| E5 | Animated values would be read in composition by naive implementations | all motion via `graphicsLayer` / `drawBehind` (phase deferral) | animations skip composition & layout entirely |
| E6 | Every card rebuilds its `Brush.linearGradient` per recomposition | `remember` brushes/colors per theme | allocation per frame gone |
| E7 | Sort/filter changes swap list contents with no animation | `Modifier.animateItem()` on picker rows | movement becomes legible instead of a jump |
| E8 | Thumbnail slot shows a text badge until decode | shimmer skeleton + 120 ms cross-fade (LRU unchanged) | perceived speed, no new decode work |
| E9 | `totalSelected` / `totalBytes` are recomputed getters on a data class | `derivedStateOf` in the tray composable | trivial, but removes per-frame summation over the selection |
| E10 | Shared-element modifiers attached to every lazy row | attach only when visible | avoids prefetch overhead in flings |

Explicitly **not** part of this plan: any change to `core/`, `platform/net/`, the wire format,
stream counts, buffers, or the transfer scheduler. `git diff --stat core/ platform/net/` must
stay empty — that is the regression guard, and it stays the gate for every phase.

---

## 8. Guardrails (what will not change)

- No new permissions, no network calls, no fonts or assets fetched at runtime.
- Scan-only pairing; no typed code, no pasted link.
- One tray, all sources; in-app folder browser; gallery button; working search.
- No `.part`/scratch files; hidden `.loopjet-partial` staging stays exactly as it is.
- 31 existing tests stay green; `core/` diff stays empty; loopback throughput must not drop.
- APK size: currently 2,442,687 B. Budget **+120 KB** for this round. (Nothing in this plan
  needs a new dependency; if shape morphing via `graphics-shapes` is approved, that adds
  ~40–60 KB and would be the only new artifact.)
- minSdk 26 stays; no API-level regressions (predictive back and haptics degrade gracefully).

---

## 9. Phases, risk and effort

| Phase | Contents | Risk | Est. |
|---|---|---|---|
| **A — Foundations** | tokens (§3): spacing grid, radii, surface tiers, success/warning, type scale incl. `displayHero` + `numeral`, `JetMotion` object, contrast unit tests, Settings › Motion row | very low (visual only) | 1× session |
| **B — Navigation & continuity** | fade-through `AnimatedContent`, predictive back, 3 shared elements, screen entrance choreography, per-screen state slices (E3) | medium (touches navigation + state plumbing) | 1–2 sessions |
| **C — Screen redesign** | Home, Picker (segmented categories, tray, breadcrumb), Transfer hero, Receive radar, Settings grouping | medium | 2 sessions |
| **D — Micro-interactions** | tick/haptic map, odometer counts, ring→check morph, shimmer thumbnails, empty states, sheet drag-dismiss | low–medium (density of small changes) | 1–2 sessions |
| **E — Efficiency & polish** | E1, E2, E4, E6–E10, animateItem, final audit | low (contained, measurable) | 1 session |

Each phase ends with: CI green (compile + tests), APK rebuilt and signed with the same key,
APK introspection (dex strings, font, permissions), `core/` diff empty, and a BUILD-NOTES
entry. Nothing ships half-migrated: if a phase breaks CI, it is reverted rather than patched
forward.

**Recommended order: A → E → C → D → B.** Efficiency first (E) because it makes everything
after it smoother on real phones; navigation last (B) because shared elements are the one item
most likely to need iteration, and they land best on top of the finished screen layouts.
If you prefer visible change first, A → C → D → B → E also works — just with more rework in C.

---

## 10. Verification — and its honest limits

Verifiable here, every phase:

- Kotlin compile, 31 existing tests + new pure-JVM tests (contrast ratios, motion token settle
  times, formatter/`tnum` behaviour, selection reducer).
- APK introspection: dex strings for new copy, bundled font, permission list from the binary
  manifest string pool, single dex, no `debuggable`.
- `core/` / `platform/net/` byte-identical; loopback benchmark not lower than the previous run.

**Not** verifiable here (no device, emulator, or GPU): how the springs actually feel, frame
timing, the predictive-back handoff, haptic quality, and real text rendering. Those will be
built to spec and reviewed by you on the phone; anything that feels off gets tuned by number,
not by guesswork.

---

## 11. Decisions I need from you

1. **Scope**: all five phases, or start with A + E (foundations + efficiency, lowest risk) and
   judge the feel before the redesign?
2. **Home boldness**: asymmetric hero (Send wide, Receive compact) as sketched, or keep two
   equal cards?
3. **Shape morphing**: approve the optional `androidx.graphics:graphics-shapes` dependency
   (~40–60 KB) for true path morphs (radar blob, ring→check, blob-shaped buttons), or stay
   dependency-free with radius/scale morphs only?
4. **Dark theme**: keep true black (current) or move to a softly lifted `#07090F` that makes the
   surface tiers more visible?
5. **Small features**: which of §6 to include (my pick: 1, 2, 3, 5, 6, 9).
