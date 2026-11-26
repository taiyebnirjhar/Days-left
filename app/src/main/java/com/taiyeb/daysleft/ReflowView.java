package com.taiyeb.daysleft;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The zoomed book. Zooming does not change how anything looks: every word is cut out of the PDF's own rendering (same font, weight, colour, italics,
 * footnote numbers, headings) and only its size and place change, so the text gets bigger and wraps to the next line when it no longer fits
 * (see WordFlow). One row per PDF page, in reading order. The page is rendered in horizontal bands at exactly the size on screen, so the words are
 * sharp at any zoom. The spoken sentence and word are drawn over the words.
 */
class ReflowView extends ListView {
    static final float MIN_Z = 1.0f, MAX_Z = 3.4f;
    private static final int BAND = 512, OVERLAP = 192;

    interface Host {
        void onTap();
        void onPage(int page);
        void onUserScroll();
        /** The pinch went below the original size: go back to the page. */
        void onPinchOut();
        /** Renders a part of a page at {@code sc} pixels per page width: rectangle (x, y, w, h) in those pixels. Background thread. */
        Bitmap renderBand(int page, float sc, int x, int y, int w, int h);
        /** Page height / page width. */
        float aspect(int page);
    }

    private final BookIndex bi;
    private final String[] tok;       // printed words, may be null (then no hyphen joining)
    private final Host host;
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private float zoom = 1.6f;
    private boolean pinching;
    private float bookTw = 0.8f;      // width of the body text as a fraction of the page: the zoom is relative to "the text fills the line"
    private int theme;
    private int hlFrom = -1, hlTo = -1, hlStyle, hlWord = -1;
    private int lastPage = -1;
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG), bandP = new Paint(Paint.ANTI_ALIAS_FLAG), lblP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BaseAdapter adapter;
    private final LruCache<Integer, WordFlow.Flow> flows = new LruCache<>(10);
    private final LruCache<Long, Bitmap> bands = new LruCache<Long, Bitmap>(96 * 1024) {
        @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private final Map<Integer, TreeSet<Integer>> scalesOf = new HashMap<>();   // page -> source scales that have bands
    private final Set<Long> pending = new HashSet<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final float density;

    ReflowView(Context c, BookIndex bi, String[] tok, Host host) {
        super(c);
        this.bi = bi; this.tok = tok; this.host = host;
        density = getResources().getDisplayMetrics().density;
        setDivider(null);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setSelector(android.R.color.transparent);
        setCacheColorHint(0);
        lblP.setTextAlign(Paint.Align.CENTER);
        lblP.setTextSize(11 * density);
        bookTw = measureBookWidth();
        adapter = new BaseAdapter() {
            @Override public int getCount() { return ReflowView.this.bi.pages; }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int i) { return false; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                PageRow r = v instanceof PageRow ? (PageRow) v : new PageRow(getContext());
                r.bind(p);
                return r;
            }
        };
        setAdapter(adapter);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { pinching = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) { if (scaleBy(d.getScaleFactor())) host.onPinchOut(); return true; }
            @Override public void onScaleEnd(ScaleGestureDetector d) { endPinch(); }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { host.onTap(); return true; }
        });
        setOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { if (s != SCROLL_STATE_IDLE) host.onUserScroll(); }
            @Override public void onScroll(AbsListView v, int first, int count, int total) {
                int p = pageAtTop();
                if (p != lastPage) { lastPage = p; host.onPage(p); }
            }
        });
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    /** The body text width of the book (median over a sample of pages), so the zoom means the same on every page. */
    private float measureBookWidth() {
        java.util.ArrayList<Float> w = new java.util.ArrayList<>();
        int step = Math.max(1, bi.pages / 24);
        float asp = host.aspect(0);
        for (int p = 0; p < bi.pages; p += step) {
            int a = bi.pageStart[p], b = bi.pageStart[p + 1];
            if (b - a < 60) continue;
            float[] e = WordFlow.extent(bi.box, a, b, asp);
            w.add(e[1] - e[0]);
        }
        if (w.isEmpty()) return 0.8f;
        java.util.Collections.sort(w);
        return Math.max(0.3f, Math.min(1f, w.get(w.size() / 2)));
    }

    // ---------- zoom ----------
    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if (sgd.isInProgress()) return true;
        return super.onTouchEvent(e);
    }

    float zoom() { return zoom; }

    /** A pinch step (also fed by the page view's running gesture). True = smaller than the original size: leave. */
    boolean scaleBy(float f) {
        pinching = true;
        float nz = zoom * f;
        if (nz < MIN_Z) { if (f < 1) return true; nz = MIN_Z; }
        setZoom(Math.min(MAX_Z, nz));
        return false;
    }

    void endPinch() {
        pinching = false;
        invalidateRows();   // now the bands are fetched at the exact size
    }

    void setZoom(float z) {
        if (Math.abs(z - zoom) < 0.004f) return;
        float ratio = z / zoom;
        zoom = z;
        flows.evictAll();
        int pos = getFirstVisiblePosition();
        View c0 = getChildAt(0);
        int top = c0 == null ? 0 : c0.getTop();
        adapter.notifyDataSetChanged();
        setSelectionFromTop(pos, (int) (top * ratio));
    }

    void setColors(int ink, int paper, int theme) {
        this.theme = theme;
        // the reader's own colour filter: white -> paper, black -> ink
        float[] pr = {android.graphics.Color.red(paper), android.graphics.Color.green(paper), android.graphics.Color.blue(paper)};
        float[] ik = {android.graphics.Color.red(ink), android.graphics.Color.green(ink), android.graphics.Color.blue(ink)};
        bmpP.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                (pr[0] - ik[0]) / 255f, 0, 0, 0, ik[0],
                0, (pr[1] - ik[1]) / 255f, 0, 0, ik[1],
                0, 0, (pr[2] - ik[2]) / 255f, 0, ik[2],
                0, 0, 0, 1, 0})));
        lblP.setColor((ink & 0x00FFFFFF) | 0x66000000);
        setBackgroundColor(paper);
        invalidateRows();
    }

    private void invalidateRows() { for (int i = 0; i < getChildCount(); i++) getChildAt(i).invalidate(); }

    int pageAtTop() {
        int p = pointToPosition(getWidth() / 2, getHeight() / 3);
        return p == INVALID_POSITION ? Math.max(0, getFirstVisiblePosition()) : p;
    }

    void showPage(int p) { setSelectionFromTop(Math.max(0, Math.min(bi.pages - 1, p)), 0); }

    // ---------- layout of a page ----------
    private float lineWidth() { int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels; return w - 2 * dp(12); }

    private WordFlow.Flow flow(int p) {
        WordFlow.Flow f = flows.get(p);
        if (f != null) return f;
        int a = bi.pageStart[p], b = bi.pageStart[p + 1];
        float lw = lineWidth();
        f = WordFlow.layout(bi.box, tok, bi.flag, a, b, host.aspect(p), lw, lw / bookTw * zoom, dp(12));
        flows.put(p, f);
        return f;
    }

    /** The scale bands are rendered at: the exact one, or while pinching a coarse step (so a pinch does not render a new set every frame). */
    private int srcScale(float sc) {
        if (!pinching) return Math.max(64, Math.round(sc));
        double step = Math.log(1.1);
        return (int) Math.round(Math.exp(Math.round(Math.log(sc) / step) * step));
    }

    private static long key(int page, int sc, int band) { return ((long) page << 40) | ((long) sc << 16) | band; }

    private int bandX0(WordFlow.Flow f, int sc) { return Math.max(0, Math.round((f.wholePage ? 0 : f.minX - 0.01f) * sc)); }

    /** The bitmap holding band {@code band} of the page at scale sc, or null (and a request is queued). */
    private Bitmap band(WordFlow.Flow f, int page, int sc, int band) {
        long k = key(page, sc, band);
        Bitmap bm = bands.get(k);
        if (bm != null) return bm;
        synchronized (pending) { if (!pending.add(k)) return null; }
        final float asp = host.aspect(page);
        final int x0 = bandX0(f, sc);
        final int x1 = Math.min(sc, Math.round((f.wholePage ? 1 : f.maxX + 0.01f) * sc));
        final int pageH = Math.max(1, Math.round(asp * sc));
        final int y0 = f.wholePage ? 0 : band * BAND;
        final int h = f.wholePage ? pageH : Math.min(pageH - y0, BAND + OVERLAP);
        if (h <= 0 || x1 <= x0) { synchronized (pending) { pending.remove(k); } return null; }
        exec.submit(() -> {
            try {
                Bitmap nb = host.renderBand(page, sc, x0, y0, x1 - x0, h);
                if (nb != null) {
                    post(() -> {
                        bands.put(k, nb);
                        TreeSet<Integer> ts = scalesOf.get(page);
                        if (ts == null) { ts = new TreeSet<>(); scalesOf.put(page, ts); }
                        ts.add(sc);
                        synchronized (pending) { pending.remove(k); }
                        invalidateRows();
                    });
                    return;
                }
            } catch (Throwable ignored) {}
            synchronized (pending) { pending.remove(k); }
        });
        return null;
    }

    /** Any band that already covers this source row, from another scale: used while the exact one is being rendered (blurry for a moment, never blank). */
    private Bitmap fallback(WordFlow.Flow f, int page, int wantSc, float srcTopUnits, int[] out) {
        TreeSet<Integer> ts = scalesOf.get(page);
        if (ts == null) return null;
        Bitmap best = null; int bestD = Integer.MAX_VALUE;
        for (int s2 : ts) {
            int band2 = f.wholePage ? 0 : Math.max(0, (int) (srcTopUnits * s2) / BAND);
            Bitmap b2 = bands.get(key(page, s2, band2));
            if (b2 != null && Math.abs(s2 - wantSc) < bestD) { best = b2; bestD = Math.abs(s2 - wantSc); out[0] = s2; out[1] = band2; }
        }
        return best;
    }

    // ---------- the spoken sentence and word ----------
    void setHighlight(int from, int to, int style, int word) {
        if (from == hlFrom && to == hlTo && style == hlStyle && word == hlWord) return;
        hlFrom = from; hlTo = to; hlStyle = style; hlWord = word;
        invalidateRows();
    }

    /** Keeps the word being read on screen (not at the very top or bottom). */
    void follow(int word) {
        if (word < 0 || getHeight() == 0) return;
        int p = bi.pageOf(word);
        int first = getFirstVisiblePosition(), last = getLastVisiblePosition();
        if (p < first || p > last) { showPage(p); return; }
        View row = getChildAt(p - first);
        if (!(row instanceof PageRow)) return;
        float y = ((PageRow) row).yOfWord(word);
        if (y < 0) return;
        int h = getHeight();
        float yy = row.getTop() + y;
        if (yy < h * 0.15f || yy > h * 0.62f) smoothScrollBy((int) (yy - h * 0.3f), 400);
    }

    // ---------- one page ----------
    private class PageRow extends View {
        int page = -1;
        WordFlow.Flow f;
        final int topPad = dp(22);
        final RectF dst = new RectF();
        final Rect srcR = new Rect();

        PageRow(Context c) { super(c); }

        void bind(int p) { page = p; f = flow(p); requestLayout(); invalidate(); }

        @Override protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            setMeasuredDimension(w, f == null ? topPad : (int) Math.ceil(f.height) + topPad + dp(8));
        }

        /** y of a word's line inside this row (for scrolling), or -1. */
        float yOfWord(int word) {
            if (f == null) return -1;
            int k = word - f.a;
            if (k < 0 || k >= f.itemOfWord.length) return -1;
            return topPad + f.y[f.itemOfWord[k]];
        }

        @Override protected void onDraw(Canvas c) {
            if (f == null) return;
            c.drawText("— " + (page + 1) + " —", getWidth() / 2f, topPad * 0.72f, lblP);
            Rect clip = c.getClipBounds();
            int sc = f.wholePage ? Math.max(64, Math.round(f.scale)) : srcScale(f.scale);
            int[] fb = new int[2];
            for (int i = 0; i < f.n; i++) {
                float dy = topPad + f.y[i];
                if (dy + f.h[i] < clip.top - 2 || dy > clip.bottom + 2) continue;
                float topU = Math.max(0, f.sy0[i]);
                int band = f.wholePage ? 0 : (int) (topU * sc) / BAND;
                Bitmap bm = band(f, page, sc, band);
                int useSc = sc, useBand = band;
                if (bm == null) { bm = fallback(f, page, sc, topU, fb); if (bm != null) { useSc = fb[0]; useBand = fb[1]; } }
                if (bm == null) continue;
                if (f.wholePage) { dst.set(f.x[i], dy, f.x[i] + f.w[i], dy + f.h[i]); c.drawBitmap(bm, null, dst, bmpP); continue; }
                int bx0 = bandX0(f, useSc), by0 = useBand * BAND;
                float k = f.w[i] / Math.max(1f, (f.sx1[i] - f.sx0[i]) * useSc);      // destination pixels per source pixel (1 at the exact scale)
                int l = Math.round(f.sx0[i] * useSc) - bx0 - 1, r = Math.round(f.sx1[i] * useSc) - bx0 + 1;
                int t = Math.round(f.sy0[i] * useSc) - by0, b = Math.round(f.sy1[i] * useSc) - by0;
                srcR.set(Math.max(0, l), Math.max(0, t), Math.min(bm.getWidth(), r), Math.min(bm.getHeight(), b));
                if (srcR.width() <= 0 || srcR.height() <= 0) continue;
                // the destination follows the source clipping so nothing stretches
                dst.set(f.x[i] + (srcR.left - (l + 1)) * k, dy + (srcR.top - t) * k, f.x[i] + (srcR.right - (l + 1)) * k, dy + (srcR.bottom - t) * k);
                c.drawBitmap(bm, srcR, dst, bmpP);
            }
            drawHighlight(c);
        }

        private void drawHighlight(Canvas c) {
            if (hlFrom < 0 || f.wholePage) return;
            int a = f.a, b = a + f.itemOfWord.length;
            int lo = Math.max(hlFrom, a), hi = Math.min(hlTo, b - 1);
            if (lo > hi) return;
            boolean dark = theme == 3;
            int itemLo = f.itemOfWord[lo - a], itemHi = f.itemOfWord[hi - a];
            bandP.setColor(hlStyle == 1 ? (dark ? 0xFFFFD54F : 0xFFD9822B) : (dark ? 0x55FFD54F : 0x70F2B93B));
            float pad = dp(1.5f);
            int i = itemLo;
            while (i <= itemHi) {   // the sentence: one band per printed line (neighbouring words are merged)
                float yTop = topPad + f.y[i] + 0.2f * f.h[i], yBot = topPad + f.y[i] + 0.96f * f.h[i];
                float x0 = f.x[i], x1 = f.x[i] + f.w[i];
                int j = i + 1;
                while (j <= itemHi && Math.abs(f.y[j] - f.y[i]) < 0.35f * f.h[i]) { x1 = Math.max(x1, f.x[j] + f.w[j]); yTop = Math.min(yTop, topPad + f.y[j] + 0.2f * f.h[j]); j++; }
                if (hlStyle == 1) c.drawRoundRect(x0 - pad, yBot - dp(2.2f), x1 + pad, yBot, dp(1.1f), dp(1.1f), bandP);
                else c.drawRoundRect(x0 - pad, yTop - pad, x1 + pad, yBot + pad, dp(3), dp(3), bandP);
                i = j;
            }
            if (hlWord >= lo && hlWord <= hi) {   // the word being spoken: a filled box in both styles
                int w = f.itemOfWord[hlWord - a];
                bandP.setColor(((dark ? 0xFFFFD54F : 0xFFD9822B) & 0x00FFFFFF) | 0xB0000000);
                c.drawRoundRect(f.x[w] - pad, topPad + f.y[w] + 0.2f * f.h[w] - pad, f.x[w] + f.w[w] + pad, topPad + f.y[w] + 0.96f * f.h[w] + pad, dp(3), dp(3), bandP);
            }
        }
    }
}
