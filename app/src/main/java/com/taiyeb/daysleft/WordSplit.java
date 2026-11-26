package com.taiyeb.daysleft;

import java.util.Locale;

/**
 * Pieces of audio are laid end to end in one speech call. This puts the recognised words back into their pieces.
 * Pure Java: the phone and the laptop test both use it.
 */
final class WordSplit {
    private WordSplit() {}

    static final int GAP_SAMPLES = 16000;   // a second of silence between pieces

    /**
     * @param raw lines "t0 TAB t1 TAB text" from the speech model (one word per line in word mode)
     * @param off start (seconds) of each piece in the combined buffer, len its length
     * @return per piece, lines "rel TAB rel TAB word", times counted from the start of that piece
     */
    static String[] split(String raw, double[] off, double[] len, boolean words) {
        StringBuilder[] sb = new StringBuilder[off.length];
        for (int i = 0; i < sb.length; i++) sb[i] = new StringBuilder();
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            double s0, s1;
            try { s0 = Double.parseDouble(p[0]); s1 = Double.parseDouble(p[1]); } catch (Exception e) { continue; }
            String[] ws = p[2].trim().split("\\s+");
            for (int k = 0; k < ws.length; k++) {
                if (ws[k].isEmpty()) continue;
                // word mode: the model's start is a little early and its end runs into the next word, so take start+0.3 s, never past the middle
                double tw = words ? Math.min(s0 + 0.3, (s0 + s1) / 2) : s0 + (k + 0.5) / ws.length * Math.max(0.2, s1 - s0);
                int ci = 0;
                for (int i = 0; i < off.length; i++) if (off[i] <= tw) ci = i;
                double rel = Math.max(0, Math.min(len[ci], tw - off[ci]));
                sb[ci].append(String.format(Locale.US, "%.2f\t%.2f\t%s\n", rel, rel, ws[k]));
            }
        }
        String[] out = new String[sb.length];
        for (int i = 0; i < out.length; i++) out[i] = sb[i].toString();
        return out;
    }

    /** Lays the pieces end to end (a second of silence between them). */
    static float[] combine(float[][] clips, double[] off, double[] len) {
        int total = 0;
        for (float[] k : clips) total += k.length + GAP_SAMPLES;
        float[] buf = new float[total];
        int pos = 0;
        for (int i = 0; i < clips.length; i++) {
            off[i] = pos / 16000.0; len[i] = clips[i].length / 16000.0;
            System.arraycopy(clips[i], 0, buf, pos, clips[i].length);
            pos += clips[i].length + GAP_SAMPLES;
        }
        return buf;
    }
}
