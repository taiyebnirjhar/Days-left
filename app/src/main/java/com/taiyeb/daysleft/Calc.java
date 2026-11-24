package com.taiyeb.daysleft;

import java.time.*;
import java.time.temporal.ChronoUnit;

/** All countdown maths lives here (pure java.time, no Android deps). */
final class Calc {
    LocalDate target, startDate;
    Duration rest;
    long days, hours, mins, agoDays, sinceDays, sinceHours, sinceMins;
    boolean today, passed;
    double frac;
    int end;

    static Calc of(Cd c, LocalDateTime now) {
        Calc k = new Calc();
        k.end = c.end;
        LocalDate t = LocalDate.ofEpochDay(c.target), s = LocalDate.ofEpochDay(c.start), td = now.toLocalDate();
        if (c.yearly) {
            MonthDay md = MonthDay.from(t);
            LocalDate cand = md.atYear(td.getYear());
            if (cand.isBefore(td)) cand = md.atYear(td.getYear() + 1);
            t = cand;
            s = md.atYear(t.getYear() - 1);
            k.end = 0;
        }
        k.target = t;
        k.startDate = s;
        k.rest = Duration.between(now, t.atStartOfDay());
        k.today = t.equals(td);
        k.passed = !k.today && k.rest.isNegative();
        if (k.passed) {
            k.agoDays = ChronoUnit.DAYS.between(t, td);
            Duration since = Duration.between(t.atStartOfDay(), now);
            k.sinceDays = since.toDays();
            k.sinceHours = since.toHours() % 24;
            k.sinceMins = since.toMinutes() % 60;
        } else if (!k.today) {
            k.days = k.rest.toDays();
            k.hours = k.rest.toHours() % 24;
            k.mins = k.rest.toMinutes() % 60;
        }
        long total = Duration.between(s.atStartOfDay(), t.atStartOfDay()).toMinutes();
        long done = Duration.between(s.atStartOfDay(), now).toMinutes();
        if (k.today || k.passed) k.frac = 1;
        else k.frac = total <= 0 ? 0 : Math.max(0, Math.min(1, (double) done / total));
        return k;
    }

    boolean countUp() { return passed && end == 1; }
    boolean done() { return passed && end != 1; }
    int permille() { return (int) Math.round(frac * 1000); }

    String pctText() {
        double p = frac * 100;
        return p > 0 && p < 10 ? String.format(java.util.Locale.ENGLISH, "%.1f%%", p) : Math.round(p) + "%";
    }

    static String plural(long n, String w) { return n + " " + w + (n == 1 ? "" : "s"); }

    String full() {
        if (today) return "Today is the day!";
        if (countUp()) return plural(sinceDays, "day") + " " + plural(sinceHours, "hour") + " since";
        if (passed) return "Completed";
        if (days > 0) return plural(days, "day") + " " + plural(hours, "hour") + " left";
        return plural(hours, "hour") + " " + plural(mins, "min") + " left";
    }

    String compact() {
        if (today) return "Today";
        if (countUp()) return "+" + sinceDays + "d " + sinceHours + "h " + sinceMins + "m";
        if (passed) return "Done";
        return days > 0 ? days + "d " + hours + "h " + mins + "m" : hours + "h " + mins + "m";
    }

    String agoText() { return agoDays == 0 ? "today" : plural(agoDays, "day") + " ago"; }
}
