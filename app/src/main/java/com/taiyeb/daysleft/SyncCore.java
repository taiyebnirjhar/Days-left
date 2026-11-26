package com.taiyeb.daysleft;

import java.util.ArrayList;
import java.util.List;

/**
 * The control logic of the sync, with no Android in it: stages, "ready", jumps, goals, and turning what was heard into anchors.
 * SyncService runs it on the phone (with MediaCodec and whisper.cpp as the ears); tests/host/E2E runs the same code on a laptop
 * with a synthesised audiobook and the real speech model, and scores what a listener would see.
 */
final class SyncCore {
    /** The ears: how the audio is decoded and recognised. */
    interface Hearer {
        /** Called just before a batch is decoded; throw Stop to end the run (low battery, cancelled). */
        void beforeWork() throws Stop;
        float[][] decode(double[] start, double[] len) throws Exception;
        /** Per piece, lines "seconds TAB seconds TAB word" counted from the start of that piece. Null = the model failed. */
        String[] recognize(float[][] pcm) throws Exception;
    }

    /** The side effects. */
    interface Sink {
        void anchors(int[] pos, double[] time);                        // persist
        void readyChanged(boolean ready, double zoneDone, double zoneTotal);
        void log(String message);
        void batch(int pieces, long msDecode, long msSpeech, String detail);
    }

    static final class Stop extends Exception {
        final String reason;   // shown to the user; null = a plain stop
        Stop(String reason) { super(reason); this.reason = reason; }
    }

    /** Return values of step(). */
    static final int WORKED = 0, IDLE = 1, LOST = 2;

    /** First a ring around the playhead (usable quickly), then the wide window. */
    static final double[] STAGES = {150, 600};

    final SyncPlanner pl;
    final Aligner al;
    private final Hearer ear;
    private final Sink sink;

    // state the service publishes to the screen
    volatile double doneSec, totalSec;
    volatile int anchorCount;
    volatile boolean ready;
    volatile int hereWord = -1;
    volatile double goalTime = -1;
    double startedFrom;
    long startedMs;
    int probes;
    double listened;
    int stage;
    boolean widening;
    private int lastGoal = -2;

    SyncCore(SyncPlanner pl, Aligner al, Hearer ear, Sink sink, long nowMs) {
        this.pl = pl; this.al = al; this.ear = ear; this.sink = sink;
        pl.reach = STAGES[0];
        pl.dense = true;   // fill the zone around the playhead first, with probes close enough for a sentence highlight
        anchorCount = pl.anchorCount();
        double[] zp = pl.zoneProgress();
        totalSec = zp[1]; doneSec = zp[0]; startedFrom = zp[0]; startedMs = nowMs;
    }

    /**
     * One round: follow the playhead, answer a goal if asked, and either do one batch of listening or report that there is nothing to do.
     * @param playhead seconds, valid only if playheadKnown; goalReq book word to locate, or -1
     */
    int step(double playhead, boolean playheadKnown, int goalReq, long nowMs) throws Exception {
        if (goalReq != lastGoal) {
            lastGoal = goalReq;
            if (goalReq >= 0) { pl.setGoal(goalReq); goalTime = pl.goalTime; sink.log("goal: when is book word " + goalReq + " spoken?" + (pl.goalTime >= 0 ? " known already: " + (int) pl.goalTime + " s" : "")); }
            else { pl.clearGoal(); goalTime = -1; }
        }
        if (playheadKnown) {
            // the window and the dense zone follow the playhead; a jump to an unmapped place starts over there
            if (pl.recenter(playhead) == 2) {
                stage = 0; pl.reach = STAGES[0]; pl.dense = true; widening = false;
                sink.log("playhead jumped to " + (int) playhead + " s, mapping around it");
                publish(nowMs, playhead, playheadKnown);
            }
        }
        updateReady(nowMs);

        List<SyncPlanner.Req> batch = pl.nextBatch(3);
        if (batch.isEmpty()) {
            if (pl.lost) return LOST;
            if (stage < STAGES.length - 1) {
                stage++; pl.widen(STAGES[stage]); pl.dense = true; widening = true;
                sink.log("zone ready around " + (int) pl.focus + " s after " + probes + " probes; widening to +-" + (int) STAGES[stage] + " s");
                return WORKED;
            }
            widening = false;
            return IDLE;
        }

        ear.beforeWork();
        long t0 = System.nanoTime();
        double[] st = new double[batch.size()], ln = new double[batch.size()];
        for (int i = 0; i < st.length; i++) { st[i] = batch.get(i).start; ln[i] = batch.get(i).len; }
        float[][] pcm = ear.decode(st, ln);                         // the pieces are decoded side by side
        long msDec = (System.nanoTime() - t0) / 1000000L;
        long t1 = System.nanoTime();
        String[] raws = ear.recognize(pcm);                        // up to three pieces in one speech call
        long msTr = (System.nanoTime() - t1) / 1000000L;
        if (raws == null) throw new Exception("speech model failed");

        al.paceHint = pl.typicalWps();   // a match far from this narrator's pace is a false match
        StringBuilder sum = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            SyncPlanner.Req q = batch.get(i);
            Heard h = parse(raws[i], q.start);
            Aligner.Out o = pcm[i].length >= 16000 * 3 ? al.align(h.words, h.times, q.lo, q.hi) : null;
            if (o != null) sink.anchors(o.pos, o.time);
            q.heard = h.times.length;
            int kept = pl.feed(q, o);
            probes++; listened += q.len;
            sum.append("\n   ").append(q.why).append(" at ").append((int) q.start).append(" s: heard ").append(h.times.length).append(" words, ")
                    .append(o == null ? "no match (" + (pcm[i].length < 16000 * 3 ? "clip too short" : Aligner.lastWhy) + ")" : "matched " + o.pos.length + " (votes " + o.votes + ")").append(", kept ").append(kept)
                    .append(", window ").append(q.lo).append("..").append(q.hi);
        }
        sink.batch(batch.size(), msDec, msTr, sum.toString());
        if (goalReq >= 0) goalTime = pl.goalTime;
        publish(nowMs, playhead, playheadKnown);
        updateReady(nowMs);
        return WORKED;
    }

    private void publish(long nowMs, double playhead, boolean known) {
        double[] zp = pl.zoneProgress();
        totalSec = zp[1];
        doneSec = Math.max(0, Math.min(zp[1], zp[0]));
        anchorCount = pl.anchorCount();
        if (known) hereWord = pl.wordNear(playhead);
    }

    /** Re-check "ready" against the zone around the playhead (on at 90%, off below 60%); the speed estimate restarts whenever it changes. */
    private void updateReady(long nowMs) {
        double[] zp = pl.zoneProgress();
        boolean now = anchorCount > 0 && zp[0] >= (ready ? 0.6 : 0.9) * zp[1];
        if (now == ready) return;
        ready = now;
        startedFrom = zp[0]; startedMs = nowMs;
        sink.log(now ? "ready here (" + (int) zp[0] + " of " + (int) zp[1] + " s of the zone around " + (int) pl.focus + " s)"
                : "no longer ready: the zone around " + (int) pl.focus + " s is only " + (int) (zp[0] * 100 / zp[1]) + "% mapped");
        sink.readyChanged(now, zp[0], zp[1]);
    }

    /** What was heard in one piece: the first word of every recognised token, with its time in the whole audio. */
    static final class Heard { String[] words; double[] times; }

    static Heard parse(String raw, double pieceStart) {
        ArrayList<String> w = new ArrayList<>();
        ArrayList<Double> t = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            double s0;
            try { s0 = Double.parseDouble(p[0]); } catch (Exception e) { continue; }
            List<String> ws = PageMatcher.words(p[2]);
            if (ws.isEmpty()) continue;
            w.add(ws.get(0)); t.add(pieceStart + s0);
        }
        Heard h = new Heard();
        h.words = w.toArray(new String[0]);
        h.times = new double[t.size()];
        for (int i = 0; i < h.times.length; i++) h.times[i] = t.get(i);
        return h;
    }
}
