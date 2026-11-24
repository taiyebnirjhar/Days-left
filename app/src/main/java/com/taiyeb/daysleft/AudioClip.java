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
    static float[] decode(Context c, String uri, double startSec, double lenSec) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, Uri.parse(uri), null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("no audio in that file");
            ex.selectTrack(track);
            long startUs = (long) (Math.max(0, startSec) * 1e6), endUs = startUs + (long) (lenSec * 1e6);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

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
            while (!outDone && outN < mono.length - 1) {
                if (!inDone) {
                    int ib = codec.dequeueInputBuffer(10000);
                    if (ib >= 0) {
                        ByteBuffer in = codec.getInputBuffer(ib);
                        int sz = ex.readSampleData(in, 0);
                        long ts = ex.getSampleTime();
                        if (sz < 0 || ts > endUs + 500000) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ib, 0, sz, ts, 0); ex.advance(); }
                    }
                }
                int ob = codec.dequeueOutputBuffer(info, 10000);
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
            int n = Math.min(outN, (int) (lenSec * 16000));
            float[] res = new float[n];
            System.arraycopy(mono, 0, res, 0, n);
            return res;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            ex.release();
        }
    }
}
