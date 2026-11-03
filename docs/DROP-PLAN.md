# FlowLink Drop — strategy v3

**Status: plan only. Nothing built yet. Waiting for approval.**
Supersedes v1 and v2. The change in v3 is the **two-QR flow** the user proposed, plus a full, aspect-by-aspect
specification. Where the proposal could not work as literally described, this document says so and gives the
version that does.

---

## 0. The two-QR flow — and the one correction that makes it safe

The proposal: show a QR that joins the Wi-Fi; when the other phone connects, detect it and *then* show a second
QR that opens the web page.

The flow is right, and it is better than what v2 had, because it separates two different actions that both
otherwise compete for the same tap. But one part cannot be guaranteed:

> **Android has no reliable public API that says "a client joined my local-only hotspot."**

`LocalOnlyHotspotReservation` exposes the SSID and passphrase; it does not expose a client list. Some ROMs
allow reading `/proc/net/arp`; many do not. Wi-Fi Direct *does* have a client list
(`WifiP2pManager.requestGroupInfo().clientList`), but that only exists for the P2P-group route, not the
local-only-hotspot route.

So the design is **two-stage with detection as an accelerator, never as a gate**:

* Stage 1 (**Join**) is shown first and forever: the Wi-Fi QR, the SSID, the password.
* Stage 2 (**Open**) is *always reachable*: the URL text is visible from the start in small type, and one tap
  ("I'm on the Wi-Fi") promotes it to full size.
* If detection fires — by any of the four independent signals in §3 — Stage 2 promotes itself, with a haptic,
  a spring animation and a status line naming the device.

If detection fails on a given phone, the user loses nothing: they tap one button, or simply read the URL that
was already on screen. A gated design would have bricked the whole app on exactly the devices where detection
fails, which is the sort of thing you only discover when a stranger is holding your phone.

### The two payloads, exactly

| | Stage 1 — Join | Stage 2 — Open |
|---|---|---|
| QR contains | `WIFI:T:WPA;S:AndroidShare_1234;P:h7Kd2mQp;;` | `http://192.168.43.1:8080/` |
| Under the QR | `Network: AndroidShare_1234` + `Password: h7Kd2mQp` (each with a copy button) | `http://192.168.43.1:8080/` (copyable) |
| What it does | Android 10+ camera / iOS 11+ camera offers to join the network | Camera → browser opens the page |
| If the camera can't | type the SSID and password in Wi-Fi settings — both are printed | type the address in Chrome — it is printed |

Escaping rule for the WIFI payload: `\`, `;`, `,`, `:` inside SSID or passphrase must be backslash-escaped.
The existing `WifiQr.payload()` already does this and has a unit test; it is reused verbatim.

**Correction to v2:** v2 claimed iOS cannot join a Wi-Fi network from a QR code. That is wrong — iOS 11+
Camera joins Wi-Fi from a `WIFI:` QR. Android 10+ does the same. So Stage 1 is a single scan on both platforms.
What iOS *cannot* do is chain the two scans automatically, which is exactly why Stage 2 is a second, separate QR.

### Why two QRs beat one

A single QR can only carry one payload. The alternatives people try are worse:

* A URL QR only → the guest must join the network by hand first, then scan again. Two steps, one of them
  error-prone (typing a 12-character WPA2 key on a phone keyboard).
* A Wi-Fi QR only → they join and then have nothing to open.
* A link to a page that explains joining → unreachable until they have joined. Circular.

Two QRs, one action each, in the order the human actually performs them.

---

## 1. What changed against v2 (problem → fix)

| # | Problem found in v2 | Fix in v3 |
|---|---|---|
| 1 | v2 said iOS can't join Wi-Fi from QR | wrong; corrected above, both platforms join from Stage 1 |
| 2 | v2 had no join detection at all | four detection signals (§3), with the never-gate rule |
| 3 | v2's host screen was one flat card | explicit two-stage state machine with a promotion animation (§4.1) |
| 4 | Guest had no way to know if the phone saw them | status line + joined-device count + page-side "connected to X's phone" banner |
| 5 | Manual entry was implied, not designed | every QR is paired with the exact text to type, plus one "copy instructions" button (§4.2) |
| 6 | Chunked upload was deferred to Phase 5 while URLs were designed in Phase 2 | API surface for resume is fixed in Phase 2, client support lands in Phase 4/5 |
| 7 | No specification of what happens when the hotspot dies mid-session | recovery ladder and user-visible states (§8.3) |
| 8 | No performance or memory budgets | numbers in §11 |
| 9 | No privacy statement | §12: zero telemetry, zero external requests, verified in tests |

Everything else from v2 stands: MediaStore storage, `app` module kept (CI hardcodes `:app:`), bind `0.0.0.0`,
port 80 preferred, `Expect: 100-continue`, multipart fallback, `specialUse` foreground service, secure-context
limits of the browser page.

---

## 2. The flow, end to end

```
HOST PHONE                                   GUEST PHONE / LAPTOP
──────────                                   ────────────────────
open app
  └ hotspot up (1–3 s)
      ├ Stage 1 QR (Wi-Fi)      ──── scan ───▶ joins "AndroidShare_1234"
      ├ SSID + password printed                  │
      └ "waiting for someone…"                   │
                                                 │  (no internet — expected)
  detection fires (≈0.5–2 s) ◀───────────────────┘
      ├ haptic + Stage 2 promotes
      ├ Stage 2 QR (URL)        ──── scan ───▶ Safari/Chrome opens the page
      └ "Talal's iPhone joined"                  │
                                                 │  drops 3 files
  rows appear with live progress ◀───────────────┘
      └ files land in gallery / Download
```

Total for the guest: scan, tap "Join", scan, drag files. Four touches, no typing.

---

## 3. Detection — four independent signals

All four run concurrently; the first one to fire wins, and the app records which one fired (diagnostics use it).

| # | Signal | Coverage | Cost | Notes |
|---|---|---|---|---|
| D1 | `WifiP2pManager.requestGroupInfo` → `clientList` polled every 1 s | exact, **only** on the fixed-group route | negligible | gives MAC + name; the strongest signal we can have |
| D2 | **mDNS listener** on the AP interface (`MulticastLock`, group `224.0.0.251:5353`) | catches most iOS and Android guests in 0.5–3 s — they emit mDNS on join | one socket, one thread | needs no root; the old app already had the socket code |
| D3 | ARP table poll (`/proc/net/arp`) every 1.5 s | works on many ROMs, blocked on others | cheap read | never load-bearing; absence is not an error |
| D4 | First HTTP request | definitive | free | also flips "connected" if the guest skipped the QR and typed the URL |
| D5 | Manual: "I'm on the Wi-Fi" button | 100 % | free | shown from the first second, not hidden |

**Rules**

* Detection only changes *presentation*. It never enables or disables a capability.
* A false positive is worse than a miss: D3 requires an entry with a non-zero, non-broadcast MAC whose IP is
  inside the AP subnet, and latches only after the second consecutive poll. Guessing "someone joined" when
  nobody did would promote a QR to a stranger who is not there.
* Detection state is displayed, not implied: `waiting` → `joined (1 device)` → `receiving`.
* Detected devices are listed by name where known ("Talal's iPhone"), by address otherwise. Nothing is stored
  after the session ends except in the diagnostics ring buffer.

---

## 4. Screen specifications

### 4.1 Host screen — state machine

```
        ┌──────────┐  hotspot.start()   ┌───────────┐  detection   ┌────────────┐
 launch │ STARTING │ ─────────────────▶ │  WAITING  │ ───────────▶ │  CONNECTED │
        └──────────┘                    └───────────┘              └────────────┘
             │ failure                        ▲                          │ upload starts
             ▼                                │ hotspot lost             ▼
        ┌──────────┐                          └──────────────────── ┌───────────┐
        │  FAILED  │ ── retry / fallback ────────────────────────▶ │ RECEIVING │
        └──────────┘                                                └───────────┘
```

**Layout, top to bottom** (one scroll, no tabs, no drawer):

1. **Header** — phone name and a live status chip: `starting…` / `waiting` / `2 joined` / `receiving`.
2. **Primary card** — the current stage, 62 % of the screen height.
   * Stage 1: label `1 · Join this Wi-Fi`, big QR, then `Network` and `Password` rows with copy buttons.
   * Stage 2: label `2 · Open this page`, big QR, then the URL in tabular digits with a copy button,
     and a line "tip: type it in Chrome if the camera won't open it".
   * Collapsed stage is a single-line chip under the active card (`1 · AndroidShare_1234 ✓`), tappable to swap.
3. **Join strip** — as soon as a device is detected: avatar + name + "joined" + a subtle pulse; tap for
   details (address, user agent once known).
4. **Incoming** — one row per file: name, size, animated progress, source ("Chrome on Windows"). Empty until
   something arrives; then the card lifts into view with a spring.
5. **Footer** — `Copy instructions` (see 4.2), `Stop hosting`.

**Promotion animation** (Stage 1 → Stage 2): Stage 1 scales to 0.92 and fades to a chip while Stage 2 springs
up from below with a 40 ms delay; the status chip crossfades to "joined"; one `ToggleOn` haptic. Under
reduced motion or power-save: instant swap, no scale, haptic kept.

### 4.2 "Copy instructions" — the manual path, in one tap

Host taps once, and the clipboard holds:

```
Join Wi-Fi: AndroidShare_1234
Password: h7Kd2mQp
Then open: http://192.168.43.1:8080
```

Pasteable into WhatsApp/SMS. This is the answer to "what if they can't scan at all", and it costs one button.

### 4.3 Received

Grouped by day. Each row: thumbnail or type icon, name, size, time, source, and actions — **Open**,
**Share**, **Show in folder**. Long-press selects several for a multi-share. Empty state explains the flow in
one sentence and repeats the address so a returning guest can just type it.

### 4.4 Settings

| Group | Items |
|---|---|
| Network | route (Fixed group / OS hotspot / my own hotspot), fixed name + passphrase (only for the fixed route), preferred port, restart hotspot |
| Saving | destination folder name, "publish media to gallery" (on by default) |
| Behaviour | keep screen awake while hosting, stop hotspot when app is closed, auto-stop after N minutes idle |
| Access | PIN (off), approve each new device (off) |
| Help | self-test, request log, copy diagnostics, battery-optimisation helper, About |

### 4.5 Diagnostics

* **Self-test**: hotspot up? port bound? server answers on its own AP address? loopback upload of 1 MB
  succeeds? → four green ticks or one clear red with the failing step named.
* **Request log**: last 50 requests — time, remote address, method, path, bytes, status, duration.
* **Copy diagnostics**: app version, route used, SSID, AP address, port, detection signals that fired, last
  error, device model/SDK. One paste replaces five chat messages.

---

## 5. The browser page

### 5.1 Constraints that shape it

* No internet on that network → **zero external requests**, no CDN, no web fonts, no analytics.
* `http://192.168.x.x` is a **non-secure context** → no `navigator.clipboard`, `crypto.randomUUID`, Wake Lock,
  service workers, `navigator.share` is unreliable. The page uses none of them.
* iOS Safari suspends tabs on screen lock → resume-capable uploads and an explicit "keep this page open" note.
* Some guests will have JavaScript disabled or blocked → a `<form enctype="multipart/form-data">` fallback that
  works with no JS at all.

### 5.2 Layout

```
┌────────────────────────────────────────────────┐
│  Send to Talal's phone                         │  ← phone name from the server
│  Direct over Wi-Fi · nothing leaves this network│
│  ╭──────────────────────────────────────────╮  │
│  │              ⬆  Drop files here           │  │  ← breathes when idle (CSS only),
│  │        or tap to choose from this device  │  │     glows on drag-over
│  ╰──────────────────────────────────────────╯  │
│   report.pdf        2.4 MB  ▓▓▓▓▓▓▓░░░ 78%     │  ← rows spring in, staggered
│   holiday.mp4       212 MB  ▓▓▓░░░░░░░ 31%     │
│   app-release.apk    61 MB  ✓ sent   [open]    │
│   3 files · 275 MB · 18 MB/s        [clear]    │
└────────────────────────────────────────────────┘
```

### 5.3 States

| State | What the page shows |
|---|---|
| idle | drop zone, phone name, "no internet needed" reassurance |
| dragging | zone scales 1.02, border animates, drop text changes |
| uploading | row with bytes/total, percentage, rate, cancel |
| paused/offline | "connection lost — reconnecting", retry button, resumes at the last byte if the protocol allows |
| done | check animation, "3 files sent", offer "send more" |
| rejected | 413 "too large (limit 4 GB)", 507 "phone is full", 403 "PIN required" — each with the exact reason |
| no-JS | plain form: file input + submit, then a plain result page |

### 5.4 Budgets

≤ 30 KB uncompressed, ≤ 1 500 lines total, first paint < 300 ms on a mid-range phone, no layout shift after
first paint, works at 320 px width and at 4 K.

---

## 6. Server and protocol

### 6.1 Routes

| Method | Path | Purpose | Success | Failures |
|---|---|---|---|---|
| GET | `/` | the page | 200 html | — |
| GET | `/favicon.ico` | inline SVG | 200 | — |
| GET | `/api/ping` | liveness, phone name, free space, limits | 200 json | 503 while warming up |
| GET | `/api/info` | same + session id, PIN required? | 200 json | — |
| POST | `/api/upload?name=&size=` | raw body upload (XHR fast path) | 200 `{ok,name,size}` | 400, 403, 413, 507, 500 |
| POST | `/upload` | multipart form (no-JS path) | 303 → `/sent` | same |
| POST | `/api/upload/start` | begin resumable | 200 `{id,offset}` | 507 |
| POST | `/api/upload/chunk?id=&offset=` | append bytes | 200 `{offset}` | 409 offset mismatch |
| POST | `/api/upload/finish?id=` | commit | 200 `{ok}` | 400 incomplete |
| GET | `/app.apk` | installer, **only** if invite is enabled | 200 apk | 404 |

Status semantics are part of the UI: `413` → "too large", `507` → "phone full", `403` → "ask them for the PIN".

### 6.2 HTTP details that decide whether it works at all

* `Host` header ignored; absolute-form request lines accepted (some proxies rewrite).
* `Expect: 100-continue` → answer `HTTP/1.1 100 Continue` then read. **Verified missing in the old code**, so it
  is a required item with its own test.
* `Transfer-Encoding: chunked` accepted on upload (no `Content-Length`).
* Request-line and header limits: 8 KB, 64 headers; longer → 431.
* Keep-alive with a **5 s** idle timeout; `Connection: close` honoured.
* Range requests honoured for `/app.apk` (a browser that resumes an interrupted download).
* Read timeout 60 s on a silent socket; write timeout 60 s; both close cleanly so a vanished client cannot pin
  a worker.

### 6.3 Concurrency

* One accept thread, pool of **12** workers, queue cap 24 → `503` with `Retry-After: 2` beyond that.
* Uploads stream directly to the sink; no whole-file buffering anywhere.
* Per-connection state is a single object; no shared mutable state except the request log ring buffer.

---

## 7. Storage

| Content | Destination | Mechanism |
|---|---|---|
| image/*, video/*, audio/* | `Pictures/FlowLink`, `Movies/FlowLink`, `Music/FlowLink` | `MediaStore` + `IS_PENDING=1` |
| everything else (pdf, docx, zip, apk, unknown) | `Download/FlowLink` | `MediaStore.Downloads` + `IS_PENDING=1` |
| API 26–28 | same logical directories | `File` + `WRITE_EXTERNAL_STORAGE`, `.part` → rename |

* **No storage permission on Android 10+.** The old app needed `MANAGE_EXTERNAL_STORAGE` to write to
  `Downloads`; this one never does.
* A file becomes visible in one step (clear `IS_PENDING`), so no partial file is ever visible and a crash leaves
  only an orphan pending row, cleaned on next launch.
* Names: sanitised, length-capped at 120 characters, extension preserved; collision → `name (1).ext`.
* MIME: extension first, browser-declared type only as a hint (never trusted for routing), sniffing for
  extensionless files.
* Free-space guard: refuse below 32 MB headroom with `507`.
* Per-file cap 4 GB (configurable), per-session cap unlimited but tracked.

---

## 8. Hotspot

### 8.1 Routes, in priority order

| Route | SSID / password | Address | When |
|---|---|---|---|
| **Fixed group** (`WifiP2pManager.createGroup` with `setNetworkName`/`setPassphrase`, API 29+) | **stable**, e.g. `FlowLink-Talal` / user's key | `192.168.49.1` | if supported and enabled — the only route with a permanent link |
| **Local-only hotspot** (`startLocalOnlyHotspot`) | OS-random per session (`AndroidShare_1234`) | `192.168.43.1` or `.49.1` by OEM | default everywhere else |
| **User's own hotspot** | whatever they set | read from the AP interface | when both of the above are refused |

### 8.2 Preconditions, checked before starting

Wi-Fi radio on (offer a one-tap system intent — no app can switch it on since Android 10) · not in airplane
mode · no active tethering already · battery-optimisation exemption offered (never demanded) · creation only
while the app is visible.

### 8.3 Recovery ladder

| Failure | What the user sees | What the app does |
|---|---|---|
| hotspot refused | the exact reason + `Try fixed group` / `Use my own hotspot` | tries the next route |
| reservation lost (process death, OS reclaim) | "hotspot stopped — tap to restart" | restarts, re-derives address, regenerates Stage 1 QR |
| address changed | new address on screen, Stage 2 QR regenerated | re-proves reachability, updates the URL |
| port 80 taken | `:8080` shown explicitly | walks 8080→8095 |
| server died but hotspot alive | "page not answering — restarting server" | restarts the server only |

### 8.4 Lifecycle

Foreground service (`specialUse`), started on app launch, stopped by the user or by the idle timer (default 15
minutes with no client and no traffic — configurable, and off when the user pins "keep hosting").

---

## 9. Permissions — exact list

```
INTERNET
ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
NEARBY_WIFI_DEVICES                 runtime, API 33+
ACCESS_FINE_LOCATION                runtime, maxSdkVersion 32 (LOH requirement on older APIs)
ACCESS_LOCAL_NETWORK                (repo already targets SDK 37 conventions)
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE      + PROPERTY_SPECIAL_USE_FGS_SUBTYPE
POST_NOTIFICATIONS, WAKE_LOCK
WRITE_EXTERNAL_STORAGE              maxSdkVersion 28 only
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

Deliberately absent: `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` (we only write), `CAMERA` (the app only *shows*
QRs — the guest uses their own camera), `REQUEST_INSTALL_PACKAGES` unless invite-install is enabled. Four
runtime prompts maximum, three of them once.

---

## 10. Design system and motion

Foundations already built and tested in this repo are reused: `Palette` (WCAG-asserted ints), six spring tokens,
`hero` numeral style.

* Dark-first, near-black `#0B0F17`, single accent gradient `#255CE8 → #8B5CF6`, 26 dp radii, cards by tone
  (no 1 dp borders), one variable typeface at three weights, tabular digits for anything counting.
* **Motion** — every animation is tied to a state change, none runs on a timer, none runs while bytes move:

| Event | Motion |
|---|---|
| app opens | QR card scales 0.94 → 1 with a 1.4 s breathing ring |
| hotspot ready | ring settles, credentials slide up staggered, `Confirm` haptic |
| guest joins | Stage 1 shrinks to a chip, Stage 2 springs up, status crossfades, `ToggleOn` haptic |
| file arrives | row springs in, bar chases the value on a fast spring |
| file done | check draws with slight overshoot, `Confirm` haptic |
| failure | 180 ms shake, error-tinted row, `Reject` haptic |
| reduced motion / power-save | all of the above collapse to a fade or an instant swap; haptics kept |

---

## 11. Budgets and performance

| Metric | Target | How it is checked |
|---|---|---|
| app cold start → hotspot requested | < 500 ms | manual + logcat timing in diagnostics |
| hotspot ready → QR on screen | < 1 s | ditto |
| QR regeneration | < 16 ms | measured in a JVM test on the encoder |
| page first paint | < 300 ms on a mid-range phone | manual on device |
| page weight | ≤ 30 KB | asserted in a unit test |
| transfer CPU | < 8 % of one core at 40 MB/s | loopback benchmark |
| memory | < 80 MB RSS with 3 uploads in flight | device check |
| scroll smoothness during transfer | 60 fps (no jank frames > 16 ms) | device check |

---

## 12. Privacy, plainly

* No telemetry, no crash reporting, no analytics, no network access other than serving the page and (once) a
  version check that is **off by default**.
* The page makes zero external requests — asserted by a test that scans the HTML for `http://`/`https://`/
  `//` references not pointing at the phone.
* Files are never uploaded anywhere: the app has no outbound code path for user data.
* Nothing about the guest is persisted beyond the session, except what the user keeps in History.

---

## 13. Testing

**JVM (no device, runs in CI every push)**

* HTTP parser: request line variants, chunked bodies, 100-continue, header limits, keep-alive, Range.
* Upload: 2 GB streamed, 200 files, aborted body, duplicate names, unicode names, traversal attempts
  (`../../etc/passwd`), zero-byte file, wrong MIME, disk-full (`507`), too-large (`413`), concurrent (12).
* Sink abstraction faked on the JVM so the MediaStore path is exercised behind an interface.
* QR payload round-trip: SSID/passphrase with `;`, `,`, `:`, `\`, emoji; WIFI payload parse-back.
* HTML page: no external URLs, all states present, form fallback markup valid, size budget.
* Old suite (52 tests) is not carried over wholesale — the parts that tested the deleted P2P engine go with it.

**Device (the user, ~10 minutes per build)**

| Check | Pass condition |
|---|---|
| launch | QR within 3 s |
| guest joins (Android) | scan Stage 1 → prompt → joined; host shows joined within 3 s |
| guest joins (iOS) | scan Stage 1 → prompt → joined; same |
| manual join | type SSID + password → joined |
| Stage 2 | scan → page opens; or type URL → page opens |
| upload single | 50 MB file arrives, checksum matches, appears in gallery/Download |
| upload many | 20 files, all present, no duplicates |
| backgrounded | repeat with screen off; still works |
| force-stop | hotspot gone, no orphan notification |
| diagnostics | copy block pastes usefully into chat |

---

## 14. Phases

| Phase | Deliverable | Gate |
|---|---|---|
| **1. Hotspot + server + two-QR** | rewritten `app` module, both hotspot routes, Stage 1/Stage 2 screen, detection (D1–D5), self-test, request log | the device matrix above, rows 1–5 and 8–10 |
| **2. Uploads that land correctly** | MediaStore sinks, raw + multipart + 100-continue + chunked, limits, collisions, crash-safety | full JVM suite green |
| **3. The browser page** | designed page, all states, no-JS fallback, budget met | device matrix rows 6–7 on 4 browsers |
| **4. Phone UI polish** | Received, Settings, motion, live progress, history | user completes a transfer without instructions |
| **5. Robustness** | resumable client, folder upload, PIN/approval, OEM fallbacks, idle policy | targeted tests + one round trip |
| **6. Release** | CI green, signed APK, checksums, notes, old code deleted | user installs the published artefact |

Phase 1 is the only one that can fail for reasons outside my control, so it is deliberately small and ships
first.

---

## 15. Risk register

| Risk | Prob. | Impact | Mitigation |
|---|---|---|---|
| Hotspot refused on the user's phones | medium | blocks everything | two routes + guided manual; tested first, before anything else is built |
| Join detection never fires on some ROM | medium–high | UX degraded, not blocked | D1–D5; never gate the flow on it |
| Guest can't reach the page though joined | low | blocks the transfer | self-test proves server↔AP address; page shows a specific failure |
| 2.4 GHz ceiling ~8–10 MB/s | likely | expectations | band shown on screen; real MB/s displayed, never a CI number |
| iOS suspends uploads on screen lock | medium | partial uploads | resumable chunks + "keep this page open" |
| OEM battery manager kills the service | medium | session dies | FGS + exemption helper + notification restart |
| Port 80 unavailable | low | uglier URL | 8080 fallback, port printed |
| Keystore lost again (happened once) | medium | one reinstall | export `.p12` with the release, documented |

---

## 16. Open questions

1. **Fixed SSID/password default?** Only the Wi-Fi Direct route gives a permanent network name; on phones
   without it the OS randomises per session. Recommendation: enable fixed group where supported, fall back to
   OS-random silently, and say which one is active on screen.
2. **Keep the invite (`GET /app.apk`)?** It is one route behind a toggle and does not affect the core flow.
   Recommendation: keep, off by default.

---

## 17. What I will not promise

* **Instant detection on every phone.** APIs do not exist for it; four signals cover most devices, and the
  manual promotion button covers the rest.
* **Speed.** The radio is the ceiling: ~8–10 MB/s on 2.4 GHz, 40–50 MB/s on 5 GHz, band not selectable through
  public APIs.
* **A permanent password** on the local-only-hotspot route — the OS regenerates it every session. Fixed name
  and passphrase are possible only on the Wi-Fi Direct route.
* **Automatic page opening** (captive portal): withdrawn in v2, still impossible without owning DHCP/DNS.
* **Zero steps for the guest.** Scan, join, scan, drop. Four touches; the QR is what makes typing unnecessary.
