# LoopJet 1.0.0 — test report

Everything below was executed. Nothing in this file is extrapolated except where it is explicitly
labelled as **not tested**.

```
Environment
  build      GitHub Actions ubuntu-latest, 2 vCPU / 7 GB, JDK 21 (Temurin),
             Android SDK platform 37, build-tools 36.0.0, Gradle 9.6.0, AGP 9.4.1
  app        com.loopjet.app 1.0.0, minSdk 26, targetSdk 37
  hardware   no phone, no emulator (see "Not tested")
  run ids    40648734612 (tests green, APK built), 40646512706 + final run (signature verified)
```

## 1. Build and packaging

| Check | Result |
| --- | --- |
| Kotlin compile (debug) | pass, 0 errors |
| R8 release assemble | pass (`minifyEnabled`, `shrinkResources`, non-debuggable) |
| Debug APK build | pass |
| Lint (release) | runs; findings listed in `dist/lint-report.html` |
| `zipalign -c -p -v 4` | `Verification successful` |
| APK size | 1 641 815 bytes, 85 entries, `resources.arsc` present |
| `aapt2 dump badging` | `com.loopjet.app` versionCode 1, versionName 1.0.0, minSdk 26, targetSdk 37 |
| `apksigner verify --verbose --print-certs` | **Verifies** — v2: true, v3: true, v1: false (not needed at minSdk 26) |
| Certificate | `CN=LoopJet, OU=Release, O=LoopJet, L=Local, ST=Local, C=PK`, RSA 4096 |
| Certificate SHA-256 | `fea2a9d950971eb7fd76948d1f85cd06bbd0347a90722492e2d7968dff0c747d` |
| APK SHA-256 | printed in `dist/SIGNING-REPORT.txt` for the exact shipped file |

`dist/SIGNING-REPORT.txt` is produced by the official `apksigner` inside CI over the committed APK, not
by the local signing tool.

## 2. Engine tests (`TransferLoopbackTest`, real TCP sockets on loopback)

All six pass in the final run (`ci/test.log`, run 40648734612).

| Test | What it proves | Measured |
| --- | --- | --- |
| `single large file transfers and verifies` | handshake, 4 parallel sealed streams, positional writes, per-chunk SHA-256, `.part` → rename, byte-identical result | 192 MiB in **518 ms = 370.7 MB/s** (sender side) |
| `many small files are bundled and verified` | bundle path for 2 000 files, directory creation, per-file digests | 2 000 files / 5.8 MB in **1 184 ms = 4.9 MB/s**, 2.1k files/s |
| `unicode names and nested folders survive` | Persian, Cyrillic, Japanese and emoji names with nested paths | pass, content identical |
| `resume after a dropped link only re-sends missing chunks` | chunk map persistence, resume negotiation, skip of completed ranges | first attempt cut at 5 chunks; second attempt re-sent **23 068 672 of 25 165 824 bytes (−2.03 MB)**, file completed and verified |
| `pause and resume keeps the transfer alive` | pause/resume signalling mid-transfer | 64 MiB, receiver completed, content identical |
| `receiver may decline an offer` | explicit approval path, no partial files left behind | pass, destination empty |

What the throughput numbers mean: 370 MB/s is a **loopback** number. It measures the whole pipeline
(read, AES-256-GCM seal, framing, sockets, write, SHA-256 check) on the CI CPU with the loopback
interface as the "radio". It is the right way to find pipeline overhead, and it is **not** a Wi-Fi
number. A 5 GHz Wi-Fi 5/6 link peaks around 40–110 MB/s of real payload, so the pipeline is not the
bottleneck; the radio is.

Small-file throughput (4.9 MB/s, ~2 100 files/s) is metadata-bound, as expected: each file needs a
sink, a digest check and a rename. For 5 000 photos that is roughly 2.4 s of protocol work plus the
actual bytes.

## 3. Protocol and security behaviour exercised by the tests

* Mutual authentication by signature over the transcript; a mismatching pinned fingerprint is refused
  (`expectedPeerFingerprint` path).
* AES-256-GCM record sealing on every stream with per-direction keys and monotonic nonces.
* SHA-256 per data record and per file; a corrupt record fails the transfer instead of being written.
* Path sanitising and unique-name resolution (a resume test writes into an existing directory).
* Chunk-map resume, including a second session that re-uses the same session id.

## 4. Benchmarks available to a user (not run here)

`Settings → Benchmark` runs on the phone and prints: 128 MB storage write and read MB/s, AES-GCM
seal MB/s, engine MB/s through the real sender/receiver over loopback on that device, the Wi-Fi link
rate and band, and the achieved percentage of the reported link rate. It also states in the output
that loopback excludes airtime. No number from this screen exists yet for this build, because no
device was available.

## 5. Not tested (honest list)

These are implemented and compile against API 37, but **no phone or emulator was reachable**:

* Wi-Fi Direct: peer discovery, group creation, 5 GHz / 6 GHz band request, group-owner address,
  `removeGroup()` teardown, group recovery after a crash.
* Local-only hotspot creation, band read-back, `WifiNetworkSpecifier` join sheet, Wi-Fi QR join.
* BLE advertising and scanning; mDNS/NsdManager and UDP multicast; subnet scan.
* `PackageInstaller` split installs, unknown-sources flow, signature-conflict and downgrade errors.
* MediaStore visibility with `IS_PENDING`, mtime preservation, gallery refresh.
* Foreground service lifecycle on a real device, the Android 15 `onTimeout` path, wake/Wi-Fi locks,
  notification actions, screen-off behaviour.
* Web Share and Invite pages in real browsers (the HTTP server itself is unit-testable and the routes
  are implemented; no browser was driven against it here).
* Real-radio throughput of any kind. Every MB/s number in this report is CPU/disk/loopback.

Consequences to keep in mind: the speed claims that matter (5 GHz link rate, 10 GB video, 5 000 small
files over the air) can only be confirmed on hardware. The design is aimed at them, the pipeline is
measured, and the in-app benchmark exists so the missing numbers can be produced in one minute on a
real pair of phones.

## 6. Known defects and limits found during this work

* **Resume did not skip anything in the first implementation.** The receiver only persisted chunk maps
  for *completed* files, so a dropped link restarted from zero. Found by an assertion added
  deliberately for it, fixed by persisting incomplete maps (and every 2 s during transfer), and the
  test now fails if the second attempt re-sends the whole file.
* **The final file could be reported complete before the rename finished.** A data thread verifies and
  renames the last file; the session used to return as soon as the chunk counter hit zero. Fixed with
  an in-flight-commit counter and a bounded wait, verified by the large-file test.
* **Bundle records could exceed the record cap** (256 KiB payload + 49-byte header), so the receiver
  rejected them and the sender retried forever. Fixed by sizing the record cap above the chunk payload
  and by accounting for the per-entry header when packing bundles.
* **The six-digit confirmation code differed between phones** because the fingerprint order was not
  canonical. Fixed by ordering the fingerprints before hashing; both sides now derive the same code.
* **`WifiP2pGroup.getFrequency()` (API 29) and `getNetworkName()/getPassphrase()` (API 30) were called
  unguarded**, which would have crashed on Android 8–10. Guarded with explicit version checks.
* Without the optional all-files permission, received files are written into app storage and then
  published into MediaStore (one extra copy). Granting all-files or picking a SAF folder avoids it.
* LocalSend v2 compatibility is not implemented.
* The local-only hotspot cannot be forced to 5 GHz; the UI reports the band that was actually granted.
* The release keystore cannot be handed to the CI job in this environment (no workflow dispatch and no
  repository secrets for the integration), so the APK is zipaligned in CI, signed locally with
  `tools/sign_apk.py`, and then verified by CI's `apksigner`. The verification output is committed.
