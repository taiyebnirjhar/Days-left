package com.taiyeb.daysleft;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try { CountdownService.sync(c); } catch (Exception ignored) {}
        try { NudgeReceiver.schedule(c); } catch (Exception ignored) {}
    }
}
