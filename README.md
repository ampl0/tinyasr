<div align="center">

# tinyasr

**Offline, on-device speech recognition for Android — no account, no network, nothing uploaded.**

Captions while you speak · transcribe any audio file · be the phone's dictation engine

[![build](https://github.com/ampl0/tinyasr/actions/workflows/build.yml/badge.svg)](https://github.com/ampl0/tinyasr/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/ampl0/tinyasr?label=release&color=2a6fdb)](https://github.com/ampl0/tinyasr/releases/latest)
[![downloads](https://img.shields.io/github/downloads/ampl0/tinyasr/total?color=2a6fdb)](https://github.com/ampl0/tinyasr/releases)
![platform](https://img.shields.io/badge/platform-Android%2023%2B%20arm64-3ddc84)
![engine](https://img.shields.io/badge/engine-tinyasr%20(Zipformer%20RNN--T)-b45)
![license](https://img.shields.io/badge/engine%20license-GPL--3.0-blue)
![offline](https://img.shields.io/badge/network-none-critical)

</div>

---

## Download

Grab a signed APK from [**Releases**](https://github.com/ampl0/tinyasr/releases/latest):

| Build | Size | Models | For |
|---|---|---|---|
| `tinyasr-core-debug.apk` | ~0.5 MB | import your own | a small install, or several packs |
| `tinyasr-full-debug.apk` | ~73 MB | `en-fa` + `en-ru-uk` + VAD bundled | just work |

*Android 6.0+ (API 23), **arm64-v8a**.* Install, grant the microphone when asked,
and either use the bundled packs (`full`) or tap **Import models** (`core`) and
pick `*.tzf` files plus `silero_vad.onnx`.

## What it does

| Feature | |
|---|---|
| 🎧 **Audio file** | Any file the phone decodes — m4a, mp3, ogg, wav, flac, 3gp… Transcribed with the full-context pass (best punctuation). |
| 🎙️ **Record** | Capture a note, then transcribe it the same way. |
| ⚡ **Live captions** | Engines' streaming mode: committed words stay, the not-yet-final tail is shown in the accent colour and replaced as it firms up. |
| 📤 **Share to tinyasr** | *Share* or *Open with* an `audio/*` file from any app and it transcribes immediately. |
| 🗣️ **Phone speech engine** | Registers an `android.speech.RecognitionService`: select it once and every keyboard, search box and app dictates offline through it. |
| 🔒 **Private by construction** | No `INTERNET` permission. Audio and text never leave the device. |
| 💾 **Portable results** | Copy, save a `.txt`, or share through the system sheet. |

## Screenshots

<!-- Add files to docs/screenshots/ and uncomment:
<p align="center">
  <img src="docs/screenshots/main.png" width="30%" alt="main screen" />
  <img src="docs/screenshots/live.png" width="30%" alt="live captions" />
</p>
-->

## Documentation

| Document | What's in it |
|---|---|
| [`docs/IDEA.md`](docs/IDEA.md) | The idea, the architecture, and the design decisions behind it |
| [`docs/FORENSIC.md`](docs/FORENSIC.md) | How Telegram's on-device transcription was reverse-engineered, step by step |
| [`docs/MODELS.md`](docs/MODELS.md) | Engine and pack reference: sizes, SHA-256, TZF container, architecture |
| [`docs/AGENT_GUIDE.md`](docs/AGENT_GUIDE.md) | Checklist to reproduce all of this on a future Telegram version |
| [`docs/download_models.py`](docs/download_models.py) | Fetch the packs from Telegram over MTProto |

## How it works

The recognizer is [`dkaraush/tinyasr`](https://github.com/dkaraush/tinyasr) — a
streaming **Zipformer RNN-T / TDT** model with a **Silero VAD** front end and an
end-of-turn head — built from source for Android and wrapped by a small JNI
bridge (`app/src/main/cpp/tinyasr_jni.c`):

```
file  → MediaCodec → 16 kHz mono f32 ─┐
mic   → AudioRecord ──────────────────┤→ engine thread ─→ tinyasr ─→ text
share → content:// ───────────────────┘        (single thread: the C API
                                                is not thread-safe per object)
```

* **One engine thread.** Every native call for a models handle runs there; the UI
  posts jobs and receives callbacks. Live audio is captured on a separate thread
  and handed over in 100 ms chunks with a bounded queue.
* **One resident pack** (~30–40 MB), freed before the next load.
* **Big-core pinning** and the engine's own worker threads, as its header
  recommends (~1.5×).

> **Not Telegram's binary.** Reusing `libtmessages.so` cannot work: its
> `JNI_OnLoad` resolves method IDs on Telegram's own Java classes and aborts the
> process in any other app. The cause, the evidence, and the fix are in
> [`docs/FORENSIC.md`](docs/FORENSIC.md) §6.

## Build from source

```bash
# 1. engine -> static lib + packs (needs git, gzip, rustup)
rustup target add aarch64-linux-android
scripts/build-engine.sh

# 2. app (JDK 17+, Android SDK 34, NDK 27, CMake 3.22.1)
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug        # -> app/build/outputs/apk/{core,full}/debug/
```

`core` ships the engine only (`dev.tinyasr.app.core`, so it installs alongside
`full`); `full` bundles `en-fa`, `en-ru-uk` and `silero_vad.onnx`. Both are
guarded by `scripts/check-layout.sh` and Android Lint (0 errors) in CI —
`.github/workflows/build.yml` builds both APKs on every push.

## Getting the models

Ready-made packs are attached to [Releases](https://github.com/ampl0/tinyasr/releases/latest). To fetch
them yourself, with your own Telegram account:

```bash
export TG_API_ID=12345 TG_API_HASH=...      # https://my.telegram.org
python3 docs/download_models.py --list
python3 docs/download_models.py --lang en,fa
```

Verify against the SHA-256 values in [`docs/MODELS.md`](docs/MODELS.md).

## Crash recovery

A native crash can't be caught in Java, so the app is built to survive one:
nothing loads at startup; one pack at a time; an `inflight` marker is written
with `commit()` before every native call, so a marker left behind proves the
previous process died inside the engine and (after two) enables **safe mode**.
**Remove models** clears the packs; **Copy log** puts `tinyasr.log` — including
the native `tinyasr_load: <reason>` line — on the clipboard.

## Keywords

`android` · `speech-recognition` · `speech-to-text` · `asr` · `stt` ·
`offline` · `on-device` · `privacy` · `no-internet` · `transcription` ·
`dictation` · `voice-typing` · `zipformer` · `rnnt` · `tdt` · `silero-vad` ·
`rust` · `jni` · `arm64` · `recognition-service` · `telegram-tinyasr`

## Legal

* **Engine** — [`dkaraush/tinyasr`](https://github.com/dkaraush/tinyasr),
  **GPL-3.0-or-later**. The prebuilt static library and the sources under
  `third_party/tinyasr/` are that work; distributing this app means complying
  with the GPL. See [`NOTICE`](NOTICE).
* **Model packs** — Telegram documents, redistributed here for convenience.
  Check your own obligations.
* No Telegram code ships in the app.

## Contributing

Issues and PRs welcome — especially reports from real devices (model, Android
version, and the `Copy log` output make a fix much faster). If you add a
language pack or a UI language, mention it in the PR.
