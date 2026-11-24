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

    static boolean ready(Context c, int itemId) { return file(c, itemId).exists(); }

    private static final Set<String> ABBR = new HashSet<>(Arrays.asList("dr", "mr", "mrs", "ms", "st", "vs", "jr", "sr", "prof", "fig", "vol", "inc", "no", "eg", "ie", "etc", "cf"));

    /** Splits a PDF page into words, remembering the box of every word. */
    static class BoxStripper extends PDFTextStripper {
        final ArrayList<String> words = new ArrayList<>();
        final ArrayList<float[]> boxes = new ArrayList<>();
        final ArrayList<Byte> flags = new ArrayList<>();
        float pw = 612, ph = 792;

        BoxStripper() throws IOException { super(); }

        @Override protected void startPage(PDPage page) throws IOException {
            PDRectangle r = page.getCropBox();
            int rot = page.getRotation();
            pw = (rot == 90 || rot == 270) ? r.getHeight() : r.getWidth();
            ph = (rot == 90 || rot == 270) ? r.getWidth() : r.getHeight();
            super.startPage(page);
        }

        @Override protected void writeString(String text, List<TextPosition> pos) throws IOException {
            if (text == null || text.trim().isEmpty() || pos == null || pos.isEmpty()) return;
            float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f;
            for (TextPosition t : pos) {
                float x = t.getXDirAdj(), w = t.getWidthDirAdj(), h = t.getHeightDir(), y = t.getYDirAdj();
                x0 = Math.min(x0, x); x1 = Math.max(x1, x + w);
                y0 = Math.min(y0, y - h); y1 = Math.max(y1, y + h * 0.22f);
            }
            float[] bx = {clamp(x0 / pw), clamp(y0 / ph), clamp(x1 / pw), clamp(y1 / ph)};
            for (String tok : text.trim().split("\\s+")) {
                List<String> ws = PageMatcher.words(tok);
                if (ws.isEmpty()) continue;
                boolean end = endsSentence(tok, ws.get(ws.size() - 1));
                for (int k = 0; k < ws.size(); k++) {
                    words.add(ws.get(k));
                    boxes.add(bx);
                    flags.add((byte) (end && k == ws.size() - 1 ? 1 : 0));
                }
            }
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
        if (!scanned) bi.save(file(c, it.id));
        Dbg.p("Book", "text read", tx, bi.pages + " pages, " + m + " words" + (scanned ? ", LOOKS SCANNED (too little text)" : ", saved"));
        return bi;
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
        clearSamples(c, itemId);
        alignFile(c, itemId).delete();
        Store.sp(c).edit().remove("syncDone_" + itemId).remove("syncFull_" + itemId).apply();
    }

    /** Forget everything derived from the PDF (a new PDF was attached). */
    static synchronized void forget(Context c, int itemId) {
        file(c, itemId).delete();
        clearAudioData(c, itemId);
        if (cachedId == itemId) { cached = null; cachedMatcher = null; cachedId = -1; }
    }
}
