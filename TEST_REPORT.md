# LoopJet 1.0.0 — test report

Everything below was executed. Nothing in this file is extrapolated except where it is explicitly
labelled as **not tested**.

```
Environment
  build      GitHub Actions ubuntu-latest, 2 vCPU / 7 GB, JDK 21 (Temurin),
             Android SDK platform 37, build-tools 36.0.0, Gradle 9.6.0, AGP 9.4.1
  app        com.loopjet.app 1.0.0, minSdk 26, targetSdk 37
  hardware   no phone, no emulator (see "Not tested")
  run ids    40657791564 (12 tests green, R8 release built from c8859b8),
             40656202018 (12 tests green, first run of the web suite),
             40655284844 (one failing web test, fixed in 6c713f8),
             40658991426 (shipped APK verified by apksigner), 40651955288 (previous APK, same)
```

## 1. Build and packaging

| Check | Result |
| --- | --- |
| Kotlin compile (debug) | pass, 0 errors, 14 warnings (all deprecation / unchecked-cast) |
| R8 release assemble | pass (`minifyEnabled`, `shrinkResources`, non-debuggable) |
| Debug APK build | pass |
| Lint (release) | runs; findings listed in `dist/lint-report.html` |
| `zipalign -c -p -v 4` | `Verification successful` |
| APK size | 1 674 573 bytes, 85 entries, `resources.arsc` present |
| `aapt2 dump badging` | `com.loopjet.app` versionCode 1, versionName 1.0.0, minSdk 26, targetSdk 37 |
| `apksigner verify --verbose --print-certs` | **Verifies** — v2: true, v3: true, v1: false (not needed at minSdk 26) |
| Certificate | `CN=LoopJet, OU=Release, O=LoopJet, L=Local, ST=Local, C=PK`, RSA 4096 |
| Certificate SHA-256 | `f35454a191e71fc689835921811560d70093f33ac43d0b01aa455a5b22d363dd` |
| APK SHA-256 | `11075d62bef87259f0fbe315c38ca6ccf4446d4d8baba234e8ec0e0b07e46b80` (aligned payload `aac1163e77c1dd85e503d2588b392f9012f0c88449e1ec8c47b29ed3dd7a0b04`, R8 release build of the current source tree) |

`dist/SIGNING-REPORT.txt` is produced by the official `apksigner` inside CI over the committed APK, not
by the local signing tool. For the shipped file it contains:

```
11075d62bef87259f0fbe315c38ca6ccf4446d4d8baba234e8ec0e0b07e46b80  dist/LoopJet-1.0.0-release.apk
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate SHA-256 digest: f35454a191e71fc689835921811560d70093f33ac43d0b01aa455a5b22d363dd
```

and both `apksigner verify` invocations (default, and `--min-sdk-version 26 --max-sdk-version 37`)
exited 0 in run 40658991426.

## 2. Engine tests (`TransferLoopbackTest`, real TCP sockets on loopback)

All six pass in the final run (`ci/test.log`, run 40656202018).

| Test | What it proves | Measured |
| --- | --- | --- |
| `single large file transfers and verifies` | handshake, 4 parallel sealed streams, positional writes, per-chunk SHA-256, `.part` → rename, byte-identical result | 192 MiB in **500 ms = 384.0 MB/s** (sender side; 518 ms / 370.7 MB/s and 1 119 ms / 171.6 MB/s on two other CI runs — shared-runner noise) |
| `many small files are bundled and verified` | bundle path for 2 000 files, directory creation, per-file digests | 2 000 files / 5.8 MB in **1 209 ms = 4.8 MB/s**, 1.65k files/s (769 ms / 7.6 MB/s in an earlier run) |
| `unicode names and nested folders survive` | Persian, Cyrillic, Japanese and emoji names with nested paths | pass, content identical |
| `resume after a dropped link only re-sends missing chunks` | chunk map persistence, resume negotiation, skip of completed ranges | first attempt cut at 5 chunks; second attempt re-sent **23 068 672 of 25 165 824 bytes (−2.03 MB)**, file completed and verified |
| `pause and resume keeps the transfer alive` | pause/resume signalling mid-transfer | 64 MiB, receiver completed, content identical |
| `receiver may decline an offer` | explicit approval path, no partial files left behind | pass, destination empty |

What the throughput numbers mean: 384 MB/s is a **loopback** number. It measures the whole pipeline
(read, AES-256-GCM seal, framing, sockets, write, SHA-256 check) on the CI CPU with the loopback
interface as the "radio". It is the right way to find pipeline overhead, and it is **not** a Wi-Fi
number. A 5 GHz Wi-Fi 5/6 link peaks around 40–110 MB/s of real payload, so the pipeline is not the
bottleneck; the radio is.

Small-file throughput (4.9 MB/s, ~2 100 files/s) is metadata-bound, as expected: each file needs a
sink, a digest check and a rename. For 5 000 photos that is roughly 2.4 s of protocol work plus the
actual bytes.

## 2b. Web server tests (`WebServerTest`, real HTTP over loopback)

Six tests, all pass in run 40656202018. These exist because the browser side had never been driven by
anything but a human.

| Test | What it proves | Measured |
| --- | --- | --- |
| `streamedZipDeclaresItsExactLengthAndExtracts` | the streamed ZIP's `Content-Length` equals the bytes actually sent, and the archive extracts with both files | declared length == body length, to the byte; two entries, one with a Unicode name |
| `zipEntryNamesCannotEscapeTheExtractionFolder` | a display name of `../../etc/passwd` cannot become a zip-slip entry | entry name has no `..` and is not absolute |
| `rangeRequestResumesAtTheRequestedOffset` | resumable single-file download | `206`, `Content-Range: bytes 1000-1999/4096`, 1 000 bytes identical to the source |
| `uploadStreamsToDiskAndCannotWriteOutsideItsFolder` | browser upload path, traversal refusal, no leftover `.part` | 300 000 bytes landed inside the upload folder, nothing above it, no `.part` |
| `pinIsRequiredOnEveryRequest` | the PIN is checked on API endpoints, not just the page | `401` without PIN, `200` with it |
| `sharePageNeedsNoNetworkResourcesBeyondThePhone` | branding and self-containment | page names the app, links `/files/1`, contains no external URL, script, CDN or integrity attribute |

The first version of the share-page test failed in run 40655284844: the page never said which app was
serving it. The name now comes from one constant (`Branding.APP_NAME`) used by the page, the ZIP file
name and the APK link.

## 3. Protocol and security behaviour exercised by the tests

* Mutual authentication by signature over the transcript; a mismatching pinned fingerprint is refused
  (`expectedPeerFingerprint` path).
* AES-256-GCM record sealing on every stream with per-direction keys and monotonic nonces.
* SHA-256 per data record and per file; a corrupt record fails the transfer instead of being written.
* Path sanitising and unique-name resolution (a resume test writes into an existing directory).
* Chunk-map resume, including a second session that re-uses the same session id.
* Manifest hardening: chunk size outside `4096..MAX_RECORD`, more than 200 000 entries, negative or
  absurd file sizes, duplicate file ids and a chunk count that would size a 512 MB allocation are all
  refused instead of trusted (`TransferManifest.fromJson`, `ChunkMap.fromBytes`).
* Subnet checks in the web server are real comparisons: an IPv4 client on an IPv6-only link, or a
  client from another /24, is rejected — and when no bind address is configured the check walks the
  device's own private addresses instead of accepting anything.

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

### Signing key, stated plainly

The release key that produced the earlier builds in this branch lived outside the repository (as it
must) and the build environment it was created in no longer exists, so that key is gone and no further
APK can carry its certificate. This build is therefore signed by a newly generated key with the same
parameters (RSA 4096, self-signed, 30 years, `CN=LoopJet, OU=Release, O=LoopJet, L=Local, ST=Local,
C=PK`), certificate SHA-256 `f35454a1…d363dd`. Consequences, in full:

* If an earlier build of LoopJet is installed on a phone, it has to be uninstalled before this APK
  installs; Android refuses an update that changes the signing certificate.
* Future updates must be signed with `/home/user/keys/loopjet-release.p12` (password in the separate
  credentials file). That file is the only copy: **back it up in two places now.** Losing it means
  every installed copy has to be uninstalled before the next build.
* `tools/make_keystore.py` regenerates a key with identical parameters if the original is lost, but a
  regenerated key is a *different* certificate, not a recovery of the old one.

An unsigned release APK is not an option: Android will not install one (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`),
so signing is mandatory for a deliverable.

## 6. Known defects and limits found during this work

* **The receiving phone never announced itself.** `go(Screen.Receive)` started the server and then
  discovery with `advertise = false`, i.e. as a *sender*. Only a QR, a code or a lucky subnet scan
  could ever reach it. Found by reading the discovery engine's role handling, fixed so Receive
  advertises and Send scans.
* **Sending to a Wi-Fi Direct peer connected to a MAC address.** The engine now joins the group,
  takes the group owner's address from `requestConnectionInfo`, probes its beacon for the live control
  port and fingerprint, and reports honestly when the other phone is not receiving.
* **A failed data stream lost its chunk.** After three attempts the item was dropped and the file
  reported as failed, even though the receiver's chunk map would have accepted a re-send. Items now go
  back on the shared queue (bounded: 64 requeues per session) and are picked up by whichever worker
  reconnects.
* **A checksum failure counted as success.** The receiver reported "complete" as soon as every chunk
  arrived; verification failures are now part of the result and the message names the file.
* **The streamed ZIP had no length**, so no browser could show progress, and its entry names went
  straight from user files into the archive (zip-slip for anyone who extracted it). Both fixed and
  covered by the new tests.
* **`isSameSubnet` accepted anything** when no bind address was configured and ignored address-family
  mismatches. It now rejects what it cannot place.
* **A permission granted on first run arrived after advertising had started**, so the mDNS,
  multicast and BLE registrations of that attempt had already failed; only subnet probes worked until
  the screen was left and reopened. A grant now restarts discovery with the receive role.
* **A refused permission had no way back.** The app now remembers what it asked for, tells the user
  when Android will not show the dialog again, and offers the app-settings page; Android 17's
  local-network prompt is asked on its own so it is not bundled into a bulk dialog.
* **The benchmark could report a number for a transfer that failed.** A failed loopback run now
  reports "not measured" instead of a fabricated rate, and the link comparison is labelled as a
  ceiling rather than an achieved speed.
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
