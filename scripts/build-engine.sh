#!/usr/bin/env bash
# Builds tinyasr (https://github.com/dkaraush/tinyasr, GPL-3.0-or-later) for
# Android arm64 and stages the static library, header and model packs.
#
# Needs: git, gzip, rustup, and the Android NDK (only for the final .so, which
# Gradle builds through CMake).
set -euo pipefail

REV="e034289132667fa8e87dd8d474a4dc59d447b2dc"

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/third_party/tinyasr"
out="$root/app/src/main/cpp/prebuilt/arm64-v8a"

if [ ! -d "$src/.git" ]; then
    mkdir -p "$src"
    git -C "$src" init -q
    git -C "$src" remote add origin https://github.com/dkaraush/tinyasr.git
fi
if [ "$(git -C "$src" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$REV" ]; then
    git -C "$src" fetch -q --depth 1 origin "$REV"
    git -C "$src" checkout -q --detach FETCH_HEAD
fi

rustup target add aarch64-linux-android
cargo build --manifest-path "$src/Cargo.toml" --release --features c-api --target aarch64-linux-android

mkdir -p "$out"
cp "$src/target/aarch64-linux-android/release/libtinyasr.a" "$out/libtinyasr.a"

# Model packs (uncompressed .tzf + Silero VAD) for the "full" flavor.
assets="$root/app/src/full/assets/models"
mkdir -p "$assets"
gzip -dc "$src/models/en-fa.tzf.gz" > "$assets/en-fa.tzf"
gzip -dc "$src/models/en-ru-uk.tzf.gz" > "$assets/en-ru-uk.tzf"
gzip -dc "$src/models/silero_vad.onnx.gz" > "$assets/silero_vad.onnx"

echo "tinyasr $REV ready: $out/libtinyasr.a"
echo "models ready: $assets"
