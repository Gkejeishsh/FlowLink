# LoopJet — engineering plan, protocol and research log

> **App name:** LoopJet — one string resource (`res/values/strings.xml → app_name`).
> **Application ID:** `com.loopjet.app` · **minSdk 26** · **compile/target SDK 37** · Kotlin + Jetpack Compose.
> This document is written *before* the code and updated afterwards with measured results.
> It records what was verified, what had to be corrected, and where a promised capability is
> **not** achievable through public Android APIs (with the fallback we ship instead).

---

## 0. Mission and how this project is judged

Move any file between two Android phones, offline, with no account and no ads, faster than the
incumbents. Every decision below is scored on two numbers only:

| Metric | Target | How it is measured |
|---|---|---|
| Device → connected (handshake complete) | **≤ 5 s** | timestamps in `ConnectLog`, shown in the in-app Benchmark screen and in `TEST_REPORT.md` |
| Accept → first data byte | **≤ 1 s** | sender records `t_accept` and `t_firstPayload` |
| Sustained throughput | **≥ 80 % of negotiated link rate** on the best path | achieved MB/s ÷ `WifiInfo.linkSpeed` (or p2p group freq/rate), measured by the built-in benchmark |
| One 10 GB video | limited only by radio/storage | loopback and hardware measurements in `TEST_REPORT.md` |
| 5 000 small files | no per-file handshake | batched bundle frames + single manifest exchange |

Honesty rule used throughout: **a number appears in the report only if a test printed it.** Numbers
measured on the JVM loopback rig and numbers measured on real radio hardware are labelled
separately, and every feature that could not be exercised on hardware is listed as
*implemented-but-not-hardware-verified* rather than claimed.

---

## 1. Research log (sources actually relied on)

### 1.1 On-device buffer management — the single biggest throughput lever

* **SMUFF: Towards Line Rate Wi-Fi Direct Transport with Orchestrated On-device Buffer Management**, USENIX NSDI '24 — <https://www.usenix.org/system/files/nsdi24-wang-chengke.pdf>, <https://www.usenix.org/conference/nsdi24/presentation/wang-chengke>
  * Wi-Fi Direct file transfer under-uses the link because of **three overflow-prone buffers on the data path**: sender Qdisc, receiver socket read buffer, receiver application buffer. Overflow ⇒ drops; underflow ⇒ the radio idles.
  * SMUFF keeps the bottleneck buffer inside a "safe range" of backlog, computed from measured fill/consume rates and the control latency (≈ RTT). Reported link utilisation **88.7 % on 802.11ac, 91.8 % on 802.11ax, up to 94.7 % of practical line rate**, with 22.6 % more throughput, 37 % less CPU and 15 % less energy than TACK/CUBIC.
  * Also reports that **per-packet ACK policies exacerbate channel contention**, that CUBIC/BBR are wrong for a single-hop single-flow link, and that RTT rises to ~25 ms under load (bufferbloat) — so a latency signal is a usable congestion signal.
* Design consequences adopted here (§4): independent read → encrypt → send → write stages with **bounded queues**, **reused large buffers**, **batch ACKs with a chunk map** (not per packet), **sender-side pacing driven by the receiver's delivered-byte + queue-depth reports**, and **no kernel-level tricks or root**.
* The paper reaches line-rate with a UDP-based transport in a system-service context. We ship an
  app-store-safe APK, so we use TCP with **N parallel streams**, large socket buffers, batch ACKs
  and adaptive stream control — a deliberately lower-risk approximation with the same
  "never let a buffer run dry, never let one overflow" policy at the application layer.

### 1.2 Android platform behaviour (checked against current docs, October 2026)

* **Foreground service timeouts** — <https://developer.android.com/develop/background-work/services/fgs/timeout>
  * Apps targeting Android 15+ get **6 h per rolling 24 h total** for `dataSync`; then the system calls
    `Service.onTimeout(int startId, int fgsType)` and the service must call `stopSelf()` "within a few
    seconds" or the process is killed (`ForegroundServiceDidNotStopInTimeException`).
  * Consequence (§8): our transfer service is `dataSync|connectedDevice`, implements **both**
    `onTimeout` overloads, finishes the in-flight chunk, persists the chunk map, posts a
    "paused — tap to resume" notification and stops. Resuming after the quota is exhausted requires
    the app in the foreground (the timer resets on foreground interaction) — the UI says so.
* **Local network permission** — <https://developer.android.com/privacy-and-security/local-network-permission>, <https://developer.android.com/about/versions/17/behavior-changes-17>
  * Android 16: opt-in protection; `NEARBY_WIFI_DEVICES` is temporarily reused.
  * **Android 17 (API 37): mandatory.** Apps targeting 37 get local network access **blocked by
    default** and must declare + request the runtime permission **`ACCESS_LOCAL_NETWORK`**
    (NEARBY_DEVICES group). Apps targeting ≤ 36 keep an implicit grant through `INTERNET`.
  * Because this build targets 37, we declare `ACCESS_LOCAL_NETWORK`, request it just-in-time and
    treat "denied" as a first-class state (every path that needs the LAN shows a fixable banner
    instead of failing silently — the failure mode reported by several apps' issue trackers).
* **Wi-Fi Direct band control** — <https://developer.android.com/reference/kotlin/android/net/wifi/p2p/WifiP2pConfig>, <https://developer.android.com/about/versions/10/features> (5 GHz example)
  * `WifiP2pConfig.Builder` (API 29): `setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)`,
    `setGroupOperatingFrequency(MHz)`; **`GROUP_OWNER_BAND_6GHZ` was added in API 36**.
    Band and frequency are mutually exclusive. `discoverPeersOnSpecificFrequency` exists but must be
    feature-checked with `isChannelConstrainedDiscoverySupported()`.
  * Consequence (§4.2): we create the group with 6 GHz → 5 GHz → auto fallback, and we **report the
    frequency we actually got** (`WifiP2pGroup.frequency`) instead of claiming 5 GHz.
* **Local-only hotspot** — <https://developer.android.com/guide/topics/connectivity/localonlyhotspot>, <https://developer.android.com/reference/android/net/wifi/WifiManager>
  * The public API `startLocalOnlyHotspot(callback, handler)` takes **no band**. The overload that
    accepts a `SoftApConfiguration` is annotated `@SystemApi` (see AOSP `WifiManager.java` and
    <https://gist.github.com/spieglt/09027eefe098af6a6207181ad3aeabf7>); calling it by reflection is
    blocked by the hidden-API policy and would crash on many builds.
  * **Correction to the brief:** an app cannot force a 5 GHz local-only hotspot. We therefore ship the
    hotspot path as "platform-chosen band", read back the reservation's `SoftApConfiguration`, display
    the real band, and offer the *user's own system hotspot* (which the user can set to 5 GHz) as the
    fastest fallback, with the receiver auto-joining through a QR payload. Claiming a forced 5 GHz
    hotspot would be a lie, so we do not claim it.
* **PackageInstaller / split APKs** — <https://developer.android.com/reference/kotlin/android/content/pm/PackageInstaller.Session>
  * One session can hold a base APK plus any number of splits, written with
    `session.openWrite(name, 0, size)`; **all APKs must share package name, version code and signing
    certificate**, split names must be unique, and a `MODE_FULL_INSTALL` session must contain a base.
  * Consequence (§7): the receiver installs every split of a sent app in **one** session, names the
    base entry `base.apk`, the splits by their real split names, and surfaces
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature conflict) and `INSTALL_FAILED_VERSION_DOWNGRADE`
    as human sentences.
* **Compose / Material 3** — <https://developer.android.com/jetpack/androidx/releases/compose-material3>, <https://developer.android.com/build/releases/agp-9-4-0-release-notes>, <https://developer.android.com/jetpack/compose/setup-compose-dependencies-and-compiler>
  * Stable material3 is **1.4.0**; `MaterialExpressiveTheme`, `MotionScheme` and the expressive
    component set are **internal** there and only public from 1.5.0-beta. **Correction to the brief's
    "1.5 alphas" note:** by October 2026 the expressive APIs are public in the 1.5 **beta** line, but
    1.5.0 is not stable and drags in Compose foundation alphas.
  * Decision: stay on the stable BOM and **rebuild the Expressive feel with custom spring physics,
    `Animatable`, `graphicsLayer` and custom shape morphs** — no experimental API, no version roulette.
* **Wi-Fi Direct vs. incumbent speeds** — vendor blogs and independent round-ups consistently place
  Xender/SHAREit in the 20–42 MB/s band, Quick Share direct transfers lower on the same hardware, and
  LocalSend on a shared Wi-Fi 6 LAN at 70–95 MB/s:
  <https://ushareit.io/blogs/shareit-vs-xender-vs-zapya/>, <https://punprime.com/localsend-vs-quick-share/>,
  <https://ifilesender.com/blog/how-fast-can-file-transfer-be-on-the-same-wi-fi/>.
  These are marketing/third-party numbers; they are used only to choose which *path* to prefer, never
  quoted as our result. 5 GHz/6 GHz links are consistently 2–3× faster than 2.4 GHz in the same
  sources, which is why band selection is a first-class feature here.
* **LocalSend protocol** — <https://github.com/localsend/protocol> (v2.2 at time of writing)
  * Defaults: multicast UDP **224.0.0.167:53317**, REST on TCP **53317**,
    `POST /api/localsend/v2/{register,prepare-upload,upload,cancel}`, fingerprint = SHA-256 of the
    certificate when HTTPS is on. Their own docs note that some Android devices reject multicast
    groups outside `224.0.0.0/24`, which is why "HTTP subnet scan" exists as a fallback.
  * We adopt the *ideas* (multicast announce + subnet-scan fallback + QR/PIN pairing) but ship a
    different, encrypted, resumable protocol (§5). LocalSend v2 wire compatibility is left out of the
    release: it is an explicitly optional stretch goal in the brief and would force a plaintext-HTTP
    or self-signed-TLS server on the receive path, which conflicts with "never fall back silently to
    plain text". Stated as a known limit, not hidden.

### 1.3 Ideas taken from real-world failure reports

* Android 15 `dataSync` crashes: several 2026 bug reports (`ForegroundServiceDidNotStopInTimeException`
  after 6 h) — handled by `onTimeout` + persisted chunk map (§1.2).
* Android 17 local-network silent timeouts: multiple October 2026 issues where LAN transfers just
  "hang" because `ACCESS_LOCAL_NETWORK` was never requested — handled by a permission-aware
  preflight and an explicit error state, never a spinner.
* LocalSend/multicast discovery failing on routers with IGMP snooping or AP isolation — handled by a
  parallel subnet probe, mDNS and BLE as independent discovery channels.
* Split-APK installs failing when only `base.apk` was shared — handled by sending every
  `splitSourceDirs` entry in one session.

---

## 2. Product surface

Screens: Onboarding (guided permissions) · Home · Send picker (+ Apps, Downloads, full storage tree) ·
Receive (discoverable, QR, offers) · Transfer (live) · Invite · Web Share · History · Settings · About.
Everything below exists in code, nothing is a placeholder:

* **Home** — two large Send / Receive buttons, plus Invite, Web Share, History, Settings; radar pulse
  when scanning, avatar bubbles, link-line animation on connect.
* **Send** — built-in browser with categories (Apps, Photos, Videos, Music, Documents, Archives,
  Downloads, APKs) + full storage tree via SAF; multi-select tray with count/size; search; sort;
  lazy thumbnails; virtualised lists that stay smooth with thousands of rows; system share-sheet
  entry point (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) prefilled into the same flow.
* **Receive** — one tap makes the phone discoverable (device name, avatar, QR code, 6-digit code);
  offers show sender, count and bytes; single Accept; "trust this device"; auto-accept for trusted
  devices; files land in a dated folder, are indexed into the gallery immediately, never overwrite.
* **Transfer** — pause / resume / cancel / queue, live speed graph, progress ring, per-file list,
  ETA, automatic resume after a drop, continues with the screen off.
* **Send apps** — installed apps with icon/name/version/size, base + all splits as one item,
  arch/SDK compatibility warning computed from the receiver's advertised capabilities, one-session
  install with progress, plain-language error mapping, explicit "app data and accounts are not
  transferred", and an honest note when game data is unreadable.
* **Invite** — local web server + QR + Wi-Fi QR, branded install page served from the universal APK
  (never modified, never re-signed), live download progress, auto-stop on timeout/stop.
* **Web Share** — the phone as a tiny web server for any browser: list, download (range/resume),
  streamed ZIP of many files, drag-and-drop upload streamed straight to disk, per-browser approval,
  optional PIN, same-subnet enforcement, session expiry.
* **Settings** — device name/avatar, save location, theme (System/Light/Dark), speed mode,
  trusted devices, auto-accept, benchmark, permission health, battery-optimisation guide.
* **Benchmark** — link rate, storage read/write MB/s, achieved throughput with the honest
  "measured here" label and path used.

---

## 3. Architecture

Single Gradle module, hard package boundary between **pure-JVM core** and **Android glue**. The core
never imports `android.*`, which is what makes the full transfer engine testable at line speed on the
JVM in CI (and is how the reported loopback numbers are produced).

```
com.loopjet.app
├── core/                     ← pure Kotlin/JVM, unit-tested, no Android imports
│   ├── json/Json.kt          minimal JSON reader/writer (no org.json, works on JVM tests)
│   ├── crypto/               ECDH P-256 + HKDF-SHA256 + AES-256-GCM record layer, identity keys
│   ├── proto/                frame codec, messages, chunk map, manifest, bundles
│   ├── engine/               sender, receiver, staged pipeline, pacing, resume, stats
│   ├── store/                chunk-map persistence, resume store
│   ├── http/                 hand-written HTTP/1.1 server (range, streaming, uploads, ZIP)
│   ├── files/                name/path sanitiser, collision-free naming, size formatting
│   └── bench/                loopback benchmark harness (CI)
├── platform/                 Android-only adapters
│   ├── net/                  path probing, socket binding, interface/address selection
│   ├── p2p/                  Wi-Fi Direct controller (band ladder, group lifecycle)
│   ├── nsd/                  NsdManager/mDNS + UDP announce + subnet scan
│   ├── hotspot/              local-only hotspot + QR credentials + WifiNetworkSpecifier join
│   ├── ble/                  BLE advertiser/scanner for no-network discovery
│   ├── service/              foreground transfer service, notifications, wake locks, wakelocks
│   ├── storage/              direct file writes, MediaStore gallery indexing, free-space guard
│   ├── apps/                 installed-app enumerator + one-session split installer
│   └── settings/             preferences, identity, trusted devices, history
└── ui/                       Compose UI: theme, motion, screens, components
```

### 3.1 Threading model (data path)

```
 [reader threads ×N] → ArrayBlockingQueue(4) → [cipher threads ×N] → [socket writers ×N]
                                                                          │
 receiver: [socket readers ×N] → [AEAD verify ×N] → ArrayBlockingQueue(8) → [writer thread] → disk
```
* Fixed-size, pre-allocated direct `ByteBuffer`s recycled through a small pool (no per-chunk
  allocation, no GC churn in steady state).
* Blocking I/O on dedicated threads (predictable, no dispatcher starvation); coroutines only for
  orchestration, UI state and the web server's accept loop.
* Control frames (ACK/queue depth/pause) are handled on a separate small thread so a busy data
  thread cannot delay flow control → that is the SMUFF "keep control latency small" lesson.
* Progress is aggregated and emitted at ≤ 10 Hz; the UI reads a `StateFlow` snapshot.

### 3.2 Connection manager (no hardcoded order)

Every candidate path is a `LinkCandidate` with an estimated cost and an actual probe RTT. On opening
Send or Receive we probe **all available** candidates in parallel with a 4 KiB echo (≤ 1 s budget) and
choose by measured RTT/throughput and device capability:

| Rank | Path | Gate | Why |
|---|---|---|---|
| 1 | Wi-Fi Direct group, 6 GHz → 5 GHz → auto | both support p2p; band requested via API 29/36 | one air hop, no router, airtime not doubled |
| 2 | Shared Wi-Fi LAN over the *in-use* interface | same SSID/subnet, probe answers | zero setup; router relays, so ~half the airtime |
| 3 | Local-only hotspot (platform band) / user's 5 GHz hotspot | no p2p or probe failed | works on every phone; receiver joins from QR |
| 4 | 2.4 GHz hotspot | last resort | clearly labelled slower |

The chosen path, its negotiated frequency/link rate, and the measured probe latency are logged and
shown in the Transfer and Benchmark screens. If a path degrades mid-transfer (3 consecutive ACK
timeouts), the engine pauses, re-probes, migrates the socket set and resumes from the chunk map.

### 3.3 Discovery & handshake

* **Same Wi-Fi:** UDP multicast announce on the in-use network + `NsdManager` (`_loopjet._tcp`) +
  **subnet probe fallback** (bounded, parallel, 512 addresses, 250 ms) for routers that drop multicast
  or enable client isolation.
* **No network:** BLE advertising (service UUID + device id) and scanning; the handshake payload
  (address + one-time key + path hint) rides in the advert, so two phones can pair with no network at
  all; Wi-Fi Direct then carries the data.
* **Manual:** 6-digit code, derivation = last 6 digits of `SHA-256(sorted fingerprint pair)` so both
  sides show the same code; QR carries `loopjet://<addr>:<port>#<fingerprint>#<otp>`.

### 3.4 Identity & security

* Per-install **ECDSA P-256 identity key** in the Android Keystore (signing/verification only, no
  export). Fingerprint = SHA-256 of the SubjectPublicKeyInfo DER.
* Every session: ephemeral **ECDH P-256** on both sides → HKDF-SHA256(transcript) → two 256-bit AES-GCM
  keys (one per direction) + a session id. Both sides sign the transcript hash with their identity key;
  the peer's pinned fingerprint (TOFU or QR/BLE/code-learned) is checked. MITM without the pinned key
  fails; a mismatch is a hard stop with a warning, never a silent downgrade.
* Records: AES-256-GCM, 64 KiB plaintext, AAD = `sessionId|fileId|offset|flags` → reorder, replay and
  truncation are all detected. Overhead 16 B/tag ≈ **0.024 %**.
* Received names/paths are sanitised (no `..`, no absolute paths, no NUL/control chars, reserved
  Windows names, length caps, unique-name allocation), free space is checked before accepting, and
  writes go to `<name>.part` + atomic rename after the per-file SHA-256 matches.
* App-to-app traffic is *always* encrypted; plaintext HTTP exists only for the Invite/Web Share pages
  that a browser must be able to open, and those require on-device approval.

---

## 4. Speed engine

1. **Staged pipeline with bounded queues** (as above) so the slowest stage is never starved.
2. **Reused buffers**: 64 KiB payload records, `ByteBuffer` pool, `FileChannel.write(buf, pos)` with
   `RandomAccessFile` per stream. No whole-file reads, no `DocumentFile` in hot paths.
3. **Adaptive parallelism**: streams start at 2, ramp to 8 while throughput keeps rising ≥ 5 % per
   window, back off on RTT growth or ACK gaps (AIMD on stream count, not on cwnd — the kernel does
   that). Measured per window, reported in the Benchmark screen.
4. **Range splitting**: files ≥ 8 MiB are split into contiguous ranges across streams; the receiver
   writes them at absolute offsets into a pre-allocated `.part` file.
5. **Small-file batching**: files ≤ 256 KiB are packed into ≤ 4 MiB bundle records, so 5 000 files cost
   one manifest + ~200 records, not 5 000 handshakes.
6. **Skip what the receiver has**: `prepare` answers per file (`accept` / `skip:have` / `reject:space`),
   using size+mtime+name (and SHA-256 when it is already known), so re-sends are near-instant.
7. **No wasted bytes**: the content type sniffing table refuses DEFLATE for already-compressed types;
   compression is only applied to text-ish payloads and only when it wins ≥ 5 % on a sampled probe.
8. **Cheap encryption**: AES-256-GCM with `Cipher` instances reused per thread (hardware AES on ARMv8);
   the benchmark prints the encrypt-only and full-transfer MB/s so the cost is visible, target < 5 %.
9. **Radio & host tuning**: screen kept on during transfer (`FLAG_KEEP_SCREEN_ON`) with an explanation
   that it is what unlocks the highest-performance Wi-Fi mode; `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` on
   29+, partial wake lock, `setSendBufferSize`/`setReceiveBufferSize` 4 MiB, `TCP_NODELAY`, sockets bound
   to the **chosen** `Network` via `Network.bindSocket` (never the default route).
10. **Overlap**: link pre-warm on screen open, file enumeration + hashing while connecting, first
    payload immediately after accept (`t_firstPayload - t_accept` logged).
11. **UI never on the data path**: progress at ≤ 10 Hz, animation clock throttled, decorative effects
    disabled on low-RAM devices and when the system's animation scale is 0 (animator duration scale is
    read and respected).
12. **Resilience**: per-chunk retry (3×), automatic re-connect with exponential backoff (1 s → 30 s),
    chunk map persisted every ~2 s and on every pause/drop/timeout, so a resume re-sends only missing
    chunks. Foreground service + wake locks keep it alive with the screen off and after swipe-away
    (`START_STICKY` + persisted session).

---

## 5. Wire protocol (LJP/1)

All frames are length-prefixed and AEAD-sealed; the plaintext layout is:

```
record = u32 totalLen | u8 type | payload[totalLen]
type 0x01 HELLO      JSON {v,id,name,platform,sdk,abi,screenRate,caps[]}
type 0x02 AUTH       u8[65] ephPub | u8[64] sig(transcript)          (identity proof)
type 0x03 MANIFEST   JSON {session, files[{id,relPath,size,mtime,mode,sha256?}], totals}
type 0x04 PREPARE    JSON {accept[{fileId, token}], skip[{fileId, reason}], free, chunk}
type 0x05 DATA       u32 fileId | u64 offset | u8 flags | bytes   (flags: 0=data,1=bundle,2=last)
type 0x06 BUNDLE     u16 count | {u32 fileId,u32 len,bytes}*
type 0x07 ACK        u64 deliveredBytes | u32 rttUs | u16 queueDepth | u32 fileId | chunk-map delta
type 0x08 PAUSE/RES  JSON {reason, chunkMap?}
type 0x09 COMPLETE   JSON {fileId, sha256, ok}
type 0x0A ERROR      JSON {code,msg}
type 0x0B BYE        JSON {reason}
```
* Resume: receiver sends its chunk map (bitmap, 1 MiB granularity) in `PREPARE`; sender skips bits.
* Flow control: sender keeps ≤ `windowBytes` (adaptive, default 8 MiB per stream) unacknowledged;
  receiver reports `deliveredBytes`, `queueDepth` and `rttUs` every 32 records or 10 ms, whichever
  comes first. Sender targets a queue depth of 1–2 batches (SMUFF's safe range) and adjusts the
  number of streams, never dropping below 1.
* Every file ends with `COMPLETE` carrying its streaming SHA-256; receiver renames `.part` → final only
  on match (mismatch ⇒ file kept as `.corrupt` and re-requested once, then surfaced honestly).

---

## 6. Permission matrix

| Capability | 26–28 (8.0–9) | 29 (10) | 30 (11) | 31 (12) | 32 (12L) | 33 (13) | 34 (14) | 35 (15) | 36 (16) | 37 (17) |
|---|---|---|---|---|---|---|---|---|---|---|
| Wi-Fi Direct | `ACCESS_FINE_LOCATION` + `CHANGE_WIFI_STATE` | same | + `ACCESS_BACKGROUND_LOCATION` not needed | same | same | **`NEARBY_WIFI_DEVICES`** (+ neverForLocation) | same | same | same | same |
| Wi-Fi scan / SSID | location on | location on | location on | location on | location on | nearby | nearby | nearby | nearby | nearby |
| LAN sockets / mDNS | `INTERNET` | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | opt-in `NEARBY_WIFI_DEVICES` | **`ACCESS_LOCAL_NETWORK`** (runtime) |
| BLE advertise/scan | `BLUETOOTH`,`BLUETOOTH_ADMIN`, location | same | same | **`BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT`** (+ neverForLocation) | same | same | same | same | same | same |
| Local-only hotspot | location | location | location | location | location | `NEARBY_WIFI_DEVICES` | same | same | same | same |
| Notifications | — | — | — | — | — | **`POST_NOTIFICATIONS`** | same | same | same | same |
| Media read | `READ_EXTERNAL_STORAGE` | same | same | same | same | `READ_MEDIA_IMAGES/VIDEO/AUDIO` | + `VISUAL_USER_SELECTED` option | same | same | same |
| All-files write | `WRITE_EXTERNAL_STORAGE` | + `requestLegacyExternalStorage` | **`MANAGE_EXTERNAL_STORAGE`** | same | same | same | same | same | same | same |
| Install APKs | `REQUEST_INSTALL_PACKAGES` + per-app "unknown sources" | same | same | same | same | same | same | same | same | same |
| Foreground service | plain service | `FOREGROUND_SERVICE` | same | same | same | + type permissions | **`FOREGROUND_SERVICE_DATA_SYNC`**, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | + 6 h timeout handling | same | same |
| Wi-Fi/6 GHz info | — | — | — | — | — | `NEARBY_WIFI_DEVICES` | same | same | same | same |

Rule: ask **just in time**, explain in a one-sentence pre-dialog, and always provide a working path if
the user refuses (e.g. no all-files access ⇒ SAF folder picker; no nearby-Wi-Fi ⇒ LAN + hotspot paths;
no notification permission ⇒ transfer still runs, notification is silent).

---

## 7. Storage, gallery and duplicates

* Default target `Download/LoopJet/<yyyy-MM-dd>/` on primary external storage (direct `File` access,
  no SAF in the hot path); optional custom folder via SAF for constrained setups.
* Images/videos additionally inserted into `MediaStore` (or `MediaScannerConnection.scanFile`) so the
  gallery shows them immediately; `setLastModified` preserves the original modified date.
* Never overwrite: `name (1).ext`, `name (2).ext`, … checked atomically under a lock.
* FAT32/exFAT/external-SD: a write that would exceed 4 GiB − 1 on FAT32 is detected and reported
  ("this card uses FAT32, files are limited to 4 GB — choose internal storage"), not silently truncated.
* Free-space check ≥ total × 1.05 before accepting; low-storage aborts with the needed/available bytes.

---

## 8. Services, lifecycle and cleanup

* `TransferService` — foreground (`dataSync|connectedDevice`), notification with speed, pause, cancel,
  `onTimeout` handling as in §1.2, `START_STICKY`, survives screen-off and swipe-away via a persisted
  session record.
* `DiscoveryService` — short-lived foreground service for "discoverable" mode with its own timeout.
* `WebShareService` / `InviteService` — foreground `connectedDevice` with a self-expiring server.
* Teardown: on finish/cancel/crash/`onDestroy`, remove the p2p group (`removeGroup`), close the hotspot
  reservation, release wake/wifi locks, and **restore the previous Wi-Fi state** (we remember whether
  Wi-Fi was on and which network was active, and re-connect to it after a p2p/hotspot session).
* Sockets are bound to the intended `Network`; we never assume `192.168.x.x` — the address shown is
  read from the interface that actually carries the session and checked to be non-cellular.

---

## 9. Design, logo and motion

* **Mark:** a bolt that curves into a closed loop between two nodes (send ⇄ receive), electric blue
  `#2F6BFF` → violet `#8B5CF6` → cyan `#22D3EE`; adaptive icon with a separate monochrome themed layer
  for Android 13+; vector sources in `design/`.
* **Type & shape:** large rounded containers, confident headline scale, 48 dp+ touch targets, one-hand
  primary actions at the bottom third.
* **Motion:** springs (`spring(dampingRatio, stiffness)`), custom `Animatable` dash phase tied to real
  throughput, radar pulse on search, avatars gliding together with a drawn link line, counting-up MB/s,
  smooth live graph, staggered list entrances, press physics, success burst + haptic, error shake.
  Everything respects `Settings.Global.ANIMATOR_DURATION_SCALE == 0` and degrades on low-RAM devices.
* **Themes:** System default, Light, Dark (true-black friendly); cross-fade on switch; both palettes
  checked for ≥ 4.5:1 body-text contrast.
* **Browser pages** (Invite, Web Share): zero external resources, inline CSS/SVG, light/dark via
  `prefers-color-scheme`, light animation only (respects `prefers-reduced-motion`).

---

## 10. Test plan

| Layer | What | Where |
|---|---|---|
| Unit (JVM) | JSON, sanitiser (traversal/reserved/length/unicode), chunk map + resume arithmetic, manifest/token auth, frame codec, AEAD record tamper/reorder detection, HKDF vectors, size formatting, subnet math, HTTP range parsing, APK split grouping, path ranking | `app/src/test` (runs in CI) |
| Integration (JVM, no Android) | full sender↔receiver over **loopback**: 1 × 8 GiB, 5 000 × 8 KiB, 200 × 1 MiB, unicode names, injected mid-transfer drop + automatic resume, pause/resume, encryption on/off comparison, prints **MB/s** and CPU-inclusive encrypt cost | CI, `TransferEngineLoopbackTest` |
| HTTP server (JVM) | range requests, resume, ZIP streaming, 4 GiB+ offsets (sparse), 64 concurrent clients, upload streaming, throughput print | CI |
| Android smoke (emulator) | install debug + release, launch, permissions (nearby/LAN/notification/install), picker, settings persistence, service start | documented; run where an emulator is available |
| Real radio | Wi-Fi Direct 6/5/2.4 GHz ladder, hotspot, BLE, LAN, screen-off transfer, gallery indexing | **hardware-dependent**; listed per-item as verified or not verified in `TEST_REPORT.md` |

CI also builds the **release** variant with R8, runs `lint`, and produces a signed universal APK; the
release build is smoke-tested on the emulator because R8 must not strip runtime-needed classes.

---

## 11. Risk register

| # | Risk | Likelihood | Impact | Mitigation / fallback (shipped) |
|---|---|---|---|---|
| 1 | Wi-Fi Direct unsupported, disabled or OEM-broken | med | high | probe p2p at runtime; fall back to LAN → LOHS → manual code; UI never dead-ends |
| 2 | p2p group negotiates 2.4 GHz despite a 5 GHz request | high | med | read back `WifiP2pGroup.frequency`, show it, retry once with explicit frequency, then accept and tell the user |
| 3 | Local-only hotspot cannot be forced to 5 GHz (SystemApi) | **certain** | med | documented; show real band; offer user's own 5 GHz system hotspot + QR join |
| 4 | Multicast/IGMP blocked by router | high | med | mDNS + parallel subnet probe + QR/manual code |
| 5 | Client isolation on guest Wi-Fi | med | high | probe detects it; redirect to p2p/LOHS path |
| 6 | Android 17 `ACCESS_LOCAL_NETWORK` denied | med | high | explicit preflight + fixable banner; p2p path needs no LAN permission |
| 7 | Android 15 6 h `dataSync` quota | low | med | `onTimeout` → finish chunk, persist map, stop, resume later; message explains the reset |
| 8 | OEM battery killer kills the service | high | med | battery-optimisation exemption request + guide, wake locks, persisted resume; honest guide in Settings |
| 9 | R8 strips reflection-relevant classes | med | high | keep rules for Keystore/zxing/JSON, release build smoke-tested, no reflection in hot paths |
| 10 | Storage too slow (eMMC write ~30 MB/s) | med | med | benchmark shows storage read/write so the bottleneck is visible; receive buffering absorbs bursts |
| 11 | FAT32 4 GiB limit | med | med | pre-flight detection + clear error + "use internal storage" action |
| 12 | Split-APK install rejected (signature/version) | med | med | compatibility pre-check (package/version/cert via `PackageManager`), human-readable errors |
| 13 | Android 16/17 hidden-API or behavior change | med | med | no hidden APIs anywhere; feature-detect every new API with `Build.VERSION` |
| 14 | Cipher unavailable/limited | low | high | AES-GCM is mandatory on all API 26+ devices; benchmark measures cost; encrypt-only fallback reported |
| 15 | Throughput regression from TCP on lossy direct link | med | med | parallel streams, batch ACK, pacing by queue depth; measured live in the benchmark |
| 16 | Web server buffering or caps throughput | low | high | hand-written streaming server with `FileChannel.transferTo`, benchmarked in CI |
| 17 | Keystore loss blocks updates | low | high | keystore + password exported outside the tree with a loud warning in README |
| 18 | Emulator can't represent radio behaviour | certain | low | all radio claims labelled "not hardware-verified" |

---

## 12. Delivery checklist

1. `assembleRelease` clean with R8 + `lint` reviewed.
2. Unit + loopback integration tests green; MB/s printed and copied into `TEST_REPORT.md`.
3. Universal signed APK `LoopJet-1.0.0-release.apk` (single ABI-independent APK, no splits), aligned,
   signed with v2 + v3, `apksigner verify --print-certs` output captured.
4. Dedicated 4096-bit RSA (or EC P-256) release key, 30-year validity, generated outside the source
   tree; keystore + alias + password delivered separately with a backup warning.
5. `README.md` (build / run / re-sign), `PLAN.md` (this file), `TEST_REPORT.md` (measured + limits),
   `design/` (SVG logo, adaptive icon layers, monochrome themed layer, splash, in-app mark).
6. Final report: what was built, where, measured numbers, honest limitations — including which paths
   were verified on real hardware by the user vs. only compiled/simulated here.
