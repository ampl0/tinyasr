# Forensic walkthrough — how Telegram's on-device transcription was reverse-engineered

This is the long version of "where did all this come from". It records the
method, not just the result, because the same method is what keeps the project
alive when Telegram ships a new version.

Read [`AGENT_GUIDE.md`](AGENT_GUIDE.md) if you want the checklist to redo this on
a future build.

---

## 0. The problem

Telegram 13.x transcribes voice messages **on the phone**, offline, in ~10
languages, with punctuation. That implies a bundled recognizer plus per-language
models. The APK is a normal Telegram release, so everything must be discoverable
inside it:

* which recognizer,
* where the models live,
* how the Java side talks to it.

## 1. Get the artifacts

```bash
# The split we used: arm64 only, which is what a modern phone runs.
unzip -l telegram.apk | grep -E '\.so|assets/'
#   lib/arm64-v8a/libtmessages.49.so   22 MB
```

`libtmessages.so` is Telegram's single native library: SQLite, FFmpeg, WebRTC,
BoringSSL, and — as it turns out — the ASR engine.

```bash
readelf -d libtmessages.49.so | grep NEEDED
#   libjnigraphics, liblog, libz, libGLESv2, libEGL, libandroid,
#   libOpenSLES, libm, libdl, libc      <- no ASR* or tflite: it's statically linked
```

Decompile the Java/DEX with jadx (`jadx -d src telegram.apk`), then look for the
feature by name. The strings are a goldmine:

```bash
strings -a libtmessages.49.so | grep -i -E 'tinyasr|tzf|dictation'
#   tinyasr_load: %s
#   tinyasr: %d thread(s), pinned to %d of %d cores
#   TZF1 TZF2 TZF3
#   no recognizer model / no VAD model / not an ONNX model
```

`tinyasr` is the tell. Search the decompiled tree:

```bash
grep -rn "tinyasr\|nativeLoad\|TranscriptionModel" src/sources | head
```

## 2. Recover the JNI surface

The exports are plain name-mangled JNI, so the Java side is not needed to know
what exists:

```bash
nm -D --defined-only libtmessages.49.so | grep Dictation
#   Java_org_telegram_ui_Dictation_DictationAsr_nativeLoad
#   Java_org_telegram_ui_Dictation_DictationAsr_nativeLangs
#   ... nativeFileNew / nativeFileNext / nativeFileLang / nativeFileFree
#   ... nativeSessionNew / nativeSessionPush / nativeSessionFinish / nativeSessionFree
#   ... nativeDecode / nativePinFast / nativeVersion
```

So the implementation class is `org.telegram.ui.Dictation.DictationAsr`. The
decompiled class gives the exact signatures, the model directory
(`filesDir/tinyasr`) and the call order (`b0()`):

```java
long k = k(kVar);                       // the loaded pack's handle
nativeFileNew(k, pcm, languages, pz.o()==0 ? 2 : 4)
while ((chunk = nativeFileNext(f)) != null) n(sb, new JSONArray(chunk), timings);
nativeFileLang(f); nativeFileFree(f);
```

`DictationLid` is the companion (language ID, `tinylid.bin`).

### The ABI trap

The declaration is `private static native long nativeLoad(String, String)`, so at
the JNI level it is `(JNIEnv*, jclass, jstring, jstring)` — **four** registers on
arm64. Reading the disassembly naively and assuming two arguments puts `x1`/`x2`
in the wrong slots and produces a convincing but wrong story. Always count from
`JNIEnv*` first; on a static native method the second register is the unused
`jclass`.

## 3. Identify the recognizer

The Rust crate's error strings are unmistakable and the loader's call site is a
single function (`nativeLoad` → an internal `tinyasr_load`-shaped routine):

* `TZF1/TZF2/TZF3` — a custom container magic,
* `no recognizer model`, `no VAD model`,
* `not an ONNX model` — so the VAD is ONNX (Silero).

Searching the web for `tinyasr` finds the upstream project:
<https://github.com/dkaraush/tinyasr> — a Rust Zipformer **RNN-T / TDT**
recognizer with a Silero VAD front end and an end-of-turn head.

## 4. Get the models

They are **not** in the APK. They are ordinary Telegram documents served by two
catalog methods (`messages.getTranscriptionModels`) and fetched with
`upload.getFile`. [`download_models.py`](download_models.py) implements this
with Pyrogram, including the layer-234 TL definitions Pyrogram 2.x does not
generate.

```bash
export TG_API_ID=... TG_API_HASH=...
python3 download_models.py --list
python3 download_models.py --lang fa
```

Packs arrive **gzip-compressed** and are unwrapped by the client's `FileLoader`
path before reaching the engine.

## 5. Validate the packs before blaming the engine

A `TZF` file is a tiny, self-describing format. Parse it and you learn the
generation and the language list immediately:

```
TZF3                                   <- magic + generation
u32 n_settings, then n × (u16 len, key, i32 value)
   shared=32 lang_size=1024 n_langs=2 vocab=2080 ...
u32 n_text,   then n × (u16 len, key, u16 len, value)      [TZF3+ only]
   lang.0="en" lang.1="fa" ...
u32 n_tensors, then per tensor: u16 len, name, u8 integer, u8 n_dims, u32 dims[],
   (integer) u8 bits, u32 group, u8 scale_dtype + aligned payload
u32 n_pieces, then n × (u16 len, piece)
```

Two generations matter:

* **TZF2** (library version 2): languages are a fixed table, no text settings.
* **TZF3+**: the file carries its own language codes and writing rules.

Both load in current builds; a mismatch produces a clean error, not a crash.

## 6. The crash that started this repo

The first version of this app declared Telegram's class and called its
`nativeLoad` through the monolith. It died on **every** run, inside
`System.loadLibrary`, before a byte of a model was parsed.

Disassembling `JNI_OnLoad` explained it:

```
FindClass(env, "org/telegram/messenger/AnimatedFileDrawableStream") -> null
GetMethodID(env, null, "read", "(II)I")                             -> fatal
```

The monolith caches method IDs on Telegram's own Java classes. An app that does
not ship them (it can't — they are half the client) fails the lookup, and ART
turns a failed `GetMethodID` into a process abort. Conclusion: **do not reuse
Telegram's `.so`; rebuild the engine.** Hence `scripts/build-engine.sh` and the
28 MB static library in `app/src/main/cpp/prebuilt/`.

The lesson is worth stating plainly: a native library is reusable only if it is
self-contained. `JNI_OnLoad` reaching into its host application's classes makes
it a plugin of that application, not a library.

## 7. Language selection, and the bug it caused

With one language the engine decodes in it. With several it runs a full pass in
**each** and keeps the one whose transcript has the best mean token
log-probability, emitting a `language` event when it switches:

```rust
let sc = t.iter().map(|k| k.logp).sum::<f32>() / t.len() as f32;
if best.is_none_or(|b| sc > b.0) { best = Some((sc, t, l)); }
```

For short, quiet or unclear audio this readily picks `en`, because English is the
*shared block* of every pack. The app therefore pins a fixed language by default
and offers auto-detect as an explicit choice.

## 8. What was verified, and how

Without an arm64 device, the loader was exercised on x86-64 by building the same
engine source:

```
en-fa.tzf       -> loaded, chunk_ms=320, langs [en, fa]       peak ~85 MB
en-ru-uk.tzf    -> loaded, chunk_ms=320, langs [en, ru, uk]   peak ~93 MB
silero_vad.onnx -> byte-identical to upstream
whole-file pass on non-speech -> empty piece list (expected)
```

That is what ruled the packs out as a cause of the crash and pointed at the
JNI host dependency instead.
