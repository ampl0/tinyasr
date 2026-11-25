package dev.tinyasr.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Owns the engine's model directory: {@code filesDir/tinyasr}.
 *
 * Files can arrive two ways:
 *  - bundled in assets/models (only in the "full" flavor), copied on first run;
 *  - imported by the user through the storage picker (both flavors).
 *
 * Either way they end up as plain files in that directory, so a pack imported
 * once survives app updates and can be replaced by importing a newer one.
 */
public final class ModelStore {

    private static final String TAG = "tinyasr";

    public static final String VAD = "silero_vad.onnx";
    public static final String LID = "tinylid.bin";

    /** Set by the Application-less entry points before any load. */
    static File dirPath;

    private ModelStore() {
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "tinyasr");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
        }
        dirPath = dir;
        return dir;
    }

    /** The engine also loads model files directly; these are the plain names. */
    public static File vadFile() {
        File dir = dirPath;
        if (dir == null) {
            throw new IllegalStateException("model directory not initialised");
        }
        return new File(dir, VAD);
    }

    public static File vad(Context context) {
        return new File(dir(context), VAD);
    }

    public static boolean hasVad(Context context) {
        return vad(context).isFile() && vad(context).length() > 0;
    }

    /** Installed .tzf packs, sorted by name. */
    public static List<File> packs(Context context) {
        List<File> out = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".tzf")) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    public static boolean isKnown(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".tzf") || n.equals(VAD) || n.equals(LID);
    }

    /** Copies whatever the "full" flavor ships; a no-op for the "core" flavor. */
    public static void installBundled(Context context) throws IOException {
        String[] names;
        try {
            names = context.getAssets().list("models");
        } catch (IOException e) {
            names = null;
        }
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (isKnown(name)) {
                copyAsset(context, name);
            }
        }
    }

    /**
     * Copies one picked document into the model dir, accepting gzipped packs
     * (the form Telegram serves them in) and validating the container magic.
     */
    public static File importUri(Context context, Uri uri) throws IOException {
        String name = displayName(context, uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
        if (!isKnown(name)) {
            throw new IOException("unsupported file '" + name + "' — need *.tzf, " + VAD + " or " + LID);
        }

        File out = new File(dir(context), name);
        File tmp = new File(dir(context), name + ".part");

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("cannot read " + uri);
            }
            InputStream in = maybeGunzip(raw);
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
        }

        try {
            validate(tmp, name);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        //noinspection ResultOfMethodCallIgnored
        out.delete();
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not store " + name);
        }
        Log.i(TAG, "imported " + name + " (" + out.length() + " bytes)");
        return out;
    }

    public static String describe(Context context) {
        StringBuilder sb = new StringBuilder();
        for (File f : packs(context)) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(f.getName()).append(' ').append(String.format(Locale.US, "%.1f MB", f.length() / 1048576.0));
        }
        sb.append(" · VAD ").append(hasVad(context) ? "ok" : "missing");
        return sb.toString();
    }

    /** Deletes installed packs, VAD and tinylid (keeps the log). */
    public static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".tzf") || n.endsWith(".part") || n.equals(VAD) || n.equals(LID)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        log(context, "models cleared");
    }

    /** Append-only event log, kept small; useful when the engine kills the process. */
    public static void log(Context context, String line) {
        File f = new File(context.getFilesDir(), "tinyasr.log");
        if (f.length() > 64 * 1024) {
            File old = new File(context.getFilesDir(), "tinyasr.old.log");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(old);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()));
            w.write("  ");
            w.write(line);
            w.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "log write failed", e);
        }
    }

    public static String readLog(Context context) {
        File f = new File(context.getFilesDir(), "tinyasr.log");
        if (!f.isFile()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return "cannot read log: " + e;
        }
        return sb.toString();
    }

    // --- internals -----------------------------------------------------------

    private static void copyAsset(Context context, String name) throws IOException {
        File out = new File(dir(context), name);
        long expected = -1;
        try (InputStream probe = context.getAssets().open("models/" + name)) {
            expected = probe.available();
        }
        if (out.exists() && out.length() > 0 && (expected <= 0 || out.length() == expected)) {
            return;
        }
        File tmp = new File(dir(context), name + ".part");
        try (InputStream in = context.getAssets().open("models/" + name);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        if (!tmp.renameTo(out)) {
            throw new IOException("could not move " + tmp + " to " + out);
        }
        Log.i(TAG, "installed bundled " + name + " (" + out.length() + " bytes)");
    }

    private static InputStream maybeGunzip(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
        in.mark(2);
        int a = in.read();
        int b = in.read();
        in.reset();
        if (a == 0x1f && b == 0x8b) {
            return new GZIPInputStream(in, 1 << 16);
        }
        return in;
    }

    private static void validate(File f, String name) throws IOException {
        byte[] head = new byte[4];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = in.read(head);
        }
        if (n < 4) {
            throw new IOException(name + " is too small to be a model");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tzf")) {
            if (head[0] != 'T' || head[1] != 'Z' || head[2] != 'F') {
                throw new IOException(name + " is not a TZF pack (magic " + new String(head) + ")");
            }
        } else if (lower.equals(LID)) {
            if (head[0] != 'T' || head[1] != 'L' || head[2] != 'I' || head[3] != 'D') {
                throw new IOException(name + " is not a TLID model");
            }
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cur = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cur.getString(idx);
                    if (name != null && !name.isEmpty()) {
                        return sanitize(name);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the path segment
        }
        String last = uri.getLastPathSegment();
        return sanitize(last == null ? "model" : last);
    }

    private static String sanitize(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
