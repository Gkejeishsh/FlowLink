#!/usr/bin/env bash
#
# Build LoopJet from this source tree into a signed, aligned, universal APK.
#
# Everything here is the same sequence CI runs (.github/workflows/build.yml): Gradle builds the R8
# release APK, zipalign rewrites it for 4-byte alignment, apksigner signs it with v2 + v3, and the
# result is verified and hashed. Nothing is downloaded except Gradle itself, which the wrapper pins.
#
# Usage:
#   ./build-apk.sh                 build, align, sign if LJ_KEYSTORE is set, verify, print hashes
#   ./build-apk.sh --test          also run the unit tests first (engine + HTTP server, real sockets)
#   ./build-apk.sh --unsigned      stop after alignment (no signing material available)
#
# Requirements:
#   JDK 21                java on PATH (or JAVA_HOME set)
#   Android SDK           ANDROID_HOME set, with platform 37 and build-tools 36.0.0 installed:
#                           sdkmanager "platforms;android-37" "build-tools;36.0.0" "platform-tools"
#   Signing (optional)    LJ_KEYSTORE=/path/to/key.p12 LJ_STORE_PASS=... [LJ_KEY_PASS=...]
#                         [LJ_KEY_ALIAS=loopjet]   — create one with tools/make_keystore.py
#
# The password is passed to apksigner through `env:` so it never appears in the process list.
#
set -euo pipefail
cd "$(dirname "$0")"

RUN_TESTS=0
UNSIGNED_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --test) RUN_TESTS=1 ;;
    --unsigned) UNSIGNED_ONLY=1 ;;
    -h|--help) sed -n '3,24p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

say() { printf '\n== %s\n' "$1"; }
die() { printf 'error: %s\n' "$1" >&2; exit 1; }

# --- toolchain ------------------------------------------------------------------------------------
command -v java >/dev/null 2>&1 || die "java not found — install JDK 21 or set JAVA_HOME"
java -version 2>&1 | head -1

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || die "ANDROID_HOME is not set (point it at the Android SDK)"
[ -d "$SDK" ] || die "ANDROID_HOME=$SDK does not exist"

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BT" ] || die "no build-tools in $SDK — run: sdkmanager \"build-tools;36.0.0\""
BT="${BT%/}"
[ -x "$BT/zipalign" ] || die "zipalign missing from $BT"
[ -x "$BT/apksigner" ] || die "apksigner missing from $BT"
PLATFORM="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$PLATFORM" ] || die "no platform in $SDK — run: sdkmanager \"platforms;android-37\""
echo "build-tools : $BT"
echo "platform    : ${PLATFORM%/}"

# --- build ----------------------------------------------------------------------------------------
if [ "$RUN_TESTS" = 1 ]; then
  say "unit tests (real sockets, no device needed)"
  ./gradlew --no-daemon --console=plain :app:testDebugUnitTest
fi

say "release build (R8, shrinkResources, non-debuggable)"
./gradlew --no-daemon --console=plain :app:assembleRelease

UNSIGNED="$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)"
[ -n "$UNSIGNED" ] || die "Gradle produced no release APK"
echo "gradle output: $UNSIGNED"

# --- align ----------------------------------------------------------------------------------------
mkdir -p dist/unsigned
ALIGNED="dist/unsigned/LoopJet-1.0.0-release-aligned.apk"
say "zipalign"
"$BT/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"
"$BT/zipalign" -c -p 4 "$ALIGNED" && echo "alignment verified"

# --- sign -----------------------------------------------------------------------------------------
FINAL="dist/LoopJet-1.0.0-release.apk"
if [ "$UNSIGNED_ONLY" = 1 ]; then
  say "unsigned build"
  echo "aligned payload: $ALIGNED"
  sha256sum "$ALIGNED"
  exit 0
fi

if [ -n "${LJ_KEYSTORE:-}" ]; then
  [ -f "$LJ_KEYSTORE" ] || die "LJ_KEYSTORE=$LJ_KEYSTORE does not exist"
  [ -n "${LJ_STORE_PASS:-}" ] || die "LJ_STORE_PASS is not set"
  KEY_PASS="${LJ_KEY_PASS:-$LJ_STORE_PASS}"
  export LJ_STORE_PASS LJ_KEY_PASS="$KEY_PASS"
  ALIAS="${LJ_KEY_ALIAS:-loopjet}"

  say "sign (v2 + v3, alias $ALIAS)"
  "$BT/apksigner" sign \
    --ks "$LJ_KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --ks-pass env:LJ_STORE_PASS \
    --key-pass env:LJ_KEY_PASS \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$FINAL" "$ALIGNED"

  say "verify"
  "$BT/apksigner" verify --verbose --print-certs "$FINAL"
  "$BT/apksigner" verify --min-sdk-version 26 --max-sdk-version 37 "$FINAL"
else
  say "no LJ_KEYSTORE set — publishing the unsigned aligned payload instead"
  cp "$ALIGNED" "$FINAL"
  echo "An unsigned APK will not install on Android; set LJ_KEYSTORE/LJ_STORE_PASS to sign it."
fi

# --- report ---------------------------------------------------------------------------------------
say "result"
ls -l "$FINAL"
sha256sum "$FINAL" "$ALIGNED"
"$BT/aapt2" dump badging "$FINAL" 2>/dev/null | grep -E "^(package|minSdkVersion|targetSdkVersion)" || true
