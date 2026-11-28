package com.k2fsa.sherpa.onnx;

/** Android: the two libraries sit in jniLibs/arm64-v8a. (The original looks inside a jar for desktop use.) */
public class LibraryLoader {
    private static boolean loaded;

    static synchronized void maybeLoad() {
        if (loaded) return;
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }
}
