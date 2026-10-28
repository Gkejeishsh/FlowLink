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
