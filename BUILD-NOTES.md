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

---

# Build: LoopJet 1.0.0 — connection fix (`dist/LoopJet-1.0.0-connect-fix.apk`)

* size **2,373,939 bytes**
* sha256 `360a39a60cb8e24c643a780014df23018244a4b662093e80b382df2045fa62b2`
* signed v2+v3, `SELF-CHECK: OK`, single dex, not debuggable
* source commit `ee22ca8`, CI run **40741852868**: compile/debug/release all 0, **24/24 tests**
* aligned payload sha256 `70304d15…`

## ⚠ This APK is signed with a NEW key

The old signing keystore lived outside the persisted workspace and was destroyed when the
sandbox restarted, and repo secrets are not accessible from here (and CI has no signing
secret, which is why its builds are unsigned). A fresh RSA-2048 key was generated and now
lives at `keys/` inside the workspace, git-ignored, so it survives from here on.

**Consequence: uninstall the previous LoopJet once before installing this one** (Android
refuses an update signed by a different key). Settings and history from the old install are
not carried over. Every build from this one onwards keeps this signature.

## The bug that made every scan fail

`Receive` was started **twice** per entry: `receiveHere()` called `go(Screen.Receive)` — which
already starts the service — and then called `startReceive()` again. The second
`ACTION_RECEIVE` built a second receiver, could not have port 43130 because the first one held
it, bound **43132**, published a QR pointing at 43132, and then reached `startWorker()` with
`running == true`, which called `stopSelfSafely()` and tore down *both* engines and the
service.

Net effect: the screen advertised `loopjet://<ip>:43132` with nothing listening, the sender
dialled it and got `failed to connect`, and the receiving phone sat on the QR page forever
because its own session was already dead. Transport-independent, which is why Wi-Fi and
hotspot both failed.

## Fixes

1. `ACTION_RECEIVE` is idempotent — an existing receive session is reused, never rebound or
   republished; a send in flight is reported instead of killed.
2. `startWorker()` no longer stops the service when a session is already running.
3. `receiveHere()` no longer double-starts.
4. Tapping Send while receiving closes only the passive receiver (`stopReceivingOnly`), so a
   phone can swap roles.
5. The receiver now publishes `CONNECTING` after the handshake and `WAITING_ACCEPT` before the
   offer, so **the receiving phone moves to the transfer screen by itself**. Before, its first
   state change was `TRANSFERRING`, so the screen only appeared once bytes were moving — the
   "section only opens on the phone that scans" complaint.
6. The Receive screen says "receiver stopped — this code will not connect" with a Restart
   button instead of showing a code that cannot work.
7. `ConnectDiagnosis` replaces the raw socket text: different subnets, peer alive but its port
   closed, or peer silent — each with the phone to fix. Runs only after a failure.
8. A bound socket that cannot connect retries on the default route (covers a stale specifier
   network left by an earlier hotspot join).
9. The advertised address is re-checked every 3 s while idle, so opening Receive *before*
   joining Wi-Fi no longer leaves a dead code on screen.
10. `sessionBinding()` prefers `wlan*` over `ap*/swlan*` and `p2p*`, so a leftover Wi-Fi Direct
    address (192.168.49.1) can no longer be advertised to a same-Wi-Fi peer.
11. `ReceiveCodes.lanLink` is the single place the code is built and refuses `0.0.0.0` — the
    new test caught that regression in review.

## Tests

19 → **24**. New: the published code must point at the port the receiver is actually
listening on; a sender dialling the *parsed* link must transfer a file end to end; the
diagnosis messages; empty/portless links are never published.

## Throughput

`core/`'s data path was not touched: the only change in `Engine.kt` is two UI listener
callbacks. Loopback in this run: 370.7 MB/s large file, 6.9 MB/s for 2 000 small files —
inside the runner's usual spread (166–407 MB/s on identical code).

---

# Build: LoopJet 1.0.0 — offer fix (`dist/LoopJet-1.0.0-offer-fix.apk`)

* size **2,373,939 bytes**
* sha256 `034425d787022974cfbae1da64d41202f818e674aef7a6b462ca5a3c1dbba639`
* signed v2+v3, `SELF-CHECK: OK`, one dex, not debuggable
* source commit `06572d1`, CI run **40819888920**: compile/debug/release 0, **25/25 tests**
* aligned payload sha256 `ddcad964…`
* ⚠ **different signing key again** — the workspace that held the previous key was reset by the
  sandbox. Uninstall the previous LoopJet before installing this one.

## What the screenshots proved, and what was wrong

The handshake works now: both phones open the transfer screen. What failed is the **offer**.

1. **"Declined" with nothing tapped** (same-Wi-Fi screenshot). `OfferDialog` answered
   `(false, false)` from `onDismissRequest`, so any outside tap or back press declined the
   transfer with a blank reason — and leaving the Receive/Transfer screen called
   `TransferService.stop`, which answered "no" on the user's behalf. Fixed: the dialog cannot be
   dismissed by accident, navigating away keeps a pending offer alive, and a decline now carries
   a reason the sender can read ("Declined on the receiving phone — tap Show my code there to
   retry" / "The other phone stopped receiving").
2. **The hotspot path could never connect** — `sendTo()` cleared `PendingPeer.network`
   unconditionally and is called from `pairWithLink`'s callback, i.e. right after a successful
   join. The joined network was thrown away and the sockets left through the phone's own Wi-Fi.
   `sendTo(peer, joinedNetwork)` and `pairWithLink(link, joinedNetwork)` now thread it through.
3. **Different-subnet message** stays, because it is true: 192.168.10.x cannot reach a phone on
   another network. The fix there is a hotspot join, which now works (2).
4. UI from the same screenshots: "Remaining" broke into "Remainin"/"g" (StatRow 1:2 with
   `fill = false`); the receiving screen said "Phone at 192.168.10.8" and "Code 000 000" (the
   receiver never adopted the sender's name or the pairing code). Both fixed via
   `TransferListener.onPeer` plus `peerName`/`sas` on the offer.
5. The code caption now says which path is faster: "Same Wi-Fi · <ip> (fastest)" and
   "hotspot (slower than shared Wi-Fi)".

## Answer to "which is faster"

**Same Wi-Fi.** Both phones are then on the access point they already belong to, so traffic takes
one hop through the router — effectively the Wi-Fi link speed. With LoopJet's own hotspot one
phone becomes the access point: the other phone's traffic is received, re-processed and written
by the host phone, and the host is simultaneously AP and server, so expect roughly half the
throughput and more latency. Wi-Fi Direct (5 GHz when available) sits between the two. The app
never silently downgrades: it uses the LAN path whenever the two phones share a subnet, and only
falls back to hosting a hotspot when the receiver has no LAN address at all.

---

# Build: LoopJet 1.0.0 — cross-network path (`dist/LoopJet-1.0.0-hotspot-path.apk`)

* size **2,373,939 bytes**
* sha256 `441c4d214438e52be5609364a78206fcb0368e86c0a35ef9da28cf7f94cb376b`
* signed v2+v3, `SELF-CHECK: OK`, one dex, not debuggable
* source commit `b6b7eb8`, CI run **40824070611**: compile/debug/release 0, **31/31 tests**
* aligned payload sha256 `5424cf76…`
* same signing key as `-offer-fix` (both were generated in this workspace), so it installs over it

## Why the hotspot "still showed the same red line"

The fallback hotspot was only started when the receiving phone had **no address at all**. Two
phones on two different networks each have a perfectly good address, so the receiver published a
LAN-only code, the sender dialled an address it could never reach, and nothing else was offered.
Both screenshots are that exact case, or the offer being cancelled in the same window.

Now: the receiver starts its hotspot **while the code is on screen** and publishes one v2 code
carrying **both** paths. The sender decides with `ConnectPlanner` — same IPv4 subnet ⇒ LAN
(fastest, no consent sheet); different network ⇒ join the advertised hotspot (one Android
consent tap, unavoidable) and send over it. No probing, no extra traffic on the data path.

## Other fixes in this build

1. **App closed when tapping Receive.** `startReceiver()` runs on the main thread and called
   `startLocalOnlyHotspot` (SecurityException without the Wi-Fi permission), the Wi-Fi Direct
   stack and the bind, all unguarded. LOHS is permission-checked, every platform call is guarded,
   and a failure is now a message on screen.
2. **Sender said "Receiving from …".** The direction came from `SessionUi.mode`, which the
   receive side also writes (code publishing, the 3 s address watcher). `SessionUi.sending` is now
   the only source and the receive path may not touch a session that is sending.
3. **`.part` files next to the photos.** Incomplete files are staged in a hidden
   `.loopjet-partial` folder inside the destination and renamed to their real name only when
   whole and verified; the folder disappears when empty. Resume works and keeps the original
   name rather than creating `name (1)` copies.
4. **No typed 6-digit code, no pasted link** on the Send screen — one card explains, the two
   actions are "Show my code" and "Scan a code".
5. The receive code's caption no longer claims "no shared Wi-Fi" when the hotspot simply failed.

## Tests

25 → **31**: subnet routing, a code carrying LAN + hotspot, hotspot-only codes, no visible
`.part` file, a resumed file keeps its name. Loopback in this run: **381 MB/s** large file.

---

# Round 4 — the picker rebuilt around one tray

Everything below was reproduced from the source first; nothing is a guess about a platform
behaviour I could not check.

## File choosing (the "way too complicated" report)

1. **Apps had no tick.** Tapping an app called `SendQueue.add` directly, so it was queued the
   instant you touched it, and there was no way to untick it. Apps now use the *same* tick
   circle as files (`SelectionTick`), the tray counts them, and nothing is queued until Send.
2. **One tray, every source.** Apps, files from any category, files from a browsed folder and
   picks from the gallery all live in one selection and go out in one Send
   (`toggleApp` / `commitSelection` / `addPickedUris`).
3. **Folders category was the system picker.** It launched `OpenDocumentTree` and asked the user
   to nominate exactly one folder before showing anything. It now browses the phone's storage
   in-app: a breadcrumb with Up, folders first, then files — down to any depth. When the phone
   has not granted all-files access it shows one card with one button instead of failing.
4. **Gallery button.** A "Gallery" chip sits next to the search field, above the categories. It
   opens the system photo picker (`PickMultipleVisualMedia`, no permission required), and the
   picks join the tray like everything else.
5. **Search never filtered.** The bug was ordering, not the pattern: the cursor was limited to
   the first rows *before* the in-memory name filter ran, so any match outside that window was
   invisible — i.e. almost every search. The `LIKE` is now part of the MediaStore query, and the
   folder listing applies the same filter.
6. **First run no longer asks for a folder.** Sending and receiving need none. The only optional
   extra is all-files access for browsing, and it is labelled optional.

## Bugs found while verifying the round-3 fixes

- **Speed mode did nothing until something else repainted.** The chips read a SharedPreferences
  value (invisible to Compose) and `setSpeedMode()` copied state that compared equal, so Compose
  saw no change. `speedMode` is part of `AppState` now; a tap repaints immediately.
- **Text broken mid-word.** The transfer status was one word in a `weight(1f)` slot without
  `maxLines`, so a wide "12m 34s left" split it into "Connecte" + "d". Single line, no mid-word
  wrap, ETA yields first.
- **`startForeground` could crash the app on Android 14+** when the service type was refused.
  Guarded: the session continues without a notification rather than the process dying.
- **The picker stayed on "Allow all files" after granting it** (nothing refreshed on return).
  `onResume` re-opens the folder root when the permission has actually appeared.

## Font

The platform default face was in use, which every OEM substitutes — the same screen looked
different on every phone. **Public Sans** (SIL OFL 1.1), variable `wght` axis, 103 KB, is bundled
and used by every type style, with tighter heading tracking and line heights.

## Speed

No code path that was already fast got slower. The opt-in **Max** mode goes from 6 to 8 parallel
streams; Balanced (2) and Fast (4) are untouched, and the wire format is unchanged, so a new
phone and an old one still interoperate. Loopback bench this round: **402.5 MB/s** (381 MB/s last
round), 2000 small files 5.6 MB/s.

## Tests

31/31, unchanged — the round-4 changes are UI and I/O-scheduling only; `core/` is untouched
(`git diff --stat core/` is empty), which is the only reliable regression signal available
without a device.

## Audit pass (after the round-4 build)

Checked every round-4 claim against the source and against the shipped APK; three real gaps
turned up and are fixed here:

1. **A gallery pick could be counted but never sent.** The engine skips zero-length files
   (`buildWork`: `if (file.size <= 0) continue`). For a gallery entry that reports no
   `OpenableColumns.SIZE` (cloud-backed photos often do not), `resolvePicked` could leave the
   size at 0 — so the item was ticked, counted in "n selected", and silently absent on the other
   phone. Size is now also read from the descriptor (`AssetFileDescriptor.length`, then
   `statSize`), and a pick whose length genuinely cannot be determined is refused up front with
   "n added · m could not be read" instead of being queued.
2. **The notice could overstate the count** when an app in the tray was uninstalled while the
   picker was open. It now counts only apps that are actually in the queue.
3. **`Connected browsers` did not fit its 40% label column** and was being ellipsised; the row
   reads "Browsers" in the Web Share panel.

Verified, not assumed: `MANAGE_EXTERNAL_STORAGE` is declared in the manifest (the in-app file
tree is dead without it); every one of the 15 Material text styles names the bundled family, so
no screen falls back to the OEM face; `core/` and `platform/net/` are byte-identical to the
round-3 commit, which is the only trustworthy "no slower" signal available without a device;
and the release APK carries the same signing certificate as the previous build, so it installs
as an update.

---

# Final build

`dist/LoopJet-1.0.0.apk` — 2,442,687 bytes, sha256 `f0f827c0aecc852266e48b0c2922341459fd85625716b8b995d702092b44d07d`

Built from commit 428172e by CI (compile 0 errors, 31/31 tests, release assembly OK), signed
from that run's own `dist/unsigned` payload after checking it against the `SHA256.txt` the
workflow published. One dex, no `debuggable` attribute, v2 + v3 signatures valid,
certificate `C=PK, O=LoopJet, OU=Release, CN=LoopJet` — the same key as the previous build, so
it installs as an update. Permissions were read out of the binary manifest's string pool, not
grepped as raw bytes: `MANAGE_EXTERNAL_STORAGE` is present, which the in-app file tree needs.

---

# Round 5 — FlowLink, a permanent room link, working categories, global search

## 1. The name

Every shipped surface now says **FlowLink**: the launcher label, the notification channels, the
in-app copy, the browser pages served over Web Share, the share-sheet hint and the APK name.
The application id stays `com.loopjet.app` on purpose — changing it would make this build a
different app that cannot install over the one already on the phone. The `loopjet://` QR scheme
is untouched, so codes from the previous build still work.

## 2. A room link that never changes

`http://<phone-ip>:8080/talal`

* Port **8080** and room path **`/talal`** are constants. If another process already holds 8080
  the server walks up to 15 ports and the phone shows the port it actually bound — sharing still
  works, and it says which address to use.
* The per-session `?t=…` token is gone: it was the thing that made the link different every time.
  First contact from a new browser is still gated by the one-tap approval on the phone, cached
  per address, so a permanent link does not mean an open door.
* Share sessions now stay valid for 12 hours instead of 30 minutes; the install-invite link stays
  short-lived on purpose.

The host part is the phone's own Wi-Fi address. That is the one piece an app cannot dictate; if
the router hands out 192.168.1.7 every time, the link is exactly the requested address.

## 3. Why every category looked empty

`READ_MEDIA_IMAGES / VIDEO / AUDIO` were only ever requested on the **Receive** screen, so anyone
who went straight to Send got an empty Photos, Videos, Music, Downloads, Documents, Archives and
APKs list — the query throws `SecurityException`, which the category loader swallowed. Now:

* `Perms.pickerRequirements()` asks for exactly what the picker needs, when the Send screen opens.
* A failed query is no longer silent: an empty category shows "Allow access to your files" with a
  button instead of "Nothing here yet".
* Returning from the dialog refreshes the open category.

## 4. Search that finds the thing

The field is now a doorway, not a filter. Tapping it hides the category strip and offers two
tabs — **Files** and **Apps** — exactly as requested:

* **Files** searches the whole phone, not the open folder: one indexed `MediaStore.Files` query
  across every shared directory, then a bounded tree walk (all-files access, 1.2 s, 20 000
  entries) for files the media index never scanned. Results are ranked exact → prefix → contains,
  newest first within each band.
* **Apps** answers from the installed-app list by name or package.
* Files and apps land in the same tray, so one Send still sends everything.
* Closing search restores the category you were in.

## 5. Speed

Nothing that was fast got slower.

* **One less copy per chunk.** The sender read each 256 KiB chunk into a scratch array, hashed it,
  then memcpy'd it into the record buffer. It now reads straight into the payload slot of the
  record buffer and hashes it there. Same wire format, one fewer full copy of every byte, and the
  256 KiB scratch array per stream is gone.
* **1 MiB stream buffers** (was 128 KiB) and **8 MiB socket buffers** (was 4 MiB): a 256 KiB
  record used to straddle several write syscalls.
* **Fast mode: 4 → 6 parallel streams** (Max stays 8, Balanced stays 2). The receiver already
  accepts eight, so the other phone needs no update for this.

## 6. Tests

31 → **33**: the room path serves the same page as the root (and `/talal-other` is still a 404),
a requested port is honoured, and a second server asked for a taken port moves off it.

---

# Round 6 — the motion system, the type scale and the efficiency pass

This is the first half of the design plan in `docs/ROUND-5-DESIGN-PLAN.md`: phases **A** (foundations),
**E** (efficiency) and the low-risk half of **D** (micro-interactions). Phase C (screen-by-screen
redesign) is not in this build yet.

## Foundations

* **Six motion tokens** in `JetMotion`, using the published M3 Expressive constants (spatial
  ζ 0.6/0.8, k 800/380/200; effects ζ 1.0, k 3800/1600/800). `MotionSettings.spec()` returns the
  spring for a job and degrades twice: no overshoot under power-save / low-RAM, and a snap when the
  system animation scale is 0. Declared locally rather than imported, because material3 1.4's
  expressive APIs are still partly internal.
* **Type**: weight 300 added to the bundled variable face; new `hero` (44/46, tabular digits) for
  the live speed, `numeral` for any live number, and `section` for tracked-out small labels.
* **Colour**: `surfaceContainer*` tiers for both themes (dark keeps true black), plus `success` and
  `warning`. The palette is now plain ints behind `Palette`, so contrast is testable.
* **Shape/depth**: radii moved onto 12/18/26/34; `SectionCard` moved from a 1 dp border to the
  `surfaceContainerLow` tone.

## Motion and touch

* **Navigation is a fade-through with direction**, not a 240 ms cross-fade: the incoming screen
  grows in from 1.04 (deeper) or 0.92 (back), the outgoing one recedes, all on the shared springs.
  `Screen` now carries a `depth` so the transition knows which way it is going.
* **Predictive back**: the surface recedes under the finger during the gesture and only commits on
  completion (it behaves like a plain back press where progress is not reported).
* **Haptics exist at last** — the app had none. One `Haptics` wrapper over Compose's feedback:
  toggle on/off for a file or app joining the tray, confirm on Send and on a finished transfer,
  reject on failure, tap for navigation. Compose's mapping also refuses to buzz during touch
  exploration, which a raw `View.performHapticFeedback` does not.
* **The tick lands**: the selection circle scales on `fastSpatial` while its fill fades on
  `fastEffects`, so a tap reads as a snap rather than a repaint.
* Thumbnails **pulse while decoding** instead of showing a dead grey square.

## Efficiency

* `vm.history()` — a **JSON file read** — ran inside Home's composable body on every recomposition.
  The count is now cached in `AppState` and refreshed on resume / after a session; the History
  screen loads off the main thread.
* `vm.deviceSummary()` — **three WifiManager IPC calls** — also ran inside composition. Cached the
  same way.
* `AppRepository.load()` decoded and retained a Drawable for **every installed app**. It now returns
  metadata only; icons load per visible row from a 64-entry LRU on the IO dispatcher.
* Brushes and gradients are no longer rebuilt per frame in the card path.

## Tests

33 → **38**: five contrast assertions (both themes, every surface tier, button pairs, semantic
marks, and a guard that the two themes really are opposite) and four motion-token assertions
(spatial springs overshoot but under 12 %, effects springs never do, faster really is faster, and
every token settles between 120 and 700 ms).

## Audit follow-up: the Android 14 “select photos” trap

Re-reading the picker for this pass turned up a real hole in the round-4 empty-category fix.
On Android 14 the photo permission has a weaker answer — *Select photos and videos* — which grants
`READ_MEDIA_VISUAL_USER_SELECTED` rather than `READ_MEDIA_IMAGES`. Media queries then **succeed**
but return only the handful of items the user ticked. `canListMedia()` saw a granted permission and
said yes, so the “Allow access” prompt never appeared: Photos looked half-empty, or empty, with no
explanation and no way forward. That is the same symptom the user reported, caused by a permission
state the fix could not see.

* `Perms.mediaAccessIsPartial()` distinguishes “a few” from “all” (and treats all-files access as
  full, since it makes the media grants redundant).
* `PickerState.mediaPartial` is set whenever a media query comes back empty under a partial grant.
* Two surfaces: a full prompt when the category is empty (*Only a few photos are visible* → Open
  settings), and a one-line banner above the list when it is merely short, so the explanation is
  present even when some items did load. Both point at the app's own settings page, which is the
  only place a partial grant can be widened.

Also verified in this pass, with evidence rather than assertion:

* every picker item has a positive size (folder listings, MediaStore rows and gallery picks all
  reject zero-length entries), so the tray count cannot promise more than the engine can send;
* `Browse every folder` really is backed by the `MANAGE_EXTERNAL_STORAGE` declaration plus
  `Environment.isExternalStorageManager()`, not just an intent;
* all fifteen typography styles carry the bundled variable font, and every line height is greater
  than its font size (the mid-word splitting came from the reverse);
* the signing certificate is byte-identical to the keystore's, so this build installs over the
  previous one.

---

# 1.0.1 — the connection, the speed and the progress bar

Three complaints from a real two-phone test: the connection was hard to get, the speed was poor,
and the two phones reported different progress (16% against 0%). Each had a concrete cause.

## 1. Progress: the receiving phone never knew the total

`SessionUi.totalBytes` was only ever written by the sending phone. The receiver's progress listener
copied bytes, speed and file counts but not the total, so its percentage divided by a floored zero:
0% for the whole transfer, then a jump to 100% at the end. The two screens *could not* agree.

* One shared fold, `SessionUi.absorbProgress()` in `platform/SessionProgress.kt`, used by both
  listeners. The total only grows and always comes from the frame (which both engines fill from the
  manifest), `doneBytes` never goes backwards when an out-of-order frame arrives, and `filesTotal`
  takes the largest seen.
* One shared `SessionUi.percent`, so the number is computed in one place.
* The transfer screen now prints the percentage next to the byte counts as well as in the bar, and
  names the link the bytes are crossing ("Same Wi-Fi · 192.168.1.7" or "Hotspot link") — the honest
  answer to "why is it slow".
* Six unit tests pin this down, including the exact 16%-vs-0 case and a late frame arriving with an
  older count.

## 2. Connection: a phone that had once scanned never announced itself

`startDiscovery()` returned early whenever a discovery engine already existed, ignoring the role it
was asked for. A phone that had opened Send (scanning, not advertising) and then opened Receive kept
scanning — it never announced itself, so the other phone's list stayed empty. That is the "I pressed
Receive and no connection was made" report, and it also explains why only one ordering worked.

* A different role now restarts discovery; the same role is still a no-op.
* The beacon advertises the port the receiving engine **actually bound** (`ReceiverPort`), not the
  default: `bind()` walks a range, and after an interrupted session it can land on the next free
  pair, leaving a beacon that points at a closed socket.
* The receiving phone stays findable for the whole session (`openSessionIfRunning`, and leaving for
  the transfer screen no longer stops it), so a failed first attempt can simply be retried.
* Announcements every 900 ms instead of 1.5 s, subnet scans every 2.5 s instead of 6 s, so a peer
  appears in the list in about a second rather than several.
* A sighting that was verified by a beacon probe can no longer be overwritten by an unverified one
  (BLE hands over whatever address the other phone put in its advert — often its hotspot address).
* Every dial now re-probes on failure: first attempt 2.5 s, then a fresh beacon probe for the current
  address and port, then one patient retry at the full timeout. Previously a stale endpoint cost six
  seconds and reported failure.

## 3. Speed

* **Commits left the data path.** Finishing a file (fsync + full re-read to verify the hash + rename)
  used to run on the data-stream thread, so the stream that delivered a large file's last chunk
  stopped receiving while it hashed the whole file — the transfer visibly froze near the end. It now
  runs on a two-thread commit pool.
* **Source reads are positional and lock-free.** `FileSource` used one `RandomAccessFile` with a seek
  under a lock, so six workers reading one large file took turns. `FileChannel.read(buf, position)`
  removes the lock.
* Max mode really opens eight streams (the worker count was capped at six), the receiving sockets go
  from 4 MiB to 8 MiB to match the sender, and `SocketIo`/`AdapterIo` both keep TCP_NODELAY on.

CI on this commit: compile, 48 unit tests, debug, release, loopback benchmark all green; the 192 MB
loopback run reports 176.8 MB/s, inside the runner's usual 160–400 MB/s noise band.

---

# 1.0.2 — hotspot first, Wi-Fi by choice

Built to the user's proposal: the phones should default to the hotspot, offer a Wi-Fi button for
people who know they share a network, and show the OS-generated hotspot name and password under the
QR code. All three are in, plus one fix that is probably the reason hotspot attempts failed before.

## The bug that made hotspot mode fail

`activeWifiNetwork()` returned the *first* Wi-Fi network the system listed. A phone that has just
joined the other phone's hotspot is usually still associated with its own home network too, so the
sender bound its socket to the interface that cannot reach the peer, and every dial timed out while
both phones showed "connected". `NetworkTools.wifiNetworkFor(peerAddress)` now picks the network whose
own IPv4 address shares the peer's /24 — chosen by address, not by enumeration order.

## Hotspot first

* `Prefs.connectMode` defaults to `"hotspot"`; the Connect screen is a two-chip choice
  (Hotspot / Same Wi-Fi) with the hotspot route described in one line: *tap Receive on the other
  phone, then join the hotspot it shows*.
* The Receive screen publishes the access point the OS created: `hotspotSsid`, `hotspotPass`,
  `hotspotAddress` in `SessionUi`, shown as a card under the QR with per-field copy and a
  "copy name and password" button. This is the manual route into the AP when the camera is busy, the
  other device is not an Android phone, or the system prompt refuses.
* The sender can type the name and password (`joinHotspot`), which requests the network, binds to it
  and starts discovery on it; if the OEM refuses the request, the app opens Wi-Fi settings instead of
  leaving the user at a dead end. After a manual join the app restarts discovery on resume, so the
  other phone appears in the list by itself.
* Scanning the QR still works and still carries the network, so the one-tap path is unchanged.

## Same Wi-Fi, chosen rather than assumed

* The Wi-Fi chip runs the ordinary radar. Each peer now carries a verification: same /24 as one of
  this phone's own addresses shows "Same Wi-Fi verified", otherwise "Different network" in the error
  colour. The check lives in `net/Subnet.kt` (no Android types) and is covered by four unit tests,
  including guest networks, 192.168.43.x hotspot addresses and malformed input.
* Tapping a peer that is *not* verified is still allowed — routing can make it work — but the user
  is no longer told a thing is fine when it is not.

## Tests

48 → 52 unit tests (contrast, motion tokens, session progress, and now subnet verification). CI on this
commit builds debug and release and runs the loopback benchmark.

---

# 1.0.3 — hotspot only

The Wi-Fi mode is gone. One connection method, as the user asked: the phone showing the code hosts
a hotspot, the other phone joins it, and scanning the code does both without a second decision.

## What changed

* **Connect screen is hotspot-only.** The Hotspot / Same-Wi-Fi chips are removed, along with the
  preference behind them. What is left is the thing that works: scan a code (which joins the other
  phone's hotspot automatically), or type the hotspot name and password the other phone displays.
* **A scan always joins, never guesses.** A code that carries a network is joined first, every time.
  The old path compared subnets and preferred "LAN" when the two phones merely *looked* like they
  shared a Wi-Fi network — the exact guess that produced silent six-second timeouts with nothing on
  screen. If the join is refused and the code also carries a LAN address, that is used as a
  fallback, but only after the hotspot attempt.
* **Already joined? No prompt.** A second scan on the same hotspot detects the SSID and connects
  straight over the existing network instead of asking Android for a network the phone is sitting on.
* **Wi-Fi off is named**, not discovered five seconds later: the app says so and opens Wi-Fi settings
  (no app can switch Wi-Fi on since Android 10).
* **The receiving screen says when the hotspot is not up yet** ("Starting this phone's hotspot…"),
  because the code is published a second before the access point exists — scanning in that window was
  a silent failure.
* The hotspot card now also shows the **band** (2.4 GHz / 5 GHz). Android's public API cannot choose
  the band for a local-only hotspot, so the honest thing is to show which one was granted: 2.4 GHz
  caps out around 8–10 MB/s, 5 GHz around 40–50 MB/s.
* The QR line no longer says "Same Wi-Fi · host (fastest)". It says what a scan will do:
  `Hotspot "AndroidAP_1234" · scanning joins it`.

## Speed, stated honestly

The loopback benchmark numbers in this log (160–380 MB/s) measure the engine on a CI machine with no
radio. They are not a promise about a phone-to-phone transfer, and they were never meant to read as
one. What the radio can do is the ceiling, and the burst-then-slowdown that shows up in real tests is
the kernel buffers draining: at a 50 MB/s line with 8 MiB per stream in flight, the first seconds
report the rate at which the buffers empty, not the rate the air can carry. The transfer screen now
prints the **average** next to the instantaneous rate for exactly this reason.

Engine-side change in this build: the receiver's per-stream buffered I/O went from 64 KiB to 1 MiB,
matching the sender and the 256 KiB record size — four copies of every byte on the receive path
became one.

## Signing identity changed with this build

The release keystore lived in `keys/`, which is gitignored (a private key must not be in a public
repository) and was **not included in the workspace snapshot that carried this session over**. The
private key is therefore gone — the certificate in the APKs up to 1.0.2 is all that survives of it,
and a certificate cannot sign anything.

A new keystore was generated for 1.0.3: `C=PK, O=FlowLink, OU=Release, CN=FlowLink`, SHA-256
`3B:B1:6B:04:B3:E4:0D:FA:37:A2:49:2F:5E:5C:F9:E4:B5:4A:51:5F:BC:C6:0F:66:1B:92:8E:D8:90:86:E8:59`.

Consequence, stated plainly: **Android will refuse to install 1.0.3 over 1.0.2** — different signer,
same package. The old app has to be uninstalled once (which also clears its saved transfers, trusted
devices and settings). From 1.0.3 onwards upgrades are in place again, as long as this keystore
survives; it cannot be regenerated and cannot be recovered if lost.
