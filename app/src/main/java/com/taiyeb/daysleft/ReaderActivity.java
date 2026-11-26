package com.taiyeb.daysleft;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.webkit.*;
import org.json.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** A calm paper-style PDF reader with a play/pause button that controls whatever audio is playing. */
public class ReaderActivity extends Activity {
    static final String[] TN = {"Paper", "Sepia", "Day", "Night"};
    static final int[] PAPER = {0xFFF4ECD8, 0xFFE8D6B0, 0xFFFBFAF6, 0xFF1C1B19};
    static final int[] INK = {0xFF2C2519, 0xFF3A2A16, 0xFF1B1B1B, 0xFFCFC8B6};
    static final int[] DESK = {0xFFE3D8BE, 0xFFD3BF93, 0xFFE9E7E1, 0xFF0F0F0E};

    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final Object lock = new Object();
    final LruCache<Integer, Bitmap> cache = new LruCache<>(4);
    final Set<Integer> inflight = Collections.synchronizedSet(new HashSet<>());

    PdfRenderer pr;
    ParcelFileDescriptor pfd;
    Item item;
    int n, cur, theme, renderW;
    boolean trim;
    float cl, ct, cr, cb;      // trimmed margins as fractions of the page

    FrameLayout root;
    PageView pv;
    LinearLayout top, bottom, settings, donePill;
    TextView title, pageLbl, aa;
    SeekBar seek;
    Glyph back, playGlyph, audioGlyph;
    FrameLayout playBtn;
    TextView audioBtn;
    boolean chrome = true, finishedToastShown;
    AudioManager am;

    // in-app YouTube player + page/time sync
    LinearLayout controlsCol;
    TextView timeLbl, speedBtn;
    final List<TextView> pillBtns = new ArrayList<>();
    final List<double[]> anchors = new ArrayList<>();   // {seconds, page}
    boolean ui, fileMode, mpReady;
    TextView pinLbl, goPageBtn;
    boolean clearArmed;
    BookIndex bi;
    Alignment al = new Alignment();
    int hlFrom = -1, hlTo = -1;
    boolean hlOn, followOn;
    int hlStyle;   // 0 marker, 1 underline
    long lastManual, alReloaded;

    int goPage;
    boolean jumpToAudio;
    double aT, aDur;
    int aState = -1, speedIdx, bottomInset, chromeBg;
    long lastSave;
    static final float[] SPEEDS = {1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f};

    // ====================== lifecycle ======================
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        item = Learn.item(this, getIntent().getIntExtra("id", -1));
        if (item == null || item.pdf.isEmpty()) { Dbg.w("Reader", "opened without a book"); finish(); return; }
        Dbg.i("Reader", "open \"" + item.title + "\", audio " + (item.audio.isEmpty() ? "none" : "attached"));
        try {
            pfd = getContentResolver().openFileDescriptor(Uri.parse(item.pdf), "r");
            pr = new PdfRenderer(pfd);
            n = pr.getPageCount();
        } catch (Exception e) {
            Dbg.e("Reader", "cannot open PDF", e);
            Toast.makeText(this, "Can't open this PDF. Attach it again from the item.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (n == 0) { finish(); return; }
        am = getSystemService(AudioManager.class);
        fileMode = !item.audio.isEmpty();
        ui = fileMode;
        bottomInset = 0;
        loadAnchors();
        hlOn = Store.flag(this, "rdHl", true);
        followOn = Store.flag(this, "rdFollow", true);
        hlStyle = Store.num(this, "rdHlStyle", 0);
        Store.setNum(this, "lastBook", item.id);
        theme = Store.num(this, "rdTheme", 0);
        trim = Store.flag(this, "rdTrim", true);
        cur = Math.max(0, Math.min(n - 1, item.page));
        renderW = Math.min(2200, Math.max(1400, (int) (getResources().getDisplayMetrics().widthPixels * 1.6f)));
        int br = Store.num(this, "rdBright", -1);
        if (br >= 0) setBright(br);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        build();
        immersive();
        jumpToAudio = getIntent().getBooleanExtra("fromNote", false);
        if (fileMode) { setupFile(); loadSyncData(); }
        skin();
        goTo(cur, false);
        computeCrop();
        h.postDelayed(poll, 800);
    }

    @Override protected void onPause() {
        super.onPause();
        saveProgress();
        if (ui && aT > 1) Learn.setAudio(this, item.id, (int) aT, null);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        closePanel();
        if (!SyncService.running) new Thread(Whisper::release).start();
        Player.onReady = null;
        Player.readerOpen = false;
        if (!Player.isPlaying()) Player.release(this);   // keep going only if the audio is playing
        exec.shutdown();
        try { synchronized (lock) { if (pr != null) pr.close(); } if (pfd != null) pfd.close(); } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        if (settings != null && settings.getVisibility() == View.VISIBLE) { settings.setVisibility(View.GONE); return; }
        super.onBackPressed();
    }

    void immersive() {
        try { immersiveNow(); } catch (Exception ignored) {}
    }

    void immersiveNow() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    void setBright(int pct) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.03f, pct / 100f);
        getWindow().setAttributes(lp);
    }

    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    // ====================== rendering ======================
    Bitmap render(int i, int width) {
        synchronized (lock) {
            PdfRenderer.Page pg = pr.openPage(i);
            try {
                int bw = width, bh = Math.max(1, Math.round(bw * (float) pg.getHeight() / pg.getWidth()));
                if (bh > 3600) { bw = Math.round(bw * 3600f / bh); bh = 3600; }
                Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } finally { pg.close(); }
        }
    }

    void ensure(int i) {
        if (i < 0 || i >= n || cache.get(i) != null || !inflight.add(i)) return;
        exec.submit(() -> {
            try {
                Bitmap b = render(i, renderW);
                h.post(() -> { cache.put(i, b); inflight.remove(i); if (pv != null) pv.invalidate(); });
            } catch (Throwable t) { inflight.remove(i); }
        });
    }

    /** Looks at a handful of pages to find the margins that are always empty, so the text can be drawn bigger. */
    void computeCrop() {
        if (!trim) { cl = ct = cr = cb = 0; pv.invalidate(); return; }
        exec.submit(() -> {
            try {
                float l = 1, t = 1, r = 1, b = 1;
                int hits = 0;
                int count = Math.min(8, Math.max(1, n - 2));
                for (int k = 0; k < count; k++) {
                    int i = n <= 4 ? Math.min(n - 1, k) : 1 + Math.round(k * (n - 3f) / Math.max(1, count - 1));
                    Bitmap s = render(Math.max(0, Math.min(n - 1, i)), 300);
                    int w = s.getWidth(), hh = s.getHeight();
                    int[] px = new int[w * hh];
                    s.getPixels(px, 0, w, 0, 0, w, hh);
                    int x0 = w, x1 = -1, y0 = hh, y1 = -1;
                    for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) {
                        int c = px[y * w + x];
                        if (((c >> 16 & 255) + (c >> 8 & 255) + (c & 255)) / 3 < 200) {
                            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
                        }
                    }
                    if (x1 < 0) continue;
                    hits++;
                    l = Math.min(l, x0 / (float) w); r = Math.min(r, (w - 1 - x1) / (float) w);
                    t = Math.min(t, y0 / (float) hh); b = Math.min(b, (hh - 1 - y1) / (float) hh);
                }
                if (hits == 0) return;
                final float pad = 0.012f;
                float fl = Math.max(0, l - pad), fr = Math.max(0, r - pad), ft = Math.max(0, t - pad), fb = Math.max(0, b - pad);
                if (1 - fl - fr < 0.5f || 1 - ft - fb < 0.5f) return;
                h.post(() -> { cl = fl; cr = fr; ct = ft; cb = fb; if (pv != null) pv.invalidate(); });
            } catch (Throwable ignored) {}
        });
    }

    // ====================== navigation ======================
    void goTo(int i, boolean haptic) {
        cur = Math.max(0, Math.min(n - 1, i));
        for (int k = -1; k <= 2; k++) ensure(cur + k);
        ensure(cur);
        if (pv != null) { pv.resetZoom(); pv.invalidate(); }
        updateLabel(cur);
        if (seek != null) seek.setProgress(cur);
        h.removeCallbacks(saver);
        h.postDelayed(saver, 700);
        h.removeCallbacks(prefetch);
        h.postDelayed(prefetch, 2500);
        if (haptic) lastManual = System.currentTimeMillis();
        if (haptic && pv != null) pv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        boolean last = cur == n - 1 && !item.done;
        if (donePill != null) donePill.setVisibility(last ? View.VISIBLE : View.GONE);
        if (last && haptic && !finishedToastShown) { finishedToastShown = true; Toast.makeText(this, "That was the last page", Toast.LENGTH_SHORT).show(); }
    }

    final Runnable saver = this::saveProgress;

    void saveProgress() {
        if (item != null && n > 0) Learn.setReading(this, item.id, cur, n);
    }

    void updateLabel(int page) {
        int pct = n <= 1 ? 100 : Math.round(100f * page / (n - 1));
        if (pageLbl != null) pageLbl.setText("Page " + (page + 1) + " of " + n + "  ·  " + pct + "%");
    }

    // ====================== audio ======================
    final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!ui) refreshPlay();
            h.postDelayed(this, 1200);
        }
    };

    void refreshPlay() {
        if (playGlyph == null || am == null) return;
        int t = am.isMusicActive() ? 2 : 1;
        if (playGlyph.type != t) { playGlyph.type = t; playGlyph.invalidate(); }
    }

    void togglePlay() {
        playBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (fileMode) {
            if (!mpReady) { Toast.makeText(this, "Loading the audio…", Toast.LENGTH_SHORT).show(); return; }
            if (!Player.isPlaying() && Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 92);
            Player.toggle(this);
            return;
        }
        // Not an in-app video: only pause/resume something that is really playing, never start a random track.
        if (am.isMusicActive()) {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            h.postDelayed(this::refreshPlay, 500);
        } else Toast.makeText(this, "Attach an audio file to this book first", Toast.LENGTH_SHORT).show();
    }

    String fmtT(double s) {
        int t = (int) Math.max(0, s), hh = t / 3600, m = t % 3600 / 60, ss = t % 60;
        return hh > 0 ? String.format(Locale.ENGLISH, "%d:%02d:%02d", hh, m, ss) : String.format(Locale.ENGLISH, "%d:%02d", m, ss);
    }

    void onTick(double t, double d, int st) {
        aT = t; aDur = d; aState = st;
        if (timeLbl != null) timeLbl.setText(fmtT(t) + " / " + fmtT(d));
        int g = st == 1 || st == 3 ? 2 : 1;
        if (playGlyph != null && playGlyph.type != g) { playGlyph.type = g; playGlyph.invalidate(); }
        long now = System.currentTimeMillis();
        if (now - lastSave > 5000 && t > 1) { lastSave = now; Learn.setAudio(this, item.id, (int) t, null); }
        if (al.size() > 0 && bi != null) {
            int j = al.wordAtTime(t);
            if (j >= 0) {
                int[] r = bi.sentenceAround(j);
                if (r[0] != hlFrom) { hlFrom = r[0]; hlTo = r[1]; if (hlOn && pv != null) pv.invalidate(); }
                if (followOn && st == 1 && now - lastManual > 5000 && pv != null && !pv.dragging && pv.zoom <= 1.01f && settings.getVisibility() != View.VISIBLE) {
                    int pg = bi.pageOf(j);
                    if (pg != cur) goTo(pg, false);
                }
            }
        }
        if (SyncService.running && SyncService.itemId == item.id && now - alReloaded > 10000) loadSyncData();
        updatePin();
    }

    void pickAudioFile() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 78);
        } catch (Exception e) { Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc != 78 || res != RESULT_OK || d == null || d.getData() == null) return;
        Uri u = d.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        String name = "audio";
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (ix >= 0) name = c.getString(ix); }
        } catch (Exception ignored) {}
        saveProgress();
        Learn.setAudioFile(this, item.id, u.toString(), name);
        recreate();
    }

    // ---- audio file backend ----
    void setupFile() {
        Player.readerOpen = true;
        Player.open(this, item);
        speedIdx = 0;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - Player.speed) < 0.01f) speedIdx = i;
        if (speedBtn != null) speedBtn.setText(SPEEDS[speedIdx] == (int) SPEEDS[speedIdx] ? (int) SPEEDS[speedIdx] + "x" : SPEEDS[speedIdx] + "x");
        Runnable ready = () -> {
            mpReady = true;
            aDur = Player.dur();
            aT = Player.pos();
            if (timeLbl != null) timeLbl.setText(fmtT(aT) + " / " + fmtT(aDur));
            if (jumpToAudio) jumpToAudioPage();
            updatePin();
        };
        Player.onReady = ready;
        if (Player.mp == null) Toast.makeText(this, "Can't open that audio file. Attach it again from the item.", Toast.LENGTH_LONG).show();
        else if (Player.ready) ready.run();
        h.postDelayed(fileTick, 250);
    }

    /** Opened from the notification: show the page the audio is on. */
    void jumpToAudioPage() {
        if (!jumpToAudio || !mpReady || bi == null || al.size() == 0) return;
        jumpToAudio = false;
        int j = al.wordAtTime(Player.pos());
        if (j >= 0) goTo(bi.pageOf(j), false);
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        if (in.getIntExtra("id", -1) != item.id) { setIntent(in); recreate(); return; }
        if (in.getBooleanExtra("fromNote", false)) { jumpToAudio = true; jumpToAudioPage(); }
    }

    final Runnable fileTick = new Runnable() {
        @Override public void run() {
            try {
                if (mpReady && Player.mp != null) onTick(Player.pos(), Player.dur(), Player.isPlaying() ? 1 : 2);
            } catch (Exception ignored) {}
            h.postDelayed(this, 250);
        }
    };

    void applySpeed() {
        Player.speed = SPEEDS[speedIdx];
        Player.applySpeed();
    }

    /** Jump to a time, optionally starting playback. */
    void seekAbs(double t, boolean play) {
        if (fileMode) {
            if (!mpReady) return;
            Player.seekMs((int) (t * 1000));
            if (play && !Player.isPlaying()) { Player.play(this); applySpeed(); }
        }
    }

    void seekBy(double d) { seekAbs(Math.max(0, Math.min(aDur > 0 ? aDur : 1e9, aT + d)), false); }

    void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        applySpeed();
        speedBtn.setText(SPEEDS[speedIdx] == (int) SPEEDS[speedIdx] ? (int) SPEEDS[speedIdx] + "x" : SPEEDS[speedIdx] + "x");
    }

    // ---- page <-> audio time ----
    void loadAnchors() {
        anchors.clear();
        try {
            JSONArray a = new JSONArray(item.sync.isEmpty() ? "[]" : item.sync);
            for (int i = 0; i < a.length(); i++) { JSONArray x = a.getJSONArray(i); anchors.add(new double[]{x.getDouble(0), x.getDouble(1)}); }
        } catch (JSONException ignored) {}
    }

    void saveAnchors() {
        JSONArray a = new JSONArray();
        try { for (double[] x : anchors) a.put(new JSONArray().put(x[0]).put(x[1])); } catch (JSONException ignored) {}
        item.sync = a.toString();
        Learn.setAudio(this, item.id, (int) Math.max(0, aT), item.sync);
    }

    // Pins are exact, manual links: "this page = this moment in the audio". Nothing is guessed between them.
    double[] pinFor(int page) {
        for (double[] x : anchors) if ((int) x[1] == page) return x;
        return null;
    }

    /** The pin with the latest time that the audio has already reached. */
    double[] lastPinReached(double t) {
        double[] best = null;
        for (double[] x : anchors) if (x[0] <= t + 0.5 && (best == null || x[0] > best[0])) best = x;
        return best;
    }

    String span(double d) {
        return (d < 0 ? "-" : "") + fmtT(Math.abs(d));
    }

    void updatePin() {
        if (pinLbl == null) return;
        String msg;
        double[] here = pinFor(cur);
        boolean showGo = false;
        int audioPage = -1;
        if (mpReady && bi != null && al.size() > 0) { int j = al.wordAtTime(aT); if (j >= 0) audioPage = bi.pageOf(j); }
        if (!mpReady) msg = "Loading the audio…";
        else if (audioPage >= 0) {
            msg = (audioPage == cur ? "The audio is on this page" : "The audio is on page " + (audioPage + 1)) + (here != null ? " · 📍 pinned at " + fmtT(here[0]) : "");
            if (audioPage != cur) { showGo = true; goPageBtn.setText("Go to page " + (audioPage + 1)); goPage = audioPage; }
        } else {
            double[] reached = lastPinReached(aT);
            if (here != null) {
                double d = aT - here[0];
                msg = "📍 Page " + (cur + 1) + " is pinned at " + fmtT(here[0]) + (Math.abs(d) <= 3 ? " · in sync" : " · audio is " + span(d) + " from it");
            } else if (anchors.isEmpty()) msg = "No pins yet. Find this page in the audio, then tap Pin.";
            else msg = "Page " + (cur + 1) + " has no pin yet.";
            if (reached != null && (int) reached[1] != cur) {
                showGo = true;
                goPageBtn.setText("Go to page " + ((int) reached[1] + 1));
                goPage = (int) reached[1];
                if (here == null) msg += " Audio is " + span(aT - reached[0]) + " past 📍 page " + ((int) reached[1] + 1) + ".";
            }
        }
        if (pinIcon != null && pinIcon.filled != (here != null)) { pinIcon.filled = here != null; pinIcon.invalidate(); }
        pinLbl.setText(msg);
        goPageBtn.setVisibility(showGo ? View.VISIBLE : View.GONE);
        updateSyncLabel();
    }

    // ====================== sync with the book ======================
    boolean syncing() { return SyncService.running && SyncService.itemId == item.id && !SyncService.cancel; }

    boolean stopping() { return SyncService.running && SyncService.itemId == item.id && SyncService.cancel; }

    int syncPct() {
        if (syncing()) { double tot = SyncService.totalSec; return tot > 0 ? (int) Math.min(100, SyncService.doneSec * 100 / tot) : 0; }
        return SyncService.pctFor(this, item.id);
    }

    String etaText() {
        int e = SyncService.etaMinutes();
        return e <= 0 ? "" : " · about " + (e >= 60 ? (e / 60) + " h " + (e % 60) + " min" : e + " min") + " left";
    }

    void updateSyncLabel() {
        if (syncIcon == null) return;
        boolean run = syncing(), full = SyncService.fullFor(this, item.id) || (run ? SyncService.idle : SyncService.winReady(this, item.id));
        int pct = run || SyncService.pctFor(this, item.id) > 0 ? syncPct() : 0;
        String label = full ? "Synced" : run ? pct + "%" : pct > 0 ? "Resume " + pct + "%" : "Sync";
        if (!label.contentEquals(syncIcon.label.getText())) syncIcon.label.setText(label);
        if (syncIcon.pct != (full ? 100 : pct) || syncIcon.done != full || syncIcon.badge != (!full && !run && pct == 0)) {
            syncIcon.pct = full ? 100 : pct;
            syncIcon.done = full;
            syncIcon.badge = !full && !run && pct == 0;   // a dot that says "this is worth doing"
            syncIcon.invalidate();
        }
    }

    /** (Re)load the book index and the audio map from disk, off the main thread. */
    void loadSyncData() {
        alReloaded = System.currentTimeMillis();
        final int id = item.id;
        new Thread(() -> {
            try {
                BookIndex b = BookText.ready(this, id) ? BookText.index(this, id) : null;
                long tl = Dbg.now();
                Alignment a = Alignment.load(BookText.alignFile(this, id));
                Dbg.p("Reader", "sync data loaded", tl, "book words " + (b == null ? "none" : b.count()) + ", aligned " + a.size() + (SyncService.running ? ", sync running" : ""));
                ui(() -> {
                    bi = b; al = a; jumpToAudioPage(); updatePin(); if (pv != null) pv.invalidate();
                    // a sync that was interrupted (app closed by the system, low battery) carries on by itself
                    if (b != null && fileMode && !SyncService.running && SyncService.wanted(this, id) && !SyncService.fullFor(this, id) && Whisper.hasModel(this)) {
                        Dbg.i("Sync", "auto-resuming from the reader (was interrupted, not stopped by the user)");
                        SyncService.start(this, id);
                        Toast.makeText(this, "Continuing the sync in the background", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable e) { Dbg.e("Reader", "loading sync data failed", e); }
        }).start();
    }

    final Runnable syncPoll = new Runnable() {
        @Override public void run() {
            if (fdlg == null || !syncPanel) return;
            showSyncState();
            h.postDelayed(this, 1000);
        }
    };
    boolean syncPanel;

    void showSync() {
        if (!mpReady) { Toast.makeText(this, "The audio is still loading", Toast.LENGTH_SHORT).show(); return; }
        openPanel("Sync with the book");
        syncPanel = true;
        fdlg.setOnDismissListener(d -> syncPanel = false);
        showSyncState();
        h.postDelayed(syncPoll, 1000);
    }

    void showSyncState() {
        if (fmsg == null) return;
        if (stopping()) {
            setMsg("Stopping… your progress is saved.", -1);
            setBtns("Close", (Runnable) this::closePanel);
        } else if (syncing() && SyncService.dlPct >= 0) {
            setMsg("Downloading the speech model… " + SyncService.dlPct + "%\n\nIt keeps going in the background. When it is done the sync starts by itself.", SyncService.dlPct);
            setBtns("Hide", (Runnable) this::closePanel, "Cancel", (Runnable) () -> { SyncService.stop(this); showSyncState(); });
        } else if (syncing() && SyncService.idle) {
            setMsg("Synced around where you are listening.\n\nPlay page, Find page and the sentence highlight work here. It follows you as you play, and maps the new place within a minute or two if you jump somewhere else.", -1);
            setBtns("Close", (Runnable) this::closePanel, "Stop", (Runnable) () -> { SyncService.stop(this); showSyncState(); });
        } else if (syncing()) {
            setMsg("Syncing around your position… " + syncPct() + "%" + etaText() + "\n\nYou can leave this screen or turn the screen off. It keeps going in the background and shows a notification. Stopping is safe: it continues from where it stopped.", syncPct());
            setBtns("Hide", (Runnable) this::closePanel, "Stop", (Runnable) () -> { SyncService.stop(this); showSyncState(); });
        } else if (SyncService.fullFor(this, item.id)) {
            setMsg("This audiobook is synced.\n\nPlay page and Find page are instant now, and the sentence being read is highlighted in the book.", -1);
            setBtns("Close", (Runnable) this::closePanel, "Sync again", (Runnable) () -> { BookText.clearAudioData(this, item.id); al = new Alignment(); hlFrom = hlTo = -1; showSyncState(); });
        } else if (SyncService.winReady(this, item.id)) {
            setMsg("Synced around where you were listening.\n\nPlay page, Find page and the sentence highlight work there. If you go somewhere else, Find page and Play page map that place on demand (about a minute), or tap Sync here to map it now.", -1);
            setBtns("Close", (Runnable) this::closePanel, "Sync here", (Runnable) () -> startSync(Whisper.which(this)),
                    "Start over", (Runnable) () -> { BookText.clearAudioData(this, item.id); al = new Alignment(); hlFrom = hlTo = -1; updateSyncLabel(); showSyncState(); });
        } else {
            boolean part = SyncService.pctFor(this, item.id) > 0;
            if (!SyncService.error.isEmpty() && !part) { /* show the reason once */ }
            setMsg((part ? "Paused at " + syncPct() + "%. It continues from there.\n\n" : "")
                    + "This listens to a few short samples around where you are in the audiobook and links them to the book. Then Play page and Find page are instant there, and the sentence being read is highlighted. It follows you as you play or jump.\n\n"
                    + "Rough guess: Faster about 2 minutes, Accurate about 5 minutes. It runs in the background."
                    + (SyncService.error.isEmpty() ? "" : "\n\nLast stop: " + SyncService.error), -1);
            setBtns("Accurate", (Runnable) () -> startSync(0), "Faster", (Runnable) () -> startSync(1), "Cancel", (Runnable) this::closePanel);
        }
    }

    /** Samsung and others put apps to sleep to save battery. Ask once to let this app keep working. */
    void askBackground() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !Store.flag(this, "askedBg", false)) {
                Store.setFlag(this, "askedBg", true);
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
    }

    void startSync(int model) {
        Dbg.i("Sync", "start tapped, model " + (model == 0 ? "accurate" : "faster") + ", item " + item.id);
        Whisper.setWhich(this, model);
        syncPanel = false;
        h.removeCallbacks(syncPoll);
        ensureModel(() -> ensureText(() -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 91);
            SyncService.start(this, item.id);
            h.postDelayed(this::askBackground, 1800);
            syncPanel = true;
            h.postDelayed(() -> { showSyncState(); h.postDelayed(syncPoll, 1000); }, 700);
        }));
    }

    void pinHere() {
        if (!mpReady) { Toast.makeText(this, "The audio is still loading", Toast.LENGTH_SHORT).show(); return; }
        double t = Math.floor(aT);
        double[] old = pinFor(cur);
        anchors.removeIf(x -> (int) x[1] == cur);
        anchors.add(new double[]{t, cur});
        Dbg.i("Pin", "page " + (cur + 1) + " = " + fmtT(t));
        anchors.sort(Comparator.comparingDouble(x -> x[1]));
        saveAnchors();
        performHaptic();
        Toast.makeText(this, (old == null ? "Pinned page " : "Moved pin for page ") + (cur + 1) + " to " + fmtT(t)
                + (old != null ? " (was " + fmtT(old[0]) + ")" : ""), Toast.LENGTH_SHORT).show();
        updatePin();
    }

    void performHaptic() { if (playBtn != null) playBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM); }

    void playPageAudio() {
        if (!mpReady) { Toast.makeText(this, "The audio is still loading", Toast.LENGTH_SHORT).show(); return; }
        double[] p = pinFor(cur);
        if (p != null) { Dbg.i("Play", "page " + (cur + 1) + " from pin -> " + fmtT(p[0])); seekAbs(p[0], true); return; }
        final int target = cur;
        if (bi != null && al.size() > 0) {
            double ts = al.timeOfWord(bi.pageStart[target]);
            if (ts < 0 && target + 1 < bi.pages) ts = al.timeOfWord(bi.pageStart[target] + 12);
            if (ts >= 0) {
                lastManual = System.currentTimeMillis();
                Dbg.i("Play", "page " + (target + 1) + " from sync data -> " + fmtT(ts));
                seekAbs(Math.max(0, ts - 0.3), true);
                Toast.makeText(this, "Page " + (target + 1) + " · " + fmtT(ts), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Dbg.i("Play", "page " + (target + 1) + " has no pin and is not in the sync data" + (syncing() ? " (sync running)" : "") + ", " + (syncing() ? "telling user" : "will search"));
        // a page the background prefetch already located
        if (bi != null && SyncService.goalWordReq == bi.pageStart[target] && SyncService.goalTimeOut >= 0) {
            double ts = SyncService.goalTimeOut;
            Dbg.i("Play", "page " + (target + 1) + " from prefetch -> " + fmtT(ts));
            lastManual = System.currentTimeMillis();
            seekAbs(Math.max(0, ts - 0.3), true);
            Toast.makeText(this, "Page " + (target + 1) + " · " + fmtT(ts), Toast.LENGTH_SHORT).show();
            return;
        }
        if (syncing()) { openPanel("Find page " + (target + 1) + " in the audio"); runLocate(target); return; }
        prepare("Find page " + (target + 1) + " in the audio",
                "Page " + (target + 1) + " isn't mapped yet. I listen to a few short pieces of the audio and check them against the book. It usually takes under a minute, and the result is remembered.",
                "Find it", true, () -> runLocate(target));
    }

    /** Asks the sync service when the first word of a page is spoken, and waits for the answer. */
    void runLocate(int target) {
        final boolean[] cancel = fcancel;
        final long started = System.currentTimeMillis();
        setMsg("Looking for page " + (target + 1) + "…", -1);
        setBtns("Cancel", (Runnable) () -> { SyncService.goalWordReq = -1; closePanel(); });
        new Thread(() -> {
            BookIndex b;
            try { b = BookText.index(this, item.id); } catch (Exception e) { b = null; }
            final BookIndex bk = b;
            if (bk == null) { ui(() -> { if (!cancel[0]) fail("Couldn't read the book's text."); }); return; }
            ui(() -> {
                if (cancel[0]) return;
                SyncService.goalTimeOut = -1;
                SyncService.goalWordReq = bk.pageStart[target];
                if (!SyncService.running) SyncService.start(this, item.id);
                Dbg.i("Locate", "page " + (target + 1) + " word " + bk.pageStart[target]);
                final long[] startedSvc = {System.currentTimeMillis()};
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (cancel[0] || isFinishing()) { return; }
                        double gt = SyncService.goalTimeOut;
                        long sec = (System.currentTimeMillis() - started) / 1000;
                        if (gt >= 0) {
                            SyncService.goalWordReq = -1;
                            Dbg.i("Locate", "page " + (target + 1) + " at " + (int) gt + " s after " + sec + " s");
                            setMsg("Page " + (target + 1) + " is read at about " + fmtT(gt) + ". Checked against the book. Took " + sec + " s.", -1);
                            setBtns("Play and pin", (Runnable) () -> { pinAt(target, gt); seekAbs(Math.max(0, gt - 0.3), true); closePanel(); },
                                    "Play only", (Runnable) () -> { seekAbs(Math.max(0, gt - 0.3), true); closePanel(); });
                            loadSyncData();
                        } else if (gt == -2) {
                            SyncService.goalWordReq = -1;
                            fail("I couldn't confirm page " + (target + 1) + " in the audio. The audio may skip it, be abridged, or differ from this edition.");
                        } else if (!SyncService.running && System.currentTimeMillis() - startedSvc[0] > 4000) {
                            SyncService.goalWordReq = -1;
                            fail("The listening stopped" + (SyncService.error.isEmpty() ? "." : ": " + SyncService.error));
                        } else {
                            setMsg("Looking for page " + (target + 1) + "… " + sec + " s\n\nListening to short pieces of the audio and checking them against the book.", -1);
                            h.postDelayed(this, 700);
                        }
                    }
                }, 700);
            });
        }).start();
    }

    /** Find my page when the spot isn't mapped yet: the sync service listens at the playhead. */
    void runFindFast() {
        final boolean[] cancel = fcancel;
        final double t0 = aT;
        final long started = System.currentTimeMillis();
        setMsg("Listening to the audio at " + fmtT(t0) + "…", -1);
        setBtns("Cancel", (Runnable) this::closePanel);
        SyncService.hereWord = -1;
        if (!SyncService.running) SyncService.start(this, item.id);
        final long[] svc = {System.currentTimeMillis()};
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (cancel[0] || isFinishing()) return;
                int w = SyncService.hereWord;
                long sec = (System.currentTimeMillis() - started) / 1000;
                if (w >= 0 && bi != null) {
                    final int pg = bi.pageOf(w);
                    int[] sn = bi.sentenceAround(w);
                    StringBuilder sb = new StringBuilder();
                    for (int k = sn[0]; k <= Math.min(sn[1], sn[0] + 18); k++) sb.append(bi.w[k]).append(' ');
                    Dbg.i("Find", "listened: " + fmtT(t0) + " -> page " + (pg + 1) + " after " + sec + " s");
                    setMsg("At " + fmtT(t0) + " the audio is on page " + (pg + 1) + ". Took " + sec + " s.\n\nReading: “" + sb.toString().trim() + (sn[1] > sn[0] + 18 ? "…" : "") + "”", -1);
                    setBtns("Pin page " + (pg + 1) + " here", (Runnable) () -> { goTo(pg, false); pinAt(pg, t0); closePanel(); },
                            "Go to page", (Runnable) () -> { goTo(pg, false); closePanel(); });
                    loadSyncData();
                } else if (w >= 0) {
                    h.postDelayed(this, 500);   // the book text is still loading
                } else if (!SyncService.running && System.currentTimeMillis() - svc[0] > 4000) {
                    fail("I couldn't match this part with confidence" + (SyncService.error.isEmpty() ? "." : ": " + SyncService.error)
                            + "\n\nPause on a stretch of someone speaking the book (not music, an intro or silence) and try again.");
                } else {
                    setMsg("Listening to the audio at " + fmtT(t0) + "… " + sec + " s", -1);
                    h.postDelayed(this, 700);
                }
            }
        }, 700);
    }

    /** After you rest on a page, find where it is read so that Play page is instant. */
    final Runnable prefetch = this::prefetchNow;

    void prefetchNow() {
        try {
            if (isFinishing() || fdlg != null || bi == null || item == null || !mpReady || !fileMode) return;
            if (!SyncService.wanted(this, item.id) || !Whisper.hasModel(this) || pinFor(cur) != null) return;
            if (al.size() > 0 && al.timeOfWord(bi.pageStart[cur]) >= 0) return;
            if (SyncService.goalWordReq == bi.pageStart[cur]) return;
            Dbg.i("Prefetch", "locating page " + (cur + 1) + " in the background");
            SyncService.goalTimeOut = -1;
            SyncService.goalWordReq = bi.pageStart[cur];
            if (!SyncService.running) SyncService.start(this, item.id);
        } catch (Throwable e) { Dbg.e("Prefetch", "failed", e); }
    }

    void showPins() {
        android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int ink = INK[theme], paper = PAPER[theme];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackground(rr(paper, 22));
        TextView ttl = new TextView(this);
        ttl.setText("Pins · " + anchors.size());
        ttl.setTextSize(18);
        ttl.setTypeface(Typeface.DEFAULT_BOLD);
        ttl.setTextColor(ink);
        box.addView(ttl);
        TextView add = new TextView(this);
        add.setText("📍  Pin page " + (cur + 1) + " at " + fmtT(Math.floor(aT)));
        add.setTextSize(15);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(paper);
        add.setPadding(dp(14), dp(13), dp(14), dp(13));
        add.setBackground(rr(ink, 16));
        add.setOnClickListener(v -> { pinHere(); dlg.dismiss(); showPins(); });
        box.addView(add, lpm(-1, -2, 0, 10, 0, 4));
        TextView sub = new TextView(this);
        sub.setText("Each pin links a page to a moment in the audio. Tap a pin to go to both. To change one, open that page and pin it again.");
        sub.setTextSize(12);
        sub.setTextColor((ink & 0x00FFFFFF) | 0x99000000);
        sub.setPadding(0, dp(4), 0, dp(10));
        box.addView(sub);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<double[]> byPage = new ArrayList<>(anchors);
        byPage.sort(Comparator.comparingDouble(x -> x[1]));
        if (byPage.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("No pins yet.");
            e.setTextColor(ink);
            e.setPadding(0, dp(8), 0, dp(8));
            list.addView(e);
        }
        double prevT = -1;
        for (double[] x : byPage) {
            boolean odd = x[0] < prevT;
            prevT = x[0];
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(10), dp(8), dp(10));
            r.setBackground(rr((ink & 0x00FFFFFF) | 0x14000000, 14));
            TextView a = new TextView(this);
            a.setText("Page " + ((int) x[1] + 1) + "   ·   " + fmtT(x[0]) + (odd ? "   ⚠ earlier than a lower page" : ""));
            a.setTextSize(15);
            a.setTextColor(ink);
            r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextSize(16);
            del.setTextColor(ink);
            del.setPadding(dp(14), dp(6), dp(10), dp(6));
            del.setOnClickListener(v -> { anchors.remove(x); saveAnchors(); updatePin(); dlg.dismiss(); showPins(); });
            r.addView(del);
            r.setOnClickListener(v -> { goTo((int) x[1], false); if (mpReady) seekAbs(x[0], false); dlg.dismiss(); });
            list.addView(r, lpm(-1, -2, 0, 0, 0, 6));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        if (!anchors.isEmpty()) {
            clearArmed = false;
            TextView clr = new TextView(this);
            clr.setText("Clear all pins");
            clr.setTextSize(13);
            clr.setTextColor(0xFFD05A5A);
            clr.setPadding(dp(4), dp(12), dp(4), dp(4));
            clr.setOnClickListener(v -> {
                if (!clearArmed) { clearArmed = true; clr.setText("Tap again to clear all " + anchors.size() + " pins"); return; }
                anchors.clear(); saveAnchors(); updatePin(); dlg.dismiss();
            });
            box.addView(clr);
        }
        dlg.setContentView(box);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    // ====================== find (speech + book text) ======================
    android.app.Dialog fdlg;
    LinearLayout fbtns;
    TextView fmsg;
    ProgressBar fbar;
    boolean[] fcancel = {false};

    void ui(Runnable r) { if (!isFinishing()) runOnUiThread(r); }

    void closePanel() {
        fcancel[0] = true;
        if (fdlg != null) { try { fdlg.dismiss(); } catch (Exception ignored) {} fdlg = null; }
    }

    void openPanel(String title) {
        closePanel();
        fcancel = new boolean[]{false};
        final boolean[] mine = fcancel;
        int ink = INK[theme], paper = PAPER[theme];
        fdlg = new android.app.Dialog(this);
        fdlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(14));
        box.setBackground(rr(paper, 22));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ink);
        box.addView(t);
        fmsg = new TextView(this);
        fmsg.setTextSize(14);
        fmsg.setTextColor(ink);
        fmsg.setLineSpacing(0, 1.15f);
        fmsg.setPadding(0, dp(10), 0, dp(8));
        box.addView(fmsg);
        fbar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fbar.setMax(100);
        fbar.setVisibility(View.GONE);
        box.addView(fbar, new LinearLayout.LayoutParams(-1, dp(8)));
        fbtns = new LinearLayout(this);
        fbtns.setGravity(Gravity.END);
        box.addView(fbtns, lpm(-1, -2, 0, 14, 0, 0));
        fdlg.setContentView(box);
        fdlg.setOnCancelListener(d -> mine[0] = true);
        Window w = fdlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        fdlg.show();
    }

    /** progress: -1 = busy with no number, 0..100 = bar */
    void setMsg(String text, int progress) {
        if (fmsg == null) return;
        fmsg.setText(text);
        fbar.setVisibility(progress >= 0 ? View.VISIBLE : View.GONE);
        if (progress >= 0) fbar.setProgress(progress);
    }

    void setBtns(Object... pairs) {
        fbtns.removeAllViews();
        int ink = INK[theme], paper = PAPER[theme];
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String label = (String) pairs[i];
            Runnable r = (Runnable) pairs[i + 1];
            boolean primary = i == 0 && pairs.length > 2 || (pairs.length == 2 && !label.equals("Cancel") && !label.equals("Close"));
            TextView b = new TextView(this);
            b.setText(label);
            b.setTextSize(14);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(16), dp(11), dp(16), dp(11));
            b.setTextColor(primary ? paper : ink);
            b.setBackground(rr(primary ? ink : ((ink & 0x00FFFFFF) | 0x1F000000), 14));
            b.setOnClickListener(v -> r.run());
            fbtns.addView(b, lpm(-2, -2, 8, 0, 0, 0));
        }
    }

    void fail(String msg) {
        setMsg(msg, -1);
        setBtns("Close", (Runnable) this::closePanel);
    }

    /** Makes sure the speech model and the book text are ready, then runs `go`. */
    void prepare(String title, String intro, String startLabel, boolean confirm, Runnable go) {
        if (!mpReady) { Toast.makeText(this, "The audio is still loading", Toast.LENGTH_SHORT).show(); return; }
        if (Player.isPlaying()) Player.pause(this);
        openPanel(title);
        Runnable start = () -> ensureModel(() -> ensureText(go));
        if (confirm || !Whisper.hasModel(this) || !BookText.ready(this, item.id)) {
            String extra = "";
            if (!Whisper.hasModel(this)) extra += "\n\nFirst time: it downloads a small offline speech model (" + Whisper.SIZES[Whisper.which(this)] + ", needs internet once) and reads the book's text. Both stay on your phone.";
            else if (!BookText.ready(this, item.id)) extra += "\n\nFirst time: it reads the book's text once (a minute or so).";
            setMsg(intro + extra, -1);
            setBtns(startLabel, (Runnable) start, "Cancel", (Runnable) this::closePanel);
        } else start.run();
    }

    void ensureModel(Runnable next) {
        if (Whisper.hasModel(this)) { next.run(); return; }
        final boolean[] cancel = fcancel;
        final long t0 = System.currentTimeMillis();
        // the download runs in the background service: closing this panel or leaving the book does not stop it
        SyncService.dlPct = 0;
        if (!SyncService.running) SyncService.start(this, item.id);
        setMsg("Downloading the speech model… 0%\n\nIt keeps going in the background. You can close this and use your phone.", 0);
        setBtns("Hide", (Runnable) this::closePanel, "Cancel download", (Runnable) () -> { SyncService.stop(this); closePanel(); });
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (cancel[0] || isFinishing()) return;
                if (Whisper.hasModel(ReaderActivity.this)) { next.run(); return; }
                int pc = SyncService.dlPct;
                if (!SyncService.running && System.currentTimeMillis() - t0 > 4000) {
                    fail("The download didn't finish. Check your internet and try again." + (SyncService.error.isEmpty() ? "" : "\n\n" + SyncService.error));
                    return;
                }
                if (pc >= 0) setMsg("Downloading the speech model… " + pc + "%\n\nIt keeps going in the background. You can close this and use your phone.", pc);
                h.postDelayed(this, 500);
            }
        }, 500);
    }

    void ensureText(Runnable next) {
        if (BookText.ready(this, item.id)) { next.run(); return; }
        final boolean[] cancel = fcancel;
        setMsg("Reading the book's text… 0%", 0);
        setBtns("Cancel", (Runnable) this::closePanel);
        new Thread(() -> {
            try {
                BookIndex got = BookText.extract(this, item, pc -> ui(() -> { if (!cancel[0]) setMsg("Reading the book's text… " + pc + "%", pc); }), cancel);
                if (BookText.looksScanned(got)) {
                    BookText.forget(this, item.id);
                    ui(() -> fail("This PDF is made of page pictures (a scan), so there is no text to match against."));
                } else { loadSyncData(); ui(() -> { if (!cancel[0]) next.run(); }); }
            } catch (Exception e) {
                ui(() -> { if (!cancel[0]) fail("Couldn't read the text of this PDF."); });
            }
        }).start();
    }

    static class Probe { double t; PageMatcher.Result r; String text; }

    /** Listens to 20 seconds starting at t and works out which page they come from. Slow: background thread only. */
    Probe probe(double t) throws Exception {
        Probe p = new Probe();
        p.t = t;
        long t0 = Dbg.now();
        float[] pcm = AudioClip.decode(this, item.audio, t, 12);
        long msDec = Dbg.ms(t0);
        if (pcm.length < 16000 * 5) { Dbg.w("Probe", "at " + (int) t + " s: too little audio (" + pcm.length / 16000 + " s)"); return null; }
        long t1 = Dbg.now();
        String txt = Whisper.transcribe(this, pcm);
        long msTr = Dbg.ms(t1);
        if (txt == null) throw new Exception("speech model failed");
        p.text = txt.trim();
        long t2 = Dbg.now();
        p.r = BookText.matcher(this, item.id, n).match(p.text);
        Dbg.p("Probe", "listen at " + (int) t + " s", t0, "decode " + msDec + " ms, speech " + msTr + " ms, match " + Dbg.ms(t2) + " ms, heard " + p.text.split("\\s+").length + " words -> " + (p.r == null ? "no result" : "page " + (p.r.page + 1) + (p.r.sure ? " sure" : " not sure")));
        if (p.r != null && p.r.sure) BookText.addSample(this, item.id, t, p.r.page);   // remember it: later searches start closer
        return p;
    }

    String quote(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 110 ? "“" + s.substring(0, 110) + "…”" : "“" + s + "”";
    }

    void runFindMine() {
        final double t0 = aT;
        final boolean[] cancel = fcancel;
        setMsg("Listening to the audio at " + fmtT(t0) + "…", -1);
        setBtns("Cancel", (Runnable) this::closePanel);
        new Thread(() -> {
            try {
                Probe p = probe(Math.max(0, t0 - 3));
                if (cancel[0]) return;
                ui(() -> {
                    if (cancel[0]) return;
                    if (p == null || p.r == null || !p.r.sure) {
                        fail("I couldn't match this part with confidence" + (p != null && !p.text.isEmpty() ? ".\n\nHeard: " + quote(p.text) : ".")
                                + "\n\nPause on a stretch of someone speaking the book (not music, an intro or silence) and try again.");
                        return;
                    }
                    final int pg = p.r.page;
                    setMsg("At " + fmtT(t0) + " the audio is on page " + (pg + 1) + (p.r.spansTwo ? " (close to the break with page " + (p.r.second + 1) + ")" : "")
                            + ".\n\nHeard: " + quote(p.text), -1);
                    setBtns("Pin page " + (pg + 1) + " here", (Runnable) () -> { goTo(pg, false); pinAt(pg, t0); closePanel(); },
                            "Go to page", (Runnable) () -> { goTo(pg, false); closePanel(); });
                });
            } catch (Exception e) {
                ui(() -> { if (!cancel[0]) fail("Couldn't read that audio file."); });
            }
        }).start();
    }

    void findMyPage() {
        if (mpReady && bi != null && al.size() > 0) {
            int j = al.wordAtTime(aT);
            if (j >= 0) {
                final int pg = bi.pageOf(j);
                final double t0 = aT;
                Dbg.i("Find", "instant from sync: " + fmtT(t0) + " -> page " + (pg + 1));
                openPanel("Find my page");
                int[] sn = bi.sentenceAround(j);
                StringBuilder sb = new StringBuilder();
                for (int k = sn[0]; k <= Math.min(sn[1], sn[0] + 18); k++) sb.append(bi.w[k]).append(' ');
                setMsg("At " + fmtT(t0) + " the audio is on page " + (pg + 1) + ".\n\nReading: “" + sb.toString().trim() + (sn[1] > sn[0] + 18 ? "…" : "") + "”", -1);
                setBtns("Go to page " + (pg + 1), (Runnable) () -> { goTo(pg, false); closePanel(); },
                        "Pin it here", (Runnable) () -> { goTo(pg, false); pinAt(pg, t0); closePanel(); });
                return;
            }
        }
        Dbg.i("Find", "no sync data at " + fmtT(aT) + ", listening instead");
        if (syncing()) { openPanel("Find my page"); runFindFast(); return; }
        findMyPageListening();
    }

    void findMyPageListening() { prepare("Find my page", "This listens to a few seconds of the audio at your position and finds the page in the book.", "Find", false, this::runFindFast); }

    /** Where to listen next. Only used to choose where to probe, never as the answer. */
    double guessTime(double[] lo, double[] hi, int target, double dur) {
        double rate = dur > 0 ? (n - 1) / dur : 0.01;
        if (lo != null && hi != null && hi[1] > lo[1]) return lo[0] + (target - lo[1]) * (hi[0] - lo[0]) / (hi[1] - lo[1]);
        if (lo != null) { double r = lo[0] > 60 && lo[1] > 0 ? lo[1] / lo[0] : rate; return lo[0] + (target - lo[1]) / r; }
        if (hi != null) { double r = hi[0] > 60 && hi[1] > 0 ? hi[1] / hi[0] : rate; return hi[0] - (hi[1] - target) / r; }
        return target * dur / Math.max(1, n - 1);
    }

    void runSearch(int target) {
        final boolean[] cancel = fcancel;
        final double dur = aDur;
        Dbg.i("Search", "looking for page " + (target + 1) + " (audio " + (int) dur + " s)");
        new Thread(() -> {
            long started = System.currentTimeMillis();
            double[] lo = null, hi = null, exact = null;
            List<double[]> refs = new ArrayList<>(anchors);
            refs.addAll(BookText.samples(this, item.id));
            for (double[] k : refs) {
                if ((int) k[1] == target && (exact == null || k[0] < exact[0])) exact = k;
                if (k[1] < target && (lo == null || k[1] > lo[1] || (k[1] == lo[1] && k[0] > lo[0]))) lo = new double[]{k[0], k[1]};
                if (k[1] > target && (hi == null || k[1] < hi[1] || (k[1] == hi[1] && k[0] < hi[0]))) hi = new double[]{k[0], k[1]};
            }
            if (exact != null) {
                // already checked earlier: no listening needed
                final double et = exact[0];
                ui(() -> {
                    if (cancel[0]) return;
                    setMsg("Page " + (target + 1) + " is read at about " + fmtT(et) + " (checked earlier, so this was instant).", -1);
                    setBtns("Play and pin", (Runnable) () -> { pinAt(target, et); seekAbs(et, true); closePanel(); },
                            "Play only", (Runnable) () -> { seekAbs(et, true); closePanel(); });
                });
                return;
            }
            double t = Math.max(0, Math.min(dur - 25, guessTime(lo, hi, target, dur)));
            double found = -1;
            int unclear = 0;
            List<Double> tried = new ArrayList<>();
            try {
                for (int i = 0; i < 10 && found < 0; i++) {
                    if (cancel[0]) return;
                    final int step = i;
                    final double tt = t;
                    ui(() -> { if (!cancel[0]) { setMsg("Searching for page " + (target + 1) + "… checking " + fmtT(tt) + " (step " + (step + 1) + " of up to 10)", step * 10); setBtns("Cancel", (Runnable) this::closePanel); } });
                    tried.add(t);
                    Probe p = probe(t);
                    if (p == null || p.r == null || !p.r.sure) {
                        if (++unclear > 3) break;
                        t = Math.min(dur - 25, t + 30);
                        continue;
                    }
                    unclear = 0;
                    int pg = p.r.page;
                    if (pg == target || (p.r.spansTwo && p.r.second == target)) { found = t; break; }
                    if (pg < target) { if (lo == null || pg > lo[1] || (pg == lo[1] && t > lo[0])) lo = new double[]{t, pg}; }
                    else { if (hi == null || pg < hi[1] || (pg == hi[1] && t < hi[0])) hi = new double[]{t, pg}; }
                    double nt = guessTime(lo, hi, target, dur);
                    if (Math.abs(nt - t) < 8) nt = t + (pg < target ? 12 : -12);
                    nt = Math.max(0, Math.min(dur - 25, nt));
                    boolean again = false;
                    for (double x : tried) if (Math.abs(x - nt) < 3) again = true;
                    if (again) break;
                    t = nt;
                }
                // walk back a little so playback starts near the top of the page
                if (found > 0) {
                    for (int k = 0; k < 4 && found >= 12; k++) {
                        if (cancel[0]) return;
                        final double ft = found;
                        ui(() -> { if (!cancel[0]) setMsg("Found page " + (target + 1) + " around " + fmtT(ft) + ". Finding where it starts…", 90); });
                        Probe b = probe(found - 12);
                        if (b != null && b.r != null && b.r.sure && (b.r.page == target || (b.r.spansTwo && b.r.second == target && b.r.page != target - 1))) found -= 12;
                        else break;
                    }
                }
            } catch (Exception e) {
                ui(() -> { if (!cancel[0]) fail("Couldn't read that audio file."); });
                return;
            }
            if (cancel[0]) { Dbg.i("Search", "cancelled"); return; }
            Dbg.i("Search", "page " + (target + 1) + (found >= 0 ? " found at " + (int) found + " s" : " NOT found") + " after " + tried.size() + " probes, " + (System.currentTimeMillis() - started) + " ms");
            final double ft = found;
            final double[] flo = lo;
            ui(() -> {
                if (cancel[0]) return;
                if (ft >= 0) {
                    setMsg("Page " + (target + 1) + " is read at about " + fmtT(ft) + ". Checked against the book, not a guess. Took " + (System.currentTimeMillis() - started) / 1000 + " s.", -1);
                    setBtns("Play and pin", (Runnable) () -> { pinAt(target, ft); seekAbs(ft, true); closePanel(); },
                            "Play only", (Runnable) () -> { seekAbs(ft, true); closePanel(); });
                } else {
                    String near = flo != null ? "\n\nClosest I could verify: page " + ((int) flo[1] + 1) + " at " + fmtT(flo[0]) + "." : "";
                    if (flo != null) setBtns("Go there", (Runnable) () -> { seekAbs(flo[0], false); closePanel(); }, "Close", (Runnable) this::closePanel);
                    else setBtns("Close", (Runnable) this::closePanel);
                    setMsg("I couldn't confirm page " + (target + 1) + " in the audio. The audio may skip it, be abridged, or differ from this edition." + near, -1);
                }
            });
        }).start();
    }

    void pinAt(int page, double t) {
        Dbg.i("Pin", "page " + (page + 1) + " = " + fmtT(Math.floor(t)));
        anchors.removeIf(x -> (int) x[1] == page);
        anchors.add(new double[]{Math.floor(t), page});
        anchors.sort(Comparator.comparingDouble(x -> x[1]));
        saveAnchors();
        updatePin();
        Toast.makeText(this, "Pinned page " + (page + 1) + " to " + fmtT(Math.floor(t)), Toast.LENGTH_SHORT).show();
    }

    // ====================== UI ======================
    void build() {
        root = new FrameLayout(this);
        pv = new PageView();
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        // top bar
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(26), dp(14), dp(8));
        back = new Glyph(0);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        title = new TextView(this);
        title.setText(item.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextSize(17);
        aa.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        aa.setGravity(Gravity.CENTER);
        aa.setOnClickListener(v -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        top.addView(aa, new LinearLayout.LayoutParams(dp(48), dp(40)));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom area: done pill, settings panel, controls
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);

        donePill = new LinearLayout(this);
        donePill.setGravity(Gravity.CENTER);
        TextView dt = new TextView(this);
        dt.setText("Finished? Mark this book done");
        dt.setTextSize(14);
        dt.setTypeface(Typeface.DEFAULT_BOLD);
        dt.setPadding(dp(18), dp(11), dp(18), dp(11));
        dt.setTag("pill");
        donePill.addView(dt);
        donePill.setVisibility(View.GONE);
        dt.setOnClickListener(v -> {
            Learn.setDone(this, item.id, true);
            item.done = true;
            donePill.setVisibility(View.GONE);
            Toast.makeText(this, "Marked done. Nice work!", Toast.LENGTH_SHORT).show();
        });
        stack.addView(donePill, lpm(-2, -2, 0, 0, 0, 10));

        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(18), dp(16), dp(18), dp(12));
        settings.setVisibility(View.GONE);
        buildSettings();
        stack.addView(settings, lpm(-1, -2, 12, 0, 12, 8));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(20), dp(14), dp(20), dp(22));
        pageLbl = new TextView(this);
        pageLbl.setTextSize(13);
        pageLbl.setGravity(Gravity.CENTER);
        bottom.addView(pageLbl, new LinearLayout.LayoutParams(-1, -2));
        seek = new SeekBar(this);
        seek.setMax(Math.max(1, n - 1));
        seek.setProgress(cur);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) updateLabel(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { goTo(s.getProgress(), true); }
        });
        bottom.addView(seek, lpm(-1, -2, 0, 4, 0, 10));

        playBtn = new FrameLayout(this);
        playGlyph = new Glyph(1);
        playBtn.setOnClickListener(v -> togglePlay());
        if (ui) {
            controlsCol = new LinearLayout(this);
            controlsCol.setOrientation(LinearLayout.VERTICAL);

            // transport: time on the left, play in the middle, speed on the right
            LinearLayout tr = new LinearLayout(this);
            tr.setGravity(Gravity.CENTER_VERTICAL);
            timeLbl = new TextView(this);
            timeLbl.setText(fileMode ? "Loading…" : "Press play");
            timeLbl.setTextSize(12);
            timeLbl.setTag("sub");
            timeLbl.setSingleLine(true);
            tr.addView(timeLbl, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout mid = new LinearLayout(this);
            mid.setGravity(Gravity.CENTER);
            mid.addView(pill("−15s", () -> seekBy(-15)));
            playBtn.addView(playGlyph, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
            mid.addView(playBtn, lpm(dp(54), dp(54), 12, 0, 12, 0));
            mid.addView(pill("+15s", () -> seekBy(15)));
            tr.addView(mid, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout rt = new LinearLayout(this);
            rt.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            speedBtn = pill("1x", this::cycleSpeed);
            rt.addView(speedBtn);
            tr.addView(rt, new LinearLayout.LayoutParams(0, -2, 1f));
            controlsCol.addView(tr);

            // what is pinned right now
            LinearLayout st = new LinearLayout(this);
            st.setGravity(Gravity.CENTER);
            pinLbl = new TextView(this);
            pinLbl.setTextSize(12);
            pinLbl.setTag("sub");
            pinLbl.setGravity(Gravity.CENTER);
            pinLbl.setMaxLines(2);
            pinLbl.setOnClickListener(v -> showPins());
            st.addView(pinLbl, new LinearLayout.LayoutParams(0, -2, 1f));
            goPageBtn = pill("Go to page", () -> goTo(goPage, false));
            goPageBtn.setVisibility(View.GONE);
            st.addView(goPageBtn, lpm(-2, -2, 8, 0, 0, 0));
            controlsCol.addView(st, lpm(-1, -2, 0, 12, 0, 4));

            // bottom navigation
            navBar = new LinearLayout(this);
            navBar.setPadding(dp(4), dp(6), dp(4), dp(6));
            pinIcon = navItem(0, "Pins", this::showPins);
            navItem(1, "Play page", this::playPageAudio);
            navItem(2, "Find page", this::findMyPage);
            syncIcon = navItem(4, "Sync", this::showSync);
            controlsCol.addView(navBar, new LinearLayout.LayoutParams(-1, -2));
            bottom.addView(controlsCol, new LinearLayout.LayoutParams(-1, -2));
        } else {
            FrameLayout ctl = new FrameLayout(this);
            playBtn.addView(playGlyph, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            ctl.addView(playBtn, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
            bottom.addView(ctl, new LinearLayout.LayoutParams(-1, -2));
        }
        stack.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        root.addView(stack, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    LinearLayout navBar;
    NavIcon pinIcon, syncIcon;
    final List<NavIcon> navIcons = new ArrayList<>();

    NavIcon navItem(int type, String label, Runnable r) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setPadding(dp(2), dp(7), dp(2), dp(7));
        NavIcon ic = new NavIcon(type);
        navIcons.add(ic);
        ic.holder = it;
        it.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        it.addView(tv, lpm(-1, -2, 0, 3, 0, 0));
        ic.label = tv;
        it.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        it.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.55f);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        navBar.addView(it, new LinearLayout.LayoutParams(0, -2, 1f));
        return ic;
    }

    TextView pill(String text, Runnable r) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        t.setTag("pillbtn");
        t.setOnClickListener(v -> r.run());
        pillBtns.add(t);
        return t;
    }

    LinearLayout.LayoutParams lpm(int w, int hh, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hh);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    void buildSettings() {
        settings.removeAllViews();
        TextView l1 = new TextView(this);
        l1.setText("PAGE COLOUR");
        l1.setTextSize(11);
        l1.setLetterSpacing(0.1f);
        l1.setTag("sub");
        settings.addView(l1);
        LinearLayout sw = new LinearLayout(this);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView dot = new TextView(this);
            dot.setText("A");
            dot.setTextSize(18);
            dot.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            dot.setGravity(Gravity.CENTER);
            dot.setTextColor(INK[i]);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PAPER[i]);
            g.setStroke(dp(theme == i ? 3 : 1), theme == i ? INK[theme] : 0x55808080);
            dot.setBackground(g);
            cell.addView(dot, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView nm = new TextView(this);
            nm.setText(TN[i]);
            nm.setTextSize(11);
            nm.setTag("sub");
            nm.setTextColor(INK[theme]);
            cell.addView(nm, lpm(-2, -2, 0, 4, 0, 0));
            cell.setOnClickListener(v -> { theme = idx; Store.setNum(this, "rdTheme", idx); skin(); });
            sw.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        settings.addView(sw, lpm(-1, -2, 0, 10, 0, 0));

        TextView l2 = new TextView(this);
        l2.setText("BRIGHTNESS");
        l2.setTextSize(11);
        l2.setLetterSpacing(0.1f);
        l2.setTag("sub");
        settings.addView(l2, lpm(-2, -2, 0, 16, 0, 0));
        SeekBar br = new SeekBar(this);
        br.setMax(100);
        int cur0 = Store.num(this, "rdBright", -1);
        br.setProgress(cur0 < 0 ? 70 : cur0);
        br.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { setBright(p); Store.setNum(ReaderActivity.this, "rdBright", p); } }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        br.setTag("seek");
        settings.addView(br, lpm(-1, -2, 0, 2, 0, 0));

        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        TextView tt = new TextView(this);
        tt.setText("Trim empty margins");
        tt.setTextSize(15);
        tt.setTag("txt");
        tr.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch s = new Switch(this);
        s.setChecked(trim);
        s.setOnCheckedChangeListener((b, on) -> { trim = on; Store.setFlag(this, "rdTrim", on); computeCrop(); });
        tr.addView(s);
        settings.addView(tr, lpm(-1, -2, 0, 12, 0, 0));
        if (ui) {
            settings.addView(switchRow("Highlight the spoken sentence", hlOn, on -> { hlOn = on; Store.setFlag(this, "rdHl", on); if (pv != null) pv.invalidate(); }), lpm(-1, -2, 0, 12, 0, 0));
            settings.addView(switchRow("Turn pages with the audio", followOn, on -> { followOn = on; Store.setFlag(this, "rdFollow", on); }), lpm(-1, -2, 0, 12, 0, 0));
            TextView hs = new TextView(this);
            hs.setText("Highlight style: " + (hlStyle == 0 ? "Marker" : "Underline") + "  ›");
            hs.setTextSize(15);
            hs.setTag("txt");
            hs.setPadding(0, dp(14), 0, dp(4));
            hs.setOnClickListener(v -> { hlStyle = 1 - hlStyle; Store.setNum(this, "rdHlStyle", hlStyle); hs.setText("Highlight style: " + (hlStyle == 0 ? "Marker" : "Underline") + "  ›"); if (pv != null) pv.invalidate(); });
            settings.addView(hs);
            TextView sy = new TextView(this);
            sy.setText("Sync with the book…");
            sy.setTextSize(15);
            sy.setTag("txt");
            sy.setPadding(0, dp(14), 0, dp(4));
            sy.setOnClickListener(v -> { settings.setVisibility(View.GONE); showSync(); });
            settings.addView(sy);
        }
    }

    LinearLayout switchRow(String label, boolean on, java.util.function.Consumer<Boolean> cb) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTag("txt");
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> cb.accept(v));
        r.addView(sw);
        return r;
    }

    void tintAll(View v, int ink) {
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) tintAll(g.getChildAt(i), ink); }
        Object tag = v.getTag();
        if (v instanceof TextView && !"pill".equals(tag)) ((TextView) v).setTextColor("sub".equals(tag) || "hint".equals(tag) ? (ink & 0x00FFFFFF) | 0xAA000000 : ink);
        if (v instanceof SeekBar) {
            SeekBar s = (SeekBar) v;
            s.setProgressTintList(ColorStateList.valueOf(ink));
            s.setThumbTintList(ColorStateList.valueOf(ink));
            s.setProgressBackgroundTintList(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x44000000));
        }
        if (v instanceof Switch) {
            Switch s = (Switch) v;
            int[][] st = {{android.R.attr.state_checked}, {}};
            s.setThumbTintList(new ColorStateList(st, new int[]{ink, (ink & 0x00FFFFFF) | 0xAA000000}));
            s.setTrackTintList(new ColorStateList(st, new int[]{(ink & 0x00FFFFFF) | 0x55000000, (ink & 0x00FFFFFF) | 0x22000000}));
        }
    }

    GradientDrawable box(int col, float tl, float tr) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        float a = dp(tl), b = dp(tr);
        g.setCornerRadii(new float[]{a, a, b, b, 0, 0, 0, 0});
        return g;
    }

    GradientDrawable rr(int col, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        g.setCornerRadius(dp(r));
        return g;
    }

    void skinSettings() { tintAll(settings, INK[theme]); }

    void skin() {
        int ink = INK[theme], paper = PAPER[theme], desk = DESK[theme];
        chromeBg = (desk & 0x00FFFFFF) | 0xF2000000;
        top.setBackgroundColor(chromeBg);
        bottom.setBackground(box(chromeBg, 24, 24));
        for (TextView pb : pillBtns) pb.setBackground(rr((ink & 0x00FFFFFF) | 0x1F000000, 14));
        if (navBar != null) navBar.setBackground(rr((ink & 0x00FFFFFF) | 0x12000000, 20));
        for (NavIcon ni : navIcons) { ni.color = ink; ni.invalidate(); }
        settings.setBackground(rr(chromeBg, 20));
        title.setTextColor(ink);
        aa.setTextColor(ink);
        back.color = ink; back.invalidate();
        playBtn.setBackground(rr(ink, 30));
        playGlyph.color = paper; playGlyph.invalidate();
        if (audioBtn != null) { audioBtn.setTextColor(ink); audioBtn.setBackground(rr((ink & 0x00FFFFFF) | 0x1F000000, 16)); }
        View pill = donePill.getChildAt(0);
        pill.setBackground(rr(ink, 22));
        ((TextView) pill).setTextColor(paper);
        pageLbl.setTextColor((ink & 0x00FFFFFF) | 0xCC000000);
        buildSettings();
        tintAll(top, ink);
        tintAll(bottom, ink);
        tintAll(settings, ink);
        playGlyph.color = paper;
        pageLbl.setTextColor((ink & 0x00FFFFFF) | 0xCC000000);
        ((TextView) pill).setTextColor(paper);
        pv.setTheme(theme);
        root.setBackgroundColor(desk);
    }

    void showChrome(boolean on) {
        chrome = on;
        int vis = on ? View.VISIBLE : View.GONE;
        top.setVisibility(vis);
        if (!on) settings.setVisibility(View.GONE);
        ((View) bottom.getParent()).setVisibility(vis);
        donePill.setVisibility(on && cur == n - 1 && !item.done ? View.VISIBLE : View.GONE);
    }

    // ====================== glyphs ======================
    /** Small line icons for the bottom bar, drawn in the reader's ink colour. */
    class NavIcon extends View {
        final int type;
        int color = 0xFF000000;
        boolean filled, done, badge;
        int pct;
        TextView label;
        View holder;
        NavIcon(int t) { super(ReaderActivity.this); type = t; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.085f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            if (type == 0) {
                path.moveTo(w * .5f, h * .92f);
                path.cubicTo(w * .2f, h * .62f, w * .2f, h * .1f, w * .5f, h * .1f);
                path.cubicTo(w * .8f, h * .1f, w * .8f, h * .62f, w * .5f, h * .92f);
                path.close();
                if (filled) { p.setStyle(Paint.Style.FILL_AND_STROKE); }
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                if (filled) p.setColor(PAPER[theme]);
                c.drawCircle(w * .5f, h * .4f, w * .09f, p);
            } else if (type == 1) {
                path.moveTo(w * .3f, h * .18f); path.lineTo(w * .84f, h * .5f); path.lineTo(w * .3f, h * .82f); path.close();
                c.drawPath(path, p);
            } else if (type == 2) {
                c.drawCircle(w * .44f, h * .44f, w * .26f, p);
                c.drawLine(w * .64f, h * .64f, w * .88f, h * .88f, p);
            } else if (type == 4) {
                RectF ov = new RectF(w * .14f, h * .14f, w * .86f, h * .86f);
                if (pct > 0 || done) {
                    p.setAlpha(70);
                    c.drawArc(ov, 0, 360, false, p);
                    p.setAlpha(255);
                    c.drawArc(ov, -90, done ? 360 : 3.6f * pct, false, p);
                    if (done) {
                        path.moveTo(w * .34f, h * .52f); path.lineTo(w * .46f, h * .64f); path.lineTo(w * .68f, h * .38f);
                        c.drawPath(path, p);
                    }
                } else {
                    c.drawArc(ov, 200, 130, false, p);
                    c.drawArc(ov, 20, 130, false, p);
                    for (int k = 0; k < 2; k++) {
                        double e = Math.toRadians(k == 0 ? 330 : 150);
                        float ex = (float) (w * .5f + ov.width() / 2 * Math.cos(e)), ey = (float) (h * .5f + ov.height() / 2 * Math.sin(e));
                        float dx = k == 0 ? 1 : -1;
                        path.reset();
                        path.moveTo(ex - dx * w * .02f, ey - h * .17f); path.lineTo(ex, ey); path.lineTo(ex - dx * w * .17f, ey - h * .01f);
                        c.drawPath(path, p);
                    }
                }
                if (badge) { p.setStyle(Paint.Style.FILL); p.setColor(0xFFE8A33D); c.drawCircle(w * .9f, h * .1f, w * .12f, p); }
            } else {
                c.drawLine(w * .4f, h * .28f, w * .88f, h * .28f, p);
                c.drawLine(w * .4f, h * .5f, w * .88f, h * .5f, p);
                c.drawLine(w * .4f, h * .72f, w * .88f, h * .72f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(w * .15f, h * .28f, w * .05f, p);
                c.drawCircle(w * .15f, h * .5f, w * .05f, p);
                c.drawCircle(w * .15f, h * .72f, w * .05f, p);
            }
        }
    }

    class Glyph extends View {
        int type, color = 0xFF000000;
        Glyph(int t) { super(ReaderActivity.this); type = t; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            if (type == 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w * 0.085f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                Path path = new Path();
                path.moveTo(w * 0.62f, hh * 0.24f); path.lineTo(w * 0.36f, hh * 0.5f); path.lineTo(w * 0.62f, hh * 0.76f);
                c.drawPath(path, p);
            } else if (type == 1) {
                Path path = new Path();
                path.moveTo(w * 0.28f, hh * 0.16f); path.lineTo(w * 0.84f, hh * 0.5f); path.lineTo(w * 0.28f, hh * 0.84f); path.close();
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeWidth(w * 0.08f);
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                c.drawPath(path, p);
            } else {
                c.drawRoundRect(w * 0.2f, hh * 0.16f, w * 0.42f, hh * 0.84f, w * 0.06f, w * 0.06f, p);
                c.drawRoundRect(w * 0.58f, hh * 0.16f, w * 0.8f, hh * 0.84f, w * 0.06f, w * 0.06f, p);
            }
        }
    }

    // ====================== the page ======================
    class PageView extends View {
        int th;
        float zoom = 1, px, py, dragX;
        boolean scaling, dragging, flung;
        ValueAnimator anim;
        final Paint paperP = new Paint(Paint.ANTI_ALIAS_FLAG), bmpP = new Paint(Paint.FILTER_BITMAP_FLAG), shP = new Paint(Paint.ANTI_ALIAS_FLAG),
                grainP = new Paint(), edgeP = new Paint(), dimP = new Paint(), gutP = new Paint();
        final RectF sheet = new RectF();
        final ScaleGestureDetector sgd;
        final GestureDetector gd;

        PageView() {
            super(ReaderActivity.this);
            Bitmap g = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Random r = new Random(7);
            for (int i = 0; i < 128 * 128 / 5; i++) g.setPixel(r.nextInt(128), r.nextInt(128), Color.argb(8 + r.nextInt(22), 70, 50, 20));
            grainP.setShader(new BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            sgd = new ScaleGestureDetector(ReaderActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; cancelAnim(); dragX = 0; return true; }
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float nz = Math.max(1f, Math.min(5f, zoom * d.getScaleFactor()));
                    px *= nz / zoom; py *= nz / zoom;
                    zoom = nz;
                    clampPan();
                    invalidate();
                    return true;
                }
                @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; if (zoom < 1.05f) resetZoom(); }
            });
            gd = new GestureDetector(ReaderActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { flung = false; dragging = false; cancelAnim(); return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    float x = e.getX(), w = getWidth();
                    if (settings.getVisibility() == View.VISIBLE) { settings.setVisibility(View.GONE); return true; }
                    // Taps never turn the page (swipe for that). A tap just shows or hides the controls.
                    showChrome(!chrome);
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (zoom > 1.05f) resetZoom();
                    else {
                        zoom = 2.4f;
                        px = (e.getX() - getWidth() / 2f) * (1 - zoom);
                        py = (e.getY() - getHeight() / 2f) * (1 - zoom);
                        clampPan();
                    }
                    invalidate();
                    return true;
                }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (scaling) return false;
                    if (zoom > 1.01f) { px -= dx; py -= dy; clampPan(); invalidate(); return true; }
                    if (Math.abs(dx) > Math.abs(dy) * 0.8f || dragging) {
                        dragging = true;
                        dragX -= dx;
                        float w = getWidth();
                        if (cur == n - 1 && dragX < 0) dragX = Math.max(dragX, -w * 0.12f);
                        if (cur == 0 && dragX > 0) dragX = Math.min(dragX, w * 0.12f);
                        if (chrome) showChrome(false);
                        invalidate();
                        return true;
                    }
                    return false;
                }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (zoom > 1.01f || !dragging) return false;
                    flung = true;
                    if (vx < -900) settle(cur < n - 1 ? 1 : 0);
                    else if (vx > 900) settle(cur > 0 ? -1 : 0);
                    else settle(dragX < -getWidth() * 0.22f && cur < n - 1 ? 1 : dragX > getWidth() * 0.22f && cur > 0 ? -1 : 0);
                    return true;
                }
            });
        }

        void setTheme(int t) {
            th = t;
            float[] pr = {Color.red(PAPER[t]), Color.green(PAPER[t]), Color.blue(PAPER[t])};
            float[] ik = {Color.red(INK[t]), Color.green(INK[t]), Color.blue(INK[t])};
            ColorMatrix m = new ColorMatrix(new float[]{
                    (pr[0] - ik[0]) / 255f, 0, 0, 0, ik[0],
                    0, (pr[1] - ik[1]) / 255f, 0, 0, ik[1],
                    0, 0, (pr[2] - ik[2]) / 255f, 0, ik[2],
                    0, 0, 0, 1, 0});
            bmpP.setColorFilter(new ColorMatrixColorFilter(m));
            invalidate();
        }

        void cancelAnim() { if (anim != null) { anim.cancel(); anim = null; } }

        void resetZoom() { zoom = 1; px = py = 0; }

        void clampPan() {
            float mx = (zoom - 1) * getWidth() / 2f, my = (zoom - 1) * getHeight() / 2f;
            px = Math.max(-mx, Math.min(mx, px));
            py = Math.max(-my, Math.min(my, py));
        }

        /** dir: 1 = next page, -1 = previous, 0 = snap back. */
        void settle(int dir) {
            float w = getWidth(), to = dir == 1 ? -w : dir == -1 ? w : 0;
            cancelAnim();
            anim = ValueAnimator.ofFloat(dragX, to);
            anim.setDuration(dir == 0 ? 160 : 240);
            anim.setInterpolator(new DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> { dragX = (float) a.getAnimatedValue(); invalidate(); });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled) return;
                    dragX = 0;
                    dragging = false;
                    if (dir != 0) goTo(cur + dir, true);
                    invalidate();
                }
            });
            anim.start();
        }

        void turn(boolean fwd) {
            if (fwd && cur >= n - 1 || !fwd && cur <= 0) return;
            if (chrome) showChrome(false);
            settle(fwd ? 1 : -1);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sgd.onTouchEvent(e);
            if (!sgd.isInProgress()) gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && dragging && !flung && zoom <= 1.01f)
                settle(dragX < -getWidth() * 0.22f && cur < n - 1 ? 1 : dragX > getWidth() * 0.22f && cur > 0 ? -1 : 0);
            return true;
        }

        RectF cropOf(Bitmap b) {
            if (b == null) return new RectF(0, 0, 100, 140);
            int w = b.getWidth(), hh = b.getHeight();
            return trim ? new RectF(cl * w, ct * hh, w - cr * w, hh - cb * hh) : new RectF(0, 0, w, hh);
        }

        @Override protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            c.drawColor(DESK[th]);
            if (zoom > 1.001f) {
                c.save();
                c.translate(W / 2f + px, H / 2f + py);
                c.scale(zoom, zoom);
                c.translate(-W / 2f, -H / 2f);
                drawPage(c, cur, 0);
                c.restore();
                return;
            }
            if (dragX < 0) {
                drawPage(c, cur + 1, 0);
                dimP.setColor(Color.argb((int) (70 * (1 + dragX / W)), 0, 0, 0));
                c.drawRect(0, 0, W, H, dimP);
                drawPage(c, cur, dragX);
                edge(c, W + dragX, W);
            } else if (dragX > 0) {
                drawPage(c, cur, 0);
                dimP.setColor(Color.argb((int) (70 * (1 - dragX / W)), 0, 0, 0));
                c.drawRect(0, 0, W, H, dimP);
                drawPage(c, cur - 1, dragX - W);
                edge(c, dragX, W);
            } else drawPage(c, cur, 0);
        }

        final Paint hlP = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** Marks the words of the sentence being read, one soft bar per line of text. */
        void drawHighlight(Canvas c, int idx, Bitmap b, RectF src, float l, float t, float s) {
            if (idx >= bi.pages) return;
            int from = Math.max(hlFrom, bi.pageStart[idx]), to = Math.min(hlTo, bi.pageStart[idx + 1] - 1);
            if (from > to) return;
            hlP.setColor(hlStyle == 1 ? (th == 3 ? 0xFFFFD54F : 0xFFD9822B) : (th == 3 ? 0x55FFD54F : 0x70F2B93B));
            float bw = b.getWidth(), bh = b.getHeight(), pad = dp(1.5f), rad = dp(3);
            float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
            boolean open = false;
            for (int j = from; j <= to; j++) {
                float a = bi.box[j * 4] * bw, bb = bi.box[j * 4 + 1] * bh, cc = bi.box[j * 4 + 2] * bw, dd = bi.box[j * 4 + 3] * bh;
                if (open && Math.abs((bb + dd) / 2 - (y0 + y1) / 2) < (y1 - y0) * 0.6f) {
                    x0 = Math.min(x0, a); x1 = Math.max(x1, cc); y0 = Math.min(y0, bb); y1 = Math.max(y1, dd);
                } else {
                    if (open) mark(c, src, l, t, s, x0, y0, x1, y1, pad, rad);
                    x0 = a; y0 = bb; x1 = cc; y1 = dd; open = true;
                }
            }
            if (open) mark(c, src, l, t, s, x0, y0, x1, y1, pad, rad);
        }

        void mark(Canvas c, RectF src, float l, float t, float s, float x0, float y0, float x1, float y1, float pad, float rad) {
            float L = l + (x0 - src.left) * s - pad, R = l + (x1 - src.left) * s + pad, T = t + (y0 - src.top) * s - pad, B = t + (y1 - src.top) * s + pad;
            if (hlStyle == 1) c.drawRoundRect(L, B - dp(2.2f), R, B, dp(1.1f), dp(1.1f), hlP);
            else c.drawRoundRect(L, T, R, B, rad, rad, hlP);
        }

        void edge(Canvas c, float x, int W) {
            if (x <= 0 || x >= W) return;
            edgeP.setShader(new LinearGradient(x, 0, x + dp(22), 0, th == 3 ? 0x80000000 : 0x55000000, 0, Shader.TileMode.CLAMP));
            c.drawRect(x, 0, x + dp(22), getHeight(), edgeP);
        }

        void drawPage(Canvas c, int idx, float x) {
            if (idx < 0 || idx >= n) return;
            int W = getWidth(), H = getHeight();
            c.save();
            c.translate(x, 0);
            sheet.set(dp(8), dp(30), W - dp(8), H - dp(16) - bottomInset);
            float rad = dp(5);
            if (th != 3) for (int k = 5; k >= 1; k--) {
                shP.setColor(Color.argb(10, 40, 25, 5));
                RectF s = new RectF(sheet.left - k * dp(1.5f), sheet.top - k * dp(1f) + dp(2), sheet.right + k * dp(1.5f), sheet.bottom + k * dp(1.6f) + dp(2));
                c.drawRoundRect(s, rad + k, rad + k, shP);
            }
            paperP.setColor(PAPER[th]);
            c.drawRoundRect(sheet, rad, rad, paperP);
            if (th != 3) {
                grainP.setAlpha(150);
                c.drawRoundRect(sheet, rad, rad, grainP);
            }
            Bitmap b = cache.get(idx);
            if (b != null) {
                RectF src = cropOf(b);
                float pad = dp(14);
                float s = Math.min((sheet.width() - 2 * pad) / src.width(), (sheet.height() - 2 * pad) / src.height());
                float cw = src.width() * s, ch = src.height() * s;
                float l = sheet.centerX() - cw / 2f, t = sheet.centerY() - ch / 2f;
                c.drawBitmap(b, new Rect((int) src.left, (int) src.top, (int) src.right, (int) src.bottom), new RectF(l, t, l + cw, t + ch), bmpP);
                if (hlOn && bi != null && hlFrom >= 0) drawHighlight(c, idx, b, src, l, t, s);
            }
            // soft shading toward the spine, like the fold of a real book
            boolean spineLeft = idx % 2 == 0;
            float gw = dp(26);
            int gc = th == 3 ? 0x30000000 : 0x26301800;
            if (spineLeft) gutP.setShader(new LinearGradient(sheet.left, 0, sheet.left + gw, 0, gc, 0, Shader.TileMode.CLAMP));
            else gutP.setShader(new LinearGradient(sheet.right, 0, sheet.right - gw, 0, gc, 0, Shader.TileMode.CLAMP));
            c.save();
            Path clip = new Path();
            clip.addRoundRect(sheet, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(sheet, gutP);
            c.restore();
            c.restore();
        }
    }
}
