package dev.tinyasr.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exposes the engine as the phone's speech recognizer, so any app that uses
 * {@code SpeechRecognizer} (keyboards, search boxes, assistants) can dictate
 * offline. The user selects it in Settings → System → Languages → Speech →
 * "Speech recognition", or via the button in {@link MainActivity}.
 *
 * Each session runs on its own thread. The models are loaded and freed per
 * session and all engine calls happen on that one thread, as the C API
 * requires.
 */
public class TinyAsrRecognitionService extends RecognitionService {

    private static final String TAG = "tinyasr";
    private static final long MAX_MS = 60_000;
    private static final long STOP_AFTER_SILENCE_MS = 4_000;

    private volatile Session active;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        final String lang = pickLanguage(recognizerIntent);
        final Session session = new Session(callback);
        active = session;
        new Thread(() -> session.run(lang), "tinyasr-stt").start();
    }

    @Override
    protected void onStopListening(Callback callback) {
        Session s = active;
        if (s != null) {
            s.stop = true;
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        Session s = active;
        if (s != null) {
            s.discard = true;
            s.stop = true;
        }
        active = null;
    }

    private static String pickLanguage(Intent intent) {
        String tag = intent == null ? null : intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        if (tag == null) {
            return "";
        }
        int dash = tag.indexOf('-');
        if (dash > 0) {
            tag = tag.substring(0, dash);
        }
        int underscore = tag.indexOf('_');
        if (underscore > 0) {
            tag = tag.substring(0, underscore);
        }
        return tag.toLowerCase(Locale.ROOT);
    }

    private final class Session {
        final Callback callback;
        volatile boolean stop;
        volatile boolean discard;
        volatile long lastActivity;
        long session;

        Session(Callback callback) {
            this.callback = callback;
        }

        void run(String lang) {
            MicCapture mic = new MicCapture();
            long models = 0;
            try {
                File pack = firstPack();
                if (pack == null || !ModelStore.hasVad(TinyAsrRecognitionService.this)) {
                    callback.error(SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                models = TinyAsr.load(pack.getAbsolutePath(),
                        ModelStore.vadFile().getAbsolutePath());
                if (models == 0) {
                    callback.error(SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                // Pin to one language when the caller asked for one; otherwise
                // let the pack's languages compete. The engine's own pick is
                // readily "en", so a fixed code is the safer default.
                boolean fixed = !lang.isEmpty() && hasLang(models, lang);
                callback.readyForSpeech(new Bundle());
                callback.beginningOfSpeech();

                session = TinyAsr.nativeSessionNew(models, fixed ? lang : null,
                        fixed ? lang : null, 0);
                if (session == 0) {
                    callback.error(SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                lastActivity = SystemClock.elapsedRealtime();
                Sink sink = new Sink();
                mic.start((pcm, length, level) -> {
                    if (stop) {
                        return;
                    }
                    try {
                        float[] f = Engine.pcm16ToFloat(pcm, length);
                        String json = TinyAsr.nativeSessionPush(session, f, f.length);
                        boolean changed = sink.apply(json);
                        if (changed) {
                            lastActivity = SystemClock.elapsedRealtime();
                            if (!sink.partial.isEmpty()) {
                                Bundle b = new Bundle();
                                b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                                        new ArrayList<>(List.of(sink.partial)));
                                callback.partialResults(b);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "push failed", t);
                    }
                });

                long start = SystemClock.elapsedRealtime();
                while (!stop) {
                    if (SystemClock.elapsedRealtime() - start > MAX_MS) {
                        break;
                    }
                    if (SystemClock.elapsedRealtime() - lastActivity > STOP_AFTER_SILENCE_MS) {
                        break;
                    }
                    Thread.sleep(100);
                }
                mic.stop();
                if (discard) {
                    return;
                }
                sink.apply(TinyAsr.nativeSessionFinish(session));
                Bundle result = new Bundle();
                ArrayList<String> list = new ArrayList<>();
                list.add(sink.committed.toString());
                result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                result.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES,
                        new float[]{sink.committed.length() == 0 ? 0f : 1f});
                callback.results(result);
                callback.endOfSpeech();
            } catch (Throwable t) {
                Log.e(TAG, "recognition failed", t);
                if (!discard) {
                    try {
                        callback.error(SpeechRecognizer.ERROR_CLIENT);
                    } catch (Throwable ignored) {
                        // the client is gone
                    }
                }
            } finally {
                mic.stop();
                if (session != 0) {
                    TinyAsr.nativeSessionFree(session);
                    session = 0;
                }
                if (models != 0) {
                    TinyAsr.nativeFree(models);
                }
                active = null;
            }
        }

        /** Text assembled from the event stream. */
        final class Sink {
            final StringBuilder committed = new StringBuilder();
            volatile String partial = "";

            boolean apply(String json) throws Exception {
                if (json == null || json.length() <= 2) {
                    return false;
                }
                final boolean[] changed = {false};
                Transcript.dispatch(json, new Transcript.Sink() {
                    @Override
                    public void commit(String text) {
                        if (committed.length() > 0) {
                            committed.append(' ');
                        }
                        committed.append(text);
                        changed[0] = true;
                    }

                    @Override
                    public void partial(String text) {
                        if (!text.equals(partial)) {
                            changed[0] = true;
                        }
                        partial = text;
                    }

                    @Override
                    public void language(String l) {
                        changed[0] = true;
                    }
                });
                return changed[0];
            }
        }
    }

    private static boolean hasLang(long models, String code) {
        int n = TinyAsr.nativeLangCount(models);
        for (int i = 0; i < n; i++) {
            String c = TinyAsr.nativeLang(models, i);
            if (c != null && c.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    private File firstPack() {
        List<File> packs = ModelStore.packs(this);
        return packs.isEmpty() ? null : packs.get(0);
    }

    /** Convenience used by MainActivity to describe the current selection. */
    public static String describe(Context context) {
        List<File> packs = ModelStore.packs(context);
        return packs.isEmpty() ? "no packs" : packs.get(0).getName();
    }
}
