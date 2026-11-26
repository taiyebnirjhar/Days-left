# Whisper host harness

Runs the app's vendored whisper.cpp on the build machine with the same `whisper_full` parameters as `jni.cpp`, to time
calls and to reproduce native crashes (use `-fsanitize=address` in CMAKE_CXX_FLAGS for the open crash in CLAUDE.md).

    cmake -S tests/host/whisper -B /tmp/wh -DCMAKE_BUILD_TYPE=Release && make -C /tmp/wh -j4 drv
    /tmp/wh/drv ggml-tiny.en-q5_1.bin sample.wav 4          # timing grid: audio_ctx x mode x clip length
    /tmp/wh/drv ggml-tiny.en-q5_1.bin sample.wav 4 words    # word-level timestamps (token_timestamps, max_len=1)

Findings (x86, 4 threads, tiny.en q5_1, 11 s clip): a call costs ~0.55 s whatever the clip length (the encoder always
processes a 30 s window); audio_ctx=600 gives ~1.8x but changes the text; no crash for any audio_ctx here, so the phone
crash is ARM-specific. Vosk small was slower (8x real time) than a whisper window. Spliced clips (3 x 9 s in one
window) transcribe fine; use word timestamps to tell which clip a word belongs to.
