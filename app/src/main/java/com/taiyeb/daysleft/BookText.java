package com.taiyeb.daysleft;

import android.content.Context;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import java.io.*;
import java.util.*;

/** The words of a book and where each one sits on its page, kept on the phone. Read once per PDF. */
class BookText {
    interface Progress { void on(int percent); }

    private static BookIndex cached;
    private static PageMatcher cachedMatcher;
    private static int cachedId = -1;

    static File file(Context c, int itemId) { return new File(c.getFilesDir(), "bi_" + itemId + ".bin"); }
    static File alignFile(Context c, int itemId) { return new File(c.getFilesDir(), "al_" + itemId + ".bin"); }

    static boolean ready(Context c, int itemId) {
        File f = file(c, itemId);
        if (!f.exists()) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() == BookIndex.MAGIC) return true;
        } catch (Exception ignored) {}
        f.delete();   // an index from before v3.45 (boxes per line, not per word): read the book again, the word numbers do not change
        dispFile(c, itemId).delete();
        if (cachedId == itemId) { cached = null; cachedMatcher = null; cachedId = -1; }
        return false;
    }

    private static final Set<String> ABBR = new HashSet<>(Arrays.asList("dr", "mr", "mrs", "ms", "st", "vs", "jr", "sr", "prof", "fig", "vol", "inc", "no", "eg", "ie", "etc", "cf"));

    /** Splits a PDF page into words, remembering the box of every word. */
    static class BoxStripper extends PDFTextStripper {
        final ArrayList<String> words = new ArrayList<>();
        final ArrayList<float[]> boxes = new ArrayList<>();
        final ArrayList<Byte> flags = new ArrayList<>();
        final ArrayList<String> disp = new ArrayList<>();   // the word as printed (case, punctuation); "" for the 2nd+ piece of one printed token
        final ArrayList<int[]> look = new ArrayList<>();    // per word {bold|italic bits, font size in half points}
        float pw = 612, ph = 792;

        BoxStripper() throws IOException { super(); }

        @Override protected void startPage(PDPage page) throws IOException {
            PDRectangle r = page.getCropBox();
            int rot = page.getRotation();
            pw = (rot == 90 || rot == 270) ? r.getHeight() : r.getWidth();
            ph = (rot == 90 || rot == 270) ? r.getWidth() : r.getHeight();
            super.startPage(page);
        }

        /**
         * PDFBox hands over one printed LINE at a time, so a box per call would give every word of a line the same box. Here every word gets its OWN box
         * from the glyphs it is made of (the glyphs line up one to one with the characters of the line). y0 = baseline - h, y1 = baseline + 0.22 h, taken
         * from the glyphs of the word's main size, so a footnote number glued to a word does not move its baseline. If the glyphs do not line up with the
         * characters (ligatures, normalised text) the line's box is shared out by letters.
         */
        @Override protected void writeString(String text, List<TextPosition> pos) throws IOException {
            if (text == null || text.trim().isEmpty() || pos == null || pos.isEmpty()) return;
            float lx0 = 1e9f, ly0 = 1e9f, lx1 = -1e9f, ly1 = -1e9f;
            int[] off = new int[pos.size() + 1];
            for (int q = 0; q < pos.size(); q++) {
                TextPosition t = pos.get(q);
                float x = t.getXDirAdj(), w = t.getWidthDirAdj(), h = t.getHeightDir(), y = t.getYDirAdj();
                lx0 = Math.min(lx0, x); lx1 = Math.max(lx1, x + w);
                ly0 = Math.min(ly0, y - h); ly1 = Math.max(ly1, y + h * 0.22f);
                off[q + 1] = off[q] + (t.getUnicode() == null ? 0 : t.getUnicode().length());
            }
            boolean aligned = off[pos.size()] == text.length();
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("\\S+").matcher(text);
            while (tm.find()) {
                String tok = tm.group();
                List<String> ws = PageMatcher.words(tok);
                if (ws.isEmpty()) continue;
                float[] bx;
                int[] lk;
                if (aligned) {
                    float x0 = 1e9f, x1 = -1e9f, big = 0;
                    int s0 = tm.start(), e0 = tm.end();
                    for (int q = 0; q < pos.size(); q++) if (off[q + 1] > s0 && off[q] < e0) big = Math.max(big, pos.get(q).getFontSizeInPt());
                    float y0 = 1e9f, y1 = -1e9f;
                    TextPosition firstP = null;
                    for (int q = 0; q < pos.size(); q++) {
                        if (off[q + 1] <= s0 || off[q] >= e0) continue;
                        TextPosition t = pos.get(q);
                        if (firstP == null) firstP = t;
                        x0 = Math.min(x0, t.getXDirAdj()); x1 = Math.max(x1, t.getXDirAdj() + t.getWidthDirAdj());
                        if (t.getFontSizeInPt() >= 0.9f * big) { y0 = Math.min(y0, t.getYDirAdj() - t.getHeightDir()); y1 = Math.max(y1, t.getYDirAdj() + t.getHeightDir() * 0.22f); }
                    }
                    if (firstP == null || y1 < y0) { aligned = false; bx = null; lk = null; }
                    else { bx = new float[]{clamp(x0 / pw), clamp(y0 / ph), clamp(x1 / pw), clamp(y1 / ph)}; lk = lookOf(firstP); }
                    if (bx != null) { add(tok, ws, bx, lk); continue; }
                }
                // fallback: the line's box shared out by letters
                float tot = Math.max(1, text.length());
                float fx0 = lx0 + (lx1 - lx0) * tm.start() / tot, fx1 = lx0 + (lx1 - lx0) * tm.end() / tot;
                add(tok, ws, new float[]{clamp(fx0 / pw), clamp(ly0 / ph), clamp(fx1 / pw), clamp(ly1 / ph)}, lookOf(pos.get(Math.min(tm.start(), pos.size() - 1))));
                aligned = off[pos.size()] == text.length();
            }
        }

        private void add(String tok, List<String> ws, float[] bx, int[] lk) {
            boolean end = endsSentence(tok, ws.get(ws.size() - 1));
            for (int k = 0; k < ws.size(); k++) {
                words.add(ws.get(k));
                boxes.add(bx);
                flags.add((byte) (end && k == ws.size() - 1 ? 1 : 0));
                disp.add(k == 0 ? tok : "");
                look.add(lk);
            }
        }

        static int[] lookOf(TextPosition t) {
            int bits = 0;
            try {
                String fn = t.getFont() == null || t.getFont().getName() == null ? "" : t.getFont().getName().toLowerCase(Locale.ROOT);
                if (fn.contains("bold") || fn.contains("black") || fn.contains("heavy") || fn.contains("semibold")) bits |= 1;
                if (fn.contains("italic") || fn.contains("oblique")) bits |= 2;
            } catch (Exception ignored) {}
            return new int[]{bits, Math.round(t.getFontSizeInPt() * 2)};
        }

        static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

        static boolean endsSentence(String raw, String norm) {
            int e = raw.length();
            while (e > 0 && "\"'”’)]»*".indexOf(raw.charAt(e - 1)) >= 0) e--;
            if (e == 0) return false;
            char c = raw.charAt(e - 1);
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                if (c == '.' && ABBR.contains(norm)) return false;
                return true;
            }
            return false;
        }
    }

    /** Blocking. Reads the PDF, saves the index, returns it. Throws if the PDF can't be read. */
    static BookIndex extract(Context c, Item it, Progress pr, boolean[] cancel) throws Exception {
        long tx = Dbg.now();
        Dbg.i("Book", "reading text of item " + it.id);
        PDFBoxResourceLoader.init(c.getApplicationContext());
        File tmp = new File(c.getCacheDir(), "book_src.pdf");
        try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(it.pdf)); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[64 * 1024];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
        }
        BoxStripper st;
        int[] ps;
        try (PDDocument doc = PDDocument.load(tmp, MemoryUsageSetting.setupTempFileOnly())) {
            int n = doc.getNumberOfPages();
            st = new BoxStripper();
            ps = new int[n + 1];
            for (int p = 1; p <= n; p++) {
                if (cancel[0]) throw new Exception("cancelled");
                ps[p - 1] = st.words.size();
                st.setStartPage(p);
                st.setEndPage(p);
                st.getText(doc);
                if (p % 5 == 0 || p == n) pr.on(p * 100 / n);
            }
            ps[n] = st.words.size();
        } finally { tmp.delete(); }
        int m = st.words.size();
        String[] w = st.words.toArray(new String[0]);
        float[] box = new float[m * 4];
        byte[] fl = new byte[m];
        for (int j = 0; j < m; j++) { float[] b = st.boxes.get(j); System.arraycopy(b, 0, box, j * 4, 4); fl[j] = st.flags.get(j); }
        BookIndex bi = new BookIndex(w, ps, box, fl);
        boolean scanned = looksScanned(bi);
        if (!scanned) {
            bi.save(file(c, it.id));
            try { saveDisplay(c, it.id, st.disp, st.look); } catch (Exception e) { Dbg.e("Book", "could not save the display text", e); }
        }
        Dbg.p("Book", "text read", tx, bi.pages + " pages, " + m + " words" + (scanned ? ", LOOKS SCANNED (too little text)" : ", saved"));
        return bi;
    }

    static File dispFile(Context c, int itemId) { return new File(c.getFilesDir(), "tx_" + itemId + ".txt"); }

    private static void saveDisplay(Context c, int itemId, List<String> disp, List<int[]> look) throws IOException {
        File f = new File(dispFile(c, itemId).getPath() + ".tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"), 1 << 16)) {
            w.write("#v2\n");
            for (int i = 0; i < disp.size(); i++) { int[] l = look.get(i); w.write(l[0] + " " + l[1] + " " + disp.get(i) + "\n"); }
        }
        if (!f.renameTo(dispFile(c, itemId))) throw new IOException("rename failed");
    }

    /** The printed words with how they look, one per index word, for the text view. */
    static final class Disp {
        String[] tok; byte[] bits; short[] half;   // half = font size in half points
    }

    /** Null if not saved yet (books read before v3.40 have no look data) or out of step with the index. */
    static Disp display(Context c, int itemId, int words) {
        File f = dispFile(c, itemId);
        if (!f.exists()) return null;
        try {
            List<String> l = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            if (l.isEmpty() || !l.get(0).equals("#v2") || l.size() - 1 != words) return null;
            Disp d = new Disp();
            d.tok = new String[words]; d.bits = new byte[words]; d.half = new short[words];
            for (int i = 0; i < words; i++) {
                String[] q = l.get(i + 1).split(" ", 3);
                d.bits[i] = (byte) Integer.parseInt(q[0]); d.half[i] = (short) Integer.parseInt(q[1]); d.tok[i] = q.length > 2 ? q[2] : "";
            }
            return d;
        } catch (Exception e) { return null; }
    }

    /** True when the PDF has too little text to search (a scan). */
    static boolean looksScanned(BookIndex bi) { return bi.count() < Math.max(200, bi.pages * 20); }

    static synchronized BookIndex index(Context c, int itemId) throws Exception {
        if (cached != null && cachedId == itemId) return cached;
        cached = BookIndex.load(file(c, itemId));
        cachedMatcher = null;
        cachedId = itemId;
        return cached;
    }

    static synchronized PageMatcher matcher(Context c, int itemId, int pageCount) throws Exception {
        BookIndex bi = index(c, itemId);
        if (cachedMatcher == null) cachedMatcher = new PageMatcher(bi.pageTexts());
        return cachedMatcher;
    }

    // Every spot the app has checked against the book: "at this time the audio is on this page".
    static File samplesFile(Context c, int itemId) { return new File(c.getFilesDir(), "samples_" + itemId + ".txt"); }

    static List<double[]> samples(Context c, int itemId) {
        List<double[]> out = new ArrayList<>();
        File f = samplesFile(c, itemId);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(" ");
                if (p.length == 2) out.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void addSample(Context c, int itemId, double t, int page) {
        try (FileWriter w = new FileWriter(samplesFile(c, itemId), true)) { w.write((long) t + " " + page + "\n"); } catch (Exception ignored) {}
    }

    static void clearSamples(Context c, int itemId) { samplesFile(c, itemId).delete(); }

    /** Forget everything derived from the audio (a new audio file was attached). */
    static void clearAudioData(Context c, int itemId) {
        if (SyncService.liveItem == itemId) { SyncService.live = null; SyncService.liveItem = -1; }   // the in-memory copy must not bring cleared data back
        clearSamples(c, itemId);
        alignFile(c, itemId).delete();
        Store.sp(c).edit().remove("syncDone_" + itemId).remove("syncFull_" + itemId).remove("syncPct_" + itemId).remove("syncWin_" + itemId).apply();
    }

    /** Moves a book text prepared under a temporary id (the sheet was still open) to the item's own id. */
    static synchronized void adopt(Context c, int from, int to) {
        forget(c, to);
        file(c, from).renameTo(file(c, to));
        dispFile(c, from).renameTo(dispFile(c, to));
        if (cachedId == from || cachedId == to) { cached = null; cachedMatcher = null; cachedId = -1; }
    }

    /** Forget everything derived from the PDF (a new PDF was attached). */
    static synchronized void forget(Context c, int itemId) {
        file(c, itemId).delete();
        dispFile(c, itemId).delete();
        clearAudioData(c, itemId);
        if (cachedId == itemId) { cached = null; cachedMatcher = null; cachedId = -1; }
    }
}
