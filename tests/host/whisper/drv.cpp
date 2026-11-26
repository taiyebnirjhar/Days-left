// Host harness: same whisper_full parameters as app/src/main/cpp/jni.cpp, to time and to reproduce the crash.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include "whisper.h"
static std::vector<float> readwav(const char *p) {
    FILE *f = fopen(p, "rb"); fseek(f, 44, SEEK_SET);
    std::vector<int16_t> s; int16_t b[4096]; size_t n;
    while ((n = fread(b, 2, 4096, f)) > 0) s.insert(s.end(), b, b + n);
    fclose(f); std::vector<float> o(s.size()); for (size_t i = 0; i < s.size(); i++) o[i] = s[i] / 32768.0f; return o;
}
static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
// mode: 0 = nRun (no_timestamps, single_segment), 1 = nRunSegs
static std::string run(whisper_context *ctx, const std::vector<float> &pcm, int threads, int mode, int ac, double *secs) {
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "en"; wp.translate = false; wp.n_threads = threads;
    wp.print_progress = wp.print_realtime = wp.print_timestamps = wp.print_special = false;
    wp.no_context = true; wp.suppress_blank = true; wp.temperature = 0.0f;
    if (mode == 0) { wp.no_timestamps = true; wp.single_segment = true; } else { wp.no_timestamps = false; wp.single_segment = false; }
    if (mode == 2) { wp.token_timestamps = true; wp.max_len = 1; wp.split_on_word = true; }
    wp.audio_ctx = ac;
    double t0 = now();
    int rc = whisper_full(ctx, wp, pcm.data(), (int) pcm.size());
    *secs = now() - t0;
    if (rc != 0) return "<fail>";
    std::string out; int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; i++) {
        char h[64]; snprintf(h, sizeof h, "[%.2f-%.2f] ", whisper_full_get_segment_t0(ctx, i) / 100.0, whisper_full_get_segment_t1(ctx, i) / 100.0);
        out += h; out += whisper_full_get_segment_text(ctx, i); out += "\n";
    }
    return out;
}
int main(int argc, char **argv) {
    const char *model = argv[1], *wav = argv[2]; int threads = atoi(argv[3]);
    whisper_context_params cp = whisper_context_default_params(); cp.use_gpu = false;
    double t0 = now(); whisper_context *ctx = whisper_init_from_file_with_params(model, cp);
    printf("model load %.2f s\n", now() - t0);
    std::vector<float> full = readwav(wav);
    printf("audio %.1f s\n", full.size() / 16000.0);
    if (argc > 4) { double s; std::string o = run(ctx, full, threads, 2, 0, &s); printf("words mode %.2f s\n%s", s, o.c_str()); return 0; }
    for (int ac : {0, 1500, 1000, 600, 400}) for (int mode : {0, 1}) for (double len : {11.0, 6.0}) {
        std::vector<float> clip(full.begin(), full.begin() + std::min(full.size(), (size_t)(len * 16000)));
        double s; std::string o = run(ctx, clip, threads, mode, ac, &s);
        for (char &c : o) if (c == '\n') c = '|';
        printf("ac=%-4d mode=%d len=%4.1f  %.2f s  %s\n", ac, mode, len, s, o.c_str());
        fflush(stdout);
    }
    whisper_free(ctx);
}
