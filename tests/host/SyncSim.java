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

    public static void main(String[] a) {
        double hours = a.length > 0 ? Double.parseDouble(a[0]) : 9.7;
        double sttSpeed = a.length > 1 ? Double.parseDouble(a[1]) : 3.0;   // 3x real time = tiny model, 1x = base
        int bad = 0;
        for (long seed = 1; seed <= 5; seed++) {
            build(seed, hours, sttSpeed);
            int[] pageStart = {0, N};
            BookIndex bi = new BookIndex(book, pageStart, new float[N * 4], new byte[N]);
            Aligner al = new Aligner(bi);
            double start = 0.37 * total;
            SyncPlanner p = new SyncPlanner(total, start, N, new int[0], new double[0]);
            double listened = 0; int probes = 0, fails = 0;
            boolean usable = false, ext = false;
            StringBuilder log = new StringBuilder();
            SyncPlanner.Req r;
            while ((r = p.next()) != null && probes < 5000) {
                List<String> w = new ArrayList<>(); List<Double> t = new ArrayList<>();
                hear(r.start, r.len, w, t);
                double[] tt = new double[t.size()]; for (int i = 0; i < tt.length; i++) tt[i] = t.get(i);
                Aligner.Out o = al.align(w.toArray(new String[0]), tt, r.lo, r.hi);
                int kept = p.feed(r, o);
                listened += r.len; probes++;
                if (o == null) fails++;
                if (!ext && r.kind == 3) {
                    ext = true;
                    double[] ec = accuracy(snapshot(p));
                    log.append(String.format("  end of sparse pass: %d probes, %.1f min listened (%.1f%%, ~%.1f min on phone): mapped %.1f%%, error median %.2f s, 95th pct %.2f s%n", probes - 1, (listened - r.len) / 60, (listened - r.len) * 100 / total, (listened - r.len) / sttSpeed / 60, ec[0], ec[1], ec[2]));
                }
                if (!usable && p.anchorCount() > 0) {
                    usable = true;
                    double[] ac = accuracy(snapshot(p));
                    log.append(String.format("  first anchors after %d probe(s), %.0f s listened (~%.0f s on the phone): near-playhead ok\n", probes, listened, listened / sttSpeed));
                }
            }
            Alignment fin = snapshot(p);
            double[] ac = accuracy(fin);
            System.out.printf("seed %d: %d probes, %.1f min listened of %.1f h (%.1f%%), phone time ~%.1f min @%.0fx, failed probes %d, lost=%b%n",
                    seed, probes, listened / 60, total / 3600, listened * 100 / total, listened / sttSpeed / 60, sttSpeed, fails, p.lost);
            System.out.print(log);
            System.out.printf("  final: mapped %.1f%% of timeline, error median %.2f s, 95th pct %.2f s%n", ac[0], ac[1], ac[2]);
            if (ac[0] < 95 || ac[2] > 6 || p.lost) bad++;
        }
        System.out.println(bad == 0 ? "ALL SIM RUNS OK" : bad + " SIM RUNS BAD");
        System.exit(bad == 0 ? 0 : 1);
    }
}
