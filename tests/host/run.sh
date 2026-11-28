#!/bin/sh
# Compiles the pure-Java classes (no Android runtime needed) and runs HostTests.
set -e
cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/taiyeb/daysleft
OUT=$(mktemp -d)
AJ=${ANDROID_HOME:-/opt/android}/platforms/android-36/android.jar
javac -cp "$AJ" -d "$OUT" $SRC/Calc.java $SRC/Store.java $SRC/PageMatcher.java $SRC/BookIndex.java $SRC/Aligner.java $SRC/Alignment.java $SRC/Version.java tests/host/HostTests.java
java -cp "$OUT:$AJ" com.taiyeb.daysleft.HostTests
javac -cp "$OUT:$AJ" -d "$OUT" $SRC/Highlight.java $SRC/MarkerTrack.java tests/host/MarkerCheck.java
java -cp "$OUT:$AJ" com.taiyeb.daysleft.MarkerCheck
javac -cp "$OUT:$AJ" -d "$OUT" $SRC/Timeline.java tests/host/TimelineCheck.java
java -cp "$OUT:$AJ" com.taiyeb.daysleft.TimelineCheck
python3 tests/host/check_services.py
