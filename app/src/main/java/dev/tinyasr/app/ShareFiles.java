package dev.tinyasr.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.net.Uri;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes transcripts somewhere the user can find them, and hands out a
 * content:// URI for sharing.
 */
public final class ShareFiles {

    private static final String TAG = "tinyasr";

    private ShareFiles() {
    }

    /**
     * A timestamped .txt in the app's external files directory, so the user can
     * pick it from any file manager (and so no storage permission is needed).
     */
    public static File newTranscript(Context context, String text) throws Exception {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = context.getCacheDir();
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "transcript-" + stamp + ".txt");
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
            os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    /** A content:// URI only if the file is inside one of our own directories. */
    public static Uri shareUri(Context context, File file) {
        try {
            return FileProvider.getUriForFile(context, file);
        } catch (Throwable t) {
            Log.w(TAG, "no shareable uri for " + file, t);
            return null;
        }
    }

    private static boolean isInside(File root, File dir) {
        if (root == null || dir == null) {
            return false;
        }
        try {
            String r = root.getCanonicalPath();
            String d = dir.getCanonicalPath();
            return d.equals(r) || d.startsWith(r + File.separator);
        } catch (Exception e) {
            return false;
        }
    }
}
