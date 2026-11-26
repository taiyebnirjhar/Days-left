package com.taiyeb.daysleft;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The audiobook player. It lives with the app, not with the reader screen, so the audio keeps going
 * when you leave the reader or turn the screen off, and the notification can control it.
 */
class Player {
    static MediaPlayer mp;
    static volatile boolean ready;
    static int itemId = -1;
    static String uri = "";
    static String title = "";
    static float speed = 1f;
    static Runnable onReady;          // set by the reader while it is open
    static boolean readerOpen;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static AudioFocusRequest focus;
    private static boolean resumeAfterFocus;
    private static volatile double lastPos;          // last position seen, to come back to after an error
    private static double pendingSeek = -1;          // where to go once a reopened player is ready
    private static boolean playWhenReady;
    private static long lastRecover;
    private static int recoverCount;

    static boolean has(Item it) { return mp != null && itemId == it.id && uri.equals(it.audio); }

    /** Makes sure a player for this item exists (keeps the current one when it already matches). */
    static synchronized void open(Context c, Item it) {
        Context app = c.getApplicationContext();
        if (has(it)) return;
        release(app);
        itemId = it.id; uri = it.audio; title = it.title; ready = false;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build());
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(app, Uri.parse(it.audio));
            final int id = it.id;
            mp.setOnPreparedListener(m -> {
                ready = true;
                Dbg.i("Player", "audio ready, " + m.getDuration() / 1000 + " s");
                Item cur = Learn.item(app, id);
                if (pendingSeek >= 0) { m.seekTo((int) (pendingSeek * 1000)); pendingSeek = -1; }
                else if (cur != null && cur.apos > 0) m.seekTo(cur.apos * 1000);
                main.post(() -> {
                    if (onReady != null) onReady.run();
                    if (playWhenReady) { playWhenReady = false; play(app); }
                });
            });
            mp.setOnCompletionListener(m -> { saveNow(app); PlaybackService.sync(app); });
            mp.setOnErrorListener((m, w, e) -> {
                boolean was = ready;
                ready = false;
                Dbg.e("Player", "media error what=" + w + " extra=" + e + " at " + (int) lastPos + " s, was " + (was ? "ready" : "not ready"), null);
                main.post(() -> recover(app, lastPos, true));
                return true;
            });
            Dbg.i("Player", "opening audio for item " + it.id);
            mp.prepareAsync();
        } catch (Exception e) {
            Dbg.e("Player", "could not open the audio file", e);
            mp = null;
        }
    }

    static boolean isPlaying() { try { return mp != null && ready && mp.isPlaying(); } catch (Exception e) { return false; } }
    static double pos() { try { if (mp != null && ready) { lastPos = mp.getCurrentPosition() / 1000.0; return lastPos; } return 0; } catch (Exception e) { return 0; } }
    static double dur() { try { return mp != null && ready ? mp.getDuration() / 1000.0 : 0; } catch (Exception e) { return 0; } }

    private static boolean getFocus(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(ch -> main.post(() -> {
                    if (ch == AudioManager.AUDIOFOCUS_LOSS) { resumeAfterFocus = false; pause(c.getApplicationContext()); }
                    else if (ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeAfterFocus = isPlaying(); pause(c.getApplicationContext()); }
                    else if (ch == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) { resumeAfterFocus = false; play(c.getApplicationContext()); }
                }))
                .build();
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    static void play(Context c) {
        Context app = c.getApplicationContext();
        if (mp == null) { Dbg.w("Player", "play ignored: no player"); return; }
        if (!ready) {
            Dbg.w("Player", "play asked but the player is not ready (error or still loading): reopening and playing from " + (int) lastPos + " s");
            recover(app, lastPos, true);
            return;
        }
        boolean got = getFocus(app);
        try {
            mp.start();
            applySpeed();
            Dbg.i("Player", "play at " + (int) pos() + " s" + (got ? "" : " (audio focus not granted)"));
        } catch (Exception e) {
            Dbg.e("Player", "start failed, reopening", e);
            recover(app, lastPos, true);
            return;
        }
        PlaybackService.sync(app);
    }

    /** The player died (media error or wrong state): open the same audio again, go back to where it was, and carry on. */
    static void recover(Context app, double at, boolean autoplay) {
        if (itemId < 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRecover > 20000) recoverCount = 0;
        if (++recoverCount > 3) { Dbg.e("Player", "giving up after repeated errors", null); main.post(() -> android.widget.Toast.makeText(app, "The audio file can't be played. Re-attach it in the book's menu.", android.widget.Toast.LENGTH_LONG).show()); return; }
        lastRecover = now;
        Item it = Learn.item(app, itemId);
        if (it == null) return;
        Dbg.i("Player", "recovering: reopening at " + (int) at + " s" + (autoplay ? ", will play" : ""));
        synchronized (Player.class) {
            if (mp != null) { try { mp.release(); } catch (Exception ignored) {} }
            mp = null; ready = false; itemId = -1; uri = "";
        }
        pendingSeek = Math.max(0, at);
        playWhenReady = autoplay;
        open(app, it);
    }

    static void pause(Context c) {
        Context app = c.getApplicationContext();
        try { if (mp != null && ready && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        saveNow(app);
        PlaybackService.sync(app);
    }

    static void toggle(Context c) { if (isPlaying()) pause(c); else play(c); }

    static void seekMs(int ms) { try { if (mp != null && ready) mp.seekTo(Math.max(0, ms)); } catch (Exception ignored) {} }

    static void seekBy(Context c, double sec) {
        double d = dur();
        seekMs((int) (Math.max(0, Math.min(d > 0 ? d : 1e9, pos() + sec)) * 1000));
        PlaybackService.sync(c.getApplicationContext());
    }

    static void applySpeed() {
        try { if (mp != null && ready && mp.isPlaying()) mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
    }

    static void saveNow(Context c) {
        if (mp != null && ready && itemId >= 0) { int s = (int) pos(); if (s > 1) Learn.setAudio(c, itemId, s, null); }
    }

    static synchronized void release(Context c) {
        if (mp != null) {
            saveNow(c);
            try { mp.release(); } catch (Exception ignored) {}
        }
        mp = null; ready = false; itemId = -1; uri = "";
        try { if (focus != null) c.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); } catch (Exception ignored) {}
        focus = null;
        PlaybackService.sync(c.getApplicationContext());
    }
}
