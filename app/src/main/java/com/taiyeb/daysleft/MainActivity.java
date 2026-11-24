package com.taiyeb.daysleft;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

public class MainActivity extends Activity {
    static final int BG = 0xFF0E1013, CARD = 0xFF181B21, CARD2 = 0xFF222731, TXT = 0xFFF2F4F8, SUB = 0xFF9AA3B2, OK = 0xFF4DD0A1, DARK = 0xFF10131A;
    static final int[] ACC = CountdownService.ACC;
    static final String[] EMOJI = {"🎯", "📝", "🎂", "💞", "✈️", "💍", "⏳", "🏠", "💼", "🎓", "🏆", "🌙"};

    FrameLayout root;
    LinearLayout listBox;
    TextView countLabel;
    final Handler h = new Handler(Looper.getMainLooper());
    int screen = 0, tourPage = 0;
    View tour;

    final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (screen == 0 && listBox != null) renderList();
            h.postDelayed(this, 30000);
        }
    };

    // ====================== tiny drawing views ======================
    static class Ico extends View {
        final int type, color;
        Ico(Context c, int type, int color) { super(c); this.type = type; this.color = color; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), hh = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * 0.085f);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch (type) {
                case 0: // tune / settings
                    Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
                    f.setColor(CARD);
                    float[] kx = {0.66f, 0.34f, 0.58f};
                    for (int i = 0; i < 3; i++) {
                        float y = hh * (0.27f + 0.23f * i);
                        cv.drawLine(w * 0.2f, y, w * 0.8f, y, p);
                        cv.drawCircle(w * kx[i], y, w * 0.1f, f);
                        cv.drawCircle(w * kx[i], y, w * 0.1f, p);
                    }
                    break;
                case 1: // back
                    path.moveTo(w * 0.62f, hh * 0.24f); path.lineTo(w * 0.36f, hh * 0.5f); path.lineTo(w * 0.62f, hh * 0.76f);
                    cv.drawPath(path, p);
                    break;
                case 2: // plus
                    cv.drawLine(w * 0.5f, hh * 0.25f, w * 0.5f, hh * 0.75f, p);
                    cv.drawLine(w * 0.25f, hh * 0.5f, w * 0.75f, hh * 0.5f, p);
                    break;
                default: // check
                    path.moveTo(w * 0.24f, hh * 0.52f); path.lineTo(w * 0.43f, hh * 0.70f); path.lineTo(w * 0.77f, hh * 0.32f);
                    cv.drawPath(path, p);
            }
        }
    }

    static class Bar extends View {
        float frac; int color;
        Bar(Context c) { super(c); }
        void set(float f, int col) { frac = f; color = col; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), hh = getHeight(), r = hh / 2f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF2A2F39);
            cv.drawRoundRect(0, 0, w, hh, r, r, p);
            if (frac > 0) { p.setColor(color); cv.drawRoundRect(0, 0, Math.max(hh, w * frac), hh, r, r, p); }
        }
    }

    // ====================== helpers ======================
    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    GradientDrawable rr(int col, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        g.setCornerRadius(dp(r));
        return g;
    }

    int alpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    TextView tv(String s, float sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(col);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout.LayoutParams lp(int w, int hh, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hh);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    LinearLayout.LayoutParams wt(float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = lp(0, -2, l, t, r, b);
        p.weight = 1f;
        return p;
    }

    TextView button(String s, int bg, int fg) {
        TextView t = tv(s, 16, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(15), dp(16), dp(15));
        t.setBackground(rr(bg, 18));
        return t;
    }

    TextView label(String s) {
        TextView t = tv(s.toUpperCase(Locale.ENGLISH), 12, SUB, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    View ico(int type, int color, int box) {
        Ico v = new Ico(this, type, color);
        v.setLayoutParams(new ViewGroup.LayoutParams(dp(box), dp(box)));
        return v;
    }

    FrameLayout iconButton(int type, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(rr(CARD, 23));
        f.addView(ico(type, TXT, 28), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        f.setOnClickListener(l);
        return f;
    }

    void tint(Switch s) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{ACC[0], 0xFF8A93A3}));
        s.setTrackTintList(new ColorStateList(st, new int[]{alpha(ACC[0], 0x66), 0xFF2A2F39}));
    }

    LinearLayout row(String title, String sub, View right, View.OnClickListener click) {
        LinearLayout r = hrow();
        r.setBackground(rr(CARD, 18));
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout t = col();
        t.addView(tv(title, 16, TXT, false));
        if (sub != null) t.addView(tv(sub, 13, SUB, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) r.addView(right);
        if (click != null) r.setOnClickListener(click);
        return r;
    }

    LinearLayout switchRow(String title, String sub, boolean on, java.util.function.Consumer<Boolean> cb) {
        Switch sw = new Switch(this);
        tint(sw);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> cb.accept(v));
        return row(title, sub, sw, v -> sw.toggle());
    }

    LinearLayout stepper(String title, String sub, int val, int min, int max, int step, String unit, IntConsumer cb) {
        int[] v = {val};
        TextView value = tv(val + unit, 16, TXT, true);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(58));
        TextView minus = tv("−", 20, TXT, true), plus = tv("+", 20, TXT, true);
        for (TextView b : new TextView[]{minus, plus}) {
            b.setGravity(Gravity.CENTER);
            b.setBackground(rr(CARD2, 19));
        }
        Runnable done = () -> cb.accept(v[0]);
        holdRepeat(minus, () -> { v[0] = Math.max(min, v[0] - step); value.setText(v[0] + unit); }, done);
        holdRepeat(plus, () -> { v[0] = Math.min(max, v[0] + step); value.setText(v[0] + unit); }, done);
        LinearLayout ctl = hrow();
        ctl.addView(minus, new LinearLayout.LayoutParams(dp(38), dp(38)));
        ctl.addView(value);
        ctl.addView(plus, new LinearLayout.LayoutParams(dp(38), dp(38)));
        return row(title, sub, ctl, null);
    }

    /** Tap = one step; press and hold = keeps stepping, faster the longer you hold. Applies the value on release. */
    void holdRepeat(View b, Runnable step, Runnable done) {
        Runnable[] r = new Runnable[1];
        int[] n = {0};
        r[0] = () -> {
            step.run();
            n[0]++;
            h.postDelayed(r[0], n[0] > 14 ? 35 : n[0] > 6 ? 70 : 130);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    n[0] = 0;
                    v.setPressed(true);
                    step.run();
                    h.postDelayed(r[0], 380);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    h.removeCallbacks(r[0]);
                    done.run();
                    return true;
                default:
                    return true;
            }
        });
    }

    LinearLayout chips(String[] labels, int cur, IntConsumer cb) {
        LinearLayout l = hrow();
        int[] sel = {cur};
        TextView[] ts = new TextView[labels.length];
        Runnable paint = () -> {
            for (int i = 0; i < ts.length; i++) {
                boolean on = i == sel[0];
                ts[i].setBackground(rr(on ? alpha(ACC[0], 0x33) : CARD2, 14));
                ts[i].setTextColor(on ? ACC[0] : SUB);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            ts[i] = tv(labels[i], 14, SUB, true);
            ts[i].setGravity(Gravity.CENTER);
            ts[i].setPadding(dp(6), dp(11), dp(6), dp(11));
            ts[i].setOnClickListener(v -> { sel[0] = idx; paint.run(); cb.accept(idx); });
            l.addView(ts[i], wt(i == 0 ? 0 : 4, 0, i == labels.length - 1 ? 0 : 4, 0));
        }
        paint.run();
        return l;
    }

    String fmt(LocalDate d) { return CountdownService.fmt(d); }

    void sync() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        try { CountdownService.sync(this); } catch (Exception ignored) {}
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { sync(); }

    // ====================== sheets ======================
    Dialog sheet(String title, View body) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout w = col();
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        float r = dp(28);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        w.setBackground(g);
        w.setPadding(dp(20), dp(10), dp(20), dp(24));
        View handle = new View(this);
        handle.setBackground(rr(0xFF3A404C, 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        w.addView(handle, hp);
        if (title != null) w.addView(tv(title, 20, TXT, true), lp(-1, -2, 0, 16, 0, 12));
        w.addView(body);
        d.setContentView(w);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(0));
        win.setLayout(-1, -2);
        win.setGravity(Gravity.BOTTOM);
        win.setDimAmount(0.65f);
        win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        return d;
    }

    void confirm(String title, String msg, String yes, Runnable ok) {
        LinearLayout b = col();
        b.addView(tv(msg, 15, SUB, false), lp(-1, -2, 0, 0, 0, 18));
        Dialog[] d = new Dialog[1];
        LinearLayout btns = hrow();
        TextView no = button("Cancel", CARD2, TXT);
        TextView y = button(yes, alpha(0xFFFF5C5C, 0x33), 0xFFFF7A7A);
        no.setOnClickListener(v -> d[0].dismiss());
        y.setOnClickListener(v -> { d[0].dismiss(); ok.run(); });
        btns.addView(no, wt(0, 0, 6, 0));
        btns.addView(y, wt(6, 0, 0, 0));
        b.addView(btns);
        d[0] = sheet(title, b);
        d[0].show();
    }

    void pickDate(long epoch, String title, LongConsumer cb) {
        LocalDate[] sel = {LocalDate.ofEpochDay(epoch)};
        YearMonth[] vm = {YearMonth.from(sel[0])};
        LinearLayout body = col();
        TextView big = tv("", 26, ACC[0], true);
        body.addView(big, lp(-1, -2, 0, 0, 0, 6));

        LinearLayout nav = hrow();
        TextView yp = tv("«", 22, TXT, true), mp = tv("‹", 26, TXT, true), mt = tv("", 17, TXT, true), mn = tv("›", 26, TXT, true), yn = tv("»", 22, TXT, true);
        for (TextView t : new TextView[]{yp, mp, mn, yn}) { t.setGravity(Gravity.CENTER); t.setBackground(rr(CARD2, 12)); }
        mt.setGravity(Gravity.CENTER);
        nav.addView(yp, new LinearLayout.LayoutParams(dp(42), dp(42)));
        nav.addView(mp, lp(dp(42), dp(42), 6, 0, 0, 0));
        nav.addView(mt, wt(0, 0, 0, 0));
        nav.addView(mn, lp(dp(42), dp(42), 0, 0, 6, 0));
        nav.addView(yn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        body.addView(nav, lp(-1, -2, 0, 4, 0, 8));

        LinearLayout wk = hrow();
        for (String s : new String[]{"M", "T", "W", "T", "F", "S", "S"}) {
            TextView t = tv(s, 12, SUB, true);
            t.setGravity(Gravity.CENTER);
            wk.addView(t, wt(0, 0, 0, 0));
        }
        body.addView(wk, lp(-1, -2, 0, 0, 0, 4));
        LinearLayout grid = col();
        body.addView(grid);

        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            big.setText(fmt(sel[0]));
            mt.setText(vm[0].format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)));
            grid.removeAllViews();
            int off = vm[0].atDay(1).getDayOfWeek().getValue() - 1, len = vm[0].lengthOfMonth();
            for (int r = 0; r < 6; r++) {
                LinearLayout line = hrow();
                for (int c = 0; c < 7; c++) {
                    int day = r * 7 + c - off + 1;
                    TextView t = tv(day >= 1 && day <= len ? String.valueOf(day) : "", 15, TXT, false);
                    t.setGravity(Gravity.CENTER);
                    if (day >= 1 && day <= len) {
                        LocalDate dd = vm[0].atDay(day);
                        if (dd.equals(sel[0])) { t.setBackground(rr(ACC[0], 20)); t.setTextColor(DARK); t.setTypeface(Typeface.DEFAULT_BOLD); }
                        else if (dd.equals(LocalDate.now())) {
                            GradientDrawable g = rr(0, 20);
                            g.setStroke(dp(1.5f), ACC[0]);
                            t.setBackground(g);
                        }
                        t.setOnClickListener(v -> { sel[0] = dd; draw[0].run(); });
                    }
                    line.addView(t, lp(0, dp(42), 0, 0, 0, 0));
                    ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
                }
                grid.addView(line);
            }
        };
        yp.setOnClickListener(v -> { vm[0] = vm[0].minusYears(1); draw[0].run(); });
        mp.setOnClickListener(v -> { vm[0] = vm[0].minusMonths(1); draw[0].run(); });
        mn.setOnClickListener(v -> { vm[0] = vm[0].plusMonths(1); draw[0].run(); });
        yn.setOnClickListener(v -> { vm[0] = vm[0].plusYears(1); draw[0].run(); });

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout qc = hrow();
        String[] ql = {"Today", "+1 week", "+1 month", "+100 days", "+1 year"};
        for (int i = 0; i < ql.length; i++) {
            final int idx = i;
            TextView t = tv(ql[i], 13, ACC[0], true);
            t.setPadding(dp(14), dp(9), dp(14), dp(9));
            t.setBackground(rr(alpha(ACC[0], 0x22), 14));
            t.setOnClickListener(v -> {
                LocalDate n = LocalDate.now();
                sel[0] = idx == 0 ? n : idx == 1 ? n.plusWeeks(1) : idx == 2 ? n.plusMonths(1) : idx == 3 ? n.plusDays(100) : n.plusYears(1);
                vm[0] = YearMonth.from(sel[0]);
                draw[0].run();
            });
            qc.addView(t, lp(-2, -2, 0, 0, 8, 0));
        }
        hs.addView(qc);
        body.addView(hs, lp(-1, -2, 0, 10, 0, 14));

        Dialog[] d = new Dialog[1];
        LinearLayout btns = hrow();
        TextView no = button("Cancel", CARD2, TXT), yes = button("Set date", ACC[0], DARK);
        no.setOnClickListener(v -> d[0].dismiss());
        yes.setOnClickListener(v -> { d[0].dismiss(); cb.accept(sel[0].toEpochDay()); });
        btns.addView(no, wt(0, 0, 6, 0));
        btns.addView(yes, wt(6, 0, 0, 0));
        body.addView(btns);
        draw[0].run();
        d[0] = sheet(title, body);
        d[0].show();
    }

    // ====================== lifecycle ======================
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(0, ins.getSystemWindowInsetTop(), 0, ins.getSystemWindowInsetBottom());
            return ins;
        });
        setContentView(root);
        learn = new LearnUi(this);
        showHome();
        if (!Store.flag(this, "toured", false)) showTour();
        NudgeReceiver.schedule(this);
        showLastCrash();
        if (s == null && learn.handleShare(getIntent())) setIntent(new Intent());
    }

    void showLastCrash() {
        String cr = Store.sp(this).getString("crash", null);
        if (cr == null) return;
        Store.sp(this).edit().remove("crash").apply();
        LinearLayout b = col();
        b.addView(tv("The app closed unexpectedly last time. Copy this and send it to me so I can fix it.", 14, SUB, false), lp(-1, -2, 0, 0, 0, 10));
        TextView t = tv(cr, 11, TXT, false);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        b.addView(sv, new LinearLayout.LayoutParams(-1, dp(260)));
        Dialog[] d = new Dialog[1];
        LinearLayout btns = hrow();
        TextView close = button("Close", CARD2, TXT), copy = button("Copy", ACC[0], DARK);
        close.setOnClickListener(v -> d[0].dismiss());
        copy.setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("crash", cr));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        btns.addView(close, wt(0, 0, 6, 0));
        btns.addView(copy, wt(6, 0, 0, 0));
        b.addView(btns, lp(-1, -2, 0, 14, 0, 0));
        d[0] = sheet("Crash report", b);
        d[0].show();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (learn.handleShare(i)) setIntent(new Intent());
    }

    @Override protected void onResume() {
        super.onResume();
        h.postDelayed(refresher, 30000);
        for (Cd d : Store.load(this)) if (d.active) { sync(); break; }
        if (screen == 0 && listBox != null) renderList();
        if (screen == 2) showSettings();
        if (screen == 3) learn.showPlan(learn.curPlan);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresher);
    }

    @Override public void onBackPressed() {
        if (tour != null) return;
        if (screen == 5) showSettings(); else if (screen != 0) showHome(); else super.onBackPressed();
    }

    View topBar(String title) {
        LinearLayout bar = hrow();
        bar.setPadding(dp(14), dp(10), dp(20), dp(6));
        FrameLayout back = new FrameLayout(this);
        back.addView(ico(1, TXT, 30), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        back.setOnClickListener(v -> showHome());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(tv(title, 22, TXT, true), lp(-2, -2, 4, 0, 0, 0));
        return bar;
    }

    // ====================== home ======================
    View fabView;
    TextView fabText;
    LinearLayout tabBar;

    int tab() { return Store.num(this, "tab", 0); }
    java.util.function.BiConsumer<String, String> pickCb;

    void pickPdf(java.util.function.BiConsumer<String, String> cb) { pickFile("application/pdf", cb); }

    void pickAudio(java.util.function.BiConsumer<String, String> cb) { pickFile("audio/*", cb); }

    void pickFile(String mime, java.util.function.BiConsumer<String, String> cb) {
        pickCb = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, 77); } catch (Exception e) { Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc != 77 || res != RESULT_OK || d == null || d.getData() == null || pickCb == null) return;
        Uri u = d.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        String name = "book.pdf";
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (ix >= 0) name = c.getString(ix); }
        } catch (Exception ignored) {}
        pickCb.accept(u.toString(), name);
    }
    LearnUi learn;

    void showHome() {
        screen = 0;
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = col();
        c.setPadding(dp(20), dp(24), dp(20), dp(110));

        LinearLayout head = hrow();
        LinearLayout titles = col();
        titles.addView(tv("Days Left", 32, TXT, true));
        countLabel = tv("", 14, SUB, false);
        titles.addView(countLabel);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(iconButton(0, v -> showSettings()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        c.addView(head, lp(-1, -2, 0, 0, 0, 16));

        tabBar = hrow();
        tabBar.setBackground(rr(CARD, 18));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        c.addView(tabBar, lp(-1, -2, 0, 0, 0, 18));

        listBox = col();
        c.addView(listBox, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(c);
        root.addView(sv);

        LinearLayout fab = hrow();
        fab.setBackground(rr(ACC[0], 30));
        fab.setPadding(dp(18), dp(15), dp(22), dp(15));
        fab.setElevation(dp(8));
        fab.addView(ico(2, DARK, 24));
        fabText = tv("New countdown", 16, DARK, true);
        fab.addView(fabText, lp(-2, -2, 6, 0, 0, 0));
        fab.setOnClickListener(v -> { if (tab() == 0) showTemplates(); else learn.addSheet(); });
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(20), dp(24));
        root.addView(fab, fp);
        fabView = fab;
        renderList();
        if (tour != null) { root.removeView(tour); root.addView(tour); }
    }

    void drawTabs() {
        tabBar.removeAllViews();
        String[] names = {"⏳  Countdowns", "📚  Library"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            boolean on = tab() == i;
            TextView t = tv(names[i], 15, on ? DARK : SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(11), dp(8), dp(11));
            t.setBackground(rr(on ? ACC[0] : 0, 14));
            t.setOnClickListener(v -> { Store.setNum(this, "tab", idx); renderList(); });
            tabBar.addView(t, wt(0, 0, 0, 0));
        }
    }

    void renderList() {
        listBox.removeAllViews();
        drawTabs();
        List<Cd> l = Store.load(this);
        LocalDateTime now = LocalDateTime.now();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH));
        if (tab() == 1) {
            int todo = Learn.pending(this);
            countLabel.setText(date + (todo == 0 ? "" : "  ·  " + todo + " to go"));
            if (fabText != null) fabText.setText("Add");
            if (fabView != null) fabView.setVisibility(View.VISIBLE);
            learn.library(listBox);
            return;
        }
        countLabel.setText(date + (l.isEmpty() ? "" : "  ·  " + l.size() + (l.size() == 1 ? " countdown" : " countdowns")));
        if (fabText != null) fabText.setText("New countdown");
        if (fabView != null) fabView.setVisibility(l.isEmpty() ? View.GONE : View.VISIBLE);
        if (l.isEmpty()) {
            LinearLayout e = col();
            e.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView em = tv("⏳", 40, TXT, false);
            em.setGravity(Gravity.CENTER);
            em.setBackground(rr(alpha(ACC[0], 0x22), 60));
            e.addView(em, new LinearLayout.LayoutParams(dp(84), dp(84)));
            TextView t = tv("Nothing counting down yet", 22, TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(18), 0, dp(6));
            e.addView(t, new LinearLayout.LayoutParams(-1, -2));
            TextView s = tv("Pick a template to start. You can change everything after.", 15, SUB, false);
            s.setGravity(Gravity.CENTER);
            s.setPadding(dp(16), 0, dp(16), 0);
            e.addView(s, new LinearLayout.LayoutParams(-1, -2));
            String[][] tp = {{"📝", "Exam"}, {"🎂", "Birthday"}, {"💞", "Until we meet"}, {"💍", "Anniversary"},
                    {"✈️", "Trip"}, {"🎯", "Goal"}, {"⏳", "Life clock"}, {"✏️", "Custom"}};
            LinearLayout grid = col();
            for (int i = 0; i < tp.length; i += 2) {
                LinearLayout gr = hrow();
                for (int j = i; j < i + 2; j++) {
                    String[] x = tp[j];
                    LinearLayout tile = hrow();
                    tile.setGravity(Gravity.CENTER_VERTICAL);
                    tile.setBackground(rr(CARD, 18));
                    tile.setPadding(dp(12), dp(12), dp(12), dp(12));
                    TextView te = tv(x[0], 22, TXT, false);
                    te.setGravity(Gravity.CENTER);
                    te.setBackground(rr(CARD2, 14));
                    tile.addView(te, new LinearLayout.LayoutParams(dp(42), dp(42)));
                    TextView tn = tv(x[1], 15, TXT, true);
                    tn.setSingleLine(true);
                    tn.setEllipsize(TextUtils.TruncateAt.END);
                    tile.addView(tn, wt(10, 0, 0, 0));
                    tile.setOnClickListener(v -> startTemplate(x[1], x[0]));
                    gr.addView(tile, wt(j == i ? 0 : 5, 0, j == i ? 5 : 0, 0));
                }
                grid.addView(gr, lp(-1, -2, 0, 0, 0, 10));
            }
            e.addView(grid, lp(-1, -2, 0, 28, 0, 0));
            listBox.addView(e, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        l.sort((a, b) -> {
            Calc x = Calc.of(a, now), y = Calc.of(b, now);
            if (x.passed != y.passed) return x.passed ? 1 : -1;
            return Long.compare(x.rest.toMinutes(), y.rest.toMinutes());
        });
        for (Cd d : l) listBox.addView(card(d), lp(-1, -2, 0, 0, 0, 14));
    }

    void part(SpannableStringBuilder b, String num, float scale, int numCol, String unit) {
        int s = b.length();
        b.append(num);
        b.setSpan(new RelativeSizeSpan(scale), s, b.length(), 0);
        b.setSpan(new StyleSpan(Typeface.BOLD), s, b.length(), 0);
        b.setSpan(new ForegroundColorSpan(numCol), s, b.length(), 0);
        if (!unit.isEmpty()) {
            int u = b.length();
            b.append(" " + unit + "  ");
            b.setSpan(new ForegroundColorSpan(SUB), u, b.length(), 0);
        }
    }

    View card(Cd c) {
        Calc k = Calc.of(c, LocalDateTime.now());
        int acc = k.done() ? OK : ACC[c.color % ACC.length];
        LinearLayout card = col();
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{alpha(acc, 0x38), CARD, CARD});
        bg.setCornerRadius(dp(26));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout top = hrow();
        TextView em = tv(c.emoji, 22, TXT, false);
        em.setGravity(Gravity.CENTER);
        em.setBackground(rr(alpha(acc, 0x33), 24));
        top.addView(em, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout names = col();
        TextView nm = tv(c.label, 17, TXT, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nm);
        names.addView(tv((c.yearly ? "Every year · " : "") + fmt(k.target), 13, SUB, false));
        top.addView(names, wt(12, 0, 8, 0));
        LinearLayout sc = col();
        sc.setGravity(Gravity.CENTER_HORIZONTAL);
        Switch sw = new Switch(this);
        tint(sw);
        sw.setChecked(c.active);
        sw.setOnCheckedChangeListener((b, v) -> {
            List<Cd> all = Store.load(this);
            for (Cd d : all) if (d.id == c.id) d.active = v;
            Store.save(this, all);
            sync();
        });
        sc.addView(sw);
        sc.addView(tv("Status bar", 10, SUB, false));
        top.addView(sc);
        card.addView(top);

        SpannableStringBuilder b = new SpannableStringBuilder();
        if (k.today) part(b, "🎉 Today!", 1.9f, acc, "");
        else if (k.done()) part(b, "✓ Completed", 1.9f, OK, "");
        else if (k.countUp()) {
            part(b, "+" + k.sinceDays, 2.6f, acc, k.sinceDays == 1 ? "day" : "days");
            part(b, String.valueOf(k.sinceHours), 1.7f, TXT, "hr");
            part(b, "since", 1.0f, SUB, "");
        } else if (k.days > 0) {
            part(b, String.valueOf(k.days), 2.6f, acc, k.days == 1 ? "day" : "days");
            part(b, String.valueOf(k.hours), 1.7f, TXT, "hr");
            part(b, String.valueOf(k.mins), 1.7f, TXT, "min");
        } else {
            part(b, String.valueOf(k.hours), 2.6f, acc, "hr");
            part(b, String.valueOf(k.mins), 2.0f, TXT, "min");
        }
        TextView big = tv("", 15, TXT, false);
        big.setText(b);
        big.setPadding(0, dp(16), 0, dp(12));
        card.addView(big);

        Bar bar = new Bar(this);
        bar.set((float) k.frac, acc);
        card.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));
        String foot;
        if (k.today) foot = "It's here. Make it count!";
        else if (k.done()) foot = "Finished " + k.agoText();
        else if (k.countUp()) foot = "Counting up since " + fmt(k.target);
        else foot = k.pctText() + " done · since " + (c.yearly ? "last time" : fmt(k.startDate));
        TextView ft = tv(foot, 12, SUB, false);
        ft.setPadding(0, dp(9), 0, 0);
        card.addView(ft);
        card.setOnClickListener(v -> showEditor(c, false));
        return card;
    }

    // ====================== templates ======================
    void showTemplates() {
        String[][] t = {
                {"📝", "Exam", "Days until your exam"},
                {"🎂", "Birthday", "His / her birthday, repeats every year"},
                {"💞", "Until we meet", "Counting down to seeing someone"},
                {"💍", "Anniversary", "Repeats every year"},
                {"✈️", "Trip", "Days until you travel"},
                {"🎯", "Goal", "A target you're working toward"},
                {"⏳", "Life clock", "Approximate time left in a lifetime"},
                {"✏️", "Custom", "Start from scratch"}};
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = col();
        sv.addView(box);
        Dialog[] d = new Dialog[1];
        for (String[] x : t) {
            LinearLayout r = hrow();
            r.setPadding(dp(6), dp(10), dp(6), dp(10));
            TextView em = tv(x[0], 24, TXT, false);
            em.setGravity(Gravity.CENTER);
            em.setBackground(rr(CARD2, 16));
            r.addView(em, new LinearLayout.LayoutParams(dp(50), dp(50)));
            LinearLayout n = col();
            n.addView(tv(x[1], 16, TXT, true));
            n.addView(tv(x[2], 13, SUB, false));
            r.addView(n, lp(0, -2, 14, 0, 0, 0));
            ((LinearLayout.LayoutParams) n.getLayoutParams()).weight = 1f;
            r.setOnClickListener(v -> { d[0].dismiss(); startTemplate(x[1], x[0]); });
            box.addView(r);
        }
        LinearLayout wrap = col();
        wrap.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(470), (int) (getResources().getDisplayMetrics().heightPixels * 0.62f))));
        d[0] = sheet("New countdown", wrap);
        d[0].show();
    }

    Cd preset(String label, String emoji, boolean yearly, int daysAhead) {
        Cd c = new Cd();
        c.id = Store.nextId(Store.load(this));
        c.label = label;
        c.emoji = emoji;
        c.yearly = yearly;
        c.start = LocalDate.now().toEpochDay();
        c.target = LocalDate.now().plusDays(daysAhead).toEpochDay();
        c.color = c.id % ACC.length;
        c.years = 75;
        return c;
    }

    void startTemplate(String name, String emoji) {
        switch (name) {
            case "Exam": showEditor(preset("Exam", emoji, false, 30), true); break;
            case "Birthday": showEditor(preset("Birthday", emoji, true, 30), true); break;
            case "Until we meet": showEditor(preset("Until we meet", emoji, false, 30), true); break;
            case "Anniversary": showEditor(preset("Anniversary", emoji, true, 30), true); break;
            case "Trip": showEditor(preset("Trip", emoji, false, 30), true); break;
            case "Goal": showEditor(preset("Goal", emoji, false, 90), true); break;
            case "Life clock": {
                Cd c = preset("Life left (approx.)", "⏳", false, 0);
                c.kind = 1;
                c.start = LocalDate.now().minusYears(30).toEpochDay();
                c.target = LocalDate.ofEpochDay(c.start).plusYears(c.years).toEpochDay();
                showEditor(c, true);
                break;
            }
            default: showEditor(preset("", "⏳", false, 30), true);
        }
    }

    // ====================== editor ======================
    void showEditor(Cd cd, boolean isNew) {
        screen = 1;
        root.removeAllViews();
        LinearLayout page = col();
        page.addView(topBar(isNew ? "New countdown" : "Edit countdown"));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = col();
        c.setPadding(dp(20), dp(8), dp(20), dp(24));
        boolean life = cd.kind == 1;

        LinearLayout nameRow = hrow();
        EditText em = new EditText(this);
        em.setText(cd.emoji);
        em.setTextSize(26);
        em.setGravity(Gravity.CENTER);
        em.setTextColor(TXT);
        em.setBackground(rr(CARD, 18));
        em.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        nameRow.addView(em, lp(dp(64), dp(58), 0, 0, 10, 0));
        EditText name = new EditText(this);
        name.setHint("Label (e.g. Exam)");
        name.setText(cd.label);
        name.setTextColor(TXT);
        name.setHintTextColor(SUB);
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setBackground(rr(CARD, 18));
        name.setPadding(dp(16), 0, dp(16), 0);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, dp(58), 1f));
        c.addView(nameRow);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = hrow();
        for (String e : EMOJI) {
            TextView t = tv(e, 22, TXT, false);
            t.setGravity(Gravity.CENTER);
            t.setBackground(rr(CARD, 14));
            t.setOnClickListener(v -> em.setText(e));
            chipRow.addView(t, lp(dp(46), dp(46), 0, 0, 8, 0));
        }
        hs.addView(chipRow);
        c.addView(hs, lp(-1, -2, 0, 10, 0, 6));

        TextView prev = tv("", 20, ACC[cd.color % ACC.length], true);
        prev.setGravity(Gravity.CENTER);
        prev.setPadding(dp(12), dp(18), dp(12), dp(18));
        TextView prevSub = tv("", 13, SUB, false);
        prevSub.setGravity(Gravity.CENTER);
        TextView tVal = tv("", 15, ACC[0], true), sVal = tv("", 15, ACC[0], true);
        Runnable upd = () -> {
            if (life) cd.target = LocalDate.ofEpochDay(cd.start).plusYears(cd.years).toEpochDay();
            tVal.setText(fmt(LocalDate.ofEpochDay(cd.target)));
            sVal.setText(fmt(LocalDate.ofEpochDay(cd.start)));
            int a = ACC[cd.color % ACC.length];
            prev.setTextColor(a);
            prev.setBackground(rr(alpha(a, 0x22), 20));
            Calc k = Calc.of(cd, LocalDateTime.now());
            prev.setText(k.done() ? "✓ Completed" : k.full());
            prevSub.setText(k.done() ? "That date was " + k.agoText() : k.pctText() + " done · ends " + fmt(k.target));
        };

        c.addView(label(life ? "Your life" : "When"));
        if (life) {
            c.addView(row("Date of birth", "Where the progress bar starts", sVal, v -> pickDate(cd.start, "Date of birth", x -> { cd.start = x; upd.run(); })), lp(-1, -2, 0, 0, 0, 10));
            c.addView(stepper("Expected lifespan", "A rough guess is fine", cd.years, 1, 120, 1, " yrs", x -> { cd.years = x; upd.run(); }), lp(-1, -2, 0, 0, 0, 10));
        } else {
            c.addView(row("Target date", "The day you're counting to", tVal, v -> pickDate(cd.target, "Target date", x -> { cd.target = x; upd.run(); })), lp(-1, -2, 0, 0, 0, 10));
            LinearLayout startRow = row("Progress starts", "Where the progress bar begins", sVal, v -> pickDate(cd.start, "Progress starts", x -> { cd.start = x; upd.run(); }));
            c.addView(startRow, lp(-1, -2, 0, 0, 0, 10));
            LinearLayout endBox = col();
            endBox.setBackground(rr(CARD, 18));
            endBox.setPadding(dp(16), dp(14), dp(16), dp(14));
            endBox.addView(tv("When it ends", 16, TXT, false));
            String[] ed = {"Shows “✓ Completed” until you remove it", "Keeps going: “3 days since”", "Removes itself from the status bar"};
            TextView edesc = tv(ed[cd.end], 13, SUB, false);
            edesc.setPadding(0, dp(2), 0, dp(10));
            endBox.addView(edesc);
            endBox.addView(chips(new String[]{"Mark done", "Count up", "Remove"}, cd.end, i -> { cd.end = i; edesc.setText(ed[i]); upd.run(); }));
            c.addView(switchRow("Repeat every year", "Birthdays, anniversaries", cd.yearly, v -> {
                cd.yearly = v;
                startRow.setVisibility(v ? View.GONE : View.VISIBLE);
                endBox.setVisibility(v ? View.GONE : View.VISIBLE);
                upd.run();
            }), lp(-1, -2, 0, 0, 0, 10));
            startRow.setVisibility(cd.yearly ? View.GONE : View.VISIBLE);
            endBox.setVisibility(cd.yearly ? View.GONE : View.VISIBLE);
            c.addView(endBox, lp(-1, -2, 0, 0, 0, 10));
        }

        c.addView(label("Look & feel"));
        c.addView(switchRow("Show in status bar", "Pinned notification next to the clock", cd.active, v -> cd.active = v), lp(-1, -2, 0, 0, 0, 10));
        LinearLayout dots = hrow();
        dots.setBackground(rr(CARD, 18));
        dots.setPadding(dp(16), dp(14), dp(16), dp(14));
        dots.addView(tv("Colour", 16, TXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            while (dots.getChildCount() > 1) dots.removeViewAt(1);
            for (int i = 0; i < ACC.length; i++) {
                final int idx = i;
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(ACC[i]);
                if (cd.color % ACC.length == i) g.setStroke(dp(3), Color.WHITE);
                View dv = new View(this);
                dv.setBackground(g);
                dv.setOnClickListener(v -> { cd.color = idx; redraw[0].run(); upd.run(); });
                dots.addView(dv, lp(dp(28), dp(28), 7, 0, 0, 0));
            }
        };
        redraw[0].run();
        c.addView(dots, lp(-1, -2, 0, 0, 0, 14));

        c.addView(label("Preview"));
        c.addView(prev);
        c.addView(prevSub, lp(-1, -2, 0, 8, 0, 0));
        upd.run();
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = hrow();
        bottom.setBackgroundColor(BG);
        bottom.setPadding(dp(20), dp(10), dp(20), dp(14));
        if (!isNew) {
            TextView del = button("Delete", alpha(0xFFFF5C5C, 0x22), 0xFFFF7A7A);
            del.setOnClickListener(v -> confirm("Delete this countdown?", "“" + cd.label + "” will be removed for good.", "Delete", () -> {
                List<Cd> all = Store.load(this);
                all.removeIf(x -> x.id == cd.id);
                Store.save(this, all);
                sync();
                showHome();
            }));
            bottom.addView(del, lp(-2, -2, 0, 0, 10, 0));
        }
        TextView save = button(isNew ? "Create countdown" : "Save changes", ACC[0], DARK);
        save.setOnClickListener(v -> {
            cd.label = name.getText().toString().trim();
            if (cd.label.isEmpty()) cd.label = "Countdown";
            String e = em.getText().toString().trim();
            cd.emoji = e.isEmpty() ? "⏳" : e;
            List<Cd> all = Store.load(this);
            boolean found = false;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id == cd.id) { all.set(i, cd); found = true; }
            if (!found) all.add(cd);
            Store.save(this, all);
            sync();
            showHome();
        });
        bottom.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(bottom);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    // ====================== settings ======================
    LinearLayout card() {
        LinearLayout l = col();
        l.setBackground(rr(CARD, 18));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        return l;
    }

    LinearLayout choice(String title, String sub, String[] labels, int cur, IntConsumer cb) {
        LinearLayout l = card();
        l.addView(tv(title, 16, TXT, false));
        if (sub != null) {
            TextView d = tv(sub, 13, SUB, false);
            d.setPadding(0, dp(2), 0, 0);
            l.addView(d);
        }
        l.addView(chips(labels, cur, cb), lp(-1, -2, 0, 12, 0, 0));
        return l;
    }

    void showSettings() {
        screen = 2;
        root.removeAllViews();
        LinearLayout page = col();
        page.addView(topBar("Settings"));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = col();
        c.setPadding(dp(20), dp(0), dp(20), dp(40));

        c.addView(label("Status bar"));
        LinearLayout box = col();   // everything here depends on "Show as"; rebuilt in place
        c.addView(box);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            boolean text = Store.flag(this, "ov", false) && Settings.canDrawOverlays(this);
            boolean wantText = Store.flag(this, "ov", false);
            box.addView(choice("Show as", wantText ? "Text at clock size, beside your status icons" : "A small icon in the status bar",
                    new String[]{"Small icon", "Text"}, wantText ? 1 : 0, i -> {
                        Store.setFlag(this, "ov", i == 1);
                        if (i == 1 && !Settings.canDrawOverlays(this))
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                        sync();
                        fill[0].run();
                    }), lp(-1, -2, 0, 0, 0, 10));
            if (!wantText) {
                box.addView(choice("Icon content", "Shown in the status bar icon", new String[]{"Days only", "Days + hours"}, Store.icon(this),
                        i -> { Store.sp(this).edit().putInt("icon", i).apply(); sync(); }), lp(-1, -2, 0, 0, 0, 10));
                TextView t = tv("Samsung squeezes icons to a tiny size. Choose Text for something as large as the clock.", 12, SUB, false);
                t.setPadding(dp(4), 0, dp(4), dp(6));
                box.addView(t);
                return;
            }
            if (!text) {
                LinearLayout w = card();
                w.addView(tv("Permission needed", 16, TXT, true));
                TextView d = tv("Allow “Display over other apps” so the text can sit in the status bar.", 13, SUB, false);
                d.setPadding(0, dp(2), 0, dp(12));
                w.addView(d);
                TextView g = button("Allow", ACC[0], DARK);
                g.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
                w.addView(g);
                box.addView(w, lp(-1, -2, 0, 0, 0, 10));
                return;
            }
            box.addView(stepper("Text size", "Exactly this size. If space runs out, the furthest countdown is hidden", Store.num(this, "ovSize", 14), 8, 28, 1, " sp",
                    x -> { Store.setNum(this, "ovSize", x); sync(); }), lp(-1, -2, 0, 0, 0, 10));
            box.addView(choice("Side", "Right keeps clear of Wi-Fi, SIM, alarm and silent icons automatically",
                    new String[]{"Right, by icons", "Left, by clock"}, Store.num(this, "ovPos", 1) == 1 ? 0 : 1,
                    i -> { Store.setNum(this, "ovPos", i == 0 ? 1 : 0); sync(); }), lp(-1, -2, 0, 0, 0, 10));
            box.addView(stepper("Nudge sideways", "Use it if Bluetooth or hotspot icons get covered. − left, + right",
                    Store.num(this, "ovNudge", 0), -120, 120, 2, " dp", x -> { Store.setNum(this, "ovNudge", x); sync(); }), lp(-1, -2, 0, 0, 0, 10));
            box.addView(stepper("Nudge vertically", "− up, + down", Store.num(this, "ovY", 0), -20, 20, 1, " dp",
                    x -> { Store.setNum(this, "ovY", x); sync(); }), lp(-1, -2, 0, 0, 0, 10));
            box.addView(choice("Text colour", "Each colour follows the countdown colour",
                    new String[]{"White", "Black", "Each colour"}, Store.num(this, "ovColor", 2),
                    i -> { Store.setNum(this, "ovColor", i); sync(); }), lp(-1, -2, 0, 0, 0, 10));
            box.addView(row("Reset position and size", null, null, v -> {
                Store.setNum(this, "ovNudge", 0);
                Store.setNum(this, "ovY", 0);
                Store.setNum(this, "ovSize", 14);
                Store.setNum(this, "ovPos", 1);
                sync();
                showSettings();
            }), lp(-1, -2, 0, 0, 0, 10));
        };
        fill[0].run();

        c.addView(label("Notification"));
        c.addView(switchRow("Keep pinned", "Brings it back if you swipe it away", Store.flag(this, "pin", true), v -> { Store.setFlag(this, "pin", v); sync(); }), lp(-1, -2, 0, 0, 0, 10));
        c.addView(switchRow("Progress bar", "Show progress inside the notification", Store.flag(this, "progress", true), v -> { Store.setFlag(this, "progress", v); sync(); }), lp(-1, -2, 0, 0, 0, 10));
        c.addView(switchRow("Show on lock screen", "Off hides the details when the phone is locked", Store.flag(this, "lock", true), v -> { Store.setFlag(this, "lock", v); sync(); }), lp(-1, -2, 0, 0, 0, 10));

        c.addView(label("Learning"));
        c.addView(switchRow("Daily nudge", "One “Up next” reminder a day from your plans and inbox", Store.flag(this, "nudge", true), v -> { Store.setFlag(this, "nudge", v); NudgeReceiver.schedule(this); sync(); }), lp(-1, -2, 0, 0, 0, 10));
        c.addView(stepper("Nudge time", "Hour of the day", Store.num(this, "nudgeH", 19), 0, 23, 1, ":00", x -> { Store.setNum(this, "nudgeH", x); NudgeReceiver.schedule(this); }), lp(-1, -2, 0, 0, 0, 10));
        c.addView(row("Send a test nudge", "See what it looks like now", null, v -> {
            sync();
            if (!NudgeReceiver.post(this)) Toast.makeText(this, "Add something to a plan or the inbox first", Toast.LENGTH_SHORT).show();
        }), lp(-1, -2, 0, 0, 0, 10));

        c.addView(label("System"));
        c.addView(row("Notification settings", "Allow, sound, show in status bar", null, v ->
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))), lp(-1, -2, 0, 0, 0, 10));
        c.addView(row("Battery: don't restrict", "Keeps the countdown running in the background", null, v ->
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))), lp(-1, -2, 0, 0, 0, 10));
        c.addView(row("Replay tour", null, null, v -> showTour()), lp(-1, -2, 0, 0, 0, 10));
        c.addView(row("Debug log", "Crashes, timings and what the app did. Copy it and send it to me", null, v -> showLog()), lp(-1, -2, 0, 0, 0, 10));

        c.addView(label("Experimental"));
        c.addView(switchRow("Android 16 status-bar chip", "Only some phones show it. Samsung usually doesn't", Store.flag(this, "chip", false), v -> { Store.setFlag(this, "chip", v); sync(); }), lp(-1, -2, 0, 0, 0, 10));

        TextView ver = tv("Days Left 3.17", 12, SUB, false);
        ver.setGravity(Gravity.CENTER);
        c.addView(ver, lp(-1, -2, 0, 20, 0, 0));
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    // ====================== debug log ======================
    int logFilter = 0;

    /** The log entries (a line starting with a date, plus any lines after it) that match the filter. */
    String logText(int filter, int maxChars) {
        String all = Dbg.read(Math.max(maxChars * 3, 60000));
        if (filter == 0) return all.length() > maxChars ? all.substring(all.length() - maxChars) : all;
        StringBuilder out = new StringBuilder(), cur = new StringBuilder();
        boolean keep = false;
        java.util.regex.Pattern start = java.util.regex.Pattern.compile("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} ([IWEP])/(\\w+) ");
        for (String ln : all.split("\n")) {
            java.util.regex.Matcher m = start.matcher(ln);
            if (m.find()) {
                if (keep) out.append(cur);
                cur.setLength(0);
                String lv = m.group(1), tag = m.group(2);
                keep = filter == 1 ? (lv.equals("E") || lv.equals("W")) : filter == 2 ? lv.equals("P")
                        : (tag.equals("Sync") || tag.equals("Whisper") || tag.equals("Probe") || tag.equals("Book") || tag.equals("Exit") || tag.equals("CRASH"));
            }
            cur.append(ln).append('\n');
        }
        if (keep) out.append(cur);
        String r = out.toString();
        return r.length() > maxChars ? r.substring(r.length() - maxChars) : r;
    }

    void showLog() {
        screen = 5;
        root.removeAllViews();
        LinearLayout page = col();
        LinearLayout bar = hrow();
        bar.setPadding(dp(14), dp(10), dp(20), dp(6));
        FrameLayout back = new FrameLayout(this);
        back.addView(ico(1, TXT, 30), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        back.setOnClickListener(v -> showSettings());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(tv("Debug log", 22, TXT, true), lp(-2, -2, 4, 0, 0, 0));
        page.addView(bar);

        LinearLayout top = col();
        top.setPadding(dp(20), dp(4), dp(20), dp(0));
        TextView info = tv(Dbg.header(this).trim(), 11, SUB, false);
        info.setTypeface(Typeface.MONOSPACE);
        LinearLayout ic = card();
        ic.addView(info);
        top.addView(ic, lp(-1, -2, 0, 0, 0, 10));
        TextView[] body = new TextView[1];
        ScrollView[] sv = new ScrollView[1];
        Runnable refresh = () -> {
            String t = logText(logFilter, 80000);
            body[0].setText(t.isEmpty() ? "Nothing logged for this filter yet." : t);
            info.setText(Dbg.header(this).trim());
            sv[0].post(() -> sv[0].fullScroll(View.FOCUS_DOWN));
        };
        top.addView(chips(new String[]{"All", "Problems", "Timings", "Sync"}, logFilter, i -> { logFilter = i; refresh.run(); }), lp(-1, -2, 0, 0, 0, 10));
        page.addView(top);

        sv[0] = new ScrollView(this);
        body[0] = tv("", 11, TXT, false);
        body[0].setTypeface(Typeface.MONOSPACE);
        body[0].setTextIsSelectable(true);
        body[0].setPadding(dp(20), dp(4), dp(20), dp(16));
        sv[0].addView(body[0]);
        page.addView(sv[0], new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout btns = hrow();
        btns.setPadding(dp(20), dp(8), dp(20), dp(16));
        TextView clear = button("Clear", CARD2, TXT), share = button("Share", CARD2, TXT), copy = button("Copy", ACC[0], DARK);
        copy.setOnClickListener(v -> {
            String all = "=== Days Left log ===\n" + Dbg.header(this) + "\n" + logText(logFilter, 200000);
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Days Left log", all));
            Toast.makeText(this, "Copied " + (all.length() / 1024 + 1) + " KB. Paste it into the chat", Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> {
            String all = "=== Days Left log ===\n" + Dbg.header(this) + "\n" + logText(logFilter, 100000);
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, all).putExtra(Intent.EXTRA_SUBJECT, "Days Left log"), "Share log"));
        });
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Clear the whole log?").setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> { Dbg.clear(); refresh.run(); }).show());
        btns.addView(clear, wt(0, 0, 6, 0));
        btns.addView(share, wt(6, 0, 6, 0));
        btns.addView(copy, wt(6, 0, 0, 0));
        page.addView(btns);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        refresh.run();
    }

    // ====================== tour ======================
    void showTour() {
        tourPage = 0;
        if (tour != null) root.removeView(tour);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(BG);
        f.setClickable(true);
        f.setElevation(dp(40));
        tour = f;
        root.addView(f, new FrameLayout.LayoutParams(-1, -1));
        renderTour();
    }

    void renderTour() {
        String[][] pg = {
                {"⏳", "Your countdown, always in sight", "Days and hours left show right next to the clock in your status bar."},
                {"🎯", "Start from a template", "Exam, birthday, until we meet, life clock — or build your own with an emoji and colour."},
                {"📌", "It stays pinned", "The notification comes back if you swipe it away. Allow notifications and unrestricted battery so it never stops."},
                {"📚", "Your library", "Save books, podcasts and videos you keep meaning to get to. Set a deadline, read PDFs on paper-style pages and pause your audiobook from the reader."},
                {"🎉", "Celebrate the finish", "When the day arrives you get an alert. Afterwards it shows ✓ Completed, counts up, or removes itself — your choice."}};
        FrameLayout f = (FrameLayout) tour;
        f.removeAllViews();
        String[] p = pg[tourPage];
        int acc = ACC[tourPage % ACC.length];
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(32), 0, dp(32), 0);
        TextView e = tv(p[0], 64, TXT, false);
        e.setGravity(Gravity.CENTER);
        e.setBackground(rr(alpha(acc, 0x26), 80));
        c.addView(e, new LinearLayout.LayoutParams(dp(160), dp(160)));
        TextView t = tv(p[1], 26, TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(32), 0, dp(12));
        c.addView(t);
        TextView b = tv(p[2], 16, SUB, false);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(dp(3), 1f);
        c.addView(b);
        if (tourPage == 2) {
            TextView allow = button("Allow now", alpha(ACC[0], 0x33), ACC[0]);
            allow.setOnClickListener(v -> {
                sync();
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
            });
            c.addView(allow, lp(-2, -2, 0, 24, 0, 0));
        }
        f.addView(c, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout dots = hrow();
        for (int i = 0; i < pg.length; i++) {
            View d = new View(this);
            d.setBackground(rr(i == tourPage ? ACC[0] : 0xFF2A2F39, 4));
            dots.addView(d, lp(dp(i == tourPage ? 22 : 8), dp(8), 4, 0, 4, 0));
        }
        FrameLayout.LayoutParams dp1 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dp1.bottomMargin = dp(118);
        f.addView(dots, dp1);

        boolean last = tourPage == pg.length - 1;
        TextView next = button(last ? "Get started" : "Next", ACC[0], DARK);
        next.setOnClickListener(v -> { if (last) endTour(); else { tourPage++; renderTour(); } });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(dp(24), 0, dp(24), dp(36));
        f.addView(next, np);
        if (!last) {
            TextView skip = tv("Skip", 15, SUB, true);
            skip.setPadding(dp(24), dp(22), dp(24), dp(22));
            skip.setOnClickListener(v -> endTour());
            f.addView(skip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        }
    }

    void endTour() {
        Store.setFlag(this, "toured", true);
        root.removeView(tour);
        tour = null;
    }
}
