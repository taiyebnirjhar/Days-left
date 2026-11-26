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

    static int which(Context c) { return Store.num(c, "wModel", 1) == 1 ? 1 : 0; }   // unset = the fast model (about 3x quicker)
    static void setWhich(Context c, int m) { if (m != which(c)) release(); Store.setNum(c, "wModel", m); }

    static native long nInit(String path);
    static native String nRun(long ctx, float[] pcm, int threads);
    static native String nRunSegs(long ctx, float[] pcm, int threads);
    static native String nRunWords(long ctx, float[] pcm, int threads);
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
        appCtx = c.getApplicationContext();
        if (Store.num(appCtx, "wThrV", 0) != 3) {   // thread tuning method changed: measure again
            android.content.SharedPreferences.Editor e = Store.sp(appCtx).edit().remove("wThr").remove("wThrN").putInt("wThrV", 3);
            for (int t : CAND) e.remove("wThrT" + t);
            e.apply();
        }
        int m = which(c);
        if (ctx != 0 && ctxModel != m) { nFree(ctx); ctx = 0; }
        if (ctx == 0) {
            long t = Dbg.now();
            Dbg.i("Whisper", "loading model " + (m == 0 ? "accurate" : "faster") + ", " + (modelFile(c).length() >> 20) + " MB, " + threads() + " threads");
            ctx = nInit(modelFile(c).getAbsolutePath()); ctxModel = m;
            Dbg.p("Whisper", ctx == 0 ? "model load FAILED" : "model loaded", t, null);
        }
    }

    // Thread count is measured on this phone: the first calls try 4, 2, 6, 3 threads, then the fastest is kept.
    private static final int[] CAND = {4, 2, 6, 3};
    private static Context appCtx;
    private static int curThreads = 4;

    private static int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        int best = appCtx == null ? 0 : Store.num(appCtx, "wThr", 0);
        if (best > 0) return curThreads = Math.min(best, cores);
        int n = appCtx == null ? 0 : Store.num(appCtx, "wThrN", 0);
        return curThreads = Math.max(2, Math.min(CAND[n % CAND.length], cores));
    }

    /** Remember how long a call took with the thread count it used; each count is tried twice and the better run counts. */
    private static void tuned(long ms, double audioSec) {
        if (appCtx == null || Store.num(appCtx, "wThr", 0) > 0) return;
        int n = Store.num(appCtx, "wThrN", 0);
        // calls differ in length (1 to 3 pieces), so compare milliseconds per second of audio, not per call
        int per = (int) Math.max(1, Math.round(ms / Math.max(1.0, audioSec) * 10));   // ms per 10 s of audio
        String k = "wThrT" + curThreads;
        int old = Store.num(appCtx, k, 0);
        Store.setNum(appCtx, k, old > 0 ? Math.min(old, per) : per);
        Store.setNum(appCtx, "wThrN", n + 1);
        Dbg.i("Whisper", "tuning: " + curThreads + " threads, " + ms + " ms for " + (int) audioSec + " s of audio");
        if (n + 1 >= CAND.length * 2) {
            int bestT = 4; long bestMs = Long.MAX_VALUE;
            for (int t : CAND) { int v = Store.num(appCtx, "wThrT" + t, 0); if (v > 0 && v < bestMs) { bestMs = v; bestT = t; } }
            Store.setNum(appCtx, "wThr", bestT);
            Dbg.i("Whisper", "tuning done: using " + bestT + " threads (" + bestMs + " ms per 10 s of audio)");
        }
    }

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
        long t = Dbg.now();
        String r = nRunSegs(ctx, pcm, threads());
        if (r != null && !stopping()) tuned(Dbg.ms(t), pcm.length / 16000.0);
        return r;
    }

    /**
     * Word-by-word timestamps. If the app died inside such a call last time (native crash), they are switched off
     * for good and the older phrase-level timing is used instead.
     */
    private static boolean wordMode() {
        if (appCtx == null) return false;
        if (Store.sp(appCtx).getBoolean("wordPending", false)) {
            Store.sp(appCtx).edit().putBoolean("wordPending", false).putBoolean("noWordMode", true).commit();
            Dbg.w("Whisper", "word timestamps crashed last time: turned off, using phrase timing");
        }
        return !Store.sp(appCtx).getBoolean("noWordMode", false);
    }

    private static boolean stopping() { return SyncService.cancel; }

    /**
     * Several short clips in ONE call. The model always encodes a full 30 s window, so three 9 s clips cost the same
     * as one. Clips are laid end to end with a second of silence between. Returns, per clip, lines of
     * "seconds<TAB>seconds<TAB>word" with times counted from the start of that clip.
     */
    static synchronized String[] transcribeBatch(Context c, float[][] clips) {
        ensureCtx(c);
        if (ctx == 0) return null;
        double[] off = new double[clips.length], len = new double[clips.length];
        float[] buf = WordSplit.combine(clips, off, len);
        long t = Dbg.now();
        boolean words = wordMode();
        if (words) Store.sp(appCtx).edit().putBoolean("wordPending", true).commit();   // if the process dies inside the call, next time we know
        String raw = words ? nRunWords(ctx, buf, threads()) : nRunSegs(ctx, buf, threads());
        if (words) Store.sp(appCtx).edit().putBoolean("wordPending", false).commit();
        if (raw == null) return null;
        if (!stopping()) tuned(Dbg.ms(t), buf.length / 16000.0);
        return WordSplit.split(raw, off, len, words);
    }

    static synchronized void release() { if (ctx != 0) { nFree(ctx); ctx = 0; ctxModel = -1; } }
}
