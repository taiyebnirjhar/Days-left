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
    /** Everything around the playhead is mapped; the service is only waiting for the user to move. */
    static volatile boolean idle;
    /**
     * The zone around the playhead is mapped well enough for a sentence highlight. Re-checked continuously (hysteresis: on at 90%,
     * off below 60%), so it turns off by itself after a jump and the user is told the truth.
     */
    static volatile boolean ready;
    /** Speech model download progress (0..100) while the service is downloading, else -1. */
    static volatile int dlPct = -1;
    /** The phone is in battery saver, which slows the processor (and so the sync). */
    static volatile boolean powerSaving;
    /** How many anchors are known (0 = still finding the place in the audio). */
    static volatile int anchorCount;
    /** Book text reading progress (0..100) while the service is reading it, else -1. */
    static volatile int textPct = -1;
    /** The reader asks: when is this book word spoken? (-1 none). The answer appears in goalTimeOut (-1 working, -2 not found). */
    static volatile int goalWordReq = -1;
    static volatile double goalTimeOut = -1;
    /** Book word at the playhead once it is known (for Find page), else -1. */
    static volatile int hereWord = -1;
    static final double WINDOW = 600;   // seconds mapped either side of the playhead

    /** Saved progress of the window sync, 0..100. */
    static int pctFor(Context c, int id) { return Store.sp(c).getInt("syncPct_" + id, 0); }

    static boolean winReady(Context c, int id) { return Store.sp(c).getBoolean("syncWin_" + id, false); }

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

    /** Seconds left in the current stage, from the speed since the stage began; -1 when unknown or absurd. */
    static int etaSeconds() {
        if (ready) return -1;
        double got = doneSec - startedFrom, el = (System.currentTimeMillis() - startedMs) / 1000.0;
        if (got < 8 || el < 8) return -1;
        double sec = Math.max(0, totalSec - doneSec) / (got / el);
        return sec > 900 ? -1 : (int) Math.ceil(sec);
    }

    /** What the sync is doing, in words. The notification, the pill and the panel all use this, so they never disagree. */
    static String statusText() {
        if (dlPct >= 0) return "Downloading the speech model… " + dlPct + "%";
        if (textPct >= 0) return "Reading the book… " + textPct + "%";
        if (ready) return "Ready · follows you as you listen";
        if (anchorCount == 0 || statusPct() < 3) return "Finding your place in the audio…";   // nothing mapped around you yet: do not show a percentage
        int sec = etaSeconds();
        return "Syncing around you… " + statusPct() + "%" + (sec > 0 ? " · about " + (sec >= 90 ? (sec + 30) / 60 + " min" : sec + " s") : "");
    }

    /** The number that goes with statusText(), or -1 when there is none (yet). */
    static int statusPct() {
        if (dlPct >= 0) return dlPct;
        if (textPct >= 0) return textPct;
        if (ready) return 100;
        if (anchorCount == 0) return -1;
        return totalSec > 0 ? (int) Math.max(0, Math.min(99, doneSec * 100 / totalSec)) : 0;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        // Android kills the whole app a few seconds after startForegroundService() unless startForeground() is called right away,
        // for EVERY start request, including the ones we then ignore (a stop request, or a start while already running).
        // That was a real crash: the early returns used to come first. tests/host/check_services.py guards this.
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sync", "Syncing audiobook", NotificationManager.IMPORTANCE_LOW));
        final boolean stopping = in != null && "stop".equals(in.getAction());
        try {
            Notification first = running ? note(statusText(), statusPct(), statusPct() < 0) : note("Getting ready…", 0, true);
            if (Build.VERSION.SDK_INT >= 29) startForeground(7100, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(7100, first);
        } catch (Exception e) {
            Dbg.e("Sync", "startForeground refused", e);
            if (!stopping) error = "Android did not allow the background work to start. Open the book and try again.";
            if (!running) stopSelf();
            if (!stopping) return START_NOT_STICKY;
        }
        if (stopping) { stop(this); if (!running) { stopForeground(true); stopSelf(); } return START_NOT_STICKY; }
        if (running) return START_REDELIVER_INTENT;
        final int id = in == null ? -1 : in.getIntExtra("id", -1);
        running = true; cancel = false; idle = false; ready = false; try { powerSaving = getSystemService(PowerManager.class).isPowerSaveMode(); } catch (Exception ignored) {} error = ""; itemId = id; hereWord = -1; goalTimeOut = -1;
        Whisper.abort(false);
        Store.sp(this).edit().putBoolean("syncWant_" + id, true).apply();
        PowerManager pm = getSystemService(PowerManager.class);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daysleft:sync");
        wl.acquire(8 * 60 * 60 * 1000L);
        new Thread(() -> {
            // the speech threads inherit this: fewer preemptions, steadier call times (they varied 4 to 13 s for the same work)
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (Exception ignored) {}
            work(id);
        }, "sync").start();
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
            AudioClip.warm(this, it.audio);   // opening a 10 h file takes ~10 s: do it now, while the model/book text are prepared
            if (!Whisper.hasModel(this)) {
                // downloaded here, in the service, so it carries on when the dialog is closed or the screen is off
                dlPct = 0;
                Dbg.i("Sync", "downloading the speech model in the background");
                final boolean[] stop = {false};
                final long[] lastN = {0};
                try {
                    Whisper.download(this, pc -> {
                        dlPct = pc; stop[0] = cancel;
                        long now = System.currentTimeMillis();
                        if (now - lastN[0] > 1000) { lastN[0] = now; nm.notify(7100, note("Downloading the speech model… " + pc + "%", pc, false)); }
                    }, stop);
                } catch (Exception e) {
                    if (cancel) { Dbg.i("Sync", "model download cancelled"); return; }
                    throw e;
                } finally { dlPct = -1; }
                if (!Whisper.hasModel(this)) throw new Exception("speech model missing");
            }
            if (!BookText.ready(this, id)) {
                // the book's text is read here too, so it does not depend on the dialog being open
                Dbg.i("Sync", "reading the book's text in the background");
                final boolean[] stop = {false};
                final long[] lastN = {0};
                BookIndex got;
                try {
                    got = BookText.extract(this, it, pc -> {
                        stop[0] = cancel; dlPct = -1; textPct = pc;
                        long now = System.currentTimeMillis();
                        if (now - lastN[0] > 1000) { lastN[0] = now; nm.notify(7100, note("Reading the book's text… " + pc + "%", pc, false)); }
                    }, stop);
                } catch (Exception e) {
                    if (cancel) return;
                    throw e;
                } finally { textPct = -1; }
                if (BookText.looksScanned(got)) { BookText.forget(this, id); throw new Exception("This PDF is made of page pictures (a scan), so there is no text to match against."); }
            }
            BookIndex bi = BookText.index(this, id);
            Aligner al = new Aligner(bi);
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            mr.setDataSource(this, Uri.parse(it.audio));
            totalSec = Long.parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            mr.release();
            Alignment prior = Alignment.load(BookText.alignFile(this, id));
            double here = Player.pos();   // start where the user is listening: usable within one short clip
            SyncPlanner pl = new SyncPlanner(totalSec, here, bi.count(), prior.words(), prior.times());
            idle = winReady(this, id);   // was ready last time: stays "Synced" unless new work turns up
            final String audioUri = it.audio;
            // the control logic lives in SyncCore (pure Java, run end to end by tests/host/E2E); here are its ears and side effects
            SyncCore core = new SyncCore(pl, al, new SyncCore.Hearer() {
                @Override public void beforeWork() throws SyncCore.Stop {
                    if (cancel) throw new SyncCore.Stop(null);
                    if (lowBattery()) throw new SyncCore.Stop("The battery is low. Plug in and it carries on when you open the book.");
                }
                @Override public float[][] decode(double[] start, double[] len) throws Exception {
                    return AudioClip.decodeMany(SyncService.this, audioUri, start, len);
                }
                @Override public String[] recognize(float[][] pcm) throws Exception {
                    String[] r = Whisper.transcribeBatch(SyncService.this, pcm);
                    if (cancel) throw new SyncCore.Stop(null);
                    return r;
                }
            }, new SyncCore.Sink() {
                @Override public void anchors(int[] pos, double[] time) {
                    try { Alignment.append(BookText.alignFile(SyncService.this, id), pos, time); } catch (Exception e) { Dbg.w("Sync", "could not save anchors: " + e); }
                }
                @Override public void readyChanged(boolean r, double zd, double zt) {
                    Store.sp(SyncService.this).edit().putBoolean("syncWin_" + id, r).putInt("syncPct_" + id, r ? 100 : (int) (zd * 100 / Math.max(1, zt))).apply();
                }
                @Override public void log(String m) { Dbg.i("Sync", m); }
                @Override public void batch(int n, long msDec, long msTr, String detail) {
                    Dbg.p("Sync", "call with " + n + " pieces", Dbg.now() - (msDec + msTr) * 1000000L, "decode " + msDec + " ms, speech " + msTr + " ms" + detail);
                }
            }, System.currentTimeMillis());
            Dbg.i("Sync", "start item " + id + ", model " + (Whisper.which(this) == 0 ? "accurate" : "faster") + ", audio " + (int) pl.total + " s, from " + (int) here + " s, known anchors " + pl.anchorCount() + ", book words " + bi.count());
            long lastNote = 0, idleSince = 0;
            while (!cancel) {
                int rc;
                try {
                    rc = core.step(Player.pos(), Player.ready, goalWordReq, System.currentTimeMillis());
                } catch (SyncCore.Stop stop) {
                    if (stop.reason != null) error = stop.reason;
                    break;
                }
                // what the screen reads
                doneSec = core.doneSec; totalSec = core.totalSec; anchorCount = core.anchorCount; ready = core.ready;
                startedFrom = core.startedFrom; startedMs = core.startedMs;
                if (Player.ready) hereWord = core.hereWord;
                if (goalWordReq >= 0) goalTimeOut = core.goalTime;
                if (rc == SyncCore.LOST) break;
                long nowMs = System.currentTimeMillis();
                if (rc == SyncCore.IDLE) {
                    if (idleSince == 0) {
                        idleSince = nowMs;
                        if (!idle) {
                            idle = true;
                            Dbg.i("Sync", "mapped around " + (int) Player.pos() + " s after listening to " + (long) core.listened + " s in " + core.probes + " probes");
                        }
                        nm.notify(7100, note(statusText(), statusPct(), statusPct() < 0));
                    }
                    if (!Player.isPlaying() && nowMs - idleSince > 10000) break;
                    Thread.sleep(2000);
                    continue;
                }
                idleSince = 0;
                Store.sp(this).edit().putFloat("syncDone_" + id, (float) doneSec).putInt("syncPct_" + id, ready ? 100 : (int) Math.min(100, doneSec * 100 / Math.max(1, totalSec))).apply();
                if (nowMs - lastNote > 4000) {
                    lastNote = nowMs;
                    nm.notify(7100, note(statusText(), statusPct(), statusPct() < 0));
                }
            }
            Dbg.i("Sync", "listened to " + (long) core.listened + " s of " + (int) pl.total + " s in " + core.probes + " probes (" + (long) (core.listened * 100 / Math.max(1, pl.total)) + "%)");
            if (!cancel && pl.lost && error.isEmpty()) error = "Lost the place in the audio. Pin a page, then start the sync again.";
            if (!cancel && error.isEmpty()) Store.sp(this).edit().putBoolean("syncWin_" + id, true).putInt("syncPct_" + id, 100).apply();   // wanted stays on: it resumes next time the book opens
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            Dbg.e("Sync", "failed", e);
        } catch (Throwable t) {
            error = String.valueOf(t);
            Dbg.e("Sync", "failed (serious)", t);
        } finally {
            Dbg.i("Sync", (complete ? "finished" : cancel ? "stopped" : error.isEmpty() ? "ended" : "ended with error: " + error) + " at " + (int) doneSec + " of " + (int) totalSec + " s");
            running = false; idle = false; ready = false; hereWord = -1; textPct = -1; dlPct = -1; anchorCount = 0;
            Whisper.abort(false);
            if (wl != null && wl.isHeld()) wl.release();
            stopForeground(true);
            if (complete) finishNote("Audiobook synced", "Instant page lookup and highlighting are ready.");
            else if (!error.isEmpty()) finishNote("Sync stopped", error);
            stopSelf();
        }
    }
}
