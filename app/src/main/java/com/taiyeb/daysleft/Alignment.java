package com.taiyeb.daysleft;

import java.io.*;
import java.util.*;

/**
 * The finished map between the audio and the book: "at this second the narrator is on this word".
 * Looking things up is a binary search over two sorted arrays, so it takes microseconds.
 */
class Alignment {
    private int[] aw = new int[0];
    private double[] at = new double[0];
    private double typ;   // the narrator's usual words per second (median over close probe pairs); 0 if unknown
    static final double MAX_GAP_S = 200;    // sparse sync leaves gaps up to ~165 s; beyond this, do not trust interpolation
    static final int MAX_GAP_W = 900;

    int size() { return aw.length; }
    double firstTime() { return aw.length == 0 ? 0 : at[0]; }
    double lastTime() { return aw.length == 0 ? 0 : at[at.length - 1]; }

    /** Median words per second between probes that are close together (0.5 to 4 s apart): the pace without long pauses. */
    static double typicalRate(int[] w, double[] t) {
        double[] r = new double[Math.max(0, w.length - 1)];
        int c = 0;
        for (int i = 0; i + 1 < w.length; i++) {
            double dt = t[i + 1] - t[i];
            int dw = w[i + 1] - w[i];
            if (dt >= 0.5 && dt <= 4 && dw >= 2) r[c++] = dw / dt;
        }
        if (c < 3) return 0;
        double[] x = java.util.Arrays.copyOf(r, c);
        java.util.Arrays.sort(x);
        return x[c / 2];
    }

    /** True when the words between two probes are far too few for the time between them: a pause or music sits in that gap. */
    boolean pauseBetween(int i) {
        double dt = at[i + 1] - at[i];
        return typ > 0 && dt > 4 && (aw[i + 1] - aw[i]) / dt < 0.55 * typ;
    }

    int[] words() { return aw.clone(); }
    double[] times() { return at.clone(); }

    /**
     * Points may arrive in any order (the sync works outward from where the user is). Sort by time, then keep the
     * longest run that also rises in book position, so one wrong point can't throw away the good ones after it.
     */
    static Alignment fromPoints(List<int[]> w, List<Double> t) {
        Alignment a = new Alignment();
        int n = w.size();
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (x, y) -> Double.compare(t.get(x), t.get(y)));
        int[] cw = new int[n]; double[] ct = new double[n];
        int c = 0;
        for (int i = 0; i < n; i++) {
            double pt = t.get(ord[i]);
            if (c > 0 && pt <= ct[c - 1]) continue;       // same moment twice
            cw[c] = w.get(ord[i])[0]; ct[c] = pt; c++;
        }
        // longest strictly increasing subsequence of book positions
        int[] tail = new int[c], prev = new int[c];
        int len = 0;
        for (int i = 0; i < c; i++) {
            int lo = 0, hi = len;
            while (lo < hi) { int m = (lo + hi) >>> 1; if (cw[tail[m]] < cw[i]) lo = m + 1; else hi = m; }
            prev[i] = lo > 0 ? tail[lo - 1] : -1;
            tail[lo] = i;
            if (lo == len) len++;
        }
        a.aw = new int[len]; a.at = new double[len];
        for (int i = tail.length > 0 && len > 0 ? tail[len - 1] : -1, k = len - 1; i >= 0 && k >= 0; i = prev[i], k--) { a.aw[k] = cw[i]; a.at[k] = ct[i]; }
        a.typ = typicalRate(a.aw, a.at);
        return a;
    }

    static void append(File f, int[] pos, double[] time) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f, true)))) {
            for (int i = 0; i < pos.length; i++) { o.writeInt(pos[i]); o.writeFloat((float) time[i]); }
        }
    }

    static Alignment load(File f) {
        List<int[]> w = new ArrayList<>();
        List<Double> t = new ArrayList<>();
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
                while (true) { int p = in.readInt(); float s = in.readFloat(); w.add(new int[]{p}); t.add((double) s); }
            } catch (EOFException e) { /* normal end */ } catch (IOException ignored) {}
        }
        return fromPoints(w, t);
    }

    /** Index of the last point with aw <= j, or -1 */
    private int floorW(int j) {
        int lo = 0, hi = aw.length - 1, r = -1;
        while (lo <= hi) { int m = (lo + hi) >>> 1; if (aw[m] <= j) { r = m; lo = m + 1; } else hi = m - 1; }
        return r;
    }

    private int floorT(double t) {
        int lo = 0, hi = at.length - 1, r = -1;
        while (lo <= hi) { int m = (lo + hi) >>> 1; if (at[m] <= t) { r = m; lo = m + 1; } else hi = m - 1; }
        return r;
    }

    /** Second at which book word j is spoken, or -1 if that part isn't mapped (yet). */
    double timeOfWord(int j) {
        int i = floorW(j);
        if (i < 0 || i + 1 >= aw.length) return i >= 0 && aw[i] == j ? at[i] : -1;
        if (aw[i + 1] - aw[i] > MAX_GAP_W || at[i + 1] - at[i] > MAX_GAP_S) return aw[i] == j ? at[i] : -1;
        return at[i] + (j - aw[i]) * (at[i + 1] - at[i]) / (double) (aw[i + 1] - aw[i]);
    }

    /**
     * How far apart (seconds) the two probes around t are; the interpolation between them is only as good as this is small.
     * Large when t is outside the mapped part. A sentence highlight needs this to be small (about 25 s or less).
     */
    double gapAt(double t) {
        int i = floorT(t);
        if (i < 0) return Double.MAX_VALUE;
        if (i + 1 >= at.length) return t - at[i] < 6 ? 0 : Double.MAX_VALUE;
        if (pauseBetween(i)) return Double.MAX_VALUE;   // somewhere in here is a pause: where the narrator is cannot be told by interpolating
        return at[i + 1] - at[i];
    }

    /** Book word being spoken at second t, or -1 if unknown. */
    int wordAtTime(double t) {
        int i = floorT(t);
        if (i < 0) return -1;
        if (i + 1 >= aw.length) return t - at[i] < 6 ? aw[i] : -1;
        if (aw[i + 1] - aw[i] > MAX_GAP_W || at[i + 1] - at[i] > MAX_GAP_S) return t - at[i] < 4 ? aw[i] : -1;
        return aw[i] + (int) ((t - at[i]) * (aw[i + 1] - aw[i]) / (at[i + 1] - at[i]));
    }
}
