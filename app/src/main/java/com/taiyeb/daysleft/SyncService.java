package com.taiyeb.daysleft;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.*;

/**
 * Lines the audiobook up with the book by listening to short samples (see SyncPlanner), not the whole thing.
 * Runs as a foreground service so it keeps going with the screen off. Safe to stop: it resumes where it left off.
 */
public class SyncService extends Service {
    static volatile boolean running;
    static volatile int itemId = -1;
    static volatile double doneSec, totalSec;
    static volatile long startedMs;
    static volatile double startedFrom;
    static volatile String error = "";
    static volatile boolean cancel;

    static final double CHUNK = 30;
    private PowerManager.WakeLock wl;
    private NotificationManager nm;

    static void start(Context c, int id) {
        Intent i = new Intent(c, SyncService.class).putExtra("id", id);
        c.startForegroundService(i);
    }

    static void stop(Context c) {
        Dbg.i("Sync", "stop requested (item " + itemId + ", at " + (int) doneSec + " s)");
        cancel = true;
        Whisper.abort(true);   // stop the piece being listened to right now, not after it
        if (itemId >= 0) Store.sp(c).edit().putBoolean("syncWant_" + itemId, false).apply();   // the user asked to stop: don't auto-resume
    }

    static boolean wanted(Context c, int id) { return Store.sp(c).getBoolean("syncWant_" + id, false); }

    static double doneFor(Context c, int id) { return Store.sp(c).getFloat("syncDone_" + id, 0f); }
    static boolean fullFor(Context c, int id) { return Store.sp(c).getBoolean("syncFull_" + id, false); }

    /** Minutes left, from the speed so far; -1 while unknown. */
    static int etaMinutes() {
        double got = doneSec - startedFrom, el = (System.currentTimeMillis() - startedMs) / 1000.0;
        if (got < 60 || el < 20) return -1;
        return (int) Math.ceil((totalSec - doneSec) / (got / el) / 60.0);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && "stop".equals(in.getAction())) { stop(this); return START_NOT_STICKY; }
        if (running) return START_REDELIVER_INTENT;
        final int id = in == null ? -1 : in.getIntExtra("id", -1);
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sync", "Syncing audiobook", NotificationManager.IMPORTANCE_LOW));
        running = true; cancel = false; error = ""; itemId = id;
        Whisper.abort(false);
        Store.sp(this).edit().putBoolean("syncWant_" + id, true).apply();
        Notification n = note("Getting ready…", 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(7100, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(7100, n);
        PowerManager pm = getSystemService(PowerManager.class);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daysleft:sync");
        wl.acquire(8 * 60 * 60 * 1000L);
        new Thread(() -> work(id), "sync").start();
        return START_REDELIVER_INTENT;   // if the system kills the app, it restarts and carries on
    }

    private boolean lowBattery() {
        android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
        return b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) == 0 && level < 15;
    }

    /** Android 15 limits how long a data-sync service may run in one go. Stop cleanly; it resumes next time. */
    @Override public void onTimeout(int startId, int fgsType) {
        error = "Android pauses long background jobs. Open the book and it carries on.";
        cancel = true;
    }

    private Notification note(String text, int pct, boolean indeterminate) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SyncService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "sync")
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle("Syncing audiobook with the book")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setProgress(100, pct, indeterminate)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void finishNote(String title, String text) {
        Notification n = new Notification.Builder(this, "sync").setSmallIcon(R.drawable.ic_fg).setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).build();
        nm.notify(7101, n);
    }

    private void work(int id) {
        boolean complete = false;
        try {
            Item it = Learn.item(this, id);
            if (it == null || it.audio.isEmpty()) throw new Exception("no audio file");
            if (!Whisper.hasModel(this)) throw new Exception("speech model missing");
            BookIndex bi = BookText.index(this, id);
            Aligner al = new Aligner(bi);
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            mr.setDataSource(this, Uri.parse(it.audio));
            totalSec = Long.parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            mr.release();
            Alignment prior = Alignment.load(BookText.alignFile(this, id));
            double here = Player.pos();   // start where the user is listening: usable within one short clip
            SyncPlanner pl = new SyncPlanner(totalSec, here, bi.count(), prior.words(), prior.times());
            doneSec = totalSec - pl.unresolved(); startedFrom = doneSec; startedMs = System.currentTimeMillis();
            Dbg.i("Sync", "start item " + id + ", model " + (Whisper.which(this) == 0 ? "accurate" : "faster") + ", audio " + (int) totalSec + " s, from " + (int) here + " s, known anchors " + pl.anchorCount() + ", book words " + bi.count());
            long lastNote = 0, listened = 0;
            int probes = 0;
            SyncPlanner.Req r = null;
            while (!cancel && (r = pl.next()) != null) {
                if (lowBattery()) { error = "The battery is low. Plug in and it carries on when you open the book."; break; }
                long t0 = Dbg.now();
                float[] pcm = AudioClip.decode(this, it.audio, r.start, r.len);
                long msDec = Dbg.ms(t0);
                Aligner.Out o = null;
                long msTr = 0, msAl = 0;
                int heard = 0;
                if (pcm.length >= 16000 * 3) {
                    long t1 = Dbg.now();
                    String raw = Whisper.transcribeSegs(this, pcm);
                    msTr = Dbg.ms(t1);
                    if (cancel) { Dbg.i("Sync", "piece at " + (int) r.start + " s abandoned after " + msTr + " ms (stopped)"); break; }
                    if (raw == null) throw new Exception("speech model failed");
                    ArrayList<String> tw = new ArrayList<>();
                    ArrayList<Double> tt = new ArrayList<>();
                    for (String line : raw.split("\n")) {
                        String[] p = line.split("\t", 3);
                        if (p.length < 3) continue;
                        double s0, s1;
                        try { s0 = Double.parseDouble(p[0]); s1 = Double.parseDouble(p[1]); } catch (Exception e) { continue; }
                        List<String> ws = PageMatcher.words(p[2]);
                        for (int k = 0; k < ws.size(); k++) { tw.add(ws.get(k)); tt.add(r.start + s0 + (k + 0.5) / ws.size() * Math.max(0.2, s1 - s0)); }
                    }
                    double[] times = new double[tt.size()];
                    for (int k = 0; k < times.length; k++) times[k] = tt.get(k);
                    heard = times.length;
                    long t2 = Dbg.now();
                    o = al.align(tw.toArray(new String[0]), times, r.lo, r.hi);
                    msAl = Dbg.ms(t2);
                    if (o != null) Alignment.append(BookText.alignFile(this, id), o.pos, o.time);
                }
                int kept = pl.feed(r, o);
                probes++; listened += (long) r.len;
                doneSec = Math.max(0, Math.min(totalSec, totalSec - pl.unresolved()));
                Dbg.p("Sync", "probe " + probes + " (" + r.why + ") at " + (int) r.start + "+" + (int) r.len + " s", t0, "decode " + msDec + " ms, listen " + msTr + " ms (" + String.format(Locale.US, "%.1f", r.len * 1000.0 / Math.max(1, msTr)) + "x real time), align " + msAl + " ms, heard " + heard + " words, " + (o == null ? "no match" : "matched " + o.pos.length + ", votes " + o.votes) + ", kept " + kept + ", window " + r.lo + ".." + r.hi);
                Store.sp(this).edit().putFloat("syncDone_" + id, (float) doneSec).apply();
                long now = System.currentTimeMillis();
                if (now - lastNote > 4000) {
                    lastNote = now;
                    int pct = (int) (doneSec * 100 / totalSec), eta = etaMinutes();
                    nm.notify(7100, note(pct + "%" + (eta >= 0 ? " · about " + (eta >= 60 ? (eta / 60) + " h " + (eta % 60) + " min" : eta + " min") + " left" : ""), pct, false));
                }
            }
            Dbg.i("Sync", "listened to " + listened + " s of " + (int) totalSec + " s in " + probes + " probes (" + (listened * 100 / Math.max(1, (long) totalSec)) + "%)");
            if (!cancel && r == null) {
                if (pl.lost && error.isEmpty()) error = "Lost the place in the audio. Pin a page, then start the sync again.";
                else if (!pl.lost) complete = true;
                if (complete) doneSec = totalSec;
                Store.sp(this).edit().putFloat("syncDone_" + id, (float) doneSec).putBoolean("syncFull_" + id, complete).putBoolean("syncWant_" + id, false).apply();
            }
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            Dbg.e("Sync", "failed", e);
        } catch (Throwable t) {
            error = String.valueOf(t);
            Dbg.e("Sync", "failed (serious)", t);
        } finally {
            Dbg.i("Sync", (complete ? "finished" : cancel ? "stopped" : error.isEmpty() ? "ended" : "ended with error: " + error) + " at " + (int) doneSec + " of " + (int) totalSec + " s");
            running = false;
            Whisper.abort(false);
            if (wl != null && wl.isHeld()) wl.release();
            stopForeground(true);
            if (complete) finishNote("Audiobook synced", "Instant page lookup and highlighting are ready.");
            else if (!error.isEmpty()) finishNote("Sync stopped", error);
            stopSelf();
        }
    }
}
