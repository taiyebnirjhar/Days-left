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
    static final double MAX_GAP_S = 45;     // do not trust a stretch with no match for longer than this
    static final int MAX_GAP_W = 450;

    int size() { return aw.length; }
    double firstTime() { return aw.length == 0 ? 0 : at[0]; }
    double lastTime() { return aw.length == 0 ? 0 : at[at.length - 1]; }

    static Alignment fromPoints(List<int[]> w, List<Double> t) {
        Alignment a = new Alignment();
        int n = w.size();
        // keep only points that increase in both book position and time
        int[] ww = new int[n]; double[] tt = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int pw = w.get(i)[0]; double pt = t.get(i);
            if (k > 0 && (pw <= ww[k - 1] || pt <= tt[k - 1])) continue;
            ww[k] = pw; tt[k] = pt; k++;
        }
        a.aw = Arrays.copyOf(ww, k);
        a.at = Arrays.copyOf(tt, k);
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

    /** Book word being spoken at second t, or -1 if unknown. */
    int wordAtTime(double t) {
        int i = floorT(t);
        if (i < 0) return -1;
        if (i + 1 >= aw.length) return t - at[i] < 6 ? aw[i] : -1;
        if (aw[i + 1] - aw[i] > MAX_GAP_W || at[i + 1] - at[i] > MAX_GAP_S) return t - at[i] < 4 ? aw[i] : -1;
        return aw[i] + (int) ((t - at[i]) * (aw[i + 1] - aw[i]) / (at[i + 1] - at[i]));
    }
}
