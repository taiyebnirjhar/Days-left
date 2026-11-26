package com.taiyeb.daysleft;

/** Invariants of WordFlow (the zoomed text view's layout) on a synthetic page: nothing runs off the line, bigger zoom = bigger words and more lines, order is kept, footnote numbers stay raised and glued. */
public class FlowCheck {
    static int bad = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) bad++; }

    public static void main(String[] a) {
        // 40 lines of 9 words; word 12 is followed by a smaller, raised "footnote number"
        float aspect = 1.4f, lineH = 0.010f;
        java.util.ArrayList<float[]> w = new java.util.ArrayList<>();
        int supAfter = -1;
        for (int l = 0; l < 40; l++) {
            float y = 0.08f + l * 0.020f / aspect * aspect;   // baseline spacing 0.02 (page-width units / aspect applied below)
            float x = 0.1f;
            for (int k = 0; k < 9; k++) {
                float wd = 0.05f + 0.005f * ((l * 9 + k) % 4);
                float base = 0.08f + l * 0.0145f;   // fractions of page height
                float h = 0.0070f;                   // box height in page-height fractions
                w.add(new float[]{x, base - h / 1.22f, x + wd, base + 0.22f * h / 1.22f});
                if (l == 1 && k == 3) { supAfter = w.size() - 1; float sx = x + wd; w.add(new float[]{sx, base - h * 0.95f, sx + 0.012f, base - h * 0.95f + h * 0.6f}); x = sx + 0.012f + 0.0075f; } else x += wd + 0.0075f;
            }
        }
        int n = w.size();
        float[] box = new float[n * 4]; byte[] fl = new byte[n];
        for (int i = 0; i < n; i++) System.arraycopy(w.get(i), 0, box, 4 * i, 4);
        float lineW = 1000, tw = 0.88f;
        WordFlow.Flow f1 = WordFlow.layout(box, null, fl, 0, n, aspect, lineW, lineW / tw * 1.0f, 12);
        WordFlow.Flow f2 = WordFlow.layout(box, null, fl, 0, n, aspect, lineW, lineW / tw * 2.0f, 12);
        check(f1.n == n && f2.n == n, "every word is placed once (" + f2.n + " of " + n + ")");
        boolean fit = true; for (int i = 0; i < f2.n; i++) if (f2.x[i] + f2.w[i] > 12 + lineW + 2) fit = false;
        check(fit, "at 2x nothing runs off the line");
        check(f2.height > 1.8f * f1.height, "2x makes the page taller (" + (int) f1.height + " -> " + (int) f2.height + " px): the text wraps");
        check(f2.w[0] > 1.9f * f1.w[0] && f2.w[0] < 2.1f * f1.w[0], "2x words are twice as wide");
        boolean order = true; for (int i = 1; i < f2.n; i++) if (f2.y[i] + 1 < f2.y[i - 1] - 0.5f * f2.h[i] && f2.x[i] > f2.x[i - 1] - 1) order = false;
        check(order, "reading order is kept (lines go down)");
        // the footnote number: right after its word, same line, raised, no gap
        int s = supAfter + 1;
        check(Math.abs(f2.x[s] - (f2.x[supAfter] + f2.w[supAfter])) < 1.5f, "the footnote number is glued to its word");
        check(f2.y[s] + f2.h[s] * 0.78f < f2.y[supAfter] + f2.h[supAfter] * 0.78f - 1, "the footnote number stays raised above the baseline");
        // few words = a picture page, shown whole
        WordFlow.Flow pic = WordFlow.layout(box, null, fl, 0, 10, aspect, lineW, lineW, 12);
        check(pic.wholePage && pic.n == 1, "a page with almost no text is shown as the page itself");
        System.out.println(bad == 0 ? "FLOW CHECKS PASSED" : bad + " FLOW CHECKS FAILED");
        if (bad > 0) System.exit(1);
    }
}
