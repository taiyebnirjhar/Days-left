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

    // ---- read aloud: a book the phone reads itself. Same player for the rest of the app: time, seek, speed, notification, pins, marker. ----
    static boolean virtual;
    private static Narrator spk;
    private static Timeline tl;
    private static double vT;                    // where the player is (nominal seconds) when no sentence is running
    private static double vSentStart, vSentEnd;  // nominal bounds of the sentence being spoken
    private static long vWall;                   // wall clock when that sentence began
    private static boolean vSpeaking, vPending;  // a sentence is running / Play is waiting for the speech engine

    /** True while a player (a file or a read-aloud book) is open. */
    static boolean active() { return mp != null || virtual; }

    static boolean has(Item it) { return virtual ? itemId == it.id && it.audio.isEmpty() : mp != null && itemId == it.id && uri.equals(it.audio); }

    /** Makes sure a player for this item exists (keeps the current one when it already matches). */
    static synchronized void open(Context c, Item it) {
        Context app = c.getApplicationContext();
        if (has(it)) return;
        release(app);
        if (it.audio.isEmpty() && it.speak) { openVirtual(app, it); return; }
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

    static boolean isPlaying() {
        if (virtual) return ready && spk != null && spk.playing();
        try { return mp != null && ready && mp.isPlaying(); } catch (Exception e) { return false; }
    }
    static double pos() {
        if (virtual) {
            if (!ready || tl == null) return 0;
            if (vSpeaking && spk != null && spk.playing()) lastPos = Math.min(vSentEnd, vSentStart + (System.currentTimeMillis() - vWall) / 1000.0 * speed);
            else lastPos = vT;
            return lastPos;
        }
        try { if (mp != null && ready) { lastPos = mp.getCurrentPosition() / 1000.0; return lastPos; } return 0; } catch (Exception e) { return 0; }
    }
    static double dur() {
        if (virtual) return ready && tl != null ? tl.total : 0;
        try { return mp != null && ready ? mp.getDuration() / 1000.0 : 0; } catch (Exception e) { return 0; }
    }

    private static void openVirtual(Context app, Item it) {
        final int id = it.id;
        itemId = id; uri = ""; title = it.title; ready = false; virtual = true; vSpeaking = false; vPending = false; vT = Math.max(0, it.apos);
        Dbg.i("Player", "opening read-aloud book " + id);
        new Thread(() -> {
            try {
                BookIndex b = BookText.ready(app, id) ? BookText.index(app, id) : BookText.extract(app, it, pc -> {}, new boolean[1]);
                final Timeline t = new Timeline(b);
                t.writeAlignment(BookText.alignFile(app, id));
                Store.sp(app).edit().putBoolean("syncFull_" + id, true).putInt("syncPct_" + id, 100).remove("syncWant_" + id).remove("syncPaused_" + id).apply();
                main.post(() -> {
                    if (itemId != id || !virtual) return;
                    tl = t;
                    vBook = b;
                    spk = makeNarrator(app, id);
                    spk.setBook(b, id);
                    spk.setRate(speed);
                    if (vT > tl.total) vT = 0;
                    ready = true;
                    Dbg.i("Player", "read-aloud ready, " + (int) tl.total + " s nominal");
                    if (onReady != null) onReady.run();
                    if (playWhenReady) { playWhenReady = false; play(app); }
                });
            } catch (Throwable e) {
                Dbg.e("Player", "could not prepare the read-aloud book", e);
                main.post(() -> { if (itemId == id) { virtual = false; itemId = -1; android.widget.Toast.makeText(app, "Couldn't read this PDF's text.", android.widget.Toast.LENGTH_LONG).show(); } });
            }
        }, "read-aloud-open").start();
    }

    private static BookIndex vBook;
    private static boolean neural;

    private static Narrator makeNarrator(Context app, int id) {
        NaturalVoice.checkCrash(app);
        Narrator.Listener l = new Narrator.Listener() {
            @Override public void onSentence(int from, int to) {
                if (tl == null) return;
                int s = tl.sentenceOf(from);
                vSentStart = tl.start[s]; vSentEnd = tl.end[s]; vWall = System.currentTimeMillis(); vSpeaking = true; vT = vSentStart;
            }
            @Override public void onEnd() { vSpeaking = false; vT = tl == null ? 0 : tl.total; PlaybackService.sync(app); }
            @Override public void onSlow() {
                Dbg.e("Voice", "the natural voice cannot keep up on this phone: switching to the phone's own voice", null);
                Store.setFlag(app, "nvSlow", true);
                main.post(() -> { android.widget.Toast.makeText(app, "The natural voice is too slow on this phone. Using the phone's own voice.", android.widget.Toast.LENGTH_LONG).show(); useSystemVoice(app, id); });
            }
            @Override public void onState() {
                if (spk != null && spk.failed() && neural) { main.post(() -> useSystemVoice(app, id)); return; }
                if (vPending && spk != null && spk.ready()) { vPending = false; startAt(app); }
                if (spk != null && spk.failed() && vPending) { vPending = false; main.post(() -> android.widget.Toast.makeText(app, "No speech engine found. Install \"Speech Services by Google\" from the Play Store.", android.widget.Toast.LENGTH_LONG).show()); }
                PlaybackService.sync(app);
            }
        };
        neural = NaturalVoice.usable(app);
        Narrator n = neural ? new NeuralSpeaker(app, l) : new Speaker(app, l);
        Dbg.i("Player", "reading with the " + (neural ? "natural voice" : "phone's own voice"));
        return n;
    }

    /** The natural voice failed or is too slow: carry on with the phone's own engine from the same place. */
    private static void useSystemVoice(Context app, int id) {
        if (!virtual || itemId != id || !neural) return;
        boolean was = spk != null && spk.playing();
        double at = pos();
        if (spk != null) spk.shutdown();
        neural = false;
        vT = at; vSpeaking = false;
        Narrator n = makeSystem(app, id);
        spk = n;
        n.setBook(vBook, id); n.setRate(speed);
        if (was || vPending) { vPending = true; if (n.ready()) { vPending = false; startAt(app); } }
    }

    private static Narrator makeSystem(Context app, int id) {
        Narrator.Listener l = new Narrator.Listener() {
            @Override public void onSentence(int from, int to) {
                if (tl == null) return;
                int s = tl.sentenceOf(from);
                vSentStart = tl.start[s]; vSentEnd = tl.end[s]; vWall = System.currentTimeMillis(); vSpeaking = true; vT = vSentStart;
            }
            @Override public void onEnd() { vSpeaking = false; vT = tl == null ? 0 : tl.total; PlaybackService.sync(app); }
            @Override public void onState() {
                if (vPending && spk != null && spk.ready()) { vPending = false; startAt(app); }
                if (spk != null && spk.failed() && vPending) { vPending = false; main.post(() -> android.widget.Toast.makeText(app, "No speech engine found. Install \"Speech Services by Google\" from the Play Store.", android.widget.Toast.LENGTH_LONG).show()); }
                PlaybackService.sync(app);
            }
        };
        return new Speaker(app, l);
    }

    private static void startAt(Context app) {
        if (spk == null || tl == null) return;
        vSpeaking = false;
        spk.playFrom(tl.wordAt(vT));
    }

    private static Context appRef;

    // One focus request for the whole time the player exists. Building a new request on every Play made Android tell
    // the previous request "focus lost", and its listener then paused the player right after it started.
    private static final AudioManager.OnAudioFocusChangeListener focusListener = ch -> main.post(() -> {
        Dbg.i("Player", "audio focus change " + ch);
        Context app = appRef;
        if (app == null) return;
        if (ch == AudioManager.AUDIOFOCUS_LOSS) { resumeAfterFocus = false; pause(app); }
        else if (ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeAfterFocus = isPlaying(); pause(app); }
        else if (ch == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) { resumeAfterFocus = false; play(app); }
    });

    private static boolean getFocus(Context c) {
        appRef = c.getApplicationContext();
        AudioManager am = appRef.getSystemService(AudioManager.class);
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
        }
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    static void play(Context c) {
        Context app = c.getApplicationContext();
        if (virtual) {
            if (!ready || spk == null) { playWhenReady = true; return; }
            getFocus(app);
            spk.setRate(speed);
            if (spk.ready()) startAt(app); else vPending = true;
            PlaybackService.sync(app);
            return;
        }
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
        // listening on, possibly with the app in the background: the sync follows the playhead (it exits by itself when idle)
        try {
            if (itemId >= 0 && !SyncService.running && SyncService.wanted(app, itemId) && Whisper.hasModel(app)) SyncService.start(app, itemId);
        } catch (Exception e) { Dbg.w("Player", "could not wake the sync: " + e); }
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
        if (virtual) {
            vPending = false; playWhenReady = false;
            if (spk != null && spk.playing()) { vT = pos(); vSpeaking = false; spk.pause(); }
            saveNow(app);
            PlaybackService.sync(app);
            return;
        }
        try { if (mp != null && ready && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        saveNow(app);
        PlaybackService.sync(app);
    }

    static void toggle(Context c) { if (isPlaying()) pause(c); else play(c); }

    static void seekMs(int ms) {
        if (virtual) {
            if (!ready || tl == null) return;
            vT = Math.max(0, Math.min(tl.total, ms / 1000.0));
            int s = tl.sentenceAt(vT);
            vSentStart = tl.start[s]; vSentEnd = tl.end[s]; vWall = System.currentTimeMillis();
            if (spk != null && spk.playing()) { vSpeaking = false; spk.playFrom(tl.wordAt(vT)); }
            return;
        }
        try { if (mp != null && ready) mp.seekTo(Math.max(0, ms)); } catch (Exception ignored) {}
    }

    static void seekBy(Context c, double sec) {
        double d = dur();
        seekMs((int) (Math.max(0, Math.min(d > 0 ? d : 1e9, pos() + sec)) * 1000));
        PlaybackService.sync(c.getApplicationContext());
    }

    static void applySpeed() {
        if (virtual) { if (spk != null) spk.setRate(speed); return; }
        try { if (mp != null && ready && mp.isPlaying()) mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
    }

    static void saveNow(Context c) {
        if ((mp != null || virtual) && ready && itemId >= 0) { int s = (int) pos(); if (s > 1) Learn.setAudio(c, itemId, s, null); }
    }

    static synchronized void release(Context c) {
        if (virtual) {
            saveNow(c);
            if (spk != null) { spk.shutdown(); spk = null; }
            tl = null; virtual = false; vSpeaking = false; vPending = false; vBook = null;
        }
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
