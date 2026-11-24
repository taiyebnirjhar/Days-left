package com.taiyeb.daysleft;

import android.app.*;
import android.content.*;
import android.net.Uri;
import java.time.*;

/** Posts the daily "Up next" notification and reschedules itself for the next day. */
public class NudgeReceiver extends BroadcastReceiver {
    static final String A_NUDGE = "com.taiyeb.daysleft.NUDGE", A_DONE = "com.taiyeb.daysleft.ITEM_DONE", CH = "learn";
    static final int NID = 7000;

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (A_DONE.equals(a)) {
            Learn.setDone(c, i.getIntExtra("id", -1), true);
            c.getSystemService(NotificationManager.class).cancel(NID);
            return;
        }
        if (A_NUDGE.equals(a)) post(c);
        schedule(c);
    }

    static PendingIntent alarmIntent(Context c) {
        return PendingIntent.getBroadcast(c, 1, new Intent(c, NudgeReceiver.class).setAction(A_NUDGE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(alarmIntent(c));
        if (!Store.flag(c, "nudge", true)) return;
        LocalDateTime t = LocalDate.now().atTime(Store.num(c, "nudgeH", 19), 0);
        if (!t.isAfter(LocalDateTime.now().plusMinutes(1))) t = t.plusDays(1);
        long ms = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, alarmIntent(c)); } catch (Exception ignored) {}
    }

    /** Returns false when there is nothing to do. */
    static boolean post(Context c) {
        Item it = Learn.nextUp(c);
        if (it == null) return false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Daily nudge", NotificationManager.IMPORTANCE_DEFAULT));
        Plan p = Learn.plan(c, it.planId);
        String where = p == null ? "Inbox" : p.name;
        Intent open = it.url.isEmpty() ? new Intent(c, MainActivity.class) : new Intent(Intent.ACTION_VIEW, Uri.parse(it.url));
        PendingIntent pi = PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent done = PendingIntent.getBroadcast(c, 3, new Intent(c, NudgeReceiver.class).setAction(A_DONE).putExtra("id", it.id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle("Up next: " + Learn.EMO[it.type % 6] + " " + it.title)
                .setContentText(where + " · about " + Learn.fmtMins(it.mins) + (it.url.isEmpty() ? "" : " · tap to open"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Mark done", done).build());
        nm.notify(NID, b.build());
        return true;
    }
}
