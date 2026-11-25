package dev.tinyasr.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * A finite 16 kHz mono WAV recording. {@link MicCapture} streams to the engine
 * live; this class exists for "record now, transcribe on stop", which uses the
 * full-context pass and therefore produces slightly better punctuation.
 */
public final class WavRecorder {

    private static final String TAG = "tinyasr";
    private static final int RATE = 16000;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private File file;
    private volatile long dataBytes;

    public boolean isRecording() {
        return running;
    }

    /**
     * Captures 16 kHz mono PCM16 into a WAV file. The caller must hold
     * RECORD_AUDIO; the activity checks before reaching here.
     */
    @SuppressLint("MissingPermission")
    public void start(File out) throws java.io.IOException {
        if (running) {
            return;
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = 16384;
        }
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, 32768));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            throw new IllegalStateException("microphone unavailable");
        }

        file = out;
        dataBytes = 0;
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);
            raf.write(new byte[44]); // placeholder header, patched in stop()
        }

        running = true;
        record.startRecording();
        thread = new Thread(() -> {
            byte[] buf = new byte[3200]; // 100 ms
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(44);
                while (running) {
                    int n = record.read(buf, 0, buf.length);
                    if (n > 0) {
                        raf.write(buf, 0, n);
                        dataBytes += n;
                    }
                }
            } catch (java.io.IOException e) {
                Log.e(TAG, "recording failed", e);
            }
        }, "tinyasr-rec");
        thread.start();
    }

    /** Stops, patches the WAV header and returns the finished file. */
    public File stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            record.release();
            record = null;
        }
        if (file == null) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.write(wavHeader(dataBytes));
        } catch (java.io.IOException e) {
            Log.e(TAG, "could not patch wav header", e);
        }
        File done = file;
        file = null;
        return done;
    }

    private static byte[] wavHeader(long dataLen) {
        int data = (int) Math.min(dataLen, 0x7ffff000L);
        int riff = 36 + data;
        int byteRate = RATE * 2;
        byte[] h = new byte[44];
        putAscii(h, 0, "RIFF");
        putInt(h, 4, riff);
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putInt(h, 16, 16);
        putShort(h, 20, 1);            // PCM
        putShort(h, 22, 1);            // mono
        putInt(h, 24, RATE);
        putInt(h, 28, byteRate);
        putShort(h, 32, 2);            // block align
        putShort(h, 34, 16);           // bits per sample
        putAscii(h, 36, "data");
        putInt(h, 40, data);
        return h;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }
}
