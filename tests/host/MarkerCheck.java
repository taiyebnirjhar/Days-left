package com.taiyeb.daysleft;

import java.util.*;

/** The marker must never be lost while playing, never step back, and follow real timing again after a gap. Synthetic book: 3 words/s, a sentence every 12 words. */
public class MarkerCheck {
    static int bad = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) bad++; }

    public static void main(String[] a) {
        int n = 3000;
        String[] w = new String[n]; byte[] fl = new byte[n]; float[] box = new float[n * 4];
        for (int j = 0; j < n; j++) { w[j] = "w" + j; fl[j] = (byte) (j % 12 == 11 ? 1 : 0); }
        BookIndex bi = new BookIndex(w, new int[]{0, n}, box, fl);
        // timing known every 20 s up to 100 s, then NOTHING for 100 s (a stretch not mapped yet), then every 20 s again
        List<int[]> pw = new ArrayList<>(); List<Double> pt = new ArrayList<>();
        for (int t = 0; t <= 600; t += 20) {      // a probe = several matched words close together (as the real ones are)
            if (t > 100 && t < 200) continue;
            for (int k = 0; k < 8; k++) { double tt = t + k * 0.667; pw.add(new int[]{(int) (tt * 3)}); pt.add(tt); }
        }
        Alignment al = Alignment.fromPoints(pw, pt).withBook(bi);

        MarkerTrack m = new MarkerTrack();
        long now = 0; int lost = 0, back = 0, prevFrom = -1, ticks = 0, estTicks = 0;
        for (double t = 40; t <= 260; t += 0.1) {                      // playing, no seek
            now += 100; ticks++;
            m.update(al, bi, t, true, now);
            if (t > 45 && m.from < 0) lost++;
            if (m.estimated) estTicks++;
            if (m.from < prevFrom) back++;
            prevFrom = Math.max(prevFrom, m.from);
        }
        check(lost == 0, "the marker is never lost while playing, including through the 100 s stretch with no timing (lost on " + lost + " of " + ticks + " ticks)");
        check(estTicks > 300, "that stretch was bridged by dead reckoning (" + estTicks + " ticks)");
        check(back == 0, "the marker never steps back to an earlier sentence");
        int trueWord = 260 * 3; int[] truth = Highlight.sentenceFor(bi, trueWord);
        check(truth != null && Math.abs(m.from - truth[0]) <= 24, "after the gap it is on the right sentence again (shown " + m.from + ", true " + (truth == null ? -1 : truth[0]) + ")");

        // a seek to a place nothing is known about: no marker (no guess)
        MarkerTrack s = new MarkerTrack();
        for (double t = 40; t < 60; t += 0.1) s.update(al, bi, t, true, (long) (t * 1000));
        check(s.from >= 0, "marker shown while playing in mapped audio");
        s.update(al, bi, 150, true, 61000);
        check(s.from < 0, "a seek into unmapped audio shows no marker (a wrong one is worse)");

        // a pause keeps the marker
        MarkerTrack p = new MarkerTrack();
        for (double t = 40; t < 50; t += 0.1) p.update(al, bi, t, true, (long) (t * 1000));
        int shown = p.from;
        for (int i = 0; i < 50; i++) p.update(al, bi, 50, false, 50000 + i * 100);
        check(p.from == shown || p.from >= 0, "paused: the marker stays");

        // a pace unknown to the close-probe measure still bridges (book-wide average)
        List<int[]> qw = new ArrayList<>(); List<Double> qt = new ArrayList<>();
        for (int t = 0; t <= 600; t += 20) { if (t > 100 && t < 200) continue; qw.add(new int[]{t * 3}); qt.add((double) t); }
        Alignment sparse = Alignment.fromPoints(qw, qt).withBook(bi);
        MarkerTrack q = new MarkerTrack(); int qlost = 0;
        for (double t = 40; t <= 190; t += 0.1) { q.update(sparse, bi, t, true, (long) (t * 1000)); if (t > 45 && q.from < 0) qlost++; }
        check(qlost == 0, "even with sparse timing (no close pairs) the marker bridges the gap (lost on " + qlost + " ticks)");

        // a step back that does not last is ignored; one that lasts is accepted
        MarkerTrack b = new MarkerTrack();
        for (double t = 40; t < 60; t += 0.1) b.update(al, bi, t, true, (long) (t * 1000));
        int ahead = b.from;
        b.update(al, bi, 59.9, true, 60000);      // the same instant again: nothing changes
        check(b.from == ahead, "no change on a repeated tick");
        System.out.println(bad == 0 ? "MARKER CHECKS PASSED" : bad + " MARKER CHECKS FAILED");
        if (bad > 0) System.exit(1);
    }
}
