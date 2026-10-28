# LoopJet — connection & transfer-UI plan, **v2 (corrected)**

Supersedes v1. v1 was structurally right but had **10 concrete errors** — including three that would
have shipped broken code. This document lists them, then gives the corrected design.
Status: proposal only, no app code changed. Reference commit: `2e9daf9`.

---

## 0. What was wrong with v1 (and the fix)

| # | v1 claim | Reality | Severity | Fixed by |
|---|---|---|---|---|
| 1 | "QR v2 keeps the old `loopjet://v2#…` prefix so v1 phones still parse it" | **False.** `Discovery.parseLink()` requires `host:port` *before* the `#` (`hostPort.contains(':')`), and `port` is parsed with `toIntOrNull()`. A v2 code returns `null` → "not a LoopJet link". | **High** — silent breakage between versions | §4: v2 uses a **new scheme** `loopjet2:`; the scanner accepts v1, v2 and `WIFI:` codes; a v1 phone that scans v2 gets an explicit "update the other phone" message instead of a lie. |
| 2 | "Highest scoring candidate wins — measure each with a 3×64 KB echo" | **Physically impossible on most phones.** Station Wi-Fi, SoftAP and a p2p group share one radio: opening one **tears down another** on a large fraction of devices (and Samsung is among them). You cannot measure three paths that cannot coexist. It also needs a full handshake per candidate. | **High** — the feature as written cannot work | §5: paths are **exclusive states, tried in order**; ranking is a *prediction* from local facts, verified by one cheap round-trip on the chosen path only. |
| 3 | "Sender joins the receiver's hotspot automatically" | Two problems. (a) `HotspotController.join()` is **never called anywhere** — the client side of that path does not exist today. (b) On Android 10+ a `WifiNetworkSpecifier` join **always shows a system consent sheet** that cannot be suppressed. | **High** — over-promises "no taps" | §2/§7: the path is built (it is genuinely new code), and the UX tells the truth: **exactly one system tap** on the joining phone. |
| 4 | "Read the p2p group SSID + passphrase from `WifiP2pGroup`" | `createGroup()` here never sets credentials, so the group gets a vendor-random `DIRECT-xx-…` name and key; the code then *reads them back* — and on API 30+ `getPassphrase()` is redacted for non-owners and unreliable even for owners. | **High** — the QR would sometimes carry a blank/wrong password | §3: on API 29+ create the group **with known credentials** (`WifiP2pConfig.Builder().setNetworkName().setPassphrase()`); on <29 read them back (works there). |
| 5 | "Bind sockets to the chosen network" assumed already true | `runSend()` binds to `tools.activeWifiNetwork()` — a single **global guess**. After joining the receiver's AP while still associated to the home router, that guess is the *router*, so connections go out the wrong interface and fail. | **High** — the new paths would fail mysteriously | §7: the `Network` object returned by the join is threaded through the session into `SocketConnector`; never re-derived. |
| 6 | "Receiver advertises `sessionBinding()`" | `sessionBinding()` returns **one** address (station Wi-Fi first). A sender arriving over the AP interface must be told the AP-side address, and the beacon must answer on every interface. | Medium | §7: advertise **one address per active interface**; beacon already binds `0.0.0.0` (verified), receiver engine already binds all interfaces (verified: `bindAddress = null`) — only the *advertised* address is wrong. |
| 7 | "AP passphrase = 32 random chars we generate" | `startLocalOnlyHotspot()` **chooses** the SSID and passphrase; we cannot set them (setting them is `@SystemApi`). | Medium — wrong in the docs and in any test that assumes it | §3: use what LOHS gives, display it only in the QR, tear it down with the session. Known credentials are only possible for p2p (API 29+) and the user's own hotspot. |
| 8 | "8 s per rung, 20 s total" | Too tight for p2p (group creation + join legitimately takes 10–22 s), and a total budget **cannot pause** while an Android system sheet or the user is deciding. It would report failure on paths that would have worked. | Medium | §3: per-rung budgets (LAN 2 s, AP join 12 s *excluding* time a system sheet is up, p2p 25 s), plus live progress and a Cancel button. The old 22 s constant was never the bug — *silence* was. |
| 9 | "CI asserts 85 % of the 416 MB/s baseline" | Absolute MB/s on shared CI runners is flaky; one bad neighbour on the host fails the build for no reason. | Medium | §9: assert a conservative floor with margin **plus** an explicit "protocol constants unchanged" test (chunk size, stream counts, reporter cadence) — that is the real regression guard. |
| 10 | "Failover continues on the next candidate automatically" | Resume works, but the *address* changes per path. Without a reachability channel (the other phone's IP on that network is unknown) automatic cross-network failover is impossible; only an in-place reconnect is. | Medium | §5: failover retries the **same** path first (covers real drops); moving to a different network is a **1-tap rescan** that keeps all progress via the existing chunk map. |

Two further things v1 missed entirely:

* **The receiver can hang for 180 s.** `TransferHub.awaitDecision(180_000)` runs in the service. If the
  app is not foreground the Compose Accept dialog cannot appear, so the sender waits three minutes and
  then gets "No answer". → §6.4 fix.
* **The QR must stay on screen to be scannable.** v1 said "receiver stays discoverable in background",
  which is self-contradictory: a backgrounded phone shows no code. → §6.5 fix.

---

## 1. Evidence (updated, with two new confirmed bugs)

1. **Receiver never opens the transfer screen** — `AppViewModel.kt:306` sets `Screen.Transfer` in
   `sendTo()` only. Receive path has no navigation. → no % and no speed on the receiving phone.
2. **The phone-to-phone hotspot path does not exist** — `PeerKind.HOTSPOT` is read in two `when`
   branches (`TransferService.kt:486`, `Screens.kt:764`) and **never produced**;
   `HotspotController.join()` has **zero call sites**; `HotspotController` is constructed only in
   `WebService.kt:123`, for the browser flow.
3. **p2p groups are created without known credentials** — `Discovery.kt:610-624` sets only the band.
4. **Path choice is by ping, not bandwidth** — `peersSnapshot()` sorts by `rttMicros`.
5. **One attempt, no fallback** — `runSend()` branches on `discovered.kind`; failure ends the session.
6. **Silent stalls** — 22 s p2p join, 6 s scan interval, no progress.
7. **Binding is a global guess** — `tools.activeWifiNetwork()` in `runSend()`.
8. **QR carries only a LAN address** — `TransferService.kt:682`.
9. **Two chained permission prompts** — `MainActivity.requestPermissions()`.
10. **No per-file progress anywhere** — `fileResults` is only appended in `onFileDone`.

---

## 2. Corrected experience (honest version)

```
   [ Send ]  [ Receive ]                    ← unchanged
        ↓ pick files
   ┌─────────────────────────────────┐
   │   Show this code  │  Scan code  │      ← one screen, two big buttons
   └─────────────────────────────────┘
```

* **Show this code** — one QR containing what is *actually usable right now* (LAN, or the one
  fallback network that is really up), the 6 digits underneath, and a plain sentence naming the path:
  `Same Wi-Fi · 192.168.1.24` or `No shared Wi-Fi · this phone made "LoopJet-3F2A"`.
* **Scan code** — reads the QR, and:
  * same Wi-Fi → connects with **zero** further taps;
  * fallback network → **one system tap** ("Connect to LoopJet-3F2A?") then it connects;
  * `WIFI:` code → joins that network, one tap.

Truthful tap count: **0 taps** on a shared network, **1 tap** otherwise. No plan can do better on
Android 10+ without a rooted device, and pretending otherwise is what makes apps feel broken.

---

## 3. The ladder — exclusive states, not parallel candidates

Because one radio serves one of these at a time, the receiver holds **exactly one fallback network**
and the QR describes that one. The sender walks the list; each rung is a state change, and every rung
has a guaranteed teardown.

| Rung | Receiver (host) | Sender (joiner) | Known credentials? |
|---|---|---|---|
| 1. Shared Wi-Fi | nothing | connect to LAN address | n/a |
| 2. **Local-only hotspot** (preferred fallback) | `startLocalOnlyHotspot()` — *no SIM, no tethering, no mobile data* | join via `WifiNetworkSpecifier` (1 system tap) | passphrase **chosen by Android**; read from the reservation |
| 3. **Wi-Fi Direct group** | `createGroup()` with **our own** network name + passphrase (API 29+) — the `DIRECT-…` case | join via our own WifiP2p connect path | **yes**, chosen by us |
| 4. Manual / system-camera | show `WIFI:T:WPA;S:…;P:…;;` as a secondary code | system camera joins, then scan the app code | yes |
| 5. User-made hotspot | nothing | QR carries the SSID + password you typed in Settings | yes |

Rules:
* **Rung 2 fails → rung 3 is provisioned and the QR is redrawn** (`ERROR_INCOMPATIBLE_MODE`,
  `ERROR_TETHERING_DISALLOWED`, OEM lock, or no SIM policy). The QR is derived from session state, so
  it updates automatically; the sender is told to **rescan** rather than guessing.
* **Single-radio warning:** if starting the AP drops the station Wi-Fi, the LAN candidate is removed
  from the QR at the same time (the state is re-read, not assumed).
* **Never two networks at once.** The sender joins at most one AP per session; the receiver hosts at
  most one network.
* **No plan survives a lying OEM:** every rung reports *which* call failed, and the failure text names
  the next action ("Scan the new code" / "Turn on Wi-Fi").

---

## 4. QR payload v2 — new scheme, small, honest

```
loopjet2:<base64url(JSON)>
{ "v":2, "id":"…", "fp":"…", "n":"Zain's phone",
  "lan": {"h":"192.168.1.24","p":43130},          // omitted when not on a shared network
  "net": {"k":"ap"|"p2p", "ssid":"LoopJet-3F2A", "psk":"…", "h":"192.168.49.1", "p":43130} }
```

* **One network in the payload, not three** — keeps the code ≤ ~220 bytes (scannable at ≥ 280 dp,
  EC level M). v1's three-candidate version was ~450 bytes and would have been a scannability risk.
* **No fake backwards compatibility.** v1 phones see `loopjet2:` → "This code is from a newer LoopJet;
  update the other phone" instead of "not a LoopJet code".
* **The scanner also accepts `WIFI:` payloads** (from rung 4/5), so one scanner handles every code on
  the screen. It already accepts bare 6-digit codes.
* **Redraw on change:** the QR text is a function of live session state, so any change of path redraws
  it; the code carries a short `exp` (30 min) and the sender verifies reachability before pairing.
* **The passphrase never leaves the screen** except into the QR: not logged, not in
  `TransferHub.message`, and **redacted from the diagnostics bundle** (`psk=***`).

---

## 5. "Fastest path" — corrected semantics

v1 promised measured comparison of simultaneously available paths. That is not possible. Corrected:

1. **Prediction before connecting** (cheap, no side effects): interface facts only — band from
   `WifiInfo.frequency`, `linkSpeed`, Wi-Fi generation if reported, whether the phone is already on a
   shared subnet, and whether a hotspot/p2p group already exists. Produces the ordered rung list.
2. **Prefer what is already up.** If LAN is reachable it wins by default: it requires no state change,
   no consent sheet, and is usually the fastest (5/6 GHz with MIMO). Only if LAN is unreachable or
   provably isolated do we move to rung 2.
3. **Verify, don't benchmark.** One authenticated beacon round-trip on the chosen path (already
   implemented, ~1 ms) confirms it before the session starts. No 200 KB probe, no extra handshake.
4. **Stick to the chosen path** for the whole session.
5. **Failover:** transient drop → reconnect to the **same** address up to 3 times (the existing
   resume/chunk-map makes that free). Different network → one-tap rescan; all progress preserved.
6. **Explain it:** `Connected · Wi-Fi 5 GHz · ready` with a "Why?" that lists the rungs not taken and
   the reason (`hotspot: not needed`, `p2p: refused by this ROM`).

This is honest, it satisfies "pick the fastest available", and it cannot lie about a comparison it
never made.

---

## 6. Transfer interface (both phones) — tightened

```
┌──────────────────────────────────────────────┐
│ ←  Sending to Zain              ≈ 38 s left  │
│ ██████████████████████░░░░░░░░░  64 %        │  bar: gradient, % inside
│ 412 MB/s · 1.8 GB of 2.9 GB · 3 of 7 files   │
├──────────────────────────────────────────────┤
│ ●─╮ ◔ holiday-video.mp4      1.2 GB    done  │
│ ●─╮ ◕ IMG_2041.heic          4.1 MB    82 %  │  ring = that file's bytes
│ ●─╮ ○ photos.zip             620 MB  queued  │
│ ●   ✓ notes.pdf               88 KB    done  │
├──────────────────────────────────────────────┤
│            [ Pause ]      [ Cancel ]         │
└──────────────────────────────────────────────┘
```

* **6.1 Same screen on both phones**, opened automatically **when the activity is resumed**; if the
  app is in the background, a progress notification with the same numbers, and the screen opens on
  return. Navigation is skipped if the user is mid-onboarding or on the scanner.
* **6.2 Data**: total %, speed, ETA and per-file rings come from one additive engine field (§8).
  Files listed newest-first-stable, `LazyColumn(key = fileId)`, no per-byte animation.
* **6.3 Rings**: Canvas arc on a track, 3 dp, gradient; done → full + check; failed → error tint and
  the reason; queued → empty at 30 % alpha. Icons reuse the existing `extensionBadge()`/thumbnails.
* **6.4 No more 180 s hang**: if the app is not foreground when an offer arrives, auto-decline after
  **10 s** and post a notification with **Accept / Decline** actions; the sender sees
  "Zain's phone is not showing LoopJet" in seconds instead of three minutes.
* **6.5 The code stays readable**: `FLAG_KEEP_SCREEN_ON` while the code is shown, and the code + 6
  digits mirrored into the ongoing notification so the user can leave the screen without losing it.

---

## 7. Network plumbing (the part v1 got wrong)

* The `Network` returned by `NetworkCallback.onAvailable` (AP/p2p join) is stored on the session and
  passed into `SocketConnector`; `NetworkTools.bind(socket, thatNetwork)` binds the data sockets to it.
  `activeWifiNetwork()` stays only as a fallback for rung 1.
* The **join callback is not released** until the session ends. Today `cancelJoin()` unregisters the
  callback, which on Android tears the network down — so the teardown path must move to session end
  (`stopSelfSafely` / `onDestroy`), never to "connect finished".
* The receiver advertises **one entry per interface** (station, AP, p2p) so the QR can name the address
  the sender will actually reach; the beacon already answers on all interfaces and the receiver engine
  already binds `0.0.0.0` (both verified), so only advertising changes.
* Best-effort guard: if the phone is already running its **own** hotspot (heuristic: an `ap*`/`swlan*`
  interface with an address before we start anything), prefer p2p and warn instead of silently
  breaking the user's tethering.

---

## 8. Safe implementation (unchanged rules, plus one correction)

1. **Hot path untouched**: `CHUNK_SIZE` (256 KiB), stream counts, socket buffers, AES-GCM record
   layout, the 100 ms reporter, the chunk map. The only engine addition is
   `ConcurrentHashMap<Int, AtomicLong>` incremented next to the existing byte counter in both the
   sender's read loop and the receiver's write loop, exposing **in-flight + recently finished** files
   only.
2. **Additive protocol**: v2 QR is a client-side encoding change; no new listening port, no new
   crypto, no new wire field. A v1 peer still transfers over rung 1 with today's `loopjet://` code.
3. **Every new resource has a teardown** registered in `stopSelfSafely()` and `onDestroy()`:
   AP reservation, p2p group, network callback, wake lock, keep-screen-on.
4. **Permissions:** no new ones (CAMERA already added). SoftAP uses `NEARBY_WIFI_DEVICES` (API 33+),
   already requested; API 26–32 needs location, already declared with `maxSdkVersion="32"`.
5. **No secrets in logs** — psk redacted everywhere, including the diagnostics bundle.

---

## 9. Testing — what CI proves, what only your phone can

**CI (every push)**
* QR: v2 encode/decode, v1 still accepted, `WIFI:` accepted, truncated/garbage rejected, ≤ 256 bytes.
* Ladder state machine with a fake platform: LOHS refused → p2p chosen; p2p refused → manual; each
  rung's teardown called exactly once (assert on a mock).
* Ranking: table-driven predictions (LAN up / isolated / 2.4 GHz only / hotspot already running).
* Per-file progress: deltas sum to the total, no duplicate or lost file ids, 2 000-file case.
* Failover: drop the socket at 40 % → same-address reconnect → final hash matches (extends the
  existing resume test).
* **Throughput guard, non-flaky**: floor with margin (e.g. ≥ 120 MB/s for the 192 MB loopback,
  ≥ 2.5 MB/s for 2 000 small files) **plus** a unit test asserting the protocol constants above are
  unchanged — that is what catches a real regression.

**Your phone (5 min, 8 cases):** same 5 GHz · 2.4 GHz · guest Wi-Fi with AP isolation · one phone on
mobile data · no router at all · Samsung refusing the hotspot · Wi-Fi off at 50 % · screen locked.
Expected result is stated per case in §11 so a screenshot is enough for me to act.

---

## 10. Phases (each ships an installable APK)

| Phase | Delivers | Risk | Why in this order |
|---|---|---|---|
| **P1** | Both phones show the transfer screen: bar with %, speed, ETA, per-file timeline with rings. Fixes the 180 s hang and keeps the code in the notification. | Low | Pure UI + 1 counter. No network change, so it cannot break what already connects. |
| **P2** | One-screen connect: "Show this code / Scan code", 6 digits underneath, honest path sentence. | Low | Removes the multi-step hunt without touching transports. |
| **P3** | Rung 2 for real: build the missing AP client (`join` is dead code), thread the `Network`, exclusive-state ladder, per-rung timeouts, guaranteed teardown. | **Medium-high** | This is genuinely new network code; it ships alone so a failure is attributable. |
| **P4** | Rung 3 credentials fix (`setNetworkName`/`setPassphrase` on API 29+), QR v2, prediction ranking, "Why?". | Medium | Depends on P3's plumbing. |
| **P5** | Connection doctor (live per-channel state, redacted copyable report). | Low | Needs the ladder's real states to be worth anything — so it comes after P3/P4, not before (v1 had it last for the wrong reason). |

---

## 11. Success criteria (how we know it worked)

* Shared Wi-Fi: pick files → scan → transferring with **0** extra taps; both phones show % within 1 s
  of Accept.
* No shared network: pick files → scan → **1** system tap → transferring; the sender never has to
  choose a network or type anything.
* Any failure is reported within **≤ 25 s** with a named reason and the next action; nothing hangs.
* The receiving phone always shows progress (the v1 bug), and the sender never waits 180 s.
* Loopback throughput stays within the CI floor; protocol constants byte-identical.

---

## 12. Decisions I need (v1 asked four; two were mine to make)

Answered by me, since they were design questions with a clear right answer:
* ~~SoftAP vs p2p order~~ → **SoftAP first, p2p as fallback**: no consent sheet on the host, no 22 s
  negotiation, and it is the only path that works with no SIM.
* ~~Which phone hosts~~ → **the receiving phone**, always: it is the one already showing "Receive", and
  it keeps the sender's flow to a single scan.

Still need you:
1. **Tap budget** — is "0 taps on shared Wi-Fi, 1 system tap otherwise" acceptable, or do you want me to
   also add the **Share-sheet shortcut** ("Share to LoopJet" → straight to connect with files
   attached), which removes the picker trip too?
2. **Rung 4/5 exposure** — show the "Wi-Fi code / password" option **always** in an overflow menu, or
   only after rung 2 and 3 both fail?
3. **Receiver hosting cost** — the AP keeps running while the Receive screen is open; acceptable, or
   start it **only when you tap Show code** (saves battery, adds one tap)?

Say which phases and I'll implement.
