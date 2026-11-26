package com.taiyeb.daysleft;

/**
 * Which sentence the marker shows, tick by tick (the reader calls update() every 100 ms). Pure Java so tests/host/MarkerCheck.java can run it.
 * Rules, in the order of what matters to the listener:
 *  1. NEVER LOSE THE MARKER while the audio plays: where the sync data is too sparse to be sure (a pause, music, a stretch not yet mapped) the marker
 *     moves on by itself at the narrator's usual pace (dead reckoning, up to DR_MAX_S) and is corrected the moment real timing is there.
 *  2. Smooth: it never flickers off for a moment and never steps back to an earlier sentence because the sync refined its numbers (a step back must last
 *     BACK_MS to be believed); a step forward shows at once.
 *  3. A seek, or a playhead nothing is known about, shows nothing until something is known (a wrong marker is worse than none there).
 * A pause keeps the marker where it is.
 */
final class MarkerTrack {
    static final double DR_MAX_S = 150;      // longest stretch bridged without real timing
    static final long BACK_MS = 1500;
    int from = -1, to = -1;                  // the sentence shown (word numbers), -1 = none
    int word = -1;                           // the word this tick (real or estimated)
    boolean estimated;                       // this tick came from dead reckoning
    private double lastT = -1, lastValidT;
    private int lastValidWord = -1;
    private long backSince;

    /** @return true if the shown sentence changed (the screen needs a redraw) */
    boolean update(Alignment al, BookIndex bi, double t, boolean playing, long nowMs) {
        int oldFrom = from, oldTo = to;
        boolean seeked = lastT < 0 || Math.abs(t - lastT) > 1.5;
        lastT = t;
        int hw = al.highlightWordAt(t);
        estimated = false;
        double wps = al.typicalWps() > 0.5 ? al.typicalWps() : al.averageWps() > 0.5 ? al.averageWps() : 2.5;   // the narrator's pace; 2.5 words/s if nothing else is known
        if (hw >= 0) { lastValidWord = hw; lastValidT = t; }
        else if (seeked) lastValidWord = -1;                                   // a new place nothing is known about
        else if (playing && lastValidWord >= 0 && t >= lastValidT && t - lastValidT <= DR_MAX_S && wps > 0.5) {
            hw = Math.min(bi.count() - 1, lastValidWord + (int) ((t - lastValidT) * wps));
            estimated = true;
        }
        word = hw;
        if (hw >= 0) {
            int[] r = Highlight.sentenceFor(bi, hw);   // null: nothing to read there (a page number, a lone figure): keep what is shown
            if (r != null) {
                boolean keep = false;
                if (from >= 0 && r[0] < from && playing && !seeked) {   // an earlier sentence than the one shown: believe it only if it lasts
                    if (backSince == 0) backSince = nowMs;
                    keep = nowMs - backSince < BACK_MS && !estimated;
                    if (estimated) keep = true;
                } else backSince = 0;
                if (!keep) { from = r[0]; to = r[1]; }
            }
        } else if (from >= 0 && (seeked || (playing && t - lastValidT > DR_MAX_S))) {
            from = to = -1; backSince = 0;                                      // nothing is known here
        }
        return from != oldFrom || to != oldTo;
    }

    void reset() { from = to = -1; word = -1; lastT = -1; lastValidWord = -1; backSince = 0; estimated = false; }
}
