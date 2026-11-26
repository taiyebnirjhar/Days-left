#!/bin/sh
# End-to-end test of the audio <-> book sync. Usage:  tests/e2e/run.sh [scale] [scenarios]
#   scale: 1 = quick (about 10 minutes per book), 3 = thorough.   scenarios: all | play | jump | page | find | resume
# First run downloads the speech model, two voices and a book corpus (about 140 MB) and builds three test audiobooks
# (about 20 minutes); later runs reuse them from $E2E_CACHE (default ~/.cache/daysleft-e2e).
# Needs: python3 + numpy + piper-tts, ffmpeg, espeak-ng, cmake + a C++ compiler, JDK 17, ANDROID_HOME (for android.jar only).
set -e
cd "$(dirname "$0")/../.."
CACHE=${E2E_CACHE:-$HOME/.cache/daysleft-e2e}
SCALE=${1:-1}
WHAT=${2:-all}
mkdir -p "$CACHE"
AJ=${ANDROID_HOME:-/opt/android}/platforms/android-36/android.jar
for t in python3 ffmpeg espeak-ng cmake java javac; do command -v $t >/dev/null || { echo "missing tool: $t"; exit 2; }; done
python3 -c "import numpy, piper" 2>/dev/null || { echo "missing python packages: pip install numpy piper-tts"; exit 2; }

# ---- assets
[ -f "$CACHE/tiny.bin" ] || curl -sL -o "$CACHE/tiny.bin" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin
for v in lessac:voice ryan:voice2; do
  n=${v%%:*}; f=${v##*:}
  [ -f "$CACHE/$f.onnx" ] || { curl -sL -o "$CACHE/$f.onnx" https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/$n/medium/en_US-$n-medium.onnx
                              curl -sL -o "$CACHE/$f.onnx.json" https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/$n/medium/en_US-$n-medium.onnx.json; }
done
[ -d "$CACHE/gutenberg" ] || { curl -sL -o "$CACHE/gut.zip" https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/gutenberg.zip; (cd "$CACHE" && unzip -q -o gut.zip); }

# ---- three audiobooks: a clean neural voice, another voice with more noise, and a hard robotic one (noise, numbers everywhere)
B=tests/e2e/build_corpus.py
[ -f "$CACHE/A/truth.json" ] || python3 $B --source "$CACHE/gutenberg/carroll-alice.txt"       --out "$CACHE/A" --voice "piper:$CACHE/voice.onnx"  --words 15000 --snr 28 --seed 1
[ -f "$CACHE/B/truth.json" ] || python3 $B --source "$CACHE/gutenberg/chesterton-thursday.txt" --out "$CACHE/B" --voice "piper:$CACHE/voice2.onnx" --words 12000 --snr 24 --seed 2
[ -f "$CACHE/C/truth.json" ] || python3 $B --source "$CACHE/gutenberg/austen-persuasion.txt"   --out "$CACHE/C" --voice espeak --words 6000 --snr 12 --seed 3 --numbers 0.10
for d in A B C; do python3 tests/e2e/truth_tsv.py "$CACHE/$d"; done

# ---- the speech model, built from the app's own copy of whisper.cpp
[ -x "$CACHE/drv" ] || { cmake -S tests/host/whisper -B "$CACHE/whisper-build" -DCMAKE_BUILD_TYPE=Release >/dev/null && make -C "$CACHE/whisper-build" -j4 drv >/dev/null && cp "$CACHE/whisper-build/drv" "$CACHE/drv"; }

# ---- the app's own code, compiled for the laptop
SRC=app/src/main/java/com/taiyeb/daysleft
OUT=$(mktemp -d)
javac -cp "$AJ" -d "$OUT" $SRC/PageMatcher.java $SRC/BookIndex.java $SRC/Aligner.java $SRC/Alignment.java $SRC/SyncPlanner.java $SRC/SyncCore.java $SRC/WordSplit.java $SRC/Highlight.java tests/e2e/E2E.java

bad=0
for d in A B C; do
  echo; echo "################ audiobook $d"
  java -cp "$OUT:$AJ" com.taiyeb.daysleft.E2E "$CACHE/$d" "$CACHE/tiny.bin" "$CACHE/drv" 1 "$WHAT" "$SCALE" || bad=1
done
exit $bad
