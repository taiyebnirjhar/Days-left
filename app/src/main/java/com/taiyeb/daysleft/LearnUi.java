package com.taiyeb.daysleft;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.time.LocalDate;
import java.util.*;

/** Screens for the Learn feature: plan cards on home, a plan screen, plan editor, item sheet, paste sheet, share capture. */
final class LearnUi {
    final MainActivity a;
    static final int[] ACC = CountdownService.ACC;
    static final String[] PLAN_EMOJI = {"📚", "🎧", "🎙️", "▶️", "🧠", "💪", "💼", "🌱"};

    int curPlan;

    LearnUi(MainActivity a) { this.a = a; }

    // ====================== shared bits ======================
    EditText field(String hint, String text, boolean multi) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(text);
        e.setTextColor(MainActivity.TXT);
        e.setHintTextColor(MainActivity.SUB);
        e.setTextSize(16);
        e.setBackground(a.rr(MainActivity.CARD2, 16));
        e.setPadding(a.dp(16), a.dp(multi ? 14 : 0), a.dp(16), a.dp(multi ? 14 : 0));
        if (multi) {
            e.setMinLines(6);
            e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setVerticalScrollBarEnabled(true);
            e.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            e.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            // let the box scroll its own text instead of the page stealing the swipe
            e.setOnTouchListener((v, ev) -> {
                if (v.canScrollVertically(1) || v.canScrollVertically(-1)) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) v.getParent().requestDisallowInterceptTouchEvent(false);
                return false;
            });
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    /** Horizontal row of pills; returns the scroller. labels[i] is shown, cb gets the index. */
    HorizontalScrollView pills(String[] labels, int cur, java.util.function.IntConsumer cb) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = a.hrow();
        int[] sel = {cur};
        TextView[] ts = new TextView[labels.length];
        Runnable paint = () -> {
            for (int i = 0; i < ts.length; i++) {
                boolean on = i == sel[0];
                ts[i].setBackground(a.rr(on ? a.alpha(ACC[0], 0x33) : MainActivity.CARD2, 14));
                ts[i].setTextColor(on ? ACC[0] : MainActivity.SUB);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            ts[i] = a.tv(labels[i], 14, MainActivity.SUB, true);
            ts[i].setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            ts[i].setOnClickListener(v -> { sel[0] = idx; paint.run(); cb.accept(idx); });
            row.addView(ts[i], a.lp(-2, -2, 0, 0, 8, 0));
        }
        paint.run();
        hs.addView(row);
        return hs;
    }

    String[] typeLabels() {
        String[] l = new String[6];
        for (int i = 0; i < 6; i++) l[i] = Learn.EMO[i] + " " + Learn.TYPES[i];
        return l;
    }

    String host(String url) { return Learn.hostOf(url); }

    void open(String url) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(a, "Can't open that link", Toast.LENGTH_SHORT).show(); }
    }

    String fixUrl(String u) {
        u = u.trim();
        return u.isEmpty() || u.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? u : "https://" + u;
    }

    // ====================== home ======================
    boolean hasAny() { return !Learn.plans(a).isEmpty() || !Learn.items(a).isEmpty(); }

    void homeCards(LinearLayout box, boolean withLabel) {
        List<Plan> ps = Learn.plans(a);
        List<Item> all = Learn.items(a);
        ps.sort((x, y) -> Long.compare(x.deadline, y.deadline));
        if (withLabel) box.addView(a.label("Learning"));
        for (Plan p : ps) box.addView(planCard(p, Learn.inPlan(all, p.id)), a.lp(-1, -2, 0, 0, 0, 14));
        List<Item> inbox = inbox(all);
        if (!inbox.isEmpty()) box.addView(inboxCard(inbox), a.lp(-1, -2, 0, 0, 0, 14));
    }

    List<Item> inbox(List<Item> all) {
        Set<Integer> ids = new HashSet<>();
        for (Plan p : Learn.plans(a)) ids.add(p.id);
        List<Item> out = new ArrayList<>();
        for (Item i : all) if (i.planId == 0 || !ids.contains(i.planId)) out.add(i);
        return out;
    }

    View inboxCard(List<Item> its) {
        int open = 0;
        Item next = null;
        for (Item i : its) if (!i.done) { open++; if (next == null || i.added < next.added) next = i; }
        LinearLayout card = a.hrow();
        card.setBackground(a.rr(MainActivity.CARD, 22));
        card.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        TextView em = a.tv("📥", 20, MainActivity.TXT, false);
        em.setGravity(Gravity.CENTER);
        em.setBackground(a.rr(MainActivity.CARD2, 16));
        card.addView(em, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));
        LinearLayout n = a.col();
        n.addView(a.tv("Inbox", 16, MainActivity.TXT, true));
        TextView sub = a.tv(open == 0 ? "Everything saved is done 🎉" : open + " waiting · next: " + next.title, 13, MainActivity.SUB, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        n.addView(sub);
        card.addView(n, a.wt(12, 0, 0, 0));
        card.setOnClickListener(v -> showPlan(0));
        return card;
    }

    View planCard(Plan p, List<Item> its) {
        Learn.Pace k = Learn.pace(p, its, LocalDate.now());
        int acc = k.complete ? MainActivity.OK : ACC[p.color % ACC.length];
        LinearLayout card = a.col();
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a.alpha(acc, 0x38), MainActivity.CARD, MainActivity.CARD});
        bg.setCornerRadius(a.dp(26));
        card.setBackground(bg);
        card.setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18));

        LinearLayout top = a.hrow();
        TextView em = a.tv(p.emoji, 22, MainActivity.TXT, false);
        em.setGravity(Gravity.CENTER);
        em.setBackground(a.rr(a.alpha(acc, 0x33), 24));
        top.addView(em, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        LinearLayout names = a.col();
        TextView nm = a.tv(p.name, 17, MainActivity.TXT, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nm);
        names.addView(a.tv("By " + a.fmt(LocalDate.ofEpochDay(p.deadline)), 13, MainActivity.SUB, false));
        top.addView(names, a.wt(12, 0, 8, 0));
        if (!k.complete) {
            TextView dl = a.tv(k.overdue ? "Overdue" : k.daysLeft + "d left", 13, k.overdue ? 0xFFFF7A7A : acc, true);
            dl.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            dl.setBackground(a.rr(a.alpha(k.overdue ? 0xFFFF7A7A : acc, 0x22), 12));
            top.addView(dl);
        }
        card.addView(top);

        SpannableStringBuilder b = new SpannableStringBuilder();
        if (k.total == 0) a.part(b, "Empty", 1.9f, acc, "");
        else {
            a.part(b, String.valueOf(k.done), 2.6f, acc, "of " + k.total + " done");
        }
        TextView big = a.tv("", 15, MainActivity.TXT, false);
        big.setText(b);
        big.setPadding(0, a.dp(16), 0, a.dp(12));
        card.addView(big);

        MainActivity.Bar bar = new MainActivity.Bar(a);
        bar.set(k.frac, acc);
        card.addView(bar, new LinearLayout.LayoutParams(-1, a.dp(8)));
        String foot = k.sub.isEmpty() ? k.headline : k.headline + " · " + k.sub;
        TextView ft = a.tv(foot, 12, MainActivity.SUB, false);
        ft.setPadding(0, a.dp(9), 0, 0);
        card.addView(ft);
        card.setOnClickListener(v -> showPlan(p.id));
        return card;
    }

    // ====================== library tab ======================
    boolean showDone;

    LinearLayout actionRow(String emoji, String title, String sub, Runnable go, boolean card) {
        LinearLayout r = a.hrow();
        r.setPadding(a.dp(card ? 14 : 6), a.dp(card ? 14 : 10), a.dp(card ? 14 : 6), a.dp(card ? 14 : 10));
        if (card) r.setBackground(a.rr(MainActivity.CARD, 18));
        TextView em = a.tv(emoji, 24, MainActivity.TXT, false);
        em.setGravity(Gravity.CENTER);
        em.setBackground(a.rr(MainActivity.CARD2, 16));
        r.addView(em, new LinearLayout.LayoutParams(a.dp(50), a.dp(50)));
        LinearLayout n = a.col();
        n.addView(a.tv(title, 16, MainActivity.TXT, true));
        n.addView(a.tv(sub, 13, MainActivity.SUB, false));
        r.addView(n, a.wt(14, 0, 0, 0));
        r.setOnClickListener(v -> go.run());
        return r;
    }

    void library(LinearLayout box) {
        List<Plan> ps = Learn.plans(a);
        List<Item> all = Learn.items(a);
        if (ps.isEmpty() && all.isEmpty()) { emptyLibrary(box); return; }
        ps.sort((x, y) -> Long.compare(x.deadline, y.deadline));

        View rc = readingCard();
        if (rc != null) box.addView(rc, a.lp(-1, -2, 0, 0, 0, 6));

        if (!ps.isEmpty()) {
            box.addView(a.label("Plans"));
            for (Plan p : ps) box.addView(planCard(p, Learn.inPlan(all, p.id)), a.lp(-1, -2, 0, 0, 0, 14));
        }

        // saved for later: the items themselves, not a summary
        List<Item> saved = inbox(all);
        LinearLayout hdr = a.hrow();
        TextView lbl = a.label("Saved for later");
        hdr.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView paste = a.tv("Paste a list", 13, ACC[0], true);
        paste.setPadding(a.dp(10), a.dp(18), a.dp(4), a.dp(4));
        paste.setOnClickListener(v -> pasteSheet(0, a::renderList));
        hdr.addView(paste);
        box.addView(hdr);

        int open = 0, done = 0;
        for (Item i : saved) { if (i.done) done++; else open++; }
        if (saved.isEmpty()) {
            TextView t = a.tv("Nothing here yet. Tap Add, or share a link from YouTube, Spotify or your browser.", 14, MainActivity.SUB, false);
            t.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            box.addView(t);
            return;
        }
        for (Item i : saved) if (!i.done) box.addView(itemRow(i, null, a::renderList), a.lp(-1, -2, 0, 0, 0, 8));
        if (open == 0) {
            TextView t = a.tv("All caught up 🎉", 14, MainActivity.SUB, false);
            t.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(8));
            box.addView(t);
        }
        if (done > 0) {
            TextView tg = a.tv((showDone ? "Hide " : "Show ") + done + " finished", 13, ACC[0], true);
            tg.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(8));
            tg.setOnClickListener(v -> { showDone = !showDone; a.renderList(); });
            box.addView(tg);
            if (showDone) for (Item i : saved) if (i.done) box.addView(itemRow(i, null, a::renderList), a.lp(-1, -2, 0, 0, 0, 8));
        }
    }

    void emptyLibrary(LinearLayout box) {
        LinearLayout e = a.col();
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView em = a.tv("📚", 40, MainActivity.TXT, false);
        em.setGravity(Gravity.CENTER);
        em.setBackground(a.rr(a.alpha(ACC[3], 0x22), 60));
        e.addView(em, new LinearLayout.LayoutParams(a.dp(84), a.dp(84)));
        TextView t = a.tv("Your library is empty", 22, MainActivity.TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, a.dp(18), 0, a.dp(6));
        e.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = a.tv("Keep the books, podcasts and videos you mean to get to, then actually finish them.", 15, MainActivity.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(a.dp(16), 0, a.dp(16), 0);
        e.addView(s, new LinearLayout.LayoutParams(-1, -2));
        e.addView(actionRow("📖", "Read a book", "Open a PDF, add its audiobook link", this::newBook, true), a.lp(-1, -2, 0, 26, 0, 10));
        e.addView(actionRow("📚", "Learning plan", "Many items with one deadline", () -> showPlanEditor(newPlan(), true), true), a.lp(-1, -2, 0, 0, 0, 10));
        e.addView(actionRow("📥", "Save for later", "A link or title to get to soon", this::saveOne, true), a.lp(-1, -2, 0, 0, 0, 10));
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }

    void saveOne() {
        Item it = new Item();
        it.type = 0;
        it.mins = Learn.MINS[0];
        itemSheet(it, true, a::renderList, false);
    }

    // ====================== add sheet ======================
    void addSheet() {
        LinearLayout box = a.col();
        Dialog[] d = new Dialog[1];
        box.addView(actionRow("📖", "Read a book", "Open a PDF, add its audiobook link", () -> { d[0].dismiss(); newBook(); }, false));
        box.addView(actionRow("📚", "Learning plan", "Many items with one deadline", () -> { d[0].dismiss(); showPlanEditor(newPlan(), true); }, false));
        box.addView(actionRow("📥", "Save for later", "A link or title to get to soon", () -> { d[0].dismiss(); saveOne(); }, false));
        box.addView(actionRow("📋", "Paste a list", "Many titles at once, one per line", () -> { d[0].dismiss(); pasteSheet(0, a::renderList); }, false));
        d[0] = a.sheet("Add to library", box);
        d[0].show();
    }

    /** Pick a PDF first, then confirm the title and audio link, then open the reader. */
    void newBook() {
        a.pickPdf((uri, name) -> {
            Item it = new Item();
            it.type = 0;
            it.mins = Learn.MINS[0];
            it.pdf = uri;
            it.pdfName = name;
            it.title = name.replaceFirst("(?i)\\.pdf$", "").replace('_', ' ').trim();
            itemSheet(it, true, () -> {
                Store.setNum(a, "tab", 1);
                a.showHome();
                a.startActivity(new Intent(a, ReaderActivity.class).putExtra("id", it.id));
            }, false);
        });
    }

    /** The card at the top of home: the book you were last reading, with play/pause for its audio. */
    View readingCard() {
        Item pick = null;
        int last = Store.num(a, "lastBook", -1);
        for (Item i : Learn.items(a)) {
            if (i.pdf.isEmpty() || i.done) continue;
            if (i.id == last) { pick = i; break; }
            if (pick == null || (pick.page == 0 && i.page > 0)) pick = i;
        }
        if (pick == null) return null;
        final Item it = pick;
        int acc = ACC[3];
        LinearLayout card = a.col();
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a.alpha(acc, 0x44), MainActivity.CARD, MainActivity.CARD});
        bg.setCornerRadius(a.dp(26));
        card.setBackground(bg);
        card.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        card.addView(a.tv("CONTINUE READING", 11, acc, true));
        TextView nm = a.tv("📖  " + it.title, 18, MainActivity.TXT, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setPadding(0, a.dp(6), 0, 0);
        card.addView(nm);
        float frac = it.pages > 1 ? (float) it.page / (it.pages - 1) : 0;
        card.addView(a.tv(it.pages > 0 ? "Page " + (it.page + 1) + " of " + it.pages + " · " + Math.round(frac * 100) + "%" : "Not started", 13, MainActivity.SUB, false));
        MainActivity.Bar bar = new MainActivity.Bar(a);
        bar.set(frac, acc);
        card.addView(bar, a.lp(-1, a.dp(6), 0, 12, 0, 14));
        LinearLayout btns = a.hrow();
        TextView read = a.button(it.page > 0 ? "Continue reading" : "Start reading", acc, MainActivity.DARK);
        read.setOnClickListener(v -> a.startActivity(new Intent(a, ReaderActivity.class).putExtra("id", it.id)));
        btns.addView(read, a.wt(0, 0, 0, 0));
        card.addView(btns);
        return card;
    }

    Plan newPlan() {
        Plan p = new Plan();
        p.id = Learn.nextPlanId(Learn.plans(a));
        p.start = LocalDate.now().toEpochDay();
        p.deadline = LocalDate.now().plusDays(180).toEpochDay();
        p.color = p.id % ACC.length;
        return p;
    }

    // ====================== plan screen ======================
    void showPlan(int planId) {
        Plan p = planId == 0 ? null : Learn.plan(a, planId);
        if (planId != 0 && p == null) { a.showHome(); return; }
        a.screen = 3;
        curPlan = planId;
        a.root.removeAllViews();
        LinearLayout page = a.col();
        LinearLayout bar = (LinearLayout) a.topBar(p == null ? "Inbox" : p.emoji + " " + p.name);
        bar.addView(new View(a), a.wt(0, 0, 0, 0));
        if (p != null) {
            TextView edit = a.tv("Edit", 14, ACC[0], true);
            edit.setPadding(a.dp(14), a.dp(9), a.dp(14), a.dp(9));
            edit.setBackground(a.rr(a.alpha(ACC[0], 0x22), 14));
            edit.setOnClickListener(v -> showPlanEditor(p, false));
            bar.addView(edit);
        }
        page.addView(bar);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = a.col();
        c.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(120));
        LinearLayout summary = a.col(), list = a.col();
        c.addView(summary);
        c.addView(list);
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        a.root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            List<Plan> ps = Learn.plans(a);
            Plan pl = null;
            for (Plan x : ps) if (x.id == planId) pl = x;
            List<Item> its = planId == 0 ? inbox(Learn.items(a)) : Learn.inPlan(Learn.items(a), planId);
            fillSummary(summary, pl, its);
            fillList(list, its, pl, fill[0]);
        };
        fill[0].run();

        LinearLayout btns = a.hrow();
        TextView add = a.button("+ Add item", ACC[0], MainActivity.DARK), paste = a.button("Paste list", MainActivity.CARD2, MainActivity.TXT);
        add.setOnClickListener(v -> {
            Item it = new Item();
            it.planId = planId;
            it.type = 0;
            it.mins = Learn.MINS[0];
            itemSheet(it, true, fill[0], false);
        });
        paste.setOnClickListener(v -> pasteSheet(planId, fill[0]));
        btns.addView(add, a.wt(0, 0, 6, 0));
        btns.addView(paste, a.wt(6, 0, 0, 0));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(a.dp(20), 0, a.dp(20), a.dp(20));
        btns.setBackground(a.rr(MainActivity.BG, 18));
        a.root.addView(btns, bp);
    }

    void fillSummary(LinearLayout box, Plan p, List<Item> its) {
        box.removeAllViews();
        if (p == null) {
            int open = 0;
            for (Item i : its) if (!i.done) open++;
            TextView t = a.tv(its.isEmpty() ? "Nothing saved yet. In Spotify, YouTube or your browser, tap Share and choose Days Left."
                    : open + " waiting. Tap an item to move it into a plan.", 14, MainActivity.SUB, false);
            t.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(14));
            box.addView(t);
            return;
        }
        Learn.Pace k = Learn.pace(p, its, LocalDate.now());
        int acc = k.complete ? MainActivity.OK : ACC[p.color % ACC.length];
        LinearLayout card = a.col();
        card.setBackground(a.rr(MainActivity.CARD, 22));
        card.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        SpannableStringBuilder b = new SpannableStringBuilder();
        a.part(b, String.valueOf(k.done), 2.6f, acc, "of " + k.total + " done");
        TextView big = a.tv("", 15, MainActivity.TXT, false);
        big.setText(b);
        card.addView(big);
        MainActivity.Bar bar = new MainActivity.Bar(a);
        bar.set(k.frac, acc);
        card.addView(bar, a.lp(-1, a.dp(8), 0, 12, 0, 0));
        TextView h = a.tv(k.headline, 17, k.overdue ? 0xFFFF7A7A : MainActivity.TXT, true);
        h.setPadding(0, a.dp(12), 0, 0);
        card.addView(h);
        String line = (k.sub.isEmpty() ? "" : k.sub + "\n") + "Deadline " + a.fmt(LocalDate.ofEpochDay(p.deadline))
                + (k.complete ? "" : " · " + k.daysLeft + " days left");
        card.addView(a.tv(line, 13, MainActivity.SUB, false));
        box.addView(card, a.lp(-1, -2, 0, 0, 0, 6));
    }

    void fillList(LinearLayout box, List<Item> its, Plan p, Runnable refresh) {
        box.removeAllViews();
        if (its.isEmpty()) {
            TextView t = a.tv("No items yet. Add one, or paste a whole list.", 14, MainActivity.SUB, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, a.dp(40), 0, 0);
            box.addView(t);
            return;
        }
        boolean any = false;
        for (Item i : its) if (!i.done) { box.addView(itemRow(i, p, refresh), a.lp(-1, -2, 0, 0, 0, 8)); any = true; }
        boolean doneHdr = false;
        for (Item i : its) if (i.done) {
            if (!doneHdr) { box.addView(a.label("Done")); doneHdr = true; }
            box.addView(itemRow(i, p, refresh), a.lp(-1, -2, 0, 0, 0, 8));
        }
        if (!any && p == null) { /* inbox all done */ }
    }

    View itemRow(Item it, Plan p, Runnable refresh) {
        int acc = p == null ? ACC[0] : ACC[p.color % ACC.length];
        LinearLayout r = a.hrow();
        r.setBackground(a.rr(MainActivity.CARD, 18));
        r.setPadding(a.dp(12), a.dp(12), a.dp(14), a.dp(12));

        FrameLayout chk = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (it.done) g.setColor(MainActivity.OK);
        else g.setStroke(a.dp(2), 0xFF4A5160);
        chk.setBackground(g);
        if (it.done) chk.addView(a.ico(3, MainActivity.DARK, 22), new FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER));
        chk.setOnClickListener(v -> { Learn.setDone(a, it.id, !it.done); refresh.run(); });
        r.addView(chk, a.lp(a.dp(30), a.dp(30), 0, 0, 12, 0));

        LinearLayout n = a.col();
        TextView t = a.tv(it.title, 16, it.done ? MainActivity.SUB : MainActivity.TXT, false);
        if (it.done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        n.addView(t);
        String sub = Learn.EMO[it.type % 6] + " " + Learn.TYPES[it.type % 6] + " · about " + Learn.fmtMins(it.mins);
        if (it.pages > 0) sub += " · page " + (it.page + 1) + " of " + it.pages;
        else if (!it.url.isEmpty()) sub += " · " + host(it.url);
        TextView s = a.tv(sub, 12, MainActivity.SUB, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        n.addView(s);
        r.addView(n, a.wt(0, 0, 8, 0));

        if (!it.pdf.isEmpty() && !it.done) {
            TextView o = a.tv(it.page > 0 ? "Continue" : "Read", 13, MainActivity.DARK, true);
            o.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
            o.setBackground(a.rr(acc, 12));
            o.setOnClickListener(v -> a.startActivity(new Intent(a, ReaderActivity.class).putExtra("id", it.id)));
            r.addView(o);
        } else if (!it.url.isEmpty() && !it.done) {
            TextView o = a.tv("Open", 13, acc, true);
            o.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            o.setBackground(a.rr(a.alpha(acc, 0x22), 12));
            o.setOnClickListener(v -> open(it.url));
            r.addView(o);
        }
        r.setOnClickListener(v -> itemSheet(it, false, refresh, false));
        return r;
    }

    // ====================== item sheet ======================
    void itemSheet(Item it, boolean isNew, Runnable after, boolean fromShare) {
        LinearLayout b = a.col();
        EditText title = field("Title (e.g. Grit)", it.title, false);
        EditText url = field("Link to save (optional)", it.url, false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        b.addView(title, new LinearLayout.LayoutParams(-1, a.dp(52)));
        b.addView(url, a.lp(-1, a.dp(52), 0, 8, 0, 0));
        // Books and audiobooks use files, so an empty link field is hidden for them

        int[] type = {it.type % 6}, mins = {it.mins > 0 ? it.mins : Learn.MINS[it.type % 6]}, pid = {it.planId};
        LinearLayout mbox = a.col();
        Runnable[] drawMins = new Runnable[1];
        drawMins[0] = () -> {
            mbox.removeAllViews();
            LinearLayout st = a.stepper("Time", "Rough estimate", mins[0], 5, 3000, 5, " min", x -> mins[0] = x);
            st.setBackground(a.rr(MainActivity.CARD2, 18));
            mbox.addView(st);
        };
        b.addView(a.tv("Type", 13, MainActivity.SUB, true), a.lp(-1, -2, 4, 14, 0, 6));
        b.addView(pills(typeLabels(), type[0], i -> {
            if (mins[0] == Learn.MINS[type[0]]) { mins[0] = Learn.MINS[i]; type[0] = i; drawMins[0].run(); }
            else type[0] = i;
            url.setVisibility(i <= 1 && url.getText().toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
        }));
        url.setVisibility(type[0] <= 1 && it.url.isEmpty() ? View.GONE : View.VISIBLE);
        b.addView(mbox, a.lp(-1, -2, 0, 12, 0, 0));
        drawMins[0].run();

        String[] pdf = {it.pdf}, pdfName = {it.pdfName};
        Runnable[] afterPdf = new Runnable[1];   // the audio section reacts when the PDF changes (read aloud needs it)
        LinearLayout pbox = a.col();
        Runnable[] drawPdf = new Runnable[1];
        drawPdf[0] = () -> {
            pbox.removeAllViews();
            boolean has = !pdf[0].isEmpty();
            TextView val = a.tv(has ? "Change" : "Attach", 14, ACC[0], true);
            LinearLayout row = a.row("PDF to read here", has ? pdfName[0] : "Read it in the paper-style reader", val, v -> a.pickPdf((uri, name) -> {
                pdf[0] = uri;
                pdfName[0] = name;
                if (title.getText().toString().trim().isEmpty()) title.setText(name.replaceFirst("(?i)\\.pdf$", "").replace('_', ' '));
                drawPdf[0].run();
                if (afterPdf[0] != null) afterPdf[0].run();
            }));
            row.setBackground(a.rr(MainActivity.CARD2, 18));
            pbox.addView(row);
            if (has) {
                TextView rm = a.tv("Remove PDF", 13, 0xFFFF7A7A, true);
                rm.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(2));
                rm.setOnClickListener(v -> { pdf[0] = ""; pdfName[0] = ""; drawPdf[0].run(); if (afterPdf[0] != null) afterPdf[0].run(); });
                pbox.addView(rm);
            }
        };
        drawPdf[0].run();
        b.addView(pbox, a.lp(-1, -2, 0, 14, 0, 0));

        String[] aud = {it.audio}, audName = {it.audioName};
        // read aloud: preparing the book's text. A changed or new PDF is prepared under a temporary id and moved to the item's id on Save.
        boolean[] speak = {it.speak && it.audio.isEmpty()}, prepping = {false}, swGuard = {false};
        boolean[][] prepCancel = {null};
        String[] prepFor = {speak[0] && !it.pdf.isEmpty() ? it.pdf : ""};
        int[] prepId = {0};
        TextView[] speakSub = {null};
        android.widget.Switch[] speakSw = {null};
        TextView[] saveBtn = {null};
        Runnable[] drawAud = new Runnable[1];
        Runnable[] startPrepR = new Runnable[1];
        Runnable startPrep = () -> startPrepR[0].run();
        Runnable[] cancelPrepR = new Runnable[1];
        Runnable[] saveState = new Runnable[1];   // Save is greyed out and says 'Preparing N%' while the book is being read
        int[] prepPct = {0};
        cancelPrepR[0] = () -> {
            if (prepCancel[0] != null) prepCancel[0][0] = true;
            prepCancel[0] = null;
            if (prepping[0]) { prepping[0] = false; saveState[0].run(); }
        };
        startPrepR[0] = () -> {
            final String uri = pdf[0];
            final boolean samePdf = !isNew && uri.equals(it.pdf);
            if (samePdf && BookText.ready(a, it.id)) { prepFor[0] = ""; prepId[0] = 0; if (speakSub[0] != null) speakSub[0].setText("Ready. The phone reads this book in a natural voice."); return; }
            if (prepFor[0].equals(uri) && prepId[0] > 0 && BookText.ready(a, prepId[0])) { if (speakSub[0] != null) speakSub[0].setText("Ready. The phone reads this book in a natural voice."); return; }
            if (prepId[0] == 0) prepId[0] = samePdf ? it.id : 900000 + (int) (System.currentTimeMillis() % 90000);
            final int fid = prepId[0];
            final boolean[] cancel = {false};
            prepCancel[0] = cancel;
            prepping[0] = true; prepPct[0] = 0;
            if (speakSub[0] != null) speakSub[0].setText("Getting the book ready… 0%");
            saveState[0].run();
            new Thread(() -> {
                try {
                    Item tmp = new Item();
                    tmp.id = fid; tmp.pdf = uri;
                    BookIndex bi = BookText.extract(a, tmp, pc -> a.runOnUiThread(() -> {
                        if (cancel[0]) return;
                        prepPct[0] = pc;
                        if (speakSub[0] != null) speakSub[0].setText("Getting the book ready… " + pc + "%");
                        saveState[0].run();
                    }), cancel);
                    final boolean scanned = BookText.looksScanned(bi);
                    a.runOnUiThread(() -> {
                        if (cancel[0]) return;
                        prepping[0] = false; prepCancel[0] = null;
                        if (scanned) {
                            Toast.makeText(a, "This PDF is made of page pictures, so there is no text to read aloud.", Toast.LENGTH_LONG).show();
                            speak[0] = false;
                            if (speakSw[0] != null) { swGuard[0] = true; speakSw[0].setChecked(false); swGuard[0] = false; }
                            if (speakSub[0] != null) speakSub[0].setText("No audio file? The phone reads the book to you");
                        } else {
                            prepFor[0] = uri;
                            if (speakSub[0] != null) speakSub[0].setText("Ready. The phone reads this book in a natural voice.");
                        }
                        saveState[0].run();
                    });
                } catch (Exception e) {
                    a.runOnUiThread(() -> {
                        if (cancel[0]) return;
                        prepping[0] = false; prepCancel[0] = null; speak[0] = false;
                        Toast.makeText(a, "Couldn't read this PDF's text.", Toast.LENGTH_LONG).show();
                        if (speakSw[0] != null) { swGuard[0] = true; speakSw[0].setChecked(false); swGuard[0] = false; }
                        if (speakSub[0] != null) speakSub[0].setText("No audio file? The phone reads the book to you");
                        saveState[0].run();
                    });
                }
            }, "prepare-book").start();
        };
        LinearLayout abox = a.col();
        drawAud[0] = () -> {
            abox.removeAllViews();
            boolean has = !aud[0].isEmpty();
            TextView val = a.tv(has ? "Change" : "Attach", 14, ACC[0], true);
            LinearLayout row = a.row("Audio file from your phone", has ? audName[0] : "Best: m4b or m4a. Also mp3, aac, ogg, opus, wav, flac", val, v -> a.pickAudio((uri, name) -> {
                aud[0] = uri;
                audName[0] = name;
                drawAud[0].run();
            }));
            row.setBackground(a.rr(MainActivity.CARD2, 18));
            abox.addView(row);
            if (has) {
                speak[0] = false;
                cancelPrepR[0].run();
                TextView rm = a.tv("Remove audio file", 13, 0xFFFF7A7A, true);
                rm.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(2));
                rm.setOnClickListener(v -> { aud[0] = ""; audName[0] = ""; drawAud[0].run(); });
                abox.addView(rm);
            } else {
                // no audio file: the phone can read the book aloud instead. Turning it on prepares the book (its text), with a counter; Save waits for it.
                android.widget.Switch sw = new android.widget.Switch(a);
                a.tint(sw);
                sw.setChecked(speak[0]);
                LinearLayout srow = a.row("Read it aloud for me", speak[0] && !prepping[0] ? "Ready. The phone reads this book in a natural voice." : prepping[0] ? "Getting the book ready…" : "No audio file? The phone reads the book to you", sw, v -> sw.toggle());
                srow.setBackground(a.rr(MainActivity.CARD2, 18));
                speakSub[0] = (TextView) ((LinearLayout) srow.getChildAt(0)).getChildAt(1);
                sw.setOnCheckedChangeListener((cb, on) -> {
                    if (swGuard[0]) return;
                    if (on) {
                        if (pdf[0].isEmpty()) { Toast.makeText(a, "Attach the PDF first", Toast.LENGTH_SHORT).show(); swGuard[0] = true; sw.setChecked(false); swGuard[0] = false; return; }
                        speak[0] = true;
                        startPrep.run();
                    } else {
                        speak[0] = false;
                        cancelPrepR[0].run();
                        speakSub[0].setText("No audio file? The phone reads the book to you");
                    }
                });
                speakSw[0] = sw;
                abox.addView(srow, a.lp(-1, -2, 0, 8, 0, 0));
            }
        };
        afterPdf[0] = () -> {
            cancelPrepR[0].run();
            if (pdf[0].isEmpty()) speak[0] = false;
            else if (speak[0] && aud[0].isEmpty()) startPrep.run();
            drawAud[0].run();
        };
        drawAud[0].run();
        b.addView(abox, a.lp(-1, -2, 0, 10, 0, 0));

        List<Plan> ps = Learn.plans(a);
        ps.sort((x, y) -> Long.compare(x.deadline, y.deadline));
        if (!ps.isEmpty()) {
            String[] names = new String[ps.size() + 1];
            names[0] = "📥 Inbox";
            int cur = 0;
            for (int i = 0; i < ps.size(); i++) { names[i + 1] = ps.get(i).emoji + " " + ps.get(i).name; if (ps.get(i).id == it.planId) cur = i + 1; }
            b.addView(a.tv("Where", 13, MainActivity.SUB, true), a.lp(-1, -2, 4, 14, 0, 6));
            b.addView(pills(names, cur, i -> pid[0] = i == 0 ? 0 : ps.get(i - 1).id));
        }

        Dialog[] d = new Dialog[1];
        boolean[] saved = {false};
        LinearLayout btns = a.hrow();
        TextView save = a.button(isNew ? "Save" : "Save changes", ACC[0], MainActivity.DARK);
        saveBtn[0] = save;
        saveState[0] = () -> {
            save.setEnabled(!prepping[0]);
            save.setAlpha(prepping[0] ? 0.55f : 1f);
            save.setText(prepping[0] ? "Preparing " + prepPct[0] + "%" : isNew ? "Save" : "Save changes");
        };
        save.setOnClickListener(v -> {
            if (prepping[0]) return;
            String ti = title.getText().toString().trim(), u = fixUrl(url.getText().toString());
            if (ti.isEmpty() && !u.isEmpty()) ti = host(u);
            if (ti.isEmpty()) { Toast.makeText(a, "Add a title", Toast.LENGTH_SHORT).show(); return; }
            it.title = ti;
            it.url = u;
            it.type = type[0];
            it.mins = mins[0];
            it.planId = pid[0];
            boolean changedPdf = !pdf[0].equals(it.pdf);
            boolean prepared = speak[0] && aud[0].isEmpty() && prepFor[0].equals(pdf[0]) && prepId[0] > 0;
            if (changedPdf) { it.page = 0; it.pages = 0; it.sync = ""; if (!isNew) BookText.forget(a, it.id); }
            it.pdf = pdf[0];
            it.pdfName = pdfName[0];
            if (!aud[0].equals(it.audio)) { it.apos = 0; it.sync = ""; BookText.clearAudioData(a, it.id); }
            it.audio = aud[0];
            it.audioName = audName[0];
            it.speak = speak[0] && aud[0].isEmpty() && !pdf[0].isEmpty();
            if (isNew) { it.id = Learn.nextItemId(Learn.items(a)); it.added = System.currentTimeMillis(); }
            if (prepared && prepId[0] != it.id) { BookText.adopt(a, prepId[0], it.id); prepId[0] = 0; }   // the prepared text now belongs to this item
            else if (it.speak && prepId[0] > 0 && prepId[0] == it.id) prepId[0] = 0;
            Learn.upsert(a, it);
            saved[0] = true;
            d[0].dismiss();
            if (fromShare) { Toast.makeText(a, "Saved to " + (pid[0] == 0 ? "Inbox" : "your plan"), Toast.LENGTH_SHORT).show(); a.finish(); }
            else if (after != null) after.run();
        });
        if (!isNew) {
            TextView del = a.button("Delete", a.alpha(0xFFFF5C5C, 0x33), 0xFFFF7A7A);
            del.setOnClickListener(v -> a.confirm("Delete item?", "“" + it.title + "” will be removed.", "Delete", () -> {
                List<Item> all = Learn.items(a);
                all.removeIf(x -> x.id == it.id);
                Learn.saveItems(a, all);
                d[0].dismiss();
                if (after != null) after.run();
            }));
            btns.addView(del, a.wt(0, 0, 6, 0));
            btns.addView(save, a.wt(6, 0, 0, 0));
        } else btns.addView(save, a.wt(0, 0, 0, 0));
        b.addView(btns, a.lp(-1, -2, 0, 18, 0, 0));
        d[0] = a.sheet(!isNew ? "Edit item" : it.pdf.isEmpty() ? "Save for later" : "New book", b);
        d[0].setOnDismissListener(x -> {
            cancelPrepR[0].run();
            if (prepId[0] >= 900000 && prepId[0] != it.id) BookText.forget(a, prepId[0]);   // prepared under a temporary id and never saved
            if (fromShare && !saved[0]) a.finish();
        });
        d[0].show();
    }

    // ====================== paste sheet ======================
    int countLines(String s) {
        int n = 0;
        for (String l : s.split("\\r?\\n")) { String[] p = Learn.parseLine(l); if (!p[0].isEmpty() || !p[1].isEmpty()) n++; }
        return n;
    }

    void pasteSheet(int planId, Runnable after) {
        LinearLayout b = a.col();
        EditText box = field("One per line. Numbers, bullets and links are fine", "", true);
        b.addView(box, new LinearLayout.LayoutParams(-1, a.dp(170)));
        TextView count = a.tv("Nothing yet", 12, MainActivity.SUB, false);
        b.addView(count, a.lp(-1, -2, 4, 8, 0, 0));
        int[] type = {0};
        b.addView(a.tv("These are", 13, MainActivity.SUB, true), a.lp(-1, -2, 4, 12, 0, 6));
        b.addView(pills(typeLabels(), 0, i -> type[0] = i));
        Dialog[] d = new Dialog[1];
        TextView add = a.button("Add items", ACC[0], MainActivity.DARK);
        box.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) {}
            public void onTextChanged(CharSequence s, int x, int y, int z) {}
            public void afterTextChanged(Editable e) {
                int n = countLines(e.toString());
                count.setText(n == 0 ? "Nothing yet" : n + (n == 1 ? " item found" : " items found"));
                add.setText(n == 0 ? "Add items" : "Add " + n + (n == 1 ? " item" : " items"));
            }
        });
        add.setOnClickListener(v -> {
            List<Item> news = Learn.parsePaste(a, box.getText().toString(), planId, type[0]);
            if (news.isEmpty()) { Toast.makeText(a, "Paste at least one line", Toast.LENGTH_SHORT).show(); return; }
            List<Item> all = Learn.items(a);
            all.addAll(news);
            Learn.saveItems(a, all);
            d[0].dismiss();
            if (after != null) after.run();
        });
        b.addView(add, a.lp(-1, -2, 0, 16, 0, 0));
        d[0] = a.sheet("Paste a list", b);
        d[0].show();
    }

    // ====================== plan editor ======================
    void showPlanEditor(Plan p, boolean isNew) {
        a.screen = 4;
        a.root.removeAllViews();
        LinearLayout page = a.col();
        page.addView(a.topBar(isNew ? "New learning plan" : "Edit plan"));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = a.col();
        c.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(24));

        LinearLayout nameRow = a.hrow();
        EditText em = new EditText(a);
        em.setText(p.emoji);
        em.setTextSize(26);
        em.setGravity(Gravity.CENTER);
        em.setTextColor(MainActivity.TXT);
        em.setBackground(a.rr(MainActivity.CARD, 18));
        em.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        nameRow.addView(em, a.lp(a.dp(64), a.dp(58), 0, 0, 10, 0));
        EditText name = field("Name (e.g. Books from that video)", p.name, false);
        name.setBackground(a.rr(MainActivity.CARD, 18));
        nameRow.addView(name, new LinearLayout.LayoutParams(0, a.dp(58), 1f));
        c.addView(nameRow);

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = a.hrow();
        for (String e : PLAN_EMOJI) {
            TextView t = a.tv(e, 22, MainActivity.TXT, false);
            t.setGravity(Gravity.CENTER);
            t.setBackground(a.rr(MainActivity.CARD, 14));
            t.setOnClickListener(v -> em.setText(e));
            chipRow.addView(t, a.lp(a.dp(46), a.dp(46), 0, 0, 8, 0));
        }
        hs.addView(chipRow);
        c.addView(hs, a.lp(-1, -2, 0, 10, 0, 6));

        c.addView(a.label("Deadline"));
        TextView dv = a.tv(a.fmt(LocalDate.ofEpochDay(p.deadline)), 15, ACC[0], true);
        c.addView(a.row("Finish by", "The pace is worked out from this date", dv, v ->
                a.pickDate(p.deadline, "Finish by", x -> { p.deadline = x; dv.setText(a.fmt(LocalDate.ofEpochDay(x))); })), a.lp(-1, -2, 0, 0, 0, 10));

        LinearLayout dots = a.hrow();
        dots.setBackground(a.rr(MainActivity.CARD, 18));
        dots.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        dots.addView(a.tv("Colour", 16, MainActivity.TXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            while (dots.getChildCount() > 1) dots.removeViewAt(1);
            for (int i = 0; i < ACC.length; i++) {
                final int idx = i;
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(ACC[i]);
                if (p.color % ACC.length == i) g.setStroke(a.dp(3), Color.WHITE);
                View dd = new View(a);
                dd.setBackground(g);
                dd.setOnClickListener(v -> { p.color = idx; redraw[0].run(); });
                dots.addView(dd, a.lp(a.dp(28), a.dp(28), 7, 0, 0, 0));
            }
        };
        redraw[0].run();
        c.addView(dots, a.lp(-1, -2, 0, 0, 0, 10));

        EditText paste = null;
        int[] type = {0};
        TextView count = a.tv("", 12, MainActivity.SUB, false);
        if (isNew) {
            c.addView(a.label("Your list"));
            paste = field("Paste titles, one per line. Numbers, bullets and links are fine", "", true);
            paste.setBackground(a.rr(MainActivity.CARD, 18));
            c.addView(paste, new LinearLayout.LayoutParams(-1, a.dp(200)));
            count.setText("You can also add items later");
            count.setPadding(a.dp(4), a.dp(8), 0, 0);
            c.addView(count);
            c.addView(a.tv("These are", 13, MainActivity.SUB, true), a.lp(-1, -2, 4, 14, 0, 6));
            c.addView(pills(typeLabels(), 0, i -> type[0] = i));
            final EditText pe = paste;
            paste.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int x, int y, int z) {}
                public void onTextChanged(CharSequence s, int x, int y, int z) {}
                public void afterTextChanged(Editable e) {
                    int n = countLines(e.toString());
                    count.setText(n == 0 ? "You can also add items later" : n + (n == 1 ? " item found" : " items found"));
                }
            });
        }
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = a.hrow();
        bottom.setPadding(a.dp(20), a.dp(10), a.dp(20), a.dp(16));
        if (!isNew) {
            TextView del = a.button("Delete", a.alpha(0xFFFF5C5C, 0x33), 0xFFFF7A7A);
            del.setOnClickListener(v -> a.confirm("Delete plan?", "“" + p.name + "” and its items will be removed.", "Delete", () -> {
                List<Plan> ps = Learn.plans(a);
                ps.removeIf(x -> x.id == p.id);
                Learn.savePlans(a, ps);
                List<Item> all = Learn.items(a);
                all.removeIf(x -> x.planId == p.id);
                Learn.saveItems(a, all);
                a.showHome();
            }));
            bottom.addView(del, a.wt(0, 0, 6, 0));
        }
        final EditText pasteF = paste;
        TextView save = a.button(isNew ? "Create plan" : "Save", ACC[0], MainActivity.DARK);
        save.setOnClickListener(v -> {
            String nm = name.getText().toString().trim();
            if (nm.isEmpty()) { Toast.makeText(a, "Give it a name", Toast.LENGTH_SHORT).show(); return; }
            p.name = nm;
            String e = em.getText().toString().trim();
            p.emoji = e.isEmpty() ? "📚" : e;
            if (p.deadline <= p.start) p.start = Math.min(p.start, p.deadline - 1);
            List<Plan> ps = Learn.plans(a);
            boolean found = false;
            for (int i = 0; i < ps.size(); i++) if (ps.get(i).id == p.id) { ps.set(i, p); found = true; }
            if (!found) ps.add(p);
            Learn.savePlans(a, ps);
            if (isNew && pasteF != null) {
                List<Item> news = Learn.parsePaste(a, pasteF.getText().toString(), p.id, type[0]);
                List<Item> all = Learn.items(a);
                all.addAll(news);
                Learn.saveItems(a, all);
            }
            NudgeReceiver.schedule(a);
            showPlan(p.id);
        });
        bottom.addView(save, a.wt(isNew ? 0 : 6, 0, 0, 0));
        page.addView(bottom);
        a.root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    // ====================== share-sheet capture ======================
    boolean handleShare(Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return false;
        String text = in.getStringExtra(Intent.EXTRA_TEXT), subj = in.getStringExtra(Intent.EXTRA_SUBJECT);
        if ((text == null || text.trim().isEmpty()) && (subj == null || subj.trim().isEmpty())) return false;
        String[] p = Learn.parseLine(text == null ? "" : text);
        Item it = new Item();
        it.url = p[1];
        it.title = subj != null && !subj.trim().isEmpty() ? subj.trim() : p[0];
        it.type = Learn.guessType(p[1]);
        it.mins = Learn.MINS[it.type];
        it.planId = 0;
        Store.setNum(a, "tab", 1);
        a.renderList();
        itemSheet(it, true, null, true);
        return true;
    }
}
