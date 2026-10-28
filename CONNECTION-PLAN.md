# LoopJet — connection & transfer-UI plan (for approval, nothing implemented yet)

Status: **proposal**. No app code has been changed for this plan.
Ground truth read from: `platform/net/Discovery.kt`, `platform/net/Hotspot.kt`, `Net/DirectLink.kt`,
`Net/Network.kt`, `platform/TransferService.kt`, `core/Engine.kt`, `core/Protocol.kt`,
`ui/AppViewModel.kt`, `ui/Screens.kt`. Line references are to commit `735a757`.

---

## 1. Why connecting feels complicated and faulty — the evidence

| # | Finding | Evidence | Consequence on the phone |
|---|---|---|---|
| 1 | **The receiving phone never opens the transfer screen.** | `AppViewModel.kt:306` sets `screen = Screen.Transfer` in `sendTo()` (send path only). Nothing on the receive path does. | After you tap Accept, the receiver falls back to the Receive screen still showing a QR code. No percentage, no speed — exactly what you described. |
| 2 | **There is no phone-to-phone hotspot path at all.** | `PeerKind.HOTSPOT` is *consumed* in two `when` branches (`TransferService.kt:486`, `Screens.kt:764`) but **never produced**. `HotspotController` is only constructed in `WebService.kt:123` (browser sharing). | On Wi-Fi with client isolation (public/guest networks, many routers, "AP isolation") there is no working path. The hotspot idea exists in the code but is not wired into transfers. |
| 3 | **Path choice is not measured; it is guessed.** | Discovery sorts peers by `rttMicros` (`Discovery.kt` `peersSnapshot() = peers.values.sortedBy { it.rttMicros }`). RTT measures latency, not bandwidth. | A 2.4 GHz path with good ping beats a 5 GHz path with more bandwidth, or vice versa. No notion of "fastest link". |
| 4 | **One path, no fallback.** | `runSend()` uses `discovered.kind`; `DIRECT` → join Wi-Fi Direct, otherwise plain TCP. If that fails → session ends with a one-line message. | One attempt, then failure. Nothing tries the next transport. |
| 5 | **Slow, silent stalls before failure.** | `DirectLink.JOIN_TIMEOUT_MS = 22_000`; subnet scan interval 6 s (`Discovery.kt` `scheduleWithFixedDelay(scan, 400, 6000)`); Wi-Fi Direct discovery must have run to see a p2p peer. | Up to ~22 s staring at "Joining Wi-Fi Direct…" with no progress, then a dead end. |
| 6 | **The QR code only carries a LAN address.** | `TransferService.kt:682` builds `loopjet://ip:port#fp#device`. | Useless unless both phones are already on the same subnet. It carries no credentials, so it cannot help you *get* onto a network. |
| 7 | **The receiver must sit on one screen.** | `go()` stops discovery on every screen except Nearby/Scanner; `back()` from Receive can stop the service. | Backing out to look at a file kills discoverability. |
| 8 | **Two chained permission prompts.** | `MainActivity.requestPermissions()` → local-network launcher, then `permissionLauncher`. | Feels like a permission interrogation before anything happens. |
| 9 | **The per-file view does not exist.** | `SessionUi.fileResults` is only populated *after* a file finishes (`onFileDone`). No per-file byte counters anywhere. | The UI can only list finished files, which is why there is no "which file is in flight" feedback. |
| 10 | **No diagnostics surface for the user.** | Discovery has an internal `onDiscoveryLog`; the UI shows one truncated line (`maxLines = 2`). | When it fails you cannot tell *which* channel died, so every report is guesswork. |

---

## 2. Target experience: one action, two ways

Replace "pick files → Next → wait → tap a peer → maybe type a code" with:

```
        [ Send ]  [ Receive ]            ← unchanged, one tap to start either
             ↓ (after choosing files)
   ┌──────────────────────────────┐
   │  Show this code   │  Scan code │     ← ONE screen, two big buttons
   └──────────────────────────────┘
```

* **Show this code** — the phone displays a single QR (plus a 6-digit fallback under it) that
  contains *everything needed to connect*: LAN address **and**, if there is no shared network, the
  Wi-Fi credentials of a network this phone just created. One code, no decisions.
* **Scan code** — the other phone scans it (scanner already built). It reads the payload, joins
  whatever is described, measures the available paths, picks the fastest, and starts.

So: **way 1 = same Wi-Fi (nothing to type), way 2 = this phone creates a network and the QR carries
it (works with no shared Wi-Fi at all)**. Both are the same two buttons; the app decides which.

---

## 3. Connection ladder (the "all cases" answer)

Every case below ends in a transfer; each rung is tried only if the one above cannot work.

| Rung | Transport | When it is used | Why it works on your Samsung |
|---|---|---|---|
| **1. Shared Wi-Fi** | Station Wi-Fi, both on same subnet | Both phones on the same SSID (home router, café Wi-Fi) | Nothing to create, nothing to type. Preferred because it is normally the fastest (5/6 GHz, MIMO). |
| **2. Local-only hotspot (SoftAP)** | `startLocalOnlyHotspot()` on the *receiving* phone; sender joins with credentials from the QR | Wi-Fi exists but peers cannot see each other (AP isolation), or one phone has no Wi-Fi | **Needs no SIM and no mobile data** — it is not tethering, so Samsung's "SIM required for hotspot" rule does not apply to it. |
| **3. Wi-Fi Direct group** | `WifiP2pManager.createGroup()` → group appears as `DIRECT-xx-…` with a WPA2 key | SoftAP refused (`ERROR_TETHERING_DISALLOWED`, policy, OEM lock) | This is exactly the "Direct-SmG90u" style network you described. We read the *real* group SSID + passphrase from `WifiP2pGroup` and put them in the QR, so there is nothing to type. |
| **4. Manual / system-camera join** | Our own `WIFI:T:WPA;S:…;P:…;;` QR + typed SSID/password | App-level join blocked, or the user prefers Samsung's own UI | The system camera understands the Wi-Fi QR format; scanning it joins the network outside LoopJet, then the app's own QR/link completes pairing. |
| **5. Existing hotspot you made yourself** | You create a hotspot in Settings; QR carries SSID+password for the other phone | Caller-side rules where even SoftAP is blocked | Covers "only my carrier's hotspot works" ROMs. |

Design rules that make this safe rather than fragile:

* **Never more than one network change.** The sending phone may join exactly one AP; it never starts
  its own. Only the receiving phone creates one (it is the one already showing "Receive").
* **The QR is versioned and self-describing** so an old build does not misread a new code (see §4).
* **Hard time budget:** 8 s per rung, 20 s total, then a screen that names what failed and offers the
  next rung as a one-tap button. No more silent 22 s waits.
* **Cleanup is guaranteed:** every rung registers a teardown (`reservation.close()`,
  `removeGroup()`), run on success, failure, cancel and service destroy — so no hotspot is left on.

---

## 4. The QR payload (v2, backwards compatible)

```json
{ "v": 2, "id": "<deviceId>", "fp": "<fingerprint>", "n": "<device name>",
  "t": [
    { "k": "lan",  "h": "192.168.1.24", "p": 43130 },
    { "k": "ap",   "ssid": "LoopJet-7F3A", "psk": "<32 random chars>",
                   "h": "192.168.49.1", "p": 43130, "band": "5" },
    { "k": "p2p",  "ssid": "DIRECT-SmG90u", "psk": "<from WifiP2pGroup>",
                   "h": "192.168.49.1", "p": 43130 }
  ] }
```

* `t` = **transport candidates in the order the phone ranks them**; the scanner tries the best
  *reachable* one, not blindly the first.
* Old receivers that get a v2 code fall back to parsing the `loopjet://` prefix (the code still
  starts with `loopjet://v2#<base64url json>` so the existing `parseLink` keeps working).
* A **second QR** button shows the plain `WIFI:` payload for case 4 — one code cannot be read by both
  our app and the system camera, so the screen offers "Wi-Fi code" as a secondary action.
* Security: the AP passphrase is 32 random chars, shown only in the QR, valid only while the
  Receive screen is open (the AP is torn down on exit). The existing AES-256-GCM session and the
  SAS confirmation are unchanged — **the network password is convenience, not the security boundary.**

---

## 5. "Always take the fastest path" (your point 2)

When more than one candidate is reachable, rank by **measured** quality, not by guess:

1. **Probe each reachable candidate** — the receiving phone answers a 3×64 KB echo on its control
   socket (the same authenticated channel, no new port). Total cost ≈ 200 KB and < 600 ms.
2. **Score** = measured KB/s (70 %) + band (6 GHz > 5 GHz > 2.4 GHz, 15 %) + link speed reported by
   `WifiInfo.linkSpeed` (10 %) + RTT (5 %).
3. **Pick the top score, then stick to it** for the whole session. No path switching mid-transfer —
   that would shred throughput and complicate resume.
4. If that path dies mid-session, the existing **resume/chunk map** makes failover cheap: reconnect on
   the next candidate and continue from the last acknowledged chunk (already implemented and tested in
   `TransferLoopbackTest`).
5. Show the choice in plain words on both phones: `Connected · Wi-Fi 5 GHz · 412 MB/s measured`, and a
   long-press/“Why?” that lists what was rejected (`hotspot: not reachable — AP isolation`).

This satisfies both halves of your request: it picks Wi-Fi when Wi-Fi is faster, picks hotspot when
that is the only path, and *knows* which is which instead of assuming.

---

## 6. Transfer interface (both phones, same screen)

Opens **automatically on both devices** the moment a session is accepted (fixes finding #1).

```
┌──────────────────────────────────────────────┐
│  ←  Sending to Zain          ≈ 38 s left     │   header: peer, ETA
│  ██████████████████████░░░░░░░░░░  64 %      │   top bar: gradient fill, % inside
│  412 MB/s · 1.8 GB of 2.9 GB · 3 of 7 files  │   speed + totals
├──────────────────────────────────────────────┤
│  ●─╮                                          │   vertical timeline
│  │ ◔  holiday-video.mp4        1.2 GB   done  │   ◔ = ring around file icon
│  │                                            │
│  ●─╮                                          │
│  │ ◕  IMG_2041.heic            4.1 MB   82 %  │   in flight: ring fills
│  │                                            │
│  ●─╮                                          │
│  │ ○  photos.zip               620 MB   queued│   ○ = empty ring
│  │                                            │
│  ●─╮                                          │
│  │ ✓  notes.pdf                88 KB    done  │   ✓ = full ring, muted row
│  ●                                            │
├──────────────────────────────────────────────┤
│            [ Pause ]      [ Cancel ]          │
└──────────────────────────────────────────────┘
```

Spec:

* **Top bar** — one `LinearProgressIndicator`-style rounded bar, brand gradient, animated with a
  spring, percentage text *inside* the bar (white, bold) so it is readable at any width. Below it:
  instantaneous speed, done/total bytes, files done/total, ETA. All from data the engine already
  emits (`TransferProgress.instantBytesPerSecond`, `etaMs`, `filesDone/total`).
* **Timeline** — a 2 dp vertical line with a node per file; node = the file's circular ring
  (Canvas arc, stroke 3 dp, brand gradient, background track from `outlineVariant`). 44 dp icon in the
  middle (image thumbnail, or a type badge from the existing `extensionBadge()`).
* **Ring fill** = that file's bytes ÷ its size. Done → full ring + check overlay; failed → error tint +
  the reason on a second line; queued → empty ring at 30 % alpha.
* **Rows are stable while scrolling** (`LazyColumn(key = fileId)`), no per-byte animation, text in the
  same type scale as the rest of the app, and every row shows name + human size + percent.
* **Both directions identical** — the receiver sees the same screen with "Receiving from …", so
  "is anything happening?" is answered on both phones.
* Completion state: bar turns solid, "Done · 2.9 GB in 7.0 s (414 MB/s)", and the timeline stays
  scrollable as a receipt.

**What this needs from the engine (small, additive):** per-file byte counters. `Engine` already tracks
`completedIds` and `currentFiles`; I will add a `ConcurrentHashMap<Int, AtomicLong>` incremented where
chunks are written, and expose **only the in-flight files (≤ 6) plus recently finished ones** in
`TransferProgress`. No map of 2 000 entries every 100 ms, no change to chunk size, threads, windowing,
crypto or the reporter cadence.

---

## 7. Safe-implementation rules (your "speed must not change")

1. **Nothing on the hot path changes.** `CHUNK_SIZE` (256 KiB), stream count, socket buffers, the
   reporter's 100 ms tick, AES-GCM record layout and the chunk map stay byte-identical. The only
   engine edit is one atomic increment next to the existing byte counter.
2. **CI guards the speed.** The loopback tests already print MB/s (`416.5 MB/s` for 192 MB, `4.7 MB/s`
   for 2 000 small files). I will turn those numbers into **assertions** (fail the build below 85 % of
   baseline) so any future change that costs throughput breaks the build, not your transfer.
3. **Additive protocol only.** New fields are optional and versioned; a v1 peer keeps working. No new
   listening port, no new crypto, no plaintext payload on the wire.
4. **Every new resource has a teardown** and is registered in `stopSelfSafely()`/`onDestroy()`.
5. **Permissions unchanged** except `CAMERA` (already added) — the hotspot and p2p rungs use the same
   `NEARBY_WIFI_DEVICES` / `ACCESS_LOCAL_NETWORK` set that is already requested.

---

## 8. How I will test it (tester's method)

I cannot touch a phone from this sandbox, so the plan separates *what CI proves* from *what needs your
phone*, and adds a diagnostics surface so failures become reports instead of guesses.

**A. Automated (CI, every push)**
* QR v2 encode → decode round-trip, including a v1 code, a bare code, and a Wi-Fi-only code.
* Path scorer: table-driven cases (5 GHz slow vs 2.4 GHz fast, hotspot-only, unreachable LAN).
* Failover: kill the socket at 40 %, assert resume continues on the second candidate and the final
  hash matches (extends the existing `resume-after-a-dropped-link` test).
* Per-file progress: deltas sum exactly to total, no duplicate/lost files, 2 000-file case.
* Throughput guard as in §7.2.

**B. Device matrix I will ask you to run (5 minutes, screenshots)**
| Case | Setup | Expected |
|---|---|---|
| 1 | Both on home Wi-Fi 5 GHz | connects with no typing, shows `5 GHz` |
| 2 | Same router, 2.4 GHz only | still connects, says `2.4 GHz` |
| 3 | Guest Wi-Fi with AP isolation | rung 2 (SoftAP) kicks in automatically |
| 4 | One phone on Wi-Fi, other on mobile data only | rung 2 or 3 |
| 5 | Airplane mode on both, Wi-Fi on, no router | rung 2 → shows `LoopJet-XXXX`, scanned, transfers |
| 6 | Samsung hotspot refused / SIM absent | rung 3 `DIRECT-…` used, not a dead end |
| 7 | Switch Wi-Fi off at 50 % | resumes on another rung, no restart from 0 |
| 8 | Screen locked mid-transfer | continues, notification shows % |

**C. New "Connection doctor" screen** (Settings › Diagnostics): live per-channel state
(mDNS / multicast / subnet scan / Wi-Fi Direct / SoftAP / BLE), last error per channel, current path
and its score, and **Copy diagnostics**. This is what turns "it doesn't work" into a fixable bug — and
it is what I would build first if you want to report the Samsung case precisely.

---

## 9. Phases (each one ships an installable APK; you can stop after any phase)

| Phase | Delivers | Risk | Files |
|---|---|---|---|
| **P1 — Progress UI everywhere** | Receiver auto-opens the session screen; top gradient bar with %, speed, ETA; per-file timeline with rings; both devices | Low (additive) | `Engine.kt` (+1 counter), `Files.kt` (+1 field), `TransferService.kt`, `AppViewModel.kt`, `Screens.kt` |
| **P2 — One-screen connect** | "Show this code / Scan code"; receiver QR ready in one tap; 6-digit under it; receiver stays discoverable in background | Low | `Screens.kt`, `AppViewModel.kt`, `TransferService.kt` |
| **P3 — QR v2 + ladder** | Versioned payload with candidates; rung 1 → 5 with a 20 s budget and honest per-rung messages; guaranteed teardown | Medium | new `ConnectPlan.kt`, `Discovery.kt`, `Hotspot.kt`, `DirectLink.kt`, `TransferService.kt` |
| **P4 — Fastest-path selection** | Probe + score + stick; failover via resume; "why this path?" explanation | Medium | `ConnectPlan.kt`, `TransferService.kt`, `Screens.kt` |
| **P5 — Diagnostics** | Connection doctor + copyable report | Low | new `ui/ConnectionDoctor.kt`, `Settings` |

My recommendation: **P1 + P2 first** (they fix the two things you feel most: no progress, too many
steps, and they are low risk), then P3/P4 for the Samsung/hotspot and fastest-path work, then P5.

---

## 10. Decisions I need from you

1. **Fallback order for "no shared Wi-Fi":** SoftAP first, then Wi-Fi Direct (my recommendation — SoftAP
   is usually faster and has no 22 s negotiation), or Wi-Fi Direct first because you have seen it work
   on your Samsung?
2. **Receiver as the one that creates the network** (my recommendation: it is the phone already showing
   "Receive"), or should either phone be able to host?
3. **Auto-start on both devices** is fine — no extra confirm screen beyond today's Accept dialog?
4. Do you want me to **also** add a Share-sheet shortcut so "Share to LoopJet" goes straight to the
   connect screen with files already attached (one tap instead of three)?

Answer those and say "implement", and I will start with P1+P2, ship an APK, then continue.
