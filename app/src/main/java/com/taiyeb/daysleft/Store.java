package com.taiyeb.daysleft;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

final class Cd {
    int id, color, end, kind, years;
    String label = "", emoji = "⏳";
    long target, start;
    boolean yearly, active = true;

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("label", label).put("emoji", emoji).put("target", target)
                .put("start", start).put("yearly", yearly).put("active", active).put("color", color).put("end", end).put("kind", kind).put("years", years);
    }

    static Cd from(JSONObject o) {
        Cd c = new Cd();
        c.id = o.optInt("id"); c.label = o.optString("label"); c.emoji = o.optString("emoji", "⏳");
        c.target = o.optLong("target"); c.start = o.optLong("start"); c.yearly = o.optBoolean("yearly");
        c.active = o.optBoolean("active", true); c.color = o.optInt("color"); c.end = o.optInt("end"); c.kind = o.optInt("kind"); c.years = o.optInt("years", 75);
        return c;
    }
}

final class Store {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("s", 0); }

    static List<Cd> load(Context c) {
        List<Cd> out = new ArrayList<>();
        String js = sp(c).getString("list", null);
        if (js == null) {
            SharedPreferences old = c.getSharedPreferences("d", 0);
            if (old.contains("day")) {
                Cd x = new Cd();
                x.id = 1;
                x.label = old.getString("label", "");
                if (x.label.isEmpty()) x.label = "Countdown";
                x.emoji = "🎯";
                x.target = old.getLong("day", LocalDate.now().toEpochDay());
                x.start = LocalDate.now().toEpochDay();
                x.active = old.getBoolean("on", true);
                out.add(x);
                save(c, out);
            }
            return out;
        }
        try {
            JSONArray a = new JSONArray(js);
            for (int i = 0; i < a.length(); i++) out.add(Cd.from(a.getJSONObject(i)));
        } catch (JSONException ignored) {}
        return out;
    }

    static void save(Context c, List<Cd> l) {
        JSONArray a = new JSONArray();
        try { for (Cd d : l) a.put(d.toJson()); } catch (JSONException ignored) {}
        sp(c).edit().putString("list", a.toString()).apply();
    }

    static int nextId(List<Cd> l) { int m = 0; for (Cd d : l) m = Math.max(m, d.id); return m + 1; }
    static boolean flag(Context c, String k, boolean def) { return sp(c).getBoolean(k, def); }
    static void setFlag(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    static int icon(Context c) { int s = sp(c).getInt("icon", 1); return s == 0 ? 0 : 1; }
    static int num(Context c, String k, int d) { return sp(c).getInt(k, d); }
    static void setNum(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
}
