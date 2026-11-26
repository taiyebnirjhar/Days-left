package com.taiyeb.daysleft;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Cuts a few seconds out of an audio file as 16 kHz mono samples, the form the speech model wants.
 *
 * Opening a 10 hour m4a (parsing its index through the file provider) takes about 10 seconds on the phone, so the opened
 * file and its codec are kept (three of them, so the three pieces of one speech call can be decoded side by side), and
 * {@link #warm} opens them in the background while the model downloads or the book's text is read.
 */
class AudioClip {
    private static final int SLOTS = 3;
    private static final Dec[] POOL = new Dec[SLOTS];

    private static synchronized Dec slot(int i) {
        if (POOL[i] == null) POOL[i] = new Dec();
        return POOL[i];
    }

    /** Opens the audio now (in the background) so the first real call does not wait for it. */
    static void warm(Context c, String uri) {
        final Context app = c.getApplicationContext();
        for (int i = 0; i < SLOTS; i++) {
            final int k = i;
            Thread t = new Thread(() -> {
                try { slot(k).open(app, uri); Dbg.i("Audio", "opened for decoding (slot " + k + ")"); }
                catch (Exception e) { Dbg.w("Audio", "warm-up failed: " + e); }
            }, "audio-warm-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    /** Gives the files back. */
    static void closeCache() {
        for (int i = 0; i < SLOTS; i++) { Dec d; synchronized (AudioClip.class) { d = POOL[i]; } if (d != null) d.close(); }
    }

    static float[] decode(Context c, String uri, double startSec, double lenSec) throws Exception {
        return slot(0).decode(c.getApplicationContext(), uri, startSec, lenSec);
    }

    /** Decodes several pieces at the same time. */
    static float[][] decodeMany(Context c, String uri, double[] start, double[] len) throws Exception {
        final int n = start.length;
        final float[][] out = new float[n][];
        final Exception[] err = new Exception[1];
        final Context app = c.getApplicationContext();
        Thread[] th = new Thread[n];
        for (int i = 1; i < n && i < SLOTS; i++) {
            final int k = i;
            th[k] = new Thread(() -> {
                try { out[k] = slot(k).decode(app, uri, start[k], len[k]); } catch (Exception e) { err[0] = e; }
            }, "audio-dec-" + k);
            th[k].start();
        }
        try { out[0] = slot(0).decode(app, uri, start[0], len[0]); } catch (Exception e) { err[0] = e; }
        for (int i = 1; i < n; i++) {
            if (th[i] != null) { try { th[i].join(); } catch (InterruptedException ignored) {} }
            else { try { out[i] = slot(0).decode(app, uri, start[i], len[i]); } catch (Exception e) { err[0] = e; } }
        }
        if (err[0] != null) throw err[0];
        return out;
    }

    /** One opened file with its codec. */
    private static final class Dec {
        private MediaExtractor ex;
        private String uri = "";
        private MediaFormat fmt;
        private MediaCodec codec;

        synchronized void close() {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            codec = null;
            try { if (ex != null) ex.release(); } catch (Exception ignored) {}
            ex = null; uri = ""; fmt = null;
        }

        synchronized void open(Context c, String u) throws Exception {
            if (ex != null && u.equals(uri)) return;
            close();
            MediaExtractor e = new MediaExtractor();
            try {
                e.setDataSource(c, Uri.parse(u), null);
                int tr = -1;
                MediaFormat f = null;
                for (int i = 0; i < e.getTrackCount(); i++) {
                    MediaFormat tf = e.getTrackFormat(i);
                    String mime = tf.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { tr = i; f = tf; break; }
                }
                if (tr < 0) throw new Exception("no audio in that file");
                e.selectTrack(tr);
                ex = e; uri = u; fmt = f;
            } catch (Exception x) { e.release(); throw x; }
        }

        synchronized float[] decode(Context c, String u, double startSec, double lenSec) throws Exception {
            try {
                return decodeOnce(c, u, startSec, lenSec);
            } catch (Exception e) {
                // the kept file/codec may be in a bad state: start again from scratch, once
                Dbg.w("Audio", "decode failed (" + e + "), retrying with a fresh decoder");
                close();
                return decodeOnce(c, u, startSec, lenSec);
            }
        }

        private float[] decodeOnce(Context c, String u, double startSec, double lenSec) throws Exception {
            boolean ok = false;
            try {
                open(c, u);
                long startUs = (long) (Math.max(0, startSec) * 1e6), endUs = startUs + (long) (lenSec * 1e6);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                if (codec == null) {
                    MediaCodec k = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                    k.configure(fmt, null, null, 0);
                    k.start();
                    codec = k;
                } else {
                    codec.flush();   // same codec, new position: much cheaper than building one per clip
                }

                int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                float[] mono = new float[(int) (lenSec * 16000) + 16000];
                int outN = 0;
                double pos = 0;                 // position in source samples (mono), for resampling
                double step = rate / 16000.0;   // source samples per output sample
                double acc = 0; int accN = 0;   // box filter accumulator
                double nextOut = step;          // source position at which the next output sample is complete
                boolean inDone = false, outDone = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                final long deadline = System.currentTimeMillis() + 20000;
                while (!outDone && outN < mono.length - 1) {
                    if (System.currentTimeMillis() > deadline) throw new Exception("decoding timed out");
                    int fed = 0;
                    while (!inDone && fed < 16) {
                        int ib = codec.dequeueInputBuffer(fed == 0 ? 1000 : 0);
                        if (ib < 0) break;
                        ByteBuffer in = codec.getInputBuffer(ib);
                        int sz = ex.readSampleData(in, 0);
                        long ts = ex.getSampleTime();
                        if (sz < 0 || ts > endUs + 500000) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ib, 0, sz, ts, 0); ex.advance(); }
                        fed++;
                    }
                    int wait = fed > 0 ? 0 : 2000;   // nothing to feed: give the codec a moment
                    while (!outDone && outN < mono.length - 1) {
                        int ob = codec.dequeueOutputBuffer(info, wait);
                        wait = 0;
                        if (ob == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                        if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat of = codec.getOutputFormat();
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) { rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); step = rate / 16000.0; nextOut = pos + step; }
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        } else if (ob >= 0) {
                            ByteBuffer out = codec.getOutputBuffer(ob);
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            java.nio.ShortBuffer sb = out.order(ByteOrder.nativeOrder()).asShortBuffer();
                            int frames = sb.remaining() / ch;
                            // skip frames that come before the wanted start (the decoder starts at the previous sync point)
                            long frameUs0 = info.presentationTimeUs;
                            int skip = 0;
                            if (frameUs0 < startUs) skip = (int) Math.min(frames, (startUs - frameUs0) * (long) rate / 1000000L);
                            for (int f = 0; f < frames && outN < mono.length - 1; f++) {
                                float v = 0;
                                for (int k = 0; k < ch; k++) v += sb.get(f * ch + k);
                                if (f < skip) continue;
                                v /= (ch * 32768f);
                                acc += v; accN++; pos += 1;
                                if (pos >= nextOut) { mono[outN++] = (float) (acc / accN); acc = 0; accN = 0; nextOut += step; }
                            }
                            codec.releaseOutputBuffer(ob, false);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                            if (outN >= (int) (lenSec * 16000)) outDone = true;
                        }
                    }
                }
                int n = Math.min(outN, (int) (lenSec * 16000));
                float[] res = new float[n];
                System.arraycopy(mono, 0, res, 0, n);
                ok = true;
                return res;
            } finally {
                if (!ok) close();   // something went wrong: next call opens the file fresh
            }
        }
    }
}
