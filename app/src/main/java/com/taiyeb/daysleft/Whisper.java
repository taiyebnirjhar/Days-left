package com.taiyeb.daysleft;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Offline speech-to-text (whisper.cpp). The model is downloaded once and stays on the phone. */
class Whisper {
    static { System.loadLibrary("dayswhisper"); }

    // 0 = base (more accurate), 1 = tiny (about 3x faster)
    static final String[] MODELS = {"ggml-base.en-q5_1.bin", "ggml-tiny.en-q5_1.bin"};
    static final long[] MIN_BYTES = {40_000_000L, 25_000_000L};
    static final String[] SIZES = {"about 60 MB", "about 32 MB"};
    static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    static int which(Context c) { return Store.num(c, "wModel", 0) == 1 ? 1 : 0; }
    static void setWhich(Context c, int m) { if (m != which(c)) release(); Store.setNum(c, "wModel", m); }

    static native long nInit(String path);
    static native String nRun(long ctx, float[] pcm, int threads);
    static native String nRunSegs(long ctx, float[] pcm, int threads);
    static native void nFree(long ctx);
    static native void nAbort(boolean on);
    /** Makes a running transcription give up within a moment (or clears that). */
    static void abort(boolean on) { nAbort(on); if (on) Dbg.i("Whisper", "abort requested"); }

    private static long ctx;
    private static int ctxModel = -1;

    static File modelFile(Context c) { return new File(c.getFilesDir(), MODELS[which(c)]); }

    static boolean hasModel(Context c) { File f = modelFile(c); return f.exists() && f.length() > MIN_BYTES[which(c)]; }

    interface Progress { void on(int percent); }

    /** Blocking. Call from a background thread. */
    static void download(Context c, Progress pr, boolean[] cancel) throws Exception {
        int wm = which(c);
        File tmp = new File(c.getFilesDir(), MODELS[wm] + ".part");
        HttpURLConnection con = (HttpURLConnection) new URL(BASE_URL + MODELS[wm]).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setInstanceFollowRedirects(true);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int r, last = -1;
            while ((r = in.read(buf)) > 0) {
                if (cancel[0]) { tmp.delete(); throw new Exception("cancelled"); }
                out.write(buf, 0, r);
                got += r;
                int pc = total > 0 ? (int) (got * 100 / total) : 0;
                if (pc != last) { last = pc; pr.on(pc); }
            }
        }
        if (tmp.length() < MIN_BYTES[wm]) { tmp.delete(); throw new Exception("download incomplete"); }
        File f = modelFile(c);
        f.delete();
        if (!tmp.renameTo(f)) throw new Exception("could not save the model");
    }

    private static void ensureCtx(Context c) {
        int m = which(c);
        if (ctx != 0 && ctxModel != m) { nFree(ctx); ctx = 0; }
        if (ctx == 0) {
            long t = Dbg.now();
            Dbg.i("Whisper", "loading model " + (m == 0 ? "accurate" : "faster") + ", " + (modelFile(c).length() >> 20) + " MB, " + threads() + " threads");
            ctx = nInit(modelFile(c).getAbsolutePath()); ctxModel = m;
            Dbg.p("Whisper", ctx == 0 ? "model load FAILED" : "model loaded", t, null);
        }
    }

    private static int threads() { return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)); }

    /** Turns 16 kHz mono samples into text. Slow (seconds): call from a background thread. */
    static synchronized String transcribe(Context c, float[] pcm) {
        ensureCtx(c);
        if (ctx == 0) return null;
        // nRun (no-timestamp mode) crashes the speech engine on this phone; the timestamped path is the one that works.
        String raw = nRunSegs(ctx, pcm, threads());
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        for (String line : raw.split("\n")) { String[] p = line.split("\t", 3); if (p.length == 3) b.append(p[2].trim()).append(' '); }
        return b.toString().trim();
    }

    /** Same, but keeps the time of each phrase: lines of "startSeconds<TAB>endSeconds<TAB>text". */
    static synchronized String transcribeSegs(Context c, float[] pcm) {
        ensureCtx(c);
        if (ctx == 0) return null;
        return nRunSegs(ctx, pcm, threads());
    }

    static synchronized void release() { if (ctx != 0) { nFree(ctx); ctx = 0; ctxModel = -1; } }
}
