package com.taiyeb.daysleft;

import java.util.*;

/**
 * Lines a stretch of transcribed speech up with the words of the book.
 * Each pair of neighbouring words that appears in the book "votes" for where the speech is.
 * The votes must agree on one place (a diagonal), then the pairs are kept in reading order.
 */
class Aligner {
    static class Out {
        int[] pos;        // book word index
        double[] time;    // seconds from the start of the audio
        int votes;
        int end;          // book word just after the last match
    }

    private final BookIndex bi;
    /** The narrator's usual words per second, if known: a match whose implied pace is far from it is a false match. 0 = unknown. */
    volatile double paceHint;
    Aligner(BookIndex bi) { this.bi = bi; }

    /** words[i] was spoken at times[i]. Only book positions in [lo, hi] are considered. */
    Out align(String[] words, double[] times, int lo, int hi) {
        int m = words.length;
        if (m < 8) return null;
        Map<Long, int[]> idx = bi.bigrams();
        int[][] hits = new int[m][];
        Map<Integer, Integer> votes = new HashMap<>();
        for (int i = 0; i + 1 < m; i++) {
            int[] ps = idx.get(PageMatcher.key(words[i], words[i + 1]));
            if (ps == null || ps.length > 25) continue;
            hits[i] = ps;
            for (int p : ps) if (p >= lo && p <= hi) votes.merge(Math.floorDiv(p - i, 6), 1, Integer::sum);
        }
        int best = Integer.MIN_VALUE, bestV = 0;
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            int b = e.getKey();
            int v = e.getValue() + votes.getOrDefault(b - 1, 0) + votes.getOrDefault(b + 1, 0);
            if (v > bestV) { bestV = v; best = b; }
        }
        if (bestV < 4) return null;
        int center = best * 6 + 3;
        // candidate pairs near that diagonal
        List<int[]> cand = new ArrayList<>();   // {i, pos}
        for (int i = 0; i + 1 < m; i++) {
            if (hits[i] == null) continue;
            int pick = -1;
            for (int p : hits[i]) if (p >= lo && p <= hi && Math.abs(p - i - center) <= 14 && (pick < 0 || Math.abs(p - i - center) < Math.abs(pick - i - center))) pick = p;
            if (pick >= 0) cand.add(new int[]{i, pick});
        }
        // longest run that keeps increasing in book position
        int c = cand.size();
        if (c < 3) return null;
        int[] tail = new int[c], prev = new int[c];
        int len = 0;
        for (int k = 0; k < c; k++) {
            int lo2 = 0, hi2 = len;
            while (lo2 < hi2) { int mid = (lo2 + hi2) >>> 1; if (cand.get(tail[mid])[1] < cand.get(k)[1]) lo2 = mid + 1; else hi2 = mid; }
            prev[k] = lo2 > 0 ? tail[lo2 - 1] : -1;
            tail[lo2] = k;
            if (lo2 == len) len++;
        }
        if (len < 3) return null;
        int[] seq = new int[len];
        for (int k = tail[len - 1], at = len - 1; k >= 0 && at >= 0; k = prev[k], at--) seq[at] = k;
        Out o = new Out();
        o.pos = new int[len];
        o.time = new double[len];
        for (int k = 0; k < len; k++) { int[] pr = cand.get(seq[k]); o.pos[k] = pr[1]; o.time[k] = times[pr[0]]; }
        o.votes = bestV;
        o.end = o.pos[len - 1] + 2;
        return consistent(o);
    }

    /**
     * Over a few seconds the narrator's pace is nearly constant, so the matched words must lie on a straight line
     * (time against book position). A common word pair matched at the wrong place does not: drop it. Uses the median
     * slope (Theil-Sen), so a few wrong points cannot drag the line. Null if too little is left or the pace is absurd.
     */
    Out consistent(Out o) { return consistent(o, paceHint); }

    static Out consistent(Out o, double hint) {
        int n = o.pos.length;
        if (n < 4) return n >= 3 ? o : null;
        double[] sl = new double[n * (n - 1) / 2];
        int c = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++)
            if (o.pos[j] > o.pos[i]) sl[c++] = (o.time[j] - o.time[i]) / (o.pos[j] - o.pos[i]);
        if (c < 3) return null;
        double[] ss = Arrays.copyOf(sl, c);
        Arrays.sort(ss);
        double m = ss[c / 2];
        if (m < 0.16 || m > 0.85) return null;      // outside 1.2..6 words per second: not a person reading this book (a false match)
        if (hint > 0 && (1 / m < 0.5 * hint || 1 / m > 1.8 * hint)) return null;   // far from this narrator's pace: a false match
        double[] ic = new double[n];
        for (int i = 0; i < n; i++) ic[i] = o.time[i] - m * o.pos[i];
        double[] sc = ic.clone();
        Arrays.sort(sc);
        double b = sc[n / 2];
        int keep = 0;
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) if (Math.abs(ic[i] - b) <= 1.5) { ok[i] = true; keep++; }
        if (keep < 3) return null;
        if (keep == n) return o;
        Out r = new Out();
        r.pos = new int[keep]; r.time = new double[keep];
        for (int i = 0, k = 0; i < n; i++) if (ok[i]) { r.pos[k] = o.pos[i]; r.time[k] = o.time[i]; k++; }
        r.votes = o.votes;
        r.end = r.pos[keep - 1] + 2;
        return r;
    }
}
