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
    static double PROBE = 9, LONG_PROBE = 9, DENSE_GAP = 90, MAX_STRIDE = 150, MIN_STRIDE = 20;
    /** Highlighting a sentence needs probes close together right around the playhead: 20 s apart from 40 s behind to 150 s ahead of it (swept in tests/host/simrun.sh: 20 s keeps 0 wrong highlights and shows the first one a call sooner than 12 s), 30 s apart within 5 min, else DENSE_GAP. */
    /** A page start is only answered (and an instant Play page / Find my page is only trusted) when the probes around it are this close (seconds). */
    static final double GOAL_GAP = 10;   // was 25: a pause (a paragraph, a chapter) inside a 25 s stretch made interpolated page starts up to 4 s off (found by tests/e2e 'book'); 10 s keeps it to a fraction of a second
    static double NEAR_BEHIND = 40, NEAR_AHEAD = 150, DENSE_NEAR = 14, DENSE_MID = 30;
    static final int EXT_FAILS_WIDE = 4, EXT_FAILS_GIVE_UP = 8, SEED_TRIES = 12, GAP_TRIES = 2;

    /** What to listen to. Fill in the result with feed(). */
    static final class Req {
        double start, len;
        int lo, hi;
        boolean wide;
        int kind;           // 0 seed, 1 forward, 2 backward, 3 gap, 4 goal
        String why = "";
        double gapLeftT, gapRightT;    // kind 3: identifies the gap
        double prevT; int prevW; // kind 1/2: the anchor the prediction is measured from
        int heard = 99;          // words the speech model heard in this piece (set by the caller before feed): a piece with speech but no match is NOT silence
    }

    final double total;
    double startSec;
    private double placedAt = -1e9;
    final int words;
    final TreeMap<Double, Integer> anchors = new TreeMap<>();
    private double fwdCursor, bwdEnd, fwdStride, bwdStride;
    private int fwdN, bwdN, fwdFails, bwdFails, seedTry, seedsGiven;
    private boolean fwdDone, bwdDone, turnFwd = true, seeded;
    private final Map<Long, Integer> gapTries = new HashMap<>();
    private final Set<Long> badGaps = new HashSet<>();
    /** Stretches that were listened to and held nothing usable (silence, an intro, music): they count as settled, not as missing. */
    private final List<double[]> voids = new ArrayList<>();
    /** Only map this far (seconds) either side of the playhead; infinity = the whole book. */
    double reach = Double.POSITIVE_INFINITY;
    /** Where the user is listening right now (follows the player). */
    double focus = -1;
    /** Refine around the playhead with the tighter gap limits above. */
    boolean dense;
    private final Set<Long> inFlightGaps = new HashSet<>();
    private final TreeMap<Integer, Double> byWord = new TreeMap<>();   // book word -> time, same anchors
    /** Goal: find when this book word is spoken (Play this page). goalTime >= 0 once found, -2 if it could not be. */
    private int goalWord = -1; private int goalRounds;
    volatile double goalTime = -1;
    /** True if it stopped before the whole book was mapped (lost the place). */
    boolean lost;

    SyncPlanner(double totalSec, double startSec, int bookWords, int[] pos, double[] time) {
        this.total = totalSec;
        this.startSec = Math.max(0, Math.min(startSec, totalSec - 1));
        this.words = bookWords;
        this.focus = this.startSec;
        for (int i = 0; i < pos.length; i++) { anchors.put(time[i], pos[i]); byWord.put(pos[i], time[i]); }
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
     * The playhead position, called often. 0 = nothing changed; 1 = the window moved with the playhead (new work may
     * exist: the extension directions are reopened, gaps near the playhead are found by the next nextBatch);
     * 2 = the user jumped to a place with nothing mapped nearby: start over there (caller resets its stages).
     */
    int recenter(double pos) {
        pos = Math.max(0, Math.min(pos, total - 1));
        focus = pos;
        if (anchors.isEmpty()) {
            if (seeded || Math.abs(pos - startSec) > 30) { startSec = pos; seeded = false; seedTry = 0; fwdDone = bwdDone = false; return 2; }
            return 0;
        }
        Double a = anchors.floorKey(pos), b = anchors.ceilingKey(pos);
        boolean nearMapped = (a != null && pos - a <= MAX_NEAR) || (b != null && b - pos <= MAX_NEAR);
        if (seeded && nearMapped) {
            double old = startSec;
            startSec = pos;                       // the window follows the playhead
            int r = Math.abs(pos - old) > 1 ? 1 : 0;
            if ((pos > fwdCursor + 60 || pos < bwdEnd - 60) && Math.abs(pos - placedAt) > 2) {   // only if the playhead MOVED since the last placement
                placedAt = pos;
                // jumped to another mapped stretch (Play page, a seek): the cursors still sit where we were before, so move them here
                placeCursors(pos);
                fwdDone = bwdDone = false;
                fwdFails = bwdFails = 0; fwdN = bwdN = 0; fwdStride = bwdStride = 0;
                return 1;
            }
            if (fwdDone && fwdCursor < startSec + reach && fwdCursor < total - 8) { fwdDone = false; fwdFails = 0; r = 1; }
            if (bwdDone && bwdEnd > startSec - reach && bwdEnd > 8) { bwdDone = false; bwdFails = 0; r = 1; }
            return r;
        }
        // Nothing is mapped near the playhead. Starting over at the playhead is right when it moved (a seek, or playing on: the search follows it),
        // but a playhead that has NOT moved since the last start (paused) must not restart the search: the v3.37 log repeated the same three probes for
        // ever and never got further than the first one that matched.
        if (Math.abs(pos - startSec) < 5) return 0;
        startSec = pos;
        fwdDone = bwdDone = false;
        fwdFails = bwdFails = 0; fwdN = bwdN = 0; fwdStride = bwdStride = 0;
        seeded = false; seedTry = 0;
        return 2;
    }

    /** Within this many seconds of an anchor counts as "mapped here". */
    private static final double MAX_NEAR = 200;
    /** Gaps wider than this separate two mapped islands (a search far away): they are not bisected, that would listen to the whole audio. */
    private static final double GAP_MAX = 300;

    /**
     * {seconds of the zone around the playhead that are ready, zone length}. "Ready" = the probes around it are close enough
     * for a sentence highlight. This is the number the user sees as progress, so it means what it says.
     */
    double[] zoneProgress() {
        double lo = Math.max(0, focus - NEAR_BEHIND), hi = Math.min(total, focus + NEAR_AHEAD);
        List<double[]> iv = new ArrayList<>();
        double pt = -1;
        for (Double t : anchors.subMap(lo - 120, true, hi + 120, true).keySet()) {
            if (pt >= 0 && t - pt <= DENSE_NEAR + 5) iv.add(new double[]{pt, t});
            pt = t;
        }
        for (double[] v : voids) if (v[1] > lo && v[0] < hi) iv.add(v);   // listened to, nothing there: settled
        iv.sort((x, y) -> Double.compare(x[0], y[0]));
        double covered = 0, curEnd = lo;
        for (double[] v : iv) {
            double a2 = Math.max(Math.max(v[0], lo), curEnd), b2 = Math.min(v[1], hi);
            if (b2 > a2) { covered += b2 - a2; curEnd = b2; } else if (b2 > curEnd) curEnd = b2;
        }
        return new double[]{Math.min(covered, hi - lo), Math.max(1, hi - lo)};
    }

    /** {mapped seconds, window size} inside the window around the playhead; the progress the user sees. */
    double[] windowProgress() {
        double lo = Math.max(0, startSec - reach), hi = Math.min(total, startSec + reach);
        double covered = 0, pt = -1;
        for (Double t : anchors.subMap(lo, true, hi, true).keySet()) { if (pt >= 0 && t - pt <= DENSE_GAP) covered += t - pt; pt = t; }
        return new double[]{covered, Math.max(1, hi - lo)};
    }

    /**
     * Make the window bigger. The two walking directions were marked finished when the small window was done, so they
     * must be woken again, otherwise the wider window is never filled (this was a real bug: only +-150 s ever got mapped).
     */
    void widen(double newReach) {
        reach = newReach;
        fwdDone = bwdDone = false;
        fwdFails = bwdFails = 0;
    }

    int anchorCount() { return anchors.size(); }

    private boolean edgeLoDone, edgeHiDone;   // sticky: once the whole-book walk has reached an end of the audio it stays reached

    /**
     * The share (0..1) of the WHOLE audio that is linked to the book well enough: stretches between probes at most DENSE_GAP apart, stretches that were
     * listened to and held nothing (settled), and the two ends once the walk over the whole book has reached them. It does not depend on where the
     * playhead is or how far the window reaches, and it only ever grows (anchors and settled stretches are only added, the ends are sticky): this is
     * the ONE progress number the user sees.
     */
    double coverage() {
        if (anchors.isEmpty() || total <= 0) return 0;
        boolean whole = Double.isInfinite(reach);
        if (whole && bwdDone) edgeLoDone = true;
        if (whole && fwdDone) edgeHiDone = true;
        List<double[]> iv = new ArrayList<>();
        double pt = -1;
        for (Double t : anchors.keySet()) { if (pt >= 0 && t - pt <= DENSE_GAP) iv.add(new double[]{pt, t}); pt = t; }
        iv.addAll(voids);
        if (edgeLoDone) iv.add(new double[]{0, anchors.firstKey()});
        if (edgeHiDone) iv.add(new double[]{anchors.lastKey(), total});
        iv.sort((x, y) -> Double.compare(x[0], y[0]));
        double covered = 0, curEnd = 0;
        for (double[] v : iv) {
            double a2 = Math.max(Math.max(v[0], 0), curEnd), b2 = Math.min(v[1], total);
            if (b2 > a2) { covered += b2 - a2; curEnd = b2; } else if (b2 > curEnd) curEnd = b2;
        }
        return Math.max(0, Math.min(1, covered / total));
    }

    /** The narrator's usual words per second, from probes that are close together; 0 when unknown. */
    double typicalWps() { return typicalRate(); }

    /** Book word being spoken at second t if it is known well (between close anchors, or just next to one), else -1. */
    int wordNear(double t) {
        Map.Entry<Double, Integer> a = anchors.floorEntry(t), b = anchors.ceilingEntry(t);
        if (a != null && b != null && b.getKey() - a.getKey() <= DENSE_GAP)
            return a.getValue() + (int) ((t - a.getKey()) * (b.getValue() - a.getValue()) / Math.max(1e-6, b.getKey() - a.getKey()));
        if (a != null && t - a.getKey() <= 12) return a.getValue() + (int) ((t - a.getKey()) * rate());
        if (b != null && b.getKey() - t <= 12) return Math.max(0, b.getValue() - (int) ((b.getKey() - t) * rate()));
        return -1;
    }

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

    /**
     * Up to max independent pieces to listen to in ONE speech-model call. The model always encodes a full 30 s window,
     * so three 9 s pieces cost the same as one. Pieces never depend on each other's result.
     */
    List<Req> nextBatch(int max) {
        List<Req> out = new ArrayList<>();
        if (goalWord >= 0 && goalTime == -1) {
            out.addAll(goalBatch(max));
            if (!out.isEmpty()) return out;
        }
        if (!seeded) {
            for (int i = 0; i < max; i++) { Req r = seed(); if (r == null) break; out.add(r); }
            return out;
        }
        // dense mode: gaps in the zone around the playhead come before the far walkers (a whole-book walk must never delay the marker the user is looking at)
        if (dense && Double.isInfinite(reach)) {
            while (out.size() < max) { Req g = gap(NEAR_AHEAD + 20); if (g == null) break; out.add(g); }
            if (!out.isEmpty()) return out;
        }
        // dense mode: fill the zone in front of the playhead first (two of the three slots while the zone behind it needs work too)
        boolean fz = dense && !fwdDone && inFwdZone(), bz = dense && !bwdDone && inBwdZone();
        int fMax = !dense ? 1 : (fz && bz ? Math.max(1, max - 1) : max);
        if (!fwdDone) out.addAll(forwardPieces(fMax));
        if (!bwdDone && out.size() < max) out.addAll(backwardPieces(dense ? max - out.size() : 1));
        while (out.size() < max) { Req g = gap(); if (g == null) break; out.add(g); }
        return out;
    }

    /** Ask for the time of a book word. Result in goalTime (-1 working, -2 could not be found). */
    void setGoal(int word) {
        if (word < 0 || word >= words) return;
        goalWord = word; goalRounds = 0; goalTime = -1;
        if (resolveGoal()) return;
    }

    void clearGoal() { goalWord = -1; goalTime = -1; }

    /** If the word is already pinned between two close anchors, answer without listening. */
    private boolean resolveGoal() {
        Map.Entry<Integer, Double> lo = byWord.floorEntry(goalWord), hi = byWord.ceilingEntry(goalWord);
        if (lo != null && lo.getKey() == goalWord) { goalTime = lo.getValue(); return true; }
        if (lo != null && hi != null && hi.getValue() - lo.getValue() <= GOAL_GAP) {
            goalTime = lo.getValue() + (goalWord - lo.getKey()) * (hi.getValue() - lo.getValue()) / (double) Math.max(1, hi.getKey() - lo.getKey());
            return true;
        }
        return false;
    }

    private List<Req> goalBatch(int max) {
        List<Req> out = new ArrayList<>();
        if (resolveGoal()) return out;
        if (++goalRounds > 8) { goalTime = -2; return out; }
        Map.Entry<Integer, Double> lo = byWord.floorEntry(goalWord), hi = byWord.ceilingEntry(goalWord);
        double est, unc;
        int wlo, whi;
        double rate = rate();
        if (lo != null && hi != null) {
            double dt = hi.getValue() - lo.getValue();
            est = lo.getValue() + (goalWord - lo.getKey()) * dt / (double) Math.max(1, hi.getKey() - lo.getKey());
            unc = Math.max(10, 0.03 * dt); wlo = lo.getKey(); whi = hi.getKey();   // pace drift inside a bracket is a few percent
        } else if (lo != null || hi != null) {
            Map.Entry<Integer, Double> a = lo != null ? lo : hi;
            est = a.getValue() + (goalWord - a.getKey()) / rate;
            unc = 20 + 0.06 * Math.abs(est - a.getValue());
            wlo = lo != null ? lo.getKey() - 40 : 0; whi = hi != null ? hi.getKey() + 40 : words;
            if (lo != null) whi = words; else wlo = 0;
        } else {
            est = goalWord / Math.max(0.5, words / total);
            unc = 0.10 * total; wlo = 0; whi = words;
        }
        boolean wide = lo == null || hi == null;
        double step = Math.max(PROBE + 3, unc * 2 / 3);   // pieces spread over est +- unc
        for (int i = 0; i < Math.min(max, 3); i++) {
            double c = est + (i - (Math.min(max, 3) - 1) / 2.0) * step;
            Req r = new Req();
            r.kind = 4; r.len = PROBE;
            r.start = Math.max(0, Math.min(total - PROBE - 1, c - PROBE / 2));
            r.lo = Math.max(0, wlo - 30); r.hi = Math.min(words, whi + 30);
            r.wide = wide && goalRounds <= 2;
            if (r.wide) { r.lo = 0; r.hi = words; }
            r.why = "goal word " + goalWord + " round " + goalRounds + " near " + (int) est + " s +-" + (int) unc;
            boolean dup = false;
            for (Req o : out) if (Math.abs(o.start - r.start) < 2) dup = true;
            if (!dup) out.add(r);
        }
        return out;
    }

    private Req seed() {
        // the user's place, then alternately further away (intro music, silence, a skipped part); out-of-range ones are skipped
        while (seedTry < SEED_TRIES * 2) {
            int n = seedTry++;
            double off = ((n + 1) / 2) * 60.0 * (n % 2 == 1 ? 1 : -1);
            double s = startSec + (n == 0 ? 0 : off);
            if (s < 0 || s > total - 10) continue;
            Req r = new Req();
            r.kind = 0; r.start = s; r.len = Math.min(PROBE, total - s);
            r.lo = 0; r.hi = words; r.wide = true; r.why = "seed #" + (n + 1);
            seedsGiven++;
            return r;
        }
        lost = true;
        return null;
    }

    private boolean inFwdZone() { return dense && fwdCursor < focus + NEAR_AHEAD; }
    private boolean inBwdZone() { return dense && bwdEnd > focus - NEAR_BEHIND; }

    /**
     * Pieces going forward. Inside the dense zone (probes DENSE_NEAR apart up to NEAR_AHEAD in front of the playhead) up to
     * max of them are planned at once, spaced like that, so the area in front of the user fills three probes per model call.
     */
    private List<Req> forwardPieces(int max) {
        List<Req> out = new ArrayList<>();
        Req first = forward();
        if (first == null) return out;
        out.add(first);
        if (inFwdZone()) {
            for (int k = 1; k < max; k++) {
                double c = fwdCursor + k * (PROBE + DENSE_NEAR);
                if (c >= total - 8 || c > startSec + reach || c > focus + NEAR_AHEAD + PROBE) break;
                out.add(fwdAt(c, first.prevT, first.prevW));
            }
        }
        return out;
    }

    private Req fwdAt(double start, double lastT, int lastW) {
        Req r = new Req();
        r.kind = 1; r.start = start;
        r.len = Math.min(PROBE, total - r.start);
        double delta = Math.max(0, r.start - lastT);
        double rate = rate();
        r.wide = fwdFails >= EXT_FAILS_WIDE;
        // where the piece should land, with a margin that grows with the distance (pace drifts a few tens of percent, pauses eat words).
        // A small window keeps repeated common phrases elsewhere in the book from outvoting the real match.
        double margin = 40 + (delta > 60 ? 0.4 : 0.3) * rate * delta;
        double expect = lastW + rate * delta;
        r.lo = r.wide ? 0 : (int) Math.max(0, Math.min(lastW - 10, expect - margin));
        r.hi = r.wide ? words : (int) Math.min(words, expect + rate * r.len + margin);
        r.prevT = lastT; r.prevW = lastW;
        r.why = (inFwdZone() ? "ahead of you" : "forward, step " + (int) fwdStride + " s") + (r.wide ? ", whole book" : "");
        return r;
    }

    private boolean endProbed, startProbed;

    private Req forward() {
        skipMappedAhead();
        Map.Entry<Double, Integer> ref = anchors.floorEntry(fwdCursor);
        if (ref == null) ref = anchors.firstEntry();
        if (fwdCursor >= total - 8) {
            // The walk stops when a whole stride would pass the end, which left up to MAX_STRIDE (2.5 minutes) of the end of the book without a single
            // probe (found by tests/e2e 'book': the last 110 s of a book were unmapped, and counted as done). One probe at the very end closes it.
            Map.Entry<Double, Integer> last = anchors.lastEntry();
            if (!endProbed && last != null && total > 40 && total - last.getKey() > 20) {
                endProbed = true;
                Req r = fwdAt(Math.max(last.getKey() + 1, total - PROBE - 2), last.getKey(), last.getValue());
                r.why = "the end of the book";
                return r;
            }
            fwdDone = true; return null;
        }
        if (fwdCursor > startSec + reach) { fwdDone = true; return null; }
        return fwdAt(fwdCursor, ref.getKey(), ref.getValue());
    }

    private List<Req> backwardPieces(int max) {
        List<Req> out = new ArrayList<>();
        Req first = backward();
        if (first == null) return out;
        out.add(first);
        if (inBwdZone()) {
            for (int k = 1; k < max; k++) {
                double end = bwdEnd - k * (PROBE + DENSE_NEAR);
                if (end <= 8 || end < startSec - reach || end < focus - NEAR_BEHIND - PROBE) break;
                out.add(bwdAt(end, first.prevT, first.prevW));
            }
        }
        return out;
    }

    private Req bwdAt(double end, double firstT, int firstW) {
        Req r = new Req();
        r.kind = 2;
        double len = Math.min(PROBE, end);
        r.start = end - len; r.len = len;
        double delta = Math.max(0, firstT - end);        // from the end of the piece to the known anchor
        double rate = rate();
        r.wide = bwdFails >= EXT_FAILS_WIDE;
        double margin = 40 + (delta > 60 ? 0.4 : 0.3) * rate * delta;
        double expectEnd = firstW - rate * delta;         // the book word at the end of the piece
        r.hi = r.wide ? words : (int) Math.min(words, Math.max(firstW + 10, expectEnd + margin));
        r.lo = r.wide ? 0 : (int) Math.max(0, expectEnd - rate * len - margin);
        r.prevT = firstT; r.prevW = firstW;
        r.why = (inBwdZone() ? "behind you" : "backward, step " + (int) bwdStride + " s") + (r.wide ? ", whole book" : "");
        return r;
    }

    private Req backward() {
        skipMappedBehind();
        Map.Entry<Double, Integer> ref = anchors.ceilingEntry(bwdEnd);
        if (ref == null) ref = anchors.lastEntry();
        if (bwdEnd <= 8) {
            Map.Entry<Double, Integer> first = anchors.firstEntry();
            if (!startProbed && first != null && total > 40 && first.getKey() > 40) {   // the first seconds of the audio: the narrator may start right away
                startProbed = true;
                Req r = bwdAt(PROBE + 2, first.getKey(), first.getValue());
                r.why = "the start of the book";
                return r;
            }
            bwdDone = true; return null;
        }
        if (bwdEnd < startSec - reach) { bwdDone = true; return null; }
        return bwdAt(bwdEnd, ref.getKey(), ref.getValue());
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

    /** How wide a gap between probes may be at this moment of the audio. */
    private double gapLimit(double mid) {
        if (!dense) return DENSE_GAP;
        double d = mid - focus;
        if (d >= -NEAR_BEHIND && d <= NEAR_AHEAD) return DENSE_NEAR;
        if (Math.abs(d) <= 300) return DENSE_MID;
        return DENSE_GAP;
    }

    private double typCache; private int typSize = -1;

    /** The narrator's usual pace (median over close probe pairs), cached per anchor count. */
    private double typicalRate() {
        if (typSize != anchors.size()) {
            int n = anchors.size();
            int[] w = new int[n]; double[] t = new double[n];
            int i = 0;
            for (Map.Entry<Double, Integer> e : anchors.entrySet()) { t[i] = e.getKey(); w[i] = e.getValue(); i++; }
            typCache = Alignment.typicalRate(w, t);
            typSize = n;
        }
        return typCache;
    }

    private Req gap() { return gap(Double.MAX_VALUE); }

    /** The gap that most needs a probe, nearest to the playhead first; only gaps whose middle is within maxDist seconds of it. */
    private Req gap(double maxDist) {
        double bestD = Double.MAX_VALUE, lt = 0, rt = 0;
        int lw = 0, rw = 0;
        double pt = -1; int pw = 0;
        final double typ = dense ? typicalRate() : 0;
        for (Map.Entry<Double, Integer> e : anchors.entrySet()) {
            double t = e.getKey();
            double rr = pt >= 0 && t > pt ? (e.getValue() - pw) / (t - pt) : 0;
            boolean pauseGap = dense && pt >= 0 && t - pt > 7 && typ > 0 && (rr < 0.55 * typ || (e.getValue() - pw >= 5 && rr > Alignment.FAST_GAP * typ));   // a pause, music or unread text sits in here: look inside
            if (pt >= 0 && t - pt <= GAP_MAX && (t - pt > gapLimit((pt + t) / 2) || pauseGap) && !badGaps.contains(Math.round(pt * 10)) && !inFlightGaps.contains(Math.round(pt * 10)) && pt < startSec + reach && t > startSec - reach) {
                double d = Math.abs((pt + t) / 2 - focus);
                if (d < bestD && d <= maxDist) { bestD = d; lt = pt; rt = t; lw = pw; rw = e.getValue(); }
            }
            pt = t; pw = e.getValue();
        }
        if (bestD == Double.MAX_VALUE) return null;
        Req r = new Req();
        r.kind = 3; r.gapLeftT = lt; r.gapRightT = rt;
        inFlightGaps.add(Math.round(lt * 10));
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
        if (!ok && r.kind == 0 && r.heard < 6) voids.add(new double[]{r.start, r.start + r.len});
        if (r.kind == 0) {
            if (ok) { kept = add(o); seeded = true; placeCursors((o.time[0] + o.time[o.time.length - 1]) / 2); }
            return kept;
        }
        if (r.kind == 4) {
            if (ok) kept = add(o);
            if (goalWord >= 0) resolveGoal();
            return kept;
        }
        if (r.kind == 3) {
            long key = Math.round(r.gapLeftT * 10);
            inFlightGaps.remove(key);
            if (ok) kept = add(o);
            if (kept == 0) {
                int n = gapTries.merge(key, 1, Integer::sum);
                if (n >= GAP_TRIES) { badGaps.add(key); voids.add(new double[]{r.gapLeftT, r.gapRightT}); }
            }
            return kept;
        }
        boolean fwd = r.kind == 1;
        if (!ok) {
            if (r.heard < 6) { if (fwd) voids.add(new double[]{r.start, r.start + r.len + 20}); else voids.add(new double[]{r.start - 20, r.start + r.len}); }
            if (fwd) { fwdFails++; fwdCursor += 20; fwdStride = Math.max(0, fwdStride / 2); if (fwdFails >= EXT_FAILS_GIVE_UP) { fwdDone = true; lost = true; } }
            else {
                bwdFails++; bwdEnd -= 20; bwdStride = Math.max(0, bwdStride / 2);
                if (r.start < 40) bwdDone = true;   // the start of the audio is usually an intro that is not in the book
                else if (bwdFails >= EXT_FAILS_GIVE_UP) { bwdDone = true; lost = anchors.size() < 3; }   // text before the first match that the book does not have (an intro) is not 'lost'
            }
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
            double endT = anchors.floorKey(o.time[o.time.length - 1]) == null ? o.time[o.time.length - 1] : anchors.floorKey(o.time[o.time.length - 1]);
            if (dense && endT < focus + NEAR_AHEAD) fwdStride = DENSE_NEAR;   // inside the dense zone the spacing is fixed
            fwdCursor = Math.max(fwdCursor, endT + fwdStride);
        } else {
            bwdFails = 0; bwdN++;
            bwdStride = bwdN < 2 ? 0 : good ? (bwdStride == 0 ? MIN_STRIDE : Math.min(MAX_STRIDE, bwdStride * 1.6)) : Math.max(MIN_STRIDE, bwdStride / 2);
            double begT = anchors.ceilingKey(o.time[0]) == null ? o.time[0] : anchors.ceilingKey(o.time[0]);
            if (dense && begT > focus - NEAR_BEHIND) bwdStride = DENSE_NEAR;
            bwdEnd = Math.min(bwdEnd, begT - bwdStride);
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
            byWord.put(w, t);
            kept++;
        }
        return kept;
    }
}
