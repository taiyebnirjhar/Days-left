package com.taiyeb.daysleft;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Re-wraps the words of a PDF page to another width WITHOUT changing how they look: every word keeps its own pixels (the caller cuts it out of the
 * rendered page), only its place changes. So the font, weight, colour, italics, footnote numbers and headings are exactly the PDF's, just bigger,
 * and a line that no longer fits wraps to the next one.
 *
 * Pure Java (no Android) so tests/host/FlowDemo.java can check it on real pages. Geometry: word boxes are fractions of the page (x of the width,
 * y of the height); here everything is in "page-width units" (the page is 1 wide and {@code aspect} tall) and the result in pixels.
 */
final class WordFlow {
    /** Where every printed word group goes. Words that share one box (a hyphenated token split into several index words) are ONE item. */
    static final class Flow {
        int n;                          // items
        float[] x, y, w, h;             // destination rectangle in pixels
        float[] sx0, sy0, sx1, sy1;     // source rectangle in page-width units
        int[] first, last;              // index words of the item (global word numbers)
        int[] itemOfWord;               // page-local word -> item
        int a;                          // first word of the page
        float height;                   // total height in pixels
        boolean wholePage;              // too little text: the page is shown as it is, as one picture
        float scale;                    // pixels per page-width unit used (source pixels needed = this)
        float minX = 0, maxX = 1;       // left and right edge of ALL printed words (fractions of the page width): what must be rendered
    }

    /**
     * @param box   x0,y0,x1,y1 per word, fractions of the page (y from the top), like BookIndex.box
     * @param tok   printed words (for joining a word broken by a hyphen), may be null
     * @param a,b   the words of the page [a, b)
     * @param aspect page height / page width
     * @param lineW the width available for text, pixels
     * @param sc    pixels per page-width unit (this is the zoom: bigger = bigger text)
     */
    static Flow layout(float[] box, String[] tok, byte[] flag, int a, int b, float aspect, float lineW, float sc, float padL) {
        Flow f = new Flow();
        f.a = a; f.scale = sc;
        int m = Math.max(0, b - a);
        f.itemOfWord = new int[m];
        // ---- items: consecutive words with the same box are one printed thing ----
        int[] fi = new int[m], la = new int[m];
        int n = 0;
        for (int j = a; j < b; j++) {
            if (j > a && same(box, j, j - 1)) { la[n - 1] = j; f.itemOfWord[j - a] = n - 1; }
            else { fi[n] = j; la[n] = j; f.itemOfWord[j - a] = n; n++; }
        }
        float[] ix0 = new float[n], iy0 = new float[n], ix1 = new float[n], iy1 = new float[n], ih = new float[n];
        for (int i = 0; i < n; i++) {
            int j = fi[i];
            ix0[i] = box[4 * j]; ix1[i] = box[4 * j + 2];
            iy0[i] = box[4 * j + 1] * aspect; iy1[i] = box[4 * j + 3] * aspect;
            ih[i] = Math.max(1e-4f, iy1[i] - iy0[i]);
        }
        float allLo = 1, allHi = 0;
        for (int i = 0; i < n; i++) { allLo = Math.min(allLo, ix0[i]); allHi = Math.max(allHi, ix1[i]); }
        if (allHi > allLo) { f.minX = allLo; f.maxX = allHi; }
        // little text (a picture page, a cover, a scan with a few words): show the page itself
        if (n < 25) {
            f.wholePage = true;
            f.n = 1; f.x = new float[]{padL}; f.y = new float[]{0}; f.w = new float[]{lineW}; f.h = new float[]{lineW * aspect};
            f.sx0 = new float[]{0}; f.sy0 = new float[]{0}; f.sx1 = new float[]{1}; f.sy1 = new float[]{aspect};
            f.first = new int[]{a}; f.last = new int[]{Math.max(a, b - 1)};
            Arrays.fill(f.itemOfWord, 0);
            f.height = lineW * aspect;
            f.scale = lineW;
            f.minX = 0; f.maxX = 1;
            return f;
        }
        // ---- what this page looks like: body height, word gap, line spacing, left/right edge of the text ----
        // BookText stores y0 = baseline - h and y1 = baseline + 0.22 h for every word, so the baseline is y0 + (y1 - y0) / 1.22 whatever h means.
        float[] base = new float[n];
        for (int i = 0; i < n; i++) base[i] = iy0[i] + ih[i] / 1.22f;
        float domH = median(ih, n);
        float minX = 1, maxX = 0;
        for (int i = 0; i < n; i++) if (ih[i] >= 0.9f * domH) { minX = Math.min(minX, ix0[i]); maxX = Math.max(maxX, ix1[i]); }
        if (maxX <= minX) { minX = 0; maxX = 1; }
        ArrayList<Float> gaps = new ArrayList<>(), leads = new ArrayList<>();
        float refBase = -1, refH = domH, prevLineBase = -1;
        for (int i = 0; i < n; i++) {
            if (ih[i] < 0.78f * domH) continue;
            boolean nl = refBase >= 0 && base[i] > refBase + 0.5f * refH;
            if (nl) { if (ih[i] >= 0.9f * domH && ih[i] <= 1.1f * domH && prevLineBase >= 0) leads.add(base[i] - refBase); prevLineBase = refBase; }
            else if (refBase >= 0) { float g = ix0[i] - lastX1(ix1, ih, i, domH); if (g > 0 && g < 1.0f * domH) gaps.add(g); }
            if (nl || refBase < 0) { refBase = base[i]; refH = ih[i]; }
        }
        float spaceU = clamp(median(gaps), 0.1f * domH, 0.9f * domH, 0.5f * domH);
        float leadU = leads.isEmpty() ? 1.95f * domH : clamp(median(leads), 1.4f * domH, 3.2f * domH, 1.95f * domH);   // baseline to baseline
        // ---- place the words ----
        float[] px = new float[n], raise = new float[n], leadI = new float[n]; int[] line = new int[n];
        ArrayList<Float> lineLead = new ArrayList<>();    // per destination line: the tallest word's line spacing, pixels
        ArrayList<Float> lineGap = new ArrayList<>();     // extra space above the line (paragraph / heading), pixels
        ArrayList<Boolean> paraStart = new ArrayList<>(); paraStart.add(true);
        int li = 0;
        float x = padL, right = padL + lineW, lineStart = padL;
        float curLead = 0, pendingGap = 0;
        boolean prevEnd = true, prevHead = false, first = true; String prevTok = "";
        float sBase = -1, sH = domH, sX1 = 0, sBase1 = 0, normBase = 0;
        for (int i = 0; i < n; i++) {
            // a footnote number: clearly smaller, or somewhat smaller AND sitting above the baseline of the word before it
            boolean sup = !first && (ih[i] < 0.78f * domH || (ih[i] < 0.93f * domH && normBase - base[i] > 0.3f * ih[i]));
            boolean head = ih[i] >= 1.18f * domH;
            leadI[i] = leadU * ih[i] / domH;
            float wpx = (ix1[i] - ix0[i]) * sc;
            if (sup) {
                // a footnote number: raised, glued to the word before; no space, never starts a line
                raise[i] = Math.max(0, normBase - base[i]);
                px[i] = x; line[i] = li;
                x += wpx;
                continue;
            }
            boolean newSrcLine = sBase >= 0 && base[i] > sBase + 0.5f * sH;
            boolean para = false;
            if (newSrcLine) {
                boolean shortLine = sX1 < maxX - 0.12f, veryShort = sX1 < maxX - 0.3f;
                para = head != prevHead || head || veryShort || (shortLine && prevEnd) || (prevEnd && (ix0[i] > minX + 0.02f || base[i] - sBase > 1.25f * leadU));
            }
            float gap = spaceU * sc * Math.max(1f, ih[i] / domH);   // a heading's word gap grows with its size
            if (tok != null && newSrcLine && !para && prevTok.endsWith("-") && !prevTok.endsWith("--") && tok[fi[i]].length() > 0 && Character.isLowerCase(tok[fi[i]].charAt(0))) gap = 0;
            boolean breakLine;
            if (first) breakLine = false;
            else if (para) breakLine = true;
            else breakLine = x + gap + wpx > right && x > lineStart + 1;
            if (breakLine) {
                lineLead.add(curLead); lineGap.add(pendingGap); paraStart.add(para);
                li++; curLead = 0;
                pendingGap = para ? (head || prevHead ? 0.35f * leadU * sc : (base[i] - sBase > 1.25f * leadU ? (base[i] - sBase - leadU) * sc : 0)) : 0;
                x = padL;
                if (para && !head) x += Math.min(Math.max(0, ix0[i] - minX) * sc, 4 * leadU * sc);   // the paragraph's own indent
            } else if (!first) x += gap;
            px[i] = x; line[i] = li;
            x += wpx;
            curLead = Math.max(curLead, leadI[i] * sc);
            if (newSrcLine || sBase < 0) { sBase = base[i]; sH = ih[i]; }
            sX1 = ix1[i]; sBase1 = base[i]; normBase = base[i];
            prevEnd = (flag[la[i]] & 1) != 0; prevHead = head;
            if (tok != null) prevTok = tok[la[i]];
            first = false;
        }
        lineLead.add(curLead); lineGap.add(pendingGap);
        // ---- a line that is a whole paragraph on its own (a heading, a centred title) keeps where the page put it ----
        int lines = lineLead.size();
        float textCenter = (minX + maxX) / 2;
        int[] lineFirst = new int[lines]; Arrays.fill(lineFirst, -1);
        for (int i = 0; i < n; i++) if (lineFirst[line[i]] < 0) lineFirst[line[i]] = i;
        for (int k = 0; k < lines; k++) {
            if (!paraStart.get(k) || (k + 1 < lines && !paraStart.get(k + 1))) continue;
            float lo = 1e9f, hi = -1e9f, oLo = 1, oHi = 0;
            for (int i = lineFirst[k]; i < n && line[i] == k; i++) { lo = Math.min(lo, px[i]); hi = Math.max(hi, px[i] + (ix1[i] - ix0[i]) * sc); oLo = Math.min(oLo, ix0[i]); oHi = Math.max(oHi, ix1[i]); }
            float width = hi - lo, room = lineW - width;
            if (room < 4) continue;
            float shift;
            if (Math.abs((oLo + oHi) / 2 - textCenter) < 0.03f && oLo - minX > 0.03f) shift = room / 2 - (lo - padL);      // centred on the page
            else if (oLo - minX > 0.03f) shift = Math.min((oLo - minX) * sc, room) - (lo - padL);                          // indented
            else continue;
            for (int i = lineFirst[k]; i < n && line[i] == k; i++) px[i] += shift;
        }
        // ---- heights: baseline to baseline = the page's own line spacing ----
        float[] baseY = new float[lines];
        float yb = 0;
        for (int k = 0; k < lines; k++) {
            float asc = 0.78f * Math.max(lineLead.get(k), leadU * sc * 0.5f);
            if (k == 0) yb = asc;
            else yb += 0.22f * Math.max(lineLead.get(k - 1), leadU * sc * 0.5f) + asc + lineGap.get(k);
            baseY[k] = yb;
        }
        f.n = n;
        f.x = new float[n]; f.y = new float[n]; f.w = new float[n]; f.h = new float[n];
        // source rectangle: from 0.78 lead above the baseline to 0.22 lead below it, the full word width
        f.sx0 = ix0; f.sx1 = ix1; f.sy0 = new float[n]; f.sy1 = new float[n];
        f.first = fi; f.last = la;
        for (int i = 0; i < n; i++) {
            f.sy0[i] = base[i] - 0.78f * leadI[i]; f.sy1[i] = base[i] + 0.22f * leadI[i];
            f.x[i] = px[i]; f.w[i] = (ix1[i] - ix0[i]) * sc; f.h[i] = leadI[i] * sc;
            f.y[i] = baseY[line[i]] - 0.78f * leadI[i] * sc - raise[i] * sc;
        }
        f.height = baseY[lines - 1] + 0.22f * leadU * sc + leadU * sc * 0.4f;
        return f;
    }

    /** {minX, maxX} of the body text of a page (fractions of the page width): the pixel scale of "text fills the line" is lineW / (maxX - minX). */
    static float[] extent(float[] box, int a, int b, float aspect) {
        int m = b - a;
        if (m <= 0) return new float[]{0, 1};
        float[] h = new float[m];
        for (int j = a; j < b; j++) h[j - a] = (box[4 * j + 3] - box[4 * j + 1]) * aspect;
        float dom = median(h, m), lo = 1, hi = 0;
        for (int j = a; j < b; j++) if (h[j - a] >= 0.9f * dom) { lo = Math.min(lo, box[4 * j]); hi = Math.max(hi, box[4 * j + 2]); }
        return hi > lo ? new float[]{lo, hi} : new float[]{0, 1};
    }

    private static boolean same(float[] b, int j, int k) {
        return b[4 * j] == b[4 * k] && b[4 * j + 1] == b[4 * k + 1] && b[4 * j + 2] == b[4 * k + 2] && b[4 * j + 3] == b[4 * k + 3];
    }

    private static float lastX1(float[] x1, float[] h, int i, float domH) {
        for (int k = i - 1; k >= 0; k--) if (h[k] >= 0.78f * domH) return x1[k];
        return -1;
    }

    private static float median(float[] v, int n) {
        if (n == 0) return 0;
        float[] c = Arrays.copyOf(v, n); Arrays.sort(c); return c[n / 2];
    }

    private static float median(ArrayList<Float> l) {
        if (l.isEmpty()) return 0;
        float[] c = new float[l.size()]; for (int i = 0; i < c.length; i++) c[i] = l.get(i);
        Arrays.sort(c); return c[c.length / 2];
    }

    private static float clamp(float v, float lo, float hi, float dflt) { return v <= 0 ? dflt : Math.max(lo, Math.min(hi, v)); }
}
