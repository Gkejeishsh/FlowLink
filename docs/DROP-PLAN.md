# FlowLink Drop — strategy, v2 (v1 audited and corrected)

**Status: plan only. Nothing built yet. Waiting for approval.**
v1 of this plan is superseded. What follows states what was wrong with it, why, and what replaces it.

The brief is unchanged: a new app whose only job is *someone else's browser sends files to my phone*.
The phone hosts a hotspot with an OS-style random name and password, shows both, the other person
joins, opens a link or scans a QR, and drops files that land in the app.

---

## 1. What was wrong with v1 — and the fix

These are not style notes. Each one would have cost real time, produced a broken build, or shipped an
app that fails on the user's actual phones.

### Storage and build

**1. The save path was illegal on modern Android.**
v1 said files land in `Downloads/FlowLink` through the File API. Verified in this repo: the current
app only writes there when it holds **MANAGE_EXTERNAL_STORAGE** ("all files access") — otherwise
`resolveSaveRoot()` silently falls back to app-private storage, which is invisible in the user's file
manager (`TransferService.kt:1104-1122`). A clean new app should not need that permission at all.
→ **Fix:** every received file is written through **MediaStore with `IS_PENDING=1`** — the Downloads
collection for documents/APKs, Images/Video/Audio for media. Zero storage permissions on Android 10+,
and the platform's own "invisible until complete" flag replaces the `.part`-then-rename trick. On
Android 8–9, File API + `WRITE_EXTERNAL_STORAGE`, which is still normal there.

**2. A new Gradle module would break CI.**
v1 proposed a `drop/` module. The workflow hardcodes `:app:` in five places
(`.github/workflows/build.yml:110-122`).
→ **Fix:** keep the module directory and name `app`, change `namespace`/`applicationId`, delete the
old sources inside it. CI needs no edits.

**3. v1 contradicted its own decisions.**
The user chose "replace outright", yet v1's phases kept the old module until Phase 7.
→ **Fix:** old code is deleted in Phase 1. The safety net is the published, checksummed APK artefact
(`dist/FlowLink-1.0.3.apk`), not a dormant module.

### Networking

**4. The server binds an address that may not exist yet.**
The existing server resolves the IP and binds to it at start (`WebService.kt:144,183`). On a hotspot
host the AP interface appears *after* the hotspot starts, so resolving first then binding is a race
that fails on exactly the phones where it matters.
→ **Fix:** bind `0.0.0.0`; discover the AP address separately, for display only.

**5. The AP address was guessed from interface names.**
`Hotspot.kt` finds it by name prefixes (`ap*`, `swlan*`, `wlan1`, `softap`) — OEM-dependent, and wrong
on devices that name the interface something else.
→ **Fix:** ranked discovery with proof: (a) known gateway addresses that are locally assigned
(`192.168.49.1`, `.43.1`, `.232.1`, `.50.1`, …); (b) any non-cellular IPv4 that is *not* the station
interface; (c) interface-name heuristics last. Then **prove it**: the app connects to its own port on
that address and shows the result ("reachable on 192.168.49.1:8080 ✓"). A wrong guess is now visible
in one tap instead of being discovered by a stranger with a phone.

**6. The typed URL was the weakest link, and my "captive portal" idea was not implementable.**
v1 proposed auto-opening the page by answering Android/iOS captive-portal probes. Correction: that
requires owning DHCP and DNS on the AP, and both are held by Android's tethering stack with no public
API to replace them. **Dropped as unviable.**
→ **Fix, three parts:** (a) try to bind **port 80** first, so the typeable string is just the IP — 12
characters, and Chrome/Safari default to `http://` for a bare IP (verify on device; fall back to 8080
and display the port); (b) the **first QR is a `WIFI:` code** — Android 10+ camera joins the network
outright from it, so the joiner never types the password; (c) the page URL is the second, large,
copyable line *and* a normal QR for anyone who prefers scanning. iOS cannot join Wi-Fi from a QR, so
it gets explicit join instructions — that is an iOS platform limit, not something I can design around.

**7. `Expect: 100-continue` was missing.**
Verified absent in `Http.kt`. Chrome and curl send it for large POST bodies; a server that ignores it
stalls or fails on exactly the big uploads this app exists for.
→ **Fix:** answer `HTTP/1.1 100 Continue` before reading the body, and keep it out of the response log.

**8. No multipart parsing meant no fallback when JavaScript fails.**
→ **Fix:** support `multipart/form-data` so a plain `<form method=post enctype=multipart/form-data>`
works with JS disabled, blocked by a corporate browser, or broken by an iOS quirk. Raw-body upload
stays the fast path; multipart is the safety net. Both are covered by tests.

**9. Connection starvation.**
Browsers open up to six connections per host and keep them alive. A 4-thread pool with long keep-alive
stalls uploads under real-world conditions.
→ **Fix:** bounded pool of 12, keep-alive idle timeout of 5 s, uploads streamed straight to the sink
(never buffered whole), and a queue-depth cap that answers 503 instead of hanging.

### Android platform

**10. Wrong foreground-service type.**
`dataSync` services are time-limited on Android 15+ (the system stops them after a few hours), which
is fatal for a session where files arrive sporadically.
→ **Fix:** `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
declaration, which has no such limit and is honest about what it does. Sideloaded app, so no Play
review to satisfy.

**11. `startLocalOnlyHotspot` preconditions were unstated.**
It can be refused when Wi-Fi is off, when tethering is already active, when a VPN owns the network, or
on OEM builds (MIUI/HyperOS, some Samsung). It may also need the app to be visible to create a
reservation.
→ **Fix:** a pre-flight checklist in the UI (Wi-Fi on? airplane mode? tethering already on?), a
visible failure reason for each case, retry with backoff once, and creation only while the app is in
the foreground — a lost reservation is presented as "tap to start again", never as a silent death.

**12. The fallback chain was optimistic, and it put the wrong option first.**
LOH and Wi-Fi Direct both need the radio and fail for many of the same reasons, so "P2P as fallback"
is weaker than v1 implied. More importantly, **LOH regenerates SSID and password every session**,
which contradicts the permanent-link expectation behind this product.
→ **Fix, with a real trade-off surfaced:**

| Route | SSID / password | Address | Support |
|---|---|---|---|
| **Wi-Fi Direct group with a fixed name and passphrase** (`WifiP2pConfig.setNetworkName/setPassphrase`, API 29+) | **fixed, chosen once in Settings** — "FlowLink-Talal" | 192.168.49.1 | good, but not universal |
| **Local-only hotspot** (`startLocalOnlyHotspot`) | random per session, `AndroidShare_1234` | 192.168.43.1 / .49.1 by OEM | the most common |
| **The user's own hotspot** | whatever they set | read from the AP | always available, manual |

Phase 1 builds the first two and tests both on the user's phones; whichever works becomes the default,
the other stays as a menu option. Only the fixed-name route gives a link that is permanent *and*
password-stable.

**13. Clients with mobile data on may never use our network.**
Many phones keep cellular as the default route when a Wi-Fi network has no internet, and "smart network
switch" silently drops no-internet Wi-Fi.
→ **Fix:** the page detects that it cannot reach the phone (a failed `fetch`) and says what to do; the
phone screen repeats the same advice next to the credentials instead of pretending everything is fine.

### The browser page

**14. The page runs in a non-secure context, which disables half the modern web APIs.**
`http://192.168.49.1` is not a secure context, so `navigator.clipboard`, `crypto.randomUUID`,
`navigator.share`, Wake Lock and service workers are all unavailable. v1 assumed a normal web surface.
→ **Fix:** zero modern-API dependencies. File identity is a counter, clipboard is a select-and-copy
fallback, screen-wake is a written instruction ("keep this page open"), and no service worker.

**15. iOS specifics were unaddressed.**
Safari suspends a tab when the screen locks, killing long uploads; folder upload (`webkitdirectory`) is
Android-Chrome-only; the Wi-Fi QR cannot be used at all.
→ **Fix:** chunked upload with resume (so a suspended tab continues where it stopped), explicit
"keep the screen on" copy, and a folder-upload control that is hidden where it is unsupported.

### Process

**16. I cannot test on a device, and v1's acceptance criteria were all "the user tries it".**
Every round trip costs the user real time and produces vague failures ("it didn't work").
→ **Fix:** diagnosis becomes a feature, not an afterthought — a **self-test** button (server reachable
from its own AP address, port bound, hotspot up), a **request log** on the phone (last 50 requests:
time, remote, path, bytes, status), and a **copyable diagnostics block**. The next failure report is
then one paste, not a guessing game.

**17. v1 claimed a one-way door, then kept an invite.**
"The server has no download route at all" contradicts the user's instruction that invite should stay.
→ **Fix:** exactly one download route exists (`/app.apk`, the installer, behind an explicit toggle),
and the page says plainly that files only ever travel *toward* the phone.

---

## 2. Revised architecture

### 2.1 Process and component map

```
MainActivity ── HostScreen ── ReceivedScreen ── SettingsScreen
      │
      └── DropService (foreground, specialUse)
             ├── HotspotHost  → LocalOnlyHotspot | DirectGroup | user's own AP
             ├── DropServer   → HTTP on 0.0.0.0:80|8080…8095
             │     ├── GET  /                → drop page
             │     ├── POST /api/upload      → raw body, XHR fast path
             │     ├── POST /api/upload?fmt=multipart → no-JS fallback
             │     ├── GET  /api/ping        → liveness + phone name + free space
             │     ├── GET  /api/info        → what the page needs to render
             │     ├── GET  /app.apk         → installer (only if invite is on)
             │     └── GET  /favicon.ico     → inline SVG
             ├── DropSink     → MediaStore (IS_PENDING) | File API on API < 29
             └── RequestLog   → ring buffer for the diagnostics block
```

### 2.2 Hotspot strategy

Chosen at start, in this order, each with a visible state:

1. **Fixed-group mode** (if the user enabled it and the device supports P2P): Wi-Fi Direct group with a
   name and passphrase set once → SSID/password stable across sessions.
2. **Local-only hotspot**: the widest support, random credentials per session as the OS decides.
3. **Guided manual**: "turn on your own hotspot, come back" — the app then serves on whatever the AP
   address turns out to be. Never a dead end, never a lie about automation.

Preconditions checked before any of them: Wi-Fi radio on, no active tethering, not in airplane mode,
battery-optimisation exemption offered (not demanded).

### 2.3 Addressing

* Server binds `0.0.0.0`, walks `80 → 8080…8095`, reports the bound port.
* AP address discovered by proof (self-connect), not by name guessing; shown only when proven.
* The displayed URL is always complete (`http://192.168.49.1`), because a bare string in the address
  bar gets searched instead of opened on some browsers.

### 2.4 Storage

| Content | Destination | Mechanism |
|---|---|---|
| Images, video, audio | gallery, `Pictures/FlowLink`, `Movies/FlowLink`, `Music/FlowLink` | `MediaStore` + `IS_PENDING` |
| Documents, archives, APKs, anything else | `Download/FlowLink` | `MediaStore.Downloads` + `IS_PENDING` |
| API 26–28 | same logical paths | File API + `WRITE_EXTERNAL_STORAGE`, `.part` + rename |

Name collisions get ` (1)`, ` (2)`; a finished file is published in one step (clear `IS_PENDING`), so
no half-written file is ever visible and there is nothing to clean up after a crash.

### 2.5 Upload protocol

* Fast path: `POST /api/upload?name=<urlencoded>` with `Content-Length`, body streamed to the sink.
  100-continue answered; `Transfer-Encoding: chunked` also accepted.
* Fallback path: `multipart/form-data`, so a JS-less form works.
* Resumable path (Phase 5): `start` → `chunk?offset=N` → `finish`, idempotent per `id`+offset, so an
  iOS tab that was suspended can continue rather than restart. The URLs are designed in Phase 2 even
  if the client only starts using them later.
* Limits: per-file cap (configurable, default 4 GB), per-session cap, free-space headroom of 32 MB,
  and `507` with a readable message rather than a silent failure.

### 2.6 Security model

Hotspot password only, as decided. Plus, because it is nearly free:

* No read route for user files — upload only. The invite APK is the single exception.
* Names are sanitised on the server and rendered with `textContent` in the page (never `innerHTML`),
  so a filename cannot become markup.
* Optional PIN and optional per-device approval remain in Settings, off by default — the decision was
  "password only", but leaving the switches present costs one line of UI and covers the café case.

---

## 3. The browser page — corrected specification

* Single HTML file, **no external requests of any kind**, ≤ 30 KB uncompressed.
* Works with JavaScript disabled (multipart form fallback).
* Works in Android Chrome, Samsung Internet, iOS Safari, desktop Chrome/Firefox/Edge/Safari.
* Drag-and-drop, tap-to-pick, multi-select, queue with per-file progress, retry, cancel, totals.
* Per-file states: queued → uploading (bytes + percentage) → verifying → done / failed (reason + retry).
* Says, in the first three seconds: whose phone this is, that the network has no internet and that is
  normal, what to do if the connection drops.
* Nothing that requires a secure context: no clipboard API, no `crypto.randomUUID`, no Wake Lock.
* Empty state explains the flow in one sentence; error state names the cause (`507` = disk full,
  `413` = too large, network error = you left the network).
* Accessible: real labels, keyboard-operable drop zone, `aria-live` for progress, respects
  `prefers-reduced-motion`.

---

## 4. The phone app — corrected specification

**Host:** QR (WIFI: first, page URL second) or a single page QR when the network is already joined;
URL in tabular digits with a copy button; SSID and password each copyable; band; status line that
changes state (starting → waiting → someone joined → receiving); live per-file rows; a stop button.

**Diagnostics (new, from problem 16):** a self-test that proves server + address + port in one tap, the
last 50 requests with remote address and byte counts, and a "copy diagnostics" button that produces a
text block I can act on without another guessing round.

**Received:** grouped by day; name, size, source ("Chrome on Windows"), open / share / show in folder.
Backed by the same MediaStore entries, so nothing is duplicated.

**Settings:** fixed-group toggle + its name/passphrase, port preference, save location, keep-awake, PIN
and approval switches, battery-optimisation helper, About.

**Motion:** unchanged in intent from v1 — springs from the existing token set, everything tied to a
real event, nothing animating while bytes are moving, everything suppressed under reduced motion or
power-save. The self-test and diagnostics are boring on purpose.

---

## 5. Manifest, exactly

```
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
NEARBY_WIFI_DEVICES            (API 33+; runtime)
ACCESS_FINE_LOCATION           (API ≤ 32 only; runtime, maxSdkVersion 32)
ACCESS_LOCAL_NETWORK           (API 37+ name for local-network access, already used in this repo)
FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS, WAKE_LOCK
WRITE_EXTERNAL_STORAGE         (maxSdkVersion 28 only)
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
CAMERA                         (only if the in-app QR scanner is kept; see open question)
```

No `MANAGE_EXTERNAL_STORAGE`, no `READ_MEDIA_*` (we only write), no `REQUEST_INSTALL_PACKAGES` unless
invite-install stays.

---

## 6. Revised phases

| Phase | Deliverable | Proven by | Round trips |
|---|---|---|---|
| **1. Hotspot + server + diagnostics** | `app` module rewritten, both hotspot routes buildable, "hello" page, self-test, request log | The five checks below, on the user's two phones | 1 (worst case 2) |
| **2. Uploads that land correctly** | MediaStore sinks, raw + multipart + 100-continue, limits, collisions, crash-safety | JVM socket tests: 2 GB, 200 files, aborted body, unicode, duplicate names, disk full | 0 (CI only) |
| **3. The browser page** | designed single-file page, all states | Device matrix by the user: Android Chrome, Samsung Internet, iOS Safari, desktop | 1 |
| **4. The phone UI** | three screens, motion, live progress, history | User runs a real transfer in front of a non-technical person | 1 |
| **5. Robustness** | resumable chunks, folder upload, PIN/approval, OEM fallbacks, idle/keep-awake policy | Targeted tests per item | 1–2 |
| **6. Proof and release** | full test suite green, signed APK, checksums, notes | CI + user | 0 |

**Phase 1 acceptance (unchanged in spirit, sharper):**

1. App opens → hotspot up, SSID/password/address/QR visible within 3 s, self-test says reachable.
2. A different phone joins by typing the password.
3. Chrome at the address shows the page.
4. Repeat 2–3 with the host's screen off and the app backgrounded.
5. Force-stop → hotspot gone, no orphan network, no stuck notification.
6. Diagnostics block copies to the clipboard in one tap (needed for any failure report).

**Why this order:** Phase 1 is the only part that can fail for reasons outside my control. Phase 2 is
pure logic and can be proven in CI without a phone. Phase 3 and 4 are the parts users judge. Nothing
later is blocked by anything earlier failing — except Phase 1, which is the point.

---

## 7. Risks, ranked by what actually kills the project

| Risk | Impact | Mitigation |
|---|---|---|
| Hotspot refused on the user's phone | project-blocking | two independent routes tested in Phase 1; guided manual as the floor |
| Client cannot reach the phone despite joining | high | self-test proves server↔AP address before the user hands the phone over; page shows a specific failure |
| iOS Safari suspends long uploads | medium | chunked resume in Phase 5; "keep the page open" copy now |
| 2.4 GHz ceiling (~8–10 MB/s) | medium | band shown on screen; expectations set in the app, not in a chat message |
| Port 80 unavailable | low | 8080 fallback, port displayed |
| Keystore lost again (it already happened once) | medium | export the `.p12` with the release; documented in BUILD-NOTES |
| OEM battery manager kills the service | medium | foreground service + exemption helper + notification-restart path |

---

## 8. Open questions (only two, both cheap)

1. **Fixed network name/password** (Wi-Fi Direct group) as the default, giving a stable SSID every
   session — versus the OS-random name the brief described? Recommended: build both in Phase 1, keep
   whichever your phones actually support, because only one of them gives a stable link.
2. **In-app QR scanner:** keep it (needs `CAMERA`, useful when someone wants to scan the other person's
   screen) or drop it (the app only *shows* a QR now; scanning is the client's job). Recommended: drop
   it — it is the last remaining permission that does nothing for the core flow.

---

## 9. What I will not promise

* **Speed.** The ceiling is the radio: roughly 8–10 MB/s on a 2.4 GHz local hotspot, 40–50 MB/s on
  5 GHz, and Android's public API cannot choose the band. The app will show the real number.
* **A permanent password** on every phone. With local-only hotspot the OS regenerates SSID and
  passphrase each session — that is the API, not a choice. The fixed-name route fixes it where the
  hardware allows.
* **Zero steps for the other person.** They must join a network and open a page. The QR makes that one
  tap on Android; iOS needs two (join in Settings, then the URL).
* **Automatic captive-portal opening.** v1 promised it; it cannot be done from an app without owning
  DHCP/DNS, so it is withdrawn.
