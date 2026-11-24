# Days Left (personal Android app)

Owner: Taiyeb Nirjhor, senior frontend engineer. Personal-use app, Samsung SM-M145F (Exynos 1330, 8 cores, 5.5 GB RAM), Android 15, arm64 only.
Plain Java, no androidx, all views built in code. Keep it that way. Package `com.taiyeb.daysleft`.

## Build
- `gradle assembleDebug --no-daemon -q` (AGP 8.10.1, compileSdk 36, minSdk 26, NDK 27.2.12479018, cmake 3.22.1, abi arm64-v8a only)
- Set `ANDROID_HOME` first. APK: `app/build/outputs/apk/debug/app-debug.apk`
- Bump `versionCode`/`versionName` in `app/build.gradle` and the "Days Left X.Y" label in MainActivity.java each delivery. Current: 3.17 (31).
- Dependency: `com.tom-roush:pdfbox-android:2.0.27.0`. whisper.cpp is vendored in `app/src/main/cpp/whisper` (CPU only), wrapped by `cpp/jni.cpp`.

## Features
Countdowns (status bar, notification, overlay) + Library: eye-friendly PDF reader, audiobook (m4a) playback synced to the book.
- Pins: manual page<->audio-time anchors.
- Find my page (audio -> page) and Play this page (page -> audio): instant from sync data, else probe by listening (Whisper) + PageMatcher.
- Full-book sync: `SyncService` (foreground, 30 s chunks, resumable) transcribes with whisper.cpp, `Aligner` maps words to book positions, `Alignment` stores time<->word. `BookIndex` holds words, per-word boxes, sentence ends. Reader highlights the spoken sentence and follows with page turns.
- Settings -> Debug log (`Dbg.java`): file log, copies for sharing. Records Java crashes and, on API 30+, why previous runs ended (native crashes show as CRASH_NATIVE). Use it, do not guess.

## Open problems (as of v3.17)
1. NATIVE CRASH in `Whisper.nRun` (no_timestamps + single_segment, 12 s clip) ~1.7 s after model load on the device. Removing `audio_ctx` did not fix it. v3.17 routes `Whisper.transcribe` through `nRunSegs` instead (the path the sync used successfully). UNVERIFIED on device. Real fix needs a repro: build the JNI/whisper for x86_64 on the host, run both paths with the real model (ggml-base.en-q5_1.bin from huggingface) on a 12 s clip, use gdb/ASan.
2. Sync speed: ~1x real time with the base model (ETA ~11 h for a 9.7 h book). "Faster" tiny model is ~3x quicker. Ideas: sync from the current position outward so highlighting works in minutes; check ggml ARM flags (the `GGML_CPU_ARM_ARCH` setting had no effect on the .so, dotprod already used).
3. Not verified on device: sentence-highlight box alignment, notification controls, background survival.

## Gotchas
- `getInsetsController` NPE: call immersive() only after the view is built.
- startForegroundService requires startForeground right away in onStartCommand.
- Whisper has a global abort flag (`Whisper.abort`); SyncService.stop sets it, service start clears it.
- Host tests for PageMatcher/Aligner/BookIndex were plain `javac` programs (not in repo).

## Delivery
APK + source zip to the user; he installs by sideloading. Tell him plainly what is untested.
