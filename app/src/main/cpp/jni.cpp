#include <jni.h>
#include <string>
#include <cstdio>
#include <atomic>
#include <vector>
#include "whisper.h"

static std::atomic<bool> g_abort{false};
static bool abort_cb(void *) { return g_abort.load(); }

extern "C" {

JNIEXPORT void JNICALL Java_com_taiyeb_daysleft_Whisper_nAbort(JNIEnv *, jclass, jboolean on) { g_abort = on; }

JNIEXPORT jlong JNICALL Java_com_taiyeb_daysleft_Whisper_nInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT jstring JNICALL Java_com_taiyeb_daysleft_Whisper_nRun(JNIEnv *env, jclass, jlong h, jfloatArray pcm, jint threads) {
    auto *ctx = (whisper_context *) h;
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> buf(n);
    env->GetFloatArrayRegion(pcm, 0, n, buf.data());
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "en";
    wp.translate = false;
    wp.n_threads = threads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.no_context = true;
    wp.single_segment = false;
    wp.suppress_blank = true;
    wp.temperature = 0.0f;
    wp.abort_callback = abort_cb;
    wp.no_timestamps = true;       // we only need the words, so skip timestamp decoding
    wp.single_segment = true;
    // Full 30 s window (audio_ctx 0). Shrinking it crashed whisper.cpp on this phone; the sync path never did.
    wp.audio_ctx = 0;
    if (whisper_full(ctx, wp, buf.data(), (int) buf.size()) != 0) return env->NewStringUTF("");
    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t) { out += t; out += " "; }
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL Java_com_taiyeb_daysleft_Whisper_nRunSegs(JNIEnv *env, jclass, jlong h, jfloatArray pcm, jint threads) {
    auto *ctx = (whisper_context *) h;
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> buf(n);
    env->GetFloatArrayRegion(pcm, 0, n, buf.data());
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "en";
    wp.translate = false;
    wp.n_threads = threads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.no_context = true;
    wp.suppress_blank = true;
    wp.temperature = 0.0f;
    wp.abort_callback = abort_cb;
    wp.no_timestamps = false;      // we need when each phrase was spoken
    wp.single_segment = false;
    if (whisper_full(ctx, wp, buf.data(), (int) buf.size()) != 0) return env->NewStringUTF("");
    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (!t) continue;
        char head[64];
        snprintf(head, sizeof head, "%.2f\t%.2f\t", whisper_full_get_segment_t0(ctx, i) / 100.0, whisper_full_get_segment_t1(ctx, i) / 100.0);
        out += head; out += t; out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL Java_com_taiyeb_daysleft_Whisper_nRunWords(JNIEnv *env, jclass, jlong h, jfloatArray pcm, jint threads) {
    auto *ctx = (whisper_context *) h;
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> buf(n);
    env->GetFloatArrayRegion(pcm, 0, n, buf.data());
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "en";
    wp.translate = false;
    wp.n_threads = threads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.no_context = true;
    wp.suppress_blank = true;
    wp.temperature = 0.0f;
    wp.abort_callback = abort_cb;
    wp.no_timestamps = false;
    wp.single_segment = false;
    wp.token_timestamps = true;    // one segment per word, each with its own time
    wp.max_len = 1;
    wp.split_on_word = true;
    if (whisper_full(ctx, wp, buf.data(), (int) buf.size()) != 0) return env->NewStringUTF("");
    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (!t) continue;
        char head[64];
        snprintf(head, sizeof head, "%.2f\t%.2f\t", whisper_full_get_segment_t0(ctx, i) / 100.0, whisper_full_get_segment_t1(ctx, i) / 100.0);
        out += head; out += t; out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_com_taiyeb_daysleft_Whisper_nFree(JNIEnv *, jclass, jlong h) {
    if (h) whisper_free((whisper_context *) h);
}

}
