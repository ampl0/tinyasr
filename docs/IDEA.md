# The idea, and how the pieces fit

## The idea

Telegram ships an offline speech recognizer on the phone. It is small, fast, has
punctuation and end-of-turn detection, and it runs without a network — which
means it is a perfectly good **on-device** recognizer, not just a Telegram
feature.

So: take the engine, drop the chat app, and wrap it in something that does the
four things people actually want from on-device speech:

1. **transcribe a file** (a meeting recording, a voice note someone sent you),
2. **record and transcribe** (a memo),
3. **live captions** while you talk,
4. **be the phone's dictation engine**, so every keyboard and app can use it.

That is this app. No account, no network permission, no audio leaving the device.

## Why it needed rebuilding rather than reusing

The obvious shortcut — keep Telegram's `libtmessages.so` and call its JNI entry
points — does not work: its `JNI_OnLoad` looks up methods on Telegram's own Java
classes, and a failed lookup aborts the process the moment the library is
loaded. It is a plugin of the Telegram client, not a library.

The engine underneath (`tinyasr`) is its own open project, so the honest route is
to build it from source and write a small bridge. That is what this repo does —
and it is why the app no longer contains a single line of Telegram code.

See [`FORENSIC.md`](FORENSIC.md) for how that was established, and
[`AGENT_GUIDE.md`](AGENT_GUIDE.md) to redo it on a new Telegram version.

## The moving parts

```
                     ┌──────────────────────────── Android app ───────────────────────────┐
  audio file ───────►│ MediaCodec / WAV reader ─┐                                        │
  microphone ───────►│ AudioRecord ─────────────┤→ 16 kHz mono f32                       │
  shared audio ─────►│ Share intent ────────────┘                                        │
                     │                                                                   │
                     │           engine thread (one, because the C API is not            │
                     │           thread-safe per object)                                 │
                     │                            │                                      │
                     │   libtinyasr_jni.so ───────┴─► tinyasr (Rust)                      │
                     │      • file pass  (full context, beam 4)                          │
                     │      • live session (320 ms chunks, greedy + re-decode)           │
                     │      • Silero VAD, end-of-turn head                               │
                     └───────────────────────────────────────────────────────────────────┘
                                            │
                     packs in filesDir/tinyasr/  (en-fa.tzf, en-ru-uk.tzf, silero_vad.onnx)
```

Two design choices carry most of the weight:

* **One engine thread.** The C API requires every call for a models object, its
  sessions and its files to come from the same thread. The app serialises
  everything through `Engine`, and the UI only ever posts jobs and receives
  callbacks. Live audio is read on a mic thread and handed over in 100 ms chunks,
  with a bounded queue so a slow pass drops audio instead of falling behind.

* **One resident pack.** A pack costs ~30–40 MB of weights; two at once is an
  OOM risk on mid-range phones. Handles are freed before the next load, and a
  crash marker makes a bad pack visible instead of fatal.

## The languages question

A pack with several languages makes the engine decode in *each* and keep the
most confident result — which costs one full pass per language and, on short or
unclear audio, very often settles on `en`, because English is the shared block
of every pack. The app therefore pins a language by default and exposes
auto-detect as an explicit choice.

## Honest limits

* Only `arm64-v8a`. The engine is built for that ABI.
* The EOU head, LID (`tinylid.bin`) and multi-language auto-detect are engine
  features the app uses lightly: auto-detect is opt-in and the phone-wide
  `RecognitionService` pins the first pack's language.
* Packs are Telegram documents. They are redistributed here for convenience;
  check your own obligations before doing the same.
