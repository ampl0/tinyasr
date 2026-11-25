package dev.tinyasr.app;

import java.io.File;

/**
 * Loads and frees engine handles through {@link TinyAsr}.
 *
 * Thin indirection kept separate so the UI never calls a native method
 * directly, and so the resident pack has exactly one owner.
 */
public final class Models {

    private Models() {
    }

    public static long load(File pack, File vad) {
        return TinyAsr.load(pack.getAbsolutePath(), vad.getAbsolutePath());
    }

    public static void free(long handle) {
        TinyAsr.nativeFree(handle);
    }

    public static String[] langs(long handle) {
        int n = TinyAsr.nativeLangCount(handle);
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = TinyAsr.nativeLang(handle, i);
        }
        return out;
    }
}
