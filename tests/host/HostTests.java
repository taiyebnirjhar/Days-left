package com.taiyeb.daysleft;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Plain-java checks for the pure logic. Run: tests/host/run.sh */
public class HostTests {
    static int fails = 0, runs = 0;
    static void eq(String name, Object got, Object want) {
        runs++;
        if (!Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    public static void main(String[] a) {
        // ---- Version ----
        eq("v 3.17<3.18", Version.compare("3.17", "3.18"), -1);
        eq("v 3.9<3.18", Version.compare("3.9", "3.18"), -1);
        eq("v 3.18=v3.18", Version.compare("3.18", "v3.18"), 0);
        eq("v 3.18.1>3.18", Version.compare("3.18.1", "3.18"), 1);
        eq("v 4.0>3.99", Version.compare("4.0", "3.99"), 1);
        eq("v suffix", Version.compare("3.18-beta", "3.18"), 0);
        eq("v empty", Version.compare("", "3.1"), -1);

        // ---- Calc ----
        LocalDateTime now = LocalDateTime.of(2026, 1, 10, 12, 0);
        Cd c = new Cd();
        c.start = LocalDate.of(2026, 1, 1).toEpochDay();
        c.target = LocalDate.of(2026, 1, 20).toEpochDay();
        Calc k = Calc.of(c, now);
        eq("calc days", k.days, 9L);
        eq("calc hours", k.hours, 12L);
        eq("calc not passed", k.passed, false);
        Cd past = new Cd();
        past.start = LocalDate.of(2025, 12, 1).toEpochDay();
        past.target = LocalDate.of(2026, 1, 5).toEpochDay();
        Calc kp = Calc.of(past, now);
        eq("calc passed", kp.passed, true);
        eq("calc ago", kp.agoDays, 5L);
        eq("calc done", kp.done(), true);
        past.end = 1;
        eq("calc countUp", Calc.of(past, now).countUp(), true);
        Cd td = new Cd();
        td.start = LocalDate.of(2026, 1, 1).toEpochDay();
        td.target = LocalDate.of(2026, 1, 10).toEpochDay();
        eq("calc today", Calc.of(td, now).today, true);
        Cd y = new Cd();
        y.yearly = true;
        y.start = LocalDate.of(2020, 3, 1).toEpochDay();
        y.target = LocalDate.of(2020, 3, 1).toEpochDay();
        eq("yearly next", Calc.of(y, now).target, LocalDate.of(2026, 3, 1));
        y.target = LocalDate.of(2020, 1, 5).toEpochDay();
        eq("yearly rolls to next year", Calc.of(y, now).target, LocalDate.of(2027, 1, 5));
        eq("plural 1", Calc.plural(1, "day"), "1 day");
        eq("plural 2", Calc.plural(2, "day"), "2 days");
        Cd leap = new Cd();
        leap.start = LocalDate.of(2028, 2, 1).toEpochDay();
        leap.target = LocalDate.of(2028, 3, 1).toEpochDay();
        eq("leap month days", Calc.of(leap, LocalDateTime.of(2028, 2, 1, 0, 0)).days, 29L);

        // ---- PageMatcher / BookIndex / Aligner / Alignment ----
        String[] pages = {
            "the quick brown fox jumps over the lazy dog near the old river bank today",
            "a journey of a thousand miles begins with a single step on the long road home",
            "we hold these truths to be self evident that all people are created equal under law"
        };
        PageMatcher pm = new PageMatcher(Arrays.asList(pages));
        PageMatcher.Result r = pm.match("a journey of a thousand miles begins with a single step");
        eq("match page", r.page, 1);
        eq("match nothing", pm.match("zebra quantum pickle orbit lantern velvet").page == 1, false);
        eq("words lowercases", PageMatcher.words("Hello, World!"), Arrays.asList("hello", "world"));

        List<String> all = new ArrayList<>();
        int[] start = new int[pages.length + 1];
        for (int p = 0; p < pages.length; p++) { start[p] = all.size(); all.addAll(PageMatcher.words(pages[p])); }
        start[pages.length] = all.size();
        byte[] flag = new byte[all.size()];
        flag[all.size() - 1] = 1;
        BookIndex bi = new BookIndex(all.toArray(new String[0]), start, new float[all.size() * 4], flag);
        eq("book count", bi.count(), all.size());
        eq("pageOf first", bi.pageOf(0), 0);
        eq("pageOf mid", bi.pageOf(start[1] + 2), 1);
        eq("pageOf last", bi.pageOf(all.size() - 1), 2);
        eq("pageTexts size", bi.pageTexts().size(), 3);

        String[] spoken = PageMatcher.words(pages[2]).toArray(new String[0]);
        double[] times = new double[spoken.length];
        for (int i = 0; i < times.length; i++) times[i] = 100 + i * 0.4;
        Aligner.Out out = new Aligner(bi).align(spoken, times, 0, all.size());
        eq("align found", out != null, true);
        if (out != null) {
            eq("align first pos", out.pos[0], start[2]);
            eq("align monotonic", isInc(out.pos), true);
        }
        eq("align too short", new Aligner(bi).align(new String[]{"a", "b"}, new double[]{0, 1}, 0, 10), null);

        List<int[]> pw = new ArrayList<>(); List<Double> pt = new ArrayList<>();
        int[] wp = {0, 10, 500, 20, 30}; double[] tp = {0, 10, 12, 20, 30};   // the 500 is a wrong match
        for (int i = 0; i < wp.length; i++) { pw.add(new int[]{wp[i]}); pt.add(tp[i]); }
        Alignment al = Alignment.fromPoints(pw, pt);
        eq("alignment drops the outlier, keeps the good points after it", al.size(), 4);
        eq("alignment timeOfWord interp", Math.round(al.timeOfWord(5)), 5L);
        eq("alignment wordAtTime", al.wordAtTime(10), 10);
        // points arriving out of time order (backward / gap-filling sync) are sorted
        List<int[]> pw2 = new ArrayList<>(); List<Double> pt2 = new ArrayList<>();
        int[] w2 = {30, 0, 20, 10}; double[] t2 = {30, 0, 20, 10};
        for (int i = 0; i < w2.length; i++) { pw2.add(new int[]{w2[i]}); pt2.add(t2[i]); }
        eq("alignment sorts unordered points", Alignment.fromPoints(pw2, pt2).size(), 4);
        eq("alignment interpolates across sparse gap", Math.round(Alignment.fromPoints(pw2, pt2).timeOfWord(15)), 15L);

        System.out.println(runs + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean isInc(int[] a) { for (int i = 1; i < a.length; i++) if (a[i] <= a[i - 1]) return false; return true; }
}
