#!/bin/sh
# Simulates the adaptive sync on a fake 9.7 h book. Usage: tests/host/simrun.sh [hours] [speech speed x real time]
set -e
cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/taiyeb/daysleft; OUT=$(mktemp -d); AJ=${ANDROID_HOME:-/opt/android}/platforms/android-36/android.jar
javac -cp "$AJ" -d "$OUT" $SRC/Calc.java $SRC/Store.java $SRC/PageMatcher.java $SRC/BookIndex.java $SRC/Aligner.java $SRC/Alignment.java $SRC/SyncPlanner.java tests/host/SyncSim.java
java -cp "$OUT:$AJ" com.taiyeb.daysleft.SyncSim "$@"
