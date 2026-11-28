package com.taiyeb.daysleft;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read aloud: the phone's own speech engine reads a book that has no audiobook, sentence by sentence.
 * Each sentence is one utterance and a few are queued ahead, so speech starts at once, a pause or a jump costs nothing, and the reader always knows
 * exactly which sentence is being spoken (no sync needed: the engine tells us). The pitch and pace shift a little for dialogue, questions and
 * exclamations so it does not sound flat. Lives as long as the reader that started it.
 */
class Speaker implements Narrator {

    private static final int AHEAD = 3;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready, failed;
    private Narrator.Listener lis;
    private BookIndex bi;
    private String[] tok;
    volatile boolean playing;
    private volatile float baseRate = 1f;
    private int pos = -1;            // first word of the sentence to speak next / being spoken (where a resume starts)
    private int queuedTo = -1;       // first word AFTER the last queued sentence
    private int queued;              // sentences queued and not yet finished
    private int gen;                 // bumps on every stop, so late callbacks of an old queue are ignored
    private List<Voice> voices = new ArrayList<>();
    String voiceName = "";

    Speaker(Context c, Narrator.Listener l) {
        app = c.getApplicationContext();
        lis = l;
        try {
            tts = new TextToSpeech(app, status -> main.post(() -> init(status)));
        } catch (Throwable e) { failed = true; Dbg.e("Speak", "no speech engine", e); }
    }

    @Override public boolean ready() { return ready; }

    @Override public boolean failed() { return failed; }

    @Override public boolean playing() { return playing; }

    private void init(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) { failed = true; Dbg.e("Speak", "speech engine failed to start, status " + status, null); notifyState(); return; }
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build());
            Locale want = Locale.getDefault().getLanguage().equals("en") ? Locale.getDefault() : Locale.US;
            if (tts.isLanguageAvailable(want) < TextToSpeech.LANG_AVAILABLE) want = Locale.US;
            tts.setLanguage(want);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { main.post(() -> started(id)); }
                @Override public void onDone(String id) { main.post(() -> finished(id)); }
                @Override public void onError(String id) { main.post(() -> finished(id)); }
            });
            loadVoices();
            ready = true;
            Dbg.i("Speak", "engine ready, voice " + voiceName + ", " + voices.size() + " English voices");
        } catch (Throwable e) { failed = true; Dbg.e("Speak", "speech engine setup failed", e); }
        notifyState();
    }

    /** English voices that work offline, best quality first; the saved choice wins. */
    private void loadVoices() {
        voices = new ArrayList<>();
        try {
            Set<Voice> all = tts.getVoices();
            if (all != null) for (Voice v : all) {
                if (!"en".equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (v.isNetworkConnectionRequired()) continue;   // reading must not depend on the internet
                voices.add(v);
            }
        } catch (Throwable ignored) {}
        java.util.Collections.sort(voices, (a, b) -> {
            int d = b.getQuality() - a.getQuality();
            return d != 0 ? d : a.getName().compareTo(b.getName());
        });
        String saved = Store.sp(app).getString("ttsVoice", "");
        Voice pick = null;
        for (Voice v : voices) if (v.getName().equals(saved)) pick = v;
        if (pick == null && !voices.isEmpty()) pick = voices.get(0);
        if (pick != null) { try { tts.setVoice(pick); voiceName = pick.getName(); } catch (Throwable ignored) {} }
    }

    int voiceCount() { return voices.size(); }

    /** Next voice in the list; the caller restarts the sentence so it is heard at once. Returns a short name for a toast. */
    String nextVoice() {
        if (!ready || voices.size() < 2) return null;
        int i = 0;
        for (int k = 0; k < voices.size(); k++) if (voices.get(k).getName().equals(voiceName)) i = k;
        Voice v = voices.get((i + 1) % voices.size());
        try { tts.setVoice(v); } catch (Throwable e) { return null; }
        voiceName = v.getName();
        Store.sp(app).edit().putString("ttsVoice", voiceName).apply();
        boolean was = playing;
        if (was) restart();
        return friendly(v);
    }

    static String friendly(Voice v) {
        String n = v.getName().replace('_', ' ').replace('-', ' ');
        return n.length() > 28 ? n.substring(0, 28) : n;
    }

    @Override public void setRate(float r) { baseRate = r; }

    @Override public void setBook(BookIndex b, int itemId) {
        bi = b;
        tok = new String[b.count()];
        BookText.Disp d = null;
        try { d = BookText.display(app, itemId, b.count()); } catch (Throwable ignored) {}
        for (int i = 0; i < tok.length; i++) tok[i] = d != null && d.tok[i] != null && !d.tok[i].isEmpty() ? d.tok[i] : b.w[i];
    }

    boolean hasBook() { return bi != null; }

    @Override public int position() { return pos; }

    /** Speak from this word (the start of its sentence). */
    @Override public void playFrom(int word) {
        if (!ready || bi == null) return;
        stopQueue();
        pos = bi.sentenceAround(Math.max(0, Math.min(bi.count() - 1, word)))[0];
        playing = true;
        queuedTo = pos;
        fill();
        notifyState();
    }

    @Override public void pause() {
        if (!playing) return;
        playing = false;
        stopQueue();     // pos stays on the sentence that was being spoken: resume starts there
        notifyState();
    }

    void resume() { if (pos >= 0) playFrom(pos); }

    private void restart() { if (pos >= 0) playFrom(pos); }

    @Override public void shutdown() {
        playing = false;
        gen++;
        lis = null;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null;
    }

    private void stopQueue() {
        gen++;
        queued = 0;
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
    }

    private void notifyState() { Narrator.Listener l = lis; if (l != null) l.onState(); }

    /** Keeps AHEAD sentences queued. */
    private void fill() {
        while (playing && ready && bi != null && queued < AHEAD && queuedTo < bi.count()) {
            int[] s = bi.sentenceAround(queuedTo);
            int from = Math.max(s[0], queuedTo), to = s[1];
            queuedTo = to + 1;
            String text = textOf(from, to);
            if (text == null) continue;   // a page number, a lone figure: nothing to read
            speak(from, to, text);
            queued++;
        }
        if (playing && queued == 0 && queuedTo >= bi.count()) { playing = false; notifyState(); Narrator.Listener l = lis; if (l != null) l.onEnd(); }
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
        if (letters < 2) return null;                                  // numbers and symbols only (page numbers)
        String s = sb.toString().trim();
        return s.length() > TextToSpeech.getMaxSpeechInputLength() - 1 ? s.substring(0, TextToSpeech.getMaxSpeechInputLength() - 1) : s;
    }

    private void speak(int from, int to, String text) {
        // a little life: dialogue a touch warmer and slower, questions lifted, exclamations brighter and quicker
        float pitch = 1f, rate = baseRate;
        char first = text.charAt(0), last = text.charAt(text.length() - 1);
        boolean quote = "\"“‘'".indexOf(first) >= 0;
        if (quote) { pitch += 0.05f; rate *= 0.97f; }
        if (last == '?' || (text.length() > 1 && text.charAt(text.length() - 2) == '?' && "\"”’'".indexOf(last) >= 0)) pitch += 0.05f;
        if (last == '!' || (text.length() > 1 && text.charAt(text.length() - 2) == '!' && "\"”’'".indexOf(last) >= 0)) { pitch += 0.08f; rate *= 1.03f; }
        try {
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);
            Bundle b = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_ADD, b, "s" + from + "_" + to + "_" + gen);
        } catch (Throwable e) { Dbg.e("Speak", "speak failed", e); }
    }

    private void started(String id) {
        int[] r = parse(id);
        if (r == null || r[2] != gen || !playing) return;
        pos = r[0];
        Narrator.Listener l = lis;
        if (l != null) l.onSentence(r[0], r[1]);
    }

    private void finished(String id) {
        int[] r = parse(id);
        if (r == null || r[2] != gen) return;
        if (queued > 0) queued--;
        if (r != null) pos = Math.min(r[1] + 1, Math.max(0, bi == null ? 0 : bi.count() - 1));
        fill();
    }

    private static int[] parse(String id) {
        try {
            String[] q = id.substring(1).split("_");
            return new int[]{Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2])};
        } catch (Exception e) { return null; }
    }
}
