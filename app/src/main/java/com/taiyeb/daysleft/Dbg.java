package com.taiyeb.daysleft;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.StatFs;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's own log. Lives in a file so it survives crashes and restarts, and the Settings page can show,
 * copy and share it. Cheap enough to leave on: lines are written from a background thread.
 * Levels: I info, W warning, E error, P performance (a timing in ms).
 */
final class Dbg {
    private static final long MAX = 300 * 1024;
    private static File file;
    private static Context app;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dbg-log"); t.setDaemon(true); return t; });
    private static final int pid = Process.myPid();

    private Dbg() {}

    static void init(Context c) {
        app = c.getApplicationContext();
        file = new File(app.getFilesDir(), "debug.log");
    }

    static long now() { return System.nanoTime(); }
    static long ms(long since) { return (System.nanoTime() - since) / 1000000L; }

    static void i(String tag, String msg) { add('I', tag, msg, false); }
    static void w(String tag, String msg) { add('W', tag, msg, false); }
    static void e(String tag, String msg, Throwable t) {
        String s = msg;
        if (t != null) { StringWriter w = new StringWriter(); t.printStackTrace(new PrintWriter(w)); s += "\n" + w; }
        add('E', tag, s, false);
    }
    /** A timing. extra can be null. */
    static void p(String tag, String what, long since, String extra) {
        add('P', tag, what + " " + ms(since) + " ms" + (extra == null ? "" : "  " + extra), false);
    }
    /** Written straight away (for crashes, where the background thread may not get to run). */
    static void now(char lvl, String tag, String msg) { add(lvl, tag, msg, true); }

    private static void add(char lvl, String tag, String msg, boolean sync) {
        if (file == null) return;
        final String line;
        synchronized (FMT) { line = FMT.format(new Date()) + " " + lvl + "/" + tag + " [" + pid + "/" + Thread.currentThread().getName() + "] " + msg + "\n"; }
        android.util.Log.println(lvl == 'E' ? android.util.Log.ERROR : lvl == 'W' ? android.util.Log.WARN : android.util.Log.INFO, "DaysLeft/" + tag, msg);
        if (sync) write(line);
        else try { ex.execute(() -> write(line)); } catch (Throwable ignored) {}
    }

    private static synchronized void write(String line) {
        try {
            if (file.length() > MAX) trim();
            try (FileOutputStream o = new FileOutputStream(file, true)) { o.write(line.getBytes("UTF-8")); }
        } catch (Throwable ignored) {}
    }

    /** Keeps the newest half. */
    private static void trim() throws Exception {
        byte[] all;
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long keep = MAX / 2;
            f.seek(Math.max(0, f.length() - keep));
            all = new byte[(int) Math.min(keep, f.length())];
            f.readFully(all);
        }
        int s = 0;
        while (s < all.length && all[s] != '\n') s++;
        try (FileOutputStream o = new FileOutputStream(file, false)) { o.write(all, Math.min(s + 1, all.length), Math.max(0, all.length - s - 1)); }
    }

    static synchronized void clear() {
        try { new FileOutputStream(file, false).close(); } catch (Throwable ignored) {}
        i("Log", "cleared");
    }

    /** The whole log as text, newest last. maxChars keeps only the end. */
    static synchronized String read(int maxChars) {
        if (file == null || !file.exists()) return "";
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long n = f.length(), from = Math.max(0, n - maxChars * 2L);
            f.seek(from);
            byte[] b = new byte[(int) (n - from)];
            f.readFully(b);
            String s = new String(b, "UTF-8");
            if (from > 0) { int nl = s.indexOf('\n'); if (nl >= 0) s = s.substring(nl + 1); }
            if (s.length() > maxChars) { s = s.substring(s.length() - maxChars); int nl = s.indexOf('\n'); if (nl >= 0) s = s.substring(nl + 1); }
            return s;
        } catch (Throwable t) { return "(could not read the log: " + t + ")"; }
    }

    /** Device and app facts that help when reading a log. */
    static String header(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            b.append("Days Left ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {}
        b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append("), ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" cores\n");
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            b.append("RAM free ").append(mi.availMem >> 20).append(" of ").append(mi.totalMem >> 20).append(" MB");
            if (mi.lowMemory) b.append(" (LOW)");
            b.append(", app heap ").append((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20).append(" MB, native ")
                    .append(Debug.getNativeHeapAllocatedSize() >> 20).append(" MB\n");
            StatFs sf = new StatFs(c.getFilesDir().getAbsolutePath());
            b.append("Storage free ").append(sf.getAvailableBytes() >> 20).append(" MB\n");
        } catch (Throwable ignored) {}
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            b.append("Battery ").append(bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).append("%");
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            b.append(pm.isPowerSaveMode() ? ", power saving ON" : "").append(pm.isIgnoringBatteryOptimizations(c.getPackageName()) ? ", unrestricted" : ", battery optimised").append('\n');
        } catch (Throwable ignored) {}
        b.append("Sync: ").append(SyncService.running ? "running item " + SyncService.itemId + " " + (int) SyncService.doneSec + "/" + (int) SyncService.totalSec + " s" : "idle")
                .append(SyncService.error.isEmpty() ? "" : ", last error: " + SyncService.error).append('\n');
        b.append("Speech model: ").append(Whisper.hasModel(c) ? (Whisper.which(c) == 0 ? "Accurate" : "Faster") : "not downloaded").append('\n');
        return b.toString();
    }

    /** Why the previous runs of the app ended. This is the only way to see a crash in native code. */
    static void logPastExits(Context c) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
            long seen = Store.sp(c).getLong("exitSeen", 0), top = seen;
            for (int k = l.size() - 1; k >= 0; k--) {
                ApplicationExitInfo x = l.get(k);
                if (x.getTimestamp() <= seen) continue;
                top = Math.max(top, x.getTimestamp());
                String why = reason(x.getReason());
                StringBuilder s = new StringBuilder("previous run (pid " + x.getPid() + ") ended: " + why + ", status " + x.getStatus() + ", " + importance(x.getImportance())
                        + ", at " + FMT.format(new Date(x.getTimestamp())));
                if (x.getDescription() != null) s.append(", \"").append(x.getDescription()).append('"');
                s.append(", memory ").append(x.getPss() >> 10).append(" MB");
                boolean bad = x.getReason() == ApplicationExitInfo.REASON_CRASH || x.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || x.getReason() == ApplicationExitInfo.REASON_ANR || x.getReason() == ApplicationExitInfo.REASON_LOW_MEMORY
                        || x.getReason() == ApplicationExitInfo.REASON_SIGNALED || x.getReason() == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
                add(bad ? 'E' : 'I', "Exit", s.toString(), true);
                if (x.getReason() == ApplicationExitInfo.REASON_ANR) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(x.getTraceInputStream()))) {
                        StringBuilder t = new StringBuilder();
                        String ln;
                        int n = 0;
                        while ((ln = r.readLine()) != null && n++ < 60) t.append(ln).append('\n');
                        add('E', "Exit", "ANR trace (start):\n" + t, true);
                    } catch (Throwable ignored) {}
                }
            }
            Store.sp(c).edit().putLong("exitSeen", top).apply();
        } catch (Throwable t) { w("Exit", "could not read past exits: " + t); }
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_ANR: return "ANR (app stopped responding)";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Java exception)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE (native code crashed)";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exited by itself";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (killed for RAM)";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED (killed by the system)";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "stopped by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "force-stopped by the user";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission changed";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency died";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_FREEZER: return "frozen by the system";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            default: return "unknown (" + r + ")";
        }
    }

    private static String importance(int i) {
        if (i <= 100) return "was in the foreground";
        if (i <= 125) return "foreground service";
        if (i <= 230) return "visible/perceptible";
        if (i <= 400) return "background";
        return "cached";
    }
}
