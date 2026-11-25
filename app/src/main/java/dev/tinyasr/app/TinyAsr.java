package dev.tinyasr.app;

import java.nio.ByteBuffer;

/**
 * JNI bindings of the tinyasr C API.
 *
 * The native library is built from the engine's own source
 * (https://github.com/dkaraush/tinyasr, GPL-3.0-or-later) into
 * libtinyasr_jni.so. Unlike Telegram's libtmessages.so it has no JNI_OnLoad
 * that looks up Telegram classes, so it links into any app.
 *
 * Every call for one models object must come from the same thread while a
 * session or file of it is running.
 */
public final class TinyAsr {

    static {
        System.loadLibrary("tinyasr_jni");
    }

    private TinyAsr() {
    }

    /** The library's version, i.e. the model file generations it reads. */
    public static native int nativeVersion();

    /** Loads {@code modelPath} (.tzf) and {@code vadPath} (silero_vad.onnx); 0 on failure. */
    public static native long nativeLoadPaths(String modelPath, String vadPath, int threads);

    public static native void nativeFree(long models);

    public static native int nativeChunkMs(long models);

    public static native int nativeLangCount(long models);

    public static native String nativeLang(long models, int index);

    /** Pins the calling thread to the big cores; returns their count (0: unknown). */
    public static native int nativePinFast();

    public static native void nativeSetThreads(long models, int threads);

    // --- streaming ---

    public static native long nativeSessionNew(long models, String langs, String startLang, int beam);

    /** Events as a UTF-8 JSON array. */
    public static native String nativeSessionPush(long session, float[] pcm, int n);

    public static native String nativeSessionFinish(long session);

    public static native String nativeSessionLang(long session);

    public static native void nativeSessionFree(long session);

    // --- whole file ---

    public static native long nativeFileNew(long models, float[] pcm, int n, String langs, int beam);

    /** Next piece of final words as a JSON array, or null at the end. */
    public static native String nativeFileNext(long file);

    public static native String nativeFileLang(long file);

    /** 0..1, or -1. */
    public static native float nativeFileProgress(long file);

    public static native void nativeFileFree(long file);

    /** Convenience for a single language. */
    public static long load(String modelPath, String vadPath) {
        return nativeLoadPaths(modelPath, vadPath, 0);
    }

    /** Convenience that keeps the buffers alive as long as they are needed. */
    public static long load(ByteBuffer model, ByteBuffer vad, int threads) {
        return nativeLoadPaths(null, null, threads);
    }
}
