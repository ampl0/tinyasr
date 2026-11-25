#!/usr/bin/env python3
"""Read a 16-bit PCM WAV, resample to 16 kHz mono, dump raw f32 samples.

Written to mirror what the app's MicCapture + Engine do: 16 kHz mono PCM,
converted to float in [-1, 1], pushed in 100 ms (1600-sample) chunks.
"""
import struct
import sys
import wave


def read_wav(path):
    w = wave.open(path, "rb")
    if w.getsampwidth() != 2:
        raise SystemExit("need 16-bit PCM")
    ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
    raw = w.readframes(n)
    w.close()
    samples = struct.unpack("<%dh" % (len(raw) // 2), raw)
    if ch > 1:  # downmix
        samples = [sum(samples[i:i + ch]) / ch for i in range(0, len(samples), ch)]
    return list(samples), rate


def resample(x, src, dst):
    if src == dst:
        return x
    n = int(len(x) * dst / src)
    out = []
    for i in range(n):
        pos = i * src / dst
        i0 = int(pos)
        i1 = min(i0 + 1, len(x) - 1)
        frac = pos - i0
        out.append(x[i0] * (1 - frac) + x[i1] * frac)
    return out


def main():
    src, dst_wav = sys.argv[1], sys.argv[2]
    samples, rate = read_wav(src)
    mono = resample(samples, rate, 16000)
    with wave.open(dst_wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(struct.pack("<%dh" % len(mono), *[int(max(-32768, min(32767, s))) for s in mono]))
    sys.stdout.buffer.write(struct.pack("<%df" % len(mono), *[s / 32768.0 for s in mono]))
    print(f"{len(mono)/16000:.2f}s -> {dst_wav}", file=sys.stderr)


if __name__ == "__main__":
    main()
