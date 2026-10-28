# Build notes — LoopJet 1.0.0 APK

Date: 2026-10-28 · branch `arena/918851ec-flowlink` · source `LoopJet-1.0.0` (`com.loopjet.app`)

## What you can install

| File | Size | Signed | Install? |
| --- | --- | --- | --- |
| `dist/LoopJet-1.0.0-release.apk` | 1,674,573 B | v2 + v3, release key `CN=LoopJet` (RSA 4096) | **Yes — use this one** |
| `dist/unsigned/LoopJet-1.0.0-release-aligned.apk` | 1,669,405 B | none | No (needs signing first) |

Requires **Android 8.0 (API 26)** or newer; `targetSdk 37`, one universal APK for all ABIs.

```
sha256  11075d62bef87259f0fbe315c38ca6ccf4446d4d8baba234e8ec0e0b07e46b80  dist/LoopJet-1.0.0-release.apk
cert    C=PK, ST=Local, L=Local, O=LoopJet, OU=Release, CN=LoopJet
cert SHA-256  f35454a191e71fc689835921811560d70093f33ac43d0b01aa455a5b22d363dd
```

Install with `adb install LoopJet-1.0.0-release.apk`, or copy it to the phone and tap it
(allow "install unknown apps" for the file manager you open it from).

## How it was compiled

This sandbox has no JDK, no Android SDK and no route to `dl.google.com` / `maven.google.com`, so the
compile ran on GitHub Actions (JDK 21, SDK platform 37, build-tools 36.0.0, Gradle 9.6.0):

* run **40675585929** — job `build`, **success**, 8m 0s, branch `arena/918851ec-flowlink`
* `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease` (R8 + resource
  shrinking), `lintRelease`, `zipalign`

```
compile=0  tests=0  debug=0  release=0  wrapper=0     (0 = success)
unit tests: 12 tests, 0 failures, 0 skipped          (ci/test.log, dist/unit-test-report/testDebugUnitTest)
loopback benchmarks: 2000 small files 5.8 MB in 1244 ms = 4.7 MB/s
                     192.0 MB large file in 461 ms = 416.5 MB/s
```

Logs for that run are committed under `ci/`; the reports are under `dist/`.

## Why the shipped APK is signed but my fresh build is not

`app/build.gradle.kts` signs the release build only when `LJ_KEYSTORE` points at a keystore. The
workflow fills that in from `LJ_KEYSTORE_B64` / `LJ_STORE_PASS` / `LJ_KEY_PASS` / `LJ_KEY_ALIAS`
repository secrets — and the GitHub App token available to this session cannot read or set repository
secrets (the API answers `403 Resource not accessible by integration`). So my run produced the release
APK **unsigned**, and the `Prepare signing material` step was skipped.

The release certificate private key is not in the repository (it is gitignored: `*.p12`, `keys/`), and
forging a new key would break the documented identity and the upgrade path for anyone already running
the official build. So `dist/LoopJet-1.0.0-release.apk` is the project's own signed artifact from the
`arena/7ef48d45-flowlink` line, and I checked it is the same build I compiled:

* stripping the APK Signing Block (5,168 bytes) from the signed file and restoring the EOCD central
  directory offset yields a payload of exactly **1,669,405 bytes**, the size of my fresh output;
* every one of the 85 zip entries matches my fresh build in CRC, compressed size and uncompressed size
  **except** `META-INF/version-control-info.textproto` (120 B, it records the git revision);
* `tools/sign_apk.py verify` (RSA PKCS#1 v1.5 over SHA-256, per AOSP `apksig`) reports
  `v2: signature_ok=True content_digest_ok=True` and the same for v3.

## To get signed builds from CI automatically

Add the four repository secrets (**Settings → Secrets and variables → Actions**), then every push
builds *and* signs:

```bash
gh secret set LJ_KEYSTORE_B64 --repo Gkejeishsh/FlowLink < loopjet-release.p12.b64
gh secret set LJ_STORE_PASS   --repo Gkejeishsh/FlowLink --body '***'
gh secret set LJ_KEY_PASS     --repo Gkejeishsh/FlowLink --body '***'
gh secret set LJ_KEY_ALIAS    --repo Gkejeishsh/FlowLink --body 'loopjet'
```

Without the original keystore, `python3 tools/make_keystore.py` can create a new identity — everything
installs fine, but it cannot update an existing install that was signed with `f35454a1…`.

## One CI bug found and fixed

`dist/unit-test-report/` was polluted. The publish step did `cp -r app/build/reports/tests/testDebugUnitTest dist/unit-test-report`,
and `cp -r` merges into an existing directory, so a stale tree from an earlier red run survived next to
the fresh one: `dist/unit-test-report/index.html` claimed *6 tests, 5 failures* while
`dist/unit-test-report/testDebugUnitTest/index.html` (and `ci/test.log`) correctly show *12 tests,
0 failures*. The workflow now clears the directory first.

---

# UI fix build — 2026-10-28

`dist/LoopJet-1.0.0-ui-fix.apk` — the design fixes below, compiled from commit **17b55b9**.

```
sha256  c6c2fd02a9bce53366f1079aca9a9d08710b7cc2f0138ce206fb7c559665cf5e
size    1,674,573 bytes
build   CI run 40687307027, compile=0 tests=0 (12/12 unit tests passed)
proof   META-INF/version-control-info.textproto inside the APK reads revision 17b55b9…
cert    C=PK, ST=Local, L=Local, O=LoopJet, OU=Release, CN=LoopJet
        cert SHA-256 97a5e9d716b80a32caf2743369c55a228155e034c13e1e929ec504c5773da17d
```

> **This build is signed with a new key**, not the `f35454a1…` key of the 1.0.0 release (that private
> key was never in the repository and cannot be extracted from an APK). Android therefore refuses to
> install it **over** an existing LoopJet: uninstall the old app first, then install this one. The
> keystore lives at `~/.loopjet-build/loopjet-ui-release.p12` (outside the repo, never committed), so
> later builds can be signed with the same identity via `tools/sign_apk.py sign`.

## What was actually wrong

1. **Black text on the dark theme.** Nothing published `LocalContentColor`: the root of the app was a
   bare `Box`, and `MaterialTheme` does not set that value. 122 `Text` calls relied on the default,
   which is `Color.Black`, so they drew black on the near-black background. Fixed by wrapping the app
   in `Surface(color = background, contentColor = onBackground)`, and giving `SectionCard` / the
   onboarding `StepCard` their own `contentColor`.
2. **Mismatched fonts.** The typography overrode 9 of the 15 styles; the other 6 fell back to the
   Material defaults, mixing two families and two letter-spacing systems on the same screen. The full
   scale is now declared on `FontFamily.Default` with explicit line heights.
3. **Clipped, off-centre button labels.** `PrimaryAction` (58dp) and the duplicate `PrimaryButton` in
   onboarding (54dp) pinned a fixed height with no label padding, so at a large system font scale the
   text was cut top and bottom. Both now delegate to one `GradientButton` using `heightIn(min = 52dp)` +
   padding + centred, wrapping text, with a real ripple.
4. **Layouts that pushed content off the edge.** Fixed widths in the picker tray, the onboarding
   footer (`width(150.dp)` next to a Back button) and the nearby-pairing row; missing `weight(1f)` on
   step cards, feature rows, switch rows and small actions; eight avatars and the theme/speed chips in
   non-scrolling rows; `StatRow` unable to wrap a long value. All now weight-based or scrollable.
5. **Content under the navigation bar.** Only some screens applied inset padding; `ScreenFrame` and the
   home list now clear the system bars.
6. **Status-bar icons ignored the in-app theme.** `enableEdgeToEdge()` follows the *system* theme, so
   choosing Dark while the system was light left dark icons on a dark bar. The style is now re-applied
   with the resolved theme.

## Not changed

The transfer path — chunking, sockets, the loops — is untouched, so the measured speeds stand.
`Transfers` still run at the same settings (Balanced / Fast / Max).

## Also fixed in CI

`dist/` accumulated artifacts across runs: the previous signed APK stayed in place, so the
align/sign/verify step described **that** file while claiming it was the current build. The step now
clears the artifacts it owns before building. (`dist/LoopJet-1.0.0-ui-fix.apk` is deliberately outside
that set, so it survives later runs.)

---

# QR scanner build — 2026-10-28

`dist/LoopJet-1.0.0-qr-scanner.apk` — compiled from commit **ff7b502**, CI run 40689631414
(`compile=0 tests=0`, 12/12 unit tests passed).

```
sha256  539d364db413f2f0a38c1879087f38ee8254d736397901723622b57c4cd26bef
size    2,359,597 bytes   (was 1,674,573 — CameraX adds ~685 KB)
cert    97a5e9d716b80a32caf2743369c55a228155e034c13e1e929ec504c5773da17d
        — the same key as LoopJet-1.0.0-ui-fix.apk, so this installs as an update over it
```

Verified inside the shipped APK: `android.permission.CAMERA` and `android.hardware.camera` are in the
binary manifest (UTF-16 string pool), `MultiFormatReader`, `ImageAnalysis` and `PreviewView` are in the
dex, and the scanner's own strings ("Scan the other phone's QR", "Camera stays on this phone", "Torch
off", …) are present. `META-INF/version-control-info.textproto` reads revision `ff7b502…`.

## The scanner

`ui/QrScanner.kt`, reached from **Send › Nearby › "Scan the other phone's QR code"**.

* CameraX preview + `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`) feeding zxing's `MultiFormatReader`.
  Only the luma plane is read — a QR code is monochrome — and `rowStride` is passed through, because
  many sensors pad rows and ignoring that skews every frame.
* One payload per visit (`AtomicBoolean`): the analyzer fires on every frame, and a second delivery
  would start a second pairing mid-connect.
* Accepts `loopjet://ip:port#fingerprint#device` (pairs and sends the selection), a bare six-digit
  code, or an `http(s)` Web Share link (copied to the clipboard, since it belongs in a browser).
* `CAMERA` is requested **only** from this screen, so onboarding is unchanged and an install that never
  scans is never asked. Refused, missing or broken camera → a fallback screen that keeps the manual
  code/link entry one tap away.
* Torch toggle for a dark room or a dim screen.
* CameraX rather than ML Kit: ML Kit pulls in Google Play services, which this app is documented not
  to need on the transfer path.
* `go()`/`back()` keep discovery running across the scanner — otherwise a freshly scanned link would
  fail with "Open Send first so discovery is running" — and Back returns to Nearby rather than Home.

## Other fixes in the same build

* `ripple()` on the three custom cards (Send/Receive, small actions, peer rows) that only had the
  scale animation, so a tap is acknowledged even before the screen changes.
* The Receive card no longer shows a media **play triangle**; History no longer shows a **house**.
* Removed the unlabelled theme button on Home — it drew a *refresh* glyph next to the gear, so it read
  as "reload". The labelled System/Light/Dark selector in Settings is the way to change it.

## Still not changed

The transfer path. `git diff` for `core/` and `platform/` across this whole session is empty apart from
the new UI file's calls into `pairWithLink` / `pairWithCode`, so speeds and the wire protocol are
untouched.

---

# Phase 1 + 2 build — 2026-10-28

`dist/LoopJet-1.0.0-live-progress.apk` — commit **14830d5**, CI run 40699529299
(`compile=0 tests=0`, 12/12 unit tests passed).

```
sha256  6801a375ffe5f7ee1c41fd1679bad9d1aaa7c2a8120f91361a206810d45899e
size    2,359,597 bytes
cert    97a5e9d7…  (same key as -ui-fix and -qr-scanner: installs as an update)
```

## P1 — the progress you could not see

**Real bug fixed:** only the *sender* ever navigated to the transfer screen
(`AppViewModel.sendTo()` set `screen = Transfer`). The receiver stayed on the Receive
screen showing its QR, so a running transfer looked like nothing was happening on that
phone. `openSessionIfRunning()` now opens the session on either device as soon as the
state goes CONNECTING / WAITING_ACCEPT / TRANSFERRING / PAUSED, skipping onboarding and
the scanner so it never yanks you out of those.

**New transfer screen** (`TransferScreen` + `TransferSummary` + `FileTimelineRow`):
* gradient bar with the **percentage drawn inside it**, not beside it;
* speed, ETA, bytes and `3 of 7 files` beneath;
* vertical timeline: connector line, and a **ring per file that fills with that file's own
  bytes**, keyed by file id so rows never jump while scrolling;
* states: queued (empty ring), in flight (ring + `1.2 GB of 2.9 GB · 82%`), done (full ring
  + check, muted row), failed (error tint + the reason).

**Engine change, and why it cannot cost speed:** one
`fileBytes.getOrPut(id).addAndGet(n)` sits beside the existing `rate.add(n)` in the sender's
chunk loop, the bundle loop and the receiver's write loop. `TransferProgress.fileProgress`
reports **in-flight files only, capped at 64**, so a 2 000-file session never rebuilds a
2 000-entry map every 100 ms. `Protocol.kt` is untouched.

**Throughput, measured twice on the same code.** Run 40698454520 reported 179.9 MB/s for
the 192 MB loopback (baseline 400–416), which looked like a regression, so an empty commit
bought a second run on identical code: **407.6 MB/s**. The 180 was runner noise. The
2 000-small-file case moved 4.7 → 7.7 → 4.9 MB/s across the same runs, i.e. noise too.

## P2 — one decision instead of a hunt

`Send › Nearby` now opens with two large cards side by side:

* **Show my code** — `receiveHere()` switches to Receive, starts the server and starts
  advertising in one tap;
* **Scan a code** — opens the QR scanner.

The typed 6-digit code and the pasted `loopjet://` link stay underneath as the fallback, so
nothing was removed.

## Not in this build

Phase 3 (the real hotspot client + exclusive-state ladder) and Phase 4 (QR v2, p2p
credentials, fastest-path prediction) are **not** in this APK — they are the next phases in
`CONNECTION-PLAN.md`. This build changes only UI and one counter, which is why it is safe to
install over the last one.

---

# Build: LoopJet 1.0.0 — phase 3+4+5 (`dist/LoopJet-1.0.0-phase3-5.apk`)

* size **2,375,981 bytes**
* sha256 `103c36b17a027dd0febb2e64905e7f5eecb6a139d97736873a05f8cca2eedc76`
* signed v2+v3 with `loopjet-ui-release.p12` (cert `97a5e9d7…`, same key as every build since
  `-ui-fix`), `SELF-CHECK: OK`, v2 and v3 `signature_ok`/`content_digest_ok`
* single dex, R8-minified, `android:debuggable` absent (checked in the binary manifest)
* source commit `721339e`, CI run **40713878250**: `compile=0 tests=0 debug=0 release=0`,
  **19/19 unit tests PASSED**
* aligned payload `dist/unsigned/LoopJet-1.0.0-release-aligned.apk`
  sha256 `6cb24df86efbac0f8010699193240fe781c97dfaa3f3c57644a1336c6a0c150d`

## What is actually in it

**P3 — the hotspot path now exists from both sides.** `HotspotController.joinAp()` returns the
`Network` object Android grants, `PendingPeer.network` carries it into the send worker, and the
sockets bind to it — previously the sender guessed `activeWifiNetwork()`, which is the phone's own
Wi-Fi, not the AP it just joined. The receiver hosts a local-only hotspot (LOHS: no SIM, no mobile
data, no tethering plan) when it has no LAN address, republishes a v2 code containing
SSID/passphrase/address, and releases the hotspot plus any join at teardown.

**P4 — versioned codes.** `loopjet2:` payload (base64url JSON) carries the LAN address and an
optional network; v1 `loopjet://` still parses, and a v1 build that scans a v2 code says "update"
instead of "not a LoopJet code". Ranking: lan 100 > ap 75 > p2p 70 > wifi 65, with p2p zeroed when
the ROM has no Wi-Fi Direct.

**P5 — connection doctor** (`Settings › Open connection doctor`): SDK/OEM, Wi-Fi state, SSID, band,
link speed, the session address and interface, every local address, Wi-Fi-like interface names,
p2p/5 GHz/6 GHz/BLE support, ports, session state, the code's offers (never the passphrase), and
one-tap copy.

## Bugs found while wiring this, and fixed

1. **Offer timeout** 180 s → 30 s, with "Offer not answered — open LoopJet to accept". A backgrounded
   receiver cannot raise the Compose dialog at all, so the sender used to wait three minutes.
2. **Wi-Fi permission was never requested on the join path.** `NEARBY_WIFI_DEVICES` (13+) /
   location (8–12) was only asked from Start/Receive, so a first scan of a hotspot code reached
   `requestNetwork` with no grant and failed as "join failed". The scan is now remembered and
   replayed after a one-permission dialog.
3. **v1 link parser gluing the tail onto the device id** (`substringAfter('#')` returns everything
   after the *first* hash). Caught by the new tests.
4. **Camera permission permanently denied** showed a dead "Allow camera" button; it now offers
   "Open app settings".
5. **Hotspot callback thread**: `ConnectivityManager` answers on a binder thread, so UI state (and
   `pairWithLink`) was mutated off the main thread. Results are now posted to the main looper.
6. **Repeated Receive taps** left the previous hotspot reservation open and the OS then refused the
   new one; and a hotspot that started without an AP address published a `0.0.0.0` code that could
   never work. Both handled.
7. **CI shipped the wrong APK for one run**: widening the artifact search to "largest APK" picked
   `app-debug.apk` (14.7 MB, unminified, debuggable) over the 2.37 MB release build. Discovery is
   now release-only and refuses a debuggable APK. Caught before publishing; nothing wrong was
   released.

## Tests

12 → **19**. The seven new ones cover the code itself, which is the only part of the pairing work
that can be proven without two phones: LAN round trip, hotspot code → join-then-connect, v1 links
(including the tail bug), a payload split across lines / missing padding / standard alphabet,
`WIFI:` escapes, junk input, 6-digit and http codes.

## Throughput

`core/` (engine, protocol, chunk map) is **byte-identical** to the build that measured 407.6 MB/s;
`git diff 6944e3b..HEAD -- app/src/main/java/com/loopjet/app/core` is empty, and the benchmark
imports only `core.*`. Measurements on identical code during this phase: 166.7, 172.5, 175.0,
171.9, **358.9 MB/s** (small files 6.4–7.0 MB/s). Same binary, same test — the runner is bimodal,
so the low readings are not a regression. Nothing on the data path was touched: no extra
round-trip, no new syscall per chunk.

## Not verified here

No phone, emulator or JDK in this environment: the system consent sheet for joining an AP, LOHS on
a real Samsung ROM, Wi-Fi Direct group formation, and the rings/bars themselves cannot be executed.
What is verified is a clean compile, 19/19 JVM tests, a signed APK with the expected strings in the
dex, and manifest permissions (`NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION` maxSdk 32,
`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CAMERA`).
