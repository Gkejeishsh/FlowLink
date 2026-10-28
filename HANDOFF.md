# Handoff: building LoopJet into a signed APK

Everything needed to produce `LoopJet-1.0.0-release.apk` from this repository, written so another
agent (or another person) can do it without asking questions. Two supported routes: **A** — GitHub
Actions, which needs no local toolchain and is what produced the current APK; **B** — a normal
Linux/macOS machine with JDK 21 and the Android SDK.

Current state: branch `arena/7ef48d45-flowlink`, latest CI run green (compile 0, tests 0 of 12
failures, R8 release built, `apksigner` verifies v2 + v3).

---

## Route 0 — you were handed a zip, or you will pull it from this repo

`handoff/LoopJet-1.0.0-source.zip` (247 KB, 61 files) is the complete, build-ready source tree: the
app module, Gradle wrapper and pinned version catalogue, `build-apk.sh`, this file and the design
sources. It contains **no** keystore, no password, no build output and no `.git`.

Either of these gets you a build directory:

```bash
# you were given the zip
unzip LoopJet-1.0.0-source.zip -d ~/build && cd ~/build/LoopJet-1.0.0

# or pull it straight out of the repository (no upload, no cloning the whole history)
git clone --depth 1 --branch arena/7ef48d45-flowlink https://github.com/Gkejeishsh/FlowLink.git flowlink
unzip -q flowlink/handoff/LoopJet-1.0.0-source.zip -d ~/build && cd ~/build/LoopJet-1.0.0
```

Verify the archive first — this is the only copy of that digest:

```bash
sha256sum -c <<< '1e8a6bb5989ce8d3dedba308c752681ec5d9496b35309c73772eb2009c038b75  LoopJet-1.0.0-source.zip'
```

Then pick a route below. With the zip you have source only, so use **Route B** (local build) or push
the tree into a repository and use **Route A** (Actions). Signing needs a key: use the existing
`keys/loopjet-release.p12` from the owner if you were given it, or `tools/make_keystore.py` to create
a *new* one — but note the consequence in "Signing material" before you do that.

---

## Route A — GitHub Actions (recommended; nothing to install)

The workflow `.github/workflows/build.yml` already downloads JDK 21, the Android SDK
(cmdline-tools, `platforms;android-37`, `build-tools;36.0.0`), Gradle 9.6.0, then runs
`compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `lintRelease`,
zipaligns the payload, signs it and verifies it. It publishes `ci/*.log`, `ci/summary.txt`,
`dist/SIGNING-REPORT.txt` and the APKs back to the branch.

**To make it sign, add four repository secrets** (Settings → Secrets and variables → Actions), or
from any machine with `gh` authenticated as a repo admin:

```bash
gh secret set LJ_KEYSTORE_B64 --repo Gkejeishsh/FlowLink < loopjet-release.p12.b64
gh secret set LJ_STORE_PASS   --repo Gkejeishsh/FlowLink --body '***'
gh secret set LJ_KEY_PASS     --repo Gkejeishsh/FlowLink --body '***'   # same value is fine
gh secret set LJ_KEY_ALIAS    --repo Gkejeishsh/FlowLink --body 'loopjet'
```

Once `LJ_KEYSTORE_B64` exists, **every push builds and signs**: no dispatch needed, no keystore on
any laptop, and the key stays in GitHub secrets (encrypted, never readable back).

Then:

```bash
git clone https://github.com/Gkejeishsh/FlowLink.git && cd FlowLink
git checkout arena/7ef48d45-flowlink
# … make changes, commit …
git push origin arena/7ef48d45-flowlink
gh run watch                     # ~9 min
```

Result in the branch after the run:

```
dist/LoopJet-1.0.0-release.apk            the signed, aligned, universal APK
dist/unsigned/LoopJet-1.0.0-release-aligned.apk   the same bytes before signing
dist/SIGNING-REPORT.txt                   apksigner --print-certs + zipalign + badging output
ci/summary.txt                            return codes, first compile errors, test result, benchmarks
ci/test.log                               all 12 tests
```

Acceptance checklist for whoever runs it — all of these must hold, and all are printed by the run:

| Check | Expected |
| --- | --- |
| `ci/summary.txt` first line | `compile=0 tests=0 debug=0 release=0 wrapper=0` |
| `ci/test.log` | 12 × `PASSED`, 0 × `FAILED` (6 engine + 6 HTTP-server tests) |
| `dist/SIGNING-REPORT.txt` | `Verifies`, v2 `true`, v3 `true` |
| same file | `zipalign -c -p -v 4` → `Verification successful` |
| same file | `minSdkVersion:'26'` and `targetSdkVersion:'37'` |
| certificate SHA-256 | `f35454a191e71fc689835921811560d70093f33ac43d0b01aa455a5b22d363dd` |

If the certificate digest is anything else, a different key was used: stop, because that APK will not
update an existing installation.

## Route B — build locally

```bash
# 1. toolchain
java -version                     # must be 21
export ANDROID_HOME=$HOME/Android/Sdk
sdkmanager "platform-tools" "platforms;android-37" "build-tools;36.0.0"

# 2. build + align + sign + verify in one step
cd FlowLink
LJ_KEYSTORE=$HOME/keys/loopjet-release.p12 \
LJ_STORE_PASS='***' \
./build-apk.sh --test
```

`build-apk.sh` is the CI sequence verbatim (Gradle → `zipalign -p 4` → `apksigner` v2+v3 → verify →
hashes) and refuses to continue if the JDK, SDK, platform, build-tools or key are missing. Without
`LJ_KEYSTORE` it stops after alignment and says that an unsigned APK will not install — that is
deliberate, not a bug.

Requirements the machine must reach over the network: `dl.google.com` and `maven.google.com` (SDK and
AndroidX/Compose AARs), `repo1.maven.org` (Maven Central), `services.gradle.org` (the Gradle
distribution). A sandbox that only allows GitHub/PyPI/npm **cannot** build this — that is why the APK
in this repository was produced by Route A.

## Signing material

| Item | Where | Notes |
| --- | --- | --- |
| Keystore (PKCS#12) | `keys/loopjet-release.p12` — **outside this repository** | RSA 4096, self-signed, valid 30 years, alias `loopjet` |
| base64 of the same file | `keys/loopjet-release.p12.b64` | for the GitHub secret, and nothing else |
| Passwords + fingerprints | `keys/loopjet-credentials.txt` | mode 600 |
| Certificate SHA-256 | `f35454a1…d363dd` | printed by every signed build |

Rules that must not be broken:

* **Never commit the keystore, the base64 or the credentials.** `.gitignore` blocks `*.jks`,
  `*.keystore`, `*.p12`; keep the credentials file out of the tree as well.
* **Never sign with a new key "just to make it work".** A different certificate means Android refuses
  to update an installed LoopJet and every user has to uninstall first. If the key is missing, stop
  and ask the owner for `keys/loopjet-release.p12`.
* `tools/make_keystore.py` exists only to *create* a key on first setup. It cannot recover a lost one.
* The Invite page serves the installed APK's own bytes (`applicationInfo.sourceDir`); nothing in the
  build may re-sign or modify that payload.

## What the other agent should not change

* Do not add build-time downloads that need the internet at runtime — the app is local-only by design.
* Do not weaken the tests to make a run green. If `TransferLoopbackTest` or `WebServerTest` fails, the
  failure is real: both drive real sockets.
* Do not quote a speed that was not measured on the machine that ran the build. `TEST_REPORT.md`
  states which numbers are loopback and which paths were never executed on hardware.
* Keep `targetSdk 37` / `minSdk 26` and the pinned versions in `gradle/libs.versions.toml`.

## Reporting back

Paste, verbatim:

1. the first line of `ci/summary.txt`,
2. the `sha256sum` line of `dist/LoopJet-1.0.0-release.apk`,
3. the `Verifies` / v2 / v3 lines and `Signer #1 certificate SHA-256 digest` from
   `dist/SIGNING-REPORT.txt`,
4. anything from `ci/summary.txt` under "first compile errors" or "test failures".

That is enough for anyone to confirm the build is genuine without re-running it.
