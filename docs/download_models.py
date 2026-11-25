#!/usr/bin/env python3
"""Fetch Telegram's on-device transcription packs (*.tzf) over MTProto.

Telegram 13.3 runs a Rust engine ("tinyasr") on per-language model packs. Those
packs are *not* in the APK and have no HTTPS URL: they are ordinary Telegram
documents that the catalogue method hands out, and the bytes come from
`upload.getFile`. So the only thing standing between you and `fa.tzf` is an
authorized account session.

Two RPCs are used (schema layer 234, newer than what Pyrogram 2.x generates, so
they are declared below as raw TL objects and registered with Pyrogram's
constructor table):

    transcriptionModel#00c16f15 document:Document languages:Vector<string> version:int = TranscriptionModel;
    messages.getTranscriptionModels#081f6321 version:int = Vector<TranscriptionModel>;

Usage
-----
    # credentials: environment only, never written by this script
    export TG_API_ID=12345            # https://my.telegram.org -> App api_id
    export TG_API_HASH=0123abcd...    # same page -> App api_hash
    # session: a local file (default: ./tg_tzf.session, created on first run)
    #          or a ready-made string in TG_SESSION_STRING

    python3 fetch_tzf.py --list                 # every pack: languages + size
    python3 fetch_tzf.py                        # download fa -> models/fa.tzf
    python3 fetch_tzf.py --lang fa,uz --out-dir models

The first run asks for the phone number, the login code and the 2FA password
interactively (that is Pyrogram's normal login); later runs reuse the session
file. Keep the session file private: it is a full account credential.

Output is written as `<primary-language>.tzf` (e.g. fa.tzf), the same name and
container the Android client uses. Packs arrive gzip-compressed and are
decompressed here, exactly like the client's FileLoader path does.
"""

from __future__ import annotations

import argparse
import asyncio
import gzip
import os
import sys
from contextlib import suppress
from io import BytesIO
from pathlib import Path
from typing import List, Optional

try:
    from pyrogram import Client, raw
    from pyrogram.errors import FileMigrate, FileReferenceExpired, FloodWait, RPCError
    from pyrogram.methods.messages.inline_session import get_session
    from pyrogram.raw.all import objects
    from pyrogram.raw.core import TLObject
    from pyrogram.raw.core.primitives import Int, String
except ImportError as exc:  # pragma: no cover
    sys.exit(f"pyrogram is required (python3 -m pip install 'pyrogram>=2.0'): {exc}")

# --- TL definitions missing from the generated Pyrogram API ------------------

TRANSCRIPTION_MODEL_ID = 0x00C16F15
GET_TRANSCRIPTION_MODELS_ID = 0x081F6321
VECTOR_ID = 0x1CB5C415


class TranscriptionModel(TLObject):
    """transcriptionModel#00c16f15 document languages version = TranscriptionModel"""

    __slots__ = ["document", "languages", "version"]

    ID = TRANSCRIPTION_MODEL_ID
    QUALNAME = "types.TranscriptionModel"

    def __init__(self, document, languages, version):
        self.document = document
        self.languages = languages or []
        self.version = version

    @staticmethod
    def read(b: BytesIO, *args) -> "TranscriptionModel":
        document = TLObject.read(b)
        # Vector<string>: elements are bare TL strings, not boxed objects, so
        # Vector.read() (which expects a constructor id per element) cannot be
        # used here.
        if Int.read(b) != VECTOR_ID:
            raise ValueError("expected a Vector for languages")
        count = Int.read(b)
        languages = [String.read(b) for _ in range(count)]
        return TranscriptionModel(
            document=document, languages=languages, version=Int.read(b))

    def write(self, *args) -> bytes:
        b = BytesIO()
        b.write(Int(self.ID, False))
        b.write(self.document.write())
        b.write(Int(VECTOR_ID, False))
        b.write(Int(len(self.languages)))
        for language in self.languages:
            b.write(String(language))
        b.write(Int(self.version))
        return b.getvalue()


class GetTranscriptionModels(TLObject):
    """messages.getTranscriptionModels#081f6321 version:int = Vector<TranscriptionModel>"""

    __slots__ = ["version"]

    ID = GET_TRANSCRIPTION_MODELS_ID
    QUALNAME = "functions.messages.GetTranscriptionModels"

    def __init__(self, version: int = 0):
        self.version = version

    @staticmethod
    def read(b: BytesIO, *args) -> "GetTranscriptionModels":
        return GetTranscriptionModels(version=Int.read(b))

    def write(self, *args) -> bytes:
        b = BytesIO()
        b.write(Int(self.ID, False))
        b.write(Int(self.version))
        return b.getvalue()


# Pyrogram resolves constructor ids through this table when it parses a
# response, and the generated schema does not know 0x00c16f15.
objects[TRANSCRIPTION_MODEL_ID] = TranscriptionModel


# --- helpers -----------------------------------------------------------------


class FileReferenceExpiredError(RuntimeError):
    """A file_reference went stale; the catalogue has to be fetched again."""


def _env(*names: str) -> Optional[str]:
    for name in names:
        value = os.environ.get(name)
        if value:
            return value.strip()
    return None


def _env_int(*names: str) -> Optional[int]:
    raw_value = _env(*names)
    if raw_value is None:
        return None
    try:
        return int(raw_value)
    except ValueError:
        sys.exit(f"environment variable {names[0]} must be an integer, got {raw_value!r}")


def human_size(size: int) -> str:
    return f"{size / (1024 * 1024):.1f} MiB" if size else "?"


def pack_name(model: TranscriptionModel) -> str:
    """Client naming: the pack is stored as <language>.tzf (first language)."""
    return model.languages[0] if model.languages else str(model.document.id)


def describe(model: TranscriptionModel) -> str:
    doc = model.document
    files = [a.file_name for a in (doc.attributes or [])
             if isinstance(a, raw.types.DocumentAttributeFilename)]
    return (
        f"{pack_name(model):<10} v{model.version:<4} "
        f"{human_size(doc.size):>9}  "
        f"langs={','.join(model.languages) or '-':<20} "
        f"id={doc.id} dc={doc.dc_id} mime={doc.mime_type}"
        + (f" file={files[0]}" if files else "")
    )


def pick_for(models: List[TranscriptionModel], lang: str) -> Optional[TranscriptionModel]:
    """A pack can cover several languages; take the biggest one that lists lang."""
    lang = lang.lower()
    matches = [m for m in models if lang in [l.lower() for l in m.languages]]
    return max(matches, key=lambda m: int(m.document.size or 0)) if matches else None


COMPANION_ASSETS = {"tinylid", "silero-vad", "silero_vad"}


def output_name(model: TranscriptionModel, lang: str) -> str:
    """Language packs are saved as <lang>.tzf; companion assets keep their own
    name (tinylid.bin, silero_vad.onnx) because the engine loads them by path."""
    if lang in COMPANION_ASSETS:
        for attr in (model.document.attributes or []):
            if isinstance(attr, raw.types.DocumentAttributeFilename) and attr.file_name:
                name = attr.file_name
                return name[:-3] if name.endswith(".gz") else name
    return f"{lang}.tzf"


def _models_from(result) -> List[TranscriptionModel]:
    if result is None:
        return []
    models = result if isinstance(result, list) else [result]
    return [m for m in models if isinstance(m, TranscriptionModel) and m.document is not None]


async def fetch_catalogue(client: Client, version: int = 0) -> List[TranscriptionModel]:
    models = _models_from(await client.invoke(GetTranscriptionModels(version)))
    if not models and version != 1:
        # The server reads version as a catalogue hash, not as "give me
        # everything": 0 comes back as an empty vector, while the first real
        # hash (1) returns the full list.
        models = _models_from(await client.invoke(GetTranscriptionModels(1)))
    return models


async def download_document(client: Client, document, dest: Path,
                            chunk_size: int = 512 * 1024) -> int:
    """Mirror of FileLoader/upload.getFile: fixed-offset chunk reads."""
    location = raw.types.InputDocumentFileLocation(
        id=document.id,
        access_hash=document.access_hash,
        file_reference=document.file_reference,
        thumb_size="",
    )
    total = int(document.size or 0)
    written = 0
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    # The pack usually lives on another DC than the account's home DC; a raw
    # upload.getFile does not migrate by itself, so follow FILE_MIGRATE_X onto
    # an exported media session for that DC (what Pyrogram's downloader does).
    sender = client

    with open(tmp, "wb") as fh:
        while total == 0 or written < total:
            # Ask for a full aligned chunk even at EOF: upload.getFile requires
            # limit % 4096 == 0, and a gzip document's size need not be aligned.
            limit = chunk_size
            try:
                part = await sender.invoke(raw.functions.upload.GetFile(
                    location=location, offset=written, limit=limit))
            except FloodWait as exc:
                await asyncio.sleep(exc.value + 1)
                continue
            except FileMigrate as exc:
                print(f"\n  file lives on DC{exc.value}, switching…", file=sys.stderr)
                sender = await get_session(client, exc.value)
                continue
            except (FileReferenceExpired, RPCError) as exc:
                if isinstance(exc, FileReferenceExpired) or "FILE_REFERENCE" in str(exc):
                    raise FileReferenceExpiredError(str(exc)) from exc
                raise

            data = getattr(part, "bytes", None)
            if data is None:
                raise RuntimeError(
                    f"unexpected {type(part).__name__} for offset {written} "
                    "(CDN redirect is not handled)")
            if not data:
                break
            fh.write(data)
            written += len(data)
            if total:
                pct = 100.0 * written / total
                print(f"\r  {written / (1024 * 1024):8.2f} / "
                      f"{total / (1024 * 1024):.2f} MiB  {pct:5.1f}%",
                      end="", file=sys.stderr, flush=True)
            if len(data) < limit:
                break
        if total and written != total:
            tmp.unlink(missing_ok=True)
            raise RuntimeError(f"short read: got {written} of {total} bytes")

    if total:
        print("", file=sys.stderr)
    tmp.replace(dest)
    return written


def unpack(src: Path, dest: Path, expect_tzf: bool = True) -> bytes:
    """The client gzips in transit; strip that before saving the model file."""
    data = src.read_bytes()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    dest.write_bytes(data)
    src.unlink(missing_ok=True)
    if expect_tzf and data[:4] not in (b"TZF1", b"TZF2", b"TZF3"):
        print(f"warning: {dest.name} does not start with a TZF magic "
              f"(got {data[:4]!r}); the engine would reject it", file=sys.stderr)
    return data


def build_client(session_override: Optional[str]) -> Client:
    api_id = _env_int("TG_API_ID", "TELEGRAM_API_ID")
    api_hash = _env("TG_API_HASH", "TELEGRAM_API_HASH")
    if api_id is None or api_hash is None:
        sys.exit(
            "missing credentials.\n"
            "  Create an app at https://my.telegram.org (API development tools),\n"
            "  then export TG_API_ID and TG_API_HASH in your shell. Nothing is\n"
            "  stored by this script; the login session lives in the session file."
        )

    session_string = _env("TG_SESSION_STRING")
    if session_string:
        print("session: TG_SESSION_STRING", file=sys.stderr)
        return Client("tg_tzf", api_id=api_id, api_hash=api_hash,
                      session_string=session_string, in_memory=True)

    # Pyrogram appends ".session" itself, so split the path into name + workdir.
    path = Path(session_override or _env("TG_SESSION") or "tg_tzf").expanduser()
    if path.suffix == ".session":
        path = path.with_suffix("")
    name = path.name or "tg_tzf"
    workdir = str(path.parent or ".")
    session_file = Path(workdir) / f"{name}.session"
    if not session_file.exists():
        print(f"first run: logging in, session will be saved to {session_file}",
              file=sys.stderr)
    print(f"session: {session_file}", file=sys.stderr)
    return Client(name, api_id=api_id, api_hash=api_hash, workdir=workdir)


async def run(args) -> int:
    wanted = [lang.strip().lower() for lang in args.lang.split(",") if lang.strip()]
    client = build_client(args.session)
    await client.start()  # prompts for phone / code / 2FA only when needed

    try:
        me = client.me or await client.get_me()

        models = await fetch_catalogue(client, args.version)
        if not models:
            print("catalogue is empty for this account/build", file=sys.stderr)
            return 1

        if args.list:
            print(f"{len(models)} pack(s):\n")
            for model in models:
                print(describe(model))
            return 0

        out_dir = Path(args.out_dir)
        rc = 0
        for lang in wanted:
            model = pick_for(models, lang)
            if model is None:
                available = sorted({l for m in models for l in m.languages})
                print(f"no pack for {lang!r}; catalogue has: {', '.join(available)}",
                      file=sys.stderr)
                rc = 1
                continue

            # Named after the language you asked for, like the client's <lang>.tzf;
            # companion assets (tinylid, silero-vad) keep their document name.
            dest = out_dir / output_name(model, lang)
            print(f"{lang}: {describe(model)}\n  -> {dest}", file=sys.stderr)

            compressed = dest.parent / (dest.name + ".gz")
            compressed_size = 0
            for attempt in (1, 2):
                try:
                    compressed_size = await download_document(
                        client, model.document, compressed)
                    break
                except FileReferenceExpiredError:
                    if attempt == 2:
                        raise
                    print("file_reference expired, refreshing catalogue…", file=sys.stderr)
                    models = await fetch_catalogue(client, args.version)
                    refreshed = pick_for(models, lang)
                    if refreshed is None:
                        raise
                    model = refreshed

            unpack(compressed, dest, expect_tzf=dest.suffix == ".tzf")
            print(f"  saved {dest} ({dest.stat().st_size} bytes on disk, "
                  f"{compressed_size} compressed as transferred)", file=sys.stderr)

        print("\ncatalogue:", file=sys.stderr)
        for model in models:
            print(describe(model), file=sys.stderr)
        return rc
    finally:
        with suppress(Exception):
            await client.stop()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Fetch Telegram's on-device transcription packs (*.tzf) via MTProto (Pyrogram).",
        epilog="Credentials come from TG_API_ID / TG_API_HASH (or TELEGRAM_API_ID / "
               "TELEGRAM_API_HASH); the session from TG_SESSION (path, default "
               "./tg_tzf.session) or TG_SESSION_STRING.",
    )
    parser.add_argument("--list", action="store_true",
                        help="only print the catalogue (languages, size, id)")
    parser.add_argument("--lang", default="fa",
                        help="comma-separated language codes to download (default: fa)")
    parser.add_argument("--out-dir", default=str(Path(__file__).resolve().parent / "models"),
                        help="where to write <lang>.tzf (default: ./models next to this script)")
    parser.add_argument("--version", type=int, default=0,
                        help="catalogue hash to revalidate (default 0; falls back "
                             "to hash 1 when the server returns an empty list)")
    parser.add_argument("--session", default=None,
                        help="session file path (overrides TG_SESSION)")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
