# Engine and model reference

Sizes, hashes, architecture, and exactly what each file is.

---

## The engine

| | |
|---|---|
| Name | `tinyasr` |
| Upstream | <https://github.com/dkaraush/tinyasr> |
| License | GPL-3.0-or-later |
| Language | Rust (`edition 2024`), no dependencies, `panic = "abort"` |
| Architecture | Zipformer-style streaming transducer, RNN-T / TDT, with an end-of-turn (EOU) head |
| Front end | 80-bin log-mel fbank, 16 kHz mono |
| VAD | Silero VAD v5, ONNX, run in-process |
| Chunk | 320 ms (16 encoder frames x 20 ms); streaming text is always greedy |
| Full-context | beam search, default width 4; used by the silence-aware re-decode and by whole-file mode |
| Threading | the bigger steps split over N threads, **bit-identical for any N** |
| Pinned revision | `e034289132667fa8e87dd8d474a4dc59d447b2dc` |
| C ABI | `include/tinyasr.h`, enabled with `--features c-api` |
| Android artifacts | `aarch64-linux-android` static lib → `app/src/main/cpp/prebuilt/arm64-v8a/libtinyasr.a` (27 MB) |

### Interface (what the app calls)

```
tinyasr_load(asr, asr_len, vad, vad_len, err, err_len) -> models*   // NULL + err on failure
tinyasr_free, tinyasr_version, tinyasr_chunk_ms
tinyasr_lang_count, tinyasr_lang
tinyasr_set_threads, tinyasr_set_parallel_for
tinyasr_session_new/push/finish/lang/free        // live
tinyasr_file_new/next/lang/progress/free         // whole recording
```

Events come back as JSON:

```json
[{"type":"commit","words":[{"text":"Hello,","start":0.52,"end":0.90}]}]
[{"type":"partial","open":[...],"tail":[...]}]
[{"type":"language","lang":"uk"}]
[{"type":"eou","at":3.1,"p":0.93}]
```

`join: true` on a word means "no space before it" (Khmer syllables, Chinese
characters).

---

## The model packs

All packs are Telegram documents, distributed gzip-compressed and unpacked by
the client. Byte sizes below are **uncompressed**. `tinyasr` reads both the
older **TZF2** and the newer **TZF3** generations, so every pack below works.

The current catalogue (`messages.getTranscriptionModels`, version 1) lists
**7 documents** — 5 language packs plus the VAD and the language-ID model:

| File | Languages | Bytes | Generation | SHA-256 |
|---|---|---|---|---|
| `en.tzf` (`en-ru-uk`) | en, ru, uk | 35,860,640 | TZF2 | `3316a3f320c1335b88b9e6707e954b1a3ec184cb8b24d55f14cbe6ab2dd467ed` |
| `es.tzf` (`en-es-it-pt`) | en, es, it, pt | 36,923,517 | TZF2 | `98d3abe71079ee4362ec616306937c6b06142e060aad32554ab7fa59a76d8e71` |
| `fa.tzf` (`en-fa`) | en, fa | 34,619,123 | TZF2 | `9611cbc97a151a205038d7dbb8062d1ad0c7844084706cd4eed804db2305422b` |
| `uz.tzf` (`en-ru-uz`) | en, ru, uz | 35,686,768 | TZF2 | `167e9a0e9ad389e6688bf7ac554d73c7e0c345c8df71b9c16fdf98d01748a563` |
| `km.tzf` (`en-km`) | en, km | 34,618,278 | TZF2 | `611c01616d7fe705c6e365fd4fd44b3077f30db12c169d287b1a6d54c193797b` |
| `silero_vad.onnx` | — (VAD, required) | 2,327,524 | — | `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` |
| `tinylid.bin` | — (language ID) | 4,428,852 | `TLID` | `36d47bdbbb0280e9eb2c73342445dd6b89707ee768d3edd32b5c07275698fd27` |

Every pack was verified by loading it into the engine built from this repo's
pinned revision; all five report `chunk_ms=320` and the expected language lists.

Upstream `dkaraush/tinyasr` also publishes newer **TZF3** generations of some of
these (`ar`, `fr-de`, `id-ms`, `zh`, and refreshed `en-fa` / `en-ru-uk`). Both
generations load; the packs above are the ones Telegram currently serves.

Each language pack contains **English** as its first language (the shared
token block), which is why auto-detect so readily lands on `en`.

### Container: TZF

Little-endian, self-describing, verifiable in ~40 lines of Python.

```
"TZF" + generation byte
u32 n_settings     ; n x (u16 key_len, key, i32 value)
u32 n_text         ; n x (u16 key_len, key, u16 val_len, val)   -- TZF3+
u32 n_tensors      ; per tensor: name, integer flag, dims[], and either f32
                   ; data or int4/int8 codes grouped with per-group scales
u32 n_pieces       ; n x (u16 len, piece)   -- the token inventory
```

Settings worth reading:

| Key | Meaning |
|---|---|
| `n_langs` | how many languages the pack carries |
| `lang_size` | per-language token block size (1024 typical) |
| `shared` | size of the shared prefix block (English lives here) |
| `vocab` | `shared + n_langs * lang_size` at most |
| `chunk`, `left_context` | streaming geometry |
| `n_stacks`, `encoder_dim.N`, `num_heads.N`, `downsampling_factor.N` | the Zipformer stack |

Language codes and writing rules come from `lang.K`, `lang.K.full_stop`,
`lang.K.own`, `lang.K.units`, `lang.K.ends` (TZF3+). **TZF2** packs instead use a
fixed table (`en ru uk es it pt uz fa km`) and carry no text settings; they still
load.

### Why "en" is always first

Every pack's first token block is the **shared English block**, so a mixed-audio
input has a path to English text. That is why two-language auto-detection so
readily lands on `en`, and why the app pins a language by default.

### Memory

A resident pack is roughly `30-40 MB` of weights plus working buffers; peak RSS
while loading `en-ru-uk` measured at **~93 MB** on x86-64. The app therefore
keeps exactly **one** pack loaded at a time.

---

## Audio path in the app

```
file  → MediaExtractor/MediaCodec (or a direct WAV reader) → 16 kHz mono float
mic   → AudioRecord 16 kHz PCM16 → float → 100 ms chunks → session_push
```

The engine wants mono `f32` in `[-1, 1]` at exactly 16 kHz; everything else is
converted (linear resampler, downmix).
