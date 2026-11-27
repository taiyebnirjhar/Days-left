package com.taiyeb.daysleft;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
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
    ScrollPages sp;            // rotated phone: pages one after the other, scrolling flows from a page into the next
    boolean scrollMode;
    boolean readingText, textTried;   // the book's text is being read quietly in the background (once per opening)
    boolean locked;                 // touch lock (like VLC): the screen ignores touches until the padlock is tapped
    Glyph lockBtn, unlockBtn, rotateBtn;       // padlock in the top bar; the one that appears after a tap while locked
    final Runnable hideUnlock = () -> { if (unlockBtn != null) unlockBtn.setVisibility(View.GONE); };
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
    int seenLiveVer = -1;
    final MarkerTrack mt = new MarkerTrack();   // which sentence the marker shows (tested in tests/host/MarkerCheck.java)

    int goPage;
    boolean jumpToAudio;
    double aT, aDur;
    long lastFollowStart;
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
        resumeSync();
    }

    /** Back on screen: pick up what the background service did meanwhile, and wake it if the playhead is outside the mapped area. */
    void resumeSync() {
        try {
            if (item == null || !fileMode || bi == null) return;
            if (BookText.alignFile(this, item.id).lastModified() > alReloaded) loadSyncData();
            double t = Player.pos();
            if (!SyncService.running && SyncService.wanted(this, item.id) && Whisper.hasModel(this)
                    && (!SyncService.winReady(this, item.id) || al.wordAtTime(t) < 0)) {
                Dbg.i("Sync", "back in the book at " + (int) t + " s: not mapped here, waking the sync");
                SyncService.start(this, item.id);
            }
        } catch (Throwable e) { Dbg.e("Reader", "resumeSync failed", e); }
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

    /**
     * The touch lock blocks what changes things by accident: taps (controls, buttons), double-tap and pinch zoom. Scrolling and swiping still work, so the
     * book can be read on (a locked page that cannot be moved is useless). A tap shows the unlock padlock for 3 s; the padlock unlocks.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (!locked) return super.dispatchTouchEvent(e);
        if (unlockBtn.getVisibility() == View.VISIBLE) {
            int[] xy = new int[2]; unlockBtn.getLocationOnScreen(xy);
            int pad = dp(12);
            if (e.getRawX() >= xy[0] - pad && e.getRawX() <= xy[0] + unlockBtn.getWidth() + pad && e.getRawY() >= xy[1] - pad && e.getRawY() <= xy[1] + unlockBtn.getHeight() + pad) {
                if (e.getAction() == MotionEvent.ACTION_UP) setLocked(false);
                return true;
            }
        }
        return super.dispatchTouchEvent(e);   // the page / the scroll gets the touch; its taps, double-taps and pinches are ignored while locked (see below)
    }

    /** A tap while locked: show the way out. */
    void revealUnlock() { unlockBtn.setVisibility(View.VISIBLE); h.removeCallbacks(hideUnlock); h.postDelayed(hideUnlock, 3000); }

    // ====================== the book's text, read quietly ======================
    /**
     * A book with audio needs the word positions (for the marker); an index from an older version is discarded when it is opened, so read the text again
     * in the background (about 15 s, once). Also gives the exact text area for "Trim empty margins". Not while the sync service is reading the same book.
     */
    void readBookQuietly() {
        if (readingText || textTried || item == null || !fileMode || SyncService.running && SyncService.itemId == item.id) return;
        readingText = true; textTried = true;
        final int id = item.id;
        new Thread(() -> {
            try {
                BookIndex fresh = BookText.extract(this, item, pc -> {}, new boolean[1]);
                if (BookText.looksScanned(fresh)) return;
                ui(() -> { if (bi == null) { bi = fresh; al.withBook(fresh); computePageBounds(); computeCrop(); if (pv != null) pv.invalidate(); } });
            } catch (Throwable e) { Dbg.e("Reader", "reading the book text failed", e); }
            finally { readingText = false; }
        }, "book-text").start();
    }

    // ---- the rotate button: tap = turn the screen (upright <-> on its side) and keep it there; long-press = follow the phone again ----
    void applyOrient(int mode) {
        setRequestedOrientation(mode == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : mode == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        refreshRotateIcon(mode);
    }

    void setOrient(int mode) { Store.setNum(this, "rdOrient", mode); applyOrient(mode); }

    void toggleOrient() {
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        setOrient(land ? 1 : 2);
        Toast.makeText(this, land ? "Upright. Long-press the icon to follow the phone." : "On its side. Long-press the icon to follow the phone.", Toast.LENGTH_SHORT).show();
    }

    void refreshRotateIcon(int mode) {
        if (rotateBtn == null) return;
        rotateBtn.land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        rotateBtn.rotMode = mode;
        rotateBtn.invalidate();
    }

    /** Phone on its side: pages stacked and scrolled as one; upright: the page view with swipes. */
    int upRenderW;   // the picture width used with the phone upright

    /** How pages change in each position (Settings): upright default = swipe left-right, rotated default = scroll up-down. */
    boolean wantScroll(boolean land) { return land ? !Store.flag(this, "rdLandSwipe", false) : Store.flag(this, "rdPortScroll", false); }

    int lastLand = -1;

    void updateMode() {
        if (pv == null || root == null || item == null) return;
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (upRenderW == 0) upRenderW = renderW;
        if ((land ? 1 : 0) != lastLand) {   // the phone was turned (or this is the first look)
            lastLand = land ? 1 : 0;
            refreshRotateIcon(Store.num(this, "rdOrient", 0));
            // pictures are shown across the whole (wide) screen: render them that wide (up to the size the renderer allows) and take new ones
            int wide = Math.max(root.getWidth(), root.getHeight());
            int nw = land ? Math.min(2560, Math.max(upRenderW, (int) (wide * 1.1f))) : upRenderW;
            if (nw != renderW) { renderW = nw; cache.evictAll(); inflight.clear(); for (int k = -1; k <= 2; k++) ensure(cur + k); }
        }
        boolean want = wantScroll(land);
        if (want == scrollMode) return;
        scrollMode = want;
        if (want) {
            if (sp == null) { sp = new ScrollPages(this); root.addView(sp, 1, new FrameLayout.LayoutParams(-1, -1)); }
            sp.setBackgroundColor(DESK[theme]);
            sp.setVisibility(View.VISIBLE);
            sp.showPage(cur);
        } else if (sp != null) {
            int p = sp.pageAtTop();
            sp.setVisibility(View.GONE);
            goTo(p, false);
        }
    }

    /** A switch with the choice written on it: "Upright: left–right" / "Upright: up–down". */
    LinearLayout valueRow(String prefix, String offText, String onText, boolean on, java.util.function.Consumer<Boolean> cb) {
        LinearLayout r = switchRow(prefix + (on ? onText : offText), on, v -> {});
        TextView t = (TextView) r.getChildAt(0);
        Switch sw = (Switch) r.getChildAt(1);
        sw.setOnCheckedChangeListener((b, v) -> { t.setText(prefix + (v ? onText : offText)); cb.accept(v); });
        return r;
    }

    void setLocked(boolean on) {
        locked = on;
        h.removeCallbacks(hideUnlock);
        if (on) {
            if (settings != null) settings.setVisibility(View.GONE);
            showChrome(false);
            unlockBtn.setVisibility(View.VISIBLE);
            h.postDelayed(hideUnlock, 2500);
            Toast.makeText(this, "Touch lock on: taps and zoom are off, scrolling still works. Tap the screen, then the padlock, to unlock.", Toast.LENGTH_LONG).show();
        } else {
            unlockBtn.setVisibility(View.GONE);
            showChrome(true);
        }
    }

    @Override public void onBackPressed() {
        if (locked) { revealUnlock(); return; }
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
        final int w = renderW;   // the width this picture is for (the phone may be turned meanwhile)
        exec.submit(() -> {
            try {
                Bitmap b = render(i, w);
                h.post(() -> {
                    inflight.remove(i);
                    if (w == renderW) cache.put(i, b);   // a picture for the other shape is dropped; the right one has been asked for
                    if (pv != null) pv.invalidate();
                    if (sp != null) sp.pictureReady();
                });
            } catch (Throwable t) { inflight.remove(i); }
        });
    }

    // ---- Trim empty margins ----
    // The book's own margins, as fractions of the page, are the same on (almost) every page, so every page is cut the same way and the text keeps one size.
    // From the printed words' positions when the book is read (exact, instant), else from looking at a few rendered pages. A page whose text reaches
    // beyond the book-wide cut is never clipped: its own text area is added back (only for that page).
    float[][] pageB;   // per page: printed text area {left, top, right, bottom} as fractions, or null (a page with almost no text)

    void computePageBounds() {
        if (bi == null) { pageB = null; return; }
        float[][] pb = new float[bi.pages][];
        for (int p = 0; p < bi.pages; p++) {
            int a = bi.pageStart[p], b = bi.pageStart[p + 1];
            if (b - a < 12) continue;
            float l = 1, t = 1, r = 0, bt = 0;
            for (int j = a; j < b; j++) { l = Math.min(l, bi.box[4 * j]); t = Math.min(t, bi.box[4 * j + 1]); r = Math.max(r, bi.box[4 * j + 2]); bt = Math.max(bt, bi.box[4 * j + 3]); }
            if (r > l && bt > t) pb[p] = new float[]{l, t, r, bt};
        }
        pageB = pb;
    }

    static float pct(float[] v, int n, float q) { float[] c = java.util.Arrays.copyOf(v, n); java.util.Arrays.sort(c); return c[Math.max(0, Math.min(n - 1, Math.round(q * (n - 1))))]; }

    void applyCrop(float l, float t, float r, float b, boolean save) {
        cl = l; ct = t; cr = r; cb = b;
        if (save && item != null) Store.sp(this).edit().putString("trim_" + item.id, l + "," + t + "," + r + "," + b).apply();
        if (pv != null) pv.invalidate();
        if (sp != null) sp.relayout();
    }

    void computeCrop() {
        if (!trim) { cl = ct = cr = cb = 0; if (pv != null) pv.invalidate(); if (sp != null) sp.relayout(); return; }
        // 1. what was found last time: shown at once, no jump when the book opens
        boolean saved = false;
        try {
            String sv = item == null ? null : Store.sp(this).getString("trim_" + item.id, null);
            if (sv != null) { String[] q = sv.split(","); applyCrop(Float.parseFloat(q[0]), Float.parseFloat(q[1]), Float.parseFloat(q[2]), Float.parseFloat(q[3]), false); saved = true; }
        } catch (Exception ignored) {}
        // 2. from the printed words
        if (pageB != null) {
            float[] L = new float[pageB.length], T = new float[pageB.length], R = new float[pageB.length], B = new float[pageB.length];
            int c = 0;
            for (float[] q : pageB) if (q != null) { L[c] = q[0]; T[c] = q[1]; R[c] = 1 - q[2]; B[c] = 1 - q[3]; c++; }
            if (c >= 3) {
                // the margin that (almost) every page respects: ignore the few pages that reach further out (a wide table, a full-width figure)
                final float pad = 0.012f;
                float fl = Math.max(0, pct(L, c, 0.1f) - pad), ft = Math.max(0, pct(T, c, 0.1f) - pad), fr = Math.max(0, pct(R, c, 0.1f) - pad), fb = Math.max(0, pct(B, c, 0.1f) - pad);
                if (1 - fl - fr >= 0.5f && 1 - ft - fb >= 0.5f) { applyCrop(fl, ft, fr, fb, true); return; }
            }
        }
        // 3. no word positions yet and nothing saved: look at a handful of rendered pages for the margins that are always empty
        if (saved) return;
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
                final float fl = Math.max(0, l - pad), fr = Math.max(0, r - pad), ft = Math.max(0, t - pad), fb = Math.max(0, b - pad);
                if (1 - fl - fr < 0.5f || 1 - ft - fb < 0.5f) return;
                h.post(() -> { if (pageB == null && trim) applyCrop(fl, ft, fr, fb, true); });
            } catch (Throwable ignored) {}
        });
    }

    // ====================== navigation ======================
    void goTo(int i, boolean haptic) {
        cur = Math.max(0, Math.min(n - 1, i));
        for (int k = -1; k <= 2; k++) ensure(cur + k);
        ensure(cur);
        if (pv != null) { pv.resetZoom(); pv.invalidate(); }
        if (scrollMode && sp != null && sp.pageAtTop() != cur) sp.showPage(cur);
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
            updateBanner();
            h.postDelayed(this, 1200);
        }
    };

    /** One place for the sync wording and number: the pill and the panel must always say the same. */
    String syncStatus() { return SyncService.statusText(); }

    /** The number that goes with syncStatus(), or -1 when there is none yet. */
    int statusPct() { return SyncService.statusPct(); }

    String jobText;   // what a hidden Find/Play page search is doing right now (shown in the pill)
    String pendingText;
    Runnable pendingAct;
    long pendingUntil;

    /** The answer to something the user asked and then hid the dialog for: shown as a tappable pill, with a tap on the phone. */
    void postResult(String text, Runnable act) {
        pendingText = text; pendingAct = act;
        pendingUntil = System.currentTimeMillis() + (act != null ? 30000 : 6000);
        if (playBtn != null) playBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        updateBanner();
    }

    /** Show the result in the dialog if it is still open, else as a pill. */
    void resultOrPost(String text, Runnable act, boolean panelHidden) {
        if (!panelHidden && fdlg != null) { setMsg(text, -1); setBtns("Close", (Runnable) this::closePanel); }
        else postResult(text, act);
    }

    /** The status line above the player. Calm wording, honest numbers. */
    void updateBanner() {
        if (bannerWrap == null || item == null || !fileMode) return;
        long now = System.currentTimeMillis();
        boolean mine = SyncService.running && SyncService.itemId == item.id;
        String t = null;
        if (pendingText != null && now >= pendingUntil) { pendingText = null; pendingAct = null; }
        if (fdlg != null) t = null;                      // a dialog is open: it says it already
        else if (pendingText != null) t = pendingText;
        else if (jobText != null) t = jobText;
        else if (mine && !SyncService.ready) {
            t = syncStatus();
            if (SyncService.powerSaving && SyncService.dlPct < 0 && SyncService.textPct < 0) t += "\nBattery saver is on, so this is slower";
        } else if (now < readyFlashUntil) t = "Ready ✓  highlight" + (followOn ? " and page turns" : "") + " are on";
        else if (!SyncService.running && !SyncService.error.isEmpty() && SyncService.wanted(this, item.id) && !SyncService.winReady(this, item.id))
            t = "Sync paused: " + SyncService.error + "\nTap to try again";
        if (t == null) { if (bannerWrap.getVisibility() != View.GONE) bannerWrap.setVisibility(View.GONE); return; }
        // solid, high contrast on every theme: the ink colour as the background, the paper colour as the text, with a shadow,
        // so it stays readable over any page text (the translucent version let the text underneath show through)
        int ink = INK[theme], paper = PAPER[theme];
        syncBanner.setTextColor((paper & 0x00FFFFFF) | 0xFF000000);
        syncBanner.setBackground(rr((ink & 0x00FFFFFF) | 0xFF000000, 22));
        syncBanner.setElevation(dp(6));
        syncBanner.setGravity(Gravity.CENTER);
        if (!t.contentEquals(syncBanner.getText())) syncBanner.setText(t);
        if (bannerWrap.getVisibility() != View.VISIBLE) bannerWrap.setVisibility(View.VISIBLE);
    }


    void refreshPlay() {
        if (playGlyph == null || am == null) return;
        int t = am.isMusicActive() ? 2 : 1;
        if (playGlyph.type != t) { playGlyph.type = t; playGlyph.invalidate(); }
    }

    boolean askedSyncThisSession, waitingReady, autoHidePanel;
    LinearLayout bannerWrap;
    TextView syncBanner;
    long readyFlashUntil;

    /** Ask once per visit, and never again if the user said so, for a book that has no sync yet. */
    boolean shouldAskSync() {
        return item != null && fileMode && Store.flag(this, "syncAsk", true) && !askedSyncThisSession && !SyncService.running
                && !SyncService.wanted(this, item.id) && !SyncService.winReady(this, item.id)
                && !Store.sp(this).getBoolean("syncNever_" + item.id, false);
    }

    /** The first time the user presses Play on an unsynced book: explain what syncing gives, and be honest about the wait. */
    void askSync() {
        askedSyncThisSession = true;
        openPanel("Sync this book with the audio?");
        setMsg("Want to sync this audiobook with the book first?\n\n"
                + "What you get:\n"
                + "•  the sentence being read is highlighted\n"
                + "•  pages turn by themselves as you listen" + (followOn ? "" : " (turn on “Turn pages with the audio” in the settings)") + "\n"
                + "•  Play page and Find page become instant\n\n"
                + "It works quietly in the background and takes a few minutes the first time, so please be patient. "
                + "You can keep listening or reading meanwhile, and you'll be told when it's ready.", -1);
        setBtns("Sync and play", (Runnable) () -> {
                    autoHidePanel = true;
                    openPanel("Sync with the book");
                    startSync(Whisper.which(this));
                    Player.toggle(this);
                },
                "Just play", (Runnable) () -> { closePanel(); Player.toggle(this); },
                "Don't ask", (Runnable) () -> { Store.sp(this).edit().putBoolean("syncNever_" + item.id, true).apply(); closePanel(); Player.toggle(this); });
    }

    void togglePlay() {
        playBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (fileMode) {
            if (!mpReady) { Toast.makeText(this, "Loading the audio…", Toast.LENGTH_SHORT).show(); return; }
            if (!Player.isPlaying() && Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 92);
            if (!Player.isPlaying() && shouldAskSync()) { askSync(); return; }
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
        // the sync hands over what it knows in memory: a new match shows up on this very tick instead of after a file reload
        if (bi != null && SyncService.liveItem == item.id && SyncService.liveVer != seenLiveVer) {
            Alignment l = SyncService.live;
            seenLiveVer = SyncService.liveVer;
            if (l != null) { al = l.withBook(bi); alReloaded = System.currentTimeMillis(); }
        }
        if (timeLbl != null) timeLbl.setText(fmtT(t) + " / " + fmtT(d));
        int g = st == 1 || st == 3 ? 2 : 1;
        if (playGlyph != null && playGlyph.type != g) { playGlyph.type = g; playGlyph.invalidate(); }
        long now = System.currentTimeMillis();
        if (now - lastSave > 5000 && t > 1) { lastSave = now; Learn.setAudio(this, item.id, (int) t, null); }
        if (al.size() > 0 && bi != null) {
            int fw = al.flipWordAt(t);   // the rules live in Alignment/MarkerTrack, so the tests use the same ones
            // MarkerTrack: never loses the marker while playing (dead reckoning through gaps), never flickers or steps back, shows nothing after a seek into unknown audio
            if (mt.update(al, bi, t, st == 1, now)) { hlFrom = mt.from; hlTo = mt.to; if (hlOn && pv != null) pv.invalidate(); if (sp != null) sp.pictureReady(); }
            int hw = mt.word;
            if (fw < 0 && mt.estimated) fw = mt.word;   // the page follows the marker too
            if (scrollMode && sp != null) {
                // on its side the marker is followed by scrolling (the page changes inside the scroll), not by page turns
                if (followOn && st == 1 && now - lastManual > 5000 && hlFrom >= 0 && settings.getVisibility() != View.VISIBLE) sp.follow(hlFrom);
            } else if (fw >= 0 && followOn && st == 1 && now - lastManual > 5000 && pv != null && !pv.dragging && pv.zoom <= 1.01f && settings.getVisibility() != View.VISIBLE) {
                int pg = bi.pageOf(fw);
                if (pg != cur) goTo(pg, false);
            }
        }
        if (SyncService.running && SyncService.itemId == item.id && SyncService.liveItem != item.id && now - alReloaded > 10000) loadSyncData();   // live hand-over replaces this poll
        if (waitingReady && SyncService.running && SyncService.ready && SyncService.itemId == item.id) {
            waitingReady = false;
            loadSyncData();
            readyFlashUntil = now + 4500;
            if (playBtn != null) playBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            updateBanner();
            Toast.makeText(this, "Ready! The sentence being read is highlighted" + (followOn ? " and pages turn by themselves." : "."), Toast.LENGTH_LONG).show();
        }
        // listening on: when the mapped area is running out ahead of the playhead, wake the service so it maps the next part
        if (st == 1 && !SyncService.running && now - lastFollowStart > 60000 && bi != null && al.size() > 0
                && SyncService.wanted(this, item.id) && Whisper.hasModel(this) && al.wordAtTime(t + 120) < 0) {
            lastFollowStart = now;
            Dbg.i("Sync", "playing at " + (int) t + " s, nothing mapped 2 min ahead: mapping the next part");
            SyncService.start(this, item.id);
        }
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
        if (SyncService.wanted(this, item.id) || SyncService.winReady(this, item.id)) AudioClip.warm(this, item.audio);
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
            h.postDelayed(this, 100);   // the marker follows the audio within a tenth of a second
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
        int sec = SyncService.etaSeconds();
        return sec <= 0 ? "" : " · about " + (sec >= 90 ? (sec + 30) / 60 + " min" : sec + " s") + " left";
    }

    void updateSyncLabel() {
        if (syncIcon == null) return;
        boolean run = syncing(), full = SyncService.fullFor(this, item.id) || (run ? SyncService.ready : SyncService.winReady(this, item.id));
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
                Alignment a = Alignment.load(BookText.alignFile(this, id)).withBook(b);
                Dbg.p("Reader", "sync data loaded", tl, "book words " + (b == null ? "none" : b.count()) + ", aligned " + a.size() + (SyncService.running ? ", sync running" : ""));
                ui(() -> {
                    bi = b; al = a; seenLiveVer = -1; jumpToAudioPage(); updatePin();
                    if (b != null && pageB == null) { computePageBounds(); computeCrop(); }
                    if (pv != null) pv.invalidate();
                    // an index from an older version is discarded when opened: read the book again quietly (a book with audio needs it for the marker)
                    if (b == null && item.audio != null && !item.audio.isEmpty()) h.postDelayed(this::readBookQuietly, 800);
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
        } else if (syncing() && SyncService.ready) {
            setMsg("Synced around where you are listening.\n\nPlay page, Find page and the sentence highlight work here. It follows you as you play, and maps the new place within a minute or so if you jump somewhere else.", -1);
            setBtns("Close", (Runnable) this::closePanel, "Stop", (Runnable) () -> { SyncService.stop(this); showSyncState(); });
        } else if (syncing()) {
            setMsg(syncStatus() + (SyncService.powerSaving ? "\n\nBattery saver is on, so this is slower than usual." : "")
                    + "\n\nPlease be patient. You can keep listening or reading meanwhile, leave this screen, or turn the screen off: it keeps going in the background and shows a notification. Stopping is safe: it continues from where it stopped.", statusPct());
            setBtns("Hide", (Runnable) this::closePanel, "Stop", (Runnable) () -> { SyncService.stop(this); showSyncState(); });
        } else if (SyncService.fullFor(this, item.id)) {
            setMsg("This audiobook is synced.\n\nPlay page and Find page are instant now, and the sentence being read is highlighted in the book.", -1);
            setBtns("Close", (Runnable) this::closePanel, "Sync again", (Runnable) () -> { BookText.clearAudioData(this, item.id); al = new Alignment(); mt.reset(); hlFrom = hlTo = -1; showSyncState(); });
        } else if (SyncService.winReady(this, item.id)) {
            setMsg("Synced around where you were listening.\n\nPlay page, Find page and the sentence highlight work there. If you go somewhere else, Find page and Play page map that place on demand (about a minute), or tap Sync here to map it now.", -1);
            setBtns("Close", (Runnable) this::closePanel, "Sync here", (Runnable) () -> startSync(Whisper.which(this)),
                    "Start over", (Runnable) () -> { BookText.clearAudioData(this, item.id); al = new Alignment(); mt.reset(); hlFrom = hlTo = -1; updateSyncLabel(); showSyncState(); });
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
        waitingReady = true;
        syncPanel = false;
        h.removeCallbacks(syncPoll);
        ensureModel(() -> ensureText(() -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 91);
            SyncService.start(this, item.id);
            h.postDelayed(this::askBackground, 1800);
            syncPanel = true;
            h.postDelayed(() -> { showSyncState(); h.postDelayed(syncPoll, 1000); }, 700);
            if (autoHidePanel) {
                autoHidePanel = false;
                final boolean[] mine = fcancel;
                h.postDelayed(() -> {
                    if (fcancel == mine && !mine[0] && SyncService.running) {
                        closePanel();
                        Toast.makeText(this, "Syncing in the background. Keep listening or reading, I'll tell you when it's ready.", Toast.LENGTH_LONG).show();
                    }
                }, 9000);
            }
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

    /**
     * Asks the sync service when the first word of a page is spoken, and waits for the answer. Hide keeps it going in the
     * background (the answer then arrives as a tappable pill); only Cancel stops it.
     */
    void runLocate(int target) {
        final boolean[] panel = fcancel;            // true once the dialog is hidden
        final boolean[] stop = new boolean[1];      // true only when the user cancels
        final long started = System.currentTimeMillis();
        setMsg("Looking for page " + (target + 1) + "…", -1);
        setBtns("Hide", (Runnable) this::closePanel, "Cancel", (Runnable) () -> { stop[0] = true; jobText = null; SyncService.goalWordReq = -1; closePanel(); updateBanner(); });
        new Thread(() -> {
            BookIndex b;
            try { b = BookText.index(this, item.id); } catch (Exception e) { b = null; }
            final BookIndex bk = b;
            if (bk == null) { ui(() -> { if (!stop[0]) resultOrPost("Couldn't read the book's text.", null, panel[0]); }); return; }
            ui(() -> {
                if (stop[0]) return;
                jobText = "Finding page " + (target + 1) + "…";
                SyncService.goalTimeOut = -1;
                SyncService.goalWordReq = bk.pageStart[target];
                if (!SyncService.running) SyncService.start(this, item.id);
                Dbg.i("Locate", "page " + (target + 1) + " word " + bk.pageStart[target]);
                final long[] svc = {System.currentTimeMillis()};
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (stop[0] || isFinishing()) return;
                        double gt = SyncService.goalTimeOut;
                        long sec = (System.currentTimeMillis() - started) / 1000;
                        if (gt >= 0) {
                            jobText = null;
                            SyncService.goalWordReq = -1;
                            Dbg.i("Locate", "page " + (target + 1) + " at " + (int) gt + " s after " + sec + " s" + (panel[0] ? " (dialog hidden)" : ""));
                            final double at = Math.max(0, gt - 0.3);
                            if (!panel[0] && fdlg != null) {
                                setMsg("Page " + (target + 1) + " is read at about " + fmtT(gt) + ". Checked against the book. Took " + sec + " s.", -1);
                                setBtns("Play and pin", (Runnable) () -> { pinAt(target, gt); seekAbs(at, true); closePanel(); },
                                        "Play only", (Runnable) () -> { seekAbs(at, true); closePanel(); });
                            } else postResult("Page " + (target + 1) + " is at " + fmtT(gt) + " · tap to play", () -> seekAbs(at, true));
                            loadSyncData();
                        } else if (gt == -2) {
                            jobText = null;
                            SyncService.goalWordReq = -1;
                            resultOrPost("I couldn't confirm page " + (target + 1) + " in the audio. The audio may skip it, be abridged, or differ from this edition.", null, panel[0]);
                        } else if (!SyncService.running && System.currentTimeMillis() - svc[0] > 4000) {
                            jobText = null;
                            SyncService.goalWordReq = -1;
                            resultOrPost("The listening stopped" + (SyncService.error.isEmpty() ? "." : ": " + SyncService.error), null, panel[0]);
                        } else {
                            jobText = "Finding page " + (target + 1) + "… " + sec + " s";
                            if (!panel[0] && fdlg != null) setMsg("Looking for page " + (target + 1) + "… " + sec + " s\n\nListening to short pieces of the audio and checking them against the book. You can hide this: it keeps going in the background.", -1);
                            h.postDelayed(this, 700);
                        }
                    }
                }, 700);
            });
        }).start();
    }


    /** Find my page when the spot isn't mapped yet: the sync service listens at the playhead. Hide keeps it going. */
    void runFindFast() {
        final boolean[] panel = fcancel;
        final boolean[] stop = new boolean[1];
        final double t0 = aT;
        final long started = System.currentTimeMillis();
        setMsg("Listening to the audio at " + fmtT(t0) + "…", -1);
        setBtns("Hide", (Runnable) this::closePanel, "Cancel", (Runnable) () -> { stop[0] = true; jobText = null; closePanel(); updateBanner(); });
        jobText = "Finding your page…";
        SyncService.hereWord = -1;
        if (!SyncService.running) SyncService.start(this, item.id);
        final long[] svc = {System.currentTimeMillis()};
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (stop[0] || isFinishing()) return;
                int w = SyncService.hereWord;
                long sec = (System.currentTimeMillis() - started) / 1000;
                if (w >= 0 && bi != null) {
                    jobText = null;
                    final int pg = bi.pageOf(w);
                    int[] sn = bi.sentenceAround(w);
                    StringBuilder sb = new StringBuilder();
                    for (int k = sn[0]; k <= Math.min(sn[1], sn[0] + 18); k++) sb.append(bi.w[k]).append(' ');
                    Dbg.i("Find", "listened: " + fmtT(t0) + " -> page " + (pg + 1) + " after " + sec + " s" + (panel[0] ? " (dialog hidden)" : ""));
                    if (!panel[0] && fdlg != null) {
                        setMsg("At " + fmtT(t0) + " the audio is on page " + (pg + 1) + ". Took " + sec + " s.\n\nReading: “" + sb.toString().trim() + (sn[1] > sn[0] + 18 ? "…" : "") + "”", -1);
                        setBtns("Pin page " + (pg + 1) + " here", (Runnable) () -> { goTo(pg, false); pinAt(pg, t0); closePanel(); },
                                "Go to page", (Runnable) () -> { goTo(pg, false); closePanel(); });
                    } else postResult("The audio is on page " + (pg + 1) + " · tap to go there", () -> goTo(pg, false));
                    loadSyncData();
                } else if (w >= 0) {
                    h.postDelayed(this, 500);   // the book text is still loading
                } else if (!SyncService.running && System.currentTimeMillis() - svc[0] > 4000) {
                    jobText = null;
                    resultOrPost("I couldn't match this part with confidence" + (SyncService.error.isEmpty() ? "." : ": " + SyncService.error)
                            + "\n\nPause on a stretch of someone speaking the book (not music, an intro or silence) and try again.", null, panel[0]);
                } else {
                    jobText = "Finding your page… " + sec + " s";
                    if (!panel[0] && fdlg != null) setMsg("Listening to the audio at " + fmtT(t0) + "… " + sec + " s\n\nYou can hide this: it keeps going in the background.", -1);
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
            if (SyncService.running && !SyncService.ready) return;   // still mapping around the playhead: don't compete with it
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

    /** Failure text in the dialog if it is open, else as a pill. */
    void failOrPost(String msg) {
        if (fdlg != null) fail(msg); else postResult(msg, null);
    }

    void ensureModel(Runnable next) {
        if (Whisper.hasModel(this)) { next.run(); return; }
        final boolean[] stop = new boolean[1];
        final long t0 = System.currentTimeMillis();
        // the download runs in the background service: hiding this panel or leaving the book does not stop it, only Cancel does
        SyncService.dlPct = 0;
        if (!SyncService.running) SyncService.start(this, item.id);
        setMsg("Downloading the speech model… 0%\n\nIt keeps going in the background. You can hide this and use your phone.", 0);
        setBtns("Hide", (Runnable) this::closePanel, "Cancel download", (Runnable) () -> { stop[0] = true; SyncService.stop(this); closePanel(); });
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (stop[0] || isFinishing()) return;
                if (Whisper.hasModel(ReaderActivity.this)) { next.run(); return; }
                int pc = SyncService.dlPct;
                if (!SyncService.running && System.currentTimeMillis() - t0 > 4000) {
                    failOrPost("The download didn't finish. Check your internet and try again." + (SyncService.error.isEmpty() ? "" : "\n\n" + SyncService.error));
                    return;
                }
                if (pc >= 0 && fdlg != null) setMsg("Downloading the speech model… " + pc + "%\n\nIt keeps going in the background. You can hide this and use your phone.", pc);
                h.postDelayed(this, 500);
            }
        }, 500);
    }

    void ensureText(Runnable next) {
        if (BookText.ready(this, item.id)) { next.run(); return; }
        final boolean[] stop = new boolean[1];
        final long t0 = System.currentTimeMillis();
        // the service reads the book's text: it carries on when this panel is hidden or the screen is off
        SyncService.textPct = 0;
        if (!SyncService.running) SyncService.start(this, item.id);
        setMsg("Reading the book… 0%\n\nIt keeps going in the background. You can hide this.", 0);
        setBtns("Hide", (Runnable) this::closePanel, "Cancel", (Runnable) () -> { stop[0] = true; SyncService.stop(this); closePanel(); });
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (stop[0] || isFinishing()) return;
                if (BookText.ready(ReaderActivity.this, item.id)) { loadSyncData(); next.run(); return; }
                int pc = SyncService.textPct;
                if (!SyncService.running && System.currentTimeMillis() - t0 > 4000) {
                    failOrPost(SyncService.error.isEmpty() ? "Couldn't read the text of this PDF." : SyncService.error);
                    return;
                }
                if (pc >= 0 && fdlg != null) setMsg("Reading the book… " + pc + "%\n\nIt keeps going in the background. You can hide this.", pc);
                h.postDelayed(this, 500);
            }
        }, 500);
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
        back.setPadding(dp(11), dp(11), dp(11), dp(11));
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        title = new TextView(this);
        title.setText(item.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextSize(15);
        aa.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        aa.setGravity(Gravity.CENTER);
        aa.setOnClickListener(v -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        lockBtn = new Glyph(3);
        lockBtn.setOnClickListener(v -> setLocked(true));
        lockBtn.setPadding(dp(13.5f), dp(11.5f), dp(13.5f), dp(11.5f));   // every top icon: a 40 dp box, an 18 dp drawing
        rotateBtn = new Glyph(4);
        rotateBtn.setPadding(dp(11), dp(11), dp(11), dp(11));
        rotateBtn.setOnClickListener(v -> toggleOrient());
        rotateBtn.setOnLongClickListener(v -> { setOrient(0); Toast.makeText(this, "Turning follows the phone", Toast.LENGTH_SHORT).show(); return true; });
        top.addView(rotateBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(lockBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(aa, new LinearLayout.LayoutParams(dp(40), dp(40)));
        applyOrient(Store.num(this, "rdOrient", 0));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        unlockBtn = new Glyph(3);
        unlockBtn.color = 0xFFFFFFFF;
        unlockBtn.round = true;
        unlockBtn.setVisibility(View.GONE);
        root.addView(unlockBtn, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) unlockBtn.getLayoutParams()).bottomMargin = dp(56);   // bottom middle, clear of the gesture bar

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
        // a quiet status line for the sync: what is happening, how long it will take, and when it is ready
        bannerWrap = new LinearLayout(this);
        bannerWrap.setGravity(Gravity.CENTER);
        syncBanner = new TextView(this);
        syncBanner.setTextSize(13);
        syncBanner.setTypeface(Typeface.DEFAULT_BOLD);
        syncBanner.setPadding(dp(16), dp(9), dp(16), dp(9));
        syncBanner.setTag("pill");
        syncBanner.setMaxLines(2);
        syncBanner.setOnClickListener(v -> {
            if (pendingText != null && System.currentTimeMillis() < pendingUntil) {
                Runnable act = pendingAct; pendingText = null; pendingAct = null; updateBanner();
                if (act != null) act.run();
            } else if (!SyncService.running && !SyncService.error.isEmpty()) { SyncService.error = ""; SyncService.start(this, item.id); }
            else showSync();
        });
        bannerWrap.addView(syncBanner);
        bannerWrap.setVisibility(View.GONE);
        stack.addView(bannerWrap, lpm(-1, -2, 12, 0, 12, 8));

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
            st.setMinimumHeight(dp(46));   // same height with or without the 'Go to page' button, so nothing above it moves
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
        // how pages change, per position: a switch with the choice written on it (defaults = how it always was)
        TextView l4 = new TextView(this);
        l4.setText("PAGE CHANGE");
        l4.setTextSize(11);
        l4.setLetterSpacing(0.1f);
        l4.setTag("sub");
        settings.addView(l4, lpm(-2, -2, 0, 16, 0, 0));
        settings.addView(valueRow("Upright: ", "left–right", "up–down", Store.flag(this, "rdPortScroll", false), on -> { Store.setFlag(this, "rdPortScroll", on); updateMode(); }), lpm(-1, -2, 0, 8, 0, 0));
        settings.addView(valueRow("Rotated: ", "left–right", "up–down", !Store.flag(this, "rdLandSwipe", false), on -> { Store.setFlag(this, "rdLandSwipe", !on); updateMode(); }), lpm(-1, -2, 0, 8, 0, 0));
        if (ui) {
            settings.addView(switchRow("Highlight the spoken sentence", hlOn, on -> { hlOn = on; Store.setFlag(this, "rdHl", on); if (pv != null) pv.invalidate(); }), lpm(-1, -2, 0, 12, 0, 0));
            settings.addView(switchRow("Turn pages with the audio", followOn, on -> { followOn = on; Store.setFlag(this, "rdFollow", on); }), lpm(-1, -2, 0, 12, 0, 0));
            settings.addView(switchRow("Offer to sync when I press Play", Store.flag(this, "syncAsk", true), on -> Store.setFlag(this, "syncAsk", on)), lpm(-1, -2, 0, 12, 0, 0));
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
        if (sp != null) { sp.setBackgroundColor(desk); sp.pictureReady(); }
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
        lockBtn.color = ink; lockBtn.invalidate();
        rotateBtn.color = ink; rotateBtn.invalidate();
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
        boolean round;   // draws a dark disc behind the glyph (the unlock padlock must be visible on any page)
        boolean land;    // rotate icon: the phone is on its side now
        int rotMode;     // rotate icon: 0 follows the phone, 1 fixed upright, 2 fixed on its side
        Glyph(int t) { super(ReaderActivity.this); type = t; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (round) { p.setColor(0xCC202020); c.drawCircle(w / 2, hh / 2, Math.min(w, hh) / 2, p); }
            p.setColor(color);
            if (type == 3) {
                float l = round ? w * 0.26f : getPaddingLeft(), r = w - (round ? w * 0.26f : getPaddingRight());
                float t = round ? hh * 0.26f : getPaddingTop(), b = hh - (round ? hh * 0.26f : getPaddingBottom());
                float bw = r - l, bodyTop = t + (b - t) * 0.42f;
                c.drawRoundRect(l, bodyTop, r, b, bw * 0.16f, bw * 0.16f, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(bw * 0.14f); p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(l + bw * 0.2f, t, r - bw * 0.2f, t + (bodyTop - t) * 2f, 180, 180, false, p);
                c.drawLine(l + bw * 0.2f, t + (bodyTop - t), l + bw * 0.2f, bodyTop, p);
                c.drawLine(r - bw * 0.2f, t + (bodyTop - t), r - bw * 0.2f, bodyTop, p);
            } else if (type == 0 || type == 4) {
                // the top-bar icons: all drawn in the padded 18 dp square of a 40 dp box, one stroke weight, so they look the same size
                float l = getPaddingLeft(), t = getPaddingTop(), aw = w - l - getPaddingRight(), ah = hh - t - getPaddingBottom();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                if (type == 0) {
                    Path path = new Path();
                    path.moveTo(l + aw * 0.68f, t + ah * 0.06f); path.lineTo(l + aw * 0.30f, t + ah * 0.5f); path.lineTo(l + aw * 0.68f, t + ah * 0.94f);
                    c.drawPath(path, p);
                } else {
                    // a phone (upright or on its side, as it is now) inside a turning arrow; filled when the turning is fixed (not following the phone)
                    float pw2 = land ? 0.62f : 0.38f, ph2 = land ? 0.38f : 0.62f;
                    RectF ph = new RectF(l + aw * (0.5f - pw2 / 2), t + ah * (0.5f - ph2 / 2), l + aw * (0.5f + pw2 / 2), t + ah * (0.5f + ph2 / 2));
                    p.setStrokeWidth(dp(1.7f));
                    if (rotMode != 0) p.setStyle(Paint.Style.FILL_AND_STROKE);
                    c.drawRoundRect(ph, dp(2), dp(2), p);
                    p.setStyle(Paint.Style.STROKE);
                    float cx = l + aw / 2, cy = t + ah / 2, R = Math.min(aw, ah) * 0.5f;
                    c.drawArc(new RectF(cx - R, cy - R, cx + R, cy + R), -75, 100, false, p);
                    double a = Math.toRadians(25);
                    float ex = (float) (cx + R * Math.cos(a)), ey = (float) (cy + R * Math.sin(a)), tx = (float) -Math.sin(a), ty = (float) Math.cos(a), nx = (float) Math.cos(a), ny = (float) Math.sin(a), hl = R * 0.45f;
                    Path head = new Path();
                    head.moveTo(ex + tx * hl * 0.5f, ey + ty * hl * 0.5f); head.lineTo(ex + nx * hl * 0.55f, ey + ny * hl * 0.55f); head.moveTo(ex + tx * hl * 0.5f, ey + ty * hl * 0.5f); head.lineTo(ex - nx * hl * 0.55f, ey - ny * hl * 0.55f);
                    c.drawPath(head, p);
                }
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
    // ====================== rotated phone: one long scroll ======================
    /**
     * With the phone on its side a page fitted to the height is tiny, so the pages are fitted to the WIDTH and stacked: scrolling down runs straight
     * from the end of a page into the top of the next one (and back), the page counter and the saved place follow, the marker is followed smoothly.
     * Same margin trim, same pictures (the page cache), same marker as the portrait view.
     */
    class ScrollPages extends ListView {
        final GestureDetector sgd2;
        final float[] rowH = new float[Math.max(1, n)];   // known row heights, so a row does not jump when its picture arrives
        float lastKnownH;
        final BaseAdapter ad;
        int lastP = -1;
        boolean auto;   // the scroll in progress is ours (following the marker), not the reader's finger

        ScrollPages(Context c) {
            super(c);
            setDivider(null);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setSelector(android.R.color.transparent);
            setCacheColorHint(0);
            setPadding(0, dp(28), 0, dp(24));
            setClipToPadding(false);
            ad = new BaseAdapter() {
                @Override public int getCount() { return n; }
                @Override public Object getItem(int i) { return null; }
                @Override public long getItemId(int i) { return i; }
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int i) { return false; }
                @Override public View getView(int p, View v, ViewGroup parent) {
                    Row r = v instanceof Row ? (Row) v : new Row(getContext());
                    r.bind(p);
                    ensure(p - 1); ensure(p); ensure(p + 1);
                    return r;
                }
            };
            setAdapter(ad);
            sgd2 = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (locked) { revealUnlock(); return true; }
                    if (settings.getVisibility() == View.VISIBLE) settings.setVisibility(View.GONE); else showChrome(!chrome);
                    return true;
                }
            });
            setOnScrollListener(new OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView v, int st) {
                    if (st == SCROLL_STATE_IDLE) { auto = false; return; }
                    if (st == SCROLL_STATE_TOUCH_SCROLL || !auto) { auto = false; lastManual = System.currentTimeMillis(); if (chrome && settings.getVisibility() != View.VISIBLE) showChrome(false); }
                }
                @Override public void onScroll(AbsListView v, int first, int count, int total) {
                    int pg = pageAtTop();
                    if (pg != lastP && pg >= 0 && pg < n) {
                        lastP = pg;
                        if (pg != cur) {
                            cur = pg; updateLabel(cur); if (seek != null) seek.setProgress(cur);
                            h.removeCallbacks(saver); h.postDelayed(saver, 700);
                            h.removeCallbacks(prefetch); h.postDelayed(prefetch, 2500);
                        }
                    }
                }
            });
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sgd2.onTouchEvent(e);
            return super.onTouchEvent(e);
        }

        int pageAtTop() {
            int p = pointToPosition(getWidth() / 2, getHeight() / 3);
            return p == INVALID_POSITION ? Math.max(0, getFirstVisiblePosition()) : p;
        }

        void showPage(int p) { setSelectionFromTop(Math.max(0, Math.min(n - 1, p)), 0); lastP = -1; }

        /** A picture arrived, the marker moved, the margins changed: draw again (heights are re-measured only for rows whose picture just arrived). */
        void pictureReady() {
            for (int i = 0; i < getChildCount(); i++) { View c = getChildAt(i); if (c instanceof Row) ((Row) c).refresh(); c.invalidate(); }
        }

        /** The margins (trim) changed: every row has another height. */
        void relayout() { java.util.Arrays.fill(rowH, 0); ad.notifyDataSetChanged(); }

        /** Keeps the sentence being read in the comfortable part of the screen: scrolls only when it leaves it, smoothly, across pages. */
        void follow(int word) {
            if (word < 0 || getHeight() == 0 || bi == null || auto) return;   // one automatic scroll at a time
            int p = bi.pageOf(word);
            int first = getFirstVisiblePosition(), last = getLastVisiblePosition();
            if (p < first || p > last) { showPage(p); return; }
            View row = getChildAt(p - first);
            if (!(row instanceof Row)) return;
            float y = ((Row) row).yOfWord(word);
            if (y < 0) return;
            int hh = getHeight();
            float yy = row.getTop() + y;
            if (yy < hh * 0.12f || yy > hh * 0.6f) { auto = true; smoothScrollBy((int) (yy - hh * 0.3f), 450); }
        }

        class Row extends View {
            int page = -1;
            Bitmap b;
            RectF src = new RectF();
            float sc = 1;
            final int gap = dp(8);
            final RectF dst = new RectF();

            Row(Context c) { super(c); }

            void bind(int p) { page = p; refresh(); requestLayout(); invalidate(); }

            void refresh() {
                Bitmap nb = cache.get(page);
                if (nb != null && nb != b) { b = nb; requestLayout(); }
                else if (nb == null && b != null && cache.get(page) == null) { /* evicted: keep the old size and the old picture until it is back */ }
            }

            @Override protected void onMeasure(int wms, int hms) {
                int W = MeasureSpec.getSize(wms);
                float hgt;
                if (b != null && pv != null) {
                    src = pv.cropOf(b, page);
                    sc = W / Math.max(1f, src.width());
                    hgt = src.height() * sc + gap;
                    rowH[page] = hgt; lastKnownH = hgt;
                } else hgt = rowH[page] > 0 ? rowH[page] : lastKnownH > 0 ? lastKnownH : W * 1.35f;
                setMeasuredDimension(W, (int) Math.ceil(hgt));
            }

            /** y of a word inside this row, or -1 while the picture is missing. */
            float yOfWord(int word) {
                if (b == null || bi == null || word < 0 || word >= bi.count()) return -1;
                return (bi.box[word * 4 + 1] * b.getHeight() - src.top) * sc;
            }

            @Override protected void onDraw(Canvas c) {
                c.drawColor(DESK[theme]);
                Bitmap bm = cache.get(page);
                if (bm == null) { ensure(page); bm = b; }
                if (bm == null || pv == null) return;
                if (bm != b) { b = bm; src = pv.cropOf(b, page); sc = getWidth() / Math.max(1f, src.width()); }
                dst.set(0, 0, getWidth(), src.height() * sc);
                c.drawBitmap(bm, new Rect((int) src.left, (int) src.top, (int) src.right, (int) src.bottom), dst, pv.bmpP);
                if (hlOn && bi != null && hlFrom >= 0) pv.drawHighlight(c, page, bm, src, 0, 0, sc);
            }
        }
    }

    // ====================== the pages ======================
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
                    if (locked) { revealUnlock(); return true; }
                    if (settings.getVisibility() == View.VISIBLE) { settings.setVisibility(View.GONE); return true; }
                    // Taps never turn the page (swipe for that). A tap just shows or hides the controls.
                    showChrome(!chrome);
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (locked) return true;   // no zoom by accident while locked
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

        /** Turning the phone: a zoom/pan from the other shape would leave the page off screen. */
        @Override protected void onSizeChanged(int w, int h2, int ow, int oh) {
            super.onSizeChanged(w, h2, ow, oh);
            if (ow != 0 || oh != 0) { zoom = 1; px = py = 0; dragX = 0; }
            post(ReaderActivity.this::updateMode);   // also the first time: a book opened with the phone already on its side
        }

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
            if (!locked) sgd.onTouchEvent(e);   // no pinch zoom while locked
            if (locked || !sgd.isInProgress()) gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && dragging && !flung && zoom <= 1.01f)
                settle(dragX < -getWidth() * 0.22f && cur < n - 1 ? 1 : dragX > getWidth() * 0.22f && cur > 0 ? -1 : 0);
            return true;
        }

        RectF cropOf(Bitmap b, int idx) {
            if (b == null) return new RectF(0, 0, 100, 140);
            int w = b.getWidth(), hh = b.getHeight();
            if (!trim) return new RectF(0, 0, w, hh);
            float l = cl, t = ct, r = cr, bt = cb;   // margins to cut
            float[] q = pageB != null && idx >= 0 && idx < pageB.length ? pageB[idx] : null;
            if (q != null) {   // this page's own text is never cut: add its area back
                final float pad = 0.012f;
                l = Math.min(l, Math.max(0, q[0] - pad)); t = Math.min(t, Math.max(0, q[1] - pad));
                r = Math.min(r, Math.max(0, 1 - q[2] - pad)); bt = Math.min(bt, Math.max(0, 1 - q[3] - pad));
            }
            return new RectF(l * w, t * hh, w - r * w, hh - bt * hh);
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
                RectF src = cropOf(b, idx);
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
