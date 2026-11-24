package com.taiyeb.daysleft;

import android.app.*;
import android.service.notification.StatusBarNotification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.telephony.TelephonyManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.*;
import android.widget.TextView;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CountdownService extends Service {
    static final String CH = "countdown";
    static final int[] ACC = {0xFF7C9CFF, 0xFFFF8A65, 0xFF4DD0A1, 0xFFFFC857, 0xFFE57CD8, 0xFF5CD2E6};
    final Handler h = new Handler(Looper.getMainLooper());
    TextView ov;
    BroadcastReceiver rx;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            updateAll();
            h.postDelayed(this, 60000 - (System.currentTimeMillis() % 60000) + 200);
        }
    };

    static void sync(Context c) {
        boolean any = false;
        for (Cd d : Store.load(c)) if (d.active) any = true;
        Intent i = new Intent(c, CountdownService.class);
        if (any) c.startForegroundService(i); else c.stopService(i);
    }

    static int nid(Cd c) { return 100 + c.id; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int sid) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Countdown", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null); ch.enableVibration(false); ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        nm.createNotificationChannel(new NotificationChannel("done", "Countdown finished", NotificationManager.IMPORTANCE_HIGH));

        List<Cd> act = new ArrayList<>();
        for (Cd d : Store.load(this)) if (d.active) act.add(d);
        if (act.isEmpty()) {
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i != null && "REPOST".equals(i.getAction())) {
            updateAll();
            return START_STICKY;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        startForeground(nid(act.get(0)), build(act.get(0)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        Set<Integer> keep = new HashSet<>();
        for (Cd d : act) keep.add(nid(d));
        for (StatusBarNotification n : nm.getActiveNotifications())
            if (!keep.contains(n.getId()) && n.getId() < 5000) nm.cancel(n.getId());
        registerStatusWatcher();
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        if (rx != null) { try { unregisterReceiver(rx); } catch (Exception ignored) {} rx = null; }
        removeOverlay();
        getSystemService(NotificationManager.class).cancelAll();
        super.onDestroy();
    }

    void updateAll() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        List<Cd> all = Store.load(this);
        boolean changed = false;
        LocalDateTime now = LocalDateTime.now();
        for (Cd d : all) {
            if (!d.active) continue;
            Calc k = Calc.of(d, now);
            if (k.passed && d.end == 2) { d.active = false; changed = true; nm.cancel(nid(d)); continue; }
            nm.notify(nid(d), build(d));
            if (k.today) alertToday(nm, d, k);
        }
        if (changed) {
            Store.save(this, all);
            onStartCommand(null, 0, 0);
            return;
        }
        updateOverlay();
    }

    void alertToday(NotificationManager nm, Cd d, Calc k) {
        String key = "al_" + d.id;
        long day = k.target.toEpochDay();
        if (Store.sp(this).getLong(key, -1) == day) return;
        Store.sp(this).edit().putLong(key, day).apply();
        Notification n = new Notification.Builder(this, "done")
                .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_fg))
                .setContentTitle("🎉 " + d.label)
                .setContentText("Today is the day!")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
        nm.notify(5000 + d.id, n);
    }

    boolean overlayOn() { return Store.flag(this, "ov", false) && Settings.canDrawOverlays(this); }

    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    void removeOverlay() {
        if (ov != null) {
            try { getSystemService(WindowManager.class).removeView(ov); } catch (Exception ignored) {}
            ov = null;
        }
    }

    static String ovText(Calc k) {
        return k.today ? "Today" : k.countUp() ? "+" + k.sinceDays + "d " + k.sinceHours + "h" : k.passed ? "Done"
                : k.days > 0 ? k.days + "d " + k.hours + "h" : k.hours + "h " + k.mins + "m";
    }

    String plainOf(List<Cd> act, LocalDateTime now, int shown, int total) {
        return build(act, now, shown, total, 0, Color.WHITE).toString();
    }

    SpannableStringBuilder build(List<Cd> act, LocalDateTime now, int shown, int total, int colMode, int base) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < shown; i++) {
            Cd c = act.get(i);
            if (i > 0) {
                int st = sb.length();
                sb.append("  \u2022  ");
                sb.setSpan(new ForegroundColorSpan(base), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int st = sb.length();
            sb.append(ovText(Calc.of(c, now)));
            int col = colMode == 2 ? ACC[c.color % ACC.length] : base;
            sb.setSpan(new ForegroundColorSpan(col), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (shown < act.size()) {
            int st = sb.length();
            sb.append(" +" + (act.size() - shown));
            sb.setSpan(new ForegroundColorSpan(base), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    void updateOverlay() {
        LocalDateTime now = LocalDateTime.now();
        List<Cd> act = new ArrayList<>();
        for (Cd d : Store.load(this)) if (d.active) act.add(d);
        if (act.isEmpty() || !overlayOn()) { removeOverlay(); return; }
        act.sort((x, y) -> {
            Calc p = Calc.of(x, now), q = Calc.of(y, now);
            if (p.passed != q.passed) return p.passed ? 1 : -1;
            return Long.compare(p.rest.toMinutes(), q.rest.toMinutes());
        });
        int colMode = Store.num(this, "ovColor", 2);
        int base = colMode == 1 ? Color.BLACK : Color.WHITE;
        SpannableStringBuilder sb = null;
        int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int sbH = sbId > 0 ? getResources().getDimensionPixelSize(sbId) : dp(28);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        Paint pt = new Paint();
        pt.setTypeface(Typeface.DEFAULT_BOLD);
        pt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, dm));
        String tm = LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm"));
        int clockEnd = (int) (dp(24) + pt.measureText(tm) + dp(8));
        int leftLimit = clockEnd;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.DisplayCutout dc = getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets().getDisplayCutout();
                if (dc != null) for (Rect r : dc.getBoundingRects())
                    if (r.top < sbH * 2 && r.right > leftLimit && r.left < sw * 0.75f) leftLimit = r.right + dp(6);
            } catch (Exception ignored) {}
        }
        boolean right = Store.num(this, "ovPos", 1) == 1;
        int nudge = dp(Store.num(this, "ovNudge", 0));
        int rightMargin = Math.max(0, dp(clusterDp()) - nudge);
        int leftX = clockEnd + nudge;
        int avail = right ? sw - rightMargin - leftLimit : sw - dp(clusterDp()) - leftX;
        float sz = Store.num(this, "ovSize", 14);
        Paint mp = new Paint();
        mp.setTypeface(Typeface.DEFAULT_BOLD);
        mp.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sz, dm));
        // keep the chosen size: drop the furthest countdowns first, shrink only as a last resort
        int total = Math.min(act.size(), 4), shown = total;
        while (shown > 1 && mp.measureText(plainOf(act, now, shown, total)) > avail) shown--;
        sb = build(act, now, shown, total, colMode, base);
        String plain = sb.toString();
        while (sz > 9) {
            mp.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sz, dm));
            if (mp.measureText(plain) <= avail) break;
            sz -= 0.5f;
        }
        int y = dp(Store.num(this, "ovY", 0));
        WindowManager wm = getSystemService(WindowManager.class);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, sbH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        p.x = right ? rightMargin : leftX;
        p.y = y;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            if (ov == null) {
                ov = new TextView(this);
                ov.setGravity(Gravity.CENTER_VERTICAL);
                ov.setTypeface(Typeface.DEFAULT_BOLD);
                ov.setIncludeFontPadding(false);
                ov.setSingleLine(true);
                wm.addView(ov, p);
            }
            ov.setText(sb);
            ov.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz);
            ov.setShadowLayer(colMode == 1 ? 0f : 3f, 0, 0, 0x88000000);
            wm.updateViewLayout(ov, p);
        } catch (Exception e) { ov = null; }
    }

    /** Estimated width (dp) of everything on the right of the status bar: edge padding, battery, SIM signal, Wi-Fi, alarm, DND, silent. */
    float clusterDp() {
        float w = 25 + 27 + 8 + 6;
        try { w += 12 * Math.max(1, getSystemService(TelephonyManager.class).getPhoneCount()); } catch (Exception e) { w += 24; }
        try { if (getApplicationContext().getSystemService(WifiManager.class).isWifiEnabled()) w += 13; } catch (Exception ignored) {}
        try { if (getSystemService(AlarmManager.class).getNextAlarmClock() != null) w += 16; } catch (Exception ignored) {}
        try { if (getSystemService(NotificationManager.class).getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) w += 16; } catch (Exception ignored) {}
        try { if (getSystemService(AudioManager.class).getRingerMode() != AudioManager.RINGER_MODE_NORMAL) w += 16; } catch (Exception ignored) {}
        return w;
    }

    void registerStatusWatcher() {
        if (rx != null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        f.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { h.postDelayed(CountdownService.this::updateOverlay, 700); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(rx, f);
        } catch (Exception ignored) {}
    }

    static String fmt(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)); }

    Notification build(Cd c) {
        LocalDateTime now = LocalDateTime.now();
        Calc k = Calc.of(c, now);
        boolean pin = Store.flag(this, "pin", true);
        Duration dl = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay());
        String head = c.emoji + " " + c.label;
        String title = k.today ? "🎉 Today is the day!" : k.done() ? "✓ Completed" : k.full();
        String sub = k.done() ? "Ended " + fmt(k.target) : fmt(k.target);
        String big;
        if (k.done()) big = head + "\nCompleted " + k.agoText() + "\nThe date was " + fmt(k.target);
        else if (k.countUp()) big = head + "\nSince " + fmt(k.target) + "\n" + k.full() + "  (" + k.compact() + ")";
        else if (k.today) big = head + "\nIt's here — " + fmt(k.target) + "\nMake it count!";
        else big = head + "\nTarget: " + fmt(k.target) + "\n" + k.full() + "  (" + k.compact() + ")"
                + "\nProgress: " + k.pctText() + " since " + (c.yearly ? "last time" : fmt(k.startDate))
                + "\nToday ends in " + dl.toHours() + "h " + (dl.toMinutes() % 60) + "m";
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(Icon.createWithBitmap(overlayOn() ? Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888) : icon(k, Store.icon(this))))
                .setContentTitle(title)
                .setContentText(c.emoji + " " + c.label)
                .setSubText(sub)
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setColor(k.done() ? 0xFF4DD0A1 : ACC[c.color % ACC.length])
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Store.flag(this, "lock", true) ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_SECRET)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (Store.flag(this, "progress", true) && !k.countUp()) b.setProgress(1000, k.permille(), false);
        if (pin) b.setDeleteIntent(PendingIntent.getService(this, 1000 + c.id,
                new Intent(this, CountdownService.class).setAction("REPOST"), PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 36 && Store.flag(this, "chip", false)) {
            try {
                b.getExtras().putBoolean("android.requestPromotedOngoing", true);
                b.setShortCriticalText(k.today ? "Today" : k.passed ? "Done" : k.days > 0 ? k.days + "d" + k.hours + "h" : k.hours + "h" + k.mins + "m");
            } catch (Throwable ignored) {}
        }
        Notification n = b.build();
        if (pin) n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return n;
    }

    static String[] lines(Calc k, int style) {
        if (k.today) return new String[]{"TO", "DAY"};
        if (k.countUp()) return style == 0 ? new String[]{"+" + k.sinceDays} : new String[]{"+" + k.sinceDays + "d", k.sinceHours + "h"};
        if (k.passed) return new String[]{"OK"};
        if (style == 0) return new String[]{k.days > 0 ? String.valueOf(k.days) : k.hours + "h"};
        return k.days > 0 ? new String[]{k.days + "d", k.hours + "h"} : new String[]{k.hours + "h", k.mins + "m"};
    }

    static Bitmap icon(Calc k, int style) {
        if (k.done()) {
            Bitmap cb = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setColor(Color.WHITE); cp.setStyle(Paint.Style.STROKE); cp.setStrokeWidth(14);
            cp.setStrokeCap(Paint.Cap.ROUND); cp.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            path.moveTo(16, 50); path.lineTo(40, 74); path.lineTo(82, 24);
            new Canvas(cb).drawPath(path, cp);
            return cb;
        }
        String[] ln = lines(k, style);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        int hgt = 96, w = 96;
        float size;
        size = ln.length == 1 ? 90 : 48;
        p.setTextSize(size);
        for (String s : ln) while (p.measureText(s) > 94 && size > 12) { size -= 2; p.setTextSize(size); }
        Bitmap bm = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        p.setTextSize(size);
        Rect r = new Rect();
        for (int i = 0; i < ln.length; i++) {
            float cy = ln.length == 1 ? hgt / 2f : (i == 0 ? 25f : 71f);
            p.getTextBounds(ln[i], 0, ln[i].length(), r);
            c.drawText(ln[i], w / 2f, cy - r.exactCenterY(), p);
        }
        return bm;
    }
}
