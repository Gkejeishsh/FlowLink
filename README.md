# LoopJet

**Direct file transfer between Android phones, over the air you already have.**

LoopJet moves files between two phones on the same network with no account, no server, no internet
path, no ads and no analytics. The link is authenticated with a per-install key pair, every byte after
the hello is encrypted with AES-256-GCM, and the receiver always decides what lands on its storage.

* `minSdk 26` (Android 8.0) → `targetSdk 37` (Android 17)
* Kotlin + Jetpack Compose (Material 3), no Flutter/RN/GMS/Firebase on the transfer path
* One universal APK: `dist/LoopJet-1.0.0-release.apk` — R8-minified, non-debuggable, zipaligned,
  signed with APK Signature Scheme v2 **and** v3

```
certificate SHA-256  fea2a9d950971eb7fd76948d1f85cd06bbd0347a90722492e2d7968dff0c747d
signer               CN=LoopJet, OU=Release, O=LoopJet, L=Local, ST=Local, C=PK (RSA 4096)
```

`dist/SIGNING-REPORT.txt` is produced by the official `apksigner` inside CI over the committed APK.

---

## What it does

| Screen | What you get |
| --- | --- |
| **Onboarding** | Guided, one-question-at-a-time permission setup. Each permission is explained with what happens if you refuse, and the app keeps working with the paths that remain. |
| **Home** | Big Send / Receive targets, Invite and Web Share, recent history, settings. Radar pulse and avatar bubbles while it is looking for the other phone. |
| **Send** | In-app picker, not a SAF detour: Apps, Photos, Videos, Music, Documents, Archives, Downloads, APKs, plus the whole storage tree. Search, sort, lazy thumbnails, multi-select tray with count and size, thousands of items. Shared files from other apps arrive here pre-filled. Text and links can be sent as a snippet. |
| **Apps** | Installed apps as one item each: icon, name, version, size, and *base APK plus every split* (`splitSourceDirs`). Received apps install through a single `PackageInstaller` session, so the splits land together instead of one at a time. |
| **Receive** | One tap to be discoverable (name, avatar, QR, 6-digit code). An incoming offer shows sender, file count and total size; you accept once, optionally trusting that device. Files land in a dated folder, appear in the gallery immediately and never overwrite anything. |
| **Transfer** | Live speed, live graph tied to real throughput, per-file progress, pause / resume / cancel, queued transfers, automatic resume after a drop, and it keeps going with the screen off. |
| **Invite** | Shares *this* APK, unmodified, through a local web page at `http://<lan-ip>:<port>` plus a QR code. Works over the shared Wi-Fi or a hotspot you create; the page shows the version, size, a download button and install steps. |
| **Web Share** | The phone runs a tiny HTTP server so a browser on the same subnet can pull files (single file with Range/resume, many files as a streamed ZIP) or drop files onto the phone (streamed to disk, nothing buffered in RAM). Each browser needs approval, optionally a PIN, and sessions expire. |
| **History** | Every session with peer, direction, size, duration and achieved speed; tap to send the same selection again. |
| **Settings** | Device name and avatar, save location, theme (System/Light/Dark, true black in dark), speed mode, trusted devices, auto-accept, verify-after-transfer, built-in benchmark, battery-optimisation help. |
| **About** | Version, build, what leaves the device (nothing), and the honest list of what is verified on hardware and what is not. |

---

## How the link is chosen

Ranking is measured at runtime, not hardcoded. Each candidate is probed (name resolution, socket
connect, tiny round trip) and scored by latency and availability:

1. **Wi-Fi Direct at 5 GHz** — the group is created with an explicit 5 GHz band request, and the
   frequency that was actually granted is read back from `WifiP2pGroup`. If both phones report 6 GHz
   support, 6 GHz is requested first (`GROUP_OWNER_BAND_6GHZ`, API 36).
2. **Shared Wi-Fi LAN** — the same SSID/BSSID (or the same IPv4 /24) on a network that is not mobile
   data. A 5 GHz association is preferred; the link speed from `WifiInfo` is used to break ties.
3. **Local-only hotspot** — the phone creates a hotspot; the other side joins it through the system
   consent sheet (or by QR). The public API takes no band parameter, so the granted band is reported
   rather than assumed.
4. **2.4 GHz hotspot** — last resort, with an explicit slow warning in the UI.

Discovery happens in parallel: BLE advertising/scanning, mDNS (`_loopjet._tcp`) and UDP multicast,
subnet scan as a fallback for networks that eat multicast, and QR / 6-digit code as the manual path
that always works. The link is opened while you are still picking files, and files are hashed while
the connection is being established.

If a session used Wi-Fi Direct or a hotspot it tears the group/hotspot down afterwards and restores the
Wi-Fi network the phone was on before. Sockets are bound to the network the session is meant to use, so
a transfer cannot silently fall back to mobile data.

---

## Speed: what the pipeline actually does

* **Staged pipeline**, per stream: read → hash → seal → socket, on the other side socket → verify →
  positional write. Bounded queues, dedicated threads, no work on the UI thread and no coroutine
  dispatch per chunk.
* **256 KiB records**, 4 MiB socket buffers, `TCP_NODELAY`, 2–6 adaptive streams (started by measured
  throughput, not by a constant), and ranged parallel reads for a single big file.
* **Zero per-chunk allocation** in the hot path: pooled read/encrypt/decrypt buffers, positional
  `FileChannel` writes, preallocation, single-write framing.
* **AES-256-GCM** with the platform's ARMv8 crypto extensions; HKDF-SHA256 for key schedule; no
  compression of already-compressed data.
* **Small files are bundled** (up to 256 KiB per record) so 5 000 photos do not cost 5 000 round trips;
  directories are created in one pass, and each file keeps its mtime.
* **Resume by chunk map**: a bitmap of received 1 MiB chunks is persisted, so a dropped link, a
  pause, a swipe-away or the Android 15 foreground-service timeout continues from where it stopped.
  Files are written as `.part` and only renamed after their digests verify.
* **Cheap integrity**: each record carries a SHA-256 and a GCM tag; whole-file verification is optional
  for big files so it never becomes a second copy of the transfer.
* **UI decoupled**: snapshots are coalesced to ~10 Hz, the speed graph is drawn with Canvas primitives.
* **Wake locks and the top Wi-Fi performance mode** are held only for the duration of a session.

Measured on the CI machine over TCP loopback (see `TEST_REPORT.md`): **192 MiB in 518 ms = 370.7 MB/s**
through four sealed streams, and **2 000 small files (5.8 MB) in 1.18 s ≈ 5 MB/s ≈ 2 100 files/s**.
Those numbers measure the pipeline, not a radio: a 5 GHz Wi-Fi 5/6 link tops out around 40–110 MB/s of
payload, so the pipeline is not the bottleneck. Real-radio numbers must be produced on two phones —
`Settings → Benchmark` does exactly that and prints link rate, storage read/write, AES-GCM and achieved
MB/s with the topology label.

---

## Security model

* **Per-install identity**: a P-256 key pair in the Android Keystore (hardware-backed when the device
  offers it) signs the handshake. Nothing secret is ever written to the filesystem.
* **Mutual authentication before any data**: ephemeral ECDH, then each side signs the transcript with
  its identity key. A peer whose identity does not match a pinned fingerprint is refused.
* **Everything after the hello is sealed**: AES-256-GCM, per-direction keys, monotonic nonces, the
  frame header as AAD. There is no plaintext mode and no downgrade path.
* **Human confirmation**: a 6-digit code derived from both fingerprints is shown on both phones, so a
  man-in-the-middle would have to break ECDH *and* the code.
* **Explicit approval**: the receiver sees sender, count and size before anything is written, and can
  remember the device. Auto-accept only ever applies to trusted fingerprints.
* **Storage safety**: path traversal, absolute paths, control characters, Windows-reserved names,
  over-long components and duplicate names are all handled; free space is checked before accepting.
* **No reachable telemetry**: no internet permission use beyond local sockets, no analytics SDK, no
  accounts, no crash reporting that leaves the phone. Cleartext HTTP exists only for Invite/Web Share,
  on the local subnet, with per-browser approval and an optional PIN.

---

## Permissions, per Android version

| Permission | Requested when | Notes |
| --- | --- | --- |
| `NEARBY_WIFI_DEVICES` | 13+ | `neverForLocation`; replaces location for Wi-Fi Direct and discovery |
| `ACCESS_FINE_LOCATION` | 8–12 | Needed for Wi-Fi Direct discovery on those versions only |
| `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` | 12+ | BLE discovery; older versions use the legacy Bluetooth permissions |
| `ACCESS_LOCAL_NETWORK` | 17+ (target 37) | Android 17 blocks local-network access by default; without it sockets fail with silent timeouts, so it is asked for just in time with an explanation |
| `POST_NOTIFICATIONS` | 13+ | Only for the transfer notification; the service still runs without it |
| `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE` | when a category is opened | Per-type on 13+, legacy on 8–12 |
| `MANAGE_EXTERNAL_STORAGE` | Settings → save location | Optional; without it, files go to app storage and are published through MediaStore |
| `REQUEST_INSTALL_PACKAGES` | when installing a received app | Explains the unknown-sources flow, handles downgrade and signature conflicts |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Settings, on request | With OEM-specific guidance in-app |
| `FOREGROUND_SERVICE_DATA_SYNC` + `CONNECTED_DEVICE` | session start | Declared with `dataSync|connectedDevice`; `onTimeout(int,int)` saves state and stops, leaving the session resumable |

---

## Build

Requirements: JDK 21, Android SDK with platform 37 and build-tools 36.0.0, Gradle 9.6.0 (the wrapper
pins it), AGP 9.4.1, Kotlin 2.4.21 (built into AGP), Compose BOM 2026.09.00. Every dependency version is
pinned in `gradle/libs.versions.toml`.

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # engine tests over real sockets
./gradlew assembleRelease        # R8 release APK (unsigned without LJ_KEYSTORE)
./gradlew lintRelease
```

CI (`.github/workflows/build.yml`) runs exactly those tasks, then zipaligns the release payload,
verifies it with `apksigner`, and publishes `ci/*.log`, `ci/summary.txt`, the benchmark lines and
`dist/SIGNING-REPORT.txt` back to the branch. Signing is done with `tools/sign_apk.py`, a small
independent v2+v3 signer, because the keystore must not live in the repository — CI only ever verifies
the committed artefact. Alias, passwords, fingerprint and a backup warning are in a file **outside**
the repository; `.gitignore` blocks `*.jks`, `*.keystore` and `keystore.properties`.

The release keystore uses RSA 4096, validity 30 years, PKCS#12. Losing it means no further updates
under `com.loopjet.app`, so back it up twice and keep it away from the password.

---

## Tests

`app/src/test/java/com/loopjet/app/TransferLoopbackTest.kt` runs the real engine over TCP loopback:
mutual authentication, a 192 MiB file through four sealed streams, 2 000 bundled small files, Unicode
and nested paths, resume after a dropped link, pause/resume, and a declined offer. Green in run
`40648734612`; numbers in `TEST_REPORT.md`.

What is **not** tested, because no phone or emulator was available in this environment: Wi-Fi Direct,
hotspot, BLE, mDNS, the install flow, MediaStore publishing, notifications and the browser pages. The
code for all of it is present, guarded per API level and reviewed against the platform documentation;
`TEST_REPORT.md` lists each item so nobody has to guess.

---

## Repository layout

```
app/src/main/java/com/loopjet/app/
  core/        engine, wire protocol, crypto, file safety, HTTP server, HTML pages
  platform/    app wiring, foreground service, permissions, discovery, hotspots, apps, storage
  ui/          Compose theme, motion, screens, onboarding, view model
  MainActivity.kt
app/src/test/  engine tests (real sockets)
design/        original vector sources for the mark, monochrome icon and wordmark
tools/         sign_apk.py (APK Signature Scheme v2+v3 signer and verifier)
dist/          the signed release APK and the signing report
PLAN.md        research, verified platform facts, architecture, risk register, sources
TEST_REPORT.md measured results, defects found, and the honest not-tested list
```

Original assets only: the mark is a lightning bolt that closes into a loop between two nodes, in an
electric blue → violet → cyan gradient, drawn from `design/loopjet-mark.svg` into Compose and into the
adaptive icon (foreground, background, monochrome fallback). No resemblance to ShareIt, Xender,
Quick Share or LocalSend branding is intended or used.

---

## Known limits

* Radio behaviour (Wi-Fi Direct band negotiation, hotspot band, BLE discovery on OEM ROMs) is
  implemented but unverified on hardware.
* LocalSend v2 compatibility is deliberately not implemented; it is never on the core path.
* The local-only hotspot cannot be forced to 5 GHz by any public API — the UI reports the band that was
  actually granted and offers your own hotspot instead when that matters.
* Without all-files access, received media is copied once more to be published to MediaStore.
* A >4 GiB single file is supported by the protocol (64-bit offsets, chunk map, preallocation) but no
  5 GiB transfer was executed here; see `TEST_REPORT.md`.
* Invite re-shares the installed APK byte for byte; it never re-signs or modifies it.

Licence: MIT (source in this repository), assets included.
