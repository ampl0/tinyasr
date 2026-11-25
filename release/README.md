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
| `en-fa.tzf`, `en-ru-uk.tzf` | uncompressed packs, for `core` users |
| `silero_vad.onnx` | required by the engine |
| `checksums.txt` | SHA-256 of the files above |

Pack sizes and hashes are documented in [`docs/MODELS.md`](../docs/MODELS.md).

## A note on the packs

The packs are Telegram documents ("transcription models"), served over MTProto.
They are attached here so the app is usable without a Telegram account. If you
run the release yourself, decide whether that is acceptable for your audience;
the alternative is to ship only
[`docs/download_models.py`](../docs/download_models.py) and have users fetch
them with their own account.
