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
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;

/**
 * The page with bigger text (the "Text size" slider). Nothing about the look changes: every word is cut out of the PDF's own rendering (same font,
 * weight, colour, italics, footnote numbers, headings) and only its size and place change, so the text gets bigger and wraps to the next line when
 * it no longer fits (see WordFlow). One row per PDF page, in reading order, with the spoken sentence marked. The page pictures come from the host
 * (one picture per page, cached, so moving the slider only re-places words: no new rendering, nothing to wait for).
 */
class ReflowView extends ListView {
    static final float MIN_Z = 1.0f, MAX_Z = 2.0f;

    interface Host {
        void onTap();
        void onPage(int page);
        void onUserScroll();
        /** The page picture (any resolution: coordinates are fractions of the page), or null while it is being rendered; the host calls {@link #pictureReady()} then. */
        Bitmap pageBitmap(int page);
        /** Page height / page width. */
        float aspect(int page);
    }

    private final BookIndex bi;
    private final String[] tok;       // printed words, may be null (then no hyphen joining)
    private final Host host;
    private final GestureDetector gd;
    private float zoom = 1.3f;
    private float bookTw = 0.8f;      // width of the body text as a fraction of the page: 1.0x = "the text fills the line"
    private int theme;
    private int hlFrom = -1, hlTo = -1, hlStyle;
    private int lastPage = -1;
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), bandP = new Paint(Paint.ANTI_ALIAS_FLAG), lblP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BaseAdapter adapter;
    private final LruCache<Integer, WordFlow.Flow> flows = new LruCache<>(10);
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

    /** The body text width of the book (median over a sample of pages), so the size means the same on every page. */
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

    @Override public boolean onTouchEvent(MotionEvent e) {
        gd.onTouchEvent(e);
        return super.onTouchEvent(e);
    }

    float zoom() { return zoom; }

    /** 1.0 = the text as the page has it (filling the line); up to 2.0 = twice as big. Only re-places words, so it can follow a slider live. */
    void setZoom(float z) {
        z = Math.max(MIN_Z, Math.min(MAX_Z, z));
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
        pictureReady();
    }

    /** A page picture arrived (or the colours changed): draw again. */
    void pictureReady() { for (int i = 0; i < getChildCount(); i++) getChildAt(i).invalidate(); }

    int pageAtTop() {
        int p = pointToPosition(getWidth() / 2, getHeight() / 3);
        return p == INVALID_POSITION ? Math.max(0, getFirstVisiblePosition()) : p;
    }

    void showPage(int p) { setSelectionFromTop(Math.max(0, Math.min(bi.pages - 1, p)), 0); }

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

    // ---------- the spoken sentence ----------
    /** The sentence being read. (The word inside it is tracked by the reader but not shown.) */
    void setHighlight(int from, int to, int style) {
        if (from == hlFrom && to == hlTo && style == hlStyle) return;
        hlFrom = from; hlTo = to; hlStyle = style;
        pictureReady();
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

        void bind(int p) {
            page = p;
            try { f = flow(p); } catch (Throwable t) { Dbg.e("Reflow", "layout of page " + (p + 1) + " failed", t); f = null; }
            requestLayout(); invalidate();
        }

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
            try { paintPage(c); } catch (Throwable t) { Dbg.e("Reflow", "drawing page " + (page + 1) + " failed", t); }
        }

        private void paintPage(Canvas c) {
            c.drawText("— " + (page + 1) + " —", getWidth() / 2f, topPad * 0.72f, lblP);
            Bitmap bm = host.pageBitmap(page);
            if (bm != null) {
                Rect clip = c.getClipBounds();
                int bw = bm.getWidth();      // y units are page widths, so pixels = units * bitmap width
                for (int i = 0; i < f.n; i++) {
                    float dy = topPad + f.y[i];
                    if (dy + f.h[i] < clip.top - 2 || dy > clip.bottom + 2) continue;
                    if (f.wholePage) { dst.set(f.x[i], dy, f.x[i] + f.w[i], dy + f.h[i]); c.drawBitmap(bm, null, dst, bmpP); continue; }
                    int l = Math.max(0, Math.round(f.sx0[i] * bw) - 1), r = Math.min(bm.getWidth(), Math.round(f.sx1[i] * bw) + 1);
                    int t = Math.max(0, Math.round(f.sy0[i] * bw)), b = Math.min(bm.getHeight(), Math.round(f.sy1[i] * bw));
                    if (r <= l || b <= t) continue;
                    float k = f.w[i] / Math.max(1f, (f.sx1[i] - f.sx0[i]) * bw);   // destination pixels per source pixel
                    float sy0 = f.sy0[i] * bw;
                    dst.set(f.x[i] - (Math.round(f.sx0[i] * bw) - l) * k, dy + (t - sy0) * k, f.x[i] + (r - Math.round(f.sx0[i] * bw)) * k, dy + (b - sy0) * k);
                    srcR.set(l, t, r, b);
                    c.drawBitmap(bm, srcR, dst, bmpP);
                }
            }
            drawSentence(c);
        }

        private void drawSentence(Canvas c) {
            if (hlFrom < 0 || f.wholePage) return;
            int a = f.a, b = a + f.itemOfWord.length;
            int lo = Math.max(hlFrom, a), hi = Math.min(hlTo, b - 1);
            if (lo > hi) return;
            boolean dark = theme == 3;
            int itemLo = f.itemOfWord[lo - a], itemHi = f.itemOfWord[hi - a];
            bandP.setColor(hlStyle == 1 ? (dark ? 0xFFFFD54F : 0xFFD9822B) : (dark ? 0x55FFD54F : 0x70F2B93B));
            float pad = dp(1.5f);
            int i = itemLo;
            while (i <= itemHi) {   // one band per printed line (neighbouring words are merged)
                float yTop = topPad + f.y[i] + 0.2f * f.h[i], yBot = topPad + f.y[i] + 0.96f * f.h[i];
                float x0 = f.x[i], x1 = f.x[i] + f.w[i];
                int j = i + 1;
                while (j <= itemHi && Math.abs(f.y[j] - f.y[i]) < 0.35f * f.h[i]) { x1 = Math.max(x1, f.x[j] + f.w[j]); yTop = Math.min(yTop, topPad + f.y[j] + 0.2f * f.h[j]); j++; }
                if (hlStyle == 1) c.drawRoundRect(x0 - pad, yBot - dp(2.2f), x1 + pad, yBot, dp(1.1f), dp(1.1f), bandP);
                else c.drawRoundRect(x0 - pad, yTop - pad, x1 + pad, yBot + pad, dp(3), dp(3), bandP);
                i = j;
            }
        }
    }
}
