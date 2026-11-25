package dev.tinyasr.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves files from the app's own directories as {@code content://} URIs so
 * they can be handed to another app (the share sheet) without a
 * {@code file://} leak.
 *
 * A tiny stand-in for {@code androidx.core.content.FileProvider} — the project
 * deliberately has no AndroidX.
 *
 * Layout (see {@code res/xml/file_paths.xml}):
 *   {@code <package>.files}     → filesDir
 *   {@code <package>.cache}     → cacheDir
 *   {@code <package>.external}  → external files dir
 */
public class FileProvider extends ContentProvider {

    private static final String AUTHORITY_SUFFIX = ".fileprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    private static String authority(Context context) {
        return context.getPackageName() + AUTHORITY_SUFFIX;
    }

    private static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>(3);
        roots.add(context.getFilesDir());
        roots.add(context.getCacheDir());
        File ext = context.getExternalFilesDir(null);
        if (ext != null) {
            roots.add(ext);
        }
        return roots;
    }

    private static String rootName(Context context, File root) {
        if (root.equals(context.getFilesDir())) {
            return "files";
        }
        if (root.equals(context.getCacheDir())) {
            return "cache";
        }
        return "external";
    }

    private File rootFor(Context context, String name) {
        for (File root : roots(context)) {
            if (rootName(context, root).equals(name)) {
                return root;
            }
        }
        return null;
    }

    public static Uri getUriForFile(Context context, File file) {
        try {
            String path = file.getCanonicalPath();
            for (File root : roots(context)) {
                String base = root.getCanonicalPath();
                if (path.equals(base) || path.startsWith(base + File.separator)) {
                    String rel = path.substring(base.length());
                    if (rel.startsWith(File.separator)) {
                        rel = rel.substring(1);
                    }
                    return new Uri.Builder()
                            .scheme("content")
                            .authority(authority(context))
                            .appendPath(rootName(context, root))
                            .appendPath(rel)
                            .build();
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("no root covers " + file, e);
        }
        throw new IllegalArgumentException("no root covers " + file);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file = resolve(uri);
        String name = file.getName();
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{name, file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = resolve(uri).getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File resolve(Uri uri) {
        Context context = getContext();
        if (context == null || uri.getPathSegments().size() < 2) {
            throw new IllegalArgumentException("bad uri " + uri);
        }
        File root = rootFor(context, uri.getPathSegments().get(0));
        if (root == null) {
            throw new IllegalArgumentException("unknown root in " + uri);
        }
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < uri.getPathSegments().size(); i++) {
            if (rel.length() > 0) {
                rel.append('/');
            }
            rel.append(uri.getPathSegments().get(i));
        }
        File file = new File(root, rel.toString());
        try {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath())) {
                throw new IllegalArgumentException("path escapes root: " + uri);
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("bad path in " + uri, e);
        }
        return file;
    }
}
