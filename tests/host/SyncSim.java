package com.taiyeb.daysleft;

import java.util.*;

/** Simulates the whole sync (fake narrator + noisy fake speech recogniser) and reports cost and accuracy. */
public class SyncSim {
    static Random rnd;
    static String[] book; static double[] tw; static int N;
    static double total;

    static void build(long seed, double hours, double sttSpeed) {
        rnd = new Random(seed);
        N = (int) (hours * 3600 * 2.6);
        book = new String[N]; tw = new double[N];
        int V = 8000;
        double[] cum = new double[V]; double s = 0;
        for (int i = 0; i < V; i++) { s += 1.0 / (i + 8); cum[i] = s; }
        for (int i = 0; i < N; i++) { double x = rnd.nextDouble() * s; int lo = 0, hi = V - 1; while (lo < hi) { int m = (lo + hi) / 2; if (cum[m] < x) lo = m + 1; else hi = m; } book[i] = "w" + lo; }
        double t = 30;                       // 30 s of intro music
        double pace = 2.6;
        for (int i = 0; i < N; i++) {
            if (i % 400 == 0) pace = Math.max(1.8, Math.min(3.4, pace + rnd.nextGaussian() * 0.25));
            t += 1.0 / pace * (0.7 + 0.6 * rnd.nextDouble());
            if (i % 14 == 13) t += 0.5 + rnd.nextDouble() * 0.6;      // sentence pause
            if (i % 2500 == 2499) t += 8;                              // chapter gap
            tw[i] = t;
        }
        total = t + 20;
    }

    /** Fake speech-to-text for audio [start, start+len]. */
    static void hear(double start, double len, List<String> w, List<Double> t) {
        int i = Arrays.binarySearch(tw, start);
        if (i < 0) i = -i - 1;
        for (; i < N && tw[i] < start + len; i++) {
            if (rnd.nextDouble() < 0.05) continue;
            String x = rnd.nextDouble() < 0.12 ? "w" + rnd.nextInt(8000) : book[i];
            w.add(x); t.add(tw[i] + rnd.nextGaussian() * 0.4);
        }
    }

    static int trueWordAt(double t) { int i = Arrays.binarySearch(tw, t); if (i < 0) i = -i - 1; return Math.min(i, N - 1); }

    static double[] accuracy(Alignment al) {
        Random r = new Random(5);
        int n = 3000, known = 0; double errSum = 0; List<Double> errs = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double t = 30 + r.nextDouble() * (total - 50);
            int w = al.wordAtTime(t);
            if (w < 0) continue;
            known++;
            double est = al.timeOfWord(Math.max(0, Math.min(N - 1, trueWordAt(t))));
            // error in seconds: where the app thinks the word is spoken vs. where it truly is
            double e = est < 0 ? 99 : Math.abs(est - tw[trueWordAt(t)]);
            errs.add(e);
        }
        Collections.sort(errs);
        double med = errs.isEmpty() ? -1 : errs.get(errs.size() / 2), p95 = errs.isEmpty() ? -1 : errs.get((int) (errs.size() * 0.95));
        return new double[]{known * 100.0 / n, med, p95};
    }

    static Alignment snapshot(SyncPlanner p) {
        List<int[]> w = new ArrayList<>(); List<Double> t = new ArrayList<>();
        for (Map.Entry<Double, Integer> e : p.anchors.entrySet()) { w.add(new int[]{e.getValue()}); t.add(e.getKey()); }
        return Alignment.fromPoints(w, t);
    }

    static int calls;       // speech-model calls: the real cost unit (one call = one 30 s window, about 10 s on the phone with the tiny model)
    static double listened;

    /** Runs one batch (up to 3 short pieces spliced into one model call). Returns false when there is nothing to do. */
    static boolean step(SyncPlanner p, Aligner al) {
        List<SyncPlanner.Req> b = p.nextBatch(3);
        if (b.isEmpty()) return false;
        calls++;
        for (SyncPlanner.Req r : b) {
            List<String> w = new ArrayList<>(); List<Double> t = new ArrayList<>();
            hear(r.start, r.len, w, t);
            double[] tt = new double[t.size()]; for (int i = 0; i < tt.length; i++) tt[i] = t.get(i);
            p.feed(r, al.align(w.toArray(new String[0]), tt, r.lo, r.hi));
            listened += r.len;
        }
        return true;
    }

    public static void main(String[] a) {
        double hours = a.length > 0 ? Double.parseDouble(a[0]) : 9.7;
        double callSec = a.length > 1 ? Double.parseDouble(a[1]) : 10;   // seconds per model call on the phone: ~10 tiny, ~26 base
        int bad = 0;

        // ---- 1. window sync around the playhead, then play on and jump around ----
        for (long seed = 1; seed <= 3; seed++) {
            build(seed, hours, callSec);
            int[] ps = {0, N};
            Aligner al = new Aligner(new BookIndex(book, ps, new float[N * 4], new byte[N]));
            double pos = 0.37 * total;
            SyncPlanner p = new SyncPlanner(total, pos, N, new int[0], new double[0]);
            p.reach = Double.parseDouble(System.getProperty("reach", "600"));
            calls = 0; listened = 0;
            int firstCalls = -1;
            while (step(p, al)) { if (firstCalls < 0 && p.anchorCount() > 0) firstCalls = calls; if (calls > 3000) break; }
            double[] ac = accuracy(snapshot(p));
            double[] wa = windowAcc(snapshot(p), pos);
            System.out.printf("window sync seed %d: first anchors after %d call(s) (~%.0f s); whole +-10 min window after %d calls (~%.0f s); near playhead mapped %.0f%%, worst error %.1f s%n",
                    seed, firstCalls, firstCalls * callSec, calls, calls * callSec, wa[0], wa[1]);
            if (wa[0] < 95 || wa[1] > 10) bad++;
        }

        // ---- 1b. two rings, like the service: ring +-150 s first, then widen to +-600 s (this once silently did nothing) ----
        build(31, hours, callSec);
        {
            int[] ps1 = {0, N};
            Aligner al1 = new Aligner(new BookIndex(book, ps1, new float[N * 4], new byte[N]));
            double pos = 0.37 * total;
            SyncPlanner p = new SyncPlanner(total, pos, N, new int[0], new double[0]);
            p.reach = 150;
            calls = 0; listened = 0;
            while (step(p, al1)) if (calls > 3000) break;
            int ringCalls = calls;
            double[] ring = windowAcc(snapshot(p), pos);
            p.widen(600);
            while (step(p, al1)) if (calls > 3000) break;
            double[] wide = windowAcc(snapshot(p), pos);
            System.out.printf("two rings: ring +-150 s after %d calls; widened to +-600 s after %d more calls; mapped within +-600 s: %.0f%% (was %.0f%% after the ring)%n", ringCalls, calls - ringCalls, wide[0], ring[0]);
            if (wide[0] < 95) bad++;
        }

        // ---- 1c. listening along: the dense zone must travel with the playhead so the sentence highlight is there and right ----
        build(41, hours, callSec);
        {
            int[] ps2 = {0, N};
            Aligner al2 = new Aligner(new BookIndex(book, ps2, new float[N * 4], new byte[N]));
            double playhead = 0.37 * total;
            SyncPlanner p = new SyncPlanner(total, playhead, N, new int[0], new double[0]);
            p.reach = 150;
            calls = 0; listened = 0;
            while (step(p, al2)) if (calls > 3000) break;
            int ringCalls = calls;
            p.widen(600); p.dense = Boolean.parseBoolean(System.getProperty("dense", "true"));
            int shown = 0, total2 = 0, wrong = 0; double worstShown = 0; List<Double> errs = new ArrayList<>();
            int readyAfter = -1;
            for (int i = 0; i < 80; i++) {
                p.recenter(playhead);
                step(p, al2);                      // one speech call; the audio plays on meanwhile
                playhead += callSec;
                p.recenter(playhead);
                Alignment fa = snapshot(p);
                double gap = fa.gapAt(playhead);
                boolean show = gap <= 25;
                if (show && readyAfter < 0) readyAfter = i;
                if (i >= 10) {
                    total2++;
                    if (show) {
                        shown++;
                        int w = trueWordAt(playhead);
                        double est = fa.timeOfWord(w);
                        double e = est < 0 ? 99 : Math.abs(est - tw[w]);
                        errs.add(e); worstShown = Math.max(worstShown, e);
                        if (e > 2.5) wrong++;
                    }
                }
            }
            Collections.sort(errs);
            double med = errs.isEmpty() ? -1 : errs.get(errs.size() / 2), p95 = errs.isEmpty() ? -1 : errs.get((int) (errs.size() * 0.95));
            System.out.printf("listening along (%d calls for the ring, then 80 calls while playing): highlight available %d%% of the time, wrong (>2.5 s) %d of %d shown, median error %.2f s, 95th pct %.2f s, worst %.1f s%n",
                    ringCalls, total2 == 0 ? 0 : shown * 100 / total2, wrong, shown, med, p95, worstShown);
            if (total2 == 0 || shown * 100 / total2 < 90 || wrong * 100 > Math.max(1, shown) * 3) bad++;
        }

        // ---- 2. Play this page: find a far-away page from nothing (no sync data at all) ----
        Random r = new Random(11);
        int ok = 0, tests = 0, maxCalls = 0; double sumCalls = 0, worst = 0;
        for (long seed = 1; seed <= 3; seed++) {
            build(100 + seed, hours, callSec);
            int[] ps = {0, N};
            Aligner al = new Aligner(new BookIndex(book, ps, new float[N * 4], new byte[N]));
            for (int k = 0; k < 20; k++) {
                int target = (int) (r.nextDouble() * (N - 400)) + 200;
                SyncPlanner p = new SyncPlanner(total, 0, N, new int[0], new double[0]);
                p.setGoal(target);
                calls = 0; listened = 0;
                while (p.goalTime == -1 && step(p, al)) if (calls > 12) break;
                tests++;
                double got = p.goalTime;
                if (got >= 0) {
                    double err = Math.abs(got - tw[target]);
                    if (err <= 4) ok++;
                    worst = Math.max(worst, err);
                    sumCalls += calls; maxCalls = Math.max(maxCalls, calls);
                }
            }
        }
        System.out.printf("play page from nothing: %d/%d within 4 s of the truth, %.1f calls on average (~%.0f s), worst %d calls (~%.0f s), worst error %.1f s%n",
                ok, tests, sumCalls / Math.max(1, ok), sumCalls / Math.max(1, ok) * callSec, maxCalls, maxCalls * callSec, worst);
        if (ok * 100 / tests < 90) bad++;

        // ---- 3. Play this page when the area around the playhead is already mapped (the usual case while listening) ----
        build(77, hours, callSec);
        int[] ps = {0, N};
        Aligner al = new Aligner(new BookIndex(book, ps, new float[N * 4], new byte[N]));
        double here = 0.37 * total;
        SyncPlanner p = new SyncPlanner(total, here, N, new int[0], new double[0]);
        p.reach = 150;
        calls = 0;
        while (step(p, al)) if (calls > 3000) break;
        p.widen(600); p.dense = true;
        while (step(p, al)) if (calls > 3000) break;
        int nearW = trueWordAt(here + 100), farW = trueWordAt(here + 420);
        p.setGoal(nearW);
        boolean instant = p.goalTime >= 0;
        double nearErr = instant ? Math.abs(p.goalTime - tw[nearW]) : -1;
        System.out.printf("play page 100 s ahead of the playhead: %s%n", instant ? "instant, error " + String.format("%.2f s", nearErr) : "NOT instant");
        p.clearGoal();
        p.setGoal(farW);
        int c0 = calls;
        while (p.goalTime == -1 && step(p, al)) if (calls - c0 > 8) break;
        System.out.printf("play page 7 min ahead (outside the dense zone): %s after %d call(s)%n", p.goalTime >= 0 ? "answered, error " + String.format("%.2f s", Math.abs(p.goalTime - tw[farW])) : "NO ANSWER", calls - c0);
        if (!instant || nearErr > 1.5 || p.goalTime < 0 || Math.abs(p.goalTime - tw[farW]) > 2.5) bad++;

        System.out.println(bad == 0 ? "ALL SIM RUNS OK" : bad + " SIM CHECKS BAD");
        System.exit(bad == 0 ? 0 : 1);
    }

    static double[] windowAcc(Alignment fa, double pos) {
        int ok = 0, n = 0; double worst = 0;
        for (int k = 0; k < 800; k++) {
            double t = pos - 600 + k * 1.5;
            if (t < 40 || t > total - 40) continue;
            n++;
            if (fa.wordAtTime(t) < 0) continue;
            double est = fa.timeOfWord(trueWordAt(t));
            if (est >= 0) { ok++; worst = Math.max(worst, Math.abs(est - tw[trueWordAt(t)])); }
        }
        return new double[]{n == 0 ? 0 : ok * 100.0 / n, worst};
    }
}
