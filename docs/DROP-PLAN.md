# FlowLink Drop — plan for a hotspot + browser receive app

**Status: plan only. Nothing built yet. Waiting for approval.**

The brief: a new app, fresh from scratch, whose only job is *someone else's browser sends files to my
phone*. My phone hosts a Wi-Fi hotspot with an OS-generated name and password (the kind NetShare and
PDANet make), shows both on screen, the other person joins it, then opens a short link in Chrome — or
scans a QR — and drops apps, documents, photos, anything into a page that lands directly in my app.

---

## 1. Why this design is right, in one paragraph

Every connection problem in the previous app came from **not owning the network**. Two phones had to
find each other across a router neither of them controlled: discovery, subnets, client isolation,
which interface to bind, whose address was stale. This design deletes that entire class of failure.
The phone *is* the access point, so there is exactly one possible address (`192.168.49.1`), exactly
one route (the hotspot), and the client is a browser that already knows how to talk HTTP. There is no
peer discovery, no key exchange, no firewall, no "both phones on Wi-Fi but not the same Wi-Fi".

It also removes the biggest practical constraint of the old app: **the other person does not need
FlowLink at all.** Any phone, any laptop, any tablet with a browser works — including iPhones and
Windows machines, which the peer-to-peer version could never support.

---

## 2. What already exists in this repo (and is worth keeping)

The previous app already contains the hard, proven parts of this design. The new app is a *rebuild of
the product*, not a rewrite of the plumbing:

| Piece | File | State | Verdict |
|---|---|---|---|
| Local-only hotspot with OS-generated SSID + password | `platform/net/Hotspot.kt` | working | **keep**, verify on device |
| HTTP/1.1 server: keep-alive, range requests, 256 KiB buffers | `core/Http.kt` | 52 tests around it | **keep**, trim routes |
| Browser→phone upload endpoint (`POST /api/upload?name=`) | `core/Http.kt` | works, writes `.part` then renames | **keep**, extend |
| HTML page with upload UI | `core/Pages.kt` | functional, plain | **replace** with a designed page |
| Safe path handling, unique names, disk-space guard | `core/Files.kt` | tested | **keep** |
| Gallery publishing via MediaStore | `platform/storage/Media.kt` | works | **keep** |
| QR generation / scanning | `ui/QrScanner.kt`, `ui/Theme.kt` | works | **keep**, redesign |
| Peer-to-peer engine, discovery, beacons, mDNS, crypto session | `core/Engine.kt`, `core/Crypto.kt`, `platform/net/Discovery.kt` | ~4 000 lines | **delete** |

That last row is the point of "start from scratch": roughly 60 % of the current codebase exists to
solve a problem this app no longer has.

---

## 3. Product definition

### 3.1 The three screens

```
┌──────────────────────────────┐   ┌──────────────────────────────┐   ┌──────────────────────────────┐
│  HOST (the only screen that  │   │  RECEIVED                    │   │  SETTINGS                    │
│  matters)                    │   │                              │   │                              │
│                              │   │  ┌────────────────────────┐  │   │  Save to   Downloads/FlowLink│
│   ┌──────────────────────┐   │   │  │ report.pdf   2.4 MB  ✓ │  │   │  PIN           none          │
│   │                      │   │   │  ├────────────────────────┤  │   │  Ask before   off            │
│   │        QR            │   │   │  │ app-release.apk 61 MB │  │   │  Keep awake   on             │
│   │                      │   │   │  └────────────────────────┘  │   │  Theme        system         │
│   └──────────────────────┘   │   │                              │   │                              │
│   192.168.49.1:8080          │   │  grouped by day, tap to open │   │  About / diagnostics         │
│                              │   │                              │   │                              │
│   Hotspot   AndroidShare_12  │   └──────────────────────────────┘   └──────────────────────────────┘
│   Password  h7Kd2mQp         │
│   [ copy ]     2.4 GHz       │
│                              │
│   ● waiting for a browser    │
│   ┌────────────────────────┐ │
│   │ photo.jpg   34% ▓▓▓░░  │ │   ← live, animated, appears only when something is arriving
│   └────────────────────────┘ │
└──────────────────────────────┘
```

**Host** is the app. Opening it starts the hotspot and shows everything a human needs in three
seconds: a QR, a typeable address, the hotspot name and password. No menus, no "Send/Receive"
choice, no mode switch.

**Received** is the history: what arrived, when, how big, open it or share it onward.

**Settings** is deliberately short. Everything else is a default.

### 3.2 The browser page (this is the "cool" part — it is what people see)

The page is the product's face, because the other person spends their entire interaction there.

```
┌─────────────────────────────────────────────┐
│  Drop files to Talal's phone                │   ← hero, the phone's own name
│  They appear instantly. Nothing is uploaded │
│  to the internet.                           │
│                                             │
│   ╭───────────────────────────────────────╮ │
│   │            ⬇  (drop zone)             │ │   ← breathes when idle,
│   │    Tap to choose, or drop files here  │ │     glows on drag-over
│   ╰───────────────────────────────────────╯ │
│                                             │
│   report.pdf        2.4 MB   ▓▓▓▓▓▓░░ 78%   │   ← staggered entry, spring progress
│   holiday.mp4       212 MB   ▓▓▓░░░░░ 31%   │
│   app-release.apk    61 MB   ✓ sent          │
│                                             │
│   3 files · 275 MB · 18 MB/s                │
└─────────────────────────────────────────────┘
```

* Single self-contained HTML file, **zero external requests** (no CDN, no fonts, no analytics) — it
  must work on a network with no internet, because that is literally the network it runs on.
* Dark glass/gradient aesthetic matching the app, one accent colour, big generous type.
* Drag-and-drop, tap-to-pick, multi-select, folder pick (later phase), per-file progress with
  retry, cancel, and a completion state with a check animation and haptics on the phone.
* Total budget: **under 30 KB uncompressed**, no build step, works in Chrome/Safari/Firefox/Edge,
  including iOS Safari.

### 3.3 What a person actually does

1. **Phone:** open FlowLink Drop. Hotspot starts, QR appears. (~3 s, automatic.)
2. **Other person:** joins `AndroidShare_1234` / `h7Kd2mQp`. (Android warns "no internet" — expected,
   the page says so.)
3. **Other person:** scans the QR (opens Chrome) *or* types `192.168.49.1:8080` — 15 characters, no
   path, no token, no scheme needed in modern Chrome.
4. **Other person:** drops files. Phone shows them arriving live.
5. **Phone:** files land in `Downloads/FlowLink`, gallery items appear in Photos immediately.

---

## 4. Technical architecture

### 4.1 Hotspot — the one genuinely hard part

Android's public API for "an access point with a password the OS invents" is
`WifiManager.startLocalOnlyHotspot()`. It returns a reservation whose `softApConfiguration` contains
the SSID (e.g. `AndroidShare_4821`) and a random WPA2 passphrase — exactly the NetShare/PDANet
behaviour the brief asks for. It does **not** need a SIM and is not tethering.

Constraints I have to design around, all of them real:

| Constraint | Consequence for the design |
|---|---|
| Needs `NEARBY_WIFI_DEVICES` (API 33+) / location (older) | ask once, on first launch, with a plain-language reason |
| Wi-Fi radio must be **on** (not connected, just on) | detect and offer a one-tap "turn on Wi-Fi" intent |
| The reservation dies with the process | must live in a **foreground service** with `WifiLock` + `PARTIAL_WAKE_LOCK` |
| Band cannot be chosen (public API) | show the granted band; 2.4 GHz realistically caps ~8–10 MB/s, 5 GHz ~40–50 MB/s |
| Some OEMs (Xiaomi/HyperOS, some Samsung builds) refuse it | fallback chain below |
| AP address varies by vendor (`192.168.49.1`, `192.168.43.1`, …) | never hardcode; read it from the AP interface and print it |

**Fallback chain**, tried in order, each with its own visible state on screen:

1. `startLocalOnlyHotspot` — primary. OS-generated SSID + password.
2. **Wi-Fi Direct group** (`WifiP2pManager.createGroup`) — also gives an OS-generated
   `DIRECT-xx-…` SSID and random passphrase, and works on most devices that block LOH.
3. **The user's own hotspot** — the app can't create one, so it explains how to turn it on and then
   reads the address to serve on. Manual, but never a dead end.

### 4.2 Server

* Bind `0.0.0.0` (the AP interface is not always known at bind time), **advertise only the AP's own
  IPv4 address** — never print `0.0.0.0`.
* Fixed port **8080** for a typeable link; if taken, walk `8081…8095` and show whatever was bound.
* Routes (deliberately few):

| Route | Purpose |
|---|---|
| `GET /` | the drop page |
| `POST /api/upload?name=<urlencoded>` | one file, raw body, `Content-Length`, returns JSON |
| `GET /api/ping` | used by the page to show "phone reachable" and by tests |
| `GET /favicon.ico` | inline SVG, kills a 404 in every browser log |
| `GET /api/info` | phone name, free space, max upload, optional PIN challenge |

* Upload pipeline: `name.part` in a hidden staging dir → fsync → rename into place → MediaStore
  index for images/video/audio → event to the UI. **No partial file is ever visible**, and the
  staging folder removes itself when empty (a rule the user set and that stays).
* Guards: per-file size cap, free-space check with 32 MB headroom, path traversal rejected by the
  existing `SafePath`, duplicate names de-duplicated (`report (1).pdf`), request read timeout so a
  client that vanishes cannot pin a thread forever.
* Concurrency: a small fixed pool (4), so three phones/laptops can upload at once; uploads are
  independent and can interleave.

### 4.3 Security model, stated honestly

The hotspot password **is** the authentication: only someone given it can route to the phone at all.
On top of that:

* No session token in the URL (a token is what made the last link change every run — it's the thing
  the user explicitly hated). The address stays constant.
* **Optional** four-digit PIN in Settings, off by default, for "I'm hosting at a café" situations.
* Optional "ask me before a new device uploads" toggle, off by default, on by default only if the
  user turns the PIN on. Every new client address is remembered for the session either way.
* No file can be *read* from the phone over HTTP. The server has no download route at all — it is a
  one-way door by construction, not by policy.

### 4.4 App identity and "delete the previous app from start"

Recommended:

* New `applicationId` **`com.flowlink.drop`**, app name **"FlowLink Drop"**, fresh preferences file,
  no migration, no legacy history, no import of old settings.
* New release keystore (the old one is gone anyway — the private key did not survive the sandbox
  reset, which is why 1.0.3 needed an uninstall).
* The old app (`com.loopjet.app`) is uninstalled by the user; the two can coexist for one test cycle,
  which is useful if the new app misbehaves on the actual device.
* In the repo: a **new module** `drop/` alongside the current `app/`, sharing nothing at the UI layer,
  copying the four proven core files (Http, Files, Media, Hotspot). The old module is **deleted in the
  final phase**, after the new app is confirmed working on the user's phones. Until then the old APK
  is the fallback. This is reversible at every step, which "delete everything first" is not.

---

## 5. Design language and motion

Reuse the foundations already built and tested in this repo (palette with WCAG assertions, six spring
tokens, `hero` numeral style), applied to a much smaller surface.

**Look:** near-black background in both themes (dark-first, light theme available), one accent
gradient (`#255CE8 → #8B5CF6`), generous 26 dp radii, cards by tone rather than borders, one typeface
at three weights, tabular digits for anything that counts.

**Motion (all spring-based, all interruptible, all suppressed when the system animation scale is 0
or the phone is in power-save):**

| Moment | Motion |
|---|---|
| App opens, hotspot starting | the QR card scales in from 0.94 with a 1.4 s breathing ring behind it |
| Hotspot ready | ring settles, credentials slide up with a soft staggered fade, one confirm haptic |
| Browser connects | radar pulse stops, the status line morphs to "someone's here", haptic tap |
| File arrives | row springs in from the top, progress bar chases the real value on a fast spring |
| File done | bar fills, icon flips to a check with a small overshoot, `ToggleOn` haptic |
| Everything sent | one burst: card lifts 2 %, check draws itself, `Confirm` haptic, then settles |
| Failure | shake (±4 dp, 3 cycles, 180 ms) + error-tinted row, `Reject` haptic |

Every animation is tied to a real state change — none run on a timer, none run while a transfer is
copying bytes (the frame budget belongs to the network).

---

## 6. Phases, step by step

Each phase ends with something installable and testable on the two phones. No phase depends on a
later one to be useful.

### Phase 0 — Decisions (blocking, needs the user)
Confirm the four open questions in §8. ~0 work, but everything after assumes the answers.

### Phase 1 — Skeleton that proves the network
New module `drop/`, new `applicationId`, foreground service, `startLocalOnlyHotspot`, AP address
detection, a trivial "hello" page on `:8080`, QR + address + SSID + password on screen.
**Acceptance:** the other phone joins the hotspot by typing the password, opens the address in
Chrome, sees "hello", with the phone's screen off and the app in the background.
No upload code yet — if this phase fails on the user's devices, nothing else matters, and we learn it
in a day rather than after a rewrite.

### Phase 2 — Uploads that land correctly
`POST /api/upload`, staging + rename, MediaStore index, de-duplication, size and disk guards, live
count on the phone, foreground-service notification with progress.
**Acceptance:** a 2 GB video, a 60 MB APK, and 200 small files all arrive byte-identical (checksum
verified by the test harness), nothing `.part` left behind, no partial file ever visible, phone
survives a client that disconnects mid-upload.

### Phase 3 — The browser page
Designed single-file page: drop zone, multi-file queue, per-file progress, retry, cancel, totals,
"no internet here, that's normal" note, empty/error/offline states, phone-name greeting, free-space
line. Budget < 30 KB, zero external requests.
**Acceptance:** works on Android Chrome, iOS Safari, desktop Chrome and Firefox; 5 files in flight;
a dropped connection shows a retry that resumes from zero without duplicating the file.

### Phase 4 — The phone UI
Three screens as drawn in §3.1, the motion table in §5, live per-file progress fed from the server
events, history with open/share, empty states that teach the flow, settings complete.
**Acceptance:** 60 fps scrolling while a transfer runs; a non-technical person completes a transfer
without being told what to tap (tested on the user's own household, not by me).

### Phase 5 — Robustness and the long tail
OEM fallbacks (Wi-Fi Direct group, own-hotspot instructions), chunked/resumable uploads for files over
~2 GB, folder upload (`webkitdirectory`), optional PIN, optional per-device approval, reconnects after
screen-off, hotspot restart without losing the address, "keep this screen awake" while hosting.

### Phase 6 — Proof, not claims
JVM tests for the whole HTTP surface (adapted from the existing 52-test suite): upload integrity,
concurrent uploads, aborted uploads, traversal attempts, unicode names, disk-full refusal, port
fallback. Loopback throughput benchmark. A device checklist the user can run in ten minutes.

### Phase 7 — Release and removal
CI build, sign, publish APK + sha256, write the notes, then **delete the old module and its leftovers**
— that is the point where "delete the previous app" is actually true.

Rough effort: Phase 1 is small (a day), 2 and 3 are the bulk, 4 is design-heavy but mechanical, 5 is
where the surprises are (OEM hotspot quirks), 6 and 7 are routine.

---

## 7. Risks, and what I'll do about each

| Risk | Likelihood | Mitigation |
|---|---|---|
| The user's phone refuses `startLocalOnlyHotspot` | medium (OEM-dependent) | Phase 1 proves it on the real devices before anything else is built; Wi-Fi Direct fallback in Phase 5 |
| Hotspot dies when the app is backgrounded | high if built naively | foreground service + Wi-Fi lock from day one (Phase 1), not bolted on later |
| 2.4 GHz ceiling makes transfers feel slow | likely | shown honestly (band on screen, real MB/s), and it is still faster than the old app's failing transfers |
| Clients see "no internet" and think it's broken | certain | the page's first line says it; the phone's screen repeats it |
| iOS Safari quirks with large uploads | medium | XHR (not fetch) with progress, chunked upload in Phase 5 |
| Android kills the service on aggressive OEMs | medium | battery-optimisation exemption flow, notification-driven keep-alive, tested in Phase 5 |
| Someone on the hotspot uploads junk | low | it's a password-protected LAN; add PIN/approval if the user wants them |

---

## 8. Decisions — locked (answered)

| Question | Decision | Consequence |
|---|---|---|
| Separate app or replace? | **Replace outright.** | The old module and its surfaces are deleted; the repo builds exactly one app. New `applicationId` **`com.flowlink.drop`**, name **FlowLink Drop**. |
| Peer-to-peer transfer | **Removed now, may return later.** | No engine, no discovery, no beacons, no device-to-device crypto. The door stays open architecturally (a plain HTTP server and a foreground service are also what a P2P path would need), but no code is kept "just in case". |
| Security | **Hotspot password only.** | No PIN, no per-device approval, no session token. The WPA2 key is the authentication and the link is permanent. |
| Storage | **Gallery first.** | Images/video/audio go into the gallery via MediaStore (so they appear in Photos immediately); everything else lands in `Downloads/FlowLink` with predictable names. |

### Safety net for "replace outright"

Replacing means no fallback build on the device, so the safety net moves to the artefacts: the current
APK stays downloadable from this branch (`dist/FlowLink-1.0.3.apk`, sha256 committed) even after its
source is deleted. If the new app fails on the phones, the old one can be reinstalled in a minute
without touching git history.

One caveat I cannot engineer away: the release keystore lives in `keys/`, which is gitignored and was
lost once already in a sandbox reset. It is regenerated locally for 1.0.3. Since the new app has a new
`applicationId`, its own installs are unaffected by that identity — but if the keystore is lost again,
the next update of *that* app needs one uninstall. Worth exporting the `.p12` somewhere safe.

---

## 9. Phase 1 work order (what actually gets built first)

Deliberately the smallest thing that proves the risky part. No upload UI, no design work.

**New module** `drop/` in the same Gradle build:

| Item | Detail |
|---|---|
| `drop/build.gradle.kts` | `namespace`/`applicationId` `com.flowlink.drop`, minSdk 26, targetSdk 37, Compose, no dependencies beyond what the old module already used |
| `DropApp.kt` | application class, prefs namespace `drop.settings`, device name default = phone model |
| `HotspotHost.kt` | wraps `startLocalOnlyHotspot`, returns `ssid`, `passphrase`, `apAddress`, `band`; exposes states Starting / Ready / Failed(reason) |
| `DropService.kt` | foreground service: starts the hotspot, holds `WifiLock` + partial wake lock, keeps the reservation alive, stops on user request |
| `HttpServer.kt` | lifted from `core/Http.kt`, trimmed to `/`, `/api/ping`, `/favicon.ico`; binds `0.0.0.0`, walks `8080…8095`, exposes the bound port |
| `HostScreen.kt` | QR (address), `192.168.49.1:8080` in tabular digits, SSID + password rows with copy, band, a single status line, a "hello" confirmation when a browser hits `/api/ping` |
| `MainActivity.kt` | one screen, starts the service on launch, permission flow for `NEARBY_WIFI_DEVICES` |

**Acceptance for Phase 1 — the only things that matter:**

1. Open the app on the phone that hosts → within 3 seconds the screen shows an SSID, a password, an
   address and a QR.
2. On a completely different phone, join that network by typing the password.
3. Open the address in Chrome → "FlowLink Drop is running."
4. Repeat 1–3 with the host phone's screen **off** and the app in the background.
5. Force-stop the app → the hotspot goes away and the phone is not left hosting an orphan network.

If any of 1–5 fails, we tune the hotspot strategy (Wi-Fi Direct group, own-hotspot instructions) while
the surface area is still one screen, not after the whole app is written.

---

## 10. What I will not promise

* **Speed.** The engine here is a plain HTTP server writing to flash; the ceiling is the radio. On a
  2.4 GHz local-only hotspot that is roughly 8–10 MB/s, on 5 GHz 40–50 MB/s, and it will vary by
  phone. I will show the real number rather than a benchmark from a machine with no radio.
* **Band selection.** Public Android APIs cannot choose the hotspot band. If the phone grants 2.4 GHz,
  that is what everyone gets.
* **Zero-touch.** The other person must join a network and open a page. That is one manual step, and
  the QR exists to make it one tap — but it cannot be none, because it is a different device.
