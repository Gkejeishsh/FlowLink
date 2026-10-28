# LoopJet — engineering plan

Status: 1.0.0 implemented, built, signed and verified (branch `arena/7ef48d45-flowlink`).
This file is the plan that the code follows, and it is kept up to date where reality differs.
Every claim marked **verified** was checked against the primary source listed in
[Sources](#sources); anything unverified is labelled as such, including in `TEST_REPORT.md`.

---

## 1. Product definition

LoopJet is a LAN-only file transfer app for Android 8.0 (API 26) through Android 17 (API 37).

Hard rules, taken as acceptance criteria:

* No internet path for transfers, no accounts, no ads, no analytics, no cloud, no GMS/Firebase on the
  transfer path. The `INTERNET` permission exists only to open local sockets.
* Receiver approval is explicit; every session is authenticated and encrypted; there is no silent
  plaintext fallback.
* Speed is **measured**, never asserted. The in-app benchmark and `TEST_REPORT.md` report numbers with
  the machine they came from.
* One universal signed APK, R8-minified, non-debuggable, zipaligned, apksigner v2+v3.

Non-goals: iOS/desktop clients, cloud relay, internet NAT traversal, LocalSend protocol parity as a
core requirement (nice-to-have, must never delay the core path).

---

## 2. Competitive bar and what to beat

| App | Reported same-LAN throughput | Notes |
| --- | --- | --- |
| ShareIt / Xender / Zapya | 3–20 MB/s typical, "up to" 40 MB/s marketing | ads, accounts, telemetry, cloud paths |
| Quick Share (Nearby Share) | often routed via BLE negotiation then Wi-Fi Direct | requires GMS, not open source |
| LocalSend | ~20–42 MB/s on 5 GHz, HTTPS with self-signed cert | single stream per file, no resume, no apps |

Realistic device-to-device ceilings (Wi-Fi Direct / hotspot, both radios half-duplex):

* 2.4 GHz 802.11n: 5–15 MB/s
* 5 GHz 802.11ac 80 MHz: 40–75 MB/s
* 5/6 GHz 802.11ax (Wi-Fi 6): 75–110 MB/s

Therefore the honest target is **≥ 80 % of the *negotiated* link rate**, not a fixed MB/s number, and
the app must show the link rate it is actually using so the number can be checked.

---

## 3. Verified platform facts that shape the design

1. **Wi-Fi Direct band control exists, but is a request.** `WifiP2pConfig.Builder` (API 29) has
   `setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ|5GHZ|6GHZ|AUTO)` and `setGroupOperatingFrequency()`,
   mutually exclusive. The framework may still fail the group creation if the band is unavailable —
   so the app must read back `WifiP2pGroup.frequency` instead of assuming. 6 GHz
   (`GROUP_OWNER_BAND_6GHZ`) requires API 36 *and* hardware/R2 support. **verified**
2. **The local-only hotspot API cannot choose a band.** `WifiManager.startLocalOnlyHotspot(callback)`
   takes no configuration; the `SoftApConfiguration` overload is `@SystemApi`. Reading the actual
   band back from `WifiInfo.getFrequency()` of the AP interface is the only honest option, and the UI
   must offer the user's own 5 GHz hotspot as a manual upgrade. **verified**
3. **Joining a hotspot always shows a system sheet.** `WifiNetworkSpecifier` requires user consent;
   apps cannot join silently. The UI must not pretend otherwise; the QR carries the credentials and
   the system prompt does the join. **verified**
4. **`dataSync` foreground services are capped at 6 h / 24 h** for apps targeting 35+. The platform
   calls `Service.onTimeout(int,int)` and the service must `stopSelf()` within seconds or the app is
   killed with `RemoteServiceException`. `connectedDevice` has no such cap. LoopJet declares
   `dataSync|connectedDevice`, implements **both** `onTimeout` overloads, and on timeout pauses,
   persists the chunk map, updates the notification and stops. **verified**
5. **Android 17 (API 37) blocks local network access at runtime** for target-37 apps: the new
   `ACCESS_LOCAL_NETWORK` permission must be granted, failures look like silent timeouts. Android 16
   (API 36) has the opt-in variant via `NEARBY_WIFI_DEVICES` + `RESTRICT_LOCAL_NETWORK`. LoopJet
   declares and requests it on 37+, and surfaces "no local network permission" as an explicit error
   instead of a hang. **verified**
6. **Material 3 Expressive is not stable.** `androidx.compose.material3:1.5.0` is beta; the stable
   line is 1.4.x. LoopJet therefore builds the Expressive *feel* — spring press physics, shape
   morphing, oversized type, generous targets — on the stable BOM instead of shipping a beta.
   **verified**
7. **Split APKs install in one `PackageInstaller` session.** One session can carry a base plus N
   splits with identical package/version/signature; `STATUS_PENDING_USER_ACTION` carries the
   confirmation `Intent`. Installing splits one by one is a defect. **verified**
8. **Media visibility on API 29+** comes from inserting with `IS_PENDING=1`, writing, then clearing
   the flag; `MediaScannerConnection` is the fallback below 29. **verified**
9. **SMUFF (NSDI '24)** shows the three real bottlenecks in a file-transfer pipeline are the sender
   qdisc, the receiver socket buffer and the receiver application buffer: unbounded or too-tight
   queues cost double-digit throughput. Design consequence: bounded queues with depth accounting,
   256 KiB chunks, large socket buffers, and pacing so the bottleneck queue stays in the safe range.
   **verified (paper)**

### Corrections to commonly repeated claims (including the brief)

* "Wi-Fi Direct 5 GHz, 6 GHz if both support" — **only a request**, and 6 GHz P2P additionally needs
  API 36+ on both ends. LoopJet ranks by measurement, not by the config it asked for.
* "Local-only hotspot on 5 GHz" — **not possible** with the public API. Only the user's own hotspot
  can be forced to 5 GHz.
* "First byte ≤ 1 s after accept" — the handshake is ECDH P-256 + signature verification; on a cold
  Keystore that can exceed 1 s. LoopJet prewarms the identity and the socket, and *reports* the
  measured accept→first-byte time rather than promising a number.
* "Peers connected ≤ 5 s" — BLE advertising interval, mDNS warm-up and the permission prompt all
  contribute; LoopJet starts discovery while the user is still picking files, which is what actually
  makes the perceived time short.
* "80 % of negotiated link rate" — Wi-Fi Direct and hotspots are half-duplex and a relay through a
  router uses airtime twice; 80 % of *PHY rate* is unreachable in those topologies. The benchmark
  reports achieved MB/s against the reported link rate and labels the topology.

---

## 4. Architecture

```
app/src/main/java/com/loopjet/app/
  core/      pure-Kotlin engine, no Android types except where unavoidable
    Protocol.kt   framing, records, handshake/manifest/control messages, ChunkMap
    Crypto.kt     SHA-256, HKDF-SHA256, AES-GCM, ECDH P-256, Identity
    Json.kt       minimal JSON writer/parser (no reflection, R8-safe)
    Files.kt      path safety, sinks, sources, Fmt, TransferState
    Engine.kt     SenderEngine / ReceiverEngine / SocketConnector / ResumeStore
    Http.kt       WebServer: Invite + Web Share, Range, streaming ZIP, uploads
    Pages.kt      server-rendered HTML/CSS/JS, zero external resources
    Beacon.kt     UDP multicast beacon + beacon client (scoring probes)
  platform/  Android integration
    App.kt        Application, prefs, history, identity (Keystore + software fallback)
    TransferService.kt  foreground service, notification, wake/Wi-Fi locks, Hub state
    WebService.kt       foreground service for Invite / Web Share, hotspot + QR
    Media.kt            MediaStore insert with IS_PENDING, mtime preserved
    Perms.kt            per-SDK permission matrix + OEM battery guidance
    apps/Apps.kt        installed apps, base+split packaging, one-session installer
    net/Discovery.kt    mDNS + multicast + subnet scan + Wi-Fi Direct + BLE
    net/Network.kt      network tools, link rate/band, socket binding, locks
    net/Hotspot.kt      local-only hotspot, real band read-back, QR join string
    Benchmark.kt        storage / crypto / engine measurements on the device
  ui/         Compose: theme+mark+motion primitives, screens, view model
```

Everything on the data path is plain Kotlin over `java.nio` and `Socket`; Compose only ever observes
a `StateFlow` that is updated at most 10 times per second.

### 4.1 Transport

TCP with our own record layer (LJP/1), not HTTP: we need full control of backpressure, resumable
chunk offsets and multi-stream framing, and the browser-facing HTTP server is separate.

* 256 KiB plaintext records, 5-byte header (`u8 type | u32 cipherLen`) used as GCM AAD.
* Nonce = 4-byte direction prefix + 64-bit counter; keys from HKDF over a transcript hash.
* `DATA` = `u32 fileId | u64 offset | u32 length | u8 flags | 32 B SHA-256 | bytes`
* `BUNDLE` = `u16 count` + per-file headers + bytes (small-file batching)
* Control channel carries offer/decision/prepare/resume/pause/cancel/finish; data streams are sealed
  independently, up to 6 in parallel (`controlPort .. controlPort+5`).

### 4.2 Connection manager (rank by probe, never by guess)

Candidates, in preference order but **ordered by measurement**:

1. **Wi-Fi Direct**, 5 GHz requested, 6 GHz if API 36+ and both sides report support. Group owner IP
   from `WifiP2pInfo`; sockets bound to the P2P `Network` (`Network.bindSocket`).
2. **Shared Wi-Fi LAN** — same subnet, mDNS/beacon discovery, direct socket.
3. **Local-only hotspot** (sender owns it) — band read back and reported; 2.4 GHz gets a slow warning.
4. **BLE only** — discovery/metadata path, then hand off to one of the above.

Each viable candidate is probed with a short connect + 64 KiB echo; the winner is the one with the
lowest `rtt + payloadTime`. The probe doubles as the link prewarm, so the transfer starts on a warm
socket. The chosen path and its measured RTT/band are shown in the UI and stored in history.

Discovery is concurrent: `NsdManager` (mDNS), UDP multicast on `224.0.0.167`-style group,
parallel subnet scan limited to the local /24 (or /64), Wi-Fi Direct peer discovery, and BLE
advertising/scanning with a 16-bit service UUID. All of them feed one peer map; duplicates collapse by
fingerprint.

Teardown is a first-class path: `removeGroup()`, hotspot stop, Wi-Fi lock release, and restore of the
previous Wi-Fi state happen in `finally` blocks, `onTimeout`, `onDestroy` and on process start
(recovery sweep for groups left behind by a crash).

### 4.3 Speed engine

* Staged pipeline: **read → seal → send → write**, each stage its own thread with a bounded queue
  (depth 4–8 chunks), so storage and radio overlap.
* Buffers allocated once per session; the sealer writes into a reusable output buffer; no per-chunk
  allocation, no `ByteArray.copyOf` in the hot loop.
* Adaptive parallelism: 2 streams for latency-sensitive small files, up to 6 for large ones; the
  sender measures per-stream progress and rebalances ranges when a stream stalls.
* Big files: ranged parallel reads (`FileChannel.read` at an offset), preallocated sink
  (`setLength`), temp `.part` name, rename only after the SHA-256 of the file verifies.
* Small files: batched into `BUNDLE` records; receiver writes them with one open handle each and
  skips files it already has (size + mtime + name match after safe-path resolution).
* No compression of already-compressed formats; compression is off entirely in this version (it costs
  CPU and buys nothing on photos/video/APK).
* Socket tuning: `TCP_NODELAY`, 4 MiB send/receive buffers where the kernel allows, no `SO_TIMEOUT`
  on the data path (a watchdog thread owns stall detection).
* Power: `PARTIAL_WAKE_LOCK` for the session, `WifiManager.WifiLock` in high-perf mode, "top Wi-Fi
  performance mode" requested on the active network where the API allows it.
* UI throttling: progress events are coalesced to 100 ms and pushed as immutable state snapshots.
* Auto-resume: `ChunkMap` per file persisted every ~2 s of progress and on pause/timeout; on restart
  the receiver sends its map and the sender skips completed ranges.

### 4.4 Server (Invite and Web Share)

One embedded HTTP/1.1 server (`core/Http.kt`), thread pool of 16, 30 s idle timeout, 256 KiB buffers,
streamed bodies both ways.

* Invite: serves the **unmodified** signed APK with Range support, plus a branded install page
  (logo, name, version, size, big download button, install steps, light/dark, live progress).
  Optional hotspot mode adds a Wi-Fi join QR so a phone without a network can still fetch it.
* Web Share: file list, single-file resume (Range), many files as a streamed ZIP (STORED entries with
  precomputed CRC32), uploads streamed straight to `.part` then renamed, per-browser approval with
  optional PIN, 30-minute expiry, same-subnet client filter.
* Client address filtering: only loopback, `::1`, link-local, or same-/24 (v4) / /64 (v6) clients are
  served; everything else gets 403 and is logged in the session.

### 4.5 Security model

* Per-install EC P-256 identity: Android Keystore when it works, software key in private prefs
  otherwise (and the About screen says which).
* Handshake: ephemeral ECDH + signature over the transcript by the long-term key on both sides;
  HKDF-SHA256 with the transcript hash as salt; separate keys per direction.
* Fingerprints are pinned on first use, and trusting a device stores the fingerprint, not the name.
* 6-digit code derived from the transcript and both fingerprints is displayed on both phones
  (visual channel) — used for the "confirm this matches" step, never as the only authentication.
* Received applications: signature and version compatibility are checked; the user is told when a
  signature conflict or downgrade will make installation fail.
* Path safety: `..`, absolute paths, reserved names, control characters, trailing dots/spaces,
  component/path/depth length caps, unique-name resolution, no overwrite.

---

## 5. Permission matrix (as implemented in `platform/Perms.kt`)

| Capability | 26–28 | 29 | 30–32 | 33–35 | 36 | 37+ |
| --- | --- | --- | --- | --- | --- | --- |
| Wi-Fi Direct / hotspot | FINE_LOCATION | FINE_LOCATION | FINE_LOCATION | NEARBY_WIFI_DEVICES | same | + ACCESS_LOCAL_NETWORK |
| BLE discovery | BLUETOOTH/ADMIN | same | same | BLUETOOTH_SCAN/ADVERTISE | same | same |
| Notifications | n/a | n/a | n/a | POST_NOTIFICATIONS | same | same |
| Read media | READ_EXTERNAL_STORAGE | same | READ_EXTERNAL_STORAGE | READ_MEDIA_* | same | same |
| Write received files | WRITE_EXTERNAL_STORAGE | MediaStore | MediaStore | MediaStore | MediaStore | MediaStore |
| Install APKs | REQUEST_INSTALL_PACKAGES | same | same | same | same | same |
| Battery exemption | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | same | same | same | same | same |

Each entry has a title and a one-line reason surfaced before the dialog, plus a documented path that
keeps working when it is refused. Nothing is requested at launch; the first ask happens on the screen
that needs it.

---

## 6. UI and motion

Screens: Onboarding, Home, Send (picker), Receive, Transfer, Apps, Invite, Web Share, History,
Settings, About. Navigation is a single `StateFlow<Screen>` with a cross-fade; no back-stack library
needed, and `BackHandler` maps to the same transition table.

Motion system (custom, on stable Compose):

| Element | Implementation |
| --- | --- |
| Press physics | `animateFloatAsState` + spring (damping 0.55, stiffness 700) on every actionable surface |
| Radar pulse + avatar bubbles | infinite transition driving 3 expanding rings while discovery runs |
| Link dashes | dashes travelling along the link, phase speed tied to reported throughput |
| Progress ring | spring-animated sweep over a gradient brush |
| Live graph | 60-sample ring buffer of instantaneous bytes/s, drawn as a filled path |
| Counting speed | `Fmt.rate` on the coalesced 10 Hz snapshot |
| Shared-element feel | picker tray → transfer screen uses the same item keys and a shared gradient banner |
| Staggered entrance | per-row 22 ms delay, capped at 12 rows so long lists stay cheap |
| Success / error | scale burst + haptic on completion, horizontal shake on failure |
| Reduced motion | animator scale 0, battery saver or low-RAM device disables rich motion entirely |

Everything is drawn with Canvas/Compose primitives; no Lottie, no external resources, 60/120 Hz
friendly (no per-frame allocations in `SpeedGraph`, no recomposition from the data path).

---

## 7. Testing and evidence

| Layer | What runs | Where |
| --- | --- | --- |
| Engine | real-socket loopback: mutual authentication, 192 MiB single file through 4 sealed streams, 2 000 bundled small files, Unicode + nested paths, resume after a dropped link, pause/resume, declined offer | JVM (`TransferLoopbackTest`, 6 tests) in CI |
| Packaging | R8 release build, lint, `zipalign -c`, `aapt2 badging`, `apksigner verify --verbose --print-certs` v2+v3 | CI (`dist/SIGNING-REPORT.txt`) |
| Device | none — no phone or emulator is reachable from this environment | **not tested** |

Not yet covered by automated tests, and listed as such in `TEST_REPORT.md`: the HTTP server routes,
crypto known-answer vectors, a >4 GiB transfer (offset math is 64-bit throughout but no 5 GiB run was
performed — disk bound), APK installs, and every radio path.

Honest consequence: radio paths (Wi-Fi Direct, hotspot, BLE) are **compiled and reviewed, not
executed on hardware**. `TEST_REPORT.md` repeats this in every section that depends on it. The
in-app benchmark exists precisely so a user can produce the missing numbers on real hardware.

---

## 8. Delivery

* `LoopJet-1.0.0-release.apk` — universal, R8, non-debuggable, zipaligned, v2+v3 signed.
* Cert SHA-256 printed by `apksigner verify --print-certs` in `dist/SIGNING-REPORT.txt`.
* Keystore lives outside the source tree (`/home/user/deliverables/loopjet-release.jks`); the alias,
  password, certificate fingerprints and a backup warning are in a separate file outside the
  repository (`/home/user/loopjet-credentials.md`). `.gitignore` blocks `*.jks`/`*.keystore`.
* `README.md` (build, run, architecture, limits), this plan, `TEST_REPORT.md`.

---

## 9. Risk register (each with the mitigation that is actually implemented)

| Risk | Mitigation |
| --- | --- |
| Hardcoded or "first IP wins" peer selection | every candidate is probed; ranking uses measured RTT + throughput; fingerprint duplicates collapse |
| Mobile-data leakage | sockets bound to the chosen local `Network`; the app refuses to start a session when only cellular is up; no external host is ever contacted |
| Drops on Wi-Fi with no internet | mDNS/multicast only, no connectivity checks, `Network.bindSocket` on the local network |
| Assuming multicast or P2P exists | four independent discovery channels plus manual QR/code |
| Leftover groups/hotspots | teardown in `finally`, `onTimeout`, `onDestroy` and a start-up recovery sweep |
| Single stream / tiny buffers | 2–6 adaptive streams, 256 KiB chunks, 4 MiB socket buffers |
| UI on the data path | engine runs on its own threads, UI sees coalesced 10 Hz snapshots |
| SAF in hot paths | SAF is used only to *choose* a folder; data goes through `ParcelFileDescriptor` + positional `FileChannel` reads, with a cached copy only when the provider is non-seekable |
| Buffering web server | streamed request/response bodies, STORED ZIP entries with precomputed CRCs |
| base.apk only, re-signed APKs | base + all `splitSourceDirs` in one session, byte-identical APK files, never touched |
| Split installs one at a time | single `PackageInstaller` session, `STATUS_*` mapped to human messages |
| FGS type mismatch | `dataSync|connectedDevice` declared and started with an explicit type; both `onTimeout` overloads |
| Ignoring Android 15 timeout | timeout pauses, persists the chunk map, stops, and the session is resumable |
| Path traversal / overwrite | `SafePath` + unique names + `.part` rename |
| Unencrypted traffic | LJP/1 is sealed from the first byte after the hello; there is no plaintext mode in the app |
| Committed keystore | keystore and password stay outside Git; `.gitignore` blocks `*.jks`/`*.keystore` |
| Permission dump at launch | just-in-time requests with rationale and refusal paths |
| Unpinned toolchain | every version pinned in `gradle/libs.versions.toml`; wrapper pinned |
| Debug-only testing | release (R8) build is what CI signs and verifies |
| R8 breakage | proguard rules keep the JSON/crypto entry points; release build is smoke-tested in CI |
| Mocks / TODOs / fake progress | no mock transfers; progress comes from counted bytes; TODO/FIXME are grepped in CI |

---

## Sources

Platform and protocol:

* https://developer.android.com/reference/kotlin/android/net/wifi/p2p/WifiP2pConfig.Builder — band/frequency request, API 29, mutually exclusive (verified)
* https://developer.android.com/reference/kotlin/android/net/wifi/p2p/WifiP2pGroup — `getFrequency()` read-back
* https://developer.android.com/guide/topics/connectivity/wifip2p — group owner, DHCP, discovery
* https://developer.android.com/guide/topics/connectivity/localonlyhotspot — no band parameter, callback API
* https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier — join requires the system sheet
* https://developer.android.com/develop/background-work/services/fgs/timeout — `dataSync` 6 h/24 h, `onTimeout(int,int)`
* https://developer.android.com/develop/background-work/services/fgs/changes — Android 15/16 FGS changes
* https://developer.android.com/privacy-and-security/local-network-permission — Android 16 opt-in, Android 17 enforcement
* https://developer.android.com/about/versions/17/behavior-changes-17 — `ACCESS_LOCAL_NETWORK`
* https://developer.android.com/training/data-storage/shared/media — `IS_PENDING` insert flow
* https://developer.android.com/reference/kotlin/android/content/pm/PackageInstaller.Session — one session, splits, `STATUS_*`
* https://developer.android.com/reference/kotlin/android/net/Network — `bindSocket`, `getAllByName`
* https://developer.android.com/build/releases/agp-9-4-0-release-notes — AGP 9.4, Gradle 9.6, JDK 17, build-tools 36
* https://developer.android.com/jetpack/androidx/releases/compose-material3 — 1.4.x stable, 1.5.0 beta (Expressive)
* https://raw.githubusercontent.com/localsend/protocol/main/README.md — LocalSend v2.2 (multicast 224.0.0.167:53317, REST 53317)

Performance research:

* https://www.usenix.org/system/files/nsdi24-wang-chengke.pdf — SMUFF: bottleneck queue management, 88.7–94.7 % line rate, +22.6 % throughput, −37 % CPU
* https://www.usenix.org/conference/nsdi24/presentation/wang-chengke — talk abstract
* Android `TrafficStats`/`Socket` tuning guidance, `setsockopt` behaviour on Linux 4.x+ kernels

Competitor and market claims (marketing pages, treated as low-confidence):

* https://ushareit.io/blogs/shareit-vs-xender-vs-zapya/
* https://punprime.com/localsend-vs-quick-share/
* https://ifilesender.com/blog/how-fast-can-file-transfer-be-on-the-same-wi-fi/

OEM battery behaviour: vendor support pages for Xiaomi/Redmi, Samsung, OnePlus/OPPO/realme, vivo,
Huawei/Honor (summarised in `Perms.batteryGuide`, shown in-app).

---

## 9a. Results that changed the plan (measured, see `TEST_REPORT.md`)

* One pipeline flaw was found by measurement rather than review: the receiver persisted chunk maps only
  for *completed* files, so "resume" re-sent everything. Fixed; a regression assertion now fails if a
  resumed session re-sends the whole file.
* Loopback throughput (192 MiB, 4 streams): 155–371 MB/s depending on CI CPU. The small-file path
  (2 000 files, 5.8 MB) runs at ~4.9–5.3 MB/s, i.e. ~2 100 files/s including per-file digest and rename.
  Both numbers come from the pipeline, not from any radio; see the honesty table in `TEST_REPORT.md`.
* The APK signing step needed three fixes against AOSP's `apksig` sources before `apksigner` accepted
  the output (record framing, then attribute framing). They are documented in `tools/sign_apk.py`.

## 10. Open items (tracked honestly)

* Real-radio verification: needs physical devices; not possible here.
* Wi-Fi 6/6E: the 6 GHz P2P request compiles against API 37 but cannot be exercised without hardware.
* LocalSend v2 compatibility: deliberately out of the core path; not implemented in 1.0.0.
* Play-compliant variant: `MANAGE_EXTERNAL_STORAGE` is declared but never requested by default (SAF is
  the default path), so a Play build only has to remove that one declaration.
