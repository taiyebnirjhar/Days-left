package com.taiyeb.daysleft;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/** The audiobook notification: pause, play, jump 15 s, and tap to open the book. */
public class PlaybackService extends Service {
    private static boolean up;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private NotificationManager nm;
    private boolean fg;
    private int ticks;

    /** Call after anything changes in the player. */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        if (Player.mp == null) { if (up) app.stopService(new Intent(app, PlaybackService.class)); return; }
        try { app.startForegroundService(new Intent(app, PlaybackService.class).setAction("sync")); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        up = true;
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("play", "Audiobook player", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "daysleft");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Player.play(PlaybackService.this); }
            @Override public void onPause() { Player.pause(PlaybackService.this); }
            @Override public void onSeekTo(long pos) { Player.seekMs((int) pos); refresh(); }
            @Override public void onSkipToNext() { Player.seekBy(PlaybackService.this, 15); }
            @Override public void onSkipToPrevious() { Player.seekBy(PlaybackService.this, -15); }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Player.pause(c); }   // headphones unplugged
    };

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        String a = in == null ? null : in.getAction();
        if (!fg) {   // a service started with startForegroundService() must go foreground right away
            Notification first = new Notification.Builder(this, "play").setSmallIcon(R.drawable.ic_fg).setContentTitle(Player.title).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7200, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(7200, first);
            fg = true;
        }
        if ("toggle".equals(a)) Player.toggle(this);
        else if ("back".equals(a)) Player.seekBy(this, -15);
        else if ("fwd".equals(a)) Player.seekBy(this, 15);
        else if ("stop".equals(a)) {
            if (!Player.readerOpen) Player.release(this);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        refresh();
        return START_NOT_STICKY;
    }

    private PendingIntent act(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!Player.isPlaying()) return;
            if (++ticks % 5 == 0) Player.saveNow(PlaybackService.this);
            if (ticks % 10 == 0) refresh();
            h.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        if (Player.mp == null) { stopForeground(true); stopSelf(); return; }
        Item it = Learn.item(this, Player.itemId);
        boolean playing = Player.isPlaying();
        long pos = (long) (Player.pos() * 1000), dur = (long) (Player.dur() * 1000);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, it != null ? it.title : Player.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Days Left")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? Player.speed : 0f, SystemClock.elapsedRealtime())
                .build());

        Intent open = new Intent(this, ReaderActivity.class).putExtra("id", Player.itemId).putExtra("fromNote", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 5, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String sub = it != null && it.pages > 0 ? "Page " + (it.page + 1) + " of " + it.pages + " · tap to open" : "Tap to open";
        Notification.Builder b = new Notification.Builder(this, "play")
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(it != null ? it.title : Player.title)
                .setContentText(sub)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(act("stop", 4))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_rew), "−15s", act("back", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), playing ? "Pause" : "Play", act("toggle", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_ff), "+15s", act("fwd", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .setOngoing(playing);
        Notification n = b.build();
        if (playing) {
            if (!fg) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(7200, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(7200, n);
                fg = true;
            } else nm.notify(7200, n);
            h.removeCallbacks(tick);
            h.postDelayed(tick, 1000);
        } else {
            if (fg) { stopForeground(STOP_FOREGROUND_DETACH); fg = false; }
            else startForegroundOnce(n);
            nm.notify(7200, n);
            h.removeCallbacks(tick);
        }
    }

    /** startForegroundService() requires startForeground() soon after, even when paused. */
    private void startForegroundOnce(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7200, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(7200, n);
            stopForeground(STOP_FOREGROUND_DETACH);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        up = false;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(noisy); } catch (Exception ignored) {}
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }
}
