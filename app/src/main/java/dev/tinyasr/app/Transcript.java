package dev.tinyasr.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns the recognizer's JSON events into text.
 *
 * Events (from include/tinyasr.h):
 *   {"type":"commit","words":[{"text":..,"start":..,"end":..}]}  final words, append
 *   {"type":"partial","open":[...],"tail":[...]}                 replaces the previous partial
 *   {"type":"language","lang":"uk"}                              the recognizer switched language
 *   {"type":"eou","at":..,"p":..}                                end-of-turn probability at a pause
 */
public final class Transcript {

    public interface Sink {
        /** Final words, to append after everything committed before. */
        void commit(String text);

        /** Words that may still change; replaces the previous partial ("" clears it). */
        void partial(String text);

        void language(String lang);
    }

    private Transcript() {
    }

    /** Returns true when the events carried any text activity. */
    public static boolean dispatch(String json, Sink sink) throws Exception {
        if (json == null || json.length() <= 2) {
            return false;
        }
        JSONArray events = new JSONArray(json);
        boolean active = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) {
                continue;
            }
            switch (e.optString("type")) {
                case "commit": {
                    String t = join(e.optJSONArray("words"));
                    if (!t.isEmpty()) {
                        sink.commit(t);
                        active = true;
                    }
                    break;
                }
                case "partial": {
                    JSONArray openArr = e.optJSONArray("open");
                    JSONArray tailArr = e.optJSONArray("tail");
                    String open = join(openArr);
                    String tail = join(tailArr);
                    String t;
                    if (open.isEmpty()) {
                        t = tail;
                    } else if (tail.isEmpty()) {
                        t = open;
                    } else {
                        JSONObject first = tailArr.optJSONObject(0);
                        boolean join = first != null && first.optBoolean("join", false);
                        t = join ? open + tail : open + " " + tail;
                    }
                    sink.partial(t);
                    if (!t.isEmpty()) {
                        active = true;
                    }
                    break;
                }
                case "language":
                    sink.language(e.optString("lang"));
                    break;
                default:
                    break;
            }
        }
        return active;
    }

    /** Joins words with spaces unless a word asks to be glued to the previous one. */
    public static String join(JSONArray words) {
        if (words == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length(); i++) {
            JSONObject w = words.optJSONObject(i);
            if (w == null) {
                continue;
            }
            String t = w.optString("text");
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0 && !w.optBoolean("join", false)) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
