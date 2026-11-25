package dev.tinyasr.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

/**
 * A live capture: the microphone is read on a plain thread and every 100 ms
 * chunk is handed to {@link Sink#onChunk} together with its level. The engine
 * call itself happens on the engine thread, so this class never blocks on it
 * for long: the sink may drop chunks if the recognizer falls behind.
 */
public final class MicCapture {

    private static final int RATE = 16000;

    public interface Sink {
        /** pcm is {@code length} bytes of 16 kHz mono PCM16LE; level is 0..1. */
        void onChunk(byte[] pcm, int length, float level);
    }

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;

    /** Throws IllegalStateException when the microphone is unavailable. */
    @SuppressLint("MissingPermission")
    public void start(final Sink sink) {
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
                Math.max(min, RATE * 2 * 2)); // 2 s of room
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {            record.release();
            record = null;
            throw new IllegalStateException("microphone unavailable");
        }
        running = true;
        record.startRecording();
        thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] buf = new byte[Engine.CHUNK_SAMPLES * 2];
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) {
                    continue;
                }
                sink.onChunk(buf, n, level(buf, n));
            }
        }, "tinyasr-mic");
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(2000);
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
    }

    private static float level(byte[] pcm16, int length) {
        double sum = 0;
        int samples = length / 2;
        for (int i = 0; i < samples; i++) {
            int lo = pcm16[2 * i] & 0xff;
            int hi = pcm16[2 * i + 1];
            int v = (hi << 8) | lo;
            double x = v / 32768.0;
            sum += x * x;
        }
        if (samples == 0) {
            return 0;
        }
        double rms = Math.sqrt(sum / samples);
        double db = 20 * Math.log10(rms + 1e-7);
        return (float) Math.max(0, Math.min(1, (db + 50) / 40));
    }
}
