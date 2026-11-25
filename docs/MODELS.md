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
the client. Byte sizes below are **uncompressed**.

| File | Languages | Bytes | SHA-256 (first 16) |
|---|---|---|---|
| `en-fa.tzf` | en, fa | 34,619,127 | `35464d05b0f542c8` |
| `en-ru-uk.tzf` | en, ru, uk | 38,257,705 | `78ab8bfdf294829d` |
| `silero_vad.onnx` | — (VAD) | 2,327,524 | `1a153a22f4509e29` |
| `tinylid.bin` | — (language ID) | 4,428,852 | `36d47bdbbb0280e9` |

Upstream also publishes `ar`, `fr-de`, `en-es-it-pt`, `en-km`, `id-ms`, `uz`,
`zh`. `fa` and `en-ru-uk` are the ones this app bundles.

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
