package com.taiyeb.daysleft;

import java.io.*;
import java.util.*;

/**
 * Every word of the book in reading order, with the page it is on, where it sits on that page
 * and whether it ends a sentence. Pure Java so it can be tested away from the phone.
 */
class BookIndex {
    final String[] w;        // normalised words
    final int[] pageStart;   // first word of each page, plus one extra entry = word count
    final float[] box;       // x0,y0,x1,y1 per word, 0..1 of the page (y from the top)
    final byte[] flag;       // 1 = this word ends a sentence
    final int pages;
    int[] sentStart;
    private Map<Long, int[]> idx;

    BookIndex(String[] w, int[] pageStart, float[] box, byte[] flag) {
        this.w = w; this.pageStart = pageStart; this.box = box; this.flag = flag;
        this.pages = pageStart.length - 1;
        buildSentences();
    }

    int count() { return w.length; }

    private void buildSentences() {
        int c = 0;
        int[] tmp = new int[w.length + 1];
        for (int j = 0; j < w.length; j++) if (j == 0 || (flag[j - 1] & 1) != 0) tmp[c++] = j;
        sentStart = Arrays.copyOf(tmp, c);
    }

    int pageOf(int j) {
        int lo = 0, hi = pages - 1;
        while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (pageStart[m] <= j) lo = m; else hi = m - 1; }
        return lo;
    }

    /** Word range [from, to] of the sentence containing word j. */
    int[] sentenceAround(int j) {
        j = Math.max(0, Math.min(w.length - 1, j));
        int lo = 0, hi = sentStart.length - 1;
        while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (sentStart[m] <= j) lo = m; else hi = m - 1; }
        int from = sentStart[lo], to = (lo + 1 < sentStart.length ? sentStart[lo + 1] : w.length) - 1;
        return new int[]{from, to};
    }

    List<String> pageTexts() {
        List<String> out = new ArrayList<>();
        for (int p = 0; p < pages; p++) {
            StringBuilder sb = new StringBuilder();
            for (int j = pageStart[p]; j < pageStart[p + 1]; j++) sb.append(w[j]).append(' ');
            out.add(sb.toString());
        }
        return out;
    }

    /** bigram -> every position where it occurs */
    synchronized Map<Long, int[]> bigrams() {
        if (idx != null) return idx;
        Map<Long, ArrayList<Integer>> tmp = new HashMap<>();
        for (int j = 0; j + 1 < w.length; j++) tmp.computeIfAbsent(PageMatcher.key(w[j], w[j + 1]), k -> new ArrayList<>(2)).add(j);
        Map<Long, int[]> m = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<Long, ArrayList<Integer>> e : tmp.entrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
            m.put(e.getKey(), a);
        }
        idx = m;
        return m;
    }

    /** Writes to a temporary file and renames it, so a reader never sees a half-written index. */
    void save(File dest) throws IOException {
        File f = new File(dest.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            o.writeInt(0xB00C0002);
            o.writeInt(pages);
            for (int p = 0; p <= pages; p++) o.writeInt(pageStart[p]);
            for (int j = 0; j < w.length; j++) {
                o.writeUTF(w[j]);
                o.writeByte(flag[j]);
                for (int k = 0; k < 4; k++) o.writeFloat(box[j * 4 + k]);
            }
        }
        dest.delete();
        if (!f.renameTo(dest)) throw new IOException("could not save the book index");
    }

    static BookIndex load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != 0xB00C0002) throw new IOException("old format");
            int pages = in.readInt();
            int[] ps = new int[pages + 1];
            for (int p = 0; p <= pages; p++) ps[p] = in.readInt();
            int n = ps[pages];
            String[] w = new String[n];
            byte[] fl = new byte[n];
            float[] bx = new float[n * 4];
            for (int j = 0; j < n; j++) {
                w[j] = in.readUTF();
                fl[j] = in.readByte();
                for (int k = 0; k < 4; k++) bx[j * 4 + k] = in.readFloat();
            }
            return new BookIndex(w, ps, bx, fl);
        }
    }
}
