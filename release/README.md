# Release asset notes

The scripts under `release/` build and attach a release. Nothing here is needed
to build the app.

```
release/make-release.sh    # builds both APKs, signs them, uploads assets
release/checksums.txt      # SHA-256 of every published file (generated)
```

## What a release contains

| Asset | Notes |
|---|---|
| `tinyasr-core-debug.apk` | engine only; import packs yourself |
| `tinyasr-full-debug.apk` | engine + `en-fa`, `en-ru-uk`, `silero_vad.onnx` |
| `en.tzf` (`en-ru-uk`) | en, ru, uk |
| `es.tzf` (`en-es-it-pt`) | en, es, it, pt |
| `fa.tzf` (`en-fa`) | en, fa |
| `uz.tzf` (`en-ru-uz`) | en, ru, uz |
| `km.tzf` (`en-km`) | en, km |
| `silero_vad.onnx` | required by the engine |
| `tinylid.bin` | optional, Telegram's language ID |
| `checksums.txt` | SHA-256 of the files above |

Every language pack carries English as its first language, so a `core` install
needs only one `*.tzf` plus `silero_vad.onnx` to be useful. Pack sizes and
hashes are documented in [`docs/MODELS.md`](../docs/MODELS.md).

## A note on the packs

The packs are Telegram documents ("transcription models"), served over MTProto.
They are attached here so the app is usable without a Telegram account. If you
run the release yourself, decide whether that is acceptable for your audience;
the alternative is to ship only
[`docs/download_models.py`](../docs/download_models.py) and have users fetch
them with their own account.
