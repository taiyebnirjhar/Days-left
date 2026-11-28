package com.taiyeb.daysleft;

import java.io.File;

/** The read-aloud timeline must behave like a fully synced audiobook: exact word<->time, a marker on the right sentence everywhere, no gaps. */
public class TimelineCheck {
    static int bad = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) bad++; }

    public static void main(String[] a) throws Exception {
        int n = 6000;
        String[] w = new String[n]; byte[] fl = new byte[n]; float[] box = new float[n * 4];
        int[] pages = new int[21];
        for (int j = 0; j < n; j++) { w[j] = "w" + j; fl[j] = (byte) (j % 17 == 16 || j % 40 == 39 ? 1 : 0); }
        for (int p = 0; p <= 20; p++) pages[p] = p * 300;
        BookIndex bi = new BookIndex(w, pages, box, fl);
        Timeline tl = new Timeline(bi);
        File f = File.createTempFile("timeline", ".bin");
        tl.writeAlignment(f);
        Alignment al = Alignment.load(f).withBook(bi);
        f.delete();

        check(al.size() >= bi.sentStart.length, "an anchor at least per sentence (" + al.size() + " for " + bi.sentStart.length + " sentences)");
        int worstGap = 0, badWord = 0, noHl = 0, badSent = 0, ticks = 0;
        double maxGap = 0;
        for (double t = 1; t < tl.total - 1; t += 0.37) {
            ticks++;
            int want = tl.wordAt(t);
            int got = al.wordAtTime(t);
            if (got < 0 || Math.abs(got - want) > 3) badWord++;
            maxGap = Math.max(maxGap, al.gapAt(t) == Double.MAX_VALUE ? 1e9 : al.gapAt(t));
            int hw = al.highlightWordAt(t);
            if (hw < 0) noHl++;
            else if (bi.sentenceAround(hw)[0] != bi.sentenceAround(want)[0] && Math.abs(hw - want) > 3) badSent++;
        }
        check(badWord == 0, "word at every moment matches the timeline (" + badWord + " off of " + ticks + ")");
        check(maxGap <= Alignment.HL_GAP, "no gap wider than the highlight limit (widest " + (int) maxGap + " s)");
        check(noHl * 100.0 / ticks < 2, "highlight available at " + (100 - noHl * 100 / ticks) + "% of moments");
        check(badSent == 0, "highlight on the right sentence (" + badSent + " wrong)");

        // Play page: the time of a page's first word then the word at that time is that page
        int bp = 0;
        for (int p = 0; p < 20; p++) {
            double ts = al.timeOfWord(pages[p]);
            int pg = ts < 0 ? -1 : bi.pageOf(al.wordAtTime(ts + 0.05));
            if (pg != p) bp++;
        }
        check(bp == 0, "Play page then Find page agree on all 20 pages (" + bp + " off)");

        // the marker follows while playing
        MarkerTrack m = new MarkerTrack();
        long now = 0; int lost = 0, back = 0, prev = -1, k = 0;
        for (double t = 5; t < 400; t += 0.1) {
            now += 100; k++;
            m.update(al, bi, t, true, now);
            if (m.from < 0) lost++;
            if (m.from < prev) back++;
            prev = Math.max(prev, m.from);
        }
        check(lost == 0 && back == 0, "marker never lost and never steps back over 400 s (lost " + lost + ", back " + back + ")");
        check(Math.abs(tl.timeOfWord(tl.wordAt(123.4)) - 123.4) < 1.0 / Timeline.WPS + 0.5, "time <-> word round trip");
        System.out.println(bad == 0 ? "TIMELINE CHECKS PASSED" : "TIMELINE CHECKS FAILED");
        if (bad != 0) System.exit(1);
    }
}
