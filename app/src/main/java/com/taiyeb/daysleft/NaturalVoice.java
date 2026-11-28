package com.taiyeb.daysleft;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The natural reading voice: Supertonic 3 (int8) run by sherpa-onnx. About 145 MB, downloaded once, used instead of the phone's own speech engine
 * when a book is read aloud. This class knows where the files are, downloads them (resumable) and says whether the voice may be used.
 */
final class NaturalVoice {
    static final String BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/";
    static final String[] FILES = {"tts.json", "unicode_indexer.bin", "voice.bin", "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vocoder.int8.onnx", "vector_estimator.int8.onnx"};
    static final long[] SIZES = {8253, 262144, 517168, 3700147, 36416150, 25991073, 78400833};
    static final long TOTAL;
    static { long t = 0; for (long s : SIZES) t += s; TOTAL = t; }

    static volatile int dlPct = -1;          // -1 = not downloading
    static volatile boolean downloading;
    static volatile String error = "";

    static File dir(Context c) { File d = new File(c.getFilesDir(), "voice/supertonic"); d.mkdirs(); return d; }

    static boolean installed(Context c) {
        for (int i = 0; i < FILES.length; i++) { File f = new File(dir(c), FILES[i]); if (!f.exists() || f.length() != SIZES[i]) return false; }
        return true;
    }

    /** Installed, switched on, and no native crash recorded: the reader may use it. */
    static boolean usable(Context c) {
        return installed(c) && Store.flag(c, "nvOn", true) && !Store.flag(c, "nvCrashed", false) && !Store.flag(c, "nvSlow", false);
    }

    /** Called at the start of every run: a 'loading' mark that was never cleared means the last run died inside the native library. */
    private static boolean checked;

    static synchronized void checkCrash(Context c) {
        if (checked) return;   // once per run of the app
        checked = true;
        if (Store.flag(c, "nvLoading", false)) {
            Store.setFlag(c, "nvLoading", false);
            Store.setFlag(c, "nvCrashed", true);
            Dbg.e("Voice", "the app died while the natural voice was in use: it is switched off", null);
        }
    }

    static void reset(Context c) { Store.setFlag(c, "nvCrashed", false); Store.setFlag(c, "nvSlow", false); Store.setFlag(c, "nvLoading", false); }

    interface Progress { void on(int percent); }

    /** Blocking; call from a worker thread. Files already complete are kept; a dropped connection carries on where it stopped (6 tries per file). */
    static void download(Context c, Progress pr, boolean[] cancel) throws Exception {
        downloading = true; error = "";
        try {
            long done = 0;
            for (int i = 0; i < FILES.length; i++) {
                File f = new File(dir(c), FILES[i]);
                if (f.exists() && f.length() == SIZES[i]) { done += SIZES[i]; continue; }
                File tmp = new File(f.getPath() + ".part");
                Exception last = null;
                boolean ok = false;
                for (int attempt = 1; attempt <= 6 && !ok; attempt++) {
                    if (cancel[0]) throw new Exception("cancelled");
                    try {
                        long have = tmp.exists() ? tmp.length() : 0;
                        if (have > SIZES[i]) { tmp.delete(); have = 0; }
                        HttpURLConnection con = (HttpURLConnection) new URL(BASE + FILES[i]).openConnection();
                        con.setConnectTimeout(15000); con.setReadTimeout(20000); con.setInstanceFollowRedirects(true);
                        if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
                        int code = con.getResponseCode();
                        boolean resumed = code == 206;
                        if (code == 416) { tmp.delete(); continue; }
                        if (code != 200 && code != 206) throw new java.io.IOException("server answered " + code);
                        if (!resumed) have = 0;
                        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp, resumed)) {
                            byte[] buf = new byte[64 * 1024];
                            long got = have; int r, last0 = -1;
                            while ((r = in.read(buf)) > 0) {
                                if (cancel[0]) throw new Exception("cancelled");
                                out.write(buf, 0, r); got += r;
                                int pc = (int) ((done + got) * 100 / TOTAL);
                                if (pc != last0) { last0 = pc; dlPct = pc; if (pr != null) pr.on(pc); }
                            }
                        }
                        if (tmp.length() != SIZES[i]) { tmp.delete(); throw new java.io.IOException("download incomplete"); }
                        f.delete();
                        if (!tmp.renameTo(f)) throw new Exception("could not save the file");
                        ok = true;
                    } catch (java.io.IOException e) {
                        last = e;
                        Dbg.i("Voice", "download interrupted (" + FILES[i] + ", try " + attempt + " of 6): " + e.getMessage());
                        try { Thread.sleep(Math.min(8000, 1000L * attempt)); } catch (InterruptedException ie) { throw new Exception("cancelled"); }
                    }
                }
                if (!ok) throw last != null ? last : new Exception("download failed");
                done += SIZES[i];
            }
        } finally { downloading = false; }
    }

    /** Starts the download on its own thread; the Settings row polls dlPct / downloading / error. */
    static boolean[] startDownload(Context c) {
        final Context app = c.getApplicationContext();
        final boolean[] cancel = new boolean[1];
        if (downloading) return cancel;
        dlPct = 0; error = ""; downloading = true;
        new Thread(() -> {
            try {
                download(app, null, cancel);
                reset(app);
                Store.setFlag(app, "nvOn", true);
                Dbg.i("Voice", "natural voice downloaded");
            } catch (Exception e) {
                if (!"cancelled".equals(e.getMessage())) { error = "Couldn't download the voice. Check the internet and try again."; Dbg.e("Voice", "download failed", e); }
            } finally { downloading = false; dlPct = -1; }
        }, "voice-download").start();
        return cancel;
    }

    static void delete(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
