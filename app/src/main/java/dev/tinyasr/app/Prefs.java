package dev.tinyasr.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Typed access to everything the app persists. Keeping the keys in one place
 * avoids the stringly-typed SharedPreferences calls scattered through the UI,
 * and makes the crash marker's commit()/remove() pairing obvious.
 */
public final class Prefs {

    private static final String NAME = "tinyasr";

    private static final String KEY_LAST_TEXT = "last_text";
    private static final String KEY_LAST_META = "last_meta";
    private static final String KEY_INFLIGHT = "inflight";
    private static final String KEY_LAST_CRASH = "last_crash";
    private static final String KEY_CRASHES = "crashes";
    private static final String KEY_SAFE_MODE = "safe_mode";
    private static final String KEY_LANG_PREFIX = "lang:";
    private static final String KEY_LANGS_PREFIX = "langs:";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // --- last result ---------------------------------------------------------

    public String lastText() {
        return prefs.getString(KEY_LAST_TEXT, "");
    }

    public String lastMeta() {
        return prefs.getString(KEY_LAST_META, "");
    }

    public void setLastResult(String text, String meta) {
        prefs.edit().putString(KEY_LAST_TEXT, text).putString(KEY_LAST_META, meta).apply();
    }

    // --- crash marker --------------------------------------------------------

    /** Written with commit() right before a native call; removed right after. */
    public void markInflight(String what) {
        prefs.edit().putString(KEY_INFLIGHT, what).commit();
    }

    public void clearInflight() {
        prefs.edit().remove(KEY_INFLIGHT).commit();
    }

    /**
     * The marker left by the previous process, if it died inside a native call.
     * Clears it in the same commit so it is reported once.
     */
    public String takeInflight() {
        String v = prefs.getString(KEY_INFLIGHT, null);
        if (v != null) {
            prefs.edit().remove(KEY_INFLIGHT).commit();
        }
        return v;
    }

    public int bumpCrash(String inflight) {
        int crashes = prefs.getInt(KEY_CRASHES, 0) + 1;
        prefs.edit()
                .putInt(KEY_CRASHES, crashes)
                .putString(KEY_LAST_CRASH, inflight)
                .commit();
        return crashes;
    }

    public String lastCrash() {
        return prefs.getString(KEY_LAST_CRASH, null);
    }

    public void clearCrashState() {
        prefs.edit().putInt(KEY_CRASHES, 0).putBoolean(KEY_SAFE_MODE, false)
                .remove(KEY_LAST_CRASH).apply();
    }

    public boolean safeMode() {
        return prefs.getBoolean(KEY_SAFE_MODE, false);
    }

    public void setSafeMode(boolean on) {
        prefs.edit().putBoolean(KEY_SAFE_MODE, on).apply();
    }

    // --- per pack ------------------------------------------------------------

    public String choiceFor(File pack) {
        return prefs.getString(KEY_LANG_PREFIX + pack.getName(), null);
    }

    public void setChoice(File pack, String lang) {
        prefs.edit().putString(KEY_LANG_PREFIX + pack.getName(), lang).apply();
    }

    public String langsFor(File pack) {
        return prefs.getString(KEY_LANGS_PREFIX + pack.getName(), "");
    }

    public void setLangs(File pack, String csv) {
        prefs.edit().putString(KEY_LANGS_PREFIX + pack.getName(), csv).apply();
    }
}
