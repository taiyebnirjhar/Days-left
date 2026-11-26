package com.taiyeb.daysleft;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The text view of a book: the same words as the PDF, set to the screen width at any size you pinch to, so zooming in makes the text bigger
 * and it still fits (nothing runs off the side). One row per PDF page, in reading order, with the spoken sentence highlighted and followed.
 * Built from the BookIndex (word boxes tell where lines and paragraphs break) and the printed words saved by BookText.
 */
class ReflowView extends ListView {
    static final float MIN_SP = 13, MAX_SP = 46;

    interface Host {
        void onTap();
        void onPage(int page);
        void onUserScroll();
        /** The pinch went below the smallest size: go back to the page. */
        void onPinchOut();
    }

    private static class PageText {
        SpannableStringBuilder sb;
        int[] ws, we;               // char range of every word of the page
        final java.util.ArrayList<Object> marks = new java.util.ArrayList<>();
    }

    private final BookIndex bi;
    private final String[] disp;
    private final Host host;
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private final Map<Integer, PageText> cache = new LinkedHashMap<Integer, PageText>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Entry<Integer, PageText> e) { return size() > 14; }
    };
    private float sizeSp = 20;
    private int ink = 0xFF222222, paper = 0xFFF4ECD8, hlColor = 0x70F2B93B;
    private int hlFrom = -1, hlTo = -1, hlStyle;
    private int lastPage = -1;
    private final BaseAdapter adapter;

    ReflowView(Context c, BookIndex bi, String[] disp, Host host) {
        super(c);
        this.bi = bi; this.disp = disp; this.host = host;
        setDivider(null);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setSelector(android.R.color.transparent);
        setCacheColorHint(0);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return ReflowView.this.bi.pages; }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int i) { return false; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                LinearLayout row; TextView lbl, body;
                if (v == null) {
                    row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.VERTICAL);
                    lbl = new TextView(getContext()); lbl.setGravity(android.view.Gravity.CENTER); lbl.setTypeface(Typeface.SERIF);
                    body = new TextView(getContext()); body.setTypeface(Typeface.SERIF); body.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL);
                    body.setJustificationMode(0);
                    row.addView(lbl, new LinearLayout.LayoutParams(-1, -2));
                    row.addView(body, new LinearLayout.LayoutParams(-1, -2));
                    v = row;
                }
                row = (LinearLayout) v;
                lbl = (TextView) row.getChildAt(0); body = (TextView) row.getChildAt(1);
                int pad = dp(20);
                row.setPadding(pad, dp(6), pad, dp(10));
                lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11, sizeSp * 0.55f));
                lbl.setTextColor((ink & 0x00FFFFFF) | 0x88000000);
                lbl.setText("— " + (p + 1) + " —");
                lbl.setPadding(0, dp(14), 0, dp(6));
                body.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
                body.setLineSpacing(0, 1.38f);
                body.setTextColor(ink);
                PageText pt = page(p);
                applyMarks(pt, p);
                body.setText(pt.sb);
                return v;
            }
        };
        setAdapter(adapter);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float acc = 1;
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { acc = 1; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                acc *= d.getScaleFactor();
                if (acc > 1.02f || acc < 0.98f) { if (scaleBy(acc)) host.onPinchOut(); acc = 1; }
                return true;
            }
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

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if (sgd.isInProgress()) return true;
        return super.onTouchEvent(e);
    }

    /** Called by whoever owns the pinch (also the page view hands its running gesture over). True = smaller than the smallest size: leave. */
    boolean scaleBy(float f) {
        float ns = sizeSp * f;
        if (ns < MIN_SP) return f < 1;
        setSize(Math.min(MAX_SP, ns));
        return false;
    }

    float size() { return sizeSp; }

    void setSize(float sp) {
        if (Math.abs(sp - sizeSp) < 0.15f) return;
        sizeSp = sp;
        int pos = getFirstVisiblePosition();
        View c0 = getChildAt(0);
        int top = c0 == null ? 0 : c0.getTop();
        adapter.notifyDataSetChanged();
        setSelectionFromTop(pos, top);
    }

    void setColors(int ink, int paper, int hl) {
        this.ink = ink; this.paper = paper; this.hlColor = hl;
        setBackgroundColor(paper);
        adapter.notifyDataSetChanged();
    }

    int pageAtTop() {
        int p = pointToPosition(getWidth() / 2, getHeight() / 3);
        return p == INVALID_POSITION ? Math.max(0, getFirstVisiblePosition()) : p;
    }

    void showPage(int p) { setSelectionFromTop(Math.max(0, Math.min(bi.pages - 1, p)), dp(64)); }

    // ---------- text of a page ----------
    private PageText page(int p) {
        PageText pt = cache.get(p);
        if (pt != null) return pt;
        pt = new PageText();
        int a = bi.pageStart[p], b = bi.pageStart[p + 1];
        pt.ws = new int[Math.max(0, b - a)]; pt.we = new int[Math.max(0, b - a)];
        float minX = 1, maxX = 0;
        for (int j = a; j < b; j++) if (!disp[j].isEmpty()) { minX = Math.min(minX, bi.box[4 * j]); maxX = Math.max(maxX, bi.box[4 * j + 2]); }
        StringBuilder sb = new StringBuilder();
        float prevY0 = -1, prevY1 = 0, prevH = 0.01f, prevX1 = 0;
        boolean prevEnd = true; String prevTok = "";
        for (int j = a; j < b; j++) {
            String t = disp[j];
            if (t.isEmpty()) {   // the second piece of one printed token shares its range
                int k = j - a;
                pt.ws[k] = k > 0 ? pt.ws[k - 1] : sb.length(); pt.we[k] = k > 0 ? pt.we[k - 1] : sb.length();
                continue;
            }
            float x0 = bi.box[4 * j], y0 = bi.box[4 * j + 1], y1 = bi.box[4 * j + 3];
            float h = Math.max(0.004f, y1 - y0);
            boolean newLine = prevY0 >= 0 && y0 > prevY0 + 0.5f * prevH;
            if (newLine) {
                boolean shortLine = prevX1 < maxX - 0.12f;
                boolean para = shortLine || (prevEnd && (x0 > minX + 0.02f || y0 - prevY1 > 0.45f * h));
                if (para) sb.append("\n\n");
                else if (prevTok.endsWith("-") && !prevTok.endsWith("--") && Character.isLowerCase(t.charAt(0))) { /* a word broken at the line end: join */ }
                else sb.append(' ');
            } else if (prevY0 >= 0) sb.append(' ');
            int k = j - a;
            pt.ws[k] = sb.length(); sb.append(t); pt.we[k] = sb.length();
            if (!newLine) { prevY0 = prevY0 < 0 ? y0 : prevY0; } else prevY0 = y0;
            prevY1 = y1; prevH = h; prevX1 = bi.box[4 * j + 2];
            prevEnd = (bi.flag[j] & 1) != 0; prevTok = t;
        }
        pt.sb = new SpannableStringBuilder(sb);
        cache.put(p, pt);
        return pt;
    }

    // ---------- the spoken sentence ----------
    void setHighlight(int from, int to, int style) {
        if (from == hlFrom && to == hlTo && style == hlStyle) return;
        int of = hlFrom, ot = hlTo;
        hlFrom = from; hlTo = to; hlStyle = style;
        refreshRange(of, ot);
        refreshRange(from, to);
    }

    private void refreshRange(int from, int to) {
        if (from < 0) return;
        int p0 = bi.pageOf(from), p1 = bi.pageOf(Math.max(from, to));
        int first = getFirstVisiblePosition();
        for (int p = p0; p <= p1; p++) {
            View v = getChildAt(p - first);
            if (v == null || p < first || p > getLastVisiblePosition()) continue;
            PageText pt = page(p);
            applyMarks(pt, p);
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(pt.sb);
        }
    }

    private void applyMarks(PageText pt, int p) {
        for (Object o : pt.marks) pt.sb.removeSpan(o);
        pt.marks.clear();
        if (hlFrom < 0) return;
        int a = bi.pageStart[p], b = bi.pageStart[p + 1];
        int lo = Math.max(hlFrom, a), hi = Math.min(hlTo, b - 1);
        if (lo > hi || hi - a >= pt.we.length) return;
        int s = pt.ws[lo - a], e = pt.we[hi - a];
        if (e <= s) return;
        Object span = hlStyle == 1 ? new UnderlineSpan() : new BackgroundColorSpan(hlColor);
        pt.sb.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        pt.marks.add(span);
    }

    /** Keeps the word being read on screen (not at the very top or bottom). */
    void follow(int word) {
        if (word < 0 || getHeight() == 0) return;
        int p = bi.pageOf(word);
        int first = getFirstVisiblePosition(), last = getLastVisiblePosition();
        if (p < first || p > last) { showPage(p); return; }
        View row = getChildAt(p - first);
        if (row == null) return;
        TextView body = (TextView) ((LinearLayout) row).getChildAt(1);
        Layout lay = body.getLayout();
        PageText pt = page(p);
        int k = word - bi.pageStart[p];
        if (lay == null || k < 0 || k >= pt.ws.length) return;
        int line = lay.getLineForOffset(Math.min(pt.ws[k], Math.max(0, body.getText().length() - 1)));
        int y = row.getTop() + body.getTop() + lay.getLineTop(line);
        int h = getHeight();
        if (y < h * 0.15f || y > h * 0.62f) smoothScrollBy((int) (y - h * 0.3f), 400);
    }
}
