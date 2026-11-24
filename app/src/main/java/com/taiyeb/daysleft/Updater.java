package com.taiyeb.daysleft;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * In-app update: asks GitHub Releases for the newest APK, downloads it and hands it to the system
 * installer (one confirm tap). The new APK must be signed with the same key as the installed one.
 */
public class Updater {
    static final String REPO = "taiyebnirjhar/Days-left";
    static final String ACTION = "com.taiyeb.daysleft.UPDATE_STATUS";

    /** What the latest release looks like. */
    static class Info {
        String version, url, notes;
        long size;
    }

    interface Cb<T> { void done(T value, String error); }
    interface Progress { void at(long done, long total); }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Picks the newest release from the GitHub "latest release" JSON. Null if it has no APK. */
    static Info parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").toLowerCase().endsWith(".apk")) {
                Info n = new Info();
                n.version = o.optString("tag_name").replaceFirst("^[vV]", "");
                n.url = a.optString("browser_download_url");
                n.size = a.optLong("size");
                n.notes = o.optString("body", "");
                return n;
            }
        }
        return null;
    }

    static String installedVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    /** Result: Info if a newer version exists, null value (and no error) if up to date. */
    static void check(Context c, Cb<Info> cb) {
        new Thread(() -> {
            Info res = null;
            String err = null;
            try {
                HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest");
                h.setRequestProperty("Accept", "application/vnd.github+json");
                int code = h.getResponseCode();
                if (code == 404) err = "No release published yet";
                else if (code != 200) err = "GitHub answered " + code;
                else {
                    Info n = parse(read(h.getInputStream()));
                    if (n == null) err = "The latest release has no APK";
                    else if (Version.compare(n.version, installedVersion(c)) > 0) res = n;
                }
            } catch (Exception e) {
                err = "Couldn't reach GitHub: " + e.getMessage();
                Dbg.e("Update", "check failed", e);
            }
            Info r = res; String er = err;
            UI.post(() -> cb.done(r, er));
        }, "update-check").start();
    }

    /** Downloads the APK into the app cache, then asks the system installer to install it. */
    static void install(Context c, Info n, Progress p, Cb<Boolean> cb) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                File dir = new File(app.getCacheDir(), "update");
                dir.mkdirs();
                File f = new File(dir, "DaysLeft-" + n.version + ".apk");
                HttpURLConnection h = open(n.url);
                if (h.getResponseCode() != 200) throw new Exception("Download answered " + h.getResponseCode());
                long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : n.size, got = 0, last = 0;
                try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[64 * 1024];
                    for (int k; (k = in.read(b)) > 0; ) {
                        out.write(b, 0, k);
                        got += k;
                        long now = System.currentTimeMillis();
                        if (p != null && now - last > 150) { last = now; long g = got; UI.post(() -> p.at(g, total)); }
                    }
                }
                if (total > 0 && got != total) throw new Exception("Download incomplete (" + got + " of " + total + ")");
                commit(app, f);
                Dbg.i("Update", "handed " + f.getName() + " (" + got + " B) to the installer");
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Dbg.e("Update", "install failed", e);
            }
            String er = err;
            UI.post(() -> cb.done(er == null, er));
        }, "update-install").start();
    }

    private static void commit(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[64 * 1024];
                for (int k; (k = in.read(b)) > 0; ) out.write(b, 0, k);
                s.fsync(out);
            }
            Intent i = new Intent(ACTION).setPackage(c.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    /** Receives the installer's status: shows the confirm screen, or logs why it failed. */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                Dbg.i("Update", "installed");
            } else {
                Dbg.w("Update", "installer status " + st + ": " + msg);
                android.widget.Toast.makeText(c, "Update failed: " + msg, android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Deletes old downloaded APKs. */
    static void cleanup(Context c) {
        File[] fs = new File(c.getCacheDir(), "update").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "DaysLeft-updater");
        return h;
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int k; (k = i.read(b)) > 0; ) o.write(b, 0, k);
            return o.toString("UTF-8");
        }
    }
}
