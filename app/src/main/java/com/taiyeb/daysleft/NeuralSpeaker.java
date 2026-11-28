package com.taiyeb.daysleft;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The natural voice. One sentence at a time is turned into sound by Supertonic (sherpa-onnx), a few sentences ahead of what is playing, and
 * streamed to an AudioTrack. Which sentence is audible is read from the track's playback position, so the marker follows the real voice.
 * If synthesis cannot keep ahead of playback on this phone (the listener hears gaps), it reports onSlow and the player falls back to the
 * phone's own voice.
 */
final class NeuralSpeaker implements Narrator {
    private static final int AHEAD = 3, CACHE = 10;
    private static final float BASE_SPEED = 1.15f;   // Supertonic's natural pace is slow for reading a book

    private static final class Chunk {
        final int from, to; final short[] pcm;
        Chunk(int from, int to, short[] pcm) { this.from = from; this.to = to; this.pcm = pcm; }
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Narrator.Listener lis;
    private volatile boolean ready, failed, playing, dead;
    private OfflineTts tts;
    private int sr = 44100;
    private BookIndex bi;
    private String[] tok;
    private volatile float rate = 1f;
    private volatile int pos = -1;
    private volatile int gen;
    private final Map<Integer, short[]> cache = new LinkedHashMap<Integer, short[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, short[]> e) { return size() > CACHE; }
    };
    private AudioTrack track;
    private final int sid;
    private volatile boolean firstDone;
    private volatile Thread prodThread;

    NeuralSpeaker(Context c, Narrator.Listener l) {
        app = c.getApplicationContext();
        lis = l;
        sid = Math.max(0, Math.min(9, Store.num(app, "nvSid", 0)));
        new Thread(() -> {
            try {
                Store.sp(app).edit().putBoolean("nvLoading", true).commit();   // cleared after the first sentence worked: if the app dies inside the library it is still set next time
                File d = NaturalVoice.dir(app);
                OfflineTtsSupertonicModelConfig sc = OfflineTtsSupertonicModelConfig.builder()
                        .setDurationPredictor(new File(d, "duration_predictor.int8.onnx").getPath())
                        .setTextEncoder(new File(d, "text_encoder.int8.onnx").getPath())
                        .setVectorEstimator(new File(d, "vector_estimator.int8.onnx").getPath())
                        .setVocoder(new File(d, "vocoder.int8.onnx").getPath())
                        .setTtsJson(new File(d, "tts.json").getPath())
                        .setUnicodeIndexer(new File(d, "unicode_indexer.bin").getPath())
                        .setVoiceStyle(new File(d, "voice.bin").getPath()).build();
                OfflineTtsModelConfig mc = OfflineTtsModelConfig.builder().setSupertonic(sc).setNumThreads(4).setProvider("cpu").build();
                long t0 = System.currentTimeMillis();
                tts = new OfflineTts(OfflineTtsConfig.builder().setModel(mc).build());
                sr = tts.getSampleRate();
                Dbg.i("Voice", "natural voice loaded in " + (System.currentTimeMillis() - t0) + " ms, " + sr + " Hz");
                ready = true;
            } catch (Throwable e) {
                Store.sp(app).edit().putBoolean("nvLoading", false).commit();
                failed = true;
                Dbg.e("Voice", "the natural voice could not start", e);
            }
            main.post(this::notifyState);
        }, "voice-load").start();
    }

    @Override public boolean ready() { return ready; }
    @Override public boolean failed() { return failed; }
    @Override public boolean playing() { return playing; }
    @Override public int position() { return pos; }
    @Override public void setRate(float r) { rate = r; }

    @Override public void setBook(BookIndex b, int itemId) {
        bi = b;
        tok = new String[b.count()];
        BookText.Disp d = null;
        try { d = BookText.display(app, itemId, b.count()); } catch (Throwable ignored) {}
        for (int i = 0; i < tok.length; i++) tok[i] = d != null && d.tok[i] != null && !d.tok[i].isEmpty() ? d.tok[i] : b.w[i];
    }

    private void notifyState() { Narrator.Listener l = lis; if (l != null) l.onState(); }

    @Override public void playFrom(int word) {
        if (!ready || bi == null || dead) return;
        stopWorkers();
        final int g = ++gen;
        final int start = bi.sentenceAround(Math.max(0, Math.min(bi.count() - 1, word)))[0];
        pos = start;
        playing = true;
        notifyState();
        final LinkedBlockingQueue<Chunk> q = new LinkedBlockingQueue<>(AHEAD);
        final Chunk END = new Chunk(-1, -1, new short[0]);
        // producer: synthesis, a few sentences ahead
        Thread prod = new Thread(() -> {
            try {
                int cursor = start;
                while (g == gen && cursor < bi.count()) {
                    int[] s = bi.sentenceAround(cursor);
                    int from = Math.max(s[0], cursor), to = s[1];
                    cursor = to + 1;
                    String text = textOf(from, to);
                    if (text == null) continue;
                    short[] pcm;
                    synchronized (cache) { pcm = cache.get(from); }
                    if (pcm == null) {
                        pcm = synth(text);
                        if (pcm == null) { if (g == gen) { failed = true; main.post(this::notifyState); } return; }
                        synchronized (cache) { cache.put(from, pcm); }
                    }
                    while (g == gen && !q.offer(new Chunk(from, to, pcm), 200, TimeUnit.MILLISECONDS)) { /* wait for room */ }
                }
                if (g == gen) q.offer(END, 5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } catch (Throwable e) { Dbg.e("Voice", "synthesis failed", e); if (g == gen) { failed = true; main.post(this::notifyState); } }
        }, "voice-synth");
        prod.setPriority(Thread.NORM_PRIORITY);
        prodThread = prod;
        // consumer: plays the sentences in order, remembers when each one starts
        Thread cons = new Thread(() -> {
            AudioTrack tr = null;
            final ArrayDeque<long[]> marks = new ArrayDeque<>();   // {startFrame, from, to}
            long written = 0;
            int slowRun = 0;
            try {
                int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                tr = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(minBuf, sr * 2))   // 1 s
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                synchronized (this) { track = tr; }
                tr.play();
                final AudioTrack ft = tr;
                // marker clock: the sentence whose first frame has been played is the one being heard
                main.post(new Runnable() {
                    @Override public void run() {
                        if (g != gen || dead) return;
                        try {
                            long head = ft.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                            synchronized (marks) {
                                while (!marks.isEmpty() && marks.peekFirst()[0] <= head) {
                                    long[] m = marks.pollFirst();
                                    pos = (int) m[1];
                                    Narrator.Listener l = lis;
                                    if (l != null) l.onSentence((int) m[1], (int) m[2]);
                                }
                            }
                        } catch (Throwable ignored) {}
                        main.postDelayed(this, 40);
                    }
                });
                while (g == gen) {
                    long w0 = System.currentTimeMillis();
                    Chunk ch = q.poll(300, TimeUnit.MILLISECONDS);
                    if (ch == null) continue;   // nothing ready yet: a gap is judged when the next sentence arrives
                    if (ch == END) break;
                    long waitedMs = System.currentTimeMillis() - w0;
                    long headNow = ft.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    boolean dry = written > 0 && headNow >= written;   // everything written was already played: the listener heard silence
                    if (dry) slowRun++; else if (waitedMs < 200) slowRun = 0;
                    if (slowRun >= 3 && g == gen) { Narrator.Listener l = lis; if (l != null) main.post(l::onSlow); return; }
                    synchronized (marks) { marks.addLast(new long[]{written, ch.from, ch.to}); }
                    int off = 0;
                    while (off < ch.pcm.length && g == gen) { int n = ft.write(ch.pcm, off, ch.pcm.length - off); if (n < 0) throw new IllegalStateException("AudioTrack " + n); off += n; }
                    written += ch.pcm.length;
                }
                // the last sound has to finish before the book counts as read
                while (g == gen && (ft.getPlaybackHeadPosition() & 0xFFFFFFFFL) < written) Thread.sleep(50);
                if (g == gen) main.post(() -> { if (g == gen) { playing = false; Narrator.Listener l = lis; if (l != null) { l.onEnd(); l.onState(); } } });
            } catch (InterruptedException ignored) {
            } catch (Throwable e) {
                Dbg.e("Voice", "playback failed", e);
                if (g == gen) { failed = true; main.post(this::notifyState); }
            } finally {
                try { if (tr != null) { tr.stop(); tr.release(); } } catch (Throwable ignored) {}
                synchronized (NeuralSpeaker.this) { if (track == tr) track = null; }
            }
        }, "voice-play");
        cons.setPriority(Thread.MAX_PRIORITY - 2);
        prod.start();
        cons.start();
    }

    private short[] synth(String text) {
        OfflineTts t = tts;
        if (t == null) return null;
        long t0 = System.currentTimeMillis();
        GenerationConfig cfg = new GenerationConfig();
        cfg.setSid(sid);
        cfg.setSpeed(Math.max(0.7f, Math.min(2.4f, BASE_SPEED * rate)));
        cfg.setNumSteps(5);
        Map<String, String> ex = new HashMap<>();
        ex.put("lang", "en");
        cfg.setExtra(ex);
        GeneratedAudio a = t.generateWithConfig(text, cfg);
        float[] f = a == null ? null : a.getSamples();
        if (f == null || f.length == 0) return new short[0];
        short[] out = new short[f.length];
        for (int i = 0; i < f.length; i++) { float v = Math.max(-1f, Math.min(1f, f[i])); out[i] = (short) (v * 32767f); }
        if (!firstDone) { firstDone = true; Store.sp(app).edit().putBoolean("nvLoading", false).commit(); }
        double secs = f.length / (double) sr;
        Dbg.i("Voice", "sentence " + String.format(java.util.Locale.ENGLISH, "%.1f s of speech in %.1f s", secs, (System.currentTimeMillis() - t0) / 1000.0));
        return out;
    }

    private String textOf(int from, int to) {
        StringBuilder sb = new StringBuilder();
        int letters = 0;
        for (int k = from; k <= to && k < tok.length; k++) {
            String t = tok[k];
            for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) letters++;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        if (letters < 2) return null;   // page numbers and lone figures: nothing to read
        return sb.toString().trim();
    }

    private void stopWorkers() {
        gen++;
        AudioTrack t;
        synchronized (this) { t = track; }
        try { if (t != null) { t.pause(); t.flush(); } } catch (Throwable ignored) {}
    }

    @Override public void pause() {
        if (!playing) return;
        playing = false;
        stopWorkers();   // pos stays on the sentence that was audible; resuming starts it again from the cache
        notifyState();
    }

    @Override public void shutdown() {
        dead = true;
        playing = false;
        lis = null;
        stopWorkers();
        final Thread p = prodThread;
        final OfflineTts t = tts;
        tts = null;
        if (t != null) new Thread(() -> {   // never free the native engine while a sentence is still being made
            try { if (p != null) p.join(20000); } catch (InterruptedException ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
        }, "voice-free").start();
    }
}
