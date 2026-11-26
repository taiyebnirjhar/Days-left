package com.taiyeb.daysleft;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Cuts a few seconds out of an audio file as 16 kHz mono samples, the form the speech model wants. */
class AudioClip {
    // The opened file is kept between calls. Opening a 10 hour m4a (parsing its index through the file provider) took
    // 2 to 14 seconds each time on the phone, more than the speech model itself.
    private static MediaExtractor cEx;
    private static String cUri = "";
    private static int cTrack = -1;
    private static MediaFormat cFmt;
    private static MediaCodec cCodec;

    /** Call when a batch of decoding is over, to give the file back. */
    static synchronized void closeCache() {
        try { if (cCodec != null) { cCodec.stop(); cCodec.release(); } } catch (Exception ignored) {}
        cCodec = null;
        try { if (cEx != null) cEx.release(); } catch (Exception ignored) {}
        cEx = null; cUri = ""; cTrack = -1; cFmt = null;
    }

    static synchronized float[] decode(Context c, String uri, double startSec, double lenSec) throws Exception {
        try {
            return decodeOnce(c, uri, startSec, lenSec);
        } catch (Exception e) {
            // the kept file/codec may be in a bad state: start again from scratch, once
            Dbg.w("Audio", "decode failed (" + e + "), retrying with a fresh decoder");
            closeCache();
            return decodeOnce(c, uri, startSec, lenSec);
        }
    }

    private static float[] decodeOnce(Context c, String uri, double startSec, double lenSec) throws Exception {
        MediaCodec codec = null;
        boolean ok = false;
        try {
            if (cEx == null || !uri.equals(cUri)) {
                closeCache();
                MediaExtractor e = new MediaExtractor();
                try {
                    e.setDataSource(c, Uri.parse(uri), null);
                    int tr = -1;
                    MediaFormat f = null;
                    for (int i = 0; i < e.getTrackCount(); i++) {
                        MediaFormat tf = e.getTrackFormat(i);
                        String mime = tf.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) { tr = i; f = tf; break; }
                    }
                    if (tr < 0) throw new Exception("no audio in that file");
                    e.selectTrack(tr);
                    cEx = e; cUri = uri; cTrack = tr; cFmt = f;
                } catch (Exception x) { e.release(); throw x; }
            }
            MediaExtractor ex = cEx;
            MediaFormat fmt = cFmt;
            long startUs = (long) (Math.max(0, startSec) * 1e6), endUs = startUs + (long) (lenSec * 1e6);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (cCodec == null) {
                MediaCodec k = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                k.configure(fmt, null, null, 0);
                k.start();
                cCodec = k;
            } else {
                cCodec.flush();   // same codec, new position: much cheaper than building one per clip
            }
            codec = cCodec;

            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            float[] mono = new float[(int) (lenSec * 16000) + 16000];
            int outN = 0;
            double pos = 0;                 // position in source samples (mono), for resampling
            double step = rate / 16000.0;   // source samples per output sample
            double acc = 0; int accN = 0;   // box filter accumulator
            double nextOut = step;          // source position at which the next output sample is complete
            long srcCount = 0;              // source frames seen after startUs
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
                            acc += v; accN++; pos += 1; srcCount++;
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
            if (!ok) closeCache();   // something went wrong: next call opens the file fresh
        }
    }
}
