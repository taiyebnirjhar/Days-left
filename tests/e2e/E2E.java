package com.taiyeb.daysleft;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * End-to-end test of the sync, as close to the phone as a laptop gets.
 *
 * Real: the app's SyncCore / SyncPlanner / Aligner / Alignment / WordSplit (the very code the service runs), the real speech model
 * (whisper.cpp, same parameters as the phone's nRunWords), real speech audio with AAC compression and noise.
 * Modelled: the phone's speed (a call costs 2.0 s + 0.17 s per second of audio, decoding about 1 s) so latencies are phone-time,
 * and the reader's 10 s alignment reload.
 *
 * It scores what a listener would see against a known timeline: is a sentence highlighted when it should be, is it the right one,
 * how long until the first highlight, does Play page / Find page answer correctly, does a far jump recover, does resuming work.
 */
public class E2E {
    // ---------------------------------------------------------------- the world
    static float[] wav;
    static double duration;
    static String[] kind, text;
    static boolean[] spoken;
    static double[] st, en;
    static BookIndex bi;
    static int[] wordEl, elStartW, elEndW;
    static int[] pageStart;
    static Aligner aligner;
    static int nWords;
    static boolean verbose;

    static void loadData(String dir) throws Exception {
        // audio: 16 kHz mono 16-bit wav
        byte[] b = Files.readAllBytes(Paths.get(dir, "audio16k.wav"));
        int data = 44;
        for (int i = 12; i + 8 < b.length; ) {   // find the "data" chunk
            String id = new String(b, i, 4); int sz = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("data")) { data = i + 8; break; }
            i += 8 + sz;
        }
        int n = (b.length - data) / 2;
        wav = new float[n];
        ByteBuffer bb = ByteBuffer.wrap(b, data, n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) wav[i] = bb.getShort() / 32768f;
        duration = n / 16000.0;
        // book + truth
        List<String> lines = Files.readAllLines(Paths.get(dir, "truth.tsv"));
        int m = lines.size();
        kind = new String[m]; text = new String[m]; spoken = new boolean[m]; st = new double[m]; en = new double[m];
        for (int i = 0; i < m; i++) {
            String[] p = lines.get(i).split("\t", 5);
            kind[i] = p[0]; spoken[i] = p[1].equals("1"); st[i] = Double.parseDouble(p[2]); en[i] = Double.parseDouble(p[3]); text[i] = p.length > 4 ? p[4] : "";
        }
        // words, sentence ends (every element is a sentence), pages of ~250 words
        List<String> words = new ArrayList<>();
        elStartW = new int[m]; elEndW = new int[m];
        List<Integer> el = new ArrayList<>();
        List<Integer> pages = new ArrayList<>(); pages.add(0);
        int sincePage = 0;
        for (int i = 0; i < m; i++) {
            List<String> ws = PageMatcher.words(text[i]);
            elStartW[i] = words.size();
            if (sincePage >= 250) { pages.add(words.size()); sincePage = 0; }
            for (String w : ws) { words.add(w); el.add(i); }
            elEndW[i] = words.size() - 1;
            sincePage += ws.size();
        }
        nWords = words.size();
        byte[] flag = new byte[nWords];
        for (int i = 0; i < m; i++) if (elEndW[i] >= elStartW[i]) flag[elEndW[i]] |= 1;
        pages.add(nWords);
        pageStart = new int[pages.size()];
        for (int i = 0; i < pageStart.length; i++) pageStart[i] = pages.get(i);
        wordEl = new int[nWords];
        for (int i = 0; i < nWords; i++) wordEl[i] = el.get(i);
        bi = new BookIndex(words.toArray(new String[0]), pageStart, new float[nWords * 4], flag);
        aligner = new Aligner(bi);
    }

    /** The element being spoken at second t (truth), or -1 in a pause or when nothing is read. */
    static int truthAt(double t) {
        for (int i = 0; i < st.length; i++) if (spoken[i] && kind[i].equals("sentence") || spoken[i] && kind[i].equals("heading")) if (t >= st[i] && t <= en[i]) return i;
        return -1;
    }
    /** Seconds from p until the narrator next reads something from the book (an intro is not in the book). */
    static double nextSpeechDelay(double p) {
        double best = 1e9;
        for (int i = 0; i < st.length; i++) if (spoken[i] && (kind[i].equals("sentence") || kind[i].equals("heading")) && en[i] > p) best = Math.min(best, Math.max(0, st[i] - p));
        return best >= 1e9 ? 0 : best;
    }
    /**
     * How wrong a highlight is, in seconds, the way a listener perceives it: how far the switch between sentences is from the true
     * boundary. Lagging (highlighting an earlier sentence): how long ago the narrator started the next one. Leading: how long until
     * the highlighted sentence really starts. A sentence the narrator never read is wrong however you look at it.
     */
    static double switchError(int h, int e, double t) {
        if (h == e) return 0;
        if (!spoken[h] || !(kind[h].equals("sentence") || kind[h].equals("heading"))) return 1e9;
        if (h < e) return Math.max(0, t - st[nextSpoken(h + 1)]);
        return Math.max(0, st[h] - t);
    }
    /** First element at or after e that is really spoken. */
    static int nextSpoken(int e) { while (e < st.length && !(spoken[e] && (kind[e].equals("sentence") || kind[e].equals("heading")))) e++; return Math.min(e, st.length - 1); }
    static int pageOfWord(int w) { return bi.pageOf(w); }

    // ---------------------------------------------------------------- the speech model (a child process running whisper.cpp)
    static Process drv; static BufferedWriter dIn; static BufferedReader dOut; static File rawFile;
    static void startDriver(String driver, String model, int threads) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(driver, model, "-", String.valueOf(threads), "serve");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        drv = pb.start();
        dIn = new BufferedWriter(new OutputStreamWriter(drv.getOutputStream()));
        dOut = new BufferedReader(new InputStreamReader(drv.getInputStream()));
        String l = dOut.readLine();
        if (!"READY".equals(l)) throw new IOException("speech model did not start: " + l);
        rawFile = File.createTempFile("e2e", ".raw"); rawFile.deleteOnExit();
    }

    static String recognizeRaw(float[] buf) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(buf.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : buf) bb.putFloat(f);
        Files.write(rawFile.toPath(), bb.array());
        dIn.write("RUN " + rawFile.getAbsolutePath() + "\n"); dIn.flush();
        StringBuilder out = new StringBuilder();
        for (String l; (l = dOut.readLine()) != null && !l.equals("END"); ) {
            if (l.startsWith("ERR")) return null;
            if (l.startsWith("SEG\t")) out.append(l.substring(4)).append('\n');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- a user session
    static final double SPEECH_FIXED = 2.0, SPEECH_PER_SEC = 0.17, DECODE = 1.0, RELOAD = 10.0;

    static final class Session implements SyncCore.Hearer, SyncCore.Sink {
        final SyncPlanner pl; final SyncCore core;
        final List<int[]> aPos = new ArrayList<>(); final List<Double> aTime = new ArrayList<>();   // what the service appends to the anchors file
        double simTime, playhead; boolean playing = true;
        int goal = -1;
        double lastCost, readyAt = -1, firstShownAt = -1, speechDelay;
        int lastEl = -1;
        int calls, failedPieces, pieces;
        Alignment visible = new Alignment(); double nextReload = RELOAD;
        // scoring
        int samples, shown, correct, wrong; final List<Double> offs = new ArrayList<>();
        int unshown;

        Session(double playhead, List<int[]> priorPos, List<Double> priorTime) {
            this.playhead = playhead;
            speechDelay = nextSpeechDelay(playhead);
            int n = priorPos.size(); int[] p = new int[n]; double[] t = new double[n];
            for (int i = 0; i < n; i++) { p[i] = priorPos.get(i)[0]; t[i] = priorTime.get(i); aPos.add(priorPos.get(i)); aTime.add(priorTime.get(i)); }
            pl = new SyncPlanner(duration, playhead, nWords, p, t);
            core = new SyncCore(pl, aligner, this, this, 0);
            visible = snapshot();
        }

        Alignment snapshot() { return Alignment.fromPoints(aPos, aTime).withBook(bi); }   // the reader does the same

        // Hearer
        public void beforeWork() {}
        public float[][] decode(double[] s, double[] l) {
            float[][] out = new float[s.length][];
            for (int i = 0; i < s.length; i++) {
                int a = (int) Math.max(0, Math.min(wav.length, s[i] * 16000)), b = (int) Math.max(a, Math.min(wav.length, (s[i] + l[i]) * 16000));
                out[i] = Arrays.copyOfRange(wav, a, b);
            }
            return out;
        }
        public String[] recognize(float[][] pcm) throws Exception {
            double[] off = new double[pcm.length], len = new double[pcm.length];
            float[] buf = WordSplit.combine(pcm, off, len);
            String raw = recognizeRaw(buf);
            if (raw == null) return null;
            lastCost = SPEECH_FIXED + SPEECH_PER_SEC * (buf.length / 16000.0) + DECODE;
            calls++;
            return WordSplit.split(raw, off, len, true);
        }
        // Sink
        public void anchors(int[] pos, double[] time) { for (int i = 0; i < pos.length; i++) { aPos.add(new int[]{pos[i]}); aTime.add(time[i]); } }
        public void readyChanged(boolean r, double zd, double zt) {}
        public void log(String m) { if (verbose) System.out.printf("      [%6.1fs] %s%n", simTime, m); }
        public void batch(int n, long dec, long tr, String d) { if (verbose) System.out.println("      call:" + d); }

        /** Advance the simulated clock by `cost` seconds, scoring what the listener sees second by second with the alignment the reader has. */
        void advance(double cost, boolean score) {
            double end = simTime + cost;
            while (simTime < end - 1e-9) {
                double dt = Math.min(1.0, end - simTime);
                simTime += dt;
                if (playing) playhead = Math.min(duration - 1, playhead + dt);
                if (simTime >= nextReload) { visible = snapshot(); nextReload += RELOAD; }
                if (playing) sample(score);
            }
        }

        void sample(boolean score) {
            int e = truthAt(playhead);
            int hw = visible.highlightWordAt(playhead);
            if (hw >= 0 && firstShownAt < 0) firstShownAt = simTime;
            if (!score || e < 0) return;
            samples++;
            if (hw < 0) { unshown++; return; }
            shown++;
            int[] sr = Highlight.sentenceFor(bi, hw);          // the reader never lights up a page number: it keeps what it showed
            int h = sr == null ? lastEl : wordEl[sr[0]];
            if (h < 0) { shown--; unshown++; return; }
            lastEl = h;
            double d = switchError(h, e, playhead);
            offs.add(Math.min(d, 60.0));
            if (d <= 0.9) correct++; else { wrong++; if (diag && diagCount++ < 40) diagnose(hw, e, h, d); }
        }

        /** What the listener saw at this moment and why: the highlighted sentence, the true one, and the probes around the playhead. */
        void diagnose(int hw, int e, int h, double d) {
            int[] w = visible.words(); double[] t = visible.times();
            int lo = 0; while (lo < t.length && t[lo] < playhead) lo++;
            StringBuilder near = new StringBuilder();
            for (int k = Math.max(0, lo - 2); k < Math.min(t.length, lo + 2); k++) near.append(String.format(" (%.1fs->w%d)", t[k], w[k]));
            System.out.printf("      WRONG at %.1f s (sim %.0f): showing [%d %.1f-%.1f %s] but the narrator is in [%d %.1f-%.1f %s]; off by %.1f s; gap %.1f s; word %d (true word range %d..%d); probes:%s%n",
                    playhead, simTime, h, st[h], en[h], cut(text[h]), e, st[e], en[e], cut(text[e]), d, visible.gapAt(playhead), hw, elStartW[e], elEndW[e], near);
        }
        String cut(String s) { return "'" + (s.length() > 38 ? s.substring(0, 38) : s) + "'"; }
        boolean diag = "1".equals(System.getenv("E2E_DIAG")); int diagCount;

        /** One round of the sync. Returns false when it has nothing left to do. */
        boolean round(boolean score) throws Exception {
            lastCost = 0;
            int rc = core.step(playhead, true, goal, (long) (simTime * 1000));
            if (core.ready && readyAt < 0) readyAt = simTime;
            if (rc == SyncCore.LOST) { advance(2, score); return false; }
            if (rc == SyncCore.IDLE) { advance(2, score); return false; }
            advance(lastCost > 0 ? lastCost : 0.2, score);
            return true;
        }
        void runFor(double seconds, boolean score) throws Exception {
            double until = simTime + seconds;
            while (simTime < until) if (!round(score)) { advance(Math.min(2, until - simTime), score); }
        }
    }

    // ---------------------------------------------------------------- reporting
    static double pct(List<Double> v, double p) { if (v.isEmpty()) return -1; List<Double> s = new ArrayList<>(v); Collections.sort(s); return s.get((int) Math.min(s.size() - 1, Math.floor(p * s.size()))); }
    static final List<String> failures = new ArrayList<>();
    static void check(boolean ok, String what) { if (!ok) failures.add(what); System.out.println("   " + (ok ? "PASS " : "FAIL ") + what); }

    static List<Double> allOffs = new ArrayList<>();
    static int totSamples, totShown, totCorrect, totWrong;
    static void add(Session s) { totSamples += s.samples; totShown += s.shown; totCorrect += s.correct; totWrong += s.wrong; allOffs.addAll(s.offs); }

    // ---------------------------------------------------------------- scenarios
    /** A random moment where the narrator is speaking, not too close to the end. */
    static double randomSpeechTime(Random r, double from, double to) {
        for (int k = 0; k < 1000; k++) {
            int e = r.nextInt(st.length);
            if (spoken[e] && (kind[e].equals("sentence")) && st[e] > from && st[e] < to) return st[e] + 0.3;
        }
        return from + 5;
    }

    static void pressPlay(Random r, int trials, double listenSec) throws Exception {
        System.out.println("\n== 1. Press play on an unsynced book, then listen for " + (int) listenSec / 60 + " min (x" + trials + ")");
        List<Double> firstShown = new ArrayList<>(), ready = new ArrayList<>(); int never = 0; int calls = 0, failed = 0, pieces = 0;
        double[] starts = new double[trials];
        for (int i = 0; i < trials; i++) starts[i] = i == 0 ? 0.0 : i == 1 ? 6.0 : randomSpeechTime(r, 60, duration - listenSec - 60);   // 0:00 means the intro: the narrator is silent at first
        for (double p : starts) {
            Session s = new Session(p, new ArrayList<>(), new ArrayList<>());
            s.runFor(listenSec, true);
            add(s);
            if (s.firstShownAt < 0) never++; else firstShown.add(Math.max(0, s.firstShownAt - s.speechDelay));
            if (s.readyAt >= 0) ready.add(Math.max(0, s.readyAt - s.speechDelay));
            calls += s.calls;
            System.out.printf("   start %7.1f s: first highlight %s, ready %s (counted from when the book is first read, %.0f s in), %d speech calls, shown %d%% of speech, wrong %d%n", p,
                    s.firstShownAt < 0 ? "NEVER" : String.format("after %.0f s", Math.max(0, s.firstShownAt - s.speechDelay)), s.readyAt < 0 ? "NEVER" : String.format("after %.0f s", Math.max(0, s.readyAt - s.speechDelay)), s.speechDelay, s.calls,
                    s.samples == 0 ? 0 : s.shown * 100 / s.samples, s.wrong);
        }
        check(never == 0, "a highlight appears in every trial (" + never + " never)");
        check(pct(firstShown, 0.5) <= 45, String.format("median time to the first highlight %.0f s (limit 45 s, phone time)", pct(firstShown, 0.5)));
        check(pct(ready, 0.9) <= 90, String.format("the area around the playhead is ready within %.0f s at the 90th percentile (limit 90 s)", pct(ready, 0.9)));
        check(pct(firstShown, 0.9) <= 75, String.format("90th percentile time to the first highlight %.0f s (limit 75 s)", pct(firstShown, 0.9)));
    }

    static void jumps(Random r, int trials) throws Exception {
        System.out.println("\n== 2. Listen, then jump far away (Play page / seek), keep listening (x" + trials + ")");
        List<Double> recover = new ArrayList<>();
        for (int i = 0; i < trials; i++) {
            Session s = new Session(randomSpeechTime(r, 60, duration * 0.4), new ArrayList<>(), new ArrayList<>());
            s.runFor(100, false);
            // jump: Play page far away is a goal search, then playback starts there
            int tgtEl = nextSpoken((int) (st.length * (0.55 + 0.4 * r.nextDouble())));
            double tgtT = st[tgtEl];
            s.goal = elStartW[tgtEl];
            s.playing = false;     // the user waits for the answer
            double t0 = s.simTime;
            for (int k = 0; k < 20 && s.core.goalTime == -1; k++) s.round(false);
            double answered = s.core.goalTime;
            double err = answered >= 0 ? Math.abs(answered - tgtT) : 999;
            s.goal = -1; s.core.step(s.playhead, true, -1, (long) (s.simTime * 1000));
            s.playhead = answered >= 0 ? Math.max(0, answered - 0.3) : tgtT; s.playing = true; s.firstShownAt = -1;
            double jumpAt = s.simTime;
            s.runFor(200, true);
            add(s);
            double rec = s.firstShownAt < 0 ? 999 : s.firstShownAt - jumpAt; recover.add(rec);
            System.out.printf("   search %.0f s, answer error %.2f s, then first highlight after %s%n", s.simTime - t0 - 200 < 0 ? 0 : 0.0 + (jumpAt - t0), err, rec > 900 ? "NEVER" : String.format("%.0f s", rec));
        }
        check(pct(recover, 0.9) <= 60, String.format("after a far jump the highlight is back in %.0f s (90th percentile, limit 60 s)", pct(recover, 0.9)));
    }

    static void playPage(Random r, int trials) throws Exception {
        System.out.println("\n== 3. Play page: when is the first word of a page spoken? (x" + trials + ")");
        List<Double> errs = new ArrayList<>(), lat = new ArrayList<>(); int unanswered = 0;
        for (int i = 0; i < trials; i++) {
            Session s = new Session(randomSpeechTime(r, 60, duration * 0.3), new ArrayList<>(), new ArrayList<>());
            s.playing = false;
            s.runFor(0, false);
            if (i % 2 == 0) { s.playing = true; s.runFor(60, false); s.playing = false; }   // half of the time the zone around the playhead is already mapped
            int pg = 1 + r.nextInt(pageStart.length - 3);
            int firstEl = wordEl[pageStart[pg]]; int tgtEl = nextSpoken(firstEl);
            s.goal = pageStart[pg] + 0;
            // the word may belong to an element the narrator skipped; the goal is then the start of the next spoken element
            if (tgtEl != firstEl) s.goal = elStartW[tgtEl];
            double t0 = s.simTime;
            for (int k = 0; k < 25 && s.core.goalTime == -1; k++) s.round(false);
            double ans = s.core.goalTime;
            if (ans < 0) { unanswered++; System.out.printf("   page %d: NO ANSWER%n", pg + 1); continue; }
            double e = Math.abs(ans - st[tgtEl]);
            errs.add(e); lat.add(s.simTime - t0);
            if (verbose || e > 2) System.out.printf("   page %d: answer error %.2f s, took %.0f s%n", pg + 1, e, s.simTime - t0);
        }
        System.out.printf("   answered %d of %d; error median %.2f s, 95th %.2f s; time median %.0f s, 95th %.0f s%n", errs.size(), trials, pct(errs, .5), pct(errs, .95), pct(lat, .5), pct(lat, .95));
        check(unanswered == 0, "every page is found (" + unanswered + " unanswered)");
        check(pct(errs, 0.95) <= 3.0, String.format("page start accurate to %.2f s at the 95th percentile (limit 3 s)", pct(errs, 0.95)));
        check(pct(errs, 0.5) <= 1.5, String.format("page start accurate to %.2f s at the median (limit 1.5 s)", pct(errs, 0.5)));
        check(pct(lat, 0.95) <= 60, String.format("a page is found in %.0f s at the 95th percentile (limit 60 s, phone time)", pct(lat, 0.95)));
    }

    static void findPage(Random r, int trials) throws Exception {
        System.out.println("\n== 4. Find page: which page is the narrator on? (x" + trials + ")");
        int right = 0, answered = 0; List<Double> lat = new ArrayList<>();
        for (int i = 0; i < trials; i++) {
            double p = randomSpeechTime(r, 40, duration - 60);
            Session s = new Session(p, new ArrayList<>(), new ArrayList<>());
            s.playing = false;           // paused where the narrator was: "Find page"
            double t0 = s.simTime;
            for (int k = 0; k < 25 && s.core.hereWord < 0; k++) s.round(false);
            int hw = s.core.hereWord;
            if (hw < 0) { System.out.printf("   at %.0f s: NO ANSWER%n", p); continue; }
            answered++; lat.add(s.simTime - t0);
            int e = truthAt(p); int tp = e < 0 ? -1 : pageOfWord(Math.max(0, elStartW[e]));
            int ap = pageOfWord(hw);
            // a page may start in the middle of an element, so the page of the element's first word may differ by one near a page break
            boolean ok = e >= 0 && (ap == tp || ap == pageOfWord(Math.max(0, elEndW[e])));
            if (ok) right++; else System.out.printf("   at %.0f s: said page %d, truth page %d%n", p, ap + 1, tp + 1);
        }
        System.out.printf("   answered %d of %d, correct %d; time median %.0f s, 95th %.0f s%n", answered, trials, right, pct(lat, .5), pct(lat, .95));
        check(answered == trials, "Find page answers every time (" + (trials - answered) + " unanswered)");
        check(right >= answered, "Find page names the right page every time (" + (answered - right) + " wrong)");
        check(pct(lat, 0.95) <= 40, String.format("Find page answers in %.0f s at the 95th percentile (limit 40 s, phone time)", pct(lat, 0.95)));
    }

    static void resume(Random r, int trials) throws Exception {
        System.out.println("\n== 5. Close the book, come back (anchors are remembered) (x" + trials + ")");
        int instant = 0; List<Double> lat = new ArrayList<>();
        for (int i = 0; i < trials; i++) {
            double p = randomSpeechTime(r, 60, duration - 600);
            Session s = new Session(p, new ArrayList<>(), new ArrayList<>());
            s.runFor(150, false);
            Session s2 = new Session(p + 20, s.aPos, s.aTime);     // reopened a bit later, same place
            for (int k = 0; k < 30 && !s2.core.ready; k++) s2.round(false);
            lat.add(s2.simTime);
            if (s2.simTime < 1) instant++;
            System.out.printf("   ready again after %.0f s (%d speech calls)%n", s2.simTime, s2.calls);
        }
        check(pct(lat, 0.9) <= 30, String.format("coming back is ready in %.0f s (90th percentile, limit 30 s)", pct(lat, 0.9)));
    }

    /**
     * The whole-book link, judged everywhere (this is what Play page, Find page and the page turns rely on after "Synced"):
     * link the whole book with the playhead paused, then compare the finished alignment with the truth at EVERY page start and at random moments,
     * then jump into the linked book at random places and listen (what the marker does in a book that is already synced).
     */
    static double nearest(Alignment al, double t, int dir) { double[] ts = al.times(); double best = -1; for (double x : ts) { if (dir < 0 && x <= t) best = x; if (dir > 0 && x >= t) return x; } return dir < 0 ? best : -1; }

    static void wholeBook(Random r, int jumpsAfter) throws Exception {
        System.out.println("\n== 6. Link the WHOLE book, then judge the link everywhere");
        Session s = new Session(3, new ArrayList<>(), new ArrayList<>());
        s.playing = false;
        int rounds = 0;
        while (!s.core.complete && !s.pl.lost && s.simTime < 6 * 3600 && rounds < 20000) { s.round(false); rounds++; }
        Alignment fin = s.snapshot();
        System.out.printf("   %s after %d speech calls (~%.0f min on the phone), %d anchors, coverage %.1f%%%s%n", s.core.complete ? "COMPLETE" : "NOT complete", s.calls, s.simTime / 60, fin.size(), s.core.coverage * 100, s.pl.lost ? ", LOST the place" : "");
        check(s.core.complete, "the whole book gets linked (complete)");
        check(s.core.coverage >= 0.97, String.format("coverage ends at %.1f%% (limit 97%%)", s.core.coverage * 100));
        // (a) every page start
        List<Double> errs = new ArrayList<>(); int judged = 0, unmapped = 0, over3 = 0; double worst = 0; int worstPg = -1;
        for (int pg = 1; pg < pageStart.length - 2; pg++) {
            int firstEl = wordEl[pageStart[pg]];
            if (!(spoken[firstEl] && (kind[firstEl].equals("sentence") || kind[firstEl].equals("heading")))) continue;   // the narrator skipped it: no truth
            judged++;
            double ts = fin.timeOfWord(pageStart[pg]);
            if (ts < 0 && pg + 1 < bi.pages) ts = fin.timeOfWord(pageStart[pg] + 12);
            if (ts < 0) { unmapped++; continue; }
            double e = Math.abs(ts - st[firstEl]);
            errs.add(e); if (e > 3) over3++; if (e > worst) { worst = e; worstPg = pg; }
        }
        System.out.printf("   page starts: %d judged, %d unmapped; error median %.2f s, 95th %.2f s, worst %.1f s (page %d); %d off by more than 3 s%n", judged, unmapped, pct(errs, .5), pct(errs, .95), worst, worstPg + 1, over3);
        check(unmapped * 100.0 / Math.max(1, judged) <= 1, String.format("%.1f%% of the pages have no start time (limit 1%%)", unmapped * 100.0 / Math.max(1, judged)));
        check(pct(errs, 0.5) <= 1.5, String.format("page start accurate to %.2f s at the median (limit 1.5 s)", pct(errs, 0.5)));
        check(pct(errs, 0.95) <= 4.0, String.format("page start accurate to %.2f s at the 95th percentile (limit 4 s)", pct(errs, 0.95)));
        // (b) which page is the narrator on, at random moments
        int n = 600, ok = 0, near = 0, wrongPg = 0, nomap = 0, listen = 0;
        for (int k = 0; k < n; k++) {
            int e = r.nextInt(st.length);
            if (!(spoken[e] && kind[e].equals("sentence"))) { k--; continue; }
            double t = st[e] + r.nextDouble() * (en[e] - st[e]);
            int tp = pageOfWord(elStartW[e]);
            int j = fin.wordAtTime(t);
            if (j >= 0 && fin.gapAt(t) > Alignment.FLIP_GAP) { listen++; continue; }   // the app does not trust probes this far apart (it listens right there instead)
            if (j < 0) { nomap++; if (verbose || nomap <= 25) System.out.printf("      unmapped at %.1f s (anchors cover %.1f..%.1f s, %d anchors; nearest before %.1f after %.1f)%n", t, fin.firstTime(), fin.times()[fin.size() - 1], fin.size(), nearest(fin, t, -1), nearest(fin, t, 1)); continue; }

            int pp = pageOfWord(j);
            if (pp == tp) { ok++; continue; }
            // a page boundary is a place where the answer may differ by a page for a moment: tolerate 3 s around it
            double bd = Math.min(Math.abs(t - st[wordEl[pageStart[tp]]]), tp + 1 < pageStart.length - 1 ? Math.abs(t - st[wordEl[pageStart[tp + 1]]]) : 1e9);
            if (bd <= 3) near++; else { wrongPg++; System.out.printf("      WRONG PAGE at %.1f s: truth page %d, alignment says page %d (word %d); gap here %.1f s%n", t, tp + 1, pp + 1, j, fin.gapAt(t)); }
        }
        System.out.printf("   which page at %d random moments: %d right, %d near a page boundary (tolerated), %d WRONG, %d unmapped, %d where the app listens instead (probes > %.0f s apart)%n", n, ok, near, wrongPg, nomap, listen, Alignment.FLIP_GAP);
        check(wrongPg == 0, "no random moment is placed on the wrong page (" + wrongPg + " wrong)");
        check(nomap * 100.0 / n <= 1, String.format("%.1f%% of the moments are unmapped (limit 1%%)", nomap * 100.0 / n));
        // (c) jump into the linked book and listen: the marker
        List<Double> first = new ArrayList<>(); int never = 0, jSamples = 0, jShown = 0, jCorrect = 0;
        for (int i = 0; i < jumpsAfter; i++) {
            double p = randomSpeechTime(r, 60, duration - 200);
            Session s2 = new Session(p, s.aPos, s.aTime);
            s2.runFor(90, true);
            jSamples += s2.samples; jShown += s2.shown; jCorrect += s2.correct;
            if (s2.firstShownAt < 0) never++; else first.add(Math.max(0, s2.firstShownAt - s2.speechDelay));
            System.out.printf("   jump to %7.1f s in the linked book: first highlight %s, shown %d%% of speech, wrong %d%n", p,
                    s2.firstShownAt < 0 ? "NEVER" : String.format("after %.0f s", Math.max(0, s2.firstShownAt - s2.speechDelay)), s2.samples == 0 ? 0 : s2.shown * 100 / s2.samples, s2.wrong);
        }
        check(never == 0, "after a jump into the linked book a highlight appears every time (" + never + " never)");
        System.out.printf("   all jumps: highlight shown %.0f%% of 90 s windows, correct %.2f%% of those%n", 100.0 * jShown / Math.max(1, jSamples), 100.0 * jCorrect / Math.max(1, jShown));
        check(jShown == 0 || 100.0 * jCorrect / jShown >= 98.5, String.format("after jumps the shown highlights are right %.2f%% of the time (limit 98.5%%)", jShown == 0 ? 0 : 100.0 * jCorrect / jShown));
        // (d) Play page in a linked book: how fast and how exact
        List<Double> perr = new ArrayList<>(), pt = new ArrayList<>(); int instant = 0, unans = 0;
        for (int i = 0; i < 10; i++) {
            int pg = 1 + r.nextInt(pageStart.length - 3);
            int firstEl = wordEl[pageStart[pg]]; int tgtEl = nextSpoken(firstEl);
            int goalW = tgtEl != firstEl ? elStartW[tgtEl] : pageStart[pg];
            double ts0 = fin.timeOfWord(goalW);
            if (ts0 >= 0 && fin.gapAt(ts0) <= SyncPlanner.GOAL_GAP) { instant++; perr.add(Math.abs(ts0 - st[tgtEl])); pt.add(0.0); continue; }   // the app answers at once
            Session s3 = new Session(randomSpeechTime(r, 60, duration - 200), s.aPos, s.aTime);
            s3.playing = false;
            s3.goal = goalW;
            double t0 = s3.simTime;
            for (int k = 0; k < 25 && s3.core.goalTime == -1; k++) s3.round(false);
            double ans = s3.core.goalTime;
            if (ans < 0) { unans++; continue; }
            perr.add(Math.abs(ans - st[tgtEl])); pt.add(s3.simTime - t0);
        }
        System.out.printf("   Play page in the linked book (10 random pages): %d answered at once, %d by a short listen, %d unanswered; error median %.2f s, 95th %.2f s; time median %.0f s, 95th %.0f s%n", instant, 10 - instant - unans, unans, pct(perr, .5), pct(perr, .95), pct(pt, .5), pct(pt, .95));
        check(unans == 0, "every page is found in the linked book (" + unans + " unanswered)");
        check(pct(perr, 0.95) <= 2.0, String.format("Play page in the linked book is right to %.2f s at the 95th percentile (limit 2 s)", pct(perr, 0.95)));
        check(pct(pt, 0.95) <= 30, String.format("Play page in the linked book takes %.0f s at the 95th percentile (limit 30 s, phone time)", pct(pt, 0.95)));
        check(pct(first, 0.9) <= 45, String.format("after a jump into the linked book the highlight is there in %.0f s (90th percentile, limit 45 s)", pct(first, 0.9)));
    }

    // ---------------------------------------------------------------- main
    public static void main(String[] a) throws Exception {
        String dir = a[0], model = a[1], driver = a[2];
        long seed = a.length > 3 ? Long.parseLong(a[3]) : 1;
        String only = a.length > 4 ? a[4] : "all";
        int scale = a.length > 5 ? Integer.parseInt(a[5]) : 1;     // 1 = quick, 3 = thorough
        verbose = "1".equals(System.getenv("E2E_VERBOSE"));
        if (System.getProperty("dn") != null) SyncPlanner.DENSE_NEAR = Double.parseDouble(System.getProperty("dn"));
        loadData(dir);
        System.out.printf("data %s: %.1f min of audio, %d elements, %d words, %d pages%n", dir, duration / 60, st.length, nWords, pageStart.length - 1);
        startDriver(driver, model, 4);
        Random r = new Random(seed);
        long t0 = System.currentTimeMillis();
        if (only.equals("all") || only.contains("play")) pressPlay(r, 4 * scale, 300);
        if (only.equals("all") || only.contains("jump")) jumps(r, 3 * scale);
        if (only.equals("all") || only.contains("page")) playPage(r, 10 * scale);
        if (only.equals("all") || only.contains("find")) findPage(r, 8 * scale);
        if (only.equals("all") || only.contains("resume")) resume(r, 3 * scale);
        if (only.equals("all") || only.contains("book")) wholeBook(r, 4 * scale);
        if (totSamples > 0) {
            System.out.println("\n== Overall while listening: " + totSamples + " seconds of speech scored");
            System.out.printf("   highlight shown %.1f%% of the time; correct %.2f%% of those; wrong (more than 0.9 s off) %d%n", 100.0 * totShown / totSamples, totShown == 0 ? 0 : 100.0 * totCorrect / totShown, totWrong);
            System.out.printf("   how far off in time when wrong or right: median %.2f s, 95th %.2f s%n", pct(allOffs, .5), pct(allOffs, .95));
            double minShown = new File(dir).getName().equals("C") ? 80 : 85;   // C: robotic voice, 12 dB noise, numbers everywhere: showing nothing beats showing a wrong sentence
            check(totShown * 100.0 / totSamples >= minShown, String.format("a highlight is shown %.1f%% of the time (limit %.0f%%)", 100.0 * totShown / totSamples, minShown));
            check(totShown == 0 || 100.0 * totCorrect / totShown >= 98.5, String.format("%.2f%% of the shown highlights are on the right sentence (limit 98.5%%)", totShown == 0 ? 0 : 100.0 * totCorrect / totShown));
        }
        System.out.printf("%n(%.0f s of laptop time)%n", (System.currentTimeMillis() - t0) / 1000.0);
        drv.destroy();
        System.out.println(failures.isEmpty() ? "ALL END-TO-END CHECKS PASSED" : failures.size() + " END-TO-END CHECKS FAILED:\n  - " + String.join("\n  - ", failures));
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
