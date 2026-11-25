#!/usr/bin/env bash
# Builds both APKs, signs them, and uploads a GitHub release with the app and
# the model packs attached.
#
# Needs: ANDROID_HOME (SDK 34, build-tools 34.0.0, NDK 27, CMake 3.22.1),
#        JDK 17+, the Rust Android target, and an authenticated `gh`.
#
# Usage:
#   export SIGNING_KEYSTORE=/path/to/key.jks SIGNING_PASSWORD=... \
#          SIGNING_KEY_ALIAS=... SIGNING_KEY_PASSWORD=...
#   release/make-release.sh 2.2
set -euo pipefail

VERSION="${1:?usage: make-release.sh <version, e.g. 2.2>}"
TAG="v$VERSION"

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

build_tools="${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools/34.0.0"
assets="$root/release/assets-$VERSION"
mkdir -p "$assets"

echo "==> engine"
scripts/build-engine.sh

echo "==> layouts"
scripts/check-layout.sh

echo "==> apks"
./gradlew assembleDebug --no-daemon

sign() {
    local unsigned="$1" name="$2"
    local out="$assets/$name"
    rm -f "$out"
    if [ -n "${SIGNING_KEYSTORE:-}" ]; then
        "$build_tools/zipalign" -f -p 4 "$unsigned" "$assets/.aligned.apk"
        "$build_tools/apksigner" sign \
            --ks "$SIGNING_KEYSTORE" \
            --ks-pass "pass:${SIGNING_PASSWORD:?}" \
            --ks-key-alias "${SIGNING_KEY_ALIAS:?}" \
            --key-pass "pass:${SIGNING_KEY_PASSWORD:?}" \
            --out "$out" "$assets/.aligned.apk"
        rm -f "$assets/.aligned.apk"
    else
        # Nothing to do: gradle already signed it with its debug key.
        echo "   (no SIGNING_KEYSTORE: attaching the debug-signed APK)"
        cp "$unsigned" "$out"
    fi
    "$build_tools/apksigner" verify "$out"
}

sign app/build/outputs/apk/core/debug/app-core-debug.apk tinyasr-core-debug.apk
sign app/build/outputs/apk/full/debug/app-full-debug.apk tinyasr-full-debug.apk

echo "==> model packs"
cp app/src/full/assets/models/en-fa.tzf "$assets/"
cp app/src/full/assets/models/en-ru-uk.tzf "$assets/"
cp app/src/full/assets/models/silero_vad.onnx "$assets/"

echo "==> checksums"
( cd "$assets" && sha256sum ./* > checksums.txt && cat checksums.txt )

echo "==> release $TAG"
gh release create "$TAG" \
    --title "tinyasr $VERSION" \
    --notes "Offline speech recognition for Android (arm64).

**Install:** \`tinyasr-full-debug.apk\` (bundled en/fa + en/ru/uk models) or
\`tinyasr-core-debug.apk\` (import the .tzf packs and silero_vad.onnx below).

Engine: tinyasr (Zipformer RNN-T, Silero VAD), GPL-3.0-or-later.
Sizes and hashes are documented in docs/MODELS.md; \`checksums.txt\` covers this
release's files." \
    "$assets"/*

echo "==> done: see the release on GitHub"
