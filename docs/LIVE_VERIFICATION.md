# Live-mode verification (local, no device)

Reproduction of the app's streaming path on the host, because "Live shows
nothing" needed a cause rather than a guess.

## Method

The streaming call sequence in the app is:

```
nativeSessionNew(models, langs, startLang, 0)
loop: nativeSessionPush(session, pcm_f32, 1600)     // 100 ms at 16 kHz
      400 ms of silence                            // let the utterance end
      nativeSessionFinish(session)
```

`docs/tools/livetest.c` does exactly that against the engine built from this
repo's pinned revision, reading raw `f32` 16 kHz mono at 1600 samples per push.

Speech was generated with `espeak-ng`, downsampled 22.05 kHz → 16 kHz mono with
`docs/tools/wav2f32.py` (the same conversion `MicCapture` +
`Engine.pcm16ToFloat` perform).

## Results

| Speech | Pack | `langs` arg | Text? |
|---|---|---|---|
| English 4.7 s | `en.tzf` | `en` | ✅ `…offline transcription and in running on a phone.` |
| English 1.8 s | `en.tzf` | `en` | ✅ `The loan is in the earth.` |
| English 4.7 s | `fa.tzf` | `en` | ✅ (pack's shared English block) |
| Persian 2.4 s | `fa.tzf` | `fa` | ✅ `سلامین یک آزمایش است.` |
| Persian 2.4 s | `fa.tzf` | `en,fa` (auto) | ✅ (stayed `fa`) |
| Spanish 1.9 s | `es.tzf` | `es` | ✅ `Toda Esto es la prueba.` |
| Spanish 1.9 s | `es.tzf` | `en,es,it,pt` (auto) | ✅ (stayed `es`) |
| Russian 5.5 s | `en.tzf` | `ru` | ❌ no text |
| Ukrainian | `en.tzf` | `uk` | ❌ no text |
| English 4.7 s | `en.tzf` | `ru` (deliberately wrong) | ❌ no text |

## How much audio is needed

`docs/tools/tailtest.c` sweeps the amount of speech fed into one session:

| Audio fed | Committed text? |
|---|---|
| 0.5 s | no |
| 1.0 s | yes (English), no (Spanish) |
| 1.5 s and longer | yes |

`docs/tools/dump.c` on 0.6 s of a real utterance shows the engine emitting
**nothing at all** — not even a `partial`. So a very short session cannot be
rescued by the app; it has to be spoken long enough for the engine to emit
anything.

## What this establishes

1. **The live pipeline is correct.** Text streams as provisional `partial`
   events while you speak, and lands as a `commit` event on `finish` — which is
   when you press **Stop live**. Nothing is dropped.
2. **The "no speech detected" symptom is a speech/language mismatch, not a bug**
   in the chunking, the thread, or the JSON merge. Pinning to a language the
   audio is not in reproduces exactly the observed result — `eou` fires,
   `partial` stays empty, no `commit`. English audio pinned to `ru` reproduces
   it in one command.
3. **It takes roughly a second of speech before anything comes out.** Under
   ~1 s the engine is silent, whatever the app does.

## App-side mitigations

`stopLive()` therefore now:

* pushes a **400 ms silence tail** before `finish`, so a session stopped
  mid-sentence still commits its last words;
* falls back to the **longest partial** seen if no `commit` ever arrived;
* reports **chunk count and the percentage of voiced chunks**, so an empty
  result says which of the three causes it was:
  * `…the microphone carried almost no audio` → mic problem,
  * `…that was under two seconds. Speak a full sentence.` → too short,
  * otherwise → language or content mismatch.

## Reproducing

```bash
cargo build --release --features c-api --target x86_64-unknown-linux-gnu
cc docs/tools/livetest.c target/release/libtinyasr.a -lpthread -ldl -lm -o livetest

espeak-ng -v en-us -s 145 -w en.wav "hello this is a test of the offline transcription engine"
python3 docs/tools/wav2f32.py en.wav en16.wav > en.f32
./livetest en.tzf silero_vad.onnx en.f32 en en
```

## What to check on a phone

Live only produces text when the engine hears speech **in the selected
language**:

* pick the language that matches what you will say (not `auto (detect)`);
* speak for at least a full sentence — a second or two minimum;
* watch the status line: `live — heard nothing yet` means the engine received no
  audio, while words appearing as you speak means it is working;
* the final text lands when you press **Stop live**, not before.
