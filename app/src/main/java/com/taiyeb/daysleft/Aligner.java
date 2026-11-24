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
        return o;
    }
}
