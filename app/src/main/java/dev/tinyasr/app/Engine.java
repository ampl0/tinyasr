package dev.tinyasr.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serialises every engine call onto one thread.
 *
 * The tinyasr C API is explicitly not thread-safe per object: all calls for a
 * models object, its sessions and its files must come from the same thread.
 * The recognizer thread is that thread for the whole app; callers submit work
 * and get results back through callbacks.
 */
public final class Engine {

    public static final int CHUNK_SAMPLES = 1600; // 100 ms at 16 kHz

    private static Engine instance;

    public static synchronized Engine get() {
        if (instance == null) {
            instance = new Engine();
        }
        return instance;
    }

    /** Work that runs on the engine thread. Implementations must not block on the UI. */
    public interface Job {
        void run(Handle h) throws Exception;
    }

    /**
     * A loaded pack. Valid only inside the job that loaded it; {@link #run}
     * keeps the pack resident between jobs.
     */
    public interface Handle {
        long models();

        String[] langs();
    }

    private final WorkThread thread = new WorkThread("tinyasr-engine");
    private final Object lock = new Object();

    private volatile long models;
    private volatile String loadedPath;
    private volatile String[] langs;

    private Engine() {
        thread.start();
    }

    /**
     * Ensures {@code packPath} is the resident pack, then runs {@code job} on
     * the engine thread. The callback is invoked on the engine thread.
     */
    public void submit(final String packPath, final Job job, final Callback callback) {
        thread.post(() -> {
            String error = null;
            try {
                ensure(packPath);
                if (models == 0) {
                    throw new IllegalStateException("engine could not load " + shortName(packPath));
                }
                job.run(new Handle() {
                    @Override
                    public long models() {
                        return models;
                    }

                    @Override
                    public String[] langs() {
                        return langs;
                    }
                });
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            if (callback != null) {
                callback.done(error);
            }
        });
    }

    /** Drops the resident pack (a no-op if none is loaded). Runs on the engine thread. */
    public void unload(final Runnable after) {
        thread.post(() -> {
            if (models != 0) {
                TinyAsr.nativeFree(models);
                models = 0;
                loadedPath = null;
                langs = null;
            }
            if (after != null) {
                after.run();
            }
        });
    }

    public interface Callback {
        /** {@code error} is null on success. */
        void done(String error);
    }

    private void ensure(String packPath) {
        if (models != 0 && packPath.equals(loadedPath)) {
            return;
        }
        if (models != 0) {
            TinyAsr.nativeFree(models);
            models = 0;
            loadedPath = null;
            langs = null;
        }
        synchronized (lock) {
            long h = TinyAsr.load(packPath, ModelStore.vadFile().getAbsolutePath());
            if (h == 0) {
                return;
            }
            int n = TinyAsr.nativeLangCount(h);
            String[] codes = new String[n];
            for (int i = 0; i < n; i++) {
                codes[i] = TinyAsr.nativeLang(h, i);
            }
            models = h;
            loadedPath = packPath;
            langs = codes;
        }
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** PCM16LE -> float [-1,1], as the engine expects. */
    public static float[] pcm16ToFloat(byte[] pcm16, int length) {
        int samples = length / 2;
        float[] out = new float[samples];
        ByteBuffer b = ByteBuffer.wrap(pcm16, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            out[i] = b.getShort() / 32768f;
        }
        return out;
    }

    /** A single daemon thread that owns the engine handles. */
    private static final class WorkThread extends Thread {
        private final java.util.concurrent.LinkedBlockingQueue<Runnable> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();

        WorkThread(String name) {
            super(name);
            setDaemon(true);
        }

        void post(Runnable r) {
            queue.add(r);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Runnable r = queue.take();
                    r.run();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    android.util.Log.e("tinyasr", "engine job failed", t);
                }
            }
        }
    }
}
