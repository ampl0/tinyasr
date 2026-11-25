package dev.tinyasr.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Whole-file transcription: decode any container the platform understands to
 * 16 kHz mono float PCM, then run the engine's full-context pass piece by
 * piece.
 *
 * Must be called on the engine thread. The pack must already be resident.
 */
public final class Transcriber {

    private static final String TAG = "tinyasr";
    private static final int RATE = 16000;
    private static final int MAX_SECONDS = 30 * 60;

    public static final class Result {
        public final String text;
        public final String detectedLang;
        public final long decodeMs;
        public final long runMs;

        Result(String text, String detectedLang, long decodeMs, long runMs) {
            this.text = text;
            this.detectedLang = detectedLang;
            this.decodeMs = decodeMs;
            this.runMs = runMs;
        }
    }

    private Transcriber() {
    }

    /** Telegram picks 2 threads on low-end devices and 4 otherwise. */
    public static int threadCount() {
        return Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2;
    }

    /** Runs on the engine thread. */
    public static Result transcribe(long models, File audio, String lang) throws Exception {
        long t0 = System.currentTimeMillis();
        float[] pcm = decode(audio);
        long t1 = System.currentTimeMillis();
        if (pcm.length == 0) {
            throw new IllegalStateException("could not decode " + audio.getName());
        }
        if (pcm.length > (long) MAX_SECONDS * RATE) {
            throw new IllegalStateException("audio is longer than 30 minutes — split it first");
        }

        // Pins this (engine) thread to the big cores; the engine's worker
        // threads inherit the affinity from whoever starts them.
        TinyAsr.nativePinFast();

        long fh = TinyAsr.nativeFileNew(models, pcm, pcm.length, lang, 0);
        if (fh == 0) {
            throw new IllegalStateException("the engine rejected language '" + lang + "'");
        }

        StringBuilder sb = new StringBuilder();
        try {
            String chunk;
            while ((chunk = TinyAsr.nativeFileNext(fh)) != null) {
                appendChunk(sb, chunk);
            }
            String detected = TinyAsr.nativeFileLang(fh);
            return new Result(sb.toString(), detected, t1 - t0, System.currentTimeMillis() - t1);
        } finally {
            TinyAsr.nativeFileFree(fh);
        }
    }

    /** Same merge rule as Telegram's DictationAsr.n(). */
    static void appendChunk(StringBuilder sb, String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String text = o.optString("text", "");
                if (text.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0 && !o.optBoolean("join", false)) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        } catch (Exception e) {
            Log.w(TAG, "unparsable chunk: " + json, e);
        }
    }

    // --- decoding ------------------------------------------------------------

    /**
     * Anything the platform can decode to 16 kHz mono float. MediaExtractor
     * handles the container and MediaCodec the codec; WAV is read directly.
     */
    /** The sniffed input format; everything is converted to 16 kHz mono float. */
    private static final class Format {
        int sampleRate = RATE;
        boolean isFloat;
    }

    public static float[] decode(File audio) throws Exception {
        byte[] head = new byte[12];
        try (FileInputStream in = new FileInputStream(audio)) {
            int n = in.read(head);
            if (n >= 4 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
                return readWav(audio);
            }
        }
        return decodeWithPlatform(audio);
    }

    private static float[] readWav(File audio) throws Exception {
        try (FileInputStream in = new FileInputStream(audio)) {
            int size = (int) Math.min(audio.length(), 1 << 30);
            byte[] all = new byte[size];
            int off = 0;
            while (off < all.length) {
                int n = in.read(all, off, all.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            int p = 12;
            int dataOff = -1;
            int dataLen = 0;
            int channels = 1;
            int bits = 16;
            int rate = RATE;
            boolean isFloat = false;
            while (p + 8 <= off) {
                int id = fourcc(all, p);
                int chunk = le32(all, p + 4);
                if (id == fourcc("fmt ")) {
                    int fmt = le16(all, p + 8);
                    channels = le16(all, p + 10);
                    rate = le32(all, p + 12);
                    bits = le16(all, p + 22);
                    isFloat = fmt == 3;
                    if (!isFloat && fmt != 1) {
                        return decodeWithPlatform(audio); // compressed WAV
                    }
                } else if (id == fourcc("data")) {
                    dataOff = p + 8;
                    dataLen = chunk < 0 ? off - dataOff : Math.min(chunk, off - dataOff);
                }
                p += 8 + chunk + (chunk & 1);
            }
            if (dataOff < 0 || channels < 1) {
                return decodeWithPlatform(audio);
            }
            float[] mono = new float[dataLen / ((bits / 8) * channels)];
            ByteBuffer bb = ByteBuffer.wrap(all, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < mono.length; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    if (bits == 16) {
                        sum += bb.getShort() / 32768f;
                    } else if (bits == 32) {
                        sum += isFloat ? bb.getFloat() : bb.getInt() / 2147483648f;
                    } else if (bits == 8) {
                        sum += ((bb.get() & 0xff) - 128) / 128f;
                    } else {
                        return decodeWithPlatform(audio);
                    }
                }
                mono[i] = sum / channels;
            }
            return resampleLinear(mono, rate, RATE);
        }
    }

    private static float[] decodeWithPlatform(File audio) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(audio.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new IllegalStateException("no audio track in " + audio.getName());
            }
            ex.selectTrack(track);

            MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            try {
                try {
                    codec.configure(format, null, null, 0);
                } catch (MediaCodec.CodecException e) {
                    // Some non-PCM WAVs are reported as audio/raw and refuse to decode here.
                    throw new IllegalStateException("cannot decode " + audio.getName() + " (" + e.getMessage() + ")");
                }
                codec.start();

                ByteArraySink sink = new ByteArraySink();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                while (!outputDone) {
                    if (!inputDone) {
                        int inIndex = codec.dequeueInputBuffer(10000);
                        if (inIndex >= 0) {
                            ByteBuffer buf = codec.getInputBuffer(inIndex);
                            int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int outIndex = codec.dequeueOutputBuffer(info, 10000);
                    if (outIndex >= 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        if (out != null && info.size > 0) {
                            sink.write(out, info.offset, info.size);
                        }
                        codec.releaseOutputBuffer(outIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore
                    }
                }
                return resamplePcm(sink.bytes(), sink.length(), format);
            } finally {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // ignore
                }
                codec.release();
            }
        } finally {
            ex.release();
        }
    }

    private static int sampleRate(MediaFormat format) {
        try {
            return format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        } catch (Exception e) {
            return RATE;
        }
    }

    private static String mime(MediaFormat format) {
        try {
            return format.getString(MediaFormat.KEY_MIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts the decoder's output (PCM16 or float PCM) to 16 kHz mono float. */
    private static float[] resamplePcm(byte[] bytes, int length, MediaFormat format) {
        String mime = mime(format);
        boolean isFloat = mime != null && ("audio/raw".equals(mime) || mime.contains("float"));
        float[] mono;
        if (isFloat && length >= 4) {
            int n = length / 4;
            mono = new float[n];
            ByteBuffer bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                mono[i] = bb.getFloat();
            }
        } else {
            int n = length / 2;
            mono = new float[n];
            ByteBuffer bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                mono[i] = bb.getShort() / 32768f;
            }
        }
        return resampleLinear(mono, sampleRate(format), RATE);
    }

    private static float[] resampleLinear(float[] in, int from, int to) {
        if (from == to || from <= 0) {
            return in;
        }
        int n = (int) ((long) in.length * to / from);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double pos = (double) i * from / to;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    private static int fourcc(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int fourcc(String s) {
        return (s.charAt(0) & 0xff) | ((s.charAt(1) & 0xff) << 8)
                | ((s.charAt(2) & 0xff) << 16) | ((s.charAt(3) & 0xff) << 24);
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /** Growable byte buffer for codec output. */
    private static final class ByteArraySink {
        private byte[] data = new byte[1 << 16];
        private int len;

        void write(ByteBuffer src, int offset, int size) {
            if (len + size > data.length) {
                int cap = Math.max(data.length * 2, len + size);
                byte[] next = new byte[cap];
                System.arraycopy(data, 0, next, 0, len);
                data = next;
            }
            int pos = src.position();
            src.position(offset);
            src.get(data, len, size);
            src.position(pos);
            len += size;
        }

        byte[] bytes() {
            return data;
        }

        int length() {
            return len;
        }
    }
}
