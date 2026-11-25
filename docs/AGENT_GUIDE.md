# Agent guide — reproduce this on a future Telegram version

A step-by-step program for an AI agent (or a patient human) to redo this work
when Telegram ships a new client and the engine or models change. Follow it in
order; every step has a check that tells you whether to continue.

Target output: a working `libtinyasr_jni.so` + fresh packs for the app in this
repo, or a confident statement of what changed.

---

## Ground rules

1. **Never reuse Telegram's `libtmessages.so` directly.** Its `JNI_OnLoad`
   resolves method IDs on Telegram's own Java classes; in another app that is a
   fatal JNI abort at load time. Rebuild the engine instead.
2. **A pack is not the suspect until it is proven one.** Parse the container and
   load it against the engine before blaming it.
3. **Count JNI arguments from `JNIEnv*`.** For `static native`, argument 1 is the
   unused `jclass`.
4. Keep credentials out of logs; the downloader reads them from the environment.

---

## Step 1 — extract the artifacts

```bash
unzip -o telegram.apk 'lib/arm64-v8a/*' -d apk/
jadx -d src telegram.apk          # or: jadx --no-res -d src telegram.apk
```

**Check:** `apk/lib/arm64-v8a/libtmessages.so` exists (version number in the name
changes; `49` was 13.3.1).

## Step 2 — find the engine and the entry points

```bash
strings -a apk/lib/arm64-v8a/libtmessages*.so | grep -iE 'tinyasr|tzf|onnx'
nm -D --defined-only apk/lib/arm64-v8a/libtmessages*.so | grep -i dictation
```

**Check:** you see `Java_org_telegram_ui_Dictation_DictationAsr_native*` and/or a
`tinyasr_load:`-shaped string. If the class moved, the export name tells you the
new package — that name *is* the ABI.

```bash
grep -rn "nativeLoad\|nativeFileNew\|nativeSessionPush" src/sources | head
```

Read the surrounding class: you need the model directory, the call order, and
the thread count (`2` low-end / `4` otherwise).

## Step 3 — pin the engine revision

```bash
curl -s https://api.github.com/repos/dkaraush/tinyasr/git/trees/main?recursive=1 \
  | grep -E '"path": "(src|models|include)'
git clone --depth 1 https://github.com/dkaraush/tinyasr && cd tinyasr
```

Compare `tinyasr_version()`'s return in `src/lib.rs` with the immediate
(`mov w0, #N; ret`) at the `nativeVersion` export in the `.so`. They may differ
(Telegram shipped a private v3 while upstream had 2 and 4) — if so, pick the
upstream revision whose model generation the packs match, and say so in the
release notes.

**Check:** upstream builds and its `models/` listing covers the languages you
need.

## Step 4 — verify the engine is self-contained

Before writing any app code, confirm the rebuilt library has no host dependency:

```bash
aarch64-linux-android-nm -D libtinyasr_jni.so | grep -c Java_dev_   # your exports
aarch64-linux-android-nm -D libtinyasr_jni.so | grep -i JNI_OnLoad  # must be absent
readelf -d libtinyasr_jni.so | grep NEEDED   # liblog libdl libm libc only
```

**Check:** no `JNI_OnLoad`, only system `NEEDED` entries.

## Step 5 — get the packs

```bash
export TG_API_ID=... TG_API_HASH=...
python3 docs/download_models.py --list          # languages + sizes
python3 docs/download_models.py --lang en,fa
```

The TL definitions in `docs/download_models.py` are layer-234 raw types
(`transcriptionModel#00c16f15`, `messages.getTranscriptionModels#081f6321`). On
a newer layer, reconcile them against <https://core.telegram.org/schema> and
update the constructor IDs — everything else stays.

**Check:** the catalogue lists one entry per language family, each with a size
close to the old one.

## Step 6 — parse a pack before loading it

```python
# see docs/FORENSIC.md §5 for the field order
b = open("en-fa.tzf","rb").read()
magic, gen = b[:4], b[3:4]
# settings, text (TZF3+), tensors, pieces
```

**Check:** the magic is `TZF` + a generation you can link to a supported engine
version; `n_langs` and the `lang.K` codes match what you expect.

## Step 7 — load it off-device first

Build the same engine for the host and drive the C API. This is faster and safer
than iterating on a phone, and it is how a bad pack is ruled out:

```bash
cargo build --release --features c-api
cc harness.c target/release/libtinyasr.a -lpthread -ldl -lm
./harness pack.tzf silero_vad.onnx     # expect a handle + lang list + chunk_ms
```

**Check:** non-zero handle, `chunk_ms` sensible, language list as expected.

## Step 8 — build the bridge and the app

```bash
scripts/build-engine.sh            # Rust -> libtinyasr.a, packs -> assets
./gradlew assembleDebug
```

Adapt `app/src/main/cpp/tinyasr_jni.c` to the new `tinyasr.h`, then run the
repo's guard rails:

```bash
scripts/check-layout.sh            # catches style-supplied layout attrs
./gradlew :app:lintDebug           # 0 errors expected
```

## Step 9 — verify on device

```bash
adb install -r app/build/outputs/apk/core/debug/app-core-debug.apk
adb logcat -c && adb logcat | grep -iE 'tinyasr|Dictation|signal|backtrace'
```

**Check:** `tinyasr_load` succeeds, a live session emits `partial` events, and a
whole-file pass on a speech clip returns text. A `tinyasr_load: <reason>` line
means the engine rejected the file — believe it.

---

## Failure triage

| Symptom | Cause | Action |
|---|---|---|
| Process dies at `System.loadLibrary` | `JNI_OnLoad` needs host classes | rebuild the engine; do not reuse the monolith |
| `tinyasr_load: not a tinyasr model file` | wrong pack or truncated download | re-download, compare SHA-256 in `MODELS.md` |
| `tinyasr_load: no VAD model` | missing/short `silero_vad.onnx` | re-import it |
| Empty transcript on speech audio | language pinned to the wrong code | try another language; with several, the engine picks "en" |
| Transcript flips to `en` mid-session | auto-detect mode | pass a single language |
| `You must supply a layout_width attribute` | a `<style>` is supplying layout params | `scripts/check-layout.sh` |
| Out-of-memory during load | two packs resident | keep one handle at a time |

## What to publish when done

* the two APKs, the packs, SHA-256 of everything;
* the pinned engine revision and, if it differs, the telegram-side
  `nativeVersion()`;
* any TL schema change the downloader needed.
