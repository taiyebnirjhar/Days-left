package com.taiyeb.daysleft;

import android.app.Application;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keeps the last crash so the app can show it next time instead of just closing. */
public class App extends Application {
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND && !SyncService.running) AudioClip.closeCache();   // give the opened audio files back when the system is short of memory
    }

    @Override public void onCreate() {
        super.onCreate();
        Dbg.init(this);
        Dbg.logPastExits(this);
        Dbg.i("App", "started");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter w = new StringWriter();
                e.printStackTrace(new PrintWriter(w));
                String s = "Thread " + t.getName() + "\n" + w;
                Dbg.now('E', "CRASH", s);
                getSharedPreferences("s", 0).edit().putString("crash", s.length() > 6000 ? s.substring(0, 6000) : s).commit();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
