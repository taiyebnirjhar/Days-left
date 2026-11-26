package com.taiyeb.daysleft;

/** Which sentence to light up for a word. Pure Java: the reader and the end-to-end test use the same rule. */
final class Highlight {
    private Highlight() {}

    /** The sentence around book word w as {first, last}, or null when it is not a sentence (a page number, a stray symbol). */
    static int[] sentenceFor(BookIndex bi, int w) {
        int[] r = bi.sentenceAround(w);
        for (int k = r[0]; k <= r[1] && k < bi.count(); k++) {
            String x = bi.w[k];
            for (int c = 0; c < x.length(); c++) if (Character.isLetter(x.charAt(c))) return r;
        }
        return null;   // only digits and symbols: running heads and page numbers are never highlighted
    }
}
