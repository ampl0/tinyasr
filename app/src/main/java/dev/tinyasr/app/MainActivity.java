package dev.tinyasr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final String TAG = "tinyasr";

    private static final int REQ_PICK = 1001;
    private static final int REQ_MIC = 1002;
    private static final int REQ_MIC_LIVE = 1005;
    private static final int REQ_IMPORT = 1003;
    private static final int REQ_SAVE = 1004;

    /** "Auto": let the engine pick among the pack's languages. */
    private static final String AUTO = "auto (detect)";
    private static final String AUTO_CODE = "auto";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WavRecorder recorder = new WavRecorder();
    private final MicCapture mic = new MicCapture();
    private final List<File> packFiles = new ArrayList<>();

    private Spinner packView;
    private Spinner langView;
    private Button pickView;
    private Button recView;
    private Button liveView;
    private Button importView;
    private Button saveView;
    private Button shareView;
    private Button copyView;
    private Button sttView;
    private Button removeView;
    private Button logView;
    private Button enableView;
    private ProgressBar progress;
    private TextView statusView;
    private TextView outView;
    private TextView partialView;
    private TextView metaView;
    private TextView hintView;

    private volatile boolean live;
    private volatile long liveSession;
    private final StringBuilder liveCommitted = new StringBuilder();
    private final AtomicInteger liveQueued = new AtomicInteger();

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        packView = findViewById(R.id.pack);
        langView = findViewById(R.id.lang);
        pickView = findViewById(R.id.pick);
        recView = findViewById(R.id.rec);
        liveView = findViewById(R.id.live);
        importView = findViewById(R.id.importBtn);
        saveView = findViewById(R.id.saveBtn);
        shareView = findViewById(R.id.shareBtn);
        copyView = findViewById(R.id.copy);
        sttView = findViewById(R.id.sttBtn);
        removeView = findViewById(R.id.removeBtn);
        logView = findViewById(R.id.logBtn);
        enableView = findViewById(R.id.enableBtn);
        progress = findViewById(R.id.prog);
        statusView = findViewById(R.id.status);
        outView = findViewById(R.id.out);
        partialView = findViewById(R.id.partial);
        metaView = findViewById(R.id.meta);
        hintView = findViewById(R.id.hint);
        requireViews();

        if (prefs.lastText().isEmpty()) {
            outView.setText(getString(R.string.empty_state));
        } else {
            outView.setText(prefs.lastText());
        }
        metaView.setText(prefs.lastMeta());

        // A native crash cannot be caught in-process. The marker is written with
        // commit() right before entering the engine and cleared right after, so
        // one still being there means the previous process died inside it.
        String inflight = prefs.takeInflight();
        if (inflight != null) {
            int crashes = prefs.bumpCrash(inflight);
            prefs.setSafeMode(crashes >= 2);
            ModelStore.log(this, "previous run died during: " + inflight);
        }

        pickView.setOnClickListener(v -> pickAudio());
        recView.setOnClickListener(v -> toggleRecording());
        liveView.setOnClickListener(v -> toggleLive());
        importView.setOnClickListener(v -> importModels());
        saveView.setOnClickListener(v -> saveText());
        shareView.setOnClickListener(v -> shareText());
        copyView.setOnClickListener(v -> copyResult());
        logView.setOnClickListener(v -> copyLog());
        removeView.setOnClickListener(v -> confirmRemoveModels());
        enableView.setOnClickListener(v -> enableModels());
        sttView.setOnClickListener(v -> openSttSettings());

        packView.setOnItemSelectedListener(new SimpleItemSelected() {
            @Override
            public void onItemSelected(int position) {
                if (position >= 0 && position < packFiles.size()) {
                    showLanguagesFor(packFiles.get(position));
                }
            }
        });

        hintView.setMovementMethod(LinkMovementMethod.getInstance());

        if (prefs.safeMode()) {
            statusView.setText(R.string.safe_mode_status);
            enableView.setVisibility(View.VISIBLE);
        } else if (prefs.lastCrash() != null) {
            statusView.setText(getString(R.string.crashed_before, prefs.lastCrash()));
        }

        updateButtons();
        io.execute(() -> {
            try {
                ModelStore.installBundled(this);
            } catch (Throwable t) {
                Log.e(TAG, "bundled model install failed", t);
            }
            ui.post(this::refreshModels);
        });
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    // --- model management ----------------------------------------------------

    /** UI thread: rebuild the pack list. Never touches the engine. */
    private void refreshModels() {
        List<File> packs = ModelStore.packs(this);
        packFiles.clear();
        packFiles.addAll(packs);

        List<String> labels = new ArrayList<>();
        for (File f : packs) {
            labels.add(f.getName() + "  (" + String.format(Locale.US, "%.1f MB", f.length() / 1048576.0) + ")");
        }
        packView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));

        if (packs.isEmpty()) {
            langView.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, new String[0]));
            statusView.setText(ModelStore.hasVad(this)
                    ? R.string.status_no_packs
                    : R.string.status_no_models);
            updateButtons();
            return;
        }
        if (!ModelStore.hasVad(this)) {
            statusView.setText(R.string.status_no_vad);
            updateButtons();
            return;
        }
        statusView.setText(ModelStore.describe(this));
        showLanguagesFor(packs.get(Math.max(0, packView.getSelectedItemPosition())));
        updateButtons();
    }

    /**
     * Language list without loading the pack: cached from a previous run,
     * otherwise guessed from the file name (en-ru-uk.tzf -> en,ru,uk). A fixed
     * language comes first and is the default: with several languages the
     * engine runs a pass in each and readily settles on "en".
     */
    private void showLanguagesFor(File pack) {
        if (pack == null) {
            return;
        }
        List<String> codes = new ArrayList<>();
        String cached = prefs.langsFor(pack);
        if (!cached.isEmpty()) {
            for (String c : cached.split(",")) {
                if (!c.isEmpty() && !codes.contains(c)) {
                    codes.add(c);
                }
            }
        }
        if (codes.isEmpty()) {
            String stem = pack.getName();
            int dot = stem.lastIndexOf('.');
            if (dot > 0) {
                stem = stem.substring(0, dot);
            }
            for (String token : stem.split("[-_,]")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (t.length() >= 2 && t.length() <= 3 && !codes.contains(t)) {
                    codes.add(t);
                }
            }
        }
        if (codes.isEmpty()) {
            codes.add("en");
        }
        codes.add(0, AUTO);
        langView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, codes));

        String cachedLang = prefs.choiceFor(pack);
        if (cachedLang != null) {
            for (int i = 0; i < codes.size(); i++) {
                if (codes.get(i).equalsIgnoreCase(cachedLang)) {
                    langView.setSelection(i);
                    return;
                }
            }
        }
        if (codes.size() > 1) {
            langView.setSelection(1);
        }
    }

    private void confirmRemoveModels() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_models)
                .setMessage(R.string.remove_models_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    Engine.get().unload(null);
                    ModelStore.clear(this);
                    prefs.clearCrashState();
                    enableView.setVisibility(View.GONE);
                    refreshModels();
                    toast(R.string.models_removed);
                })
                .show();
    }

    private void enableModels() {
        prefs.clearCrashState();
        enableView.setVisibility(View.GONE);
        statusView.setText(R.string.models_enabled);
        updateButtons();
    }

    private void importModels() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_IMPORT);
    }

    // --- audio in ------------------------------------------------------------

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQ_PICK);
    }

    private void toggleRecording() {
        if (recorder.isRecording()) {
            File wav = recorder.stop();
            updateButtons();
            if (wav != null && wav.length() > 44) {
                transcribe(wav);
            } else {
                setBusy(false, getString(R.string.recording_empty));
            }
            return;
        }
        if (!hasMic(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        try {
            recorder.start(new File(getCacheDir(), "rec.wav"));
            outView.setText("");
            partialView.setText("");
            metaView.setText("");
            statusView.setText(R.string.recording);
            updateButtons();
        } catch (Throwable t) {
            Log.e(TAG, "record failed", t);
            fail(messageOf(t));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_LIVE) {
            if (granted) {
                startLive();
            } else {
                fail(getString(R.string.mic_denied));
            }
            return;
        }
        if (requestCode == REQ_MIC) {
            if (granted) {
                startRecording();
            } else {
                fail(getString(R.string.mic_denied));
            }
        }
    }

    // --- live streaming ------------------------------------------------------

    private void toggleLive() {
        if (live) {
            stopLive();
            return;
        }
        if (!hasMic(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_LIVE);
            return;
        }
        startLive();
    }

    private void startLive() {
        final File pack = selectedPack();
        if (pack == null || prefs.safeMode()) {
            fail(getString(prefs.safeMode() ? R.string.err_safe_mode : R.string.err_no_pack));
            return;
        }
        outView.setText("");
        partialView.setText("");
        liveCommitted.setLength(0);
        live = true;
        setBusy(true, getString(R.string.live_starting));
        final String wanted = selectedLang();

        Engine.get().submit(pack.getAbsolutePath(), h -> {
            final String langs = recognizeLangs(wanted, h.langs());
            persistLang(pack, wanted, h.langs());
            TinyAsr.nativePinFast();
            liveSession = TinyAsr.nativeSessionNew(h.models(), langs,
                    AUTO_CODE.equals(wanted) ? null : wanted, 0);
            if (liveSession == 0) {
                live = false;
                liveSession = 0;
                throw new IllegalStateException(getString(R.string.err_session));
            }
            ui.post(() -> {
                if (!live) {
                    return;
                }
                statusView.setText(getString(R.string.live_running,
                        AUTO_CODE.equals(wanted) ? getString(R.string.live_detecting) : wanted));
                updateLiveText();
                try {
                    mic.start(this::onLiveChunk);
                } catch (Throwable t) {
                    Log.e(TAG, "mic failed", t);
                    fail(messageOf(t));
                    stopLive();
                }
                updateButtons();
            });
        }, error -> {
            if (!live) {
                return;
            }
            if (error != null) {
                live = false;
                liveSession = 0;
                ui.post(() -> {
                    fail(error);
                    updateButtons();
                });
            }
        });
        updateButtons();
    }

    /** Called on the mic thread; the engine calls are queued and may drop. */
    private void onLiveChunk(byte[] pcm, int length, float level) {
        final long session = liveSession;
        if (!live || session == 0) {
            return;
        }
        // Drop the chunk instead of queueing without bound: if the recognizer
        // falls behind real time, old audio is worth less than staying live.
        if (liveQueued.get() > 8) {
            return;
        }
        liveQueued.incrementAndGet();
        final float[] f = Engine.pcm16ToFloat(pcm, length);
        Engine.get().submit(null, h -> {
            if (!live || liveSession != session) {
                return;
            }
            try {
                String json = TinyAsr.nativeSessionPush(session, f, f.length);
                if (json != null && json.length() > 2) {
                    handleEvents(json);
                }
            } finally {
                liveQueued.decrementAndGet();
            }
        }, null);
    }

    private void stopLive() {
        final long session = liveSession;
        live = false;
        mic.stop();
        if (session != 0) {
            Engine.get().submit(null, h -> {
                String json = TinyAsr.nativeSessionFinish(session);
                if (json != null && json.length() > 2) {
                    handleEvents(json);
                }
                TinyAsr.nativeSessionFree(session);
            }, error -> {
                liveSession = 0;
                ui.post(() -> {
                    partialView.setText("");
                    onResult(liveCommitted.toString().trim(), getString(R.string.meta_live));
                });
            });
        } else {
            partialView.setText("");
            onResult(liveCommitted.toString().trim(), getString(R.string.meta_live));
        }
        updateButtons();
    }

    /** Applies one JSON event array. Called on the engine thread. */
    private void handleEvents(String json) {
        try {
            final String[] partial = {null};
            final boolean[] changed = {false};
            Transcript.dispatch(json, new Transcript.Sink() {
                @Override
                public void commit(String text) {
                    if (liveCommitted.length() > 0) {
                        liveCommitted.append(' ');
                    }
                    liveCommitted.append(text);
                    changed[0] = true;
                }

                @Override
                public void partial(String text) {
                    partial[0] = text;
                }

                @Override
                public void language(String lang) {
                }
            });
            final String committed = liveCommitted.toString();
            final String tail = partial[0];
            if (changed[0] || tail != null) {
                ui.post(() -> {
                    // Painted even after Stop: the last commits and finish
                    // arrive while the session is winding down.
                    outView.setText(committed.isEmpty() ? getString(R.string.empty_state) : committed);
                    partialView.setText(tail == null ? "" : tail);
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "bad events: " + json, e);
        }
    }

    private void updateLiveText() {
        outView.setText(liveCommitted.length() == 0
                ? getString(R.string.empty_state) : liveCommitted.toString());
        if (liveCommitted.length() == 0) {
            statusView.setText(R.string.live_silent);
        }
    }

    // --- transcription -------------------------------------------------------

    private File selectedPack() {
        if (packFiles.isEmpty()) {
            return null;
        }
        int pos = Math.max(0, Math.min(packFiles.size() - 1, packView.getSelectedItemPosition()));
        return packFiles.get(pos);
    }

    private String selectedLang() {
        Object item = langView.getSelectedItem();
        String s = item == null ? "en" : String.valueOf(item);
        return AUTO.equals(s) ? AUTO_CODE : s;
    }

    /**
     * Which language(s) to hand the engine. A fixed code pins the pack to that
     * language (one pass, the words asked for); "auto" passes every language,
     * so the engine runs a pass in each and keeps the surest — much slower, and
     * for short or unclear audio it readily settles on "en".
     */
    private String recognizeLangs(String wanted, String[] langs) {
        if (AUTO_CODE.equals(wanted)) {
            List<String> all = new ArrayList<>();
            if (langs != null) {
                for (String c : langs) {
                    if (c != null && !c.isEmpty()) {
                        all.add(c);
                    }
                }
            }
            return all.isEmpty() ? AUTO_CODE : TextUtils.join(",", all);
        }
        return wanted;
    }

    private void transcribe(File audio) {
        if (prefs.safeMode()) {
            fail(getString(R.string.err_safe_mode));
            return;
        }
        final File pack = selectedPack();
        if (pack == null) {
            fail(getString(R.string.err_no_pack));
            return;
        }
        final String wanted = selectedLang();
        setBusy(true, getString(R.string.loading_pack, pack.getName()));
        Engine.get().submit(pack.getAbsolutePath(), h -> {
            final String langs = recognizeLangs(wanted, h.langs());
            persistLang(pack, wanted, h.langs());
            ui.post(() -> setBusy(true, getString(R.string.transcribing)));
            markInflight("transcribe " + audio.getName());
            try {
                Transcriber.Result r = Transcriber.transcribe(h.models(), audio, langs);
                String shown = AUTO_CODE.equals(wanted) && r.detectedLang != null ? r.detectedLang : wanted;
                ui.post(() -> onResult(r.text, getString(R.string.meta_file,
                        shown, r.decodeMs, r.runMs)));
            } finally {
                clearInflight();
            }
        }, error -> ui.post(() -> {
            if (error != null) {
                ModelStore.log(this, "transcribe failed: " + error);
                fail(error);
            } else {
                setBusy(false, null);
            }
        }));
    }

    private void persistLang(File pack, String wanted, String[] langs) {
        prefs.setChoice(pack, wanted);
        if (langs != null) {
            prefs.setLangs(pack, TextUtils.join(",", langs));
        }
    }

    private void onResult(String text, String meta) {
        boolean empty = text == null || text.trim().isEmpty();
        outView.setText(empty ? getString(R.string.empty_state) : text);
        partialView.setText("");
        metaView.setText(meta);
        statusView.setText(empty ? R.string.status_nothing : R.string.status_done);
        prefs.setLastResult(outView.getText().toString(), meta);
        prefs.clearCrashState();
        setBusy(false, null);
    }

    // --- share / view intake -------------------------------------------------

    private void handleShareIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action)) {
            return;
        }
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            uri = intent.getData();
        }
        if (uri == null) {
            return;
        }
        final Uri target = uri;
        setBusy(true, getString(R.string.copying_audio));
        io.execute(() -> {
            File local = null;
            try {
                local = copyToCache(target, "shared.dat");
                final File audio = local;
                ui.post(() -> transcribe(audio));
            } catch (Throwable t) {
                Log.e(TAG, "shared audio failed", t);
                ui.post(() -> fail(messageOf(t)));
            }
        });
    }

    // --- results -------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == REQ_PICK && data.getData() != null) {
            final Uri uri = data.getData();
            setBusy(true, getString(R.string.copying_audio));
            io.execute(() -> {
                try {
                    File local = copyToCache(uri, "input.dat");
                    ui.post(() -> transcribe(local));
                } catch (Throwable t) {
                    Log.e(TAG, "audio import failed", t);
                    ui.post(() -> fail(messageOf(t)));
                }
            });
            return;
        }

        if (requestCode == REQ_IMPORT) {
            final List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) {
                return;
            }
            setBusy(true, getString(R.string.importing, uris.size()));
            io.execute(() -> {
                int ok = 0;
                String error = null;
                for (Uri uri : uris) {
                    try {
                        File f = ModelStore.importUri(this, uri);
                        ModelStore.log(this, "imported " + f.getName() + " " + f.length());
                        ok++;
                    } catch (Throwable t) {
                        Log.e(TAG, "import failed: " + uri, t);
                        error = messageOf(t);
                    }
                }
                final int imported = ok;
                final String err = error;
                ui.post(() -> {
                    setBusy(false, getString(R.string.imported, imported)
                            + (err != null ? " · " + err : ""));
                    refreshModels();
                });
            });
            return;
        }

        if (requestCode == REQ_SAVE && data.getData() != null) {
            final Uri uri = data.getData();
            final String text = outView.getText().toString();
            io.execute(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        throw new IOException("cannot write " + uri);
                    }
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    ui.post(() -> toast(R.string.saved));
                } catch (Throwable t) {
                    Log.e(TAG, "save failed", t);
                    ui.post(() -> fail(messageOf(t)));
                }
            });
        }
    }

    private void saveText() {
        if (hasNoResult()) {
            toast(R.string.nothing_to_save);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "transcript.txt");
        startActivityForResult(intent, REQ_SAVE);
    }

    /** Writes the transcript to shared storage and hands it to any app. */
    private void shareText() {
        if (hasNoResult()) {
            toast(R.string.nothing_to_share);
            return;
        }
        final String text = outView.getText().toString();
        io.execute(() -> {
            try {
                File f = ShareFiles.newTranscript(this, text);
                final Uri uri = ShareFiles.shareUri(this, f);
                ui.post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    if (uri != null) {
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_transcript)));
                });
            } catch (Throwable t) {
                Log.e(TAG, "share failed", t);
                ui.post(() -> fail(messageOf(t)));
            }
        });
    }

    private void copyResult() {
        CharSequence text = outView.getText();
        if (hasNoResult()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("transcript", text));
        toast(R.string.copied);
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String log = ModelStore.readLog(this);
        cm.setPrimaryClip(ClipData.newPlainText("tinyasr log", log.isEmpty() ? "(empty)" : log));
        toast(R.string.log_copied);
    }

    /** Opens the system speech-recognition picker, where this app can be chosen. */
    private void openSttSettings() {
        Intent intent = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
        try {
            startActivity(intent);
            toast(R.string.stt_hint);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                toast(R.string.stt_hint);
            } catch (Throwable t2) {
                fail(getString(R.string.stt_manual));
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * A missing view id is a build-time mistake, but the symptom is a shrug of
     * a NullPointerException on the first tap. Name it here instead, right at
     * startup, and put it in the log so "Copy log" can show it.
     */
    private void requireViews() {
        List<String> missing = new ArrayList<>();
        check(missing, packView, "pack");
        check(missing, langView, "lang");
        check(missing, pickView, "pick");
        check(missing, recView, "rec");
        check(missing, liveView, "live");
        check(missing, importView, "importBtn");
        check(missing, saveView, "saveBtn");
        check(missing, shareView, "shareBtn");
        check(missing, copyView, "copy");
        check(missing, sttView, "sttBtn");
        check(missing, removeView, "removeBtn");
        check(missing, logView, "logBtn");
        check(missing, enableView, "enableBtn");
        check(missing, progress, "prog");
        check(missing, statusView, "status");
        check(missing, outView, "out");
        check(missing, partialView, "partial");
        check(missing, metaView, "meta");
        check(missing, hintView, "hint");
        if (!missing.isEmpty()) {
            String what = "layout is missing views: " + TextUtils.join(", ", missing);
            ModelStore.log(this, what);
            throw new IllegalStateException(what);
        }
    }

    private static void check(List<String> missing, View view, String id) {
        if (view == null) {
            missing.add(id);
        }
    }

    private boolean hasNoResult() {
        String s = outView.getText().toString().trim();
        return s.isEmpty() || s.equals(getString(R.string.empty_state));
    }

    private File copyToCache(Uri uri, String name) throws IOException {
        File local = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(local)) {
            if (in == null) {
                throw new IOException("cannot open " + uri);
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return local;
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static boolean hasMic(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void setBusy(boolean isBusy, String status) {
        progress.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        importView.setEnabled(!isBusy);
        boolean enabled = !isBusy && !hasNoResult();
        saveView.setEnabled(enabled);
        shareView.setEnabled(enabled);
        copyView.setEnabled(enabled);
        removeView.setEnabled(!isBusy);
        logView.setEnabled(!isBusy);
        sttView.setEnabled(!isBusy);
        if (status != null) {
            statusView.setText(status);
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean canStart = !prefs.safeMode() && !packFiles.isEmpty()
                && ModelStore.hasVad(this) && !recorder.isRecording() && !live;
        pickView.setEnabled(canStart && progress.getVisibility() != View.VISIBLE);
        recView.setEnabled(canStart || recorder.isRecording());
        recView.setText(recorder.isRecording() ? R.string.stop_recording : R.string.record);
        liveView.setEnabled(live || canStart);
        liveView.setText(live ? R.string.stop_live : R.string.live);
        enableView.setVisibility(prefs.safeMode() ? View.VISIBLE : View.GONE);
    }

    private void fail(String message) {
        setBusy(false, getString(R.string.error_prefix, message == null ? "" : message));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void markInflight(String what) {
        prefs.markInflight(what);
    }

    private void clearInflight() {
        prefs.clearInflight();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recorder.isRecording()) {
            recorder.stop();
        }
        if (live) {
            stopLive();
        }
        Engine.get().unload(null);
        io.shutdownNow();
    }

    /** AdapterView.OnItemSelectedListener with the boilerplate removed. */
    private abstract static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            onItemSelected(position);
        }

        public abstract void onItemSelected(int position);
    }
}
