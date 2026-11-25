package com.taiyeb.daysleft;

import java.util.*;

/**
 * Decides which few seconds of the audiobook to listen to next. Pure Java, so it is tested on the host.
 *
 * Listening (speech-to-text) costs about one second per second of audio, so hearing the whole book takes
 * hours. The narrator's pace is steady though, so a few short samples pin the book down and the stretches
 * in between are interpolated. Order of work, always resumable from the anchors already stored:
 *   1. Seed: listen where the user is (whole-book search) -> usable within one clip.
 *   2. Extend: walk forward and backward from there. The step between samples grows while the
 *      prediction keeps coming true and shrinks when it does not (adaptive stride, up to MAX_STRIDE).
 *      Both neighbours of a gap are known later, so the search window is tiny and matching is very reliable.
 *   3. Refine: halve the gaps that are still wider than DENSE_GAP, nearest to the user first.
 */
final class SyncPlanner {
    static final double PROBE = 10, LONG_PROBE = 30, DENSE_GAP = 90, MAX_STRIDE = 150, MIN_STRIDE = 20;
    static final int EXT_FAILS_WIDE = 4, EXT_FAILS_GIVE_UP = 8, SEED_TRIES = 12, GAP_TRIES = 2;

    /** What to listen to. Fill in the result with feed(). */
    static final class Req {
        double start, len;
        int lo, hi;
        boolean wide;
        int kind;           // 0 seed, 1 forward, 2 backward, 3 gap
        String why = "";
        double gapLeftT;    // kind 3: identifies the gap
        double prevT; int prevW; // kind 1/2: the anchor the prediction is measured from
    }

    final double total;
    double startSec;
    final int words;
    final TreeMap<Double, Integer> anchors = new TreeMap<>();
    private double fwdCursor, bwdEnd, fwdStride, bwdStride;
    private int fwdN, bwdN, fwdFails, bwdFails, seedTry;
    private boolean fwdDone, bwdDone, turnFwd = true, seeded;
    private final Map<Long, Integer> gapTries = new HashMap<>();
    private final Set<Long> badGaps = new HashSet<>();
    /** Only map this far (seconds) either side of the playhead; infinity = the whole book. */
    double reach = Double.POSITIVE_INFINITY;
    /** True if it stopped before the whole book was mapped (lost the place). */
    boolean lost;

    SyncPlanner(double totalSec, double startSec, int bookWords, int[] pos, double[] time) {
        this.total = totalSec;
        this.startSec = Math.max(0, Math.min(startSec, totalSec - 1));
        this.words = bookWords;
        for (int i = 0; i < pos.length; i++) anchors.put(time[i], pos[i]);
        if (!anchors.isEmpty()) { seeded = true; placeCursors(this.startSec); }
    }

    /** Put the two walking cursors at the edges of the mapped stretch that holds (or is nearest to) time t. */
    private void placeCursors(double t) {
        Double near = anchors.floorKey(t);
        Double up = anchors.ceilingKey(t);
        double c = near != null && (up == null || t - near <= up - t) ? near : up;
        double hi = c, lo = c;
        for (Double h = anchors.higherKey(hi); h != null && h - hi <= DENSE_GAP; h = anchors.higherKey(hi)) hi = h;
        for (Double l = anchors.lowerKey(lo); l != null && lo - l <= DENSE_GAP; l = anchors.lowerKey(lo)) lo = l;
        fwdCursor = hi; bwdEnd = lo;
    }

    /**
     * The playhead moved (playing, or the user jumped). Returns true if there is new work.
     * Far outside what is mapped: look again there; inside: just walk on from the nearest mapped edge.
     */
    boolean recenter(double pos) {
        pos = Math.max(0, Math.min(pos, total - 1));
        if (Math.abs(pos - startSec) < Math.min(reach * 0.4, 240) && seeded) return false;
        startSec = pos;
        fwdDone = bwdDone = false;
        fwdFails = bwdFails = 0; fwdN = bwdN = 0; fwdStride = bwdStride = 0;
        if (!anchors.isEmpty()) {
            Double a = anchors.floorKey(pos), b = anchors.ceilingKey(pos);
            boolean mapped = (a != null && pos - a <= DENSE_GAP) || (b != null && b - pos <= DENSE_GAP);
            if (mapped) { seeded = true; placeCursors(pos); }
            else { seeded = false; seedTry = 0; }
        } else { seeded = false; seedTry = 0; }
        return true;
    }

    /** {mapped seconds, window size} inside the window around the playhead; the progress the user sees. */
    double[] windowProgress() {
        double lo = Math.max(0, startSec - reach), hi = Math.min(total, startSec + reach);
        double covered = 0, pt = -1;
        for (Double t : anchors.subMap(lo, true, hi, true).keySet()) { if (pt >= 0 && t - pt <= DENSE_GAP) covered += t - pt; pt = t; }
        return new double[]{covered, Math.max(1, hi - lo)};
    }

    int anchorCount() { return anchors.size(); }

    /** Words per second, from what is known so far (or the book average). */
    double rate() {
        if (anchors.size() >= 2) {
            double dt = anchors.lastKey() - anchors.firstKey();
            if (dt > 30) { double r = (anchors.lastEntry().getValue() - anchors.firstEntry().getValue()) / dt; if (r > 0.5 && r < 8) return r; }
        }
        return Math.max(0.5, words / Math.max(1, total));
    }

    /** Seconds of the audio that are not mapped well enough yet. */
    double unresolved() {
        if (!Double.isInfinite(reach)) { double[] w = windowProgress(); return w[1] - Math.min(w[0], w[1]); }
        if (!seeded) return total;
        double lo = bwdDone ? 0 : anchors.firstKey(), hi = fwdDone ? total : anchors.lastKey();
        double u = lo + (total - hi);
        double pt = -1;
        for (double t : anchors.keySet()) { if (pt >= 0 && t - pt > DENSE_GAP) u += t - pt; pt = t; }
        return Math.max(0, Math.min(total, u));
    }

    /** Next piece to listen to, or null when finished. */
    Req next() {
        if (!seeded) return seed();
        for (int tries = 0; tries < 2; tries++) {
            boolean f = turnFwd;
            if (f && fwdDone) f = false; else if (!f && bwdDone) f = true;
            if (f && fwdDone || !f && bwdDone) break;   // both ends done
            turnFwd = !f;
            Req r = f ? forward() : backward();
            if (r != null) return r;
        }
        return gap();
    }

    private Req seed() {
        if (seedTry >= SEED_TRIES) { lost = true; return null; }
        int n = seedTry++;
        // user's place, then alternating further away (intro music, silence, a skipped part)
        double off = ((n + 1) / 2) * 60.0 * (n % 2 == 1 ? 1 : -1);
        double s = startSec + (n == 0 ? 0 : off);
        if (s < 0 || s > total - 10) s = Math.max(0, Math.min(total - 10, startSec + Math.abs(off)));
        Req r = new Req();
        r.kind = 0; r.start = s; r.len = Math.min(n < 2 ? 20 : LONG_PROBE, total - s);
        r.lo = 0; r.hi = words; r.wide = true; r.why = "seed #" + (n + 1);
        return r;
    }

    private Req forward() {
        skipMappedAhead();
        Map.Entry<Double, Integer> ref = anchors.floorEntry(fwdCursor);
        if (ref == null) ref = anchors.firstEntry();
        double lastT = ref.getKey();
        int lastW = ref.getValue();
        if (fwdCursor >= total - 8 || fwdCursor > startSec + reach) { fwdDone = true; return null; }
        Req r = new Req();
        r.kind = 1; r.start = fwdCursor;
        boolean dense = fwdN < 2 || fwdFails > 0;
        r.len = Math.min(dense ? LONG_PROBE : PROBE, total - r.start);
        double ahead = Math.max(0, r.start - lastT) + r.len;
        r.wide = fwdFails >= EXT_FAILS_WIDE;
        r.lo = r.wide ? 0 : Math.max(0, lastW - 40);
        r.hi = r.wide ? words : (int) Math.min(words, lastW + rate() * ahead * 2 + 300);
        r.prevT = lastT; r.prevW = lastW;
        r.why = "forward, step " + (int) fwdStride + " s" + (r.wide ? ", whole book" : "");
        return r;
    }

    private Req backward() {
        skipMappedBehind();
        Map.Entry<Double, Integer> ref = anchors.ceilingEntry(bwdEnd);
        if (ref == null) ref = anchors.lastEntry();
        double firstT = ref.getKey();
        int firstW = ref.getValue();
        if (bwdEnd <= 8 || bwdEnd < startSec - reach) { bwdDone = true; return null; }
        Req r = new Req();
        r.kind = 2;
        boolean dense = bwdN < 2 || bwdFails > 0;
        double len = Math.min(dense ? LONG_PROBE : PROBE, bwdEnd);
        r.start = bwdEnd - len; r.len = len;
        double back = Math.max(0, firstT - r.start);
        r.wide = bwdFails >= EXT_FAILS_WIDE;
        r.hi = r.wide ? words : Math.min(words, firstW + 40);
        r.lo = r.wide ? 0 : (int) Math.max(0, firstW - rate() * back * 2 - 300);
        r.prevT = firstT; r.prevW = firstW;
        r.why = "backward, step " + (int) bwdStride + " s" + (r.wide ? ", whole book" : "");
        return r;
    }

    /** If the cursor runs into a stretch that is already mapped, jump to its far edge instead of listening again. */
    private void skipMappedAhead() {
        Double n = anchors.ceilingKey(fwdCursor);
        if (n == null || n - fwdCursor > DENSE_GAP) return;
        double c = n;
        for (Double h = anchors.higherKey(c); h != null && h - c <= DENSE_GAP; h = anchors.higherKey(c)) c = h;
        fwdCursor = c;
    }

    private void skipMappedBehind() {
        Double n = anchors.floorKey(bwdEnd);
        if (n == null || bwdEnd - n > DENSE_GAP) return;
        double c = n;
        for (Double l = anchors.lowerKey(c); l != null && c - l <= DENSE_GAP; l = anchors.lowerKey(c)) c = l;
        bwdEnd = c;
    }

    private Req gap() {
        double bestD = Double.MAX_VALUE, lt = 0, rt = 0;
        int lw = 0, rw = 0;
        double pt = -1; int pw = 0;
        for (Map.Entry<Double, Integer> e : anchors.entrySet()) {
            double t = e.getKey();
            if (pt >= 0 && t - pt > DENSE_GAP && !badGaps.contains(Math.round(pt * 10)) && pt < startSec + reach && t > startSec - reach) {
                double d = Math.abs((pt + t) / 2 - startSec);
                if (d < bestD) { bestD = d; lt = pt; rt = t; lw = pw; rw = e.getValue(); }
            }
            pt = t; pw = e.getValue();
        }
        if (bestD == Double.MAX_VALUE) return null;
        Req r = new Req();
        r.kind = 3; r.gapLeftT = lt;
        double mid = (lt + rt) / 2;
        r.len = Math.min(PROBE, rt - lt - 4);
        r.start = mid - r.len / 2;
        r.lo = lw; r.hi = rw; r.wide = false;
        r.why = "gap " + (int) lt + "-" + (int) rt + " s";
        return r;
    }

    /** Report what was heard. o == null means no confident match. Returns how many anchors were kept. */
    int feed(Req r, Aligner.Out o) {
        int kept = 0;
        boolean ok = o != null && o.pos.length >= (r.kind == 0 ? 5 : 3) && (r.kind != 0 || o.votes >= 6);
        if (r.kind == 0) {
            if (ok) { kept = add(o); seeded = true; placeCursors((o.time[0] + o.time[o.time.length - 1]) / 2); }
            return kept;
        }
        if (r.kind == 3) {
            long key = Math.round(r.gapLeftT * 10);
            if (ok) kept = add(o);
            if (kept == 0) {
                int n = gapTries.merge(key, 1, Integer::sum);
                if (n >= GAP_TRIES) badGaps.add(key);
            }
            return kept;
        }
        boolean fwd = r.kind == 1;
        if (!ok) {
            if (fwd) { fwdFails++; fwdCursor += 20; fwdStride = Math.max(0, fwdStride / 2); if (fwdFails >= EXT_FAILS_GIVE_UP) { fwdDone = true; lost = true; } }
            else { bwdFails++; bwdEnd -= 20; bwdStride = Math.max(0, bwdStride / 2); if (bwdFails >= EXT_FAILS_GIVE_UP) { bwdDone = true; lost = true; } }
            return 0;
        }
        // how well did the pace prediction hold?
        double rate = rate();
        double t0 = fwd ? o.time[0] : o.time[o.time.length - 1];
        int w0 = fwd ? o.pos[0] : o.pos[o.pos.length - 1];
        double dt = Math.abs(t0 - r.prevT);
        double err = Math.abs(w0 - (r.prevW + (fwd ? 1 : -1) * rate * dt));
        boolean good = err <= 40 + 0.12 * rate * dt;
        kept = add(o);
        if (fwd) {
            fwdFails = 0; fwdN++;
            fwdStride = fwdN < 2 ? 0 : good ? (fwdStride == 0 ? MIN_STRIDE : Math.min(MAX_STRIDE, fwdStride * 1.6)) : Math.max(MIN_STRIDE, fwdStride / 2);
            fwdCursor = (anchors.floorKey(o.time[o.time.length - 1]) == null ? o.time[o.time.length - 1] : anchors.floorKey(o.time[o.time.length - 1])) + fwdStride;
        } else {
            bwdFails = 0; bwdN++;
            bwdStride = bwdN < 2 ? 0 : good ? (bwdStride == 0 ? MIN_STRIDE : Math.min(MAX_STRIDE, bwdStride * 1.6)) : Math.max(MIN_STRIDE, bwdStride / 2);
            bwdEnd = (anchors.ceilingKey(o.time[0]) == null ? o.time[0] : anchors.ceilingKey(o.time[0])) - bwdStride;
        }
        return kept;
    }

    /** Adds anchors that agree with their neighbours in both time and book position. */
    private int add(Aligner.Out o) {
        int kept = 0;
        for (int i = 0; i < o.pos.length; i++) {
            double t = o.time[i]; int w = o.pos[i];
            Map.Entry<Double, Integer> lo = anchors.lowerEntry(t), hi = anchors.higherEntry(t);
            if (anchors.containsKey(t)) continue;
            if ((lo != null && lo.getValue() >= w) || (hi != null && hi.getValue() <= w)) continue;
            anchors.put(t, w);
            kept++;
        }
        return kept;
    }
}
