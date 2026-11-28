package com.taiyeb.daysleft;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The clock of a read-aloud book. A book that the phone reads has no audio file, so it gets a nominal timeline made from its own text:
 * every word takes 1/WPS seconds and every sentence is followed by a short pause. The numbers are only ever compared with each other (time
 * label, +-15 s, pins, Play page, Find page, the marker), so they do not depend on the voice that speaks. The timeline is written as an
 * ordinary alignment, which is why every audiobook feature works on it unchanged and the book counts as fully synced.
 */
final class Timeline {
    static final double WPS = 2.6, PAUSE = 0.4;
    final BookIndex bi;
    final int[] ss;            // first word of each sentence
    final double[] start, end;  // seconds
    final double total;

    Timeline(BookIndex b) {
        bi = b;
        if (b.sentStart == null || b.sentStart.length == 0) { ss = new int[]{0}; } else ss = b.sentStart;
        int n = ss.length;
        start = new double[n]; end = new double[n];
        double t = 0;
        for (int i = 0; i < n; i++) {
            int words = (i + 1 < n ? ss[i + 1] : b.count()) - ss[i];
            start[i] = t;
            end[i] = t + words / WPS;
            t = end[i] + PAUSE;
        }
        total = n == 0 ? 0 : end[n - 1];
    }

    int sentenceOf(int word) {
        int lo = 0, hi = ss.length - 1;
        while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (ss[m] <= word) lo = m; else hi = m - 1; }
        return lo;
    }

    int sentenceAt(double t) {
        int lo = 0, hi = start.length - 1;
        while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (start[m] <= t) lo = m; else hi = m - 1; }
        return lo;
    }

    double timeOfWord(int word) {
        int s = sentenceOf(Math.max(0, Math.min(bi.count() - 1, word)));
        return Math.min(end[s], start[s] + (word - ss[s]) / WPS);
    }

    /** The word being spoken at second t (the first word of the sentence in a pause). */
    int wordAt(double t) {
        t = Math.max(0, Math.min(total, t));
        int s = sentenceAt(t);
        int last = (s + 1 < ss.length ? ss[s + 1] : bi.count()) - 1;
        int w = ss[s] + (int) Math.max(0, (t - start[s]) * WPS);
        return Math.max(ss[s], Math.min(last, w));
    }

    /** One point at the start and one at the end of every sentence. */
    void writeAlignment(File f) throws IOException {
        f.delete();
        int n = ss.length;
        int[] pos = new int[n * 2];
        double[] tm = new double[n * 2];
        for (int i = 0; i < n; i++) {
            int last = (i + 1 < n ? ss[i + 1] : bi.count()) - 1;
            pos[2 * i] = ss[i]; tm[2 * i] = start[i];
            pos[2 * i + 1] = Math.max(ss[i] + 1, last); tm[2 * i + 1] = end[i];
        }
        List<Integer> p2 = new ArrayList<>(); List<Double> t2 = new ArrayList<>();
        int lastW = -1; double lastT = -1;
        for (int i = 0; i < pos.length; i++) {   // strictly rising words and times only
            if (pos[i] <= lastW || tm[i] <= lastT) continue;
            p2.add(pos[i]); t2.add(tm[i]); lastW = pos[i]; lastT = tm[i];
        }
        int[] fp = new int[p2.size()]; double[] ft = new double[p2.size()];
        for (int i = 0; i < fp.length; i++) { fp[i] = p2.get(i); ft[i] = t2.get(i); }
        Alignment.append(f, fp, ft);
    }
}
