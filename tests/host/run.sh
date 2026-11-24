#!/bin/sh
# Compiles the pure-Java classes (no Android runtime needed) and runs HostTests.
set -e
cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/taiyeb/daysleft
OUT=$(mktemp -d)
AJ=${ANDROID_HOME:-/opt/android}/platforms/android-36/android.jar
javac -cp "$AJ" -d "$OUT" $SRC/Calc.java $SRC/Store.java $SRC/PageMatcher.java $SRC/BookIndex.java $SRC/Aligner.java $SRC/Alignment.java $SRC/Version.java tests/host/HostTests.java
java -cp "$OUT:$AJ" com.taiyeb.daysleft.HostTests
